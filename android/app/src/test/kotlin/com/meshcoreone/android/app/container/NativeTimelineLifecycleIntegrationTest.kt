// AndroidOnly: WP-303 Approved per-screen timeline adaptation over the production App adapter and real Room.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.sync.SyncClock
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import com.meshcoreone.android.feature.chats.timeline.TimelineRow
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class NativeTimelineLifecycleIntegrationTest : RoomProcessTest() {
    @Test fun closedScreenReopensFromRoomWithNewTailAndDraftWithoutKeepingASecondGraph() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val peer = ContactDTO(radioId = radio, publicKey = key(3), name = "Peer", lastHeardTimestamp = null)
        store.saveDevice(DeviceDTO(radioId = radio, publicKey = key(7), nodeName = "Saved"))
        store.saveContact(peer)
        val first = MessageDTO(radioId = radio, contactID = peer.id, text = "First",
            timestamp = 1u, createdAt = EPOCH, sortDate = EPOCH, direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED)
        store.saveMessage(first)
        val h = ContainerHarness(this, store)
        val firstScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val reopenedScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        try {
            val conversation = TimelineConversation.Direct(peer)
            val vm = ChatViewModel(conversation, h.container.chatsFeature.timeline(conversation), firstScope)
            h.settle()
            val baseline = h.appState.messageEventStream.subscriberCount
            vm.start()
            vm.open()
            h.settle()
            assertEquals(baseline + 1, h.appState.messageEventStream.subscriberCount)
            assertEquals(listOf(first.id), vm.state.value.messages.map { it.id })
            vm.updateDraft("Preserved draft")
            firstScope.cancel()
            h.settle()
            assertEquals(baseline, h.appState.messageEventStream.subscriberCount)

            val second = first.copy(id = UUID.randomUUID(), text = "Arrived while closed", timestamp = 2u,
                sortDate = EPOCH.plusSeconds(1), createdAt = EPOCH.plusSeconds(1))
            store.saveMessage(second)
            store.saveContact(peer.copy(unreadCount = 1))
            val current = assertNotNull(store.fetchContact(EntityKey(radio, peer.id)))
            val reopenedConversation = TimelineConversation.Direct(current)
            val reopened = ChatViewModel(reopenedConversation, h.container.chatsFeature.timeline(reopenedConversation), reopenedScope)
            reopened.start()
            reopened.open()
            h.settle()
            assertEquals(listOf(first.id, second.id), reopened.state.value.messages.map { it.id })
            assertEquals("Preserved draft", reopened.state.value.draft)
            assertEquals(second.id, reopened.state.value.rows.filterIsInstance<TimelineRow.UnreadDivider>().single().beforeMessageId)
            assertEquals(0L, assertNotNull(store.fetchContact(EntityKey(radio, peer.id))).unreadCount)

            val changed = second.copy(text = "Updated after reopen")
            store.saveMessage(changed)
            h.appState.messageEventStream.send(MessageEvent.MessageStatusResolved(second.id, changed.status))
            h.eventually("native timeline reads the updated Room row") {
                reopened.state.value.messages.any { it.id == second.id && it.text == changed.text }
            }
            assertEquals(0, h.sessions.created, "Offline timeline reading must not construct a radio graph")
        } finally {
            firstScope.cancel()
            reopenedScope.cancel()
            h.container.close()
        }
    }

    @Test fun stoppingAScreenOrReplacingARadioGraphDoesNotCloseTheProcessStoreOrLoseDrafts() = runTest {
        val h = ContainerHarness(this, store)
        val screenScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        try {
            val target = h.target()
            h.links.configureRadio = { it.knownContacts += contactFrame(key(3), "Peer") }
            h.manager.connect(target)
            h.settle()
            h.assertReady()
            val graph = assertNotNull(h.sessions.current)
            val peer = assertNotNull(store.fetchContact(graph.token.radioId, key(3)))
            val conversation = TimelineConversation.Direct(peer)
            val vm = ChatViewModel(conversation, h.container.chatsFeature.timeline(conversation), screenScope)
            vm.start()
            vm.open()
            vm.updateDraft("Draft across connection generations")
            screenScope.cancel()
            h.settle()
            assertSame(graph, h.sessions.current)
            assertFalse(graph.isTornDown)
            assertTrue(graph.messagePollingService.isAutoFetching)

            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            assertNotNull(store.fetchContact(graph.token.radioId, key(3)))
            h.manager.connect(target)
            h.settle()
            h.assertReady()
            val replacement = assertNotNull(h.sessions.current)
            assertEquals(graph.token.radioId, replacement.token.radioId)
            assertTrue(graph.isTornDown)
            val reopened = ChatViewModel(conversation, h.container.chatsFeature.timeline(conversation), backgroundScope)
            assertEquals("Draft across connection generations", reopened.state.value.draft)
            assertEquals(1, h.sessions.outstanding)
            assertEquals(0, graph.liveJobCount)
        } finally {
            screenScope.cancel()
            h.container.close()
        }
    }

    @Test fun actualDmPollingWhileAClosedOwnerSurvivesReopensWithTheNewTail() = runTest {
        assertClosedOwnerArrival(direct = true)
    }

    @Test fun actualChannelPollingWhileAClosedOwnerSurvivesReopensWithTheNewTail() = runTest {
        assertClosedOwnerArrival(direct = false)
    }

    private suspend fun TestScope.assertClosedOwnerArrival(direct: Boolean) {
        val syncClock = object : SyncClock {
            override fun now() = EPOCH
            override fun elapsed() = testScheduler.currentTime.milliseconds
            override suspend fun sleep(duration: Duration) = delay(duration)
        }
        val h = ContainerHarness(this, store, syncClock = syncClock)
        val closedScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val reopenedScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val peerKey = key(3)
        val secret = Bytes(ByteArray(16) { (it + 1).toByte() })
        h.links.configureRadio = { radio ->
            radio.knownContacts += contactFrame(peerKey, "Alice")
            radio.respond = { command ->
                if (command[0] == 0x1f.toUByte() && command[1] == 0.toUByte()) {
                    listOf(Bytes.of(0x12, 0) + Bytes.utf8("Test").paddedOrTruncated(32) + secret)
                } else null
            }
        }
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val graph = assertNotNull(h.sessions.current)
            graph.channelService.syncChannels(graph.token.radioId, 1u, usePipelinedRead = false)
            val peer = assertNotNull(store.fetchContact(graph.token.radioId, peerKey))
            val channel = assertNotNull(store.fetchChannel(graph.token.radioId, 0u))
            val conversation = if (direct) TimelineConversation.Direct(peer) else TimelineConversation.Channel(channel)
            val closed = ChatViewModel(conversation, h.container.chatsFeature.timeline(conversation), closedScope)
            h.settle()
            val baseline = h.appState.messageEventStream.subscriberCount
            closed.start()
            closed.open()
            h.settle()
            assertEquals(baseline + 1, h.appState.messageEventStream.subscriberCount)
            assertTrue(closed.state.value.messages.isEmpty())
            closedScope.cancel()
            h.settle()
            assertEquals(baseline, h.appState.messageEventStream.subscriberCount)

            val stamp = EPOCH.epochSecond.toUInt()
            val frame = if (direct) {
                Bytes.of(0x10, 20, 0, 0) + peerKey.prefix(6) + Bytes.of(0, 0) +
                    le32(stamp.toLong()) + Bytes.utf8("Fresh tail")
            } else {
                Bytes.of(0x11, 20, 0, 0, 0, 0, 0) + le32(stamp.toLong()) + Bytes.utf8("Alice: Fresh tail")
            }
            val frames = ConcurrentLinkedQueue(listOf(frame))
            val radio = h.links.radios.single()
            val prior = radio.respond
            radio.respond = { command ->
                if (command[0] == 0x0a.toUByte()) listOf(frames.poll() ?: Bytes.of(0x0a)) else prior(command)
            }
            try {
                assertEquals(1L, graph.messagePollingService.pollAllMessages())
            } finally { radio.respond = prior }
            h.settle()
            assertTrue(closed.state.value.messages.isEmpty(), "A cancelled screen owner cannot keep consuming arrivals")
            assertEquals(baseline, h.appState.messageEventStream.subscriberCount)
            val current = if (direct) {
                TimelineConversation.Direct(assertNotNull(store.fetchContact(EntityKey(graph.token.radioId, peer.id))))
            } else {
                TimelineConversation.Channel(assertNotNull(store.fetchChannel(graph.token.radioId, 0u)))
            }
            assertEquals(1L, current.unreadCount)
            val reopened = ChatViewModel(current, h.container.chatsFeature.timeline(current), reopenedScope)
            reopened.start()
            reopened.open()
            h.settle()
            val message = reopened.state.value.messages.single()
            assertEquals("Fresh tail", message.text)
            assertEquals(graph.token.radioId, message.radioId)
            assertEquals(stamp, message.timestamp)
            assertEquals(message.id, reopened.state.value.rows.filterIsInstance<TimelineRow.UnreadDivider>().single().beforeMessageId)
            if (!direct) assertEquals("Alice", message.senderNodeName)
            assertEquals(1, h.sessions.created)
            assertSame(graph, h.sessions.current)
            assertEquals(1, radio.readers)
            assertTrue(graph.messagePollingService.isAutoFetching)
            reopenedScope.cancel()
            h.settle()
            assertEquals(baseline, h.appState.messageEventStream.subscriberCount)
        } finally {
            closedScope.cancel()
            reopenedScope.cancel()
            h.container.close()
        }
    }
}
