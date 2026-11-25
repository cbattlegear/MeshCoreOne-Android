// PortedFrom: MC1Services/Tests/MC1ServicesTests/SegmentAnalysisTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.diagnostics.rf

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

private fun rfSegmentTypeCases(suite: String, vararg cases: Pair<String, () -> Unit>): List<DynamicTest> =
    cases.map { (name, body) -> DynamicTest.dynamicTest("$suite::$name()", body) }

private fun rfSegment(
    start: String,
    end: String,
    status: ClearanceStatus,
    distance: Double,
    worst: Double,
): SegmentAnalysisResult = SegmentAnalysisResult(start, end, status, distance, worst)

class SegmentAnalysisTypeTest {
    @TestFactory fun segmentAnalysisTypes() = rfSegmentTypeCases(
        "SegmentAnalysisTypeTests",
        "SegmentAnalysisResult stores segment data" to {
            val result = rfSegment("A", "R", ClearanceStatus.CLEAR, 5000.0, 85.0)
            assertEquals("A", result.startLabel)
            assertEquals("R", result.endLabel)
            assertEquals(ClearanceStatus.CLEAR, result.clearanceStatus)
            assertTrue(result.distanceMeters == 5000.0)
            assertTrue(result.worstClearancePercent == 85.0)
        },
        "RelayPathAnalysisResult combines segments" to {
            val segmentAR = rfSegment("A", "R", ClearanceStatus.CLEAR, 5000.0, 85.0)
            val segmentRB = rfSegment("R", "B", ClearanceStatus.MARGINAL, 3000.0, 65.0)
            val result = RelayPathAnalysisResult(segmentAR = segmentAR, segmentRB = segmentRB)
            assertTrue(result.segmentAR.distanceMeters == 5000.0)
            assertTrue(result.segmentRB.distanceMeters == 3000.0)
            assertTrue(result.totalDistanceMeters == 8000.0)
            assertEquals(ClearanceStatus.MARGINAL, result.overallStatus) // worst of the two
        },
        "RelayPathAnalysisResult overall status is worst of segments" to {
            val bothClear = RelayPathAnalysisResult(
                rfSegment("A", "R", ClearanceStatus.CLEAR, 1000.0, 90.0),
                rfSegment("R", "B", ClearanceStatus.CLEAR, 1000.0, 85.0),
            )
            assertEquals(ClearanceStatus.CLEAR, bothClear.overallStatus)

            val oneBlocked = RelayPathAnalysisResult(
                rfSegment("A", "R", ClearanceStatus.CLEAR, 1000.0, 90.0),
                rfSegment("R", "B", ClearanceStatus.BLOCKED, 1000.0, -10.0),
            )
            assertEquals(ClearanceStatus.BLOCKED, oneBlocked.overallStatus)
        },
    )

    @TestFactory fun androidSegmentCases() = rfSegmentTypeCases(
        "WP-212",
        "Segment and relay km conversions divide meters by 1000" to {
            val relay = RelayPathAnalysisResult(
                rfSegment("A", "R", ClearanceStatus.PARTIAL_OBSTRUCTION, 1500.0, 10.0),
                rfSegment("R", "B", ClearanceStatus.MARGINAL, 2250.0, 70.0),
            )
            assertTrue(relay.segmentAR.distanceKm == 1.5)
            assertTrue(relay.totalDistanceKm == 3.75)
            assertEquals(ClearanceStatus.PARTIAL_OBSTRUCTION, relay.overallStatus)
        },
        "Segment equality uses Swift Double semantics" to {
            assertEquals(
                rfSegment("A", "R", ClearanceStatus.CLEAR, 0.0, 85.0),
                rfSegment("A", "R", ClearanceStatus.CLEAR, -0.0, 85.0),
            )
            assertEquals(
                rfSegment("A", "R", ClearanceStatus.CLEAR, 0.0, 85.0).hashCode(),
                rfSegment("A", "R", ClearanceStatus.CLEAR, -0.0, 85.0).hashCode(),
            )
        },
    )
}
