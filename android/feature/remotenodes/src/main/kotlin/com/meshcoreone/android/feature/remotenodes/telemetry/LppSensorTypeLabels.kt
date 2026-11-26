// PortedFrom: MC1/Extensions/LPPSensorType+LocalizedName.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/LPPSensorType+Chart.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.telemetry

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.protocol.lpp.LPPSensorType

/**
 * Localized display-name resource for telemetry labels. Distinct from `displayName`, the English
 * wire identifier stored in snapshot entries and reverse-looked-up with `LPPSensorType.fromName`.
 */
val LPPSensorType.localizedNameRes: Int
    get() = when (this) {
        LPPSensorType.DIGITAL_INPUT -> AppRemoteNodesStrings.remoteNodesStatusSensorDigitalInput
        LPPSensorType.DIGITAL_OUTPUT -> AppRemoteNodesStrings.remoteNodesStatusSensorDigitalOutput
        LPPSensorType.ANALOG_INPUT -> AppRemoteNodesStrings.remoteNodesStatusSensorAnalogInput
        LPPSensorType.ANALOG_OUTPUT -> AppRemoteNodesStrings.remoteNodesStatusSensorAnalogOutput
        LPPSensorType.GENERIC_SENSOR -> AppRemoteNodesStrings.remoteNodesStatusSensorGenericSensor
        LPPSensorType.ILLUMINANCE -> AppRemoteNodesStrings.remoteNodesStatusSensorIlluminance
        LPPSensorType.PRESENCE -> AppRemoteNodesStrings.remoteNodesStatusSensorPresence
        LPPSensorType.TEMPERATURE -> AppRemoteNodesStrings.remoteNodesStatusSensorTemperature
        LPPSensorType.HUMIDITY -> AppRemoteNodesStrings.remoteNodesStatusSensorHumidity
        LPPSensorType.ACCELEROMETER -> AppRemoteNodesStrings.remoteNodesStatusSensorAccelerometer
        LPPSensorType.BAROMETER -> AppRemoteNodesStrings.remoteNodesStatusSensorBarometer
        LPPSensorType.VOLTAGE -> AppRemoteNodesStrings.remoteNodesStatusSensorVoltage
        LPPSensorType.CURRENT -> AppRemoteNodesStrings.remoteNodesStatusSensorCurrent
        LPPSensorType.FREQUENCY -> AppRemoteNodesStrings.remoteNodesStatusSensorFrequency
        LPPSensorType.PERCENTAGE -> AppRemoteNodesStrings.remoteNodesStatusSensorPercentage
        LPPSensorType.ALTITUDE -> AppRemoteNodesStrings.remoteNodesStatusSensorAltitude
        LPPSensorType.LOAD -> AppRemoteNodesStrings.remoteNodesStatusSensorLoad
        LPPSensorType.CONCENTRATION -> AppRemoteNodesStrings.remoteNodesStatusSensorConcentration
        LPPSensorType.POWER -> AppRemoteNodesStrings.remoteNodesStatusSensorPower
        LPPSensorType.DISTANCE -> AppRemoteNodesStrings.remoteNodesStatusSensorDistance
        LPPSensorType.ENERGY -> AppRemoteNodesStrings.remoteNodesStatusSensorEnergy
        LPPSensorType.DIRECTION -> AppRemoteNodesStrings.remoteNodesStatusSensorDirection
        LPPSensorType.UNIX_TIME -> AppRemoteNodesStrings.remoteNodesStatusSensorUnixTime
        LPPSensorType.GYROMETER -> AppRemoteNodesStrings.remoteNodesStatusSensorGyrometer
        LPPSensorType.COLOUR -> AppRemoteNodesStrings.remoteNodesStatusSensorColour
        LPPSensorType.GPS -> AppRemoteNodesStrings.remoteNodesStatusSensorGps
        LPPSensorType.SWITCH_VALUE -> AppRemoteNodesStrings.remoteNodesStatusSensorSwitchValue
    }

/** SwiftUI system colors the telemetry charts use; the Compose layer maps each to a theme color. */
enum class ChartAccent { ORANGE, RED, TEAL, PURPLE, YELLOW, MINT, PINK, BLUE, GREEN, INDIGO, CYAN, GRAY }

/** Chart accent color for telemetry history views. */
val LPPSensorType.chartAccent: ChartAccent
    get() = when (this) {
        LPPSensorType.VOLTAGE -> ChartAccent.ORANGE
        LPPSensorType.TEMPERATURE -> ChartAccent.RED
        LPPSensorType.HUMIDITY -> ChartAccent.TEAL
        LPPSensorType.BAROMETER -> ChartAccent.PURPLE
        LPPSensorType.ILLUMINANCE -> ChartAccent.YELLOW
        LPPSensorType.CURRENT -> ChartAccent.MINT
        LPPSensorType.POWER -> ChartAccent.PINK
        LPPSensorType.FREQUENCY -> ChartAccent.BLUE
        LPPSensorType.ALTITUDE, LPPSensorType.DISTANCE -> ChartAccent.GREEN
        LPPSensorType.ENERGY -> ChartAccent.ORANGE
        LPPSensorType.DIRECTION -> ChartAccent.INDIGO
        LPPSensorType.PERCENTAGE -> ChartAccent.CYAN
        else -> ChartAccent.CYAN
    }

/** Sort priority for telemetry charts (lower = earlier). */
val LPPSensorType.chartSortPriority: Int
    get() = if (this == LPPSensorType.VOLTAGE) 0 else 1
