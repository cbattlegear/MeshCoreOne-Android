// AndroidOnly: WP-306 Foundation-comparison parity checks; expected values come from the swiftc oracle in docs/android/evidence/WP-306/oracle.
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.feature.chats.list.support.Fixtures.contact
import com.meshcoreone.android.feature.chats.list.support.TestStrings
import kotlin.test.assertEquals
import org.junit.Test

class ChatTextMatchingTest {
    @Test
    fun `localizedCaseInsensitiveCompare oracle`() {
        val cases = listOf(
            Triple("alice", "ALICE", true), Triple("Ä", "ä", true), Triple("é", "e", false),
            Triple("é", "é", true), Triple("ß", "SS", true), Triple("ß", "ss", true),
            Triple("İ", "i", false), Triple("ａ", "a", true), Triple("Alice ", "Alice", false),
            Triple("ǅ", "ǆ", true), Triple("σ", "ς", true), Triple("Σ", "ς", true),
        )
        for ((lhs, rhs, expected) in cases) assertEquals(expected, ChatTextMatching.caseInsensitiveEquals(lhs, rhs), "$lhs vs $rhs")
    }

    @Test
    fun `localizedStandardContains oracle`() {
        val cases = listOf(
            Triple("Café", "cafe", true), Triple("cafe", "Café", true), Triple("Straße", "strasse", true),
            Triple("strasse", "Straße", true), Triple("ａlice", "alice", false), Triple("Alice", "ａ", false),
            Triple("İstanbul", "istanbul", true), Triple("Ångström", "angstrom", true), Triple("Alice", "ALI", true),
            Triple("a b", "a  b", false), Triple("alice", "", false),
        )
        for ((haystack, needle, expected) in cases) assertEquals(expected, ChatTextMatching.standardContains(haystack, needle), "$haystack contains $needle")
    }

    @Test
    fun `independent Unicode equality probes reach sender matching consumers`() {
        val cases = listOf(
            Triple("\u00c4", "\u00e4", true), Triple("\u00e9", "e", false),
            Triple("e\u0301", "\u00e9", true), Triple("\u00df", "SS", true),
            Triple("\u0130", "i", false), Triple("\uff41", "a", true),
            Triple("\u01c5", "\u01c6", true), Triple("\u03c3", "\u03c2", true),
            Triple("Alice ", "Alice", false),
        )
        for ((name, sender, expected) in cases) {
            val normal = contact(name)
            val blocked = contact(name, isBlocked = true)
            assertEquals(
                if (expected) listOf(normal.id, blocked.id) else emptyList(),
                SenderContactMatcher.filter(listOf(normal, blocked), sender).map { it.id },
                "$name vs $sender",
            )
            assertEquals(
                if (expected) listOf(normal.id) else emptyList(),
                SenderContactMatcher.filter(listOf(normal, blocked), sender, excludeBlocked = true).map { it.id },
                "blocked exclusion: $name vs $sender",
            )
        }
    }

    @Test
    fun `independent Unicode search probes retain search over all filters`() {
        val cases = listOf(
            Triple("Caf\u00e9", "cafe", true), Triple("cafe", "Caf\u00e9", true),
            Triple("Stra\u00dfe", "strasse", true), Triple("strasse", "Stra\u00dfe", true),
            Triple("\uff41lice", "alice", false), Triple("Alice", "\uff41", false),
            Triple("\u0130stanbul", "istanbul", true), Triple("\u00c5ngstr\u00f6m", "angstrom", true),
            Triple("a b", "a  b", false),
        )
        for ((name, needle, expected) in cases) {
            val conversation = Conversation.Direct(contact(name, isMuted = true))
            for (filter in ChatFilter.entries) {
                assertEquals(
                    if (expected) listOf(conversation) else emptyList(),
                    listOf(conversation).filtered(filter, needle, TestStrings),
                    "$filter: $name contains $needle",
                )
            }
        }
    }
}
