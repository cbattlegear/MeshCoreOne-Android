// PortedFrom: MC1Tests/Formatters/LocationReportFormatTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.history

import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.feature.remotenodes.support.OriginalCase
import com.meshcoreone.android.feature.remotenodes.telemetry.MeasurementSystem
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class LocationReportFormatTest {
    @Test @OriginalCase("LocationReportFormatTests::Coordinates format to four decimal places with a dotted separator()")
    fun `Coordinates format to four decimal places with a dotted separator`() {
        assertEquals("37.7847, -122.4012", LocationReportFormat.coordinates(Coordinate(37.784712, -122.401233)))
    }

    @Test @OriginalCase("LocationReportFormatTests::Coordinates round to four places rather than truncate()")
    fun `Coordinates round to four places rather than truncate`() {
        assertEquals("37.7848, -122.4013", LocationReportFormat.coordinates(Coordinate(37.78475, -122.40125)))
    }

    @Test @OriginalCase("LocationReportFormatTests::Zero coordinates format without dropping the pair()")
    fun `Zero coordinates format without dropping the pair`() {
        assertEquals("0.0000, 0.0000", LocationReportFormat.coordinates(Coordinate(0.0, 0.0)))
    }

    @Test @OriginalCase("LocationReportFormatTests::Relative time for a past report is non-empty()", "platform-adaptation")
    fun `Relative time for a past report is non-empty`() {
        // RelativeDateTimeFormatter text comes from android.icu on device, which JVM tests cannot load;
        // the portable part (unit, amount, direction) is asserted: two hours ago renders as "2h ago".
        val now = Instant.ofEpochSecond(1_000_000)
        val relative = LocationReportFormat.relativeTime(now.minusSeconds(2 * 3600), now, UTC)
        assertEquals(RelativeTime(2, RelativeTimeUnit.HOURS, isPast = true), relative)
    }

    @Test @OriginalCase("LocationReportFormatTests::Altitude formats to a non-empty localized length()")
    fun `Altitude formats to a non-empty localized length`() {
        assertTrue(LocationReportFormat.altitude(42.0, EN_US, MeasurementSystem.US).isNotEmpty())
        assertTrue(LocationReportFormat.altitude(42.0, EN_US, MeasurementSystem.METRIC).isNotEmpty())
    }

    @Test @OriginalCase("LocationReportFormatTests::Sea-level altitude still renders()")
    fun `Sea-level altitude still renders`() {
        assertTrue(LocationReportFormat.altitude(0.0, EN_US, MeasurementSystem.US).isNotEmpty())
        assertTrue(LocationReportFormat.altitude(0.0, EN_US, MeasurementSystem.METRIC).isNotEmpty())
    }
}
