// AndroidOnly: WP-214 Unproved channel notification fallback through the real Room-backed session graph.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.contracts.notifications.ChannelNotificationTarget
import com.meshcoreone.android.core.contracts.notifications.NotificationAuthorizationStatus
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

class SyncChannelRoomFallbackTest : RoomProcessTest() {
    @Test
    fun heldUnprovedNotificationSurvivesObservedReplacement() = runTest {
        verifyHeldNotification(sameKeyAba = false)
    }

    @Test
    fun heldUnprovedNotificationSurvivesObservedSameKeyAba() = runTest {
        verifyHeldNotification(sameKeyAba = true)
    }

    private suspend fun TestScope.verifyHeldNotification(sameKeyAba: Boolean) {
        val delivery = HeldChannelDelivery()
        val harness = ContainerHarness(this, store, deliveryOverride = delivery)
        val original = ChannelInfo(0u, "Alpha", Bytes.of(*IntArray(16) { 0x11 }))
        val replacement = ChannelInfo(0u, "Beta", Bytes.of(*IntArray(16) { 0x22 }))
        val radioSlot = AtomicReference(original)
        val messages = ConcurrentLinkedQueue<Bytes>()
        harness.links.configureRadio = { radio ->
            radio.respond = { command ->
                when {
                    command[0] == 0x1f.toUByte() && command[1] == 0.toUByte() -> listOf(channelFrame(radioSlot.get()))
                    command[0] == 0x0a.toUByte() -> listOf(messages.poll() ?: Bytes.of(0x0a))
                    else -> null
                }
            }
        }
        var firstPoll: Deferred<Long>? = null
        var joinedPoll: Deferred<Long>? = null
        try {
            harness.manager.connect(harness.target())
            harness.settle()
            harness.assertReady()
            val graph = assertNotNull(harness.sessions.current)
            assertTrue(graph.messagePollingService.hasMessageHandlersWired)
            assertFalse(graph.notificationService.isSuppressingNotifications)
            assertTrue(graph.notificationService.requestAuthorization())
            graph.channelService.syncChannels(graph.token.radioId, 1u)
            val originalRow = assertNotNull(store.fetchChannel(graph.token.radioId, 0u))
            assertEquals(original.name, originalRow.name)
            assertEquals(original.secret, originalRow.secret)

            messages.add(messageFrame("held message", 0))
            val first = async { graph.messagePollingService.pollAllMessages() }
            firstPoll = first
            harness.eventually("first channel notification is held") { delivery.entered.isCompleted }
            val captured = delivery.entered.await()
            assertFalse(first.isCompleted)
            assertEquals("Alpha", captured.title)
            assertEquals(ChannelNotificationTarget.Unproved, channelPayload(captured).displayTarget)

            radioSlot.set(replacement)
            graph.channelService.syncChannels(graph.token.radioId, 1u)
            val replacementRow = assertNotNull(store.fetchChannel(graph.token.radioId, 0u))
            assertEquals(originalRow.id, replacementRow.id)
            assertEquals(replacement.name, replacementRow.name)
            assertEquals(replacement.secret, replacementRow.secret)

            if (sameKeyAba) {
                radioSlot.set(original)
                graph.channelService.syncChannels(graph.token.radioId, 1u)
                val restored = assertNotNull(store.fetchChannel(graph.token.radioId, 0u))
                assertEquals(originalRow.id, restored.id)
                assertEquals(original.name, restored.name)
                assertEquals(original.secret, restored.secret)
            }

            messages.add(messageFrame("later message", 1))
            val joined = async(start = CoroutineStart.UNDISPATCHED) {
                graph.messagePollingService.pollAllMessages()
            }
            joinedPoll = joined
            assertFalse(joined.isCompleted)
            delivery.release.complete(Unit)
            assertEquals(2L, first.await())
            assertEquals(2L, joined.await())

            val posted = delivery.posted.filter { it.payload is NotificationPayload.ChannelMessage }
            assertEquals(2, posted.size)
            assertEquals(captured, posted[0])
            assertEquals(if (sameKeyAba) "Alpha" else "Beta", posted[1].title)
            assertTrue(posted[0].body.contains("held message"))
            assertTrue(posted[1].body.contains("later message"))
            val firstPayload = channelPayload(posted[0])
            val laterPayload = channelPayload(posted[1])
            assertEquals(ChannelNotificationTarget.Unproved, firstPayload.displayTarget)
            assertEquals(ChannelNotificationTarget.Unproved, laterPayload.displayTarget)
            assertEquals(graph.token.radioId, firstPayload.radioId)
            assertEquals(graph.token.radioId, laterPayload.radioId)
            assertEquals(0u.toUByte(), firstPayload.channelIndex)
            assertEquals(0u.toUByte(), laterPayload.channelIndex)
            assertNotEquals(firstPayload.messageID, laterPayload.messageID)
        } finally {
            delivery.release.complete(Unit)
            withContext(NonCancellable) {
                firstPoll?.cancelAndJoin()
                joinedPoll?.cancelAndJoin()
                harness.container.close()
            }
        }
    }

    private fun channelFrame(info: ChannelInfo): Bytes =
        Bytes.of(0x12, info.index.toInt()) + Bytes.utf8(info.name).paddedOrTruncated(32) + info.secret

    private fun messageFrame(text: String, offsetSeconds: Long): Bytes =
        Bytes.of(0x11, 20, 0, 0, 0, 0, 0) +
            le32(Instant.parse("2024-01-02T03:04:05Z").epochSecond + offsetSeconds) +
            Bytes.utf8("Peer: $text")

    private fun channelPayload(request: NotificationRequest): NotificationPayload.ChannelMessage =
        assertIs<NotificationPayload.ChannelMessage>(request.payload)

    private class HeldChannelDelivery : RecordingDelivery() {
        val entered = CompletableDeferred<NotificationRequest>()
        val release = CompletableDeferred<Unit>()

        override suspend fun authorizationStatus(): NotificationAuthorizationStatus =
            NotificationAuthorizationStatus.AUTHORIZED

        override suspend fun requestAuthorization(): Boolean = true

        override suspend fun post(request: NotificationRequest): NotificationPostResult {
            if (request.payload is NotificationPayload.ChannelMessage && entered.complete(request)) {
                release.await()
            }
            return super.post(request)
        }
    }
}
