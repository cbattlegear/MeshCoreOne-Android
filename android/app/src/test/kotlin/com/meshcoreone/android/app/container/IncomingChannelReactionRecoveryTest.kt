// AndroidOnly: WP-214 Classified native channel reaction failures through polling, Room and history replay.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.state.ReactionIndexScope
import com.meshcoreone.android.app.state.ReactionIndexing
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.services.sync.SyncDataEvent
import com.meshcoreone.android.core.services.sync.SyncClock
import com.meshcoreone.android.core.services.sync.SyncLogSink
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class IncomingChannelReactionRecoveryTest : RoomProcessTest() {
    private val stamp = 1_704_067_200u
    private val receivedAt = Instant.ofEpochSecond(stamp.toLong() + 1)
    private val emoji = "\uD83D\uDC4D"
    private val raw = "@[Alice]$emoji\nb45pc4ek"
    private enum class Fault { MISS, LOOKUP, EXISTS, SAVE_BEFORE, SAVE_AFTER, SUMMARY_BEFORE, SUMMARY_AFTER }

    @Test fun genuineMissStaysSessionOwned() = runTest { recover(this, Fault.MISS) }
    @Test fun genuineMissStillRetiresWithItsSession() = runTest { recover(this, Fault.MISS, retireMiss = true) }
    @Test fun failedLookupSurvivesReconnect() = runTest { recover(this, Fault.LOOKUP) }
    @Test fun cancelledLookupSurvivesReconnect() = runTest { recover(this, Fault.LOOKUP, cancel = true) }
    @Test fun failedExistenceReadSurvivesReconnect() = runTest { recover(this, Fault.EXISTS) }
    @Test fun failedSaveBeforeCommitSurvivesReconnect() = runTest { recover(this, Fault.SAVE_BEFORE) }
    @Test fun liveRetryConsumesOldPendingWorkWithoutDuplicatePublication() = runTest {
        recover(this, Fault.SAVE_BEFORE, liveRetry = true)
    }
    @Test fun failedSaveAfterCommitReconcilesWithoutAnotherSave() = runTest { recover(this, Fault.SAVE_AFTER) }
    @Test fun cachedTargetFailedSaveAlsoSurvivesReconnect() = runTest { recover(this, Fault.SAVE_AFTER, cached = true) }
    @Test fun cancelledSaveAfterCommitReconcilesWithoutAnotherSave() = runTest { recover(this, Fault.SAVE_AFTER, cancel = true) }
    @Test fun failedSummaryBeforeCommitSurvivesReconnect() = runTest { recover(this, Fault.SUMMARY_BEFORE) }
    @Test fun failedSummaryAfterCommitStillPublishesOnRecovery() = runTest { recover(this, Fault.SUMMARY_AFTER) }

    private suspend fun recover(
        test: TestScope, fault: Fault, cancel: Boolean = false, cached: Boolean = false, retireMiss: Boolean = false,
        liveRetry: Boolean = false,
    ) {
        var target: MessageDTO? = null
        var armed = false
        var saves = 0
        var lookups = 0
        val entered = CompletableDeferred<Unit>()
        val logs = mutableListOf<String>()
        suspend fun fail() {
            if (cancel) {
                entered.complete(Unit)
                awaitCancellation()
            }
            throw IllegalStateException("injected $fault")
        }
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun findChannelMessageForReaction(
                radio: RadioId, channelIndex: UByte, parsed: ParsedReaction, localNodeName: String?,
                window: ClosedRange<UInt>, limit: Long,
            ): MessageDTO? {
                if (armed) {
                    lookups++
                    assertEquals(target?.radioId, radio)
                    assertEquals(3u.toUByte(), channelIndex)
                    assertEquals("TestNode", localNodeName)
                    if (fault == Fault.LOOKUP) fail()
                }
                return store.findChannelMessageForReaction(radio, channelIndex, parsed, localNodeName, window, limit)
            }

            override suspend fun reactionExists(message: EntityKey, senderName: String, emoji: String): Boolean {
                if (armed && fault == Fault.EXISTS && message.id == target?.id) fail()
                return store.reactionExists(message, senderName, emoji)
            }

            override suspend fun saveReaction(dto: ReactionDTO) {
                if (dto.messageID == target?.id) {
                    saves++
                    if (armed && fault == Fault.SAVE_BEFORE) fail()
                    store.saveReaction(dto)
                    if (armed && fault == Fault.SAVE_AFTER) fail()
                } else store.saveReaction(dto)
            }

            override suspend fun updateMessageReactionSummary(message: EntityKey, summary: String?) {
                if (armed && message.id == target?.id && fault == Fault.SUMMARY_BEFORE) fail()
                store.updateMessageReactionSummary(message, summary)
                if (armed && message.id == target?.id && fault == Fault.SUMMARY_AFTER) fail()
            }
        }
        val harness = ContainerHarness(
            test, store, persistenceOverride = persistence,
            syncClock = object : SyncClock {
                override fun now() = receivedAt
                override fun elapsed() = test.testScheduler.currentTime.milliseconds
                override suspend fun sleep(duration: Duration) = delay(duration)
            },
            syncLogSink = SyncLogSink { _, _, message -> logs += message },
        )
        val connectionTarget = harness.target()
        try {
            harness.manager.connect(connectionTarget)
            harness.eventually("incoming recovery session ready") {
                harness.manager.connectionState == DeviceConnectionState.READY
            }
            val live = assertNotNull(harness.sessions.current)
            store.saveChannel(live.token.radioId, ChannelInfo(3u, "General", Bytes(ByteArray(16))))
            val channel = assertNotNull(store.fetchChannel(live.token.radioId, 3u))
            target = MessageDTO(
                radioId = live.token.radioId, channelIndex = 3u, text = "Hello",
                timestamp = stamp + 10u, senderTimestamp = stamp, timestampCorrected = true,
                direction = MessageDirection.INCOMING, senderNodeName = "Alice",
            )
            val originalTarget = assertNotNull(target)
            if (fault != Fault.MISS) store.saveMessage(originalTarget)
            if (cached) live.reactionService.indexMessage(originalTarget.id, 3u, "Alice", "Hello", stamp)
            val events = mutableListOf<SyncDataEvent.ReactionReceived>()
            val collector = test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                live.syncCoordinator.dataEventBroadcaster.subscribe().collect {
                    if (it is SyncDataEvent.ReactionReceived) events += it
                }
            }
            armed = true
            val beforePoll = Instant.now()
            if (cancel) {
                val polling = test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { poll(harness, "Bob: $raw") }
                entered.await()
                polling.cancelAndJoin()
                assertTrue(polling.isCancelled)
            } else poll(harness, "Bob: $raw")
            val afterPoll = Instant.now()
            test.runCurrent()
            assertEquals(if (cached) 0 else 1, lookups)
            assertTrue(events.isEmpty())
            assertFalse(logs.any { it.startsWith("Saved reaction ") }, "failure/miss must not log success")

            armed = false
            collector.cancelAndJoin()

            // A real miss has no process-owned failure obligation; failed reads/mutations do.
            if (fault == Fault.MISS) store.saveMessage(originalTarget)
            if (fault != Fault.MISS || retireMiss) {
                harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
                assertTrue(live.isTornDown)
                harness.manager.connect(connectionTarget)
                harness.eventually("incoming recovery replacement ready") {
                    harness.manager.connectionState == DeviceConnectionState.READY && harness.sessions.current !== live
                }
            }
            val replacement = assertNotNull(harness.sessions.current)
            fun takePending() = replacement.reactionService.indexMessage(
                originalTarget.id, 3u, "Alice", "Hello", stamp, originalTarget.radioId, includeCustody = true,
            )
            if (!retireMiss && !liveRetry) {
                // Inspect after actual retirement for failures: requeueing before it would mask wrong ownership.
                val retained = takePending().single()
                assertEquals(raw, retained.rawText)
                assertEquals("Bob", retained.senderNodeName)
                assertEquals(receivedAt, retained.receivedAt)
                assertEquals("TestNode", retained.localNodeName)
                val context = assertIs<DeliveryContext.InitialSync>(retained.deliveryContext)
                assertTrue(context.anchor in beforePoll..afterPoll, "the original drain context must survive replacement")
                assertEquals(live.token.radioId, retained.radioId)
                assertEquals(3u.toUByte(), retained.channelIndex)
                if (fault == Fault.MISS) replacement.reactionService.queuePendingReaction(retained)
                else replacement.reactionService.requeuePendingReaction(retained)
            }
            val recoveryCollector = test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                replacement.syncCoordinator.dataEventBroadcaster.subscribe().collect {
                    if (it is SyncDataEvent.ReactionReceived) events += it
                }
            }
            try {
                if (liveRetry) poll(harness, "Bob: $raw")
                val indexing = ReactionIndexing(
                    replacement.reactionService, ReactionIndexScope.Channel(channel, "TestNode"),
                    onPersisted = { harness.appState.publishReactionPersisted(originalTarget.radioId, it) },
                )
                harness.container.chatsFeature.prepareTimelineMessages(listOf(originalTarget), indexing)
                harness.container.chatsFeature.prepareTimelineMessages(listOf(originalTarget), indexing)
                test.runCurrent()
                if (retireMiss) {
                    assertTrue(store.fetchReactions(EntityKey(originalTarget.radioId, originalTarget.id)).isEmpty())
                    assertTrue(events.isEmpty())
                    assertEquals(0, saves)
                    assertTrue(takePending().isEmpty())
                    return
                }
                val saved = store.fetchReactions(EntityKey(originalTarget.radioId, originalTarget.id)).single()
                assertEquals(raw, saved.rawText)
                assertEquals(receivedAt, saved.receivedAt)
                assertEquals("Bob", saved.senderName)
                assertEquals(emoji, saved.emoji)
                assertEquals("$emoji:1", store.fetchMessage(EntityKey(originalTarget.radioId, originalTarget.id))?.reactionSummary)
                assertEquals(listOf(SyncDataEvent.ReactionReceived(originalTarget.id, "$emoji:1")), events)
                assertEquals(if (fault == Fault.SAVE_BEFORE) 2 else 1, saves)
                assertTrue(takePending().isEmpty())
            } finally { recoveryCollector.cancelAndJoin() }
        } finally { harness.container.close() }
    }

    private suspend fun poll(harness: ContainerHarness, text: String) {
        val live = assertNotNull(harness.sessions.current)
        val firmware = harness.links.radios.last()
        val previous = firmware.respond
        var pending = true
        firmware.respond = { command ->
            if (command[0] == 0x0a.toUByte()) {
                if (pending) {
                    pending = false
                    listOf(Bytes.of(0x11, 20, 0, 0, 3, 0, 0) + le32(stamp.toLong()) + Bytes.utf8(text))
                } else listOf(Bytes.of(0x0a))
            } else previous(command)
        }
        try { live.messagePollingService.pollAllMessages() } finally { firmware.respond = previous }
    }
}
