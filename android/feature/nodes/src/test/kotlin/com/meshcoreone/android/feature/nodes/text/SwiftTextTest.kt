// AndroidOnly: WP-311 Pins Foundation string semantics to swiftc oracle output (evidence/WP-311/oracle/wp311_strings.swift.txt).
package com.meshcoreone.android.feature.nodes.text

import com.meshcoreone.android.core.ui.hoppicker.SwiftText

import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SwiftTextTest {
    private val us = Locale.US
    private val turkish = Locale.forLanguageTag("tr-TR")

    /** (haystack, needle, localizedStandardContains en_US, ci+di without locale, ci+di tr_TR) from the oracle. */
    private val containsOracle = listOf(
        Triple("Alpha Repeater", "alpha", Triple(true, true, true)),
        Triple("Relay-Alpha", "al", Triple(true, true, true)),
        Triple("Caf\u00E9", "cafe", Triple(true, true, true)),
        Triple("cafe", "caf\u00E9", Triple(true, true, true)),
        Triple("Cafe\u0301", "caf\u00E9", Triple(true, true, true)),
        Triple("Stra\u00DFe", "strasse", Triple(true, true, true)),
        Triple("STRASSE", "stra\u00DFe", Triple(true, true, true)),
        Triple("\uFF21\uFF22\uFF23", "abc", Triple(false, false, false)),
        Triple("\u0130stanbul Relay", "istanbul", Triple(true, true, false)),
        Triple("ISTANBUL", "istanbul", Triple(true, true, false)),
        Triple("istanbul", "\u0130STANBUL", Triple(true, true, false)),
        Triple("\u0131stanbul", "istanbul", Triple(false, false, false)),
        Triple("\uFB01le", "fi", Triple(true, true, true)),
        Triple("file", "\uFB01", Triple(true, true, true)),
        Triple("\u00C4", "A\u0308", Triple(true, true, true)),
        Triple("\u0152uvre", "oe", Triple(false, false, false)),
        Triple("\u00D8", "o", Triple(false, false, false)),
        Triple("\u0141", "l", Triple(false, false, false)),
        Triple("KELVIN \u212A", "k", Triple(true, true, true)),
        Triple("x\u0130y", "i", Triple(true, true, false)),
        Triple("\u03AC", "\u03B1", Triple(true, true, true)),
        Triple("\u03A3", "\u03C2", Triple(true, true, true)),
        Triple("\uD55C\uAE00", "\u314E", Triple(false, false, false)),
        Triple("a\u200Bb", "ab", Triple(false, false, false)),
    )

    /** Partial matches inside a folded expansion are refused (oracle: wp311_more). */
    private val boundaryOracle = listOf(
        Triple("\uD55C\uAE00", "\uD558", false),
        Triple("\uFB01le", "f", false),
        Triple("stra\u00DFe", "stras", false),
        Triple("strasse", "stra\u00DF", true),
        Triple("ab", "a\u0301", true),
        Triple("n\u00E9e", "ne", true),
        Triple("\u1E9E", "ss", true),
        Triple("\u017F", "s", true),
        Triple("\uFB06", "st", true),
        Triple("\u01C6", "d\u017E", false),
        Triple("\u03A3\u038A\u03A3\u03A5\u03A6\u039F\u03A3", "\u03C3\u03AF\u03C3\u03C5\u03C6\u03BF\u03C2", true),
    )

    @Test
    fun `folded contains matches the swiftc oracle for every locale variant`() {
        for ((haystack, needle, expected) in containsOracle) {
            assertEquals(expected.first, SwiftText.foldedContains(haystack, needle, us), "std en_US: $haystack / $needle")
            assertEquals(expected.second, SwiftText.foldedContains(haystack, needle, null), "no locale: $haystack / $needle")
            assertEquals(expected.third, SwiftText.foldedContains(haystack, needle, turkish), "tr_TR: $haystack / $needle")
        }
    }

    @Test
    fun `folded contains refuses matches that end inside a folded character`() {
        for ((haystack, needle, expected) in boundaryOracle) {
            assertEquals(expected, SwiftText.foldedContains(haystack, needle, null), "$haystack / $needle")
        }
    }

    @Test
    fun `empty or mark-only needle never matches like Foundation range-of`() {
        assertFalse(SwiftText.foldedContains("abc", "", us))
        assertFalse(SwiftText.foldedContains("abc", "\u0301", null))
    }

    @Test
    fun `isHexDigit accepts ASCII and fullwidth hex as single scalars only`() {
        val accepted = listOf("a", "F", "0", "\uFF10", "\uFF19", "\uFF21", "\uFF46")
        val rejected = listOf("g", "\uFF47", "\u0663", "a\u0301", "")
        accepted.forEach { assertTrue(SwiftText.isHexDigit(it), it) }
        rejected.forEach { assertFalse(SwiftText.isHexDigit(it), it) }
        assertTrue(SwiftText.isAllHexDigits("\uFF10\uFF11"))
        assertEquals("ABCD", SwiftText.filterHexDigits("AB CD"))
        assertEquals("\uFF21\uFF22", SwiftText.filterHexDigits("\uFF21\uFF22"))
    }

    @Test
    fun `trimming whitespaces keeps newlines but drops tabs and space separators`() {
        assertEquals("a1", SwiftText.trimmingWhitespaces(" a1 "))
        assertEquals("a1", SwiftText.trimmingWhitespaces("\ta1\t"))
        assertEquals("\na1\n", SwiftText.trimmingWhitespaces("\na1\n"))
        assertEquals("a1", SwiftText.trimmingWhitespaces("\u00A0a1\u3000"))
    }

    @Test
    fun `hasPrefix compares whole characters under canonical equivalence`() {
        assertTrue(SwiftText.hasPrefix("ABCD", "AB"))
        assertTrue(SwiftText.hasPrefix("e\u0301x", "\u00E9"))
        assertFalse(SwiftText.hasPrefix("e\u0301x", "e"))
        assertFalse(SwiftText.hasPrefix("q\u0301x", "q"))
        assertTrue(SwiftText.hasPrefix("anything", ""))
    }

    @Test
    fun `uppercased uses full locale-independent mappings`() {
        assertEquals("SS", SwiftText.uppercased("\u00DF"))
        assertEquals("FI", SwiftText.uppercased("\uFB01"))
        assertEquals("I", SwiftText.uppercased("\u0131"))
        assertEquals("\u02BCN", SwiftText.uppercased("\u0149"))
    }

    @Test
    fun `localized comparator orders letters like the oracle`() {
        val names = listOf("Charlie", "Alice", "Bob", "alice", "\u00C9mile", "Zoe", "eclair", "Eclair", "\u00C9clair", "\u00DF", "ss", "SS")
        val tertiary = names.sortedWith(SwiftText.localizedComparator(us))
        assertEquals(listOf("alice", "Alice", "Bob", "Charlie", "eclair", "Eclair", "\u00C9clair", "\u00C9mile", "ss", "SS", "\u00DF", "Zoe"), tertiary)
        val secondary = names.sortedWith(SwiftText.localizedComparator(us, caseInsensitive = true))
        assertEquals(listOf("Alice", "alice", "Bob", "Charlie", "eclair", "Eclair", "\u00C9clair", "\u00C9mile", "\u00DF", "ss", "SS", "Zoe"), secondary)
        assertEquals(0, SwiftText.localizedComparator(us).compare("\u00C4", "A\u0308"))
    }
}
