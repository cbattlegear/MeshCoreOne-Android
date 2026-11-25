// PortedFrom: MC1/Views/RemoteNodes/HistoryTimeRangePicker.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.history

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import java.time.Instant
import java.time.ZoneId

/**
 * Time range for filtering history charts (Swift `HistoryTimeRange`). The segmented picker is UI; its
 * title is [pickerTitle] and each segment's text is [label].
 */
enum class HistoryTimeRange {
    WEEK, MONTH, THREE_MONTHS, ALL;

    val label: RemoteNodesText
        get() = RemoteNodesText.Resource(
            when (this) {
                WEEK -> AppRemoteNodesStrings.remoteNodesHistoryWeek
                MONTH -> AppRemoteNodesStrings.remoteNodesHistoryMonth
                THREE_MONTHS -> AppRemoteNodesStrings.remoteNodesHistoryThreeMonths
                ALL -> AppRemoteNodesStrings.remoteNodesHistoryAll
            },
        )

    /**
     * Swift `Calendar.current.date(byAdding:value:to: .now)`: calendar arithmetic in [zone] that keeps the
     * wall-clock time across DST changes and clamps month ends (Mar 31 minus one month is Feb 29), which
     * `ZonedDateTime.minusDays/minusMonths` reproduces (oracle `history_location.swift.txt`, CAL lines).
     */
    fun startDate(now: Instant, zone: ZoneId): Instant? {
        val local = now.atZone(zone)
        return when (this) {
            WEEK -> local.minusDays(DAYS_IN_WEEK).toInstant()
            MONTH -> local.minusMonths(1).toInstant()
            THREE_MONTHS -> local.minusMonths(QUARTER_MONTHS).toInstant()
            ALL -> null
        }
    }

    companion object {
        private const val DAYS_IN_WEEK = 7L
        private const val QUARTER_MONTHS = 3L

        /** Swift `HistoryTimeRange.default`. */
        val DEFAULT: HistoryTimeRange = MONTH

        /** The segmented picker's accessibility title. */
        val pickerTitle: RemoteNodesText = RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesHistoryTimeRange)
    }
}

/** Swift `filteredSnapshots`: every snapshot at or after the range start (all of them for [HistoryTimeRange.ALL]). */
fun List<NodeStatusSnapshotDTO>.filtered(range: HistoryTimeRange, now: Instant, zone: ZoneId): List<NodeStatusSnapshotDTO> {
    val start = range.startDate(now, zone) ?: return this
    return filter { it.timestamp >= start }
}

/** Swift `NeighborSNRChartView.filteredDataPoints`: points at or after the range start. */
fun List<ChartDataPoint>.filteredPoints(range: HistoryTimeRange, now: Instant, zone: ZoneId): List<ChartDataPoint> {
    val start = range.startDate(now, zone) ?: return this
    return filter { it.date >= start }
}
