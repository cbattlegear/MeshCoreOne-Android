// AndroidOnly: WP-306 Native list-action, reload and failed-send behavior (the Swift counterparts are only reachable through the app container).
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.FailedSendConversationKeys
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotSet
import com.meshcoreone.android.feature.chats.list.support.FakeDependencies
import com.meshcoreone.android.feature.chats.list.support.FakeServices
import com.meshcoreone.android.feature.chats.list.support.Fixtures
import com.meshcoreone.android.feature.chats.list.support.Fixtures.channel
import com.meshcoreone.android.feature.chats.list.support.Fixtures.contact
import com.meshcoreone.android.feature.chats.list.support.Fixtures.room
import com.meshcoreone.android.feature.chats.list.support.Scenario
import com.meshcoreone.android.feature.chats.list.support.TestStrings
import com.meshcoreone.android.feature.chats.list.support.scenario
import com.meshcoreone.android.feature.chats.list.support.settle
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Test

class ChatListActionsTest {
    private class Rig(scenario: Scenario, val deps: FakeDependencies = FakeDependencies()) {
        val holder = ChatListStateHolder(deps, scenario.scope)
        val navigated = mutableListOf<ChatRoute>()
        val cleared = mutableListOf<ChatRoute>()
        val actions = ChatListActions(holder, deps, scenario.scope, { navigated += it }, { cleared += it })
    }

    private fun message(from: UUID, text: String, direction: MessageDirection = MessageDirection.INCOMING, status: MessageStatus = MessageStatus.DELIVERED) =
        MessageDTO(radioId = Fixtures.radio, contactID = from, text = text, timestamp = 1u, direction = direction, status = status)

    @Test
    fun `delete direct conversation masks the row then confirms and reloads`() = scenario {
        val rig = Rig(this)
        val alice = contact("Alice")
        rig.deps.data.contacts = listOf(alice)
        rig.holder.requestConversationReload()?.join()
        rig.actions.deleteDirectConversation(alice)
        settle()
        assertTrue(rig.holder.state.value.allConversations.isEmpty())
        assertTrue(rig.deps.data.calls.contains("deleteDirect:${alice.id}"))
        assertEquals(listOf<ChatRoute>(ChatRoute.Direct(alice)), rig.cleared)
        assertTrue(alice.id !in rig.holder.state.value.pendingRemovalIds)
    }

    @Test
    fun `delete direct failure restores the row and surfaces the error`() = scenario {
        val rig = Rig(this)
        val alice = contact("Alice")
        rig.deps.data.contacts = listOf(alice)
        rig.deps.data.failDeleteDirect = IllegalStateException("disk")
        rig.holder.requestConversationReload()?.join()
        rig.actions.deleteDirectConversation(alice)
        settle()
        assertEquals(1, rig.holder.state.value.allConversations.size)
        assertIs<ChatListMessage.Failure>(rig.holder.state.value.errorAlert)
    }

    @Test
    fun `delete channel runs clear then notification cleanup then removes the row`() = scenario {
        val rig = Rig(this)
        val general = channel("General", index = 2u)
        rig.holder.setBuffers(channels = listOf(general)); rig.holder.recomputeSnapshot()
        rig.actions.deleteChannelConversation(general)
        settle()
        assertEquals(listOf("clear:2", "removeNotifications:2", "badge"), rig.deps.services!!.calls)
        assertTrue(rig.holder.state.value.allConversations.isEmpty())
        assertTrue(rig.holder.state.value.deletingIds.isEmpty())
    }

    @Test
    fun `delete channel failure keeps the row and offers a retry alert`() = scenario {
        val services = FakeServices().apply { failClearChannel = IllegalStateException("radio") }
        val rig = Rig(this, FakeDependencies(services = services))
        val general = channel("General")
        rig.deps.data.channels = listOf(general)
        rig.holder.setBuffers(channels = listOf(general)); rig.holder.recomputeSnapshot()
        rig.actions.deleteChannelConversation(general)
        settle()
        assertEquals(1, rig.holder.state.value.allConversations.size)
        assertEquals(general.id, rig.holder.state.value.channelDeleteFailure?.channel?.id)
        assertTrue(rig.holder.state.value.deletingIds.isEmpty())
    }

    @Test
    fun `delete channel without services reports services unavailable`() = scenario {
        val rig = Rig(this, FakeDependencies(services = null))
        val general = channel("General")
        rig.holder.setBuffers(channels = listOf(general)); rig.holder.recomputeSnapshot()
        rig.actions.deleteChannelConversation(general)
        settle()
        val failure = rig.holder.state.value.channelDeleteFailure?.message
        assertIs<ChatListMessage.Failure>(failure)
        assertEquals(ConversationActionError.ServicesUnavailable, failure.cause)
    }

    @Test
    fun `a second delete while one is pending is ignored`() = scenario {
        val services = FakeServices().apply { clearChannelGate = { awaitCancellation() } }
        val rig = Rig(this, FakeDependencies(services = services))
        val general = channel("General")
        rig.holder.setBuffers(channels = listOf(general)); rig.holder.recomputeSnapshot()
        rig.actions.deleteChannelConversation(general)
        assertTrue(rig.holder.isDeletePending(general.id))
        rig.actions.deleteChannelConversation(general)
        assertTrue(services.calls.isEmpty())
    }

    @Test
    fun `delete room failure leaves the row and clears the spinner`() = scenario {
        val services = FakeServices().apply { failLeaveRoom = IllegalStateException("radio") }
        val rig = Rig(this, FakeDependencies(services = services))
        val lounge = room("Lounge")
        rig.deps.data.rooms = listOf(lounge)
        rig.holder.setBuffers(roomSessions = listOf(lounge)); rig.holder.recomputeSnapshot()
        rig.actions.deleteRoom(lounge)
        assertEquals(1, rig.holder.state.value.allConversations.size)
        assertTrue(rig.holder.state.value.deletingIds.isEmpty())
        assertNotNull(rig.holder.state.value.errorAlert)
    }

    @Test
    fun `delete room success removes the row and cleans up`() = scenario {
        val rig = Rig(this)
        val lounge = room("Lounge")
        rig.holder.setBuffers(roomSessions = listOf(lounge)); rig.holder.recomputeSnapshot()
        rig.actions.deleteRoom(lounge)
        assertTrue(rig.holder.state.value.allConversations.isEmpty())
        assertEquals(listOf("leave:${lounge.id}", "badge"), rig.deps.services!!.calls)
    }

    @Test
    fun `delete room asks for confirmation first`() = scenario {
        val rig = Rig(this)
        val lounge = room("Lounge")
        rig.actions.handleDeleteConversation(Conversation.Room(lounge))
        assertEquals(lounge.id, rig.holder.state.value.roomToDelete?.id)
    }

    @Test
    fun `cancelling a channel delete clears the spinner without an alert`() = scenario {
        val services = FakeServices().apply { clearChannelGate = { awaitCancellation() } }
        val rig = Rig(this, FakeDependencies(services = services))
        val general = channel("General")
        rig.holder.setBuffers(channels = listOf(general)); rig.holder.recomputeSnapshot()
        rig.actions.deleteChannelConversation(general)
        assertTrue(rig.holder.isDeletePending(general.id))
        scope.coroutineContext[Job]!!.cancelChildren()
        settle()
        assertTrue(rig.holder.state.value.deletingIds.isEmpty())
        assertNull(rig.holder.state.value.channelDeleteFailure)
        assertEquals(1, rig.holder.state.value.allConversations.size)
    }

    // Real 7 s (RadioCommandTimeout.delete): the only way to observe the timeout mapping without virtual time.
    @Test
    fun `a channel delete that outlives the radio timeout becomes a timeout failure`() = scenario {
        val services = FakeServices().apply { clearChannelGate = { awaitCancellation() } }
        val rig = Rig(this, FakeDependencies(services = services))
        val general = channel("General")
        rig.deps.data.channels = listOf(general)
        rig.holder.setBuffers(channels = listOf(general)); rig.holder.recomputeSnapshot()
        rig.actions.deleteChannelConversation(general)
        withTimeout(10_000) { while (rig.holder.state.value.channelDeleteFailure == null) delay(50) }
        val failure = rig.holder.state.value.channelDeleteFailure!!.message
        assertIs<ChatListMessage.Failure>(failure)
        val timeout = assertIs<ConversationActionTimeout>(failure.cause)
        assertEquals("clearChannel", timeout.operationName)
        assertEquals(1, rig.holder.state.value.allConversations.size)
    }

    @Test
    fun `contact favorite waits for device confirmation`() = scenario {
        val rig = Rig(this)
        val alice = contact("Alice")
        rig.holder.setBuffers(contacts = listOf(alice)); rig.holder.recomputeSnapshot()
        rig.holder.toggleFavorite(Conversation.Direct(alice))
        assertEquals(listOf("fav:${alice.id}:true"), rig.deps.services!!.calls)
        assertTrue(rig.holder.state.value.contacts[0].isFavorite)
        assertNull(rig.holder.state.value.togglingFavoriteId)
    }

    @Test
    fun `channel favorite rolls back when the write fails`() = scenario {
        val rig = Rig(this)
        rig.deps.data.failFavoriteWrites = IllegalStateException("disk")
        val general = channel("General")
        rig.holder.setBuffers(channels = listOf(general)); rig.holder.recomputeSnapshot()
        rig.holder.toggleFavorite(Conversation.Channel(general))
        assertFalse(rig.holder.state.value.channels[0].isFavorite)
    }

    @Test
    fun `mute and favorite do nothing while the radio is not ready`() = scenario {
        val rig = Rig(this)
        rig.deps.connectionState.value = DeviceConnectionState.CONNECTED
        val alice = contact("Alice")
        rig.holder.setBuffers(contacts = listOf(alice)); rig.holder.recomputeSnapshot()
        rig.holder.toggleMute(Conversation.Direct(alice))
        rig.holder.toggleFavorite(Conversation.Direct(alice))
        assertFalse(rig.holder.state.value.contacts[0].isMuted)
        assertFalse(rig.holder.state.value.contacts[0].isFavorite)
    }

    @Test
    fun `room mute persists the session level and refreshes the badge`() = scenario {
        val rig = Rig(this)
        val lounge = room("Lounge")
        rig.holder.setBuffers(roomSessions = listOf(lounge)); rig.holder.recomputeSnapshot()
        rig.holder.toggleMute(Conversation.Room(lounge))
        assertEquals(NotificationLevel.MUTED, rig.holder.state.value.roomSessions[0].notificationLevel)
        assertTrue(rig.deps.data.calls.contains("slevel:${lounge.id}:MUTED"))
        assertEquals(listOf("badge"), rig.deps.services!!.calls)
    }

    @Test
    fun `reload hides repeaters blocked contacts and nameless unconfigured channels`() = scenario {
        val rig = Rig(this)
        rig.deps.data.contacts = listOf(contact("Repeater", typeRawValue = 2u), contact("Blocked", isBlocked = true), contact("Keep"))
        rig.deps.data.channels = listOf(channel("", secret = com.meshcoreone.android.core.protocol.bytes.Bytes(ByteArray(16))), channel("Real"))
        rig.holder.requestConversationReload()?.join()
        assertEquals(listOf("Keep", "Real").sorted(), rig.holder.state.value.allConversations.map { it.displayName(TestStrings) }.sorted())
        assertTrue(rig.holder.state.value.hasLoadedOnce)
    }

    @Test
    fun `reload failure shows the banner and keeps existing rows`() = scenario {
        val rig = Rig(this)
        val alice = contact("Alice")
        rig.deps.data.contacts = listOf(alice)
        rig.holder.requestConversationReload()?.join()
        rig.deps.data.failFetchConversations = IllegalStateException("db")
        rig.holder.requestConversationReload()?.join()
        assertEquals(ChatListMessage.LoadConversationsFailed, rig.holder.state.value.errorBanner)
        assertEquals(1, rig.holder.state.value.allConversations.size)
    }

    @Test
    fun `reload without a radio clears the list`() = scenario {
        val rig = Rig(this)
        rig.deps.data.contacts = listOf(contact("Alice"))
        rig.holder.requestConversationReload()?.join()
        rig.deps.currentRadioId.value = null as RadioId?
        assertNull(rig.holder.requestConversationReload())
        assertTrue(rig.holder.state.value.allConversations.isEmpty())
    }

    @Test
    fun `a pending removal masks a stale reload then self-heals once the fetch drops it`() = scenario {
        val rig = Rig(this)
        val alice = contact("Alice")
        rig.deps.data.contacts = listOf(alice)
        rig.holder.requestConversationReload()?.join()
        rig.holder.removeConversation(Conversation.Direct(alice))
        rig.holder.requestConversationReload()?.join()
        assertTrue(rig.holder.state.value.allConversations.isEmpty())
        assertTrue(alice.id in rig.holder.state.value.pendingRemovalIds)
        rig.deps.data.contacts = emptyList()
        rig.holder.requestConversationReload()?.join()
        assertTrue(rig.holder.state.value.pendingRemovalIds.isEmpty())
    }

    @Test
    fun `previews skip outgoing reactions unless failed and evict emptied direct rows`() = scenario {
        val rig = Rig(this)
        val alice = contact("Alice")
        val bob = contact("Bob")
        rig.deps.data.contacts = listOf(alice, bob)
        rig.deps.data.contactMessages = mapOf(
            alice.id to listOf(message(alice.id, "hello"), message(alice.id, "react:+1", MessageDirection.OUTGOING)),
            bob.id to listOf(message(bob.id, "bye"), message(bob.id, "react:x", MessageDirection.OUTGOING, MessageStatus.FAILED)),
        )
        rig.holder.requestConversationReload()?.join()
        assertEquals("hello", rig.holder.state.value.lastMessagePreview(alice.id))
        assertEquals("react:x", rig.holder.state.value.lastMessagePreview(bob.id))
        rig.deps.data.contactMessages = mapOf(alice.id to emptyList())
        rig.holder.loadLastMessagePreviews()
        assertNull(rig.holder.state.value.lastMessagePreview(alice.id))
        // A contact absent from the batch result is evicted too (`messages?.isEmpty ?? true` in the Swift source).
        assertNull(rig.holder.state.value.lastMessagePreview(bob.id))
    }

    @Test
    fun `failed send indicators follow the store and status events gate on an existing badge`() = scenario {
        val rig = Rig(this)
        val alice = contact("Alice")
        rig.deps.data.failedSend = FailedSendConversationKeys(contactIDs = SnapshotSet(listOf(alice.id)))
        assertFalse(rig.holder.shouldRefreshFailedSendIndicators(ChatListEvent.MESSAGE_STATUS_RESOLVED))
        assertTrue(rig.holder.shouldRefreshFailedSendIndicators(ChatListEvent.MESSAGE_FAILED))
        rig.holder.refreshFailedSendIndicators()
        assertTrue(rig.holder.state.value.conversationHasFailedSend(alice.id))
        assertTrue(rig.holder.shouldRefreshFailedSendIndicators(ChatListEvent.ROOM_MESSAGE_STATUS_UPDATED))
        assertFalse(rig.holder.shouldRefreshFailedSendIndicators(ChatListEvent.DIRECT_MESSAGE_RECEIVED))
    }

    @Test
    fun `pending navigation requests are consumed once`() = scenario {
        val rig = Rig(this)
        val alice = contact("Alice")
        rig.deps.navigation.pendingChatContact.value = alice
        rig.actions.handlePendingNavigation()
        rig.actions.handlePendingNavigation()
        assertEquals(listOf<ChatRoute>(ChatRoute.Direct(alice)), rig.navigated)
        assertNull(rig.deps.navigation.pendingChatContact.value)
    }

    @Test
    fun `opening a disconnected room asks for authentication instead of navigating`() = scenario {
        val rig = Rig(this)
        val lounge = room("Lounge", isConnected = false)
        rig.actions.open(ChatRoute.Room(lounge))
        assertTrue(rig.navigated.isEmpty())
        assertEquals(lounge.id, rig.holder.state.value.roomToAuthenticate?.id)
        rig.actions.open(ChatRoute.Room(room("Open", isConnected = true)))
        assertEquals(1, rig.navigated.size)
    }

    @Test
    fun `room taps and pending requests reach the current authentication owner synchronously`() = scenario {
        val rig = Rig(this)
        val lounge = room("Lounge", isConnected = false)
        val requests = mutableListOf<com.meshcoreone.android.core.model.RemoteNodeSessionDTO>()
        val actions = ChatListActions(
            rig.holder, rig.deps, scope, { rig.navigated += it }, { rig.cleared += it },
            requestRoomAuthentication = {
                assertNull(rig.deps.navigation.pendingRoomAuthentication.value)
                requests += it
            },
        )
        actions.open(ChatRoute.Room(lounge))
        assertEquals(listOf(lounge), requests)
        assertTrue(rig.navigated.isEmpty())
        rig.deps.navigation.pendingRoomAuthentication.value = lounge
        actions.consumePendingRoomAuthentication()
        assertEquals(listOf(lounge, lounge), requests)
        assertNull(rig.holder.state.value.roomToAuthenticate)
    }

    @Test
    fun `offline announcement needs a disconnected radio that is known`() = scenario {
        val rig = Rig(this)
        assertFalse(rig.actions.shouldAnnounceOfflineState())
        rig.deps.connectionState.value = DeviceConnectionState.DISCONNECTED
        assertTrue(rig.actions.shouldAnnounceOfflineState())
    }

    @Test
    fun `route refresh follows the latest payload and drops removed conversations`() {
        val alice = contact("Alice")
        val route = ChatRoute.Direct(alice)
        val renamed = contact("Alice", id = alice.id, nickname = "Ally")
        val refreshed = route.refreshedPayload(listOf(Conversation.Direct(renamed)))
        assertEquals((refreshed as ChatRoute.Direct).contact.displayName, "Ally")
        assertNull(route.refreshedPayload(emptyList()))
        assertEquals(route, ChatRoute.Direct(renamed))
    }
}
