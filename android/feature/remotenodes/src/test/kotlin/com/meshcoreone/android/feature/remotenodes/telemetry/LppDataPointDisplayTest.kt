// PortedFrom: MC1Tests/Protocol/LPPDataPointDisplayTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.telemetry

import com.meshcoreone.android.core.protocol.lpp.LPPDataPoint
import com.meshcoreone.android.core.protocol.lpp.LPPSensorType
import com.meshcoreone.android.core.protocol.lpp.LPPValue
import com.meshcoreone.android.feature.remotenodes.common.SwiftNumberFormat
import com.meshcoreone.android.feature.remotenodes.support.OriginalCase
import java.util.Locale
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class LppDataPointDisplayTest {
    private fun point(type: LPPSensorType, value: LPPValue) = LPPDataPoint(0u, type, value)

    @Test @OriginalCase("LPPDataPointDisplayTests::Battery percentage at minimum voltage (3.0V) returns 0%()")
    fun `battery percentage at minimum voltage returns 0`() {
        assertEquals(0, point(LPPSensorType.VOLTAGE, LPPValue.Float(3.0)).batteryPercentage)
    }

    @Test @OriginalCase("LPPDataPointDisplayTests::Battery percentage at maximum voltage (4.2V) returns 100%()")
    fun `battery percentage at maximum voltage returns 100`() {
        assertEquals(100, point(LPPSensorType.VOLTAGE, LPPValue.Float(4.2)).batteryPercentage)
    }

    @Test @OriginalCase("LPPDataPointDisplayTests::Battery percentage at midpoint voltage (3.6V) returns approximately 50%()")
    fun `battery percentage at midpoint voltage returns 50`() {
        assertEquals(50, point(LPPSensorType.VOLTAGE, LPPValue.Float(3.6)).batteryPercentage)
    }

    @Test @OriginalCase("LPPDataPointDisplayTests::Battery percentage at 3.9V returns 75%()")
    fun `battery percentage at 3_9V returns 75`() {
        assertEquals(75, point(LPPSensorType.VOLTAGE, LPPValue.Float(3.9)).batteryPercentage)
    }

    @Test @OriginalCase("LPPDataPointDisplayTests::Battery percentage below minimum voltage clamps to 0%()")
    fun `battery percentage below minimum clamps to 0`() {
        assertEquals(0, point(LPPSensorType.VOLTAGE, LPPValue.Float(2.5)).batteryPercentage)
    }

    @Test @OriginalCase("LPPDataPointDisplayTests::Battery percentage above maximum voltage clamps to 100%()")
    fun `battery percentage above maximum clamps to 100`() {
        assertEquals(100, point(LPPSensorType.VOLTAGE, LPPValue.Float(5.0)).batteryPercentage)
    }

    @Test @OriginalCase("LPPDataPointDisplayTests::Battery percentage for non-voltage type returns nil()")
    fun `battery percentage for non-voltage type is null`() {
        assertNull(point(LPPSensorType.TEMPERATURE, LPPValue.Float(25.0)).batteryPercentage)
    }

    @Test @OriginalCase("LPPDataPointDisplayTests::Battery percentage for integer value returns nil()")
    fun `battery percentage for integer value is null`() {
        assertNull(point(LPPSensorType.VOLTAGE, LPPValue.Integer(4)).batteryPercentage)
    }

    @Test @OriginalCase("LPPDataPointDisplayTests::Battery percentage for percentage type returns nil()")
    fun `battery percentage for percentage type is null`() {
        assertNull(point(LPPSensorType.PERCENTAGE, LPPValue.Integer(75)).batteryPercentage)
    }

    @Test @OriginalCase("LPPDataPointDisplayTests::Voltage formatted value includes V suffix()")
    fun `voltage formatted value includes V suffix`() {
        val text = point(LPPSensorType.VOLTAGE, LPPValue.Float(3.85)).formattedValue(Locale.US, MeasurementSystem.METRIC)
        assertEquals("${SwiftNumberFormat.fixed(3.85, 3, Locale.US)} V", text)
        assertEquals("3.850 V", text)
        assertEquals("3,850 V", point(LPPSensorType.VOLTAGE, LPPValue.Float(3.85)).formattedValue(Locale.GERMANY, MeasurementSystem.METRIC))
    }

    @Test @OriginalCase("LPPDataPointDisplayTests::Temperature formatted value uses locale-appropriate unit()", "platform-adaptation")
    fun `temperature formatted value uses the measurement system unit`() {
        MeasurementSystem.entries.forEach { system ->
            val type = LPPSensorType.TEMPERATURE
            val converted = type.convertedValue(25.5, system)
            val expected = "${SwiftNumberFormat.fixed(converted, type.convertedFractionLength(system), Locale.US)} " +
                type.localizedUnitSymbol(system)
            assertEquals(expected, point(type, LPPValue.Float(25.5)).formattedValue(Locale.US, system), system.name)
        }
        assertEquals("25.5 °C", point(LPPSensorType.TEMPERATURE, LPPValue.Float(25.5)).formattedValue(Locale.US, MeasurementSystem.METRIC))
        // Foundation converts 25.5 °C to 77.89999999999563 °F (swiftc oracle), which rounds to 77.9.
        assertEquals("77.9 °F", point(LPPSensorType.TEMPERATURE, LPPValue.Float(25.5)).formattedValue(Locale.US, MeasurementSystem.US))
    }

    @Test @OriginalCase("LPPDataPointDisplayTests::Temperature conversion from Celsius to Fahrenheit()", "platform-adaptation")
    fun `temperature converts from Celsius to Fahrenheit outside metric`() {
        assertEquals(25.0, LPPSensorType.TEMPERATURE.convertedValue(25.0, MeasurementSystem.METRIC))
        listOf(MeasurementSystem.US, MeasurementSystem.UK).forEach { system ->
            assertTrue(abs(LPPSensorType.TEMPERATURE.convertedValue(25.0, system) - 77.0) < 0.01, system.name)
        }
        assertEquals(77.89999999999563, LPPSensorType.TEMPERATURE.convertedValue(25.5, MeasurementSystem.US))
        assertEquals(29.92124356615747, LPPSensorType.BAROMETER.convertedValue(1013.25, MeasurementSystem.US))
        assertEquals(137.79527559055117, LPPSensorType.ALTITUDE.convertedValue(42.0, MeasurementSystem.US))
    }

    @Test @OriginalCase("LPPDataPointDisplayTests::Voltage is not locale-sensitive()")
    fun `voltage is not measurement-system sensitive`() {
        MeasurementSystem.entries.forEach { system ->
            assertEquals(3.85, LPPSensorType.VOLTAGE.convertedValue(3.85, system))
            assertEquals("V", LPPSensorType.VOLTAGE.localizedUnitSymbol(system))
        }
    }

    @Test @OriginalCase("LPPDataPointDisplayTests::Altitude uses locale-appropriate unit symbol()", "platform-adaptation")
    fun `altitude uses the measurement system unit symbol`() {
        assertEquals("m", LPPSensorType.ALTITUDE.localizedUnitSymbol(MeasurementSystem.METRIC))
        assertEquals("ft", LPPSensorType.ALTITUDE.localizedUnitSymbol(MeasurementSystem.US))
        assertEquals("ft", LPPSensorType.ALTITUDE.localizedUnitSymbol(MeasurementSystem.UK))
    }
}
