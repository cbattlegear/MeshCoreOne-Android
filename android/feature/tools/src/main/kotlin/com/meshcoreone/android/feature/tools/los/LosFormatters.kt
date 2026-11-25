// PortedFrom: MC1/Views/Tools/LineOfSight/Components/LOSFormatters.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Tools/LineOfSight/ClearanceStatus+UI.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Tools/LineOfSight/Components/ClearanceStatusView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Tools/LineOfSight/Components/ResultsCardView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.los

import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import java.util.Locale
import kotlin.math.abs

/**
 * Locale-explicit number formatting for LOS results. `formatDistance`/`formatFrequency`/
 * `formatAssumptions` depend on Foundation `Measurement` road-usage units and the WP-005
 * localized template; they belong to the UI layer and are not ported here.
 */
object LosFormatters {
    private const val NEGLIGIBLE_DIFFRACTION_DB = 0.1

    /** "+ 8.4 dB", or null when the loss is negligible (< 0.1 dB in magnitude). */
    fun formatDiffractionLoss(loss: Double, locale: Locale): String? {
        if (!(abs(loss) >= NEGLIGIBLE_DIFFRACTION_DB)) return null
        return "+ ${formatFractionLength(loss, 1, locale)} dB"
    }

    /** "126.6 dB". */
    fun formatPathLoss(loss: Double, locale: Locale): String = "${formatFractionLength(loss, 1, locale)} dB"

    /** Integer percentage clamped to 0...100 (Swift `Int(max(0, min(100, percent)))`, truncating). */
    fun formatClearancePercent(percent: Double): Int = swiftMax(0.0, swiftMin(100.0, percent)).toInt()

    /** "k=1.33". */
    fun formatKFactor(k: Double, locale: Locale): String = "k=${formatFractionLength(k, 2, locale)}"

    /** Localized status name resource (`ClearanceStatus.localizedName`). */
    fun statusLabel(status: ClearanceStatus): Int = when (status) {
        ClearanceStatus.CLEAR -> AppToolsStrings.toolsLineOfSightStatusClear
        ClearanceStatus.MARGINAL -> AppToolsStrings.toolsLineOfSightStatusMarginal
        ClearanceStatus.PARTIAL_OBSTRUCTION -> AppToolsStrings.toolsLineOfSightStatusPartialObstruction
        ClearanceStatus.BLOCKED -> AppToolsStrings.toolsLineOfSightStatusBlocked
    }

    /** `ClearanceStatus.blockedSubtitle`, shown under a blocked single-path result. */
    val blockedSubtitle: Int get() = AppToolsStrings.toolsLineOfSightStatusBlockedSubtitle

    /** The status row shows the clearance percentage for every status except blocked. */
    fun showsClearancePercent(status: ClearanceStatus): Boolean = status != ClearanceStatus.BLOCKED

    /** Relay card headline percentage: the worse of the two segments' worst clearance. */
    fun relayHeadlineClearancePercent(result: RelayPathAnalysisResult): Double =
        swiftMin(result.segmentAR.worstClearancePercent, result.segmentRB.worstClearancePercent)
}
