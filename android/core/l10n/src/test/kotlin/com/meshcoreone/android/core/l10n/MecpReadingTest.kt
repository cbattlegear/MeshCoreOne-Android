// AndroidOnly: WP-213 MECP catalog readings from bundled Android assets.
package com.meshcoreone.android.core.l10n

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MecpReadingTest {
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
