// PortedFrom: MeshCore/Sources/MeshCore/LPP/LPPDecoder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Sources/MeshCore/LPP/LPPEncoder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.lpp

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class LPPValueTest {
    @Test
    fun `All associated value cases and data points have content equality and hashing`() {
        val values = listOf(
            LPPValue.Digital(true), LPPValue.Integer(4_294_967_295L), LPPValue.Float(1.25),
            LPPValue.Vector3(1.0, -2.0, 3.0), LPPValue.Gps(45.0, -90.0, 1.0),
            LPPValue.Rgb(255u, 128u, 0u), LPPValue.Timestamp(Instant.ofEpochSecond(4_294_967_295L)),
        )
        for (value in values) {
            val point = LPPDataPoint(255u, LPPSensorType.GENERIC_SENSOR, value)
            assertEquals(point, point.copy())
            assertEquals(point.hashCode(), point.copy().hashCode())
            assertEquals(1, setOf(point, point.copy()).size)
            assertNotEquals(point, point.copy(channel = 0u))
        }
        assertNotEquals<LPPValue>(LPPValue.Integer(1), LPPValue.Float(1.0))
        assertEquals(4_294_967_295L, LPPValue.Integer(4_294_967_295L).value)
    }

    @Test
    fun `Floating associated values retain Swift signed zero equality and hash compatibility`() {
        val pairs = listOf(
            LPPValue.Float(-0.0) to LPPValue.Float(0.0),
            LPPValue.Vector3(-0.0, 0.0, -0.0) to LPPValue.Vector3(0.0, -0.0, 0.0),
            LPPValue.Gps(-0.0, 0.0, -0.0) to LPPValue.Gps(0.0, -0.0, 0.0),
        )
        for ((left, right) in pairs) {
            assertEquals(left, right)
            assertEquals(left.hashCode(), right.hashCode())
            assertEquals(1, setOf(left, right).size)
        }
        assertFalse(LPPValue.Float(Double.NaN) == LPPValue.Float(Double.NaN))
        assertFalse(LPPValue.Vector3(Double.NaN, 0.0, 0.0) == LPPValue.Vector3(Double.NaN, 0.0, 0.0))
        assertFalse(LPPValue.Gps(0.0, Double.NaN, 0.0) == LPPValue.Gps(0.0, Double.NaN, 0.0))
    }

    @Test
    fun `Encoder count reset and snapshots do not expose the mutable buffer`() {
        val encoder = LPPEncoder()
        assertEquals(0, encoder.count)
        assertEquals(Bytes.EMPTY, encoder.encode())
        encoder.addTemperature(1u, 25.5)
        val snapshot = encoder.encode()
        assertEquals(4, encoder.count)
        encoder.addHumidity(2u, 65.0)
        assertEquals(7, encoder.count)
        assertEquals(PythonLPPReferenceBytes.temperature25_5, snapshot)
        val exposed = snapshot.toByteArray()
        exposed[0] = 0
        assertEquals(PythonLPPReferenceBytes.temperature25_5, snapshot)
        encoder.reset()
        assertEquals(0, encoder.count)
        assertEquals(Bytes.EMPTY, encoder.encode())
        assertEquals(PythonLPPReferenceBytes.temperature25_5, snapshot)
        encoder.addDigitalOutput(255u, 0u)
        assertEquals(Bytes.of(255, 1, 0), encoder.encode())
    }

    @Test
    fun `Decode results copy their collection and reject caller mutation`() {
        val point = LPPDataPoint(1u, LPPSensorType.TEMPERATURE, LPPValue.Float(25.5))
        val supplied = mutableListOf(point)
        val result = LPPDecodeResult.Complete(supplied, 4)
        supplied.clear()
        assertEquals(listOf(point), result.dataPoints)
        assertFailsWith<UnsupportedOperationException> { (result.dataPoints as MutableList<LPPDataPoint>).clear() }
        val actual = LPPDecoder.decodeStrict(PythonLPPReferenceBytes.temperature25_5)
        assertFailsWith<UnsupportedOperationException> { (actual as MutableList<LPPDataPoint>).clear() }
        assertEquals(listOf(point), actual)
    }

    @Test
    fun `Raw input copies and later encoder mutations cannot change a prior snapshot`() {
        val mutable = byteArrayOf(0x00, 0xff.toByte())
        val raw = Bytes(mutable)
        val encoder = LPPEncoder()
        encoder.addRaw(1u, LPPSensorType.TEMPERATURE, raw)
        val first = encoder.encode()
        mutable.fill(0)
        encoder.addRaw(2u, LPPSensorType.TEMPERATURE, raw)
        assertEquals(PythonLPPReferenceBytes.temperature25_5, first)
        assertEquals(Bytes.fromHex("016700ff026700ff"), encoder.encode())
    }
}
