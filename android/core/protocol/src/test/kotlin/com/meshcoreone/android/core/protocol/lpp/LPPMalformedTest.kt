// PortedFrom: MeshCore/Sources/MeshCore/LPP/LPPDecoder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation assertions require diagnostics for the source's intentional partial-prefix behavior.
package com.meshcoreone.android.core.protocol.lpp

import com.meshcoreone.android.core.protocol.bytes.Bytes
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LPPMalformedTest {
    private val prefix = PythonLPPReferenceBytes.temperature25_5
    private val prefixPoint = LPPDataPoint(1u, LPPSensorType.TEMPERATURE, LPPValue.Float(25.5))

    @TestFactory
    fun `Every truncated value retains only the complete prefix and diagnoses its exact width`(): List<DynamicTest> =
        lppGoldenVectors.map { vector ->
            DynamicTest.dynamicTest("truncated ${vector.point.type}") {
                for (length in 0 until vector.point.type.dataSize) {
                    val suffix = vector.bytes.prefix(2 + length)
                    for (accepted in listOf(Bytes.EMPTY, prefix)) {
                        val result = assertIs<LPPDecodeResult.Incomplete>(LPPDecoder.decode(accepted + suffix))
                        assertFalse(result.isComplete)
                        assertEquals(if (accepted.isEmpty) emptyList() else listOf(prefixPoint), result.dataPoints)
                        assertEquals(accepted.size, result.consumedByteCount)
                        assertEquals(suffix, result.remainingData)
                        val diagnostic = assertIs<LPPDecodeDiagnostic.TruncatedValue>(result.diagnostic)
                        assertEquals(accepted.size, diagnostic.offset)
                        assertEquals(vector.point.channel, diagnostic.channel)
                        assertEquals(vector.point.type, diagnostic.type)
                        assertEquals(length, diagnostic.availableBytes)
                        assertEquals(vector.point.type.dataSize, diagnostic.expectedBytes)
                        val error = assertFailsWith<LPPDecodingException> { result.requireComplete() }
                        assertSame(diagnostic, error.diagnostic)
                    }
                }
            }
        }

    @Test
    fun `An empty payload is complete but a lone channel is not a valid empty frame`() {
        val empty = assertIs<LPPDecodeResult.Complete>(LPPDecoder.decode(Bytes.EMPTY))
        assertTrue(empty.isComplete)
        assertEquals(emptyList(), empty.requireComplete())
        assertEquals(0, empty.consumedByteCount)
        val invalid = assertIs<LPPDecodeResult.Incomplete>(LPPDecoder.decode(Bytes.of(255)))
        assertFalse(invalid.isComplete)
        assertEquals(emptyList(), invalid.dataPoints)
        assertEquals(0, invalid.consumedByteCount)
        assertEquals(Bytes.of(255), invalid.remainingData)
        assertEquals(LPPDecodeDiagnostic.TruncatedHeader(0, 1), invalid.diagnostic)
        assertFailsWith<LPPDecodingException> { LPPDecoder.decodeStrict(Bytes.of(255)) }
    }

    @Test
    fun `A trailing channel reports the record start after the accepted prefix`() {
        val result = assertIs<LPPDecodeResult.Incomplete>(LPPDecoder.decode(prefix + Bytes.of(128)))
        assertEquals(listOf(prefixPoint), result.dataPoints)
        assertEquals(prefix.size, result.consumedByteCount)
        assertEquals(Bytes.of(128), result.remainingData)
        val diagnostic = assertIs<LPPDecodeDiagnostic.TruncatedHeader>(result.diagnostic)
        assertEquals(prefix.size, diagnostic.offset)
        assertEquals(2, diagnostic.expectedBytes)
        assertEquals(1, diagnostic.availableBytes)
    }

    @Test
    fun `Every unknown wire type stops parsing without dropping its suffix or resynchronizing`() {
        val known = setOf(
            0, 1, 2, 3, 100, 101, 102, 103, 104, 113, 115, 116, 117, 118,
            120, 121, 122, 125, 128, 130, 131, 132, 133, 134, 135, 136, 142,
        )
        var unknownCount = 0
        for (raw in 0..255) {
            if (raw in known) continue
            unknownCount++
            val suffix = Bytes.of(255, raw) + PythonLPPReferenceBytes.humidity65
            for (accepted in listOf(Bytes.EMPTY, prefix)) {
                val result = assertIs<LPPDecodeResult.Incomplete>(LPPDecoder.decode(accepted + suffix))
                assertFalse(result.isComplete)
                assertEquals(if (accepted.isEmpty) emptyList() else listOf(prefixPoint), result.dataPoints)
                assertEquals(accepted.size, result.consumedByteCount)
                assertEquals(suffix, result.remainingData)
                assertEquals(
                    LPPDecodeDiagnostic.UnknownSensorType(accepted.size, 255u, raw.toUByte()),
                    result.diagnostic,
                )
                val error = assertFailsWith<LPPDecodingException> { LPPDecoder.decodeStrict(accepted + suffix) }
                assertEquals(result.diagnostic, error.diagnostic)
            }
        }
        assertEquals(229, unknownCount)
    }

    @Test
    fun `Unknown type wins over missing data rather than pretending to know its width`() {
        val result = assertIs<LPPDecodeResult.Incomplete>(LPPDecoder.decode(Bytes.of(0, 255)))
        assertEquals(LPPDecodeDiagnostic.UnknownSensorType(0, 0u, 255u), result.diagnostic)
        assertEquals(Bytes.of(0, 255), result.remainingData)
        assertEquals(emptyList(), result.dataPoints)
    }
}
