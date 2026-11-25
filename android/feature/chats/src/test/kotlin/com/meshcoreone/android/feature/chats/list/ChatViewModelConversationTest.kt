// PortedFrom: MC1Tests/Views/Chats/ChatViewModelConversationTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.feature.chats.list.support.FakeDependencies
import com.meshcoreone.android.feature.chats.list.support.Fixtures.channel
import com.meshcoreone.android.feature.chats.list.support.Fixtures.contact
import com.meshcoreone.android.feature.chats.list.support.OriginalCase
import com.meshcoreone.android.feature.chats.list.support.Scenario
import com.meshcoreone.android.feature.chats.list.support.TestStrings
import com.meshcoreone.android.feature.chats.list.support.scenario
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ChatViewModelConversationTest {
    private fun Scenario.holder() = ChatListStateHolder(FakeDependencies(), scope)

    private fun ChatListStateHolder.names(rows: List<Conversation>) = rows.map { it.displayName(TestStrings) }

    @Test @OriginalCase("ChatViewModelConversationTests::favoriteConversations returns only favorites()")
    fun `favoriteConversations returns only favorites`() = scenario {
        val holder = holder()
        holder.setBuffers(contacts = listOf(contact("Alice", isFavorite = true), contact("Bob"), contact("Charlie", isFavorite = true)))
        holder.recomputeSnapshot()
        val favorites = holder.state.value.favoriteConversations
        assertEquals(2, favorites.size)
        assertTrue(favorites.all { it.isFavorite })
    }

    @Test @OriginalCase("ChatViewModelConversationTests::favoriteConversations sorts by lastMessageDate descending()")
    fun `favoriteConversations sorts by lastMessageDate descending`() = scenario {
        val holder = holder()
        holder.setBuffers(contacts = listOf(
            contact("Older", isFavorite = true, lastMessageDate = Instant.ofEpochSecond(1000)),
            contact("Newer", isFavorite = true, lastMessageDate = Instant.ofEpochSecond(2000)),
        ))
        holder.recomputeSnapshot()
        assertEquals(listOf("Newer", "Older"), holder.names(holder.state.value.favoriteConversations))
    }

    @Test @OriginalCase("ChatViewModelConversationTests::favoriteConversations returns empty when no favorites()")
    fun `favoriteConversations returns empty when no favorites`() = scenario {
        val holder = holder()
        holder.setBuffers(contacts = listOf(contact("Alice"), contact("Bob")))
        holder.recomputeSnapshot()
        assertTrue(holder.state.value.favoriteConversations.isEmpty())
    }

    @Test @OriginalCase("ChatViewModelConversationTests::nonFavoriteConversations returns only non-favorites()")
    fun `nonFavoriteConversations returns only non-favorites`() = scenario {
        val holder = holder()
        holder.setBuffers(contacts = listOf(contact("Alice", isFavorite = true), contact("Bob"), contact("Charlie")))
        holder.recomputeSnapshot()
        val others = holder.state.value.nonFavoriteConversations
        assertEquals(2, others.size)
        assertTrue(others.none { it.isFavorite })
    }

    @Test @OriginalCase("ChatViewModelConversationTests::nonFavoriteConversations sorts by lastMessageDate descending()")
    fun `nonFavoriteConversations sorts by lastMessageDate descending`() = scenario {
        val holder = holder()
        holder.setBuffers(contacts = listOf(
            contact("Older", lastMessageDate = Instant.ofEpochSecond(1000)), contact("Newer", lastMessageDate = Instant.ofEpochSecond(2000)),
        ))
        holder.recomputeSnapshot()
        assertEquals(listOf("Newer", "Older"), holder.names(holder.state.value.nonFavoriteConversations))
    }

    @Test @OriginalCase("ChatViewModelConversationTests::allConversations returns favorites first then non-favorites()")
    fun `allConversations returns favorites first then non-favorites`() = scenario {
        val holder = holder()
        val now = Instant.ofEpochSecond(1_700_000_000)
        holder.setBuffers(contacts = listOf(
            contact("NonFav", lastMessageDate = now), contact("Fav", isFavorite = true, lastMessageDate = now.minusSeconds(1000)),
        ))
        holder.recomputeSnapshot()
        assertEquals(listOf("Fav", "NonFav"), holder.names(holder.state.value.allConversations))
    }

    @Test @OriginalCase("ChatViewModelConversationTests::snapshot reflects favorite state changes after recompute()")
    fun `snapshot reflects favorite state changes after recompute`() = scenario {
        val holder = holder()
        val original = contact("Test")
        holder.setBuffers(contacts = listOf(original))
        holder.recomputeSnapshot()
        assertTrue(holder.state.value.favoriteConversations.isEmpty())
        assertEquals(1, holder.state.value.nonFavoriteConversations.size)
        holder.setBuffers(contacts = listOf(contact("Test", id = original.id, isFavorite = true)))
        holder.recomputeSnapshot()
        assertEquals(1, holder.state.value.favoriteConversations.size)
        assertTrue(holder.state.value.nonFavoriteConversations.isEmpty())
    }

    @Test @OriginalCase("ChatViewModelConversationTests::handles empty conversations array()")
    fun `handles empty conversations array`() = scenario {
        val holder = holder()
        holder.setBuffers(contacts = emptyList(), channels = emptyList(), roomSessions = emptyList())
        holder.recomputeSnapshot()
        assertTrue(holder.state.value.favoriteConversations.isEmpty())
        assertTrue(holder.state.value.nonFavoriteConversations.isEmpty())
        assertTrue(holder.state.value.allConversations.isEmpty())
    }

    @Test @OriginalCase("ChatViewModelConversationTests::handles nil lastMessageDate by sorting to end()")
    fun `handles nil lastMessageDate by sorting to end`() = scenario {
        val holder = holder()
        holder.setBuffers(contacts = listOf(
            contact("NoDate", isFavorite = true, lastMessageDate = null),
            contact("HasDate", isFavorite = true, lastMessageDate = Instant.ofEpochSecond(1_700_000_000)),
        ))
        holder.recomputeSnapshot()
        assertEquals(listOf("HasDate", "NoDate"), holder.names(holder.state.value.favoriteConversations))
    }

    /** Swipe chrome keeps the row from first reveal; a second tap must invert live mute state. */
    @Test @OriginalCase("ChatViewModelConversationTests::toggleMute with stale unmuted snapshot unmutes live row()")
    fun `toggleMute with stale unmuted snapshot unmutes live row`() = scenario {
        val holder = holder()
        val alice = contact("Alice")
        holder.setBuffers(contacts = listOf(alice))
        holder.recomputeSnapshot()
        val stale = Conversation.Direct(alice)
        holder.toggleMute(stale)
        assertTrue(holder.state.value.contacts[0].isMuted)
        holder.toggleMute(stale)
        assertTrue(!holder.state.value.contacts[0].isMuted)
    }

    /** Channel favorite is app-only, so a second tap with a stale copy must unfavorite without a device. */
    @Test @OriginalCase("ChatViewModelConversationTests::toggleFavorite with stale unfavorited snapshot unfavorites live channel()")
    fun `toggleFavorite with stale unfavorited snapshot unfavorites live channel`() = scenario {
        val dependencies = FakeDependencies()
        val holder = ChatListStateHolder(dependencies, scope)
        val general = channel("General")
        dependencies.data.channels = listOf(general)
        holder.setBuffers(channels = listOf(general))
        holder.recomputeSnapshot()
        val stale = Conversation.Channel(general)
        holder.toggleFavorite(stale)
        assertTrue(holder.state.value.channels[0].isFavorite)
        holder.toggleFavorite(stale)
        assertTrue(!holder.state.value.channels[0].isFavorite)
    }

    @Test @OriginalCase("ChatViewModelConversationTests::errorBannerMessage round-trips through setting and clearing()")
    fun `errorBannerMessage round-trips through setting and clearing`() = scenario {
        val holder = holder()
        assertNull(holder.errorBannerMessage)
        holder.errorBannerMessage = ChatListMessage.Verbatim("Test banner")
        assertEquals(ChatListMessage.Verbatim("Test banner"), holder.errorBannerMessage)
        holder.errorBannerMessage = null
        assertNull(holder.errorBannerMessage)
    }
}
