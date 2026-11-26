// PortedFrom: MC1Services/Tests/MC1ServicesTests/RFCalculatorTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.diagnostics.rf

import com.meshcoreone.android.core.model.Coordinate
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

private fun rfCalculatorCases(suite: String, vararg cases: Pair<String, () -> Unit>): List<DynamicTest> =
    cases.map { (name, body) -> DynamicTest.dynamicTest("$suite::$name()", body) }

/** Swift `#expect(abs(actual - expected) < tolerance)`. */
private fun rfCalcAssertNear(expected: Double, actual: Double, tolerance: Double) =
    assertTrue(abs(actual - expected) < tolerance, "expected $expected ± $tolerance but was $actual")

/** Swift `#expect(actual == expected)` on Doubles (IEEE equality). */
private fun rfCalcAssertExact(expected: Double, actual: Double) =
    assertTrue(actual == expected, "expected exactly $expected but was $actual")

private val SAN_FRANCISCO = Coordinate(latitude = 37.7749, longitude = -122.4194)
private val LOS_ANGELES = Coordinate(latitude = 34.0522, longitude = -118.2437)

/** Creates an elevation profile with flat terrain at specified elevation. */
private fun rfCalcFlatProfile(
    elevationMeters: Double,
    totalDistanceMeters: Double,
    sampleCount: Int = 11,
): List<ElevationSample> = (0 until sampleCount).map { i ->
    val fraction = i.toDouble() / (sampleCount - 1).toDouble()
    ElevationSample(
        coordinate = Coordinate(SAN_FRANCISCO.latitude + fraction * 0.01, SAN_FRANCISCO.longitude),
        elevation = elevationMeters,
        distanceFromAMeters = fraction * totalDistanceMeters,
    )
}

/** Creates an elevation profile with a triangular mountain/obstruction at midpoint. */
private fun rfCalcObstructedProfile(
    baseElevationMeters: Double,
    obstructionHeightMeters: Double,
    totalDistanceMeters: Double,
    sampleCount: Int = 11,
): List<ElevationSample> {
    val midpoint = sampleCount / 2
    return (0 until sampleCount).map { i ->
        val fraction = i.toDouble() / (sampleCount - 1).toDouble()
        val distanceFromMid = abs(i - midpoint)
        val peakFactor = maxOf(0.0, 1.0 - distanceFromMid.toDouble() / midpoint.toDouble())
        ElevationSample(
            coordinate = Coordinate(SAN_FRANCISCO.latitude + fraction * 0.01, SAN_FRANCISCO.longitude),
            elevation = baseElevationMeters + obstructionHeightMeters * peakFactor,
            distanceFromAMeters = fraction * totalDistanceMeters,
        )
    }
}

private fun rfCalcAnalyze(
    profile: List<ElevationSample>,
    heightA: Double = 50.0,
    heightB: Double = 50.0,
    frequencyMHz: Double = 910.0,
    refractionK: Double = 1.0,
): PathAnalysisResult = RFCalculator.analyzePath(profile, heightA, heightB, frequencyMHz, refractionK)

private fun rfCalcTwoSampleProfile(): List<ElevationSample> = listOf(
    ElevationSample(Coordinate(37.7749, -122.4194), 0.0, 0.0),
    ElevationSample(Coordinate(37.7849, -122.4194), 0.0, 1000.0),
)

private fun rfCalcKilometerSamples(): List<ElevationSample> = (0..10).map { i ->
    ElevationSample(Coordinate(37.0 + i.toDouble() * 0.01, -122.0), 100.0, i.toDouble() * 1000)
}

class RFCalculatorTest {
    @TestFactory fun rfCalculator() = rfCalculatorCases(
        "RFCalculatorTests",
        "Speed of light constant is correct" to { rfCalcAssertExact(299_792_458.0, RFCalculator.SPEED_OF_LIGHT) },
        "Earth radius constant is correct" to { rfCalcAssertExact(6371.0, RFCalculator.EARTH_RADIUS_KM) },
        "Wavelength at 910 MHz is approximately 0.3294m" to {
            rfCalcAssertNear(0.3294, RFCalculator.wavelength(910.0), 0.001)
        },
        "Wavelength at 2400 MHz is approximately 0.125m" to {
            rfCalcAssertNear(0.125, RFCalculator.wavelength(2400.0), 0.001)
        },
        "Wavelength returns 0 for zero frequency" to { rfCalcAssertExact(0.0, RFCalculator.wavelength(0.0)) },
        "Wavelength returns 0 for negative frequency" to { rfCalcAssertExact(0.0, RFCalculator.wavelength(-100.0)) },
        "Fresnel radius at midpoint is approximately 22.23m for 6km at 910MHz" to {
            rfCalcAssertNear(22.23, RFCalculator.fresnelRadius(910.0, 3000.0, 3000.0), 0.5)
        },
        "Fresnel radius at quarter point is smaller than at midpoint" to {
            val totalDistance = 6000.0
            val quarterPoint = totalDistance / 4
            val radiusAtQuarter = RFCalculator.fresnelRadius(910.0, quarterPoint, totalDistance - quarterPoint)
            val radiusAtMidpoint = RFCalculator.fresnelRadius(910.0, 3000.0, 3000.0)
            assertTrue(radiusAtQuarter < radiusAtMidpoint)
            rfCalcAssertNear(19.27, radiusAtQuarter, 0.5)
        },
        "Fresnel radius is symmetric" to {
            val radius1 = RFCalculator.fresnelRadius(910.0, 2000.0, 4000.0)
            val radius2 = RFCalculator.fresnelRadius(910.0, 4000.0, 2000.0)
            rfCalcAssertNear(radius1, radius2, 0.001)
        },
        "Fresnel radius returns 0 for invalid inputs" to {
            rfCalcAssertExact(0.0, RFCalculator.fresnelRadius(0.0, 100.0, 100.0))
            rfCalcAssertExact(0.0, RFCalculator.fresnelRadius(910.0, 0.0, 100.0))
            rfCalcAssertExact(0.0, RFCalculator.fresnelRadius(910.0, 100.0, 0.0))
            rfCalcAssertExact(0.0, RFCalculator.fresnelRadius(-100.0, 100.0, 100.0))
        },
        "Earth bulge at midpoint with k=0.25 is approximately 2.82m for 6km" to {
            rfCalcAssertNear(2.82, RFCalculator.earthBulge(3000.0, 3000.0, 0.25), 0.05)
        },
        "Earth bulge with standard atmosphere k=1.33 is smaller" to {
            val bulgeConservative = RFCalculator.earthBulge(3000.0, 3000.0, 0.25)
            val bulgeStandard = RFCalculator.earthBulge(3000.0, 3000.0, 1.33)
            assertTrue(bulgeStandard < bulgeConservative)
            rfCalcAssertNear(0.53, bulgeStandard, 0.05)
        },
        "Earth bulge is symmetric" to {
            rfCalcAssertNear(
                RFCalculator.earthBulge(2000.0, 4000.0, 1.0),
                RFCalculator.earthBulge(4000.0, 2000.0, 1.0),
                0.001,
            )
        },
        "Earth bulge returns 0 for invalid inputs" to {
            rfCalcAssertExact(0.0, RFCalculator.earthBulge(0.0, 100.0, 1.0))
            rfCalcAssertExact(0.0, RFCalculator.earthBulge(100.0, 0.0, 1.0))
            rfCalcAssertExact(0.0, RFCalculator.earthBulge(100.0, 100.0, 0.0))
            rfCalcAssertExact(0.0, RFCalculator.earthBulge(100.0, 100.0, -1.0))
        },
        "Path loss is approximately 107.2 dB for 6km at 910MHz" to {
            rfCalcAssertNear(107.2, RFCalculator.pathLoss(6000.0, 910.0), 0.5)
        },
        "Path loss increases with distance" to {
            val loss1km = RFCalculator.pathLoss(1000.0, 910.0)
            val loss2km = RFCalculator.pathLoss(2000.0, 910.0)
            val loss4km = RFCalculator.pathLoss(4000.0, 910.0)
            assertTrue(loss2km > loss1km)
            assertTrue(loss4km > loss2km)
            // Doubling distance adds ~6dB
            rfCalcAssertNear(6.02, loss2km - loss1km, 0.1)
            rfCalcAssertNear(6.02, loss4km - loss2km, 0.1)
        },
        "Path loss increases with frequency" to {
            val loss400MHz = RFCalculator.pathLoss(1000.0, 400.0)
            val loss900MHz = RFCalculator.pathLoss(1000.0, 900.0)
            val loss2400MHz = RFCalculator.pathLoss(1000.0, 2400.0)
            assertTrue(loss900MHz > loss400MHz)
            assertTrue(loss2400MHz > loss900MHz)
        },
        "Path loss returns 0 for invalid inputs" to {
            rfCalcAssertExact(0.0, RFCalculator.pathLoss(0.0, 910.0))
            rfCalcAssertExact(0.0, RFCalculator.pathLoss(1000.0, 0.0))
            rfCalcAssertExact(0.0, RFCalculator.pathLoss(-100.0, 910.0))
            rfCalcAssertExact(0.0, RFCalculator.pathLoss(1000.0, -910.0))
        },
        "Diffraction loss is zero for clear line-of-sight (v below grazing)" to {
            rfCalcAssertExact(0.0, RFCalculator.diffractionLoss(-50.0, 3000.0, 3000.0, 910.0))
        },
        "Diffraction loss is approximately 6 dB for grazing (v near 0)" to {
            rfCalcAssertNear(6.0, RFCalculator.diffractionLoss(0.0, 3000.0, 3000.0, 910.0), 1.0)
        },
        "Diffraction loss increases for blocked path (v near 1)" to {
            // ITU-R P.526 J(v) at v ≈ 1 is ≈ 13.9 dB
            rfCalcAssertNear(13.9, RFCalculator.diffractionLoss(15.7, 3000.0, 3000.0, 910.0), 0.5)
        },
        "Diffraction loss is greater for larger obstructions" to {
            val lossSmall = RFCalculator.diffractionLoss(5.0, 3000.0, 3000.0, 910.0)
            val lossMedium = RFCalculator.diffractionLoss(15.0, 3000.0, 3000.0, 910.0)
            val lossLarge = RFCalculator.diffractionLoss(30.0, 3000.0, 3000.0, 910.0)
            assertTrue(lossMedium > lossSmall)
            assertTrue(lossLarge > lossMedium)
        },
        "Diffraction loss matches ITU-R P.526 reference value at strong obstruction (v ≈ 2.4)" to {
            rfCalcAssertNear(20.5, RFCalculator.diffractionLoss(37.7, 3000.0, 3000.0, 910.0), 0.7)
        },
        "Diffraction loss returns 0 for invalid inputs" to {
            rfCalcAssertExact(0.0, RFCalculator.diffractionLoss(10.0, 0.0, 100.0, 910.0))
            rfCalcAssertExact(0.0, RFCalculator.diffractionLoss(10.0, 100.0, 0.0, 910.0))
            rfCalcAssertExact(0.0, RFCalculator.diffractionLoss(10.0, 100.0, 100.0, 0.0))
        },
        "Distance between same coordinates is zero" to {
            rfCalcAssertExact(0.0, RFCalculator.distance(SAN_FRANCISCO, SAN_FRANCISCO))
        },
        "Haversine distance calculation is accurate" to {
            rfCalcAssertNear(559_000.0, RFCalculator.distance(SAN_FRANCISCO, LOS_ANGELES), 10_000.0)
        },
        "Haversine distance is symmetric" to {
            rfCalcAssertNear(
                RFCalculator.distance(SAN_FRANCISCO, LOS_ANGELES),
                RFCalculator.distance(LOS_ANGELES, SAN_FRANCISCO),
                0.001,
            )
        },
        "Distance across date line is correct" to {
            val tokyo = Coordinate(latitude = 35.6762, longitude = 139.6503)
            rfCalcAssertNear(8_280_000.0, RFCalculator.distance(tokyo, SAN_FRANCISCO), 100_000.0)
        },
        "Short distance calculation is accurate" to {
            val point2 = Coordinate(latitude = 37.7839, longitude = -122.4194)
            rfCalcAssertNear(1000.0, RFCalculator.distance(SAN_FRANCISCO, point2), 100.0)
        },
    )

    @TestFactory fun pathAnalysis() = rfCalculatorCases(
        "PathAnalysisTests",
        "Clear path with flat terrain returns clear status" to {
            val result = rfCalcAnalyze(rfCalcFlatProfile(0.0, 6000.0, sampleCount = 21))
            assertEquals(ClearanceStatus.CLEAR, result.clearanceStatus)
            assertTrue(result.worstClearancePercent >= 80)
            assertTrue(result.obstructionPoints.isEmpty())
            rfCalcAssertExact(6000.0, result.distanceMeters)
            rfCalcAssertExact(6.0, result.distanceKm)
        },
        "Clear path has only FSPL, no diffraction loss" to {
            val result = rfCalcAnalyze(rfCalcFlatProfile(0.0, 6000.0, sampleCount = 21))
            rfCalcAssertNear(107.2, result.freeSpacePathLoss, 1.0)
            rfCalcAssertExact(0.0, result.peakDiffractionLoss)
            rfCalcAssertExact(result.freeSpacePathLoss, result.totalPathLoss)
        },
        "Blocked path with 100m mountain returns blocked status" to {
            val result = rfCalcAnalyze(rfCalcObstructedProfile(0.0, 100.0, 6000.0, sampleCount = 21))
            assertEquals(ClearanceStatus.BLOCKED, result.clearanceStatus)
            assertTrue(result.worstClearancePercent < 0)
            assertFalse(result.obstructionPoints.isEmpty())
        },
        "Blocked path has significant diffraction loss" to {
            val result = rfCalcAnalyze(rfCalcObstructedProfile(0.0, 100.0, 6000.0, sampleCount = 21))
            assertTrue(result.peakDiffractionLoss > 10)
            assertTrue(result.totalPathLoss > result.freeSpacePathLoss)
        },
        "Marginal path with partial obstruction returns marginal status" to {
            val result = rfCalcAnalyze(rfCalcObstructedProfile(0.0, 25.0, 6000.0, sampleCount = 21))
            assertTrue(
                result.clearanceStatus == ClearanceStatus.CLEAR || result.clearanceStatus == ClearanceStatus.MARGINAL,
            )
            assertTrue(result.worstClearancePercent >= 60)
        },
        "Partial obstruction path returns partial obstruction status" to {
            val result = rfCalcAnalyze(rfCalcObstructedProfile(0.0, 45.0, 6000.0, sampleCount = 21))
            assertEquals(ClearanceStatus.PARTIAL_OBSTRUCTION, result.clearanceStatus)
            assertTrue(result.worstClearancePercent >= 0)
            assertTrue(result.worstClearancePercent < 60)
            assertFalse(result.obstructionPoints.isEmpty())
        },
        "Empty profile returns blocked status" to {
            val result = rfCalcAnalyze(emptyList())
            assertEquals(ClearanceStatus.BLOCKED, result.clearanceStatus)
            rfCalcAssertExact(0.0, result.distanceMeters)
        },
        "Single sample profile returns blocked status" to {
            val sample = ElevationSample(SAN_FRANCISCO, 0.0, 0.0)
            assertEquals(ClearanceStatus.BLOCKED, rfCalcAnalyze(listOf(sample)).clearanceStatus)
        },
        "Profile with zero total distance returns blocked status" to {
            val samples = listOf(ElevationSample(SAN_FRANCISCO, 0.0, 0.0), ElevationSample(SAN_FRANCISCO, 0.0, 0.0))
            val result = rfCalcAnalyze(samples)
            assertEquals(ClearanceStatus.BLOCKED, result.clearanceStatus)
            rfCalcAssertExact(0.0, result.distanceMeters)
        },
        "Asymmetric antenna heights are handled correctly" to {
            val result = rfCalcAnalyze(rfCalcFlatProfile(0.0, 6000.0, sampleCount = 21), heightA = 100.0, heightB = 20.0)
            assertEquals(ClearanceStatus.CLEAR, result.clearanceStatus)
            assertTrue(result.worstClearancePercent >= 80)
        },
        "Custom k-factor affects earth bulge calculation" to {
            val profile = rfCalcObstructedProfile(0.0, 40.0, 6000.0, sampleCount = 21)
            val resultConservative = rfCalcAnalyze(profile, refractionK = 0.25)
            val resultStandard = rfCalcAnalyze(profile, refractionK = 1.33)
            assertTrue(resultStandard.worstClearancePercent > resultConservative.worstClearancePercent)
        },
    )

    @TestFactory fun pathAnalysisResultFields() = rfCalculatorCases(
        "PathAnalysisResultFieldsTests",
        "PathAnalysisResult includes frequency used in calculation" to {
            val result = rfCalcAnalyze(rfCalcTwoSampleProfile(), frequencyMHz = 915.0, refractionK = 1.33)
            rfCalcAssertExact(915.0, result.frequencyMHz)
        },
        "PathAnalysisResult includes k-factor used in calculation" to {
            val result = rfCalcAnalyze(rfCalcTwoSampleProfile(), frequencyMHz = 906.0, refractionK = 1.33)
            rfCalcAssertExact(1.33, result.refractionK)
        },
    )

    @TestFactory fun segmentAnalysisWithSlices() = rfCalculatorCases(
        "SegmentAnalysisTests",
        "analyzePathSegment works with ArraySlice" to {
            val samples = rfCalcKilometerSamples()
            // Swift samples[0...5] -> subList(0, 6) view (no copy)
            val result = RFCalculator.analyzePathSegment(samples.subList(0, 6), 50.0, 50.0, 906.0, 1.0)
            rfCalcAssertExact(5000.0, result.distanceMeters)
            assertEquals(ClearanceStatus.CLEAR, result.clearanceStatus)
        },
        "analyzePathSegment handles overlapping slice indices" to {
            val samples = rfCalcKilometerSamples()
            // Swift samples[5...10] -> subList(5, 11)
            val result = RFCalculator.analyzePathSegment(samples.subList(5, 11), 10.0, 10.0, 906.0, 1.0)
            rfCalcAssertExact(5000.0, result.distanceMeters)
        },
    )

    @TestFactory fun androidEdgeCases() = rfCalculatorCases(
        "WP-212",
        "ClearanceStatus raw values match Swift and round-trip" to {
            assertEquals(
                listOf("Clear", "Marginal", "Partial obstruction", "Blocked"),
                ClearanceStatus.entries.map { it.rawValue },
            )
            ClearanceStatus.entries.forEach { assertSame(it, ClearanceStatus.fromRawValue(it.rawValue)) }
            assertNull(ClearanceStatus.fromRawValue("clear"))
        },
        "Distance across the antimeridian takes the short way" to {
            val west = Coordinate(0.0, 179.5)
            val east = Coordinate(0.0, -179.5)
            // 1 degree of longitude at the equator on a 6371 km sphere
            rfCalcAssertNear(6_371_000.0 * Math.PI / 180, RFCalculator.distance(west, east), 1e-6)
        },
        "analyzePath measures from origin 0 even if the first sample is offset" to {
            val shifted = rfCalcKilometerSamples().subList(2, 11)
            val full = RFCalculator.analyzePath(shifted, 50.0, 50.0, 906.0, 1.0)
            val segment = RFCalculator.analyzePathSegment(shifted, 50.0, 50.0, 906.0, 1.0)
            rfCalcAssertExact(10_000.0, full.distanceMeters)
            rfCalcAssertExact(8000.0, segment.distanceMeters)
        },
        "Endpoint-only profile defaults worst clearance to 100 percent" to {
            val result = rfCalcAnalyze(rfCalcTwoSampleProfile())
            rfCalcAssertExact(100.0, result.worstClearancePercent)
            assertEquals(ClearanceStatus.CLEAR, result.clearanceStatus)
            assertTrue(result.obstructionPoints.isEmpty())
        },
        "Diffraction loss is zero below the v = -0.78 threshold and positive above it" to {
            // v = h * sqrt(2 * 6000 / (lambda * 3000 * 3000)); solve for h at v = -0.78
            val lambda = RFCalculator.wavelength(910.0)
            val scale = Math.sqrt(2 * 6000.0 / (lambda * 3000.0 * 3000.0))
            assertTrue(RFCalculator.diffractionLoss(-0.8 / scale, 3000.0, 3000.0, 910.0) == 0.0)
            assertTrue(RFCalculator.diffractionLoss(-0.7 / scale, 3000.0, 3000.0, 910.0) > 0.0)
        },
        "RfLog10 reproduces Darwin libm log10 bit-for-bit where the JVM log10 is off by one ulp" to {
            // Expected values are Darwin libm log10 outputs (what Swift's log10 returns on Apple platforms).
            listOf(
                6000.0 to 3.7781512503836434,
                1904757.0 to 6.279839578284313,
                20.733856295130334 to 1.3166800841459982,
                10.085311681824745 to 1.0036893244233436,
                654346.0 to 5.815807451925978,
                522366.0847462009 to 5.717974972084547,
                779741.0 to 5.8919503707034435,
                178.24923316257576 to 2.2510276701705894,
                784869.5303402288 to 5.894797469531819,
                977408.0974912527 to 5.990075932688062,
                212694.4352893817 to 5.3277561276332,
                97.46972728410043 to 1.9888697509299782,
                191.2927212207988 to 2.281698445229687,
            ).forEach { (x, darwin) -> rfCalcAssertExact(darwin, RfLog10.log10(x)) }
        },
        "RfLog10 handles exact powers, extremes and IEEE special cases" to {
            listOf(
                1.0 to 0.0, 10.0 to 1.0, 0.1 to -1.0, 10_000.0 to 4.0, 1e-300 to -300.0, 1e300 to 300.0,
                5e-324 to -323.3062153431158, 4.9e-310 to -309.3098039199715,
                Double.MAX_VALUE to 308.25471555991675,
            ).forEach { (x, darwin) -> rfCalcAssertExact(darwin, RfLog10.log10(x)) }
            assertEquals(Double.NEGATIVE_INFINITY, RfLog10.log10(0.0))
            assertEquals(Double.POSITIVE_INFINITY, RfLog10.log10(Double.POSITIVE_INFINITY))
            assertTrue(RfLog10.log10(-1.0).isNaN())
            assertTrue(RfLog10.log10(Double.NaN).isNaN())
        },
        "NaN inputs fall through the positive guards to zero" to {
            rfCalcAssertExact(0.0, RFCalculator.wavelength(Double.NaN))
            rfCalcAssertExact(0.0, RFCalculator.pathLoss(Double.NaN, 910.0))
            rfCalcAssertExact(0.0, RFCalculator.earthBulge(100.0, 100.0, Double.NaN))
        },
        "ObstructionPoint equality ignores id and treats signed zero as equal" to {
            val a = ObstructionPoint(0.0, 1.0, 2.0)
            val b = ObstructionPoint(-0.0, 1.0, 2.0)
            assertNotEquals(a.id, b.id)
            assertEquals(a, b)
            assertEquals(a.hashCode(), b.hashCode())
            assertNotEquals(ObstructionPoint(Double.NaN, 1.0, 2.0), ObstructionPoint(Double.NaN, 1.0, 2.0))
        },
        "worstObstructionPoint keeps the earliest of tied minima" to {
            val first = ObstructionPoint(100.0, 1.0, -5.0)
            val tie = ObstructionPoint(200.0, 2.0, -5.0)
            val result = PathAnalysisResult(1.0, 0.0, 0.0, 0.0, ClearanceStatus.BLOCKED, -5.0, listOf(first, tie), 910.0, 1.0)
            assertSame(first, result.worstObstructionPoint)
            assertNull(result.copy(obstructionPoints = emptyList()).worstObstructionPoint)
        },
        "peakObstructionPerRegion splits on gaps over 2.5 sample steps" to {
            val points = listOf(
                ObstructionPoint(100.0, 0.0, 10.0),
                ObstructionPoint(200.0, 0.0, 5.0),
                ObstructionPoint(450.0, 0.0, 20.0), // gap 250 = 2.5 steps: same region
                ObstructionPoint(800.0, 0.0, 30.0), // gap 350 > 250: new region
            )
            val result = PathAnalysisResult(1.0, 0.0, 0.0, 0.0, ClearanceStatus.BLOCKED, 5.0, points, 910.0, 1.0)
            assertEquals(listOf(points[1], points[3]), result.peakObstructionPerRegion)
        },
        "peakObstructionPerRegion with no positive gap returns only the first point" to {
            val points = listOf(ObstructionPoint(100.0, 0.0, 10.0), ObstructionPoint(100.0, 0.0, -10.0))
            val result = PathAnalysisResult(1.0, 0.0, 0.0, 0.0, ClearanceStatus.BLOCKED, -10.0, points, 910.0, 1.0)
            assertEquals(listOf(points[0]), result.peakObstructionPerRegion)
            val single = result.copy(obstructionPoints = listOf(points[1]))
            assertEquals(listOf(points[1]), single.peakObstructionPerRegion)
        },
    )
}
