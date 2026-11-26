// PortedFrom: MeshCore/Sources/MeshCore/LPP/LPPDecoder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.lpp

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LPPSensorTypeTest {
    private data class Expected(val type: LPPSensorType, val raw: Int, val size: Int, val name: String, val unit: String = "")

    private val expected = listOf(
        Expected(LPPSensorType.DIGITAL_INPUT, 0, 1, "Digital Input"),
        Expected(LPPSensorType.DIGITAL_OUTPUT, 1, 1, "Digital Output"),
        Expected(LPPSensorType.ANALOG_INPUT, 2, 2, "Analog Input"),
        Expected(LPPSensorType.ANALOG_OUTPUT, 3, 2, "Analog Output"),
        Expected(LPPSensorType.GENERIC_SENSOR, 100, 4, "Sensor"),
        Expected(LPPSensorType.ILLUMINANCE, 101, 2, "Illuminance", "lux"),
        Expected(LPPSensorType.PRESENCE, 102, 1, "Presence"),
        Expected(LPPSensorType.TEMPERATURE, 103, 2, "Temperature", "\u00b0C"),
        Expected(LPPSensorType.HUMIDITY, 104, 1, "Humidity", "%"),
        Expected(LPPSensorType.ACCELEROMETER, 113, 6, "Accelerometer"),
        Expected(LPPSensorType.BAROMETER, 115, 2, "Pressure", "hPa"),
        Expected(LPPSensorType.VOLTAGE, 116, 2, "Voltage", "V"),
        Expected(LPPSensorType.CURRENT, 117, 2, "Current", "A"),
        Expected(LPPSensorType.FREQUENCY, 118, 4, "Frequency", "Hz"),
        Expected(LPPSensorType.PERCENTAGE, 120, 1, "Percentage", "%"),
        Expected(LPPSensorType.ALTITUDE, 121, 2, "Altitude", "m"),
        Expected(LPPSensorType.LOAD, 122, 3, "Load", "kg"),
        Expected(LPPSensorType.CONCENTRATION, 125, 2, "Concentration", "ppm"),
        Expected(LPPSensorType.POWER, 128, 2, "Power", "W"),
        Expected(LPPSensorType.DISTANCE, 130, 4, "Distance", "m"),
        Expected(LPPSensorType.ENERGY, 131, 4, "Energy", "kWh"),
        Expected(LPPSensorType.DIRECTION, 132, 2, "Direction", "\u00b0"),
        Expected(LPPSensorType.UNIX_TIME, 133, 4, "Time"),
        Expected(LPPSensorType.GYROMETER, 134, 6, "Gyrometer"),
        Expected(LPPSensorType.COLOUR, 135, 3, "Colour"),
        Expected(LPPSensorType.GPS, 136, 9, "GPS"),
        Expected(LPPSensorType.SWITCH_VALUE, 142, 1, "Switch"),
    )

    @TestFactory
    fun `Wire IDs widths display names and units match the source`(): List<DynamicTest> =
        expected.map { row ->
            DynamicTest.dynamicTest("metadata ${row.name}") {
                assertEquals(row.raw.toUByte(), row.type.rawValue)
                assertEquals(row.size, row.type.dataSize)
                assertEquals(row.name, row.type.displayName)
                assertEquals(row.unit, row.type.unit)
                assertEquals(row.type, LPPSensorType.fromRawValue(row.raw.toUByte()))
                assertEquals(row.type, LPPSensorType.fromName(row.name))
            }
        }

    @Test
    fun `All unknown raw IDs are absent without ordinal persistence`() {
        assertEquals(expected.map { it.type }, LPPSensorType.entries)
        val rawIDs = expected.map { it.raw }.toSet()
        for (raw in 0..255) {
            assertEquals(raw in rawIDs, LPPSensorType.fromRawValue(raw.toUByte()) != null)
        }
        assertNull(LPPSensorType.fromRawValue(4u))
        assertEquals(100.toUByte(), LPPSensorType.GENERIC_SENSOR.rawValue)
    }

    @Test
    fun `Name lookup is exact and does not add units aliases or localization`() {
        for (name in listOf("", "temperature", " TEMPERATURE ", "Temperature ", "Barometer", "RGB", "Switch Value", "25 C")) {
            assertNull(LPPSensorType.fromName(name))
        }
        assertEquals(LPPSensorType.BAROMETER, LPPSensorType.fromName("Pressure"))
        assertEquals("", LPPSensorType.GPS.unit)
        assertEquals("", LPPSensorType.ACCELEROMETER.unit)
        assertEquals("", LPPSensorType.GYROMETER.unit)
    }
}
