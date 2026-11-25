// PortedFrom: MC1Services/Sources/MC1Services/RF/RFCalculator.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.diagnostics.rf

import com.meshcoreone.android.core.model.Coordinate
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * RF propagation calculator for line-of-sight analysis.
 *
 * Provides functions for calculating wavelength, Fresnel zones, earth bulge,
 * path loss, and diffraction loss for radio frequency propagation analysis.
 * Every expression keeps the Swift operand order so Double results are bit-identical; `log10`
 * goes through [RfLog10] (correctly rounded, like Darwin libm) rather than the JVM's fdlibm.
 */
object RFCalculator {
    // region Constants

    /** Speed of light in meters per second. */
    const val SPEED_OF_LIGHT: Double = 299_792_458.0

    /** Earth's radius in kilometers. */
    const val EARTH_RADIUS_KM: Double = 6371.0

    /** Minimum Fresnel zone clearance percentage for a "clear" path. */
    const val CLEAR_CLEARANCE_THRESHOLD: Double = 80.0

    /** Minimum Fresnel zone clearance percentage for a "marginal" path. */
    const val MARGINAL_CLEARANCE_THRESHOLD: Double = 60.0

    private const val HZ_PER_MHZ: Double = 1_000_000.0
    private const val METERS_PER_KM: Double = 1000.0

    /** 20*log10(4*pi*1e6/299792458) rounded as in the Swift source. */
    private const val FSPL_CONSTANT_DB: Double = -27.55

    /** Fresnel-Kirchhoff parameter at or below which the path has full clearance and no knife-edge loss. */
    private const val DIFFRACTION_CLEAR_THRESHOLD_V: Double = -0.78

    /** Distance margin (meters) within which a sample counts as an endpoint and is skipped. */
    private const val ENDPOINT_SKIP_MARGIN_METERS: Double = 1.0

    // endregion

    /** Wavelength in meters for a frequency in megahertz; 0 for non-positive frequencies. */
    fun wavelength(frequencyMHz: Double): Double {
        if (!(frequencyMHz > 0)) return 0.0
        val frequencyHz = frequencyMHz * HZ_PER_MHZ
        return SPEED_OF_LIGHT / frequencyHz
    }

    /**
     * First Fresnel zone radius in meters at a point along the path:
     * r = sqrt((lambda * d1 * d2) / (d1 + d2)). Returns 0 for non-positive inputs.
     */
    fun fresnelRadius(frequencyMHz: Double, distanceToAMeters: Double, distanceToBMeters: Double): Double {
        if (!(frequencyMHz > 0 && distanceToAMeters > 0 && distanceToBMeters > 0)) return 0.0
        val lambda = wavelength(frequencyMHz)
        val totalDistance = distanceToAMeters + distanceToBMeters
        return sqrt((lambda * distanceToAMeters * distanceToBMeters) / totalDistance)
    }

    /**
     * Earth bulge in meters: h = (d1 * d2) / (2 * k * Re). `refractionK` is the effective
     * earth radius factor (1.0 none, 1.33 standard atmosphere, 4.0 ducting). Returns 0 for
     * non-positive inputs.
     */
    fun earthBulge(distanceToAMeters: Double, distanceToBMeters: Double, refractionK: Double): Double {
        if (!(distanceToAMeters > 0 && distanceToBMeters > 0 && refractionK > 0)) return 0.0
        val earthRadiusMeters = EARTH_RADIUS_KM * METERS_PER_KM
        val effectiveEarthRadius = refractionK * earthRadiusMeters
        return (distanceToAMeters * distanceToBMeters) / (2 * effectiveEarthRadius)
    }

    /**
     * Free-space path loss in dB: FSPL = 20*log10(d_m) + 20*log10(f_MHz) - 27.55.
     * Returns 0 for non-positive inputs.
     */
    fun pathLoss(distanceMeters: Double, frequencyMHz: Double): Double {
        if (!(distanceMeters > 0 && frequencyMHz > 0)) return 0.0
        val distanceComponent = 20 * RfLog10.log10(distanceMeters)
        val frequencyComponent = 20 * RfLog10.log10(frequencyMHz)
        return distanceComponent + frequencyComponent + FSPL_CONSTANT_DB
    }

    /**
     * Knife-edge diffraction loss in dB (positive = loss) for an obstruction
     * `obstructionHeightMeters` above the line of sight (negative = clearance), using
     * v = h * sqrt(2 * (d1 + d2) / (lambda * d1 * d2)) and the ITU-R P.526 approximation.
     */
    fun diffractionLoss(
        obstructionHeightMeters: Double,
        distanceToAMeters: Double,
        distanceToBMeters: Double,
        frequencyMHz: Double,
    ): Double {
        if (!(distanceToAMeters > 0 && distanceToBMeters > 0 && frequencyMHz > 0)) return 0.0
        val lambda = wavelength(frequencyMHz)
        val totalDistance = distanceToAMeters + distanceToBMeters
        val vParam = obstructionHeightMeters * sqrt(
            2 * totalDistance / (lambda * distanceToAMeters * distanceToBMeters),
        )
        return diffractionLossFromV(vParam)
    }

    /** ITU-R P.526 single-equation knife-edge model; continuous and monotonic over the obstruction range. */
    private fun diffractionLossFromV(vParam: Double): Double {
        if (!(vParam > DIFFRACTION_CLEAR_THRESHOLD_V)) return 0.0
        val shifted = vParam - 0.1
        return 6.9 + 20 * RfLog10.log10(sqrt(shifted * shifted + 1) + shifted)
    }

    /** Great-circle (Haversine) distance in meters between two coordinates. */
    fun distance(from: Coordinate, to: Coordinate): Double {
        val earthRadiusMeters = EARTH_RADIUS_KM * METERS_PER_KM
        val lat1 = from.latitude * PI / 180
        val lat2 = to.latitude * PI / 180
        val deltaLat = (to.latitude - from.latitude) * PI / 180
        val deltaLon = (to.longitude - from.longitude) * PI / 180

        val haversineA = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        val angularDistance = 2 * atan2(sqrt(haversineA), sqrt(1 - haversineA))
        return earthRadiusMeters * angularDistance
    }

    /**
     * Analyze a full path A to B for clearance and signal propagation. Distances are
     * measured from A itself, so the origin is 0 even if the first sample is not at 0.
     */
    fun analyzePath(
        elevationProfile: List<ElevationSample>,
        pointAHeightMeters: Double,
        pointBHeightMeters: Double,
        frequencyMHz: Double,
        refractionK: Double,
    ): PathAnalysisResult = analyze(
        AnalysisInput(elevationProfile, pointAHeightMeters, pointBHeightMeters, frequencyMHz, refractionK),
        distanceOriginMeters = 0.0,
    )

    /**
     * Analyze a segment of the path. Swift takes an `ArraySlice`; pass a `List.subList` view
     * to avoid copying. The local origin is the first sample's `distanceFromAMeters`, while
     * recorded obstruction points keep full-path distances for chart rendering.
     */
    fun analyzePathSegment(
        elevationProfile: List<ElevationSample>,
        startHeightMeters: Double,
        endHeightMeters: Double,
        frequencyMHz: Double,
        refractionK: Double,
    ): PathAnalysisResult = analyze(
        AnalysisInput(elevationProfile, startHeightMeters, endHeightMeters, frequencyMHz, refractionK),
        distanceOriginMeters = elevationProfile.firstOrNull()?.distanceFromAMeters ?: 0.0,
    )

    private class AnalysisInput(
        val profile: List<ElevationSample>,
        val startHeightMeters: Double,
        val endHeightMeters: Double,
        val frequencyMHz: Double,
        val refractionK: Double,
    )

    /** Per-path geometry shared by every sample evaluation. */
    private class PathGeometry(
        val originMeters: Double,
        val lengthMeters: Double,
        val antennaStartHeight: Double,
        val antennaEndHeight: Double,
    )

    /** Outcome for one intermediate sample. */
    private class SampleEvaluation(
        val clearancePercent: Double,
        val obstructionHeight: Double,
        val diffractionLoss: Double?,
    )

    /** Shared core for [analyzePath] and [analyzePathSegment]. */
    private fun analyze(input: AnalysisInput, distanceOriginMeters: Double): PathAnalysisResult {
        val profile = input.profile
        if (profile.size < 2) return emptyResult(input)
        val firstSample = profile.first()
        val lastSample = profile.last()

        val lengthMeters = lastSample.distanceFromAMeters - distanceOriginMeters
        if (!(lengthMeters > 0)) return emptyResult(input)

        // Antenna heights above sea level
        val geometry = PathGeometry(
            originMeters = distanceOriginMeters,
            lengthMeters = lengthMeters,
            antennaStartHeight = firstSample.elevation + input.startHeightMeters,
            antennaEndHeight = lastSample.elevation + input.endHeightMeters,
        )
        val fspl = pathLoss(lengthMeters, input.frequencyMHz)

        var worstClearancePercent = Double.POSITIVE_INFINITY
        var peakDiffractionLoss = 0.0
        val obstructionPoints = mutableListOf<ObstructionPoint>()

        for (sample in profile) {
            val evaluation = evaluateSample(sample, geometry, input) ?: continue
            if (evaluation.clearancePercent < worstClearancePercent) {
                worstClearancePercent = evaluation.clearancePercent
            }
            if (evaluation.diffractionLoss != null && evaluation.diffractionLoss > peakDiffractionLoss) {
                peakDiffractionLoss = evaluation.diffractionLoss
            }
            // Record obstruction points where clearance < marginal threshold
            if (evaluation.clearancePercent < MARGINAL_CLEARANCE_THRESHOLD) {
                obstructionPoints.add(
                    ObstructionPoint(
                        distanceFromAMeters = sample.distanceFromAMeters,
                        obstructionHeightMeters = evaluation.obstructionHeight,
                        fresnelClearancePercent = evaluation.clearancePercent,
                    ),
                )
            }
        }

        // If no samples were analyzed, set default clearance
        if (worstClearancePercent == Double.POSITIVE_INFINITY) worstClearancePercent = 100.0

        return PathAnalysisResult(
            distanceMeters = lengthMeters,
            freeSpacePathLoss = fspl,
            peakDiffractionLoss = peakDiffractionLoss,
            totalPathLoss = fspl + peakDiffractionLoss,
            clearanceStatus = clearanceStatus(worstClearancePercent),
            worstClearancePercent = worstClearancePercent,
            obstructionPoints = obstructionPoints.toList(),
            frequencyMHz = input.frequencyMHz,
            refractionK = input.refractionK,
        )
    }

    /** Evaluates one sample; returns null for samples at or very near either endpoint. */
    private fun evaluateSample(sample: ElevationSample, geometry: PathGeometry, input: AnalysisInput): SampleEvaluation? {
        val distanceFromStart = sample.distanceFromAMeters - geometry.originMeters
        val distanceToEnd = geometry.lengthMeters - distanceFromStart
        if (!(distanceFromStart > ENDPOINT_SKIP_MARGIN_METERS && distanceToEnd > ENDPOINT_SKIP_MARGIN_METERS)) return null

        // Line of sight height at this point (linear interpolation)
        val fraction = distanceFromStart / geometry.lengthMeters
        val losHeight = geometry.antennaStartHeight +
            fraction * (geometry.antennaEndHeight - geometry.antennaStartHeight)

        // Effective terrain height including earth bulge
        val bulge = earthBulge(distanceFromStart, distanceToEnd, input.refractionK)
        val effectiveTerrainHeight = sample.elevation + bulge
        val fresnelZoneRadius = fresnelRadius(input.frequencyMHz, distanceFromStart, distanceToEnd)

        // Clearance: distance from terrain to line of sight.
        // 100% = clears full first Fresnel zone, 0% = touches LOS, <0% = blocks LOS.
        val clearance = losHeight - effectiveTerrainHeight
        val clearancePercent = if (fresnelZoneRadius > 0) {
            (clearance / fresnelZoneRadius) * 100
        } else {
            if (clearance > 0) 100.0 else 0.0
        }

        // Obstruction height is negative clearance (positive = blocked)
        val obstructionHeight = effectiveTerrainHeight - losHeight
        val loss = if (obstructionHeight > -fresnelZoneRadius) {
            diffractionLoss(obstructionHeight, distanceFromStart, distanceToEnd, input.frequencyMHz)
        } else {
            null
        }
        return SampleEvaluation(clearancePercent, obstructionHeight, loss)
    }

    /** The zeroed blocked result returned for degenerate profiles. */
    private fun emptyResult(input: AnalysisInput): PathAnalysisResult = PathAnalysisResult(
        distanceMeters = 0.0,
        freeSpacePathLoss = 0.0,
        peakDiffractionLoss = 0.0,
        totalPathLoss = 0.0,
        clearanceStatus = ClearanceStatus.BLOCKED,
        worstClearancePercent = 0.0,
        obstructionPoints = emptyList(),
        frequencyMHz = input.frequencyMHz,
        refractionK = input.refractionK,
    )

    /** Maps a worst-case Fresnel clearance percentage to a clearance status. */
    private fun clearanceStatus(percent: Double): ClearanceStatus = when {
        percent >= CLEAR_CLEARANCE_THRESHOLD -> ClearanceStatus.CLEAR
        percent >= MARGINAL_CLEARANCE_THRESHOLD -> ClearanceStatus.MARGINAL
        percent >= 0 -> ClearanceStatus.PARTIAL_OBSTRUCTION
        else -> ClearanceStatus.BLOCKED
    }
}
