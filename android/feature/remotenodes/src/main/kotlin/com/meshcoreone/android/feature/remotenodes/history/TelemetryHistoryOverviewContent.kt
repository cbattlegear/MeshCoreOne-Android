// PortedFrom: MC1/Views/RemoteNodes/TelemetryHistoryOverviewView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.history

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.telemetry.ChartAccent
import com.meshcoreone.android.feature.remotenodes.telemetry.MeasurementSystem
import java.text.Normalizer

/** A section that was never captured: the secondary "captured when you view the X section" note. */
data class NotCapturedNotice(val section: RemoteNodesText) {
    /**
     * Swift `L10n...History.sectionNotCaptured(sectionName)`; the single argument is itself a
     * [RemoteNodesText] the screen resolves before formatting.
     */
    val text: RemoteNodesText =
        RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_history_sectionnotcaptured, section)
}

/** A section that has data (charts) or a [NotCapturedNotice] in place of them. */
sealed interface OverviewSection<out T> {
    data class Content<T>(val value: T) : OverviewSection<T>
    data class NotCaptured(val notice: NotCapturedNotice) : OverviewSection<Nothing>
}

/** One neighbour's SNR chart in the overview (Swift private `NeighborChart`). */
data class NeighborChart(val prefix: Bytes, val name: String, val dataPoints: List<ChartDataPoint>) {
    val chart: MetricChartModel
        get() = MetricChartModel.single(RemoteNodesText.Verbatim(name), SNR_UNIT, dataPoints, ChartAccent.BLUE)

    private companion object {
        const val SNR_UNIT = "dB"
    }
}

/** Initial disclosure-group states: radio open, sensors open only when neighbours are hidden, neighbours closed. */
data class OverviewExpansion(val radio: Boolean, val sensors: Boolean, val neighbors: Boolean) {
    companion object {
        fun initial(showNeighbors: Boolean): OverviewExpansion =
            OverviewExpansion(radio = true, sensors = !showNeighbors, neighbors = false)
    }
}

/**
 * Everything `TelemetryHistoryOverviewView` decides outside layout. [Empty] is the
 * `ContentUnavailableView`; [Loaded] lists the sections in order (the location section between sensors
 * and neighbours is built by the location-history logic from the same filtered snapshots).
 */
sealed interface TelemetryHistoryOverviewContent {
    data object Empty : TelemetryHistoryOverviewContent {
        val title: RemoteNodesText = OVERVIEW_TITLE
        val message: RemoteNodesText = RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesHistoryNoSnapshotsMessage)
    }

    data class Loaded(
        val filteredSnapshots: List<NodeStatusSnapshotDTO>,
        /** Radio charts, or null when no filtered snapshot carries radio data (no notice for radio). */
        val radio: List<RadioChartItem>?,
        val sensors: OverviewSection<List<SensorChartSection>>,
        /** Null when the host hides neighbours (`showNeighbors == false`). */
        val neighbors: OverviewSection<List<NeighborChart>>?,
    ) : TelemetryHistoryOverviewContent {
        val radioTitle: RemoteNodesText = RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesHistoryRadioSection)
        val sensorsTitle: RemoteNodesText = SENSORS_SECTION
        val neighborsTitle: RemoteNodesText = NEIGHBORS_SECTION
        val retentionNotice: RemoteNodesText =
            RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesHistoryRetentionNotice)
    }

    companion object {
        val OVERVIEW_TITLE: RemoteNodesText = RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesHistoryOverviewTitle)
        private val SENSORS_SECTION = RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesHistorySensorsSection)
        private val NEIGHBORS_SECTION = RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesHistoryNeighborsSection)

        /** Builds the overview from the state holder's current state, filter and resolver. */
        fun build(
            holder: TelemetryHistoryOverviewStateHolder,
            showNeighbors: Boolean,
            system: MeasurementSystem,
        ): TelemetryHistoryOverviewContent {
            if (!holder.hasSnapshots) return Empty
            val filtered = holder.filteredSnapshots
            val ocvArray = holder.state.value.ocvArray
            val radio = if (holder.hasRadioData(filtered)) RadioMetricCharts.build(filtered, ocvArray) else null
            val sensors = if (holder.hasTelemetryData(filtered)) {
                OverviewSection.Content(TelemetrySensorCharts.sections(holder.channelGroups(filtered), ocvArray, system))
            } else {
                OverviewSection.NotCaptured(NotCapturedNotice(SENSORS_SECTION))
            }
            val neighbors = when {
                !showNeighbors -> null
                holder.hasNeighborData(filtered) ->
                    OverviewSection.Content(neighborCharts(filtered, holder::resolveNeighborName))
                else -> OverviewSection.NotCaptured(NotCapturedNotice(NEIGHBORS_SECTION))
            }
            return Loaded(filtered, radio, sensors, neighbors)
        }

        /**
         * Swift `buildNeighborCharts(from:)`: one chart per neighbour prefix with its SNR across snapshots,
         * named by [resolveName] or the uppercase hex prefix, sorted by name with Swift `String <`
         * (Unicode scalar order of the NFC form). Swift sorts dictionary values, so equal names have no
         * defined order; here they keep first-seen order.
         */
        fun neighborCharts(filtered: List<NodeStatusSnapshotDTO>, resolveName: (Bytes) -> String?): List<NeighborChart> {
            val charts = LinkedHashMap<Bytes, NeighborChart>()
            for (snapshot in filtered) {
                for (neighbor in snapshot.neighborSnapshots.orEmpty()) {
                    val point = ChartDataPoint(snapshot.id, snapshot.timestamp, neighbor.snr)
                    val existing = charts[neighbor.publicKeyPrefix]
                    charts[neighbor.publicKeyPrefix] = existing?.copy(dataPoints = existing.dataPoints + point)
                        ?: NeighborChart(
                            prefix = neighbor.publicKeyPrefix,
                            name = resolveName(neighbor.publicKeyPrefix) ?: neighbor.publicKeyPrefix.uppercaseHexString(),
                            dataPoints = listOf(point),
                        )
                }
            }
            return charts.values.sortedWith { a, b -> swiftStringCompare(a.name, b.name) }
        }
    }
}

/** Swift `String <`: lexicographic Unicode scalar comparison of the NFC-normalized strings. */
internal fun swiftStringCompare(left: String, right: String): Int {
    val a = Normalizer.normalize(left, Normalizer.Form.NFC).codePoints().toArray()
    val b = Normalizer.normalize(right, Normalizer.Form.NFC).codePoints().toArray()
    for (index in 0 until minOf(a.size, b.size)) {
        if (a[index] != b[index]) return a[index].compareTo(b[index])
    }
    return a.size.compareTo(b.size)
}
