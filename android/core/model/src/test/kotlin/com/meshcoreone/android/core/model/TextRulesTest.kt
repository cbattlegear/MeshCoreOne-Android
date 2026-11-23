// AndroidOnly: WP-309 Issue #729 independent assertions from upstream TextRulesPreferenceTests at 5e980a53e077c45a1270170612fdf5dae97792ff.
package com.meshcoreone.android.core.model

import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test

class TextRulesTest {
    @Test fun defaultsFixedSelectionsAndOverridesAreIndependentAndRadioScoped() {
        val key = TextRuleKey(RadioId(UUID.randomUUID()), UUID.randomUUID())
        val other = key.copy(radioId = RadioId(UUID.randomUUID()))
        for (kind in TextRuleKind.entries) {
            val preference = TextRulesPreference()
            val defaultOn = kind == TextRuleKind.PUBLIC_CHANNEL || kind == TextRuleKind.HASHTAG_CHANNEL
            assertEquals(defaultOn, preference.isEnabled(kind, null, TextRuleOverride.INHERIT))
            val fixed = preference.copy(selections = preference.selections + (kind to
                TextRuleSelection(TextRuleSelectionMode.FIXED, setOf(key))))
            assertTrue(fixed.isEnabled(kind, key, TextRuleOverride.INHERIT))
            assertFalse(fixed.isEnabled(kind, other, TextRuleOverride.INHERIT))
            assertFalse(fixed.isEnabled(kind, null, TextRuleOverride.INHERIT))
            assertTrue(fixed.isEnabled(kind, other, TextRuleOverride.ON))
            assertFalse(fixed.isEnabled(kind, key, TextRuleOverride.OFF))
        }
    }

    @Test fun literalCaseInsensitiveMatchingKeepsFirstRuleAndIndependentFields() {
        val first = TextRule(phrase = "CAFÉ*", field = TextRuleField.MESSAGE_OR_SENDER)
        val preference = TextRulesPreference(rules = listOf(first, TextRule(phrase = "alert")))
        val hit = assertNotNull(preference.firstMatch("Alert café* café*", "café* store"))
        assertEquals(first, hit.rule)
        assertEquals(listOf(TextRuleHighlight(6, 5), TextRuleHighlight(12, 5)), hit.bodyHighlights)
        assertEquals(listOf(TextRuleHighlight(0, 5)), hit.senderHighlights)
        for (text in listOf("cafe*", "cafe\u0301*", "caféx", "")) assertNull(preference.firstMatch(text, null))
        assertNull(preference.firstMatch(null, "alert"))
        assertEquals(first, preference.firstMatch(null, "café* store")?.rule)
    }

    @Test fun wholeFieldPreservesSpacesAndContainsExpandsToEmojiGraphemes() {
        val rule = TextRule(phrase = " ALERT", field = TextRuleField.MESSAGE_OR_SENDER, match = TextRuleMatch.WHOLE_FIELD)
        val preference = TextRulesPreference(rules = listOf(rule, TextRule(phrase = "👩", field = TextRuleField.SENDER)))
        assertEquals(rule, preference.firstMatch(" alert", "ALERT")?.rule)
        assertNull(preference.firstMatch("  alert", "ALERT"))
        assertNull(preference.firstMatch(" alert ", null))
        val hit = assertNotNull(preference.firstMatch(null, "Radio 👩‍🚒"))
        assertEquals(listOf(TextRuleHighlight(6, 5)), hit.senderHighlights)
        assertEquals("👩‍🚒", "Radio 👩‍🚒".substring(6, 11))
    }

    @Test fun combiningMarksCjkRtlAndOverlappingGraphemeMatchesKeepUtf16Offsets() {
        val preference = TextRulesPreference(rules = listOf(TextRule(phrase = "\u0301")))
        assertEquals(listOf(TextRuleHighlight(0, 2)), preference.firstMatch("e\u0301", null)?.bodyHighlights)
        assertNotNull(TextRulesPreference(rules = listOf(TextRule(phrase = "你好"))).firstMatch("你好世界", null))
        assertEquals(listOf(TextRuleHighlight(1, 4)), TextRulesPreference(rules = listOf(TextRule(phrase = "שלום")))
            .firstMatch(" שלום ", null)?.bodyHighlights)
    }

    @Test fun invalidRulesAndDuplicateIdsAreRejectedWithoutTrimmingValidPhrases() {
        val rule = TextRule(phrase = " keep ")
        TextRulesPreference(rules = listOf(rule)).validate()
        assertFailsWith<IllegalArgumentException> { TextRulesPreference(rules = listOf(rule, rule)).validate() }
        assertFailsWith<IllegalArgumentException> { TextRulesPreference(rules = listOf(TextRule(phrase = " \n"))).validate() }
        assertFailsWith<IllegalArgumentException> { TextRulesPreference(selections = emptyMap()).validate() }
        assertNull(TextRulesPreference(rules = listOf(rule)).firstMatch("keep", null))
    }
}
