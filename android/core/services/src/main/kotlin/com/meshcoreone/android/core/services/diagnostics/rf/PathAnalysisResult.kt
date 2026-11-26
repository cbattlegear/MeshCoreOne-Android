// PortedFrom: MC1Services/Sources/MC1Services/RF/PathAnalysisResult.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.diagnostics.rf

/** Complete analysis result for a path. Equality uses Swift IEEE Double semantics. */
data class PathAnalysisResult(
    val distanceMeters: Double,
    val freeSpacePathLoss: Double,
    /** Peak diffraction loss from the single worst knife-edge obstruction (not cumulative). */
    val peakDiffractionLoss: Double,
    val totalPathLoss: Double,
    val clearanceStatus: ClearanceStatus,
    val worstClearancePercent: Double,
    val obstructionPoints: List<ObstructionPoint>,
    val frequencyMHz: Double,
    val refractionK: Double,
) {
    val distanceKm: Double get() = distanceMeters / 1000

    /** First point with the lowest clearance (Swift `min(by:)` keeps the earliest of ties). */
    val worstObstructionPoint: ObstructionPoint?
        get() {
            var worst = obstructionPoints.firstOrNull() ?: return null
            for (point in obstructionPoints) {
                if (point.fresnelClearancePercent < worst.fresnelClearancePercent) worst = point
            }
            return worst
        }

    /**
     * Returns the worst obstruction point per contiguous obstructed region.
     * Groups adjacent obstruction points by sample spacing, then picks the
     * lowest clearance point from each group, one per red bar in the terrain profile.
     */
    val peakObstructionPerRegion: List<ObstructionPoint>
        get() {
            if (obstructionPoints.size < 2) return obstructionPoints
            val minGap = smallestPositiveGap()
            if (!minGap.isFinite()) return listOf(obstructionPoints[0])

            // A gap > 2x the sample step means a non-obstructed sample separates two regions
            val gapThreshold = minGap * REGION_GAP_FACTOR
            val regions = mutableListOf<ObstructionPoint>()
            var regionWorst = obstructionPoints[0]
            for (i in 1 until obstructionPoints.size) {
                val point = obstructionPoints[i]
                val gap = point.distanceFromAMeters - obstructionPoints[i - 1].distanceFromAMeters
                if (gap > gapThreshold) {
                    regions.add(regionWorst)
                    regionWorst = point
                } else if (point.fresnelClearancePercent < regionWorst.fresnelClearancePercent) {
                    regionWorst = point
                }
            }
            regions.add(regionWorst)
            return regions.toList()
        }

    /** Smallest gap between consecutive points (= one sample step); infinity when none is positive. */
    private fun smallestPositiveGap(): Double {
        var minGap = Double.POSITIVE_INFINITY
        for (i in 1 until obstructionPoints.size) {
            val gap = obstructionPoints[i].distanceFromAMeters - obstructionPoints[i - 1].distanceFromAMeters
            if (gap > 0 && gap < minGap) minGap = gap
        }
        return minGap
    }

    override fun equals(other: Any?): Boolean =
        other is PathAnalysisResult &&
            distanceMeters == other.distanceMeters &&
            freeSpacePathLoss == other.freeSpacePathLoss &&
            peakDiffractionLoss == other.peakDiffractionLoss &&
            totalPathLoss == other.totalPathLoss &&
            clearanceStatus == other.clearanceStatus &&
            worstClearancePercent == other.worstClearancePercent &&
            rfListsEqual(obstructionPoints, other.obstructionPoints) &&
            frequencyMHz == other.frequencyMHz &&
            refractionK == other.refractionK

    override fun hashCode(): Int = rfFieldsHash(
        rfDoubleHash(distanceMeters),
        rfDoubleHash(freeSpacePathLoss),
        rfDoubleHash(peakDiffractionLoss),
        rfDoubleHash(totalPathLoss),
        clearanceStatus.hashCode(),
        rfDoubleHash(worstClearancePercent),
        obstructionPoints.fold(1) { hash, point -> 31 * hash + point.hashCode() },
        rfDoubleHash(frequencyMHz),
        rfDoubleHash(refractionK),
    )

    private companion object {
        const val REGION_GAP_FACTOR = 2.5
    }
}
