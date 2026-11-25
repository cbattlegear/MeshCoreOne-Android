// PortedFrom: MC1/Views/RemoteNodes/ChannelGroup.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.history

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.protocol.lpp.LPPSensorType
import com.meshcoreone.android.feature.remotenodes.common.LocalizedStandardOrder
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.telemetry.MeasurementSystem
import com.meshcoreone.android.feature.remotenodes.telemetry.chartSortPriority
import com.meshcoreone.android.feature.remotenodes.telemetry.convertedValue
import com.meshcoreone.android.feature.remotenodes.telemetry.localizedNameRes
import java.util.Locale
import java.util.UUID

/**
 * Resolves a string resource id to its localized text. Swift sorts chart titles with
 * `localizedStandardCompare` on the resolved strings, so sorting needs the text; the screen passes
 * `Resources::getString` and tests pass an English table.
 */
typealias TitleTextResolver = (Int) -> String

/** The text a [RemoteNodesText] title shows: [resolver] for resources, the text itself otherwise. */
fun RemoteNodesText.titleText(resolver: TitleTextResolver): String = when (this) {
    is RemoteNodesText.Resource -> resolver(id)
    is RemoteNodesText.Verbatim -> text
    is RemoteNodesText.Failure -> error.message.orEmpty()
}

/** One chart's samples for a channel and sensor type (Swift `TelemetryChartGroup`; [key] is its id). */
data class TelemetryChartGroup(
    val key: String,
    val title: RemoteNodesText,
    val sensorType: LPPSensorType?,
    val dataPoints: List<ChartDataPoint>,
) {
    val id: String get() = key
}

/** The charts for one telemetry channel (Swift `ChannelGroup`; [channel] is its id). */
data class ChannelGroup(val channel: Long, val charts: List<TelemetryChartGroup>) {
    val id: Long get() = channel
}

object ChannelGroups {
    private const val DEFAULT_SORT_PRIORITY = 1

    /**
     * Swift `ChannelGroup.groups(from:)`: telemetry entries grouped by channel (ascending) and sensor type,
     * values converted to [system]'s units, charts sorted by `chartSortPriority` then by title with
     * `localizedStandardCompare` in [locale]. A second temperature on the same channel within one snapshot
     * is the MCU temperature and gets its own chart; the next snapshot starts counting again.
     *
     * Swift iterates a dictionary, so charts with equal priority and equal titles have no defined order;
     * here they fall back to their key so the result is deterministic.
     */
    fun groups(
        snapshots: List<NodeStatusSnapshotDTO>,
        system: MeasurementSystem,
        locale: Locale,
        titleText: TitleTextResolver,
    ): List<ChannelGroup> {
        val builders = sortedMapOf<Long, LinkedHashMap<String, ChartBuilder>>()
        val temperatureCounts = HashMap<Pair<UUID, Long>, Int>()
        for (snapshot in snapshots) {
            for (entry in snapshot.telemetryEntries.orEmpty()) {
                val sensorType = LPPSensorType.fromName(entry.type)
                val point = ChartDataPoint(
                    id = snapshot.id,
                    date = snapshot.timestamp,
                    value = sensorType?.convertedValue(entry.value, system) ?: entry.value,
                )
                var key = "${entry.channel}-${entry.type}"
                var title: RemoteNodesText = sensorType?.let { RemoteNodesText.Resource(it.localizedNameRes) }
                    ?: RemoteNodesText.Verbatim(entry.type)
                if (sensorType == LPPSensorType.TEMPERATURE) {
                    val countKey = snapshot.id to entry.channel
                    val seen = temperatureCounts[countKey] ?: 0
                    temperatureCounts[countKey] = seen + 1
                    if (seen > 0) {
                        key = "${entry.channel}-temperature-mcu"
                        title = RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesStatusSensorMcuTemperature)
                    }
                }
                val charts = builders.getOrPut(entry.channel) { LinkedHashMap() }
                charts.getOrPut(key) { ChartBuilder(key, title, sensorType) }.points += point
            }
        }
        val order = chartOrder(locale, titleText)
        return builders.map { (channel, charts) ->
            ChannelGroup(channel, charts.values.map { it.build() }.sortedWith(order))
        }
    }

    private fun chartOrder(locale: Locale, titleText: TitleTextResolver): Comparator<TelemetryChartGroup> {
        val titles = LocalizedStandardOrder(locale)
        return Comparator { left, right ->
            val leftPriority = left.sensorType?.chartSortPriority ?: DEFAULT_SORT_PRIORITY
            val rightPriority = right.sensorType?.chartSortPriority ?: DEFAULT_SORT_PRIORITY
            when {
                leftPriority != rightPriority -> leftPriority.compareTo(rightPriority)
                else -> titles.compare(left.title.titleText(titleText), right.title.titleText(titleText))
                    .takeIf { it != 0 } ?: left.key.compareTo(right.key)
            }
        }
    }

    private class ChartBuilder(val key: String, val title: RemoteNodesText, val sensorType: LPPSensorType?) {
        val points = mutableListOf<ChartDataPoint>()
        fun build() = TelemetryChartGroup(key, title, sensorType, points.toList())
    }
}
