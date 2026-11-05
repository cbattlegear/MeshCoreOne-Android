// PortedFrom: MC1Tests/ViewModels/LineOfSightViewModelTests.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-315 Same-input RF observations through the production App adapter; independent expectations in TS-22-TS-24.md.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.services.content.ElevationService
import com.meshcoreone.android.core.services.content.GeoCoordinate
import com.meshcoreone.android.core.services.diagnostics.rf.RFCalculator
import com.meshcoreone.android.feature.tools.los.AnalysisStatus
import com.meshcoreone.android.feature.tools.los.ClearanceStatus
import com.meshcoreone.android.feature.tools.los.ElevationSample
import com.meshcoreone.android.feature.tools.los.LineOfSightElevationSource
import com.meshcoreone.android.feature.tools.los.LineOfSightPathAnalyzer
import com.meshcoreone.android.feature.tools.los.LineOfSightStateHolder
import com.meshcoreone.android.feature.tools.los.PathAnalysisResult
import com.meshcoreone.android.feature.tools.los.RepeaterPoint
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LineOfSightNumericalTest {
    @Test
    @OriginalCase("AnalysisTests::analyze sets loading status()")
    fun originalLoadingStatusWithRealAnalyzer() = runTest {
        val fixture = fixture()
        fixture.holder.analyze()
        val state = fixture.holder.state.value
        assertTrue(state.isAnalyzing ||
            (state.analysisStatus != AnalysisStatus.Idle && state.analysisStatus != AnalysisStatus.Error("")))
        advanceUntilIdle()
        assertDirect(fixture)
    }

    @Test
    @OriginalCase("AnalysisTests::analyze produces result on success()")
    fun originalSuccessWithRealAnalyzer() = runTest {
        val fixture = fixture()
        fixture.holder.analyze()
        advanceUntilIdle()
        assertTrue(fixture.direct().distanceMeters > 0)
        assertDirect(fixture)
    }

    @Test
    @OriginalCase("AnalysisTests::analyze populates elevation profile()")
    fun originalProfileWithRealAnalyzer() = runTest {
        val fixture = fixture()
        fixture.holder.analyze()
        advanceUntilIdle()
        val state = fixture.holder.state.value
        assertFalse(state.isAnalyzing)
        assertTrue(state.elevationProfile.isNotEmpty())
        assertEquals(80, state.elevationProfile.size)
        assertEquals(80, state.profileSamples.size)
        assertEquals(sanFrancisco, state.elevationProfile.first().coordinate)
        assertEquals(oakland, state.elevationProfile.last().coordinate)
        assertTrue(state.elevationProfile.all { it.elevation == 100.0 })
        assertDirect(fixture)
    }

    @Test
    @OriginalCase("AnalysisTests::analyze without elevations does nothing()")
    fun originalMissingElevationsWithRealAnalyzer() = runTest {
        val fixture = Fixture(this)
        fixture.holder.analyze()
        advanceUntilIdle()
        assertEquals(AnalysisStatus.Idle, fixture.holder.state.value.analysisStatus)
        assertFalse(fixture.holder.state.value.isAnalyzing)
        assertTrue(fixture.analyzer.observations.isEmpty())
        assertEquals(0, fixture.elevation.fetchCount)
    }

    @Test
    @OriginalCase("AnalysisTests::analyze error sets error status()")
    fun originalElevationErrorWithRealAnalyzer() = runTest {
        val fixture = fixture()
        fixture.elevation.shouldFail = true
        fixture.holder.analyze()
        advanceUntilIdle()
        assertEquals(AnalysisStatus.Error("No elevation data"), fixture.holder.state.value.analysisStatus)
        assertFalse(fixture.holder.state.value.isAnalyzing)
        assertTrue(fixture.analyzer.observations.isEmpty())
    }

    @Test
    @OriginalCase("AnalysisTests::New analysis cancels previous()")
    fun originalSupersededAnalysisWithRealAnalyzer() = runTest {
        val fixture = fixture()
        fixture.holder.analyze()
        fixture.holder.analyze()
        advanceUntilIdle()
        assertFalse(fixture.holder.state.value.isAnalyzing)
        assertDirect(fixture)
        assertEquals(1, fixture.analyzer.observations.size)
    }

    @Test
    @OriginalCase("RelayAnalysisTests::analyzeWithRepeater produces relay result()")
    fun originalRelayWithRealAnalyzer() = runTest {
        val fixture = fixture()
        loadProfileThroughAnalysis(fixture, flatProfile101())
        fixture.holder.setRepeaterPoint(RepeaterPoint(testRepeaterCoordinate, additionalHeight = 10.0, pathFraction = 0.5))
        fixture.holder.analyzeWithRepeater()

        val result = fixture.relay()
        assertEquals("A", result.segmentAR.startLabel)
        assertEquals("R", result.segmentAR.endLabel)
        assertEquals("R", result.segmentRB.startLabel)
        assertEquals("B", result.segmentRB.endLabel)
        assertEquals(5000.0, result.segmentAR.distanceMeters)
        assertEquals(5000.0, result.segmentRB.distanceMeters)
        assertNear(38.7636166894, result.segmentAR.worstClearancePercent)
        assertNear(38.7636166894, result.segmentRB.worstClearancePercent)
        assertEquals(ClearanceStatus.PARTIAL_OBSTRUCTION, result.overallStatus)
        val (ar, rb) = fixture.analyzer.observations
        assertEquals(listOf(51, 51), listOf(ar.profile.size, rb.profile.size))
        assertEquals(0.0, ar.profile.first().distanceFromAMeters)
        assertEquals(5000.0, rb.profile.first().distanceFromAMeters)
        assertEquals(10000.0, rb.profile.last().distanceFromAMeters)
        assertEquals(listOf(7.0, 10.0, 10.0, 7.0), listOf(ar.startHeight, ar.endHeight, rb.startHeight, rb.endHeight))
        assertNear(105.5719640403, ar.result.freeSpacePathLoss)
        assertNear(1.6040848236, rb.result.peakDiffractionLoss)
        assertNear(107.1760488638, rb.result.totalPathLoss)
        assertEquals(37, rb.result.obstructionPoints.size)
        assertNear(7900.0, rb.result.worstObstructionPoint!!.distanceFromAMeters)
        assertNear(-7.7820530529, rb.result.worstObstructionPoint!!.obstructionHeightMeters)
    }

    @Test
    @OriginalCase("RelayAnalysisTests::analyzeWithRepeater updates when repeater position changes()")
    fun originalMovedRelayWithRealAnalyzer() = runTest {
        val fixture = fixture()
        loadProfileThroughAnalysis(fixture, flatProfile101())
        fixture.holder.setRepeaterPoint(RepeaterPoint(testRepeaterCoordinate, additionalHeight = 10.0, pathFraction = 0.5))
        fixture.holder.analyzeWithRepeater()
        val initialARDistance = fixture.relay().segmentAR.distanceMeters

        fixture.holder.updateRepeaterPosition(0.3)
        fixture.holder.analyzeWithRepeater()

        val updated = fixture.relay()
        assertTrue(updated.segmentAR.distanceMeters < initialARDistance)
        assertEquals(3000.0, updated.segmentAR.distanceMeters)
        assertEquals(7000.0, updated.segmentRB.distanceMeters)
        assertNear(52.0270384539, updated.segmentAR.worstClearancePercent)
        assertNear(30.8327838045, updated.segmentRB.worstClearancePercent)
        assertEquals(ClearanceStatus.PARTIAL_OBSTRUCTION, updated.overallStatus)
        assertEquals(4, fixture.analyzer.observations.size)
    }

    @Test
    @OriginalCase("RelayAnalysisTests::Off-path height and RF changes reuse cached profiles without network fetch()")
    fun originalOffPathCacheWithRealAnalyzer() = runTest {
        val fixture = fixture()
        val fetchCountAfterPoints = fixture.elevation.fetchCount
        fixture.holder.setRepeaterOffPath(Coordinate(37.79, -122.35))
        advanceUntilIdle()
        assertEquals(100.0, fixture.holder.state.value.repeaterPoint?.groundElevation)
        fixture.holder.analyzeWithRepeater()
        advanceUntilIdle()
        val fetchCountAfterAnalysis = fixture.elevation.fetchCount
        assertTrue(fetchCountAfterAnalysis > fetchCountAfterPoints)
        assertNear(6325.9150876, fixture.relay().segmentAR.distanceMeters)
        assertNear(7106.4660159, fixture.relay().segmentRB.distanceMeters)
        assertNear(33.1912325004, fixture.relay().segmentAR.worstClearancePercent)
        assertNear(30.4785751121, fixture.relay().segmentRB.worstClearancePercent)
        val cachedAR = fixture.holder.state.value.elevationProfileAR
        val cachedRB = fixture.holder.state.value.elevationProfileRB

        fixture.holder.updateRepeaterHeight(25.0)
        fixture.holder.analyzeWithRepeater()
        assertEquals(fetchCountAfterAnalysis, fixture.elevation.fetchCount)
        assertNear(54.9690604188, fixture.relay().segmentAR.worstClearancePercent)
        assertNear(51.1507530375, fixture.relay().segmentRB.worstClearancePercent)
        assertTrue(cachedAR === fixture.analyzer.observations[2].profile)
        assertTrue(cachedRB === fixture.analyzer.observations[3].profile)

        fixture.holder.setRefractionK(4.0 / 3.0)
        advanceUntilIdle()
        assertEquals(fetchCountAfterAnalysis, fixture.elevation.fetchCount)
        assertIs<AnalysisStatus.RelayResult>(fixture.holder.state.value.analysisStatus)
        // The pinned source guards automatic RF reanalysis on the direct profile, absent here.
        assertEquals(4, fixture.analyzer.observations.size)
        fixture.holder.analyzeWithRepeater()
        assertEquals(fetchCountAfterAnalysis, fixture.elevation.fetchCount)
        assertEquals(6, fixture.analyzer.observations.size)
        assertEquals(4.0 / 3.0, fixture.analyzer.observations.last().result.refractionK)
        assertTrue(fixture.relay().segmentAR.worstClearancePercent > 54.9690604188)
    }

    @Test
    fun supersededInFlightFetchCannotPublishARealRfResult() = runTest {
        val fixture = fixture()
        fixture.elevation.fetchDelayMillis = 300
        fixture.holder.analyze()
        runCurrent()
        assertTrue(fixture.holder.state.value.isAnalyzing)
        fixture.holder.analyze()
        advanceUntilIdle()
        assertDirect(fixture)
        assertEquals(1, fixture.analyzer.observations.size)
    }

    @Test
    fun asymmetricObstructionAndOffsetSegmentReachTheActualAppAdapter() {
        val profile = listOf(
            ElevationSample(sanFrancisco, 100.0, 0.0),
            ElevationSample(testRepeaterCoordinate, 109.0, 1500.0),
            ElevationSample(testRepeaterCoordinate, 160.0, 3000.0),
            ElevationSample(oakland, 130.0, 6000.0),
        )
        val direct = AppLineOfSightPathAnalyzer.analyzePath(profile, 10.0, 15.0, 906.0, 1.33)
        val offset = profile.map { ElevationSample(it.coordinate, it.elevation, it.distanceFromAMeters + 9000.0) }
        val segment = AppLineOfSightPathAnalyzer.analyzePathSegment(offset, 10.0, 15.0, 906.0, 1.33)
        val reversed = AppLineOfSightPathAnalyzer.analyzePath(profile, 15.0, 10.0, 906.0, 1.33)
        for (result in listOf(direct, segment)) {
            assertEquals(6000.0, result.distanceMeters)
            assertNear(107.1555889612, result.freeSpacePathLoss)
            assertNear(19.4266094180, result.peakDiffractionLoss)
            assertNear(126.5821983792, result.totalPathLoss)
            assertNear(-148.2623174854, result.worstClearancePercent)
            assertEquals(ClearanceStatus.BLOCKED, result.clearanceStatus)
            assertEquals(906.0, result.frequencyMHz)
            assertEquals(1.33, result.refractionK)
            assertEquals(2, result.obstructionPoints.size)
            assertNear(-9.3516961254, result.obstructionPoints[0].obstructionHeightMeters)
            assertNear(48.4694255457, result.obstructionPoints[0].fresnelClearancePercent)
            assertNear(33.0310718328, result.obstructionPoints[1].obstructionHeightMeters)
            assertNear(-148.2623174854, result.obstructionPoints[1].fresnelClearancePercent)
        }
        assertEquals(listOf(1500.0, 3000.0), direct.obstructionPoints.map { it.distanceFromAMeters })
        assertEquals(listOf(10500.0, 12000.0), segment.obstructionPoints.map { it.distanceFromAMeters })
        assertEquals(listOf(3000.0), reversed.obstructionPoints.map { it.distanceFromAMeters })

        val clear = AppLineOfSightPathAnalyzer.analyzePath(
            profile.map { ElevationSample(it.coordinate, 100.0, it.distanceFromAMeters) }, 50.0, 50.0, 906.0, 1.33,
        )
        assertEquals(ClearanceStatus.CLEAR, clear.clearanceStatus)
        assertNear(222.0448058943, clear.worstClearancePercent)
        assertEquals(0.0, clear.peakDiffractionLoss)
        assertTrue(clear.obstructionPoints.isEmpty())
    }

    private suspend fun TestScope.fixture(): Fixture {
        val fixture = Fixture(this)
        fixture.holder.setPointA(sanFrancisco)
        fixture.holder.setPointB(oakland)
        advanceUntilIdle()
        assertTrue(fixture.holder.state.value.canAnalyze)
        return fixture
    }

    private suspend fun TestScope.loadProfileThroughAnalysis(fixture: Fixture, profile: List<ElevationSample>) {
        fixture.elevation.profileToReturn = profile
        fixture.holder.analyze()
        advanceUntilIdle()
        assertEquals(profile.size, fixture.holder.state.value.elevationProfile.size)
        assertEquals(profile.map { it.distanceFromAMeters }, fixture.holder.state.value.elevationProfile.map { it.distanceFromAMeters })
        assertEquals(profile.map { it.elevation }, fixture.holder.state.value.elevationProfile.map { it.elevation })
        assertTrue(fixture.holder.state.value.analysisStatus is AnalysisStatus.Result)
        fixture.analyzer.observations.clear()
    }

    private fun assertDirect(fixture: Fixture) {
        val result = fixture.direct()
        assertNear(13429.6253340, result.distanceMeters)
        assertNear(114.1538418873, result.freeSpacePathLoss)
        assertNear(4.7766584400, result.peakDiffractionLoss)
        assertNear(118.9305003273, result.totalPathLoss)
        assertNear(10.3874572080, result.worstClearancePercent)
        assertEquals(ClearanceStatus.PARTIAL_OBSTRUCTION, result.clearanceStatus)
        assertEquals(74, result.obstructionPoints.size)
        assertNear(6630.4457259, result.worstObstructionPoint!!.distanceFromAMeters)
        assertNear(-3.4619689709, result.worstObstructionPoint!!.obstructionHeightMeters)
        assertEquals(906.0, result.frequencyMHz)
        assertEquals(1.0, result.refractionK)
        val call = fixture.analyzer.observations.single()
        assertEquals("path", call.kind)
        assertEquals(7.0, call.startHeight)
        assertEquals(7.0, call.endHeight)
    }

    private class Fixture(test: TestScope) {
        val elevation = MockElevationSource()
        val analyzer = ObservingAnalyzer()
        val holder = LineOfSightStateHolder(test, elevation, analyzer, test.coroutineContext)

        fun direct() = assertIs<AnalysisStatus.Result>(holder.state.value.analysisStatus).result
        fun relay() = assertIs<AnalysisStatus.RelayResult>(holder.state.value.analysisStatus).result
    }

    private class Observation(
        val kind: String,
        val profile: List<ElevationSample>,
        val startHeight: Double,
        val endHeight: Double,
        val result: PathAnalysisResult,
    )

    private class ObservingAnalyzer : LineOfSightPathAnalyzer by AppLineOfSightPathAnalyzer {
        val observations = mutableListOf<Observation>()

        override fun analyzePath(
            elevationProfile: List<ElevationSample>, pointAHeightMeters: Double, pointBHeightMeters: Double,
            frequencyMHz: Double, refractionK: Double,
        ): PathAnalysisResult = AppLineOfSightPathAnalyzer.analyzePath(
            elevationProfile, pointAHeightMeters, pointBHeightMeters, frequencyMHz, refractionK,
        ).also { observations += Observation("path", elevationProfile, pointAHeightMeters, pointBHeightMeters, it) }

        override fun analyzePathSegment(
            elevationProfile: List<ElevationSample>, startHeightMeters: Double, endHeightMeters: Double,
            frequencyMHz: Double, refractionK: Double,
        ): PathAnalysisResult = AppLineOfSightPathAnalyzer.analyzePathSegment(
            elevationProfile, startHeightMeters, endHeightMeters, frequencyMHz, refractionK,
        ).also { observations += Observation("segment", elevationProfile, startHeightMeters, endHeightMeters, it) }
    }

    private class MockElevationSource : LineOfSightElevationSource {
        var fetchCount = 0
        var shouldFail = false
        var fetchDelayMillis = 0L
        var profileToReturn: List<ElevationSample>? = null

        override suspend fun fetchElevation(coordinate: Coordinate): Double {
            fetchCount++
            delay(fetchDelayMillis)
            check(!shouldFail) { "No elevation data" }
            return 100.0
        }

        override suspend fun fetchElevations(path: List<Coordinate>): List<ElevationSample> {
            fetchCount++
            delay(fetchDelayMillis)
            check(!shouldFail) { "No elevation data" }
            profileToReturn?.let { return it }
            return path.map { ElevationSample(it, 100.0, RFCalculator.distance(path.first(), it)) }
        }

        override fun optimalSampleCount(distanceMeters: Double) = ElevationService.optimalSampleCount(distanceMeters)

        override fun sampleCoordinates(from: Coordinate, to: Coordinate, sampleCount: Int): List<Coordinate> =
            ElevationService.sampleCoordinates(
                GeoCoordinate(from.latitude, from.longitude), GeoCoordinate(to.latitude, to.longitude), sampleCount,
            ).map { Coordinate(it.latitude, it.longitude) }
    }

    private fun flatProfile101() = (0..100).map {
        ElevationSample(Coordinate(37.7749 + it * 0.001, -122.4194), 100.0, it * 100.0)
    }

    private fun assertNear(expected: Double, actual: Double) = assertEquals(expected, actual, 0.000001)

    private companion object {
        val sanFrancisco = Coordinate(37.7749, -122.4194)
        val oakland = Coordinate(37.8044, -122.2712)
        val testRepeaterCoordinate = Coordinate(37.8, -122.35)
    }
}
