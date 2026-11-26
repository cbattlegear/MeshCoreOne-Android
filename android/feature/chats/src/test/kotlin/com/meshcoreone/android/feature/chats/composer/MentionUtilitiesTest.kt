// PortedFrom: MC1Tests/Utilities/MentionUtilitiesTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/Utilities/MentionInsertionTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.feature.chats.list.support.Fixtures
import com.meshcoreone.android.feature.chats.list.support.OriginalCase
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class MentionUtilitiesTest {
    private fun contact(name: String, type: UByte = 1u): ContactDTO = Fixtures.contact(name, typeRawValue = type)
    private fun names(list: List<ContactDTO>) = list.map { it.name }
    private fun filter(contacts: List<ContactDTO>, query: String, order: Map<String, UInt>? = null) =
        MentionUtilities.filterContacts(contacts, query, order, Locale.US)

    @Test
    @OriginalCase("MentionUtilitiesTests::createMention creates correct format()")
    fun `createMention creates correct format`() {
        assertEquals("@[Alice]", MentionUtilities.createMention("Alice"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::createMention handles names with spaces()")
    fun `createMention handles names with spaces`() {
        assertEquals("@[My Node]", MentionUtilities.createMention("My Node"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::createMention handles special characters()")
    fun `createMention handles special characters`() {
        assertEquals("@[Node-123]", MentionUtilities.createMention("Node-123"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::createMention handles empty name()")
    fun `createMention handles empty name`() {
        assertEquals("@[]", MentionUtilities.createMention(""))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::appendMention into empty draft yields just the mention()")
    fun `appendMention into empty draft yields just the mention`() {
        assertEquals("@[Alice] ", MentionUtilities.appendMention("Alice", ""))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::appendMention preserves draft and adds a separating space()")
    fun `appendMention preserves draft and adds a separating space`() {
        assertEquals("hello @[Alice] ", MentionUtilities.appendMention("Alice", "hello"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::appendMention does not double the space when draft ends in whitespace()")
    fun `appendMention does not double the space when draft ends in whitespace`() {
        assertEquals("hello @[Alice] ", MentionUtilities.appendMention("Alice", "hello "))
        // Oracle: Character.isWhitespace is true for NBSP and false for a trailing zero-width space.
        assertEquals("hello\u00A0@[Alice] ", MentionUtilities.appendMention("Alice", "hello\u00A0"))
        assertEquals("hello\u200B @[Alice] ", MentionUtilities.appendMention("Alice", "hello\u200B"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::extractMentions parses single mention()")
    fun `extractMentions parses single mention`() {
        assertEquals(listOf("Alice"), MentionUtilities.extractMentions("@[Alice] hello!"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::extractMentions parses multiple mentions()")
    fun `extractMentions parses multiple mentions`() {
        assertEquals(listOf("Alice", "Bob"), MentionUtilities.extractMentions("@[Alice] and @[Bob] hello!"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::extractMentions returns empty for no mentions()")
    fun `extractMentions returns empty for no mentions`() {
        assertEquals(emptyList(), MentionUtilities.extractMentions("Hello world!"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::extractMentions handles names with spaces()")
    fun `extractMentions handles names with spaces`() {
        assertEquals(listOf("My Node"), MentionUtilities.extractMentions("@[My Node] says hi"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::extractMentions handles special characters()")
    fun `extractMentions handles special characters`() {
        assertEquals(listOf("Node-123"), MentionUtilities.extractMentions("@[Node-123] testing"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::extractMentions handles adjacent mentions()")
    fun `extractMentions handles adjacent mentions`() {
        assertEquals(listOf("Alice", "Bob"), MentionUtilities.extractMentions("@[Alice]@[Bob]"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::extractMentions handles empty message()")
    fun `extractMentions handles empty message`() {
        assertEquals(emptyList(), MentionUtilities.extractMentions(""))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::extractMentions handles Unicode names()")
    fun `extractMentions handles Unicode names`() {
        assertEquals(listOf("日本語"), MentionUtilities.extractMentions("@[日本語] hello"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::extractMentions ignores malformed patterns()")
    fun `extractMentions ignores malformed patterns`() {
        assertTrue(MentionUtilities.extractMentions("@[Alice hello").isEmpty())
        assertTrue(MentionUtilities.extractMentions("@Alice] hello").isEmpty())
        assertTrue(MentionUtilities.extractMentions("@ hello").isEmpty())
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::detectActiveMention returns nil for empty text()")
    fun `detectActiveMention returns nil for empty text`() {
        assertEquals(null, MentionUtilities.detectActiveMention(""))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::detectActiveMention returns nil for text without @()")
    fun `detectActiveMention returns nil for text without @`() {
        assertEquals(null, MentionUtilities.detectActiveMention("hello world"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::detectActiveMention returns empty string for @ alone()")
    fun `detectActiveMention returns empty string for @ alone`() {
        assertEquals("", MentionUtilities.detectActiveMention("@"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::detectActiveMention returns query after @()")
    fun `detectActiveMention returns query after @`() {
        assertEquals("jo", MentionUtilities.detectActiveMention("@jo"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::detectActiveMention works at start of message()")
    fun `detectActiveMention works at start of message`() {
        assertEquals("alice", MentionUtilities.detectActiveMention("@alice"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::detectActiveMention works after space()")
    fun `detectActiveMention works after space`() {
        assertEquals("bob", MentionUtilities.detectActiveMention("hey @bob"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::detectActiveMention returns nil for @ mid-word()")
    fun `detectActiveMention returns nil for @ mid-word`() {
        assertEquals(null, MentionUtilities.detectActiveMention("email@domain"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::detectActiveMention returns nil when space follows @()")
    fun `detectActiveMention returns nil when space follows @`() {
        assertEquals(null, MentionUtilities.detectActiveMention("@ hello"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::detectActiveMention returns last active mention()")
    fun `detectActiveMention returns last active mention`() {
        assertEquals("bo", MentionUtilities.detectActiveMention("@[Alice] hey @bo"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::detectActiveMention returns nil for completed mention()")
    fun `detectActiveMention returns nil for completed mention`() {
        assertEquals(null, MentionUtilities.detectActiveMention("@[Alice] hello"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::detectActiveMention handles Unicode()")
    fun `detectActiveMention handles Unicode`() {
        assertEquals("日本", MentionUtilities.detectActiveMention("@日本"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::detectActiveMention ignores email addresses()")
    fun `detectActiveMention ignores email addresses`() {
        assertEquals(null, MentionUtilities.detectActiveMention("contact me at test@example.com"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::detectActiveMention handles double @ symbols()")
    fun `detectActiveMention handles double @ symbols`() {
        assertEquals(null, MentionUtilities.detectActiveMention("@@alice"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::detectActiveMention returns nil for unclosed bracket()")
    fun `detectActiveMention returns nil for unclosed bracket`() {
        assertEquals(null, MentionUtilities.detectActiveMention("@[Alice"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::filterContacts matches using localizedStandardContains()")
    fun `filterContacts matches using localizedStandardContains`() {
        val filtered = filter(listOf(contact("Alice"), contact("Bob"), contact("Amanda")), "a")
        assertEquals(2, filtered.size)
        assertTrue("Alice" in names(filtered) && "Amanda" in names(filtered))
        // Diacritic- and case-insensitive like localizedStandardContains.
        assertEquals(listOf("Zoë"), names(filter(listOf(contact("Zoë")), "ZOE")))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::filterContacts excludes repeaters()")
    fun `filterContacts excludes repeaters`() {
        val filtered = filter(listOf(contact("Alice"), contact("Repeater1", 2u)), "")
        assertEquals(1, filtered.size)
        assertEquals("Alice", filtered.first().name)
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::filterContacts excludes rooms()")
    fun `filterContacts excludes rooms`() {
        assertEquals(1, filter(listOf(contact("Alice"), contact("Room1", 3u)), "").size)
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::filterContacts sorts alphabetically()")
    fun `filterContacts sorts alphabetically`() {
        assertEquals(listOf("Alice", "Bob", "Zoe"), names(filter(listOf(contact("Zoe"), contact("Alice"), contact("Bob")), "")))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::filterContacts returns empty for no matches()")
    fun `filterContacts returns empty for no matches`() {
        assertTrue(filter(listOf(contact("Alice")), "xyz").isEmpty())
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::filterContacts handles empty input()")
    fun `filterContacts handles empty input`() {
        assertTrue(filter(emptyList(), "a").isEmpty())
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::filterContacts sorts by sender order when provided()")
    fun `filterContacts sorts by sender order when provided`() {
        val order = mapOf("Charlie" to 300u, "Alice" to 200u, "Bob" to 100u)
        assertEquals(listOf("Charlie", "Alice", "Bob"), names(filter(listOf(contact("Alice"), contact("Bob"), contact("Charlie")), "", order)))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::filterContacts sender order partial match: ordered first, then alphabetical()")
    fun `filterContacts sender order partial match ordered first then alphabetical`() {
        val order = mapOf("Bob" to 500u, "Alice" to 100u)
        val contacts = listOf(contact("Zoe"), contact("Alice"), contact("Bob"), contact("Dan"))
        assertEquals(listOf("Bob", "Alice", "Dan", "Zoe"), names(filter(contacts, "", order)))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::containsSelfMention returns true for exact match()")
    fun `containsSelfMention returns true for exact match`() {
        assertTrue(MentionUtilities.containsSelfMention("Hello @[Alice]!", "Alice"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::containsSelfMention is case insensitive()")
    fun `containsSelfMention is case insensitive`() {
        assertTrue(MentionUtilities.containsSelfMention("@[ALICE]", "alice"))
        assertTrue(MentionUtilities.containsSelfMention("@[alice]", "ALICE"))
        assertTrue(MentionUtilities.containsSelfMention("@[Alice]", "aLiCe"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::containsSelfMention returns false for different name()")
    fun `containsSelfMention returns false for different name`() {
        assertFalse(MentionUtilities.containsSelfMention("@[Bob] hello", "Alice"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::containsSelfMention handles multiple mentions()")
    fun `containsSelfMention handles multiple mentions`() {
        assertTrue(MentionUtilities.containsSelfMention("@[Bob] @[Alice]", "Alice"))
        assertTrue(MentionUtilities.containsSelfMention("@[Alice] @[Bob]", "Alice"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::containsSelfMention returns false for empty text()")
    fun `containsSelfMention returns false for empty text`() {
        assertFalse(MentionUtilities.containsSelfMention("", "Alice"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::containsSelfMention returns false for empty selfName()")
    fun `containsSelfMention returns false for empty selfName`() {
        assertFalse(MentionUtilities.containsSelfMention("@[Alice]", ""))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::containsSelfMention handles names with spaces()")
    fun `containsSelfMention handles names with spaces`() {
        assertTrue(MentionUtilities.containsSelfMention("@[My Node] hello", "My Node"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::containsSelfMention handles special characters()")
    fun `containsSelfMention handles special characters`() {
        assertTrue(MentionUtilities.containsSelfMention("@[Node-123] test", "Node-123"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::containsSelfMention returns false for partial match()")
    fun `containsSelfMention returns false for partial match`() {
        assertFalse(MentionUtilities.containsSelfMention("@[Ali]", "Alice"))
    }

    @Test
    @OriginalCase("MentionUtilitiesTests::containsSelfMention returns false for text without mentions()")
    fun `containsSelfMention returns false for text without mentions`() {
        assertFalse(MentionUtilities.containsSelfMention("Hello world", "Alice"))
    }

    @Test
    @OriginalCase("MentionInsertionTests::insertMention replaces @query with mention format()")
    fun `insertMention replaces @query with mention format`() {
        assertEquals("hey @[Alice] ", MentionUtilities.insertMention("hey @ali", "Alice"))
    }

    @Test
    @OriginalCase("MentionInsertionTests::insertMention handles query at start of text()")
    fun `insertMention handles query at start of text`() {
        assertEquals("@[Bob] ", MentionUtilities.insertMention("@bob", "Bob"))
    }

    @Test
    @OriginalCase("MentionInsertionTests::insertMention preserves preceding text()")
    fun `insertMention preserves preceding text`() {
        assertEquals("Hello @[Alice] and @[John] ", MentionUtilities.insertMention("Hello @[Alice] and @jo", "John"))
    }

    @Test
    @OriginalCase("MentionInsertionTests::insertMention uses contact name not nickname()")
    fun `insertMention uses contact name not nickname`() {
        assertEquals("@[Bob's Solar Node] ", MentionUtilities.insertMention("@bob", "Bob's Solar Node"))
    }

    @Test
    fun `insertMention is a no-op without an active mention and buildReplyText quotes ten graphemes`() {
        assertEquals("hello", MentionUtilities.insertMention("hello", "Alice"))
        assertNull(MentionUtilities.detectActiveMention("@[Alice] done"))
        assertEquals("@[Bob]\n>0123456789..\n", MentionUtilities.buildReplyText("Bob", "@[Al] 0123456789ab"))
        assertEquals("@[Bob]\n>👍🏽👍🏽\n", MentionUtilities.buildReplyText("Bob", "👍🏽👍🏽"))
    }
}
