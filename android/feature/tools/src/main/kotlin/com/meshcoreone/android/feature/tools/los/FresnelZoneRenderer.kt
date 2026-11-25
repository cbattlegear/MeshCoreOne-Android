// PortedFrom: MC1/Views/Tools/LineOfSight/FresnelZoneRenderer.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.los

/** Builds the terrain-chart samples (LOS line, Fresnel radius, earth-bulge-adjusted terrain). */
object FresnelZoneRenderer {
    /** LOS height in meters above sea level at [atDistance] along a path of [totalDistance]. */
    fun losHeight(atDistance: Double, totalDistance: Double, heightA: Double, heightB: Double): Double {
        if (!(totalDistance > 0)) return heightA
        val fraction = atDistance / totalDistance
        return heightA + fraction * (heightB - heightA)
    }

    /**
     * Profile samples for [elevationProfile] (a full path or a segment slice). Fresnel/LOS math
     * uses segment-relative distances while [ProfileSample.x] keeps the original distance from A,
     * so R->B slices render at their global position.
     */
    fun buildProfileSamples(
        elevationProfile: List<ElevationSample>,
        pointAHeight: Double,
        pointBHeight: Double,
        frequencyMHz: Double,
        refractionK: Double,
    ): List<ProfileSample> {
        val first = elevationProfile.firstOrNull() ?: return emptyList()
        val last = elevationProfile.last()

        val segmentOffset = first.distanceFromAMeters
        val segmentLength = last.distanceFromAMeters - segmentOffset

        val heightA = first.elevation + pointAHeight
        val heightB = last.elevation + pointBHeight

        return elevationProfile.map { sample ->
            val distanceFromSegmentStart = sample.distanceFromAMeters - segmentOffset
            val distanceToSegmentEnd = segmentLength - distanceFromSegmentStart

            val yLOS = losHeight(distanceFromSegmentStart, segmentLength, heightA, heightB)
            val radius = LosFresnelMath.fresnelRadius(frequencyMHz, distanceFromSegmentStart, distanceToSegmentEnd)
            val earthBulge = LosFresnelMath.earthBulge(distanceFromSegmentStart, distanceToSegmentEnd, refractionK)

            ProfileSample(
                x = sample.distanceFromAMeters,
                yTerrain = sample.elevation + earthBulge,
                yLOS = yLOS,
                fresnelRadius = radius,
            )
        }
    }
}

/** Sample point with every computed value the terrain chart renders. */
data class ProfileSample(
    /** Distance from A in meters. */
    val x: Double,
    /** Terrain elevation in meters, including earth bulge. */
    val yTerrain: Double,
    /** Line-of-sight height in meters. */
    val yLOS: Double,
    val fresnelRadius: Double,
) {
    val yTop: Double get() = yLOS + fresnelRadius
    val yBottom: Double get() = yLOS - fresnelRadius

    /** Inner 60% zone boundaries (ideal clearance threshold). */
    val yTop60: Double get() = yLOS + fresnelRadius * INNER_ZONE_FRACTION
    val yBottom60: Double get() = yLOS - fresnelRadius * INNER_ZONE_FRACTION

    /** Visible bottom of the inner 60% zone (clamped). */
    val yVisibleBottom60: Double get() = swiftMin(swiftMax(yTerrain, yBottom60), yTop60)

    /** Whether terrain intrudes past the 60% Fresnel clearance threshold at this point. */
    val isObstructed: Boolean get() = yTerrain > yBottom60

    /** Visible bottom of the Fresnel zone (clamped to avoid path inversion). */
    val yVisibleBottom: Double get() = swiftMin(swiftMax(yTerrain, yBottom), yTop)

    private companion object {
        const val INNER_ZONE_FRACTION = 0.6
    }
}
