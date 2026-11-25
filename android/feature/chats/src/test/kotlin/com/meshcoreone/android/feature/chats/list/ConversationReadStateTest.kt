// AndroidOnly: WP-306 Upstream 122d6af read-state scenarios over the native list holder and row actions.
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.feature.chats.list.support.*
import com.meshcoreone.android.feature.chats.list.support.Fixtures.channel
import com.meshcoreone.android.feature.chats.list.support.Fixtures.contact
import com.meshcoreone.android.feature.chats.list.support.Fixtures.room
import com.meshcoreone.android.feature.chats.list.ui.ConversationAction
import com.meshcoreone.android.feature.chats.list.ui.conversationRowActions
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.junit.Test

class ConversationReadStateTest {
    @Test fun `offline explicit intent reloads all row kinds and a stale DTO can mark unread again`() = scenario {
        val deps = FakeDependencies(services = null)
        deps.connectionState.value = DeviceConnectionState.DISCONNECTED
        deps.data.contacts = listOf(contact(unreadCount = 4).copy(unreadMentionCount = 2))
        deps.data.channels = listOf(channel(unreadCount = 3).copy(unreadMentionCount = 1))
        deps.data.rooms = listOf(room(unreadCount = 2))
        val holder = ChatListStateHolder(deps, scope)
        holder.requestConversationReload()?.join()
        val captured = holder.state.value.allConversations
        holder.setFilter(ChatFilter.UNREAD)
        captured.forEach { holder.setReadState(it, true) }
        assertTrue(holder.state.value.allConversations.filtered(ChatFilter.UNREAD, "", TestStrings).isEmpty())
        assertNull(holder.state.value.errorAlert)
        captured.forEach { holder.setReadState(it, false) }
        assertEquals(listOf(1L, 1L, 1L), holder.state.value.allConversations.map { it.unreadCount })
        assertEquals(ChatFilter.UNREAD, holder.state.value.selectedFilter)
    }

    @Test fun `held save does not optimistically change list or refresh badge and failure stays visible`() = scenario {
        val deps = FakeDependencies(services = FakeServices())
        val original = contact(unreadCount = 5)
        deps.data.contacts = listOf(original)
        val holder = ChatListStateHolder(deps, scope)
        holder.requestConversationReload()?.join()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        deps.data.readStateGate = { entered.complete(Unit); release.await() }
        val action = scope.launch { holder.setReadState(Conversation.Direct(original), true) }
        entered.await()
        assertEquals(5L, holder.state.value.allConversations.single().unreadCount)
        assertTrue(requireNotNull(deps.services).calls.isEmpty())
        release.complete(Unit)
        action.join()
        assertEquals(0L, holder.state.value.allConversations.single().unreadCount)
        assertEquals(listOf("badge"), requireNotNull(deps.services).calls)
        deps.data.calls.clear()
        deps.data.failReadState = IllegalStateException("disk full")
        holder.setReadState(Conversation.Direct(original), false)
        assertEquals(0L, holder.state.value.allConversations.single().unreadCount)
        assertEquals(ChatListMessage.ReadStateSaveFailed, holder.state.value.errorAlert)
        assertTrue(deps.data.calls.isEmpty())
        assertEquals(listOf("badge"), requireNotNull(deps.services).calls)
    }

    @Test fun `cancellation propagates without reporting a save failure`() = scenario {
        val deps = FakeDependencies()
        val original = contact()
        deps.data.contacts = listOf(original)
        deps.data.failReadState = CancellationException("screen closed")
        val holder = ChatListStateHolder(deps, scope)
        assertFailsWith<CancellationException> { holder.setReadState(Conversation.Direct(original), false) }
        assertNull(holder.state.value.errorAlert)
    }

    @Test fun `mention only rows are unread but muted rows are excluded unless searching`() {
        val direct = Conversation.Direct(contact("Ada").copy(unreadMentionCount = 1))
        val channel = Conversation.Channel(channel("Mentions").copy(unreadMentionCount = 1))
        val muted = Conversation.Channel(channel.channel.withNotificationLevel(NotificationLevel.MUTED))
        val room = Conversation.Room(room(unreadCount = 1))
        assertEquals(listOf(direct, channel, room),
            listOf(direct, channel, muted, room).filtered(ChatFilter.UNREAD, "", TestStrings))
        assertEquals(listOf(muted), listOf(muted).filtered(ChatFilter.UNREAD, "Mentions", TestStrings))
        val actions = conversationRowActions(direct, false, false, false)
        assertTrue(actions.single { it.id == ConversationAction.MARK_READ.id }.enabled)
        assertFalse(actions.single { it.id == ConversationAction.DELETE.id }.enabled)
        val unread = conversationRowActions(Conversation.Direct(contact()), false, false, false)
        assertTrue(unread.single { it.id == ConversationAction.MARK_UNREAD.id }.enabled)
    }
}
