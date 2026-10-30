// AndroidOnly: WP-214 Recoverable receive-clock errors through the real poller, service graph and Room.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.sync.SyncClock
import com.meshcoreone.android.core.services.sync.SyncLogSink
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimestampRangeRecoveryTest : RoomProcessTest() {
    @Test fun directUnderflowRetainsDelivery() = runTest { assertRecovery(this, true, Instant.ofEpochSecond(-1)) }
    @Test fun directOverflowRetainsDelivery() = runTest { assertRecovery(this, true, Instant.ofEpochSecond(4_294_967_296)) }
    @Test fun channelUnderflowRetainsDelivery() = runTest { assertRecovery(this, false, Instant.ofEpochSecond(-1)) }
    @Test fun channelOverflowRetainsDelivery() = runTest { assertRecovery(this, false, Instant.ofEpochSecond(4_294_967_296)) }

    private suspend fun assertRecovery(test: TestScope, direct: Boolean, outOfRange: Instant) {
        var receiveTime = EPOCH
        val clock = object : SyncClock {
            override fun now() = receiveTime
            override fun elapsed() = test.testScheduler.currentTime.milliseconds
            override suspend fun sleep(duration: Duration) = delay(duration)
        }
        val harness = ContainerHarness(test, store, syncClock = clock)
        val peerKey = key(3)
        val secret = Bytes(ByteArray(16) { (it + 1).toByte() })
        harness.links.configureRadio = { radio ->
            radio.respond = { command ->
                if (command[0] == 0x1f.toUByte() && command[1] == 0.toUByte()) {
                    listOf(Bytes.of(0x12, 0) + Bytes.utf8("Public").paddedOrTruncated(32) + secret)
                } else null
            }
        }
        try {
            harness.manager.connect(harness.target())
            harness.eventually("timestamp recovery session ready") {
                harness.manager.connectionState == DeviceConnectionState.READY
            }
            val live = assertNotNull(harness.sessions.current)
            assertTrue(live.messagePollingService.hasMessageHandlersWired)
            live.channelService.syncChannels(live.token.radioId, 1u, usePipelinedRead = false)
            assertEquals(secret, assertNotNull(store.fetchChannel(live.token.radioId, 0u)).secret)
            val peer = ContactDTO(radioId = live.token.radioId, publicKey = peerKey, name = "Peer", lastHeardTimestamp = 0u)
            store.saveContact(peer)
            val events = CopyOnWriteArrayList<MessageEvent>()
            val observer = test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                harness.appState.messageEventStream.events().collect { events += it }
            }
            suspend fun messages(): List<MessageDTO> =
                if (direct) store.fetchMessages(EntityKey(live.token.radioId, peer.id))
                else store.fetchMessages(live.token.radioId, 0u)
            suspend fun poll(body: String, stamp: Long) {
                val frame = if (direct) {
                    Bytes.of(0x10, 20, 0, 0) + peerKey.prefix(6) + Bytes.of(0, 0) + le32(stamp) + Bytes.utf8(body)
                } else {
                    Bytes.of(0x11, 20, 0, 0, 0, 0, 0) + le32(stamp) + Bytes.utf8("Peer: $body")
                }
                val radio = harness.links.radios.last()
                val previous = radio.respond
                val frames = ConcurrentLinkedQueue(listOf(frame))
                radio.respond = { command ->
                    if (command[0] == 0x0a.toUByte()) listOf(frames.poll() ?: Bytes.of(0x0a)) else previous(command)
                }
                try { assertEquals(1L, live.messagePollingService.pollAllMessages()) }
                finally { radio.respond = previous }
            }
            try {
                receiveTime = outOfRange
                poll("original message", 1000)
                val saved = messages().single()
                assertEquals("original message", saved.text)
                assertEquals(1000u, saved.timestamp)
                assertEquals(outOfRange, saved.createdAt)
                assertFalse(saved.timestampCorrected)
                assertNull(saved.senderTimestamp)
                live.debugLogBuffer.flush()
                val diagnostic = store.fetchDebugLogEntries(Instant.EPOCH).single {
                    it.level == DebugLogLevel.ERROR && it.category == SyncLogSink.CATEGORY &&
                        it.message.startsWith("TimestampRangeError")
                }
                assertTrue(diagnostic.message.contains(outOfRange.toString()))
                assertTrue(diagnostic.message.contains("retaining the original sender timestamp"))
                val contentKey = assertNotNull(saved.deduplicationKey)
                assertTrue(contentKey.contains("-1000-"))
                harness.eventually("range-error message reaches the process consumer") {
                    events.any {
                        when (it) {
                            is MessageEvent.DirectMessageReceived -> it.message.id == saved.id
                            is MessageEvent.ChannelMessageReceived -> it.message.id == saved.id
                            else -> false
                        }
                    }
                }
                poll("original message", 1000)
                assertEquals(listOf(saved.id), messages().map { it.id })
                receiveTime = EPOCH
                poll("original message", 1000)
                assertEquals(listOf(saved.id), messages().map { it.id }, "clock recovery cannot change content dedup")
                poll("after clock recovery", 1001)
                val recovered = messages().single { it.text == "after clock recovery" }
                assertEquals(EPOCH.epochSecond.toUInt(), recovered.timestamp)
                assertEquals(1001u, recovered.senderTimestamp)
                assertTrue(recovered.timestampCorrected)
                assertEquals(DeviceConnectionState.READY, harness.manager.connectionState)
            } finally { observer.cancelAndJoin() }
        } finally { harness.container.close() }
    }
}
