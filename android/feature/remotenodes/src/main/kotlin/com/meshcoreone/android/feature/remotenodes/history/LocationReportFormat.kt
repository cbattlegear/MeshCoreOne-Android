// PortedFrom: MC1/Views/RemoteNodes/Location/LocationReportFormat.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/RemoteNodes/Location/LocationReportRow.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.history

import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.protocol.lpp.LPPSensorType
import com.meshcoreone.android.feature.remotenodes.common.SwiftNumberFormat
import com.meshcoreone.android.feature.remotenodes.telemetry.MeasurementSystem
import com.meshcoreone.android.feature.remotenodes.telemetry.convertedValue
import com.meshcoreone.android.feature.remotenodes.telemetry.isConverted
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The calendar unit a relative recency is expressed in (Swift `RelativeDateTimeFormatter` units). */
enum class RelativeTimeUnit { YEARS, MONTHS, WEEKS, DAYS, HOURS, MINUTES, SECONDS }

/**
 * A single-unit recency such as "2h ago": [amount] whole [unit]s, in the past or (for zero and future
 * dates, as Apple renders "in 0s") the future. Swift renders it with `RelativeDateTimeFormatter`
 * (`.abbreviated`); [format] uses ICU's narrow style on device, the closest platform equivalent.
 */
data class RelativeTime(val amount: Long, val unit: RelativeTimeUnit, val isPast: Boolean) {
    /** Device-only (android.icu is not available to JVM unit tests). */
    fun format(locale: Locale): String {
        val formatter = RelativeDateTimeFormatter.getInstance(
            ULocale.forLocale(locale), null, RelativeDateTimeFormatter.Style.NARROW,
            android.icu.text.DisplayContext.CAPITALIZATION_NONE,
        )
        val direction = if (isPast) RelativeDateTimeFormatter.Direction.LAST else RelativeDateTimeFormatter.Direction.NEXT
        val icuUnit = when (unit) {
            RelativeTimeUnit.YEARS -> RelativeDateTimeFormatter.RelativeUnit.YEARS
            RelativeTimeUnit.MONTHS -> RelativeDateTimeFormatter.RelativeUnit.MONTHS
            RelativeTimeUnit.WEEKS -> RelativeDateTimeFormatter.RelativeUnit.WEEKS
            RelativeTimeUnit.DAYS -> RelativeDateTimeFormatter.RelativeUnit.DAYS
            RelativeTimeUnit.HOURS -> RelativeDateTimeFormatter.RelativeUnit.HOURS
            RelativeTimeUnit.MINUTES -> RelativeDateTimeFormatter.RelativeUnit.MINUTES
            RelativeTimeUnit.SECONDS -> RelativeDateTimeFormatter.RelativeUnit.SECONDS
        }
        return formatter.format(amount.toDouble(), direction, icuUnit)
    }
}

/**
 * Swift `LocationReportFormat`: coarse relative recency, absolute timestamp, fixed-precision
 * coordinates and altitude for one location report.
 */
object LocationReportFormat {
    private const val COORDINATE_DECIMALS = 4
    private const val METERS_SYMBOL = "m"
    private const val FEET_SYMBOL = "ft"
    private const val DAYS_PER_WEEK = 7L

    /**
     * Apple's unit choice, verified with swiftc (oracle REL lines): the calendar components from [now] to
     * [date] in [zone] (years, months, weeks, days, hours, minutes, seconds), and the first non-zero one,
     * truncated ("89 s" is 1 minute, 30 days in a 31-day month is 4 weeks). All-zero is "in 0 seconds".
     */
    fun relativeTime(date: Instant, now: Instant, zone: ZoneId): RelativeTime {
        var cursor: ZonedDateTime = now.atZone(zone)
        val target = date.atZone(zone)
        val isPast = date.isBefore(now)
        for ((unit, chrono) in RELATIVE_UNITS) {
            val whole = cursor.until(target, chrono)
            val amount = if (unit == RelativeTimeUnit.WEEKS) whole / DAYS_PER_WEEK else whole
            if (amount != 0L) return RelativeTime(kotlin.math.abs(amount), unit, isPast)
            if (unit != RelativeTimeUnit.WEEKS) cursor = cursor.plus(whole, chrono)
        }
        return RelativeTime(0, RelativeTimeUnit.SECONDS, isPast = false)
    }

    /** Swift `absoluteTime(for:)` ("Jul 13, 07:41"); see [HistoryDateFormat] for the deviation. */
    fun absoluteTime(date: Instant, locale: Locale, zone: ZoneId): String =
        HistoryDateFormat.monthDayTime(date, locale, zone)

    /**
     * Swift `String(format: "%.4f, %.4f", lat, lon)`: C printf, so locale-independent dots and
     * round-half-even on the exact binary value (37.78475 is "37.7848", 89.99995 is "89.9999").
     */
    fun coordinates(coordinate: Coordinate): String =
        "${printfFixed(coordinate.latitude, COORDINATE_DECIMALS)}, ${printfFixed(coordinate.longitude, COORDINATE_DECIMALS)}"

    /**
     * Swift `altitude(_:)` in whole units of the locale's length unit: metres for metric, feet for US/UK
     * (Foundation's conversion factor). Swift's `MeasurementFormatter.naturalScale` also switches to km,
     * mi, in or mm by magnitude (999 m is "1 mi" in en_US, 0 m is "0 mm" in de_DE; oracle ALT lines);
     * that scaling is deliberately not reproduced (documented deviation), and the unit symbols are the
     * Latin "m"/"ft" in every locale.
     */
    fun altitude(meters: Double, locale: Locale, system: MeasurementSystem): String {
        val value = LPPSensorType.ALTITUDE.convertedValue(meters, system)
        val symbol = if (LPPSensorType.ALTITUDE.isConverted(system)) FEET_SYMBOL else METERS_SYMBOL
        return "${SwiftNumberFormat.fixed(value, 0, locale)} $symbol"
    }

    /** C `%.<digits>f` for a double (Apple libc: exact binary value, half-even, signed zero kept). */
    internal fun printfFixed(value: Double, digits: Int): String {
        val negative = value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)
        val sign = if (negative) "-" else ""
        return when {
            value.isNaN() -> "nan"
            value.isInfinite() -> "${sign}inf"
            else -> sign + BigDecimal(value).abs().setScale(digits, RoundingMode.HALF_EVEN).toPlainString()
        }
    }

    private val RELATIVE_UNITS: List<Pair<RelativeTimeUnit, ChronoUnit>> = listOf(
        RelativeTimeUnit.YEARS to ChronoUnit.YEARS,
        RelativeTimeUnit.MONTHS to ChronoUnit.MONTHS,
        RelativeTimeUnit.WEEKS to ChronoUnit.DAYS,
        RelativeTimeUnit.DAYS to ChronoUnit.DAYS,
        RelativeTimeUnit.HOURS to ChronoUnit.HOURS,
        RelativeTimeUnit.MINUTES to ChronoUnit.MINUTES,
        RelativeTimeUnit.SECONDS to ChronoUnit.SECONDS,
    )
}

/** Swift `LocationReportRow`'s non-layout text. */
object LocationReportRowText {
    /** Joins the absolute time, coordinates and altitude on the detail line. */
    const val SEPARATOR = " · "

    /** The recency line, relative to the injected clock's [now] (Swift reads `.now`). */
    fun relativeTime(snapshot: NodeStatusSnapshotDTO, now: Instant, zone: ZoneId): RelativeTime =
        LocationReportFormat.relativeTime(snapshot.timestamp, now, zone)

    /**
     * "Jul 13, 07:41 · 37.7847, -122.4012 · 42 m": the time alone without a valid fix; altitude only
     * when the fix carried one ("no altitude" differs from sea level).
     */
    fun detailLine(snapshot: NodeStatusSnapshotDTO, locale: Locale, zone: ZoneId, system: MeasurementSystem): String {
        val time = LocationReportFormat.absoluteTime(snapshot.timestamp, locale, zone)
        val coordinate = snapshot.validCoordinate ?: return time
        val withCoordinates = time + SEPARATOR + LocationReportFormat.coordinates(coordinate)
        val altitude = snapshot.altitude ?: return withCoordinates
        return withCoordinates + SEPARATOR + LocationReportFormat.altitude(altitude, locale, system)
    }
}
