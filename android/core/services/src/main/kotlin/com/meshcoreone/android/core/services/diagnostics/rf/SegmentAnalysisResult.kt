// PortedFrom: MC1Services/Sources/MC1Services/RF/SegmentAnalysisResult.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.diagnostics.rf

/** Result for a single path segment (A to R, or R to B). Equality uses Swift IEEE Double semantics. */
data class SegmentAnalysisResult(
    val startLabel: String,
    val endLabel: String,
    val clearanceStatus: ClearanceStatus,
    val distanceMeters: Double,
    val worstClearancePercent: Double,
) {
    val distanceKm: Double get() = distanceMeters / 1000

    override fun equals(other: Any?): Boolean =
        other is SegmentAnalysisResult &&
            startLabel == other.startLabel &&
            endLabel == other.endLabel &&
            clearanceStatus == other.clearanceStatus &&
            distanceMeters == other.distanceMeters &&
            worstClearancePercent == other.worstClearancePercent

    override fun hashCode(): Int = rfFieldsHash(
        startLabel.hashCode(),
        endLabel.hashCode(),
        clearanceStatus.hashCode(),
        rfDoubleHash(distanceMeters),
        rfDoubleHash(worstClearancePercent),
    )
}
