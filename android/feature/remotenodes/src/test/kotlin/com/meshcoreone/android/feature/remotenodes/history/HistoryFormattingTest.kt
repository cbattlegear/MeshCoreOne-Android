// AndroidOnly: WP-313 Native oracle tests (history_location.swift.txt) for printf coordinates, relative-time units, altitude, date patterns and range boundaries.
package com.meshcoreone.android.feature.remotenodes.history

import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.feature.remotenodes.support.bytes
import com.meshcoreone.android.feature.remotenodes.telemetry.MeasurementSystem
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class HistoryFormattingTest {
    @Test
    fun `printf fixed matches Apple libc on ties, signed zero and non-finite values`() {
        val cases = listOf(
            -0.0 to "-0.0000", -0.00001 to "-0.0000", 0.00005 to "0.0001", 0.00015 to "0.0001",
            1.00005 to "1.0001", 2.00025 to "2.0002", 0.125 to "0.1250", 1.0005 to "1.0005",
            89.99995 to "89.9999", 37.78475 to "37.7848", -122.40125 to "-122.4013",
            Double.NaN to "nan", Double.POSITIVE_INFINITY to "inf", Double.NEGATIVE_INFINITY to "-inf",
        )
        cases.forEach { (value, expected) -> assertEquals(expected, LocationReportFormat.printfFixed(value, 4), "$value") }
    }

    @Test
    fun `relative time picks Apple's first non-zero calendar unit`() {
        val now = Instant.ofEpochSecond(1_000_000)
        val day = 86_400L
        val cases = listOf(
            0L to RelativeTime(0, RelativeTimeUnit.SECONDS, false),
            -1L to RelativeTime(1, RelativeTimeUnit.SECONDS, true),
            -59L to RelativeTime(59, RelativeTimeUnit.SECONDS, true),
            -60L to RelativeTime(1, RelativeTimeUnit.MINUTES, true),
            -89L to RelativeTime(1, RelativeTimeUnit.MINUTES, true),
            -5_400L to RelativeTime(1, RelativeTimeUnit.HOURS, true),
            -7_200L to RelativeTime(2, RelativeTimeUnit.HOURS, true),
            -86_399L to RelativeTime(23, RelativeTimeUnit.HOURS, true),
            -day to RelativeTime(1, RelativeTimeUnit.DAYS, true),
            -6 * day to RelativeTime(6, RelativeTimeUnit.DAYS, true),
            -7 * day to RelativeTime(1, RelativeTimeUnit.WEEKS, true),
            -13 * day to RelativeTime(1, RelativeTimeUnit.WEEKS, true),
            -30 * day to RelativeTime(4, RelativeTimeUnit.WEEKS, true),
            -35 * day to RelativeTime(1, RelativeTimeUnit.MONTHS, true),
            -364 * day to RelativeTime(11, RelativeTimeUnit.MONTHS, true),
            -400 * day to RelativeTime(1, RelativeTimeUnit.YEARS, true),
            30L to RelativeTime(30, RelativeTimeUnit.SECONDS, false),
            7_200L to RelativeTime(2, RelativeTimeUnit.HOURS, false),
            3 * day to RelativeTime(3, RelativeTimeUnit.DAYS, false),
        )
        cases.forEach { (offset, expected) ->
            assertEquals(expected, LocationReportFormat.relativeTime(now.plusSeconds(offset), now, UTC), "offset $offset")
        }
    }

    @Test
    fun `relative recency preserves subsecond fallback and exact unit boundaries`() {
        val now = Instant.parse("2024-07-13T07:41:00Z")
        listOf(-999_999_999L, 0L, 999_999_999L).forEach { nanos ->
            assertEquals(
                RelativeTime(0, RelativeTimeUnit.SECONDS, false),
                LocationReportFormat.relativeTime(now.plusNanos(nanos), now, UTC),
            )
        }
        listOf(
            59L to RelativeTime(59, RelativeTimeUnit.SECONDS, false),
            60L to RelativeTime(1, RelativeTimeUnit.MINUTES, false),
            3_599L to RelativeTime(59, RelativeTimeUnit.MINUTES, false),
            3_600L to RelativeTime(1, RelativeTimeUnit.HOURS, false),
            86_400L to RelativeTime(1, RelativeTimeUnit.DAYS, false),
            604_800L to RelativeTime(1, RelativeTimeUnit.WEEKS, false),
        ).forEach { (seconds, future) ->
            assertEquals(future, LocationReportFormat.relativeTime(now.plusSeconds(seconds), now, UTC))
            assertEquals(future.copy(isPast = true), LocationReportFormat.relativeTime(now.minusSeconds(seconds), now, UTC))
        }
    }

    @Test
    fun `relative recency uses local calendar days across daylight saving transitions`() {
        val zone = ZoneId.of("America/New_York")
        listOf(
            "2024-03-10T16:00:00Z" to "2024-03-09T17:00:00Z",
            "2024-11-03T17:00:00Z" to "2024-11-02T16:00:00Z",
        ).forEach { (current, previous) ->
            val now = Instant.parse(current)
            val date = Instant.parse(previous)
            assertEquals(RelativeTime(1, RelativeTimeUnit.DAYS, true), LocationReportFormat.relativeTime(date, now, zone))
            assertEquals(RelativeTime(1, RelativeTimeUnit.DAYS, false), LocationReportFormat.relativeTime(now, date, zone))
        }
    }

    @Test
    fun `altitude rounds to whole metres or feet by measurement system`() {
        assertEquals("138 ft", LocationReportFormat.altitude(42.0, EN_US, MeasurementSystem.US))
        assertEquals("138 ft", LocationReportFormat.altitude(42.0, Locale.UK, MeasurementSystem.UK))
        assertEquals("42 m", LocationReportFormat.altitude(42.0, Locale.GERMANY, MeasurementSystem.METRIC))
        assertEquals("0 m", LocationReportFormat.altitude(0.0, EN_US, MeasurementSystem.METRIC))
        assertEquals("2 m", LocationReportFormat.altitude(2.5, EN_US, MeasurementSystem.METRIC))
        assertEquals("1.234 m", LocationReportFormat.altitude(1234.0, Locale.GERMANY, MeasurementSystem.METRIC))
        assertEquals("-164 ft", LocationReportFormat.altitude(-50.0, EN_US, MeasurementSystem.US))
    }

    @Test
    fun `year is removed from localized patterns with its separator`() {
        val cases = mapOf(
            "MMM d, y" to "MMM d", "MMM d, y, h:mm a" to "MMM d, h:mm a", "y年M月d日" to "M月d日",
            "d MMM y 'г'." to "d MMM", "y. M. d." to "M. d.", "dd.MM.y, HH:mm" to "dd.MM, HH:mm",
            "d MMM y" to "d MMM", "h:mm a" to "h:mm a",
        )
        cases.forEach { (pattern, expected) -> assertEquals(expected, HistoryDateFormat.withoutYear(pattern), pattern) }
    }

    @Test
    fun `month-day formats omit the year`() {
        val date = Instant.parse("2024-07-13T07:41:00Z")
        assertEquals("Jul 13", HistoryDateFormat.monthDay(date, EN_US, UTC))
        val withTime = LocationReportFormat.absoluteTime(date, EN_US, UTC)
        assertTrue(withTime.startsWith("Jul 13, 7:41"), withTime)
        assertTrue(withTime.endsWith("AM"), withTime)
        listOf(Locale.GERMANY, Locale.FRANCE, Locale.KOREA, Locale.SIMPLIFIED_CHINESE, Locale.forLanguageTag("ru-RU")).forEach {
            val text = HistoryDateFormat.monthDayTime(date, it, UTC)
            assertFalse("2024" in text, "$it: $text")
            assertTrue("13" in text && ("7:41" in text || "07:41" in text), "$it: $text")
        }
        assertEquals("Jul 12", HistoryDateFormat.monthDay(date, EN_US, ZoneId.of("Pacific/Honolulu")))
    }

    @Test
    fun `location row detail line adds coordinates and altitude only when present`() {
        val date = Instant.parse("2024-07-13T07:41:00Z")
        val time = LocationReportFormat.absoluteTime(date, EN_US, UTC)
        fun line(lat: Double?, lon: Double?, alt: Double?) = LocationReportRowText.detailLine(
            NodeStatusSnapshotDTO(timestamp = date, nodePublicKey = bytes(32, 1), latitude = lat, longitude = lon, altitude = alt),
            EN_US, UTC, MeasurementSystem.US,
        )
        assertEquals(time, line(null, null, 42.0))
        assertEquals(time, line(0.0, 0.0, 42.0))
        assertEquals(time, line(91.0, -122.401233, 42.0))
        assertEquals(time, line(37.784712, null, 42.0))
        assertEquals("$time · 37.7847, -122.4012", line(37.784712, -122.401233, null))
        assertEquals("$time · 37.7847, -122.4012 · 0 ft", line(37.784712, -122.401233, 0.0))
        val snapshot = NodeStatusSnapshotDTO(timestamp = date, nodePublicKey = bytes(32, 1))
        assertEquals(
            RelativeTime(2, RelativeTimeUnit.HOURS, true),
            LocationReportRowText.relativeTime(snapshot, date.plusSeconds(7_200), UTC),
        )
    }

    @Test
    fun `range start dates follow Calendar arithmetic across DST and month ends`() {
        val zone = ZoneId.of("America/New_York")
        val cases = listOf(
            listOf("2024-03-31T12:00:00Z", "2024-03-24T12:00:00Z", "2024-02-29T13:00:00Z", "2023-12-31T13:00:00Z"),
            listOf("2024-03-10T12:00:00Z", "2024-03-03T13:00:00Z", "2024-02-10T13:00:00Z", "2023-12-10T13:00:00Z"),
            listOf("2024-05-31T03:30:00Z", "2024-05-24T03:30:00Z", "2024-05-01T03:30:00Z", "2024-03-01T04:30:00Z"),
            listOf("2024-11-07T06:30:00Z", "2024-10-31T05:30:00Z", "2024-10-07T05:30:00Z", "2024-08-07T05:30:00Z"),
        )
        cases.forEach { row ->
            val now = Instant.parse(row[0])
            val expected = row.drop(1).map { Instant.parse(it) }
            val actual = listOf(HistoryTimeRange.WEEK, HistoryTimeRange.MONTH, HistoryTimeRange.THREE_MONTHS)
                .map { it.startDate(now, zone) }
            assertEquals(expected, actual, row[0])
            assertNull(HistoryTimeRange.ALL.startDate(now, zone))
        }
    }

    @Test
    fun `range filters include the start instant and keep everything for all`() {
        val now = Instant.parse("2024-03-31T12:00:00Z")
        val start = Instant.parse("2024-03-24T12:00:00Z")
        val key = bytes(32, 1)
        val atStart = NodeStatusSnapshotDTO(timestamp = start, nodePublicKey = key)
        val before = NodeStatusSnapshotDTO(timestamp = start.minusNanos(1), nodePublicKey = key)
        val snapshots = listOf(before, atStart)
        assertEquals(listOf(atStart), snapshots.filtered(HistoryTimeRange.WEEK, now, UTC))
        assertEquals(snapshots, snapshots.filtered(HistoryTimeRange.ALL, now, UTC))
        assertEquals(snapshots, snapshots.filtered(HistoryTimeRange.MONTH, now, UTC))
        val points = listOf(ChartDataPoint(before.id, before.timestamp, 1.0), ChartDataPoint(atStart.id, atStart.timestamp, 2.0))
        assertEquals(listOf(2.0), points.filteredPoints(HistoryTimeRange.WEEK, now, UTC).map { it.value })
        val earlier = NodeStatusSnapshotDTO(timestamp = Instant.parse("2023-12-31T11:59:59Z"), nodePublicKey = key)
        assertEquals(emptyList(), listOf(earlier).filtered(HistoryTimeRange.THREE_MONTHS, now, UTC))
    }
}
