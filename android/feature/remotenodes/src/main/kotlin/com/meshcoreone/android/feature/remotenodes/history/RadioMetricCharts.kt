// PortedFrom: MC1/Views/RemoteNodes/RadioMetricCharts.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.history

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.telemetry.ChartAccent

/** One entry of the radio-metric chart list: a standalone chart or the grouped packet charts. */
sealed interface RadioChartItem {
    /** A chart the host wraps in its own row chrome. */
    data class Chart(val chart: MetricChartModel) : RadioChartItem

    /** The packet-count charts stacked under a "Packets" section header (Swift `PacketChartsGroup`). */
    data class PacketSection(val charts: List<MetricChartModel>) : RadioChartItem {
        val header: RemoteNodesText = RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesHistoryPackets)
    }
}

/**
 * Swift `RadioMetricCharts`: the ordered radio-metric charts shared by the node-status drill-down and the
 * history overview — battery (OCV voltage domain), SNR, RSSI, noise floor, the packet section, then room
 * posts received/pushed. Charts without data are skipped, and every packet chart shares one y domain.
 */
object RadioMetricCharts {
    private const val VOLTS_UNIT = "V"
    private const val DECIBEL_UNIT = "dB"
    private const val DECIBEL_MILLIWATT_UNIT = "dBm"
    private const val COUNT_UNIT = ""
    private const val MILLIVOLTS_PER_VOLT = 1000.0

    fun build(snapshots: List<NodeStatusSnapshotDTO>, ocvArray: List<Long>): List<RadioChartItem> {
        val battery = snapshots.points { s -> s.batteryMillivolts?.let { it.toDouble() / MILLIVOLTS_PER_VOLT } }
        val items = mutableListOf<RadioChartItem>()
        items.addChart(
            AppRemoteNodesStrings.remoteNodesHistoryBattery, VOLTS_UNIT, ChartAccent.MINT, battery,
            MetricChartModel.voltageChartDomain(ocvArray, battery),
        )
        items.addChart(
            AppRemoteNodesStrings.remoteNodesHistorySnr, DECIBEL_UNIT, ChartAccent.BLUE,
            snapshots.points { it.lastSNR },
        )
        items.addChart(
            AppRemoteNodesStrings.remoteNodesHistoryRssi, DECIBEL_MILLIWATT_UNIT, ChartAccent.PURPLE,
            snapshots.points { s -> s.lastRSSI?.toDouble() },
        )
        items.addChart(
            AppRemoteNodesStrings.remoteNodesHistoryNoiseFloor, DECIBEL_MILLIWATT_UNIT, ChartAccent.INDIGO,
            snapshots.points { s -> s.noiseFloor?.toDouble() },
        )
        packetSection(snapshots)?.let { items += it }
        items.addChart(
            AppRemoteNodesStrings.remoteNodesRoomStatusPostsReceived, COUNT_UNIT, ChartAccent.PURPLE,
            snapshots.points { s -> s.postedCount?.toDouble() },
        )
        items.addChart(
            AppRemoteNodesStrings.remoteNodesRoomStatusPostsPushed, COUNT_UNIT, ChartAccent.CYAN,
            snapshots.points { s -> s.postPushCount?.toDouble() },
        )
        return items.toList()
    }

    /** The packet charts (sent, received, duplicates, receive errors) or null when none has data. */
    fun packetSection(snapshots: List<NodeStatusSnapshotDTO>): RadioChartItem.PacketSection? {
        val sentDirect = snapshots.points { s -> s.sentDirect?.toDouble() }
        val sentFlood = snapshots.points { s -> s.sentFlood?.toDouble() }
        val receivedDirect = snapshots.points { s -> s.receivedDirect?.toDouble() }
        val receivedFlood = snapshots.points { s -> s.receivedFlood?.toDouble() }
        val directDuplicates = snapshots.points { s -> s.directDuplicates?.toDouble() }
        val floodDuplicates = snapshots.points { s -> s.floodDuplicates?.toDouble() }
        val receiveErrors = snapshots.points { s -> s.receiveErrors?.toDouble() }
        val domain = MetricChartModel.sharedDomain(
            listOf(sentDirect, sentFlood, receivedDirect, receivedFlood, directDuplicates, floodDuplicates, receiveErrors),
        )
        val charts = listOfNotNull(
            overlay(AppRemoteNodesStrings.remoteNodesHistoryPacketsSent, sentDirect, sentFlood, domain),
            overlay(AppRemoteNodesStrings.remoteNodesHistoryPacketsReceived, receivedDirect, receivedFlood, domain),
            overlay(AppRemoteNodesStrings.remoteNodesHistoryDuplicates, directDuplicates, floodDuplicates, domain),
            receiveErrors.takeIf { it.isNotEmpty() }?.let {
                MetricChartModel.single(
                    RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesHistoryReceiveErrors), COUNT_UNIT,
                    it, ChartAccent.RED, domain,
                )
            },
        )
        return if (charts.isEmpty()) null else RadioChartItem.PacketSection(charts)
    }

    /** The overlaid Direct/Flood chart with empty series dropped, or null when neither has data. */
    private fun overlay(
        titleRes: Int,
        direct: List<ChartDataPoint>,
        flood: List<ChartDataPoint>,
        domain: ClosedFloatingPointRange<Double>?,
    ): MetricChartModel? {
        val series = listOf(
            ChartSeries(RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesHistoryDirect), ChartAccent.BLUE, direct),
            ChartSeries(RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesHistoryFlood), ChartAccent.ORANGE, flood),
        ).filter { it.dataPoints.isNotEmpty() }
        if (series.isEmpty()) return null
        return MetricChartModel(RemoteNodesText.Resource(titleRes), COUNT_UNIT, series, domain)
    }

    private fun MutableList<RadioChartItem>.addChart(
        titleRes: Int,
        unit: String,
        accent: ChartAccent,
        points: List<ChartDataPoint>,
        domain: ClosedFloatingPointRange<Double>? = null,
    ) {
        if (points.isEmpty()) return
        add(RadioChartItem.Chart(MetricChartModel.single(RemoteNodesText.Resource(titleRes), unit, points, accent, domain)))
    }
}

/** One point per snapshot carrying [value], keyed by the snapshot id and timestamp. */
internal fun List<NodeStatusSnapshotDTO>.points(value: (NodeStatusSnapshotDTO) -> Double?): List<ChartDataPoint> =
    mapNotNull { s -> value(s)?.let { ChartDataPoint(s.id, s.timestamp, it) } }
