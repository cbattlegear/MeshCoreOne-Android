// AndroidOnly: WP-213 MECP receiver vectors and malformed input guardrails.
package com.meshcoreone.android.core.model

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class MecpMessageTest {
    @Test
    fun `canonical wire preserves coordinates and language hint without interpreting them`() {
        val message = assertNotNull(MecpMessage.decode("MECP/0/M01 M02 T04 3pax 48.8566,2.3522 @1430 @en"))
        assertEquals(MecpMessage.Severity.MAYDAY, message.severity)
        assertEquals(listOf("M01", "M02", "T04"), message.codes)
        assertEquals(3L, message.personCount)
        assertEquals("1430", message.utcClock)
        assertEquals("48.8566,2.3522 @en", message.tail)
    }

    @Test
    fun `codes and typed fields are ASCII and malformed headers remain plain text`() {
        for (text in listOf("", "MECP/", "MECP/0", "MECP/0/", "MECP/0XM01", "MECP/5/M01",
            "MECP/0/m01", "MECP/0/M０1", "mecp/0/M01", " MECP/0/M01")) {
            assertNull(MecpMessage.decode(text), text)
        }
        val unknown = assertNotNull(MecpMessage.decode("MECP/0/D01  M01 Z99 12pax T03"))
        assertEquals(listOf("D01", "M01", "Z99"), unknown.codes)
        assertEquals(12L, unknown.personCount)
        assertEquals("T03", unknown.tail)
    }

    @Test
    fun `malformed and overflowing fields stay visible and no receiver byte limit is imposed`() {
        val tail = "1.5pax @2400 @1430 @1420. 9223372036854775808pax ${"A".repeat(200)}"
        val message = assertNotNull(MecpMessage.decode("MECP/3/M01 $tail"))
        assertNull(message.personCount)
        assertNull(message.utcClock)
        assertEquals(tail, message.tail)
        val eta = assertNotNull(MecpMessage.decode("MECP/1/R03  15 near"))
        assertEquals(15L, eta.etaMinutes)
        assertEquals("near", eta.tail)
        assertNull(MecpMessage.decode("MECP/1/R03 near 15")?.etaMinutes)
    }
}
