// PortedFrom: MC1/Views/RemoteNodes/TelemetryHistoryView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.history

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.protocol.lpp.LPPSensorType
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.telemetry.ChartAccent
import com.meshcoreone.android.feature.remotenodes.telemetry.MeasurementSystem
import com.meshcoreone.android.feature.remotenodes.telemetry.chartAccent
import com.meshcoreone.android.feature.remotenodes.telemetry.localizedUnitSymbol

/**
 * A run of sensor charts. With several channels each channel is its own section headed
 * "Channel N"; with one channel the charts are listed without a [header].
 */
data class SensorChartSection(val header: RemoteNodesText?, val charts: List<MetricChartModel>)

/** The sensor charts of the telemetry drill-down and the overview's sensors section. */
object TelemetrySensorCharts {
    /** The drill-down's navigation title (Swift `L10n...Status.telemetry`). */
    val title: RemoteNodesText = RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesStatusTelemetry)

    /**
     * Swift `chartView(for:)`: the converted-unit symbol, the sensor's accent (cyan when unknown) and, for
     * voltage, the OCV voltage domain.
     */
    fun chart(group: TelemetryChartGroup, ocvArray: List<Long>, system: MeasurementSystem): MetricChartModel =
        MetricChartModel.single(
            title = group.title,
            unit = group.sensorType?.localizedUnitSymbol(system) ?: "",
            dataPoints = group.dataPoints,
            accent = group.sensorType?.chartAccent ?: ChartAccent.CYAN,
            yAxisDomain = if (group.sensorType == LPPSensorType.VOLTAGE) {
                MetricChartModel.voltageChartDomain(ocvArray, group.dataPoints)
            } else null,
        )

    /** Swift's `groups.count > 1` branch: headed per-channel sections, else one headerless run. */
    fun sections(groups: List<ChannelGroup>, ocvArray: List<Long>, system: MeasurementSystem): List<SensorChartSection> {
        if (groups.size > 1) {
            return groups.map { group ->
                SensorChartSection(channelHeader(group.channel), group.charts.map {
                    chart(it, ocvArray, system).copy(identity = SensorChartIdentity(group.channel, it.key))
                })
            }
        }
        val single = groups.firstOrNull() ?: return emptyList()
        return listOf(SensorChartSection(null, single.charts.map {
            chart(it, ocvArray, system).copy(identity = SensorChartIdentity(single.channel, it.key))
        }))
    }

    /** Swift `L10n...Status.channel(n)` ("Channel %1$d"). */
    fun channelHeader(channel: Long): RemoteNodesText =
        RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_status_channel, channel.toInt())
}
