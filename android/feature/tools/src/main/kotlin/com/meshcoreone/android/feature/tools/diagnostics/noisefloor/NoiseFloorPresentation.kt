// PortedFrom: MC1/Views/Tools/NoiseFloorView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.diagnostics.noisefloor

import com.meshcoreone.android.feature.tools.diagnostics.FormattedStringIds
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.feature.tools.diagnostics.DiagnosticsText
import com.meshcoreone.android.feature.tools.diagnostics.text.SwiftNumbers
import java.time.Duration
import java.time.Instant

/** Which body the noise-floor screen shows (Swift `NoiseFloorView.body`). */
enum class NoiseFloorScreenMode { DISCONNECTED, COLLECTING, CONTENT }

/** One chart point: seconds since the chart start against the reading in dBm. */
data class NoiseFloorChartPoint(val elapsedSeconds: Double, val noiseFloor: Short)

/**
 * Chart coordinate math, trend and accessibility text from `NoiseFloorView`'s chart and
 * statistics sections. The Compose chart draws from these values.
 */
object NoiseFloorPresentation {
    /** Visible chart window duration in seconds. */
    const val CHART_WINDOW_SECONDS = 300.0
    const val Y_DOMAIN_MIN = -130.0
    const val Y_DOMAIN_MAX = -60.0

    /** The area fill's lower bound. */
    const val AREA_BASELINE = -130.0
    const val X_AXIS_STRIDE_SECONDS = 60.0
    private const val TREND_MIN_READINGS = 4
    private const val TREND_THRESHOLD = 3
    private const val SECONDS_PER_MINUTE = 60

    fun mode(isConnected: Boolean, state: NoiseFloorState): NoiseFloorScreenMode = when {
        !isConnected -> NoiseFloorScreenMode.DISCONNECTED
        state.readings.isEmpty() -> NoiseFloorScreenMode.COLLECTING
        else -> NoiseFloorScreenMode.CONTENT
    }

    fun elapsedSeconds(timestamp: Instant, startTime: Instant): Double =
        Duration.between(startTime, timestamp).toNanos() / 1_000_000_000.0

    fun points(readings: List<NoiseFloorReading>, startTime: Instant): List<NoiseFloorChartPoint> =
        readings.map { NoiseFloorChartPoint(elapsedSeconds(it.timestamp, startTime), it.noiseFloor) }

    /** A fixed 0-300 s window until the newest reading passes it, then a trailing 300 s window. */
    fun xDomain(readings: List<NoiseFloorReading>, startTime: Instant): ClosedFloatingPointRange<Double> {
        val last = readings.lastOrNull() ?: return 0.0..CHART_WINDOW_SECONDS
        val latestElapsed = elapsedSeconds(last.timestamp, startTime)
        if (latestElapsed <= CHART_WINDOW_SECONDS) return 0.0..CHART_WINDOW_SECONDS
        return (latestElapsed - CHART_WINDOW_SECONDS)..latestElapsed
    }

    /** Multiples of the 60 s stride inside [domain] (`AxisMarks(values: .stride(by: 60))`). */
    fun xAxisTicks(domain: ClosedFloatingPointRange<Double>): List<Double> {
        val first = kotlin.math.ceil(domain.start / X_AXIS_STRIDE_SECONDS).toLong()
        val last = kotlin.math.floor(domain.endInclusive / X_AXIS_STRIDE_SECONDS).toLong()
        return (first..last).map { it * X_AXIS_STRIDE_SECONDS }
    }

    /** Axis label `"<minute>:00"` with Swift `Int(seconds) / 60` truncation. */
    fun xAxisLabel(seconds: Double): String = "${seconds.toLong() / SECONDS_PER_MINUTE}:00"

    /** Compares the integer means of the first and second halves; needs at least four readings. */
    fun trendDescription(readings: List<NoiseFloorReading>, text: DiagnosticsText): String {
        if (readings.size < TREND_MIN_READINGS) return text.string(AppToolsStrings.toolsNoiseFloorTrendStable)
        val halfCount = readings.size / 2
        val firstAverage = readings.take(halfCount).sumOf { it.noiseFloor.toLong() } / maxOf(1, halfCount)
        val secondAverage = readings.takeLast(halfCount).sumOf { it.noiseFloor.toLong() } / maxOf(1, halfCount)
        val id = when {
            secondAverage > firstAverage + TREND_THRESHOLD -> AppToolsStrings.toolsNoiseFloorTrendIncreasing
            secondAverage < firstAverage - TREND_THRESHOLD -> AppToolsStrings.toolsNoiseFloorTrendDecreasing
            else -> AppToolsStrings.toolsNoiseFloorTrendStable
        }
        return text.string(id)
    }

    fun chartAccessibilityLabel(state: NoiseFloorState, text: DiagnosticsText): String {
        val stats = state.statistics
        if (state.readings.isEmpty() || stats == null) return text.string(AppToolsStrings.toolsNoiseFloorChartAccessibilityEmpty)
        return text.format(
            FormattedStringIds.NOISE_FLOOR_CHART_ACCESSIBILITY,
            state.readings.size.toLong(), stats.min.toLong(), stats.max.toLong(), stats.average.toLong(),
            trendDescription(state.readings, text),
        )
    }

    /** The current-reading card's spoken label: `"<value> dBm, <quality>"`. */
    fun currentReadingAccessibilityLabel(state: NoiseFloorState, text: DiagnosticsText): String {
        val reading = state.currentReading ?: return text.string(AppToolsStrings.toolsNoiseFloorNoReading)
        return "${reading.noiseFloor} ${text.string(AppToolsStrings.toolsNoiseFloorDBm)}, ${text.string(state.qualityLevel.labelId)}"
    }

    /** The large current value (`Text(displayValue, format: .number)`), 0 before the first reading. */
    fun currentValueText(state: NoiseFloorState, text: DiagnosticsText): String =
        SwiftNumbers.foundationInteger((state.currentReading?.noiseFloor ?: 0).toLong(), text.locale)

    /** Statistics rows: integers with grouping, average and SNR to one decimal. */
    fun integerText(value: Long, text: DiagnosticsText): String = SwiftNumbers.foundationInteger(value, text.locale)

    fun oneDecimalText(value: Double, text: DiagnosticsText): String = SwiftNumbers.foundationFixed(value, 1, text.locale)
}
