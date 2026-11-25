// PortedFrom: MC1Services/Tests/MC1ServicesTests/RFPathAnalysisCharacterizationTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.diagnostics.rf

import com.meshcoreone.android.core.model.Coordinate
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

private fun rfCharacterizationCases(suite: String, vararg cases: Pair<String, () -> Unit>): List<DynamicTest> =
    cases.map { (name, body) -> DynamicTest.dynamicTest("$suite::$name()", body) }

/** Swift `#expect(actual == expected)` on Doubles: exact IEEE equality, no tolerance. */
private fun rfCharAssertExact(expected: Double, actual: Double) =
    assertTrue(actual == expected, "expected exactly $expected but was $actual")

private fun rfCharFlatProfile(elevation: Double, totalDistance: Double, count: Int): List<ElevationSample> =
    (0 until count).map { i ->
        val fraction = i.toDouble() / (count - 1).toDouble()
        ElevationSample(Coordinate(37.7749 + fraction * 0.01, -122.4194), elevation, fraction * totalDistance)
    }

/** Triangular mountain centered at the midpoint sample. */
private fun rfCharMountainProfile(base: Double, peak: Double, totalDistance: Double, count: Int): List<ElevationSample> {
    val midpoint = count / 2
    return (0 until count).map { i ->
        val fraction = i.toDouble() / (count - 1).toDouble()
        val distanceFromMid = abs(i - midpoint)
        val peakFactor = maxOf(0.0, 1.0 - distanceFromMid.toDouble() / midpoint.toDouble())
        ElevationSample(
            Coordinate(37.7749 + fraction * 0.01, -122.4194),
            base + peak * peakFactor,
            fraction * totalDistance,
        )
    }
}

/** Deterministic rolling terrain with multiple distinct obstruction regions. */
private fun rfCharRollingProfile(totalDistance: Double, count: Int): List<ElevationSample> =
    (0 until count).map { i ->
        val fraction = i.toDouble() / (count - 1).toDouble()
        val elevation = 120.0 + 35.0 * sin(fraction * 9.0) + 18.0 * cos(fraction * 23.0)
        ElevationSample(Coordinate(37.0 + fraction * 0.05, -122.0), elevation, fraction * totalDistance)
    }

private fun rfCharAssertPoint(point: ObstructionPoint?, distance: Double, height: Double, percent: Double) {
    assertNotNull(point)
    rfCharAssertExact(distance, point.distanceFromAMeters)
    rfCharAssertExact(height, point.obstructionHeightMeters)
    rfCharAssertExact(percent, point.fresnelClearancePercent)
}

class RFPathAnalysisCharacterizationTest {
    @TestFactory fun characterization() = rfCharacterizationCases(
        "RFPathAnalysisCharacterizationTests",
        "Flat 6km path at 910MHz pins clear-status outputs" to {
            val result = RFCalculator.analyzePath(rfCharFlatProfile(0.0, 6000.0, 21), 50.0, 50.0, 910.0, 1.0)
            rfCharAssertExact(6000.0, result.distanceMeters)
            rfCharAssertExact(107.19385285409474, result.freeSpacePathLoss)
            rfCharAssertExact(0.0, result.peakDiffractionLoss)
            rfCharAssertExact(107.19385285409474, result.totalPathLoss)
            assertEquals(ClearanceStatus.CLEAR, result.clearanceStatus)
            rfCharAssertExact(221.7460578709339, result.worstClearancePercent)
            assertTrue(result.obstructionPoints.isEmpty())
        },
        "100m mountain at 6km pins blocked-status outputs" to {
            val result = RFCalculator.analyzePath(rfCharMountainProfile(0.0, 100.0, 6000.0, 21), 50.0, 50.0, 910.0, 1.0)
            rfCharAssertExact(6000.0, result.distanceMeters)
            rfCharAssertExact(107.19385285409474, result.freeSpacePathLoss)
            rfCharAssertExact(23.034080243532884, result.peakDiffractionLoss)
            rfCharAssertExact(130.2279330976276, result.totalPathLoss)
            assertEquals(ClearanceStatus.BLOCKED, result.clearanceStatus)
            rfCharAssertExact(-228.10082469417358, result.worstClearancePercent)
            assertEquals(13, result.obstructionPoints.size)
            assertEquals(1, result.peakObstructionPerRegion.size)
            rfCharAssertPoint(result.worstObstructionPoint, 3000.0, 50.70632553759222, -228.10082469417358)
            rfCharAssertPoint(result.obstructionPoints.firstOrNull(), 1200.0, -9.547951655940984, 53.68895359134259)
        },
        "45m hill with asymmetric heights at 868MHz, k=1.33 pins outputs" to {
            val result = RFCalculator.analyzePath(rfCharMountainProfile(0.0, 45.0, 6000.0, 21), 30.0, 10.0, 868.0, 1.33)
            rfCharAssertExact(6000.0, result.distanceMeters)
            rfCharAssertExact(106.7834195112027, result.freeSpacePathLoss)
            rfCharAssertExact(17.211410686784944, result.peakDiffractionLoss)
            rfCharAssertExact(123.99483019798764, result.totalPathLoss)
            assertEquals(ClearanceStatus.BLOCKED, result.clearanceStatus)
            rfCharAssertExact(-112.16902092433065, result.worstClearancePercent)
            assertEquals(15, result.obstructionPoints.size)
            assertEquals(1, result.peakObstructionPerRegion.size)
            rfCharAssertPoint(result.obstructionPoints.firstOrNull(), 1200.0, -7.660114027023294, 42.067734964659884)
        },
        "Rolling 10km terrain pins multi-region outputs" to {
            val result = RFCalculator.analyzePath(rfCharRollingProfile(10_000.0, 41), 12.0, 7.0, 906.0, 1.33)
            rfCharAssertExact(10_000.0, result.distanceMeters)
            rfCharAssertExact(111.59256395353627, result.freeSpacePathLoss)
            rfCharAssertExact(20.388551277139328, result.peakDiffractionLoss)
            rfCharAssertExact(131.98111523067558, result.totalPathLoss)
            assertEquals(ClearanceStatus.BLOCKED, result.clearanceStatus)
            rfCharAssertExact(-166.65278266083646, result.worstClearancePercent)
            assertEquals(18, result.obstructionPoints.size)
            assertEquals(3, result.peakObstructionPerRegion.size)
            rfCharAssertPoint(result.worstObstructionPoint, 8500.0, 34.23055294849772, -166.65278266083646)
        },
        "A-to-R segment over rolling terrain pins outputs" to {
            val rolling = rfCharRollingProfile(10_000.0, 41)
            // Swift rolling[0...20]
            val result = RFCalculator.analyzePathSegment(rolling.subList(0, 21), 12.0, 25.0, 906.0, 1.33)
            rfCharAssertExact(5000.0, result.distanceMeters)
            rfCharAssertExact(105.57196404025665, result.freeSpacePathLoss)
            rfCharAssertExact(18.929132368423446, result.peakDiffractionLoss)
            rfCharAssertExact(124.5010964086801, result.totalPathLoss)
            assertEquals(ClearanceStatus.BLOCKED, result.clearanceStatus)
            rfCharAssertExact(-139.44495806579053, result.worstClearancePercent)
            assertEquals(12, result.obstructionPoints.size)
            assertEquals(1, result.peakObstructionPerRegion.size)
            rfCharAssertPoint(result.obstructionPoints.firstOrNull(), 500.0, -4.239257565925641, 34.7405983689846)
        },
        "R-to-B segment keeps absolute obstruction distances" to {
            val rolling = rfCharRollingProfile(10_000.0, 41)
            // Swift rolling[20...40]
            val result = RFCalculator.analyzePathSegment(rolling.subList(20, 41), 25.0, 7.0, 906.0, 1.33)
            rfCharAssertExact(5000.0, result.distanceMeters)
            rfCharAssertExact(105.57196404025665, result.freeSpacePathLoss)
            rfCharAssertExact(22.69158176325522, result.peakDiffractionLoss)
            rfCharAssertExact(128.26354580351187, result.totalPathLoss)
            assertEquals(ClearanceStatus.BLOCKED, result.clearanceStatus)
            rfCharAssertExact(-219.11961746223767, result.worstClearancePercent)
            assertEquals(10, result.obstructionPoints.size)
            assertEquals(1, result.peakObstructionPerRegion.size)
            // Obstruction distances stay in full-path coordinates for chart rendering
            rfCharAssertPoint(result.worstObstructionPoint, 8250.0, 42.51118551351081, -219.11961746223767)
            rfCharAssertPoint(result.obstructionPoints.firstOrNull(), 7250.0, -6.515142400094021, 32.19623258059396)
        },
        "Full profile passed as a slice matches analyzePath exactly" to {
            val rolling = rfCharRollingProfile(10_000.0, 41)
            // Swift rolling[...]
            val viaSegment = RFCalculator.analyzePathSegment(rolling.subList(0, rolling.size), 12.0, 7.0, 906.0, 1.33)
            val viaPath = RFCalculator.analyzePath(rolling, 12.0, 7.0, 906.0, 1.33)
            assertEquals(viaPath, viaSegment)
        },
        "Degenerate inputs return the zeroed blocked result" to {
            val empty = RFCalculator.analyzePath(emptyList(), 50.0, 50.0, 910.0, 1.0)
            assertEquals(ClearanceStatus.BLOCKED, empty.clearanceStatus)
            rfCharAssertExact(0.0, empty.distanceMeters)
            rfCharAssertExact(910.0, empty.frequencyMHz)
            rfCharAssertExact(1.0, empty.refractionK)

            val flat = rfCharFlatProfile(0.0, 0.0, 3)
            val zeroLength = RFCalculator.analyzePathSegment(flat.subList(0, flat.size), 50.0, 50.0, 910.0, 1.0)
            assertEquals(ClearanceStatus.BLOCKED, zeroLength.clearanceStatus)
            rfCharAssertExact(0.0, zeroLength.distanceMeters)
        },
    )
}
