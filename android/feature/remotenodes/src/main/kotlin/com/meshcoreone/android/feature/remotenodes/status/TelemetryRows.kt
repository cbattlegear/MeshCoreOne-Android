// PortedFrom: MC1/Views/RemoteNodes/SharedNodeStatusViews.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.status

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.protocol.lpp.LPPDataPoint
import com.meshcoreone.android.core.protocol.lpp.LPPSensorType
import com.meshcoreone.android.core.protocol.lpp.LPPValue
import com.meshcoreone.android.feature.remotenodes.telemetry.localizedNameRes

private const val MILLIVOLTS_PER_VOLT = 1000.0

/**
 * Swift `telemetryLabel(for:at:in:)`: a second temperature on the same channel is the MCU's, decided
 * by occurrence (the row's [index] in [points]), not by value equality. Returns a string resource id.
 */
fun telemetryLabel(dataPoint: LPPDataPoint, index: Int, points: List<LPPDataPoint>): Int {
    if (dataPoint.type != LPPSensorType.TEMPERATURE) return dataPoint.type.localizedNameRes
    val prior = points.take(index).count { it.channel == dataPoint.channel && it.type == LPPSensorType.TEMPERATURE }
    return if (prior == 0) {
        AppRemoteNodesStrings.remoteNodesStatusSensorTemperature
    } else {
        AppRemoteNodesStrings.remoteNodesStatusSensorMcuTemperature
    }
}

/**
 * Swift `NodeTelemetryRow`'s secondary line: a float voltage reading as an OCV-curve percentage
 * (`Int(voltage * 1000)` millivolts, truncated); null for every other row.
 */
fun telemetryBatteryPercentage(dataPoint: LPPDataPoint, ocvArray: List<Long>): Int? {
    if (dataPoint.type != LPPSensorType.VOLTAGE) return null
    val voltage = (dataPoint.value as? LPPValue.Float)?.value ?: return null
    val millivolts = (voltage * MILLIVOLTS_PER_VOLT).toInt()
    return OcvBatteryPercentage.percentage(millivolts.toLong(), ocvArray)
}

/** One telemetry row: its label resource and the optional OCV percentage (Swift `NodeTelemetryRow`). */
data class TelemetryRowModel(val dataPoint: LPPDataPoint, val labelRes: Int, val batteryPercentage: Int?)

/** Rows for one list of points (a channel group, or the flat single-channel list). */
fun telemetryRows(points: List<LPPDataPoint>, ocvArray: List<Long>): List<TelemetryRowModel> =
    points.mapIndexed { index, point ->
        TelemetryRowModel(point, telemetryLabel(point, index, points), telemetryBatteryPercentage(point, ocvArray))
    }
