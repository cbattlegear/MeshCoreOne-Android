// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatCoordinatorTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Chats/Models/ChatRenderStateTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.services.rendering.ChatRenderState
import com.meshcoreone.android.core.services.rendering.ChatWriterRole
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SharedTimelineIntegrationTest : RoomProcessTest() {
    @Test fun mountedSiblingViewModelsShareRealRoomWindowPagingAndOwnership() = runTest {
        val radio = RadioId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
        store.saveDevice(DeviceDTO(radioId = radio, publicKey = key(7), nodeName = "Radio"))
        val contact = ContactDTO(radioId = radio, publicKey = key(3), name = "Peer", lastHeardTimestamp = null)
        store.saveContact(contact)
        repeat(65) { index ->
            val date = Instant.ofEpochSecond(1_700_000_000L + index)
            store.saveMessage(MessageDTO(
                radioId = radio, contactID = contact.id, text = "message $index",
                timestamp = date.epochSecond.toUInt(), createdAt = date, sortDate = date,
                direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
            ))
        }
        val harness = ContainerHarness(this, store)
        val conversation = TimelineConversation.Direct(contact)
        val scope = CoroutineScope(currentCoroutineContext())
        val first = ChatViewModel(conversation, harness.container.chatsFeature.timeline(conversation), scope)
        var second: ChatViewModel? = null
        try {
            first.open()
            val registry = assertNotNull(harness.container.appState.chatCoordinatorRegistry)
            val coordinator = registry.coordinator(conversation.id)
            assertEquals(coordinator.messages, first.state.value.messages)
            assertEquals(50, first.state.value.totalFetchedCount)
            assertTrue(first.state.value.hasMoreMessages)
            val sibling = ChatViewModel(conversation, harness.container.chatsFeature.timeline(conversation), scope)
            second = sibling
            assertEquals(first.state.value.messages, sibling.state.value.messages)
            assertTrue(sibling.state.value.hasLoadedOnce)
            first.close()
            assertNull(coordinator.bindWriter(Any(), ChatWriterRole.PRIME), "stale close must not evict sibling")
            sibling.open()
            sibling.loadOlder()
            assertEquals(65, sibling.state.value.messages.size)
            assertEquals(sibling.state.value.messages, first.state.value.messages)
            assertEquals(coordinator.renderState.totalFetchedCount.toInt(), sibling.state.value.totalFetchedCount)
            assertEquals(coordinator.renderState.hasMoreMessages, sibling.state.value.hasMoreMessages)
            assertEquals(coordinator.renderState.isLoadingOlder, sibling.state.value.isLoadingOlder)
            assertEquals(ChatRenderState.LoadPhase.LOADED, coordinator.renderState.phase)
            assertFalse(sibling.state.value.hasMoreMessages)
            sibling.close()
            assertNotNull(coordinator.bindWriter(Any(), ChatWriterRole.PRIME), "explicit close permits prime immediately")
            assertTrue(harness.links.links.isEmpty(), "offline chat never creates a radio session")
        } finally {
            first.close()
            second?.close()
            harness.container.close()
        }
    }
}
