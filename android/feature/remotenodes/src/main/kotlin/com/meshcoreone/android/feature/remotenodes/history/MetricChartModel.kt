// PortedFrom: MC1/Views/RemoteNodes/MetricChartView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.history

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.common.SwiftNumberFormat
import com.meshcoreone.android.feature.remotenodes.telemetry.ChartAccent
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** One plotted sample (Swift `MetricChartView.DataPoint`); [id] is the source snapshot's id. */
data class ChartDataPoint(val id: UUID, val date: Instant, val value: Double)

/**
 * One plotted line (Swift `MetricChartView.Series`). [color] drives the direct style of a single-series
 * chart and the categorical mapping of a multi-series chart; [name] is the legend entry and identity.
 */
data class ChartSeries(val name: RemoteNodesText, val color: ChartAccent, val dataPoints: List<ChartDataPoint>)

/** A series paired with its nearest point to the current scrub position (Swift `SeriesSelection`). */
data class ChartSeriesSelection(val series: ChartSeries, val point: ChartDataPoint)

/** One value in the scrub header: the series' color-coded value (multi-series rows also show [seriesName]). */
data class ChartReadoutEntry(val seriesName: RemoteNodesText, val color: ChartAccent, val valueText: String)

/**
 * The header readout while scrubbing. A single-series chart shows `"<value> <unit>"` and the point's
 * date; a multi-series chart shows one `name value` row per series and the shared scrub date. [date] is
 * data: the UI formats it as Swift's `.dateTime.month(.abbreviated).day().hour().minute()` (see
 * [HistoryDateFormat.monthDayTime]).
 */
data class ChartReadout(val isMultiSeries: Boolean, val entries: List<ChartReadoutEntry>, val date: Instant)

/** The placeholder shown when no series has two points: the summed first value (if any) and "check back". */
data class ChartEmptyState(val valueText: String?) {
    val message: RemoteNodesText = RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesHistoryCheckBack)
}

data class SensorChartIdentity(val channel: Long, val key: String)

/**
 * The non-drawing half of Swift `MetricChartView`: a titled chart of one or more series with an optional
 * fixed y-axis domain. Drawing (Compose Canvas) uses [MetricChartGeometry] for coordinates.
 */
data class MetricChartModel(
    val title: RemoteNodesText,
    val unit: String,
    val series: List<ChartSeries>,
    val yAxisDomain: ClosedFloatingPointRange<Double>? = null,
    val identity: SensorChartIdentity? = null,
    val selectionIdentity: String? = null,
) {
    /** More than one series switches to the overlaid layout with a categorical scale and legend. */
    val isMultiSeries: Boolean get() = series.size > 1

    /** A line needs at least two points in some series; otherwise the empty state shows. */
    val hasEnoughData: Boolean get() = series.any { it.dataPoints.size >= 2 }

    /** Series that carry points; empty series would add phantom legend entries. */
    val drawnSeries: List<ChartSeries> get() = series.filter { it.dataPoints.isNotEmpty() }

    /** The single number shown with too few points: first values summed across drawn series. */
    val emptyStateValue: Double?
        get() {
            val firstValues = drawnSeries.mapNotNull { it.dataPoints.firstOrNull()?.value }
            return if (firstValues.isEmpty()) null else firstValues.fold(0.0) { sum, value -> sum + value }
        }

    fun emptyState(locale: Locale): ChartEmptyState =
        ChartEmptyState(emptyStateValue?.let { "${SwiftNumberFormat.number(it, locale)} $unit" })

    /**
     * Swift `scrubDate`: the raw scrub position snapped to the nearest plotted date across all series.
     * Ties keep the first candidate, as Swift `min(by:)` does.
     */
    fun scrubDate(selectedDate: Instant?): Instant? {
        selectedDate ?: return null
        return series.flatMap { s -> s.dataPoints.map { it.date } }.firstMinBy { distance(it, selectedDate) }
    }

    /** Swift `selections`: each series' nearest point to the snapped [scrubDate] (first on ties). */
    fun selections(scrubDate: Instant?): List<ChartSeriesSelection> {
        scrubDate ?: return emptyList()
        return series.mapNotNull { s ->
            s.dataPoints.firstMinBy { distance(it.date, scrubDate) }?.let { ChartSeriesSelection(s, it) }
        }
    }

    /** The header readout for a raw scrub position, or null when not scrubbing. */
    fun readout(selectedDate: Instant?, locale: Locale): ChartReadout? {
        val snapped = scrubDate(selectedDate) ?: return null
        val selections = selections(snapped)
        if (isMultiSeries) {
            if (selections.isEmpty()) return null
            val entries = selections.map {
                ChartReadoutEntry(it.series.name, it.series.color, SwiftNumberFormat.number(it.point.value, locale))
            }
            return ChartReadout(isMultiSeries = true, entries = entries, date = snapped)
        }
        val selection = selections.firstOrNull() ?: return null
        val text = "${SwiftNumberFormat.number(selection.point.value, locale)} $unit"
        return ChartReadout(
            isMultiSeries = false,
            entries = listOf(ChartReadoutEntry(selection.series.name, selection.series.color, text)),
            date = selection.point.date,
        )
    }

    companion object {
        /** Swift's single-series initializer: one series named after the chart, in [accent]. */
        fun single(
            title: RemoteNodesText,
            unit: String,
            dataPoints: List<ChartDataPoint>,
            accent: ChartAccent,
            yAxisDomain: ClosedFloatingPointRange<Double>? = null,
        ): MetricChartModel = MetricChartModel(title, unit, listOf(ChartSeries(title, accent, dataPoints)), yAxisDomain)

        /**
         * Swift `[DataPoint].sharedDomain(for:)`: `0...max * 1.05` across every array, or null when the
         * maximum is missing or not positive.
         */
        fun sharedDomain(arrays: List<List<ChartDataPoint>>): ClosedFloatingPointRange<Double>? {
            val maxValue = arrays.flatten().maxOfOrNull { it.value } ?: return null
            if (maxValue <= 0.0) return null
            return 0.0..maxValue * SHARED_DOMAIN_HEADROOM
        }

        /**
         * Swift `[Int].voltageChartDomain(dataPoints:bufferMV:)`: the OCV millivolt range in volts, unioned
         * with the data so outliers are never clipped, padded by [bufferMillivolts] with a floor of 0 V.
         */
        fun voltageChartDomain(
            ocvMillivolts: List<Long>,
            dataPoints: List<ChartDataPoint> = emptyList(),
            bufferMillivolts: Int = DEFAULT_VOLTAGE_BUFFER_MV,
        ): ClosedFloatingPointRange<Double>? {
            val ocvMin = ocvMillivolts.minOrNull() ?: return null
            val ocvMax = ocvMillivolts.maxOrNull() ?: return null
            var low = ocvMin / MILLIVOLTS_PER_VOLT
            var high = ocvMax / MILLIVOLTS_PER_VOLT
            dataPoints.minOfOrNull { it.value }?.let { low = minOf(low, it) }
            dataPoints.maxOfOrNull { it.value }?.let { high = maxOf(high, it) }
            val buffer = bufferMillivolts / MILLIVOLTS_PER_VOLT
            return maxOf(0.0, low - buffer)..high + buffer
        }

        private const val SHARED_DOMAIN_HEADROOM = 1.05
        private const val DEFAULT_VOLTAGE_BUFFER_MV = 500
        private const val MILLIVOLTS_PER_VOLT = 1000.0
    }
}

private fun distance(a: Instant, b: Instant): Duration = Duration.between(b, a).abs()

/** Swift `min(by:)`: the first element whose key is strictly smallest. */
internal inline fun <T> List<T>.firstMinBy(key: (T) -> Duration): T? {
    var best: T? = null
    var bestKey: Duration? = null
    for (element in this) {
        val candidate = key(element)
        if (bestKey == null || candidate < bestKey) {
            best = element
            bestKey = candidate
        }
    }
    return best
}
