// PortedFrom: MC1/Views/RemoteNodes/StatusDeltaView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.status

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.common.SwiftNumberFormat
import java.util.Locale
import kotlin.math.abs

/** How a delta is tinted: neutral below 0.01, otherwise improvement or degradation. */
enum class StatusDeltaTone { NEUTRAL, IMPROVED, DEGRADED }

/**
 * The trend arrow and magnitude shown next to a status metric (Swift `StatusDeltaView`).
 *
 * @param higherIsBetter true for battery/SNR/RSSI, false for noise floor.
 * @param unit appended verbatim after the magnitude (" V", " dB", " dBm").
 * @param fractionDigits decimals shown for the magnitude.
 */
data class StatusDelta(
    val delta: Double,
    val higherIsBetter: Boolean,
    val unit: String,
    val fractionDigits: Int,
) {
    /** Up arrow for a positive delta, down arrow otherwise (zero included). */
    val pointsUp: Boolean get() = delta > 0

    val isImprovement: Boolean get() = if (higherIsBetter) delta > 0 else delta < 0

    val tone: StatusDeltaTone
        get() = when {
            abs(delta) < NEUTRAL_THRESHOLD -> StatusDeltaTone.NEUTRAL
            isImprovement -> StatusDeltaTone.IMPROVED
            else -> StatusDeltaTone.DEGRADED
        }

    /** The absolute delta with [fractionDigits] decimals, without the unit. */
    fun formattedMagnitude(locale: Locale): String = SwiftNumberFormat.fixed(abs(delta), fractionDigits, locale)

    /** The visible label: magnitude immediately followed by [unit]. */
    fun label(locale: Locale): String = formattedMagnitude(locale) + unit

    /** Swift `accessibilityDescription`: quality, direction, magnitude and unit in one localized phrase. */
    fun accessibilityDescription(locale: Locale): RemoteNodesText {
        val direction = RemoteNodesText.Resource(
            if (pointsUp) AppRemoteNodesStrings.remoteNodesHistoryA11yIncreased else AppRemoteNodesStrings.remoteNodesHistoryA11yDecreased,
        )
        val quality = RemoteNodesText.Resource(
            if (isImprovement) AppRemoteNodesStrings.remoteNodesHistoryA11yImproved else AppRemoteNodesStrings.remoteNodesHistoryA11yDegraded,
        )
        return RemoteNodesText.resource(
            R.string.l10n_app_remotenodes_remotenodes_history_a11y_deltadescription,
            quality, direction, formattedMagnitude(locale), unit,
        )
    }

    companion object {
        private const val NEUTRAL_THRESHOLD = 0.01
        private const val MILLIVOLTS_PER_VOLT = 1000.0

        /** Battery row: millivolt delta shown in volts with three decimals (`NodeCommonStatusRows`). */
        fun battery(deltaMillivolts: Int?): StatusDelta? =
            deltaMillivolts?.let { StatusDelta(it / MILLIVOLTS_PER_VOLT, higherIsBetter = true, unit = " V", fractionDigits = 3) }

        fun rssi(delta: Long?): StatusDelta? =
            delta?.let { StatusDelta(it.toDouble(), higherIsBetter = true, unit = " dBm", fractionDigits = 0) }

        fun snr(delta: Double?): StatusDelta? =
            delta?.let { StatusDelta(it, higherIsBetter = true, unit = " dB", fractionDigits = 1) }

        fun noiseFloor(delta: Long?): StatusDelta? =
            delta?.let { StatusDelta(it.toDouble(), higherIsBetter = false, unit = " dBm", fractionDigits = 0) }
    }
}
