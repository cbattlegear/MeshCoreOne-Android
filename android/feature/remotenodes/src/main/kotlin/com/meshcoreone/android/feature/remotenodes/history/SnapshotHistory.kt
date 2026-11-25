// PortedFrom: MC1/Views/RemoteNodes/NodeStatusHistoryView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/RemoteNodes/NeighborSNRChartView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/RemoteNodes/TelemetryHistoryView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.history

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesClock
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.telemetry.ChartAccent
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The drill-down history screens' state: every fetched snapshot and the selected range. */
data class SnapshotHistoryState(
    val snapshots: List<NodeStatusSnapshotDTO> = emptyList(),
    val timeRange: HistoryTimeRange = HistoryTimeRange.DEFAULT,
)

/**
 * The `@State` + `.task` half shared by `NodeStatusHistoryView`, `TelemetryHistoryView` and
 * `NeighborSNRChartView`: fetch the snapshots once, then filter them by the selected range against the
 * injected clock. Swift's `fetchSnapshots` cannot throw, so a failure from [fetchSnapshots] propagates.
 */
class SnapshotHistoryStateHolder(
    private val fetchSnapshots: suspend () -> List<NodeStatusSnapshotDTO>,
    private val clock: RemoteNodesClock,
    private val zone: ZoneId,
) {
    private val _state = MutableStateFlow(SnapshotHistoryState())
    val state: StateFlow<SnapshotHistoryState> = _state.asStateFlow()

    /** Swift `.task { snapshots = await fetchSnapshots() }`. */
    suspend fun load() {
        val snapshots = fetchSnapshots()
        _state.update { it.copy(snapshots = snapshots) }
    }

    fun setTimeRange(range: HistoryTimeRange) {
        _state.update { it.copy(timeRange = range) }
    }

    val filteredSnapshots: List<NodeStatusSnapshotDTO>
        get() = _state.value.let { it.snapshots.filtered(it.timeRange, clock.now, zone) }
}

/** `NodeStatusHistoryView`: radio charts for the filtered snapshots and a retention footer. */
object NodeStatusHistory {
    val title: RemoteNodesText = RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesHistoryTitle)
    val retentionNotice: RemoteNodesText = RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesHistoryRetentionNotice)

    fun charts(filtered: List<NodeStatusSnapshotDTO>, ocvArray: List<Long>): List<RadioChartItem> =
        RadioMetricCharts.build(filtered, ocvArray)
}

/** `NeighborSNRChartView`: one neighbour's SNR across snapshots, titled with the neighbour's name. */
object NeighborSnrHistory {
    private const val SNR_UNIT = "dB"

    /** Swift's `.task` mapping: per snapshot, the first neighbour entry whose prefix equals [neighborPrefix]. */
    fun dataPoints(snapshots: List<NodeStatusSnapshotDTO>, neighborPrefix: Bytes): List<ChartDataPoint> =
        snapshots.mapNotNull { snapshot ->
            val match = snapshot.neighborSnapshots?.firstOrNull { it.publicKeyPrefix == neighborPrefix }
            match?.let { ChartDataPoint(snapshot.id, snapshot.timestamp, it.snr) }
        }

    /** The single blue SNR chart over the points inside [range]. */
    fun chart(
        name: String,
        allDataPoints: List<ChartDataPoint>,
        range: HistoryTimeRange,
        now: Instant,
        zone: ZoneId,
    ): MetricChartModel = MetricChartModel.single(
        RemoteNodesText.Verbatim(name), SNR_UNIT, allDataPoints.filteredPoints(range, now, zone), ChartAccent.BLUE,
    )
}
