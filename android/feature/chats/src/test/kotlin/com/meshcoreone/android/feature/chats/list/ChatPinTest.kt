// AndroidOnly: WP-306 Offline pins are independent of radio favorites and publish only after persistence.
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.feature.chats.list.support.*
import com.meshcoreone.android.feature.chats.list.support.Fixtures.contact
import com.meshcoreone.android.feature.chats.list.support.Fixtures.channel
import com.meshcoreone.android.feature.chats.list.support.Fixtures.room
import com.meshcoreone.android.feature.chats.list.ui.ConversationAction
import com.meshcoreone.android.feature.chats.list.ui.conversationRowActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlin.test.*
import org.junit.Test

class ChatPinTest {
    @Test fun `offline pins change all row kinds without touching radio favorites`() = scenario {
        val deps = FakeDependencies(services = null)
        deps.connectionState.value = DeviceConnectionState.DISCONNECTED
        deps.data.contacts = listOf(contact("Radio favorite", isFavorite = true, isPinned = false),
            contact("Local pin", isFavorite = false, isPinned = true))
        deps.data.channels = listOf(channel())
        deps.data.rooms = listOf(room())
        val holder = ChatListStateHolder(deps, scope)
        holder.requestConversationReload()?.join()
        assertEquals("Local pin", holder.state.value.favoriteConversations.single().displayName(TestStrings))
        val rows = holder.state.value.allConversations
        for (row in rows) {
            assertTrue(conversationRowActions(row, false, false, false)
                .single { it.id == ConversationAction.FAVORITE.id }.enabled)
            holder.setPinned(row, !row.isPinned)
        }
        assertTrue(deps.data.contacts.single { it.name == "Radio favorite" }.isFavorite)
        assertFalse(deps.data.contacts.single { it.name == "Local pin" }.isFavorite)
        assertEquals(3, holder.state.value.favoriteConversations.size)
        assertNull(holder.state.value.errorAlert)
    }

    @Test fun `held pin save failure and cancellation never publish success`() = scenario {
        val deps = FakeDependencies()
        val original = contact()
        deps.data.contacts = listOf(original)
        val holder = ChatListStateHolder(deps, scope)
        holder.requestConversationReload()?.join()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        deps.data.pinGate = { entered.complete(Unit); release.await() }
        deps.data.failFavoriteWrites = IllegalStateException("disk full")
        val action = scope.launch { holder.setPinned(Conversation.Direct(original), true) }
        entered.await()
        assertFalse(holder.state.value.allConversations.single().isPinned)
        assertEquals(original.id, holder.state.value.togglingFavoriteId)
        release.complete(Unit)
        action.join()
        assertFalse(holder.state.value.allConversations.single().isPinned)
        assertEquals(ChatListMessage.PinSaveFailed, holder.state.value.errorAlert)
        assertNull(holder.state.value.togglingFavoriteId)
        holder.showError(null)
        deps.data.pinGate = null
        deps.data.failFavoriteWrites = CancellationException("closed")
        assertFailsWith<CancellationException> { holder.setPinned(Conversation.Direct(original), true) }
        assertNull(holder.state.value.errorAlert)
        assertNull(holder.state.value.togglingFavoriteId)
        assertTrue(requireNotNull(deps.services).calls.isEmpty())
    }
}
