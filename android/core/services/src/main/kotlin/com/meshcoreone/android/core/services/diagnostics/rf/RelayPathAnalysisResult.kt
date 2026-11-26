// PortedFrom: MC1Services/Sources/MC1Services/RF/RelayPathAnalysisResult.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.diagnostics.rf

/** Combined result when analyzing a path via repeater. */
data class RelayPathAnalysisResult(
    val segmentAR: SegmentAnalysisResult,
    val segmentRB: SegmentAnalysisResult,
) {
    val totalDistanceMeters: Double get() = segmentAR.distanceMeters + segmentRB.distanceMeters

    val totalDistanceKm: Double get() = totalDistanceMeters / 1000

    /** Overall status is the worst of the two segments. */
    val overallStatus: ClearanceStatus
        get() {
            val arIndex = STATUS_ORDER.indexOf(segmentAR.clearanceStatus).coerceAtLeast(0)
            val rbIndex = STATUS_ORDER.indexOf(segmentRB.clearanceStatus).coerceAtLeast(0)
            return STATUS_ORDER[maxOf(arIndex, rbIndex)]
        }

    private companion object {
        val STATUS_ORDER: List<ClearanceStatus> = listOf(
            ClearanceStatus.CLEAR,
            ClearanceStatus.MARGINAL,
            ClearanceStatus.PARTIAL_OBSTRUCTION,
            ClearanceStatus.BLOCKED,
        )
    }
}
