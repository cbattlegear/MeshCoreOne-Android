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
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import com.meshcoreone.android.feature.chats.timeline.TimelineRow
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
            assertEquals(0, assertNotNull(store.fetchContact(EntityKey(radio, peer.id))).unreadCount)

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
}
