// PortedFrom: MeshCore/Sources/MeshCore/LPP/LPPEncoder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Sources/MeshCore/LPP/LPPDecoder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.lpp

import com.meshcoreone.android.core.protocol.bytes.Bytes
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LPPGoldenVectorTest {
    @TestFactory
    fun `Every sensor encodes against independently specified bytes`(): List<DynamicTest> =
        lppGoldenVectors.map { vector ->
            DynamicTest.dynamicTest("encode ${vector.point.type}") {
                val encoder = LPPEncoder()
                encoder.add(vector.point)
                assertEquals(vector.bytes, encoder.encode())
                assertEquals(vector.bytes.size, encoder.count)
            }
        }

    @TestFactory
    fun `Every sensor decodes independently of the candidate encoder`(): List<DynamicTest> =
        lppGoldenVectors.map { vector ->
            DynamicTest.dynamicTest("decode ${vector.point.type}") {
                val result = assertIs<LPPDecodeResult.Complete>(LPPDecoder.decode(vector.bytes))
                assertEquals(listOf(vector.point), result.dataPoints)
                assertEquals(vector.bytes.size, result.consumedByteCount)
            }
        }

    @TestFactory
    fun `Raw records retain their exact bytes for every sensor`(): List<DynamicTest> =
        lppGoldenVectors.map { vector ->
            DynamicTest.dynamicTest("raw ${vector.point.type}") {
                val encoder = LPPEncoder()
                encoder.addRaw(vector.point.channel, vector.point.type, vector.bytes.slice(2, vector.bytes.size))
                assertEquals(vector.bytes, encoder.encode())
            }
        }

    @Test
    fun `Independent vectors account for all sensors exactly once`() {
        assertEquals(27, lppGoldenVectors.size)
        assertEquals(LPPSensorType.entries.toSet(), lppGoldenVectors.map { it.point.type }.toSet())
        for (vector in lppGoldenVectors) {
            assertEquals(2 + vector.point.type.dataSize, vector.bytes.size)
            assertEquals(vector.point.channel, vector.bytes[0])
            assertEquals(vector.point.type.rawValue, vector.bytes[1])
        }
    }

    @Test
    fun `Mixed records preserve insertion order duplicate channels and all widths`() {
        val points = lppGoldenVectors.mapIndexed { index, vector ->
            vector.point.copy(channel = if (index % 2 == 0) 255u else 0u)
        }
        val expected = lppGoldenVectors.mapIndexed { index, vector ->
            Bytes.of(if (index % 2 == 0) 255 else 0) + vector.bytes.slice(1, vector.bytes.size)
        }.fold(Bytes.EMPTY, Bytes::plus)
        val encoder = LPPEncoder()
        points.forEach(encoder::add)
        assertEquals(expected, encoder.encode())
        assertEquals(points, LPPDecoder.decodeStrict(expected))
    }
}
