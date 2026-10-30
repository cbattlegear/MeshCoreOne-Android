// PortedFrom: MC1Services/Tests/MC1ServicesTests/SegmentAnalysisTests.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-212: Cross-module equivalence of the RF value mirrors consumed by LOS presentation.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.services.diagnostics.rf.ClearanceStatus as CoreStatus
import com.meshcoreone.android.core.services.diagnostics.rf.RelayPathAnalysisResult as CoreRelay
import com.meshcoreone.android.core.services.diagnostics.rf.SegmentAnalysisResult as CoreSegment
import com.meshcoreone.android.feature.tools.los.AnalysisStatus
import com.meshcoreone.android.feature.tools.los.ClearanceStatus
import com.meshcoreone.android.feature.tools.los.LineOfSightState
import com.meshcoreone.android.feature.tools.los.LosFormatters
import com.meshcoreone.android.feature.tools.los.RelayPathAnalysisResult
import com.meshcoreone.android.feature.tools.los.SegmentAnalysisResult
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class RfResultCorrespondenceTest {
    @Test
    @OriginalCase("SegmentAnalysisTypeTests::SegmentAnalysisResult stores segment data()", "native-equivalent")
    fun pinnedSegmentFieldsAndUnitsAgreeWithThePresentationMirror() {
        val core = CoreSegment("A", "R", CoreStatus.CLEAR, 5000.0, 85.0)
        val feature = SegmentAnalysisResult("A", "R", ClearanceStatus.CLEAR, 5000.0, 85.0)
        assertSegment(core, feature)
        assertEquals("A", feature.startLabel)
        assertEquals("R", feature.endLabel)
        assertEquals(ClearanceStatus.CLEAR, feature.clearanceStatus)
        assertEquals(5000.0, feature.distanceMeters)
        assertEquals(85.0, feature.worstClearancePercent)
        assertEquals(5.0, feature.distanceKm)
    }

    @Test
    @OriginalCase("SegmentAnalysisTypeTests::RelayPathAnalysisResult combines segments()", "native-equivalent")
    fun pinnedCombinedResultReachesLosStateAndHeadlineFormatting() {
        val core = CoreRelay(
            CoreSegment("A", "R", CoreStatus.CLEAR, 5000.0, 85.0),
            CoreSegment("R", "B", CoreStatus.MARGINAL, 3000.0, 65.0),
        )
        val feature = RelayPathAnalysisResult(
            SegmentAnalysisResult("A", "R", ClearanceStatus.CLEAR, 5000.0, 85.0),
            SegmentAnalysisResult("R", "B", ClearanceStatus.MARGINAL, 3000.0, 65.0),
        )
        assertRelay(core, feature)
        assertEquals(8000.0, feature.totalDistanceMeters)
        assertEquals(8.0, feature.totalDistanceKm)
        assertEquals(ClearanceStatus.MARGINAL, feature.overallStatus)
        assertEquals(65.0, LosFormatters.relayHeadlineClearancePercent(feature))
        assertEquals(65, LosFormatters.formatClearancePercent(LosFormatters.relayHeadlineClearancePercent(feature)))
        assertTrue(LineOfSightState(analysisStatus = AnalysisStatus.RelayResult(feature)).hasAnalysisResult)
    }

    @Test
    @OriginalCase("SegmentAnalysisTypeTests::RelayPathAnalysisResult overall status is worst of segments()", "native-equivalent")
    fun pinnedClearAndBlockedOutcomesAndEveryOrderedStatusPairAgree() {
        val clear = pair(CoreStatus.CLEAR, CoreStatus.CLEAR, 90.0, 85.0)
        assertEquals(ClearanceStatus.CLEAR, clear.overallStatus)
        val blocked = pair(CoreStatus.CLEAR, CoreStatus.BLOCKED, 90.0, -10.0)
        assertEquals(ClearanceStatus.BLOCKED, blocked.overallStatus)
        assertEquals(-10.0, LosFormatters.relayHeadlineClearancePercent(blocked))
        assertEquals(0, LosFormatters.formatClearancePercent(LosFormatters.relayHeadlineClearancePercent(blocked)))
        for (a in CoreStatus.entries) for (b in CoreStatus.entries) pair(a, b, 90.0, 85.0)
    }

    private fun pair(a: CoreStatus, b: CoreStatus, ar: Double, rb: Double): RelayPathAnalysisResult {
        val core = CoreRelay(CoreSegment("A", "R", a, 1000.0, ar), CoreSegment("R", "B", b, 1000.0, rb))
        val feature = RelayPathAnalysisResult(
            SegmentAnalysisResult("A", "R", ClearanceStatus.valueOf(a.name), 1000.0, ar),
            SegmentAnalysisResult("R", "B", ClearanceStatus.valueOf(b.name), 1000.0, rb),
        )
        assertRelay(core, feature)
        assertTrue(LosFormatters.statusLabel(feature.overallStatus) != 0)
        assertEquals(feature.overallStatus != ClearanceStatus.BLOCKED,
            LosFormatters.showsClearancePercent(feature.overallStatus))
        return feature
    }

    private fun assertRelay(core: CoreRelay, feature: RelayPathAnalysisResult) {
        assertSegment(core.segmentAR, feature.segmentAR)
        assertSegment(core.segmentRB, feature.segmentRB)
        assertEquals(core.totalDistanceMeters, feature.totalDistanceMeters)
        assertEquals(core.totalDistanceKm, feature.totalDistanceKm)
        assertEquals(core.overallStatus.rawValue, feature.overallStatus.rawValue)
    }

    private fun assertSegment(core: CoreSegment, feature: SegmentAnalysisResult) {
        assertEquals(core.startLabel, feature.startLabel)
        assertEquals(core.endLabel, feature.endLabel)
        assertEquals(core.clearanceStatus.rawValue, feature.clearanceStatus.rawValue)
        assertEquals(core.distanceMeters, feature.distanceMeters)
        assertEquals(core.distanceKm, feature.distanceKm)
        assertEquals(core.worstClearancePercent, feature.worstClearancePercent)
    }
}
