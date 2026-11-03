// PortedFrom: MC1Tests/Models/ConversationFilteringTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.feature.chats.list.support.Fixtures.channel
import com.meshcoreone.android.feature.chats.list.support.Fixtures.contact
import com.meshcoreone.android.feature.chats.list.support.Fixtures.room
import com.meshcoreone.android.feature.chats.list.support.OriginalCase
import com.meshcoreone.android.feature.chats.list.support.TestStrings
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ConversationFilteringTest {
    private fun List<Conversation>.run(filter: ChatFilter, search: String = "") = filtered(filter, search, TestStrings)

    @Test @OriginalCase("ConversationFilteringTests::all filter shows all()")
    fun `all filter shows all`() {
        val all = listOf(Conversation.Direct(contact("Alice")), Conversation.Channel(channel("General")), Conversation.Room(room("Room1")))
        assertEquals(3, all.run(ChatFilter.ALL).size)
    }

    @Test @OriginalCase("ConversationFilteringTests::filter by unread()")
    fun `filter by unread`() {
        val result = listOf(
            Conversation.Direct(contact("Alice", unreadCount = 5)), Conversation.Direct(contact("Bob", unreadCount = 0)),
            Conversation.Channel(channel("General", unreadCount = 2)),
        ).run(ChatFilter.UNREAD)
        assertEquals(2, result.size)
        assertTrue(result.all { it.unreadCount > 0 })
    }

    @Test @OriginalCase("ConversationFilteringTests::filter by direct messages()")
    fun `filter by direct messages`() {
        val result = listOf(
            Conversation.Direct(contact("Alice")), Conversation.Direct(contact("Bob")),
            Conversation.Channel(channel("General")), Conversation.Room(room("Room1")),
        ).run(ChatFilter.DIRECT_MESSAGES)
        assertEquals(2, result.size)
        assertTrue(result.all { it is Conversation.Direct })
    }

    @Test @OriginalCase("ConversationFilteringTests::filter by channels excludes rooms()")
    fun `filter by channels excludes rooms`() {
        val result = listOf(Conversation.Direct(contact("Alice")), Conversation.Channel(channel("General")), Conversation.Room(room("Room1")))
            .run(ChatFilter.CHANNELS)
        assertEquals(1, result.size)
        assertTrue(result.all { it is Conversation.Channel })
    }

    @Test @OriginalCase("ConversationFilteringTests::filter by rooms excludes channels()")
    fun `filter by rooms excludes channels`() {
        val result = listOf(
            Conversation.Direct(contact("Alice")), Conversation.Channel(channel("General")),
            Conversation.Room(room("Room1")), Conversation.Room(room("Room2")),
        ).run(ChatFilter.ROOMS)
        assertEquals(2, result.size)
        assertTrue(result.all { it is Conversation.Room })
    }

    @Test @OriginalCase("ConversationFilteringTests::search within filter()")
    fun `search within filter`() {
        val result = listOf(
            Conversation.Direct(contact("Alice", unreadCount = 1)), Conversation.Direct(contact("Bob", unreadCount = 1)),
            Conversation.Direct(contact("Charlie", unreadCount = 0)),
        ).run(ChatFilter.UNREAD, "Ali")
        assertEquals(1, result.size)
        assertEquals("Alice", result.first().displayName(TestStrings))
    }

    @Test @OriginalCase("ConversationFilteringTests::search only without filter()")
    fun `search only without filter`() {
        val result = listOf(Conversation.Direct(contact("Alice")), Conversation.Direct(contact("Bob")), Conversation.Channel(channel("Alpha")))
            .run(ChatFilter.ALL, "Al")
        assertEquals(2, result.size)
    }

    @Test @OriginalCase("ConversationFilteringTests::empty results when no match()")
    fun `empty results when no match`() {
        assertTrue(listOf(Conversation.Direct(contact("Alice")), Conversation.Direct(contact("Bob"))).run(ChatFilter.ALL, "Zzzz").isEmpty())
    }

    @Test @OriginalCase("ConversationFilteringTests::unread filter excludes muted()")
    fun `unread filter excludes muted`() {
        val result = listOf(
            Conversation.Direct(contact("Alice", unreadCount = 5, isMuted = false)),
            Conversation.Direct(contact("Bob", unreadCount = 3, isMuted = true)),
            Conversation.Channel(channel("General", unreadCount = 2, notificationLevel = NotificationLevel.ALL)),
        ).run(ChatFilter.UNREAD)
        assertEquals(2, result.size)
        assertFalse(result.any { it.displayName(TestStrings) == "Bob" })
    }

    // Android-only: search is a search-over-all even when the selected filter would exclude the row.
    @Test
    fun `search ignores the selected filter`() {
        val result = listOf(Conversation.Direct(contact("Alice")), Conversation.Channel(channel("Alpha")))
            .run(ChatFilter.ROOMS, "al")
        assertEquals(2, result.size)
    }

    @Test
    fun `search preserves Foundation expansion boundaries across every filter`() {
        val cases = listOf(
            Triple("straße", "stras", false),
            Triple("ﬁle", "f", false),
            Triple("한글", "하", false),
            Triple("ıstanbul", "istanbul", false),
            Triple("straße", "strasse", true),
            Triple("strasse", "straß", true),
            Triple("１２", "12", true),
            Triple("👨‍👩‍👧", "👨", true),
            Triple("\u1e9e", "ss", true),
            Triple("Alpha Repeater", "alpha", true),
        )
        for ((name, query, matches) in cases) {
            val conversation = Conversation.Direct(contact(name))
            val expected: List<Conversation> = if (matches) listOf(conversation) else emptyList()
            for (filter in ChatFilter.entries) {
                assertEquals(expected, listOf(conversation).run(filter, query), "$filter: $name contains $query")
            }
        }
    }
}
