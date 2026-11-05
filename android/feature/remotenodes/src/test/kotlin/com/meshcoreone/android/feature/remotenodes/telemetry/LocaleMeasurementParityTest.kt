// PortedFrom: MC1Tests/Protocol/LPPDataPointDisplayTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Formatters/LocationReportFormatTests.swift@db14559b39d32322b06477c6ae676112f583db50
// AndroidOnly: WP-313 Independent Android locale/unit expectations supplement the original numerical families.
package com.meshcoreone.android.feature.remotenodes.telemetry

import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.protocol.lpp.LPPDataPoint
import com.meshcoreone.android.core.protocol.lpp.LPPSensorType
import com.meshcoreone.android.core.protocol.lpp.LPPValue
import com.meshcoreone.android.feature.remotenodes.history.LocationReportFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class LocaleMeasurementParityTest {
    private data class Case(
        val tag: String,
        val system: MeasurementSystem,
        val temperature: String,
        val pressure: String,
        val altitude: String,
        val distance: String,
        val voltage: String,
        val reportAltitude: String,
    )

    @Test fun regionalUnitsAndExplicitOverridesHaveIndependentExpectedValues() {
        val cases = listOf(
            Case("en-US", MeasurementSystem.US, "77.9 \u00b0F", "29.92 inHg", "138 ft", "3.3 ft", "3.850 V", "138 ft"),
            Case("en-GB", MeasurementSystem.UK, "77.9 \u00b0F", "29.92 inHg", "138 ft", "3.3 ft", "3.850 V", "138 ft"),
            Case("de-DE", MeasurementSystem.METRIC, "25,5 \u00b0C", "1.013,2 hPa", "42,0 m", "1,000 m", "3,850 V", "42 m"),
            Case("en-CA", MeasurementSystem.METRIC, "25.5 \u00b0C", "1,013.2 hPa", "42.0 m", "1.000 m", "3.850 V", "42 m"),
            Case("en-US-u-ms-metric", MeasurementSystem.METRIC, "25.5 \u00b0C", "1,013.2 hPa", "42.0 m", "1.000 m", "3.850 V", "42 m"),
            Case("de-DE-u-ms-ussystem", MeasurementSystem.US, "77,9 \u00b0F", "29,92 inHg", "138 ft", "3,3 ft", "3,850 V", "138 ft"),
            Case("de-DE-u-ms-uksystem", MeasurementSystem.UK, "77,9 \u00b0F", "29,92 inHg", "138 ft", "3,3 ft", "3,850 V", "138 ft"),
            Case("en-US-u-rg-gbzzzz", MeasurementSystem.UK, "77.9 \u00b0F", "29.92 inHg", "138 ft", "3.3 ft", "3.850 V", "138 ft"),
        )
        for (case in cases) {
            val locale = Locale.forLanguageTag(case.tag)
            val system = MeasurementSystem.of(locale)
            assertEquals(case.tag, case.system, system)
            fun display(type: LPPSensorType, value: Double) =
                LPPDataPoint(1u, type, LPPValue.Float(value)).formattedValue(locale, system)
            assertEquals(case.tag, case.temperature, display(LPPSensorType.TEMPERATURE, 25.5))
            assertEquals(case.tag, case.pressure, display(LPPSensorType.BAROMETER, 1013.2))
            assertEquals(case.tag, case.altitude, display(LPPSensorType.ALTITUDE, 42.0))
            assertEquals(case.tag, case.distance, display(LPPSensorType.DISTANCE, 1.0))
            assertEquals(case.tag, case.voltage, display(LPPSensorType.VOLTAGE, 3.85))
            assertEquals(case.tag, case.reportAltitude, LocationReportFormat.altitude(42.0, locale, system))
        }
    }

    @Test fun seaLevelNegativeAltitudeAndLargeAltitudeKeepExplicitWholeUnits() {
        assertEquals("0 m", LocationReportFormat.altitude(0.0, Locale.GERMANY, MeasurementSystem.METRIC))
        assertEquals("0 ft", LocationReportFormat.altitude(0.0, Locale.US, MeasurementSystem.US))
        assertEquals("-12 m", LocationReportFormat.altitude(-12.0, Locale.GERMANY, MeasurementSystem.METRIC))
        assertEquals("-39 ft", LocationReportFormat.altitude(-12.0, Locale.US, MeasurementSystem.US))
        assertEquals("1.234 m", LocationReportFormat.altitude(1234.0, Locale.GERMANY, MeasurementSystem.METRIC))
        assertEquals("4,049 ft", LocationReportFormat.altitude(1234.0, Locale.US, MeasurementSystem.US))
        assertEquals("37.7847, -122.4012", LocationReportFormat.coordinates(Coordinate(37.784712, -122.401233)))
    }

    @Test fun voltageUnitsAndBatteryPredicatesDoNotChangeWithDisplayLocale() {
        val voltage = LPPDataPoint(1u, LPPSensorType.VOLTAGE, LPPValue.Float(3.9))
        for (tag in listOf("en-US", "en-GB", "de-DE", "en-US-u-ms-metric")) {
            val locale = Locale.forLanguageTag(tag)
            assertEquals(75, voltage.batteryPercentage)
            assertEquals(3.9, LPPSensorType.VOLTAGE.convertedValue(3.9, MeasurementSystem.of(locale)), 0.0)
            assertEquals("V", LPPSensorType.VOLTAGE.localizedUnitSymbol(MeasurementSystem.of(locale)))
        }
    }
}
