// PortedFrom: MeshCore/Tests/MeshCoreTests/Validation/LPPPythonReferenceTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Tests/MeshCoreTests/Fixtures/PythonReferenceBytes.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.lpp

import com.meshcoreone.android.core.protocol.bytes.Bytes
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LPPPythonReferenceTest {
    @Test
    fun `Temperature 25_5 matches Python`() {
        val encoder = LPPEncoder()
        encoder.addTemperature(1u, 25.5)
        assertEquals(PythonLPPReferenceBytes.temperature25_5, encoder.encode())
    }

    @Test
    fun `Temperature negative round trip`() {
        val encoder = LPPEncoder()
        encoder.addTemperature(1u, -10.5)
        assertEquals(Bytes.of(0x01, 0x67, 0xff, 0x97), encoder.encode())
        val decoded = LPPDecoder.decodeStrict(encoder.encode())
        assertEquals(1, decoded.size)
        assertEquals(-10.5, assertIs<LPPValue.Float>(decoded[0].value).value, 0.1)
    }

    @Test
    fun `Humidity 65 matches Python`() {
        val encoder = LPPEncoder()
        encoder.addHumidity(2u, 65.0)
        assertEquals(PythonLPPReferenceBytes.humidity65, encoder.encode())
    }

    @Test
    fun `Analog input 3_3 matches Python`() {
        val encoder = LPPEncoder()
        encoder.addAnalogInput(3u, 3.3)
        assertEquals(PythonLPPReferenceBytes.analog3_3, encoder.encode())
    }

    @Test
    fun `GPS SF matches Python`() {
        val encoder = LPPEncoder()
        encoder.addGPS(4u, 37.7749, -122.4194, 10.0)
        assertEquals(PythonLPPReferenceBytes.gpsSF, encoder.encode())
    }

    @Test
    fun `GPS decode round trip`() {
        val encoder = LPPEncoder()
        encoder.addGPS(4u, 37.7749, -122.4194, 10.0)
        val decoded = LPPDecoder.decodeStrict(encoder.encode())
        assertEquals(1, decoded.size)
        assertEquals(4.toUByte(), decoded[0].channel)
        assertEquals(LPPSensorType.GPS, decoded[0].type)
        val gps = assertIs<LPPValue.Gps>(decoded[0].value)
        assertEquals(37.7749, gps.latitude, 0.0001)
        assertEquals(-122.4194, gps.longitude, 0.0001)
        assertEquals(10.0, gps.altitude, 0.01)
    }

    @Test
    fun `Barometer 1013 matches Python`() {
        val encoder = LPPEncoder()
        encoder.addBarometer(5u, 1013.2)
        assertEquals(PythonLPPReferenceBytes.barometer1013, encoder.encode())
    }

    @Test
    fun `Accelerometer 1g matches Python`() {
        val encoder = LPPEncoder()
        encoder.addAccelerometer(6u, 0.0, 0.0, 1.0)
        assertEquals(PythonLPPReferenceBytes.accelerometer1g, encoder.encode())
    }

    @Test
    fun `Accelerometer decode round trip`() {
        val encoder = LPPEncoder()
        encoder.addAccelerometer(6u, 0.5, -0.5, 1.0)
        val decoded = LPPDecoder.decodeStrict(encoder.encode())
        assertEquals(1, decoded.size)
        assertEquals(6.toUByte(), decoded[0].channel)
        assertEquals(LPPSensorType.ACCELEROMETER, decoded[0].type)
        val vector = assertIs<LPPValue.Vector3>(decoded[0].value)
        assertEquals(0.5, vector.x, 0.001)
        assertEquals(-0.5, vector.y, 0.001)
        assertEquals(1.0, vector.z, 0.001)
    }

    @Test
    fun `Multi-sensor payload`() {
        val encoder = LPPEncoder()
        encoder.addTemperature(1u, 25.5)
        encoder.addHumidity(2u, 65.0)
        encoder.addBarometer(3u, 1013.2)
        assertEquals(Bytes.fromHex("016700ff02688203732794"), encoder.encode())
        val decoded = LPPDecoder.decodeStrict(encoder.encode())
        assertEquals(3, decoded.size)
        assertEquals(1.toUByte(), decoded[0].channel)
        assertEquals(LPPSensorType.TEMPERATURE, decoded[0].type)
        assertEquals(25.5, assertIs<LPPValue.Float>(decoded[0].value).value, 0.1)
        assertEquals(2.toUByte(), decoded[1].channel)
        assertEquals(LPPSensorType.HUMIDITY, decoded[1].type)
        assertEquals(65.0, assertIs<LPPValue.Float>(decoded[1].value).value, 0.5)
        assertEquals(3.toUByte(), decoded[2].channel)
        assertEquals(LPPSensorType.BAROMETER, decoded[2].type)
        assertEquals(1013.2, assertIs<LPPValue.Float>(decoded[2].value).value, 0.1)
    }

    @Test
    fun `Voltage encoding`() {
        val encoder = LPPEncoder()
        encoder.addVoltage(1u, 3.8)
        assertEquals(Bytes.of(0x01, 0x74, 0x01, 0x7c), encoder.encode())
        val decoded = LPPDecoder.decodeStrict(encoder.encode())
        assertEquals(1, decoded.size)
        assertEquals(LPPSensorType.VOLTAGE, decoded[0].type)
        assertEquals(3.8, assertIs<LPPValue.Float>(decoded[0].value).value, 0.01)
    }

    @Test
    fun `Illuminance encoding`() {
        val encoder = LPPEncoder()
        encoder.addIlluminance(1u, 1000u)
        assertEquals(Bytes.of(0x01, 0x65, 0x03, 0xe8), encoder.encode())
        val decoded = LPPDecoder.decodeStrict(encoder.encode())
        assertEquals(1, decoded.size)
        assertEquals(1000L, assertIs<LPPValue.Integer>(decoded[0].value).value)
    }

    @Test
    fun `Digital IO encoding`() {
        val encoder = LPPEncoder()
        encoder.addDigitalInput(1u, 1u)
        encoder.addDigitalOutput(2u, 0u)
        assertEquals(Bytes.of(0x01, 0x00, 0x01, 0x02, 0x01, 0x00), encoder.encode())
        val decoded = LPPDecoder.decodeStrict(encoder.encode())
        assertEquals(2, decoded.size)
        assertTrue(assertIs<LPPValue.Digital>(decoded[0].value).value)
        assertFalse(assertIs<LPPValue.Digital>(decoded[1].value).value)
    }

    @Test
    fun `Gyrometer encoding`() {
        val encoder = LPPEncoder()
        encoder.addGyrometer(1u, 10.5, -5.25, 0.0)
        assertEquals(Bytes.of(0x01, 0x86, 0x04, 0x1a, 0xfd, 0xf3, 0x00, 0x00), encoder.encode())
        val decoded = LPPDecoder.decodeStrict(encoder.encode())
        assertEquals(1, decoded.size)
        val vector = assertIs<LPPValue.Vector3>(decoded[0].value)
        assertEquals(10.5, vector.x, 0.01)
        assertEquals(-5.25, vector.y, 0.01)
        assertEquals(0.0, vector.z, 0.01)
    }

    @Test
    fun `Load positive decodes as 3-byte signed divided by 1000`() {
        val decoded = LPPDecoder.decodeStrict(Bytes.of(0x07, 0x7a, 0x00, 0x30, 0x39))
        assertEquals(1, decoded.size)
        assertEquals(7.toUByte(), decoded[0].channel)
        assertEquals(LPPSensorType.LOAD, decoded[0].type)
        assertEquals(12.345, assertIs<LPPValue.Float>(decoded[0].value).value, 0.001)
    }

    @Test
    fun `Load negative round trips through 24-bit sign extension`() {
        val decoded = LPPDecoder.decodeStrict(Bytes.of(0x07, 0x7a, 0xff, 0xfa, 0x24))
        assertEquals(1, decoded.size)
        assertEquals(LPPSensorType.LOAD, decoded[0].type)
        assertEquals(-1.5, assertIs<LPPValue.Float>(decoded[0].value).value, 0.001)
    }

    @Test
    fun `Load consumes three bytes so the next datum stays aligned`() {
        val decoded = LPPDecoder.decodeStrict(Bytes.of(0x07, 0x7a, 0x00, 0x03, 0xe8, 0x01, 0x67, 0x00, 0xff))
        assertEquals(2, decoded.size)
        assertEquals(LPPSensorType.LOAD, decoded[0].type)
        assertEquals(1.0, assertIs<LPPValue.Float>(decoded[0].value).value, 0.001)
        assertEquals(LPPSensorType.TEMPERATURE, decoded[1].type)
        assertEquals(25.5, assertIs<LPPValue.Float>(decoded[1].value).value, 0.1)
    }

    @Test
    fun `Generic sensor decodes high bit set as a large positive integer`() {
        val decoded = LPPDecoder.decodeStrict(Bytes.of(0x01, 0x64, 0x80, 0x00, 0x00, 0x00))
        assertEquals(1, decoded.size)
        assertEquals(LPPSensorType.GENERIC_SENSOR, decoded[0].type)
        assertEquals(2_147_483_648L, assertIs<LPPValue.Integer>(decoded[0].value).value)
    }

    @TestFactory
    fun `Pinned Python frames decode without using the Kotlin encoder`(): List<DynamicTest> {
        val cases = listOf(
            PythonLPPReferenceBytes.temperature25_5 to LPPDataPoint(1u, LPPSensorType.TEMPERATURE, LPPValue.Float(25.5)),
            PythonLPPReferenceBytes.humidity65 to LPPDataPoint(2u, LPPSensorType.HUMIDITY, LPPValue.Float(65.0)),
            PythonLPPReferenceBytes.analog3_3 to LPPDataPoint(3u, LPPSensorType.ANALOG_INPUT, LPPValue.Float(3.3)),
            PythonLPPReferenceBytes.gpsSF to LPPDataPoint(4u, LPPSensorType.GPS, LPPValue.Gps(37.7749, -122.4194, 10.0)),
            PythonLPPReferenceBytes.barometer1013 to LPPDataPoint(5u, LPPSensorType.BAROMETER, LPPValue.Float(1013.2)),
            PythonLPPReferenceBytes.accelerometer1g to LPPDataPoint(6u, LPPSensorType.ACCELEROMETER, LPPValue.Vector3(0.0, 0.0, 1.0)),
        )
        return cases.map { (bytes, point) ->
            DynamicTest.dynamicTest("Python ${point.type}") {
                assertEquals(listOf(point), LPPDecoder.decodeStrict(bytes))
            }
        }
    }
}
