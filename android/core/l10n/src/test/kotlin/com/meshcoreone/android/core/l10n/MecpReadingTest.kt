// AndroidOnly: WP-213 MECP catalog readings from bundled Android assets.
package com.meshcoreone.android.core.l10n

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MecpReadingTest {
    @Test
    fun allBundledCatalogsContainEnglishCodesAndSeverities() {
        val assets = resourcesForLocale("en").assets
        fun keys(stem: String, field: String): Set<String> =
            assets.open("mecp/$stem.json").bufferedReader().use {
                JSONObject(it.readText()).getJSONObject(field).keys().asSequence().toSet()
            }
        val englishCodes = keys("en", "codes")
        val englishSeverities = keys("en", "severities")
        assertTrue(englishCodes.isNotEmpty())
        for (stem in listOf("de", "es", "fr", "it", "nl", "pl", "pt", "ru", "uk", "zh-cn")) {
            assertTrue(keys(stem, "codes").containsAll(englishCodes), stem)
            assertTrue(keys(stem, "severities").containsAll(englishSeverities), stem)
        }
    }

    @Test
    fun quantityLinesFollowEachLocaleAndFallback() {
        val cases = listOf(
            Triple("en", 1, "1 person"), Triple("en", 3, "3 people"),
            Triple("de", 1, "1 Person"), Triple("de", 3, "3 Personen"),
            Triple("es", 1, "1 persona"), Triple("es", 3, "3 personas"),
            Triple("fr", 1, "1 personne"), Triple("fr", 3, "3 personnes"),
            Triple("it", 1, "1 persona"), Triple("it", 3, "3 persone"),
            Triple("nl", 1, "1 persoon"), Triple("nl", 3, "3 personen"),
            Triple("pt", 1, "1 pessoa"), Triple("pt", 3, "3 pessoas"),
            Triple("zh-Hans", 1, "1 人"), Triple("zh-Hans", 3, "3 人"),
            Triple("pl", 1, "1 osoba"), Triple("pl", 2, "2 osoby"), Triple("pl", 5, "5 osób"),
            Triple("pl", 21, "21 osób"), Triple("pl", 22, "22 osoby"),
            Triple("ru", 1, "1 человек"), Triple("ru", 2, "2 человека"), Triple("ru", 5, "5 человек"),
            Triple("ru", 21, "21 человек"), Triple("ru", 22, "22 человека"),
            Triple("uk", 1, "1 людина"), Triple("uk", 2, "2 людини"), Triple("uk", 5, "5 людей"),
            Triple("uk", 21, "21 людина"), Triple("uk", 22, "22 людини"),
            Triple("ko", 1, "1 person"), Triple("ko", 3, "3 people"),
        )
        for ((locale, count, expected) in cases) {
            assertEquals(expected, MecpReading.lines("MECP/3/M01 ${count}pax", resourcesForLocale(locale))?.last(), locale)
        }
    }

    @Test
    fun canonicalReadingKeepsOnlyRecognizedFieldsOutOfTheTail() {
        val wire = "MECP/0/M01 M02 T04 3pax 48.8566,2.3522 @1430 @en"
        val expected = listOf("MAYDAY", "Injury", "Unconscious person", "Flooding", "3 people", "14:30 UTC", "48.8566,2.3522 @en")
        val resources = resourcesForLocale("en")
        assertEquals(expected, MecpReading.lines(wire, resources))
        assertEquals(expected.joinToString(" "), MecpReading.display(wire, resources))
    }

    @Test
    fun catalogUsesTheSelectedLocaleAndEnglishWhenNoPackExists() {
        assertEquals("URGENT ETA 15 minutes", MecpReading.display("MECP/1/R03 15", resourcesForLocale("en")))
        assertEquals("DRINGEND Ankunft in 15 Minuten", MecpReading.display("MECP/1/R03 15", resourcesForLocale("de")))
        assertEquals("\u7d27\u6025 \u9884\u8ba1\u5230\u8fbe 15 \u5206\u949f",
            MecpReading.display("MECP/1/R03 15", resourcesForLocale("zh-Hans")))
        assertEquals("ROUTINE Injury 1 person", MecpReading.display("MECP/3/M01 1pax", resourcesForLocale("ko")))
        assertTrue(MecpReading.display("MECP/3/H07 4pax", resourcesForLocale("en")).contains("space for 4pax"))
    }

    @Test
    fun malformedTextStaysRawAndDrillPrecedesTheSeverityInPreviews() {
        val resources = resourcesForLocale("en")
        assertEquals("MECP/5/M01", MecpReading.display("MECP/5/M01", resources))
        assertEquals("This is a drill MAYDAY Injury Z99: Unknown code",
            MecpReading.display("MECP/0/M01 D01 Z99", resources))
    }
}
