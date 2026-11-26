// PortedFrom: MC1Services/Tests/MC1ServicesTests/Utilities/HashtagUtilitiesTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.contacts

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class HashtagUtilitiesTest {
    @TestFactory
    fun patternMatchesValidHashtags(): List<DynamicTest> = channelsParameterized(
        "$SUITE::hashtag pattern matches valid hashtags(text : String)",
        listOf("#general", "#General", "#test-channel", "#abc123", "#a"),
    ) { text ->
        assertEquals(1, Regex(HashtagUtilities.HASHTAG_PATTERN).findAll(text).count(), "Expected match for: $text")
    }

    @TestFactory
    fun patternRejectsInvalidHashtags(): List<DynamicTest> = channelsParameterized(
        "$SUITE::hashtag pattern rejects invalid hashtags(text : String)",
        listOf("#test_underscore", "#test.dot", "#", "#-bad", "#bad!", "#white space"),
    ) { text ->
        // Anchored for full-string validation (the extraction pattern finds partial matches).
        assertEquals(0, Regex("^" + HashtagUtilities.HASHTAG_PATTERN + "$").findAll(text).count(), "Expected no match for: $text")
    }

    @TestFactory
    fun extractionAndNames(): List<DynamicTest> = channelsCases(
        SUITE,
        "extractHashtags finds single hashtag" to {
            assertEquals(listOf("#general"), names("Join #general today"))
        },
        "extractHashtags accepts uppercase hashtags" to {
            assertEquals(listOf("#General"), names("Join #General today"))
        },
        "extractHashtags finds multiple hashtags" to {
            assertEquals(listOf("#one", "#two"), names("Try #one and #two"))
        },
        "extractHashtags returns empty for no hashtags" to {
            assertTrue(HashtagUtilities.extractHashtags("No hashtags here").isEmpty())
        },
        "extractHashtags excludes hashtags inside URLs" to {
            assertEquals(listOf("#general"), names("See https://example.com#section and #general"))
        },
        "extractHashtags handles hashtag at end with punctuation" to {
            assertEquals(listOf("#general"), names("Join #general."))
        },
        "extractHashtags handles adjacent hashtags" to {
            assertEquals(2, HashtagUtilities.extractHashtags("#one#two").size)
        },
        "isValidHashtagName accepts valid names" to {
            listOf("general", "General", "TEST", "test-channel", "abc123", "a").forEach { assertTrue(HashtagUtilities.isValidHashtagName(it), it) }
        },
        "isValidHashtagName rejects invalid names" to {
            listOf("", "-bad", "test_underscore", "test.dot", "bad!").forEach { assertFalse(HashtagUtilities.isValidHashtagName(it), it) }
        },
        "normalizeHashtagName lowercases and strips prefix" to {
            assertEquals("general", HashtagUtilities.normalizeHashtagName("#General"))
            assertEquals("test", HashtagUtilities.normalizeHashtagName("#TEST"))
            assertEquals("general", HashtagUtilities.normalizeHashtagName("general"))
        },
        "sanitizeHashtagNameInput lowercases and strips invalid characters" to {
            assertEquals("general", HashtagUtilities.sanitizeHashtagNameInput("General"))
            assertEquals("general", HashtagUtilities.sanitizeHashtagNameInput("-General"))
            assertEquals("general", HashtagUtilities.sanitizeHashtagNameInput("gen_eral"))
        },
    )

    @TestFactory
    fun nativeBoundaries(): List<DynamicTest> = channelsNativeCases(
        "hashtag ranges are inclusive UTF-16 offsets into the message" to {
            val text = "🔐 #mesh"
            val hashtag = HashtagUtilities.extractHashtags(text).single()
            assertEquals(3..7, hashtag.range)
            assertEquals("#mesh", text.substring(hashtag.range))
        },
        "URL scan excludes www links and trims trailing sentence punctuation" to {
            assertEquals(listOf("#b"), names("Visit www.example.com/#a, then #b"))
            assertEquals(listOf("#tag"), names("(see https://example.com/x) #tag"))
            assertEquals(listOf("#c"), names("ftp://example.com/#c"), "only http(s) links are excluded")
            assertEquals(listOf(4..20), HashtagUtilities.findURLRanges("Go: https://ex.com/#x."))
        },
        "precomputed URL ranges are honoured" to {
            assertTrue(HashtagUtilities.extractHashtags("#one", listOf(0..3)).isEmpty())
            assertEquals(listOf("#one"), HashtagUtilities.extractHashtags("#one", emptyList()).map { it.name })
        },
        "normalize strips only a standalone leading hash grapheme and sanitize drops non-ASCII" to {
            assertEquals("#́abc", HashtagUtilities.normalizeHashtagName("#́ABC"))
            assertEquals("cafe", HashtagUtilities.sanitizeHashtagNameInput("--Caf!e"))
            assertEquals("caf-e", HashtagUtilities.sanitizeHashtagNameInput("Café-E"))
            assertFalse(HashtagUtilities.isValidHashtagName("café"))
        },
    )

    private fun names(text: String): List<String> = HashtagUtilities.extractHashtags(text).map { it.name }

    private companion object {
        const val SUITE = "HashtagUtilitiesTests"
    }
}
