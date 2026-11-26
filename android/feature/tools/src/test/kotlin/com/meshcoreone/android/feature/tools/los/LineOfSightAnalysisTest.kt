// PortedFrom: MC1Tests/ViewModels/LineOfSightViewModelTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.los

import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Test

class LineOfSightAnalysisTest {
    private suspend fun LosScenario.holderWithBothElevations(): LineOfSightStateHolder {
        val viewModel = holder()
        viewModel.setPointA(SAN_FRANCISCO)
        viewModel.setPointB(OAKLAND)
        waitUntil("both point elevations should load") { viewModel.state.value.canAnalyze }
        return viewModel
    }

    // region AnalysisTests

    @Test @OriginalCase("AnalysisTests::analyze sets loading status()")
    fun `analyze sets loading status`() = losScenario {
        val viewModel = holderWithBothElevations()
        viewModel.analyze()
        val state = viewModel.state.value
        val isAnalyzingOrResult = state.isAnalyzing ||
            (state.analysisStatus != AnalysisStatus.Idle && state.analysisStatus != AnalysisStatus.Error(""))
        assertTrue(isAnalyzingOrResult)
    }

    @Test @OriginalCase("AnalysisTests::analyze produces result on success()")
    fun `analyze produces result on success`() = losScenario {
        val viewModel = holderWithBothElevations()
        viewModel.analyze()
        waitUntil("analysis should produce result") { viewModel.state.value.analysisStatus is AnalysisStatus.Result }
        val result = assertIs<AnalysisStatus.Result>(viewModel.state.value.analysisStatus).result
        assertTrue(result.distanceMeters > 0)
        // The direct path was sampled A->B with the WP-218 sample count and analyzed with the point heights.
        val call = analyzer.calls.single { it.kind == "path" }
        assertEquals(80, call.size)
        assertEquals(7.0, call.startHeight)
        assertEquals(7.0, call.endHeight)
        assertEquals(906.0, call.frequencyMHz)
        assertEquals(1.0, call.refractionK)
    }

    @Test @OriginalCase("AnalysisTests::analyze populates elevation profile()")
    fun `analyze populates elevation profile`() = losScenario {
        val viewModel = holderWithBothElevations()
        viewModel.analyze()
        waitUntil("analysis should complete") { !viewModel.state.value.isAnalyzing }
        assertTrue(viewModel.state.value.elevationProfile.isNotEmpty())
        assertEquals(viewModel.state.value.elevationProfile.size, viewModel.state.value.profileSamples.size)
    }

    @Test @OriginalCase("AnalysisTests::analyze without elevations does nothing()")
    fun `analyze without elevations does nothing`() = losScenario {
        val viewModel = holder()
        viewModel.analyze()
        assertEquals(AnalysisStatus.Idle, viewModel.state.value.analysisStatus)
        assertEquals(false, viewModel.state.value.isAnalyzing)
    }

    @Test @OriginalCase("AnalysisTests::analyze error sets error status()")
    fun `analyze error sets error status`() = losScenario {
        val viewModel = holderWithBothElevations()
        elevation.shouldFail = true
        viewModel.analyze()
        waitUntil("analysis should transition to error") { viewModel.state.value.analysisStatus is AnalysisStatus.Error }
        assertEquals(AnalysisStatus.Error("No elevation data"), viewModel.state.value.analysisStatus)
        assertEquals(false, viewModel.state.value.isAnalyzing)
    }

    @Test @OriginalCase("AnalysisTests::New analysis cancels previous()")
    fun `New analysis cancels previous`() = losScenario {
        val viewModel = holderWithBothElevations()
        viewModel.analyze()
        viewModel.analyze()
        waitUntil("analysis should complete") { !viewModel.state.value.isAnalyzing }
        assertIs<AnalysisStatus.Result>(viewModel.state.value.analysisStatus)
        // The superseded task never reached analysis.
        assertEquals(1, analyzer.calls.count { it.kind == "path" })
    }

    // endregion

    // region LoadRepeatersTests

    @Test @OriginalCase("LoadRepeatersTests::loadRepeaters filters to only repeaters with location()")
    fun `loadRepeaters filters to only repeaters with location`() = losScenario {
        val store = FakeContactSource()
        val radioId = RadioId(UUID.randomUUID())
        store.add(createTestContact("Repeater 1", 37.7749, -122.4194, ContactType.REPEATER, radioId))
        store.add(createTestContact("Repeater 2", 0.0, 0.0, ContactType.REPEATER, radioId))
        store.add(createTestContact("Chat User", 37.8044, -122.2712, ContactType.CHAT, radioId))
        store.add(createTestContact("Room Server", 37.8716, -122.2727, ContactType.ROOM, radioId))
        val viewModel = holder()
        viewModel.configure(contactSource = { store }, radioId = { radioId })

        viewModel.loadRepeaters()

        assertEquals(listOf("Repeater 1"), viewModel.state.value.repeatersWithLocation.map { it.name })
    }

    @Test @OriginalCase("LoadRepeatersTests::loadRepeaters returns empty when no repeaters have location()")
    fun `loadRepeaters returns empty when no repeaters have location`() = losScenario {
        val store = FakeContactSource()
        val radioId = RadioId(UUID.randomUUID())
        store.add(createTestContact("Repeater 1", 0.0, 0.0, ContactType.REPEATER, radioId))
        store.add(createTestContact("Repeater 2", 0.0, 0.0, ContactType.REPEATER, radioId))
        val viewModel = holder()
        viewModel.configure(contactSource = { store }, radioId = { radioId })

        viewModel.loadRepeaters()

        assertTrue(viewModel.state.value.repeatersWithLocation.isEmpty())
    }

    @Test @OriginalCase("LoadRepeatersTests::loadRepeaters does nothing without configuration()")
    fun `loadRepeaters does nothing without configuration`() = losScenario {
        val viewModel = holder()
        viewModel.loadRepeaters()
        assertTrue(viewModel.state.value.repeatersWithLocation.isEmpty())
    }

    @Test @OriginalCase("LoadRepeatersTests::loadRepeaters only loads repeaters for configured device()")
    fun `loadRepeaters only loads repeaters for configured device`() = losScenario {
        val store = FakeContactSource()
        val radioId1 = RadioId(UUID.randomUUID())
        val radioId2 = RadioId(UUID.randomUUID())
        store.add(createTestContact("Repeater Device 1", 37.7749, -122.4194, ContactType.REPEATER, radioId1))
        store.add(createTestContact("Repeater Device 2", 37.8044, -122.2712, ContactType.REPEATER, radioId2))
        val viewModel = holder()
        viewModel.configure(contactSource = { store }, radioId = { radioId1 })

        viewModel.loadRepeaters()

        assertEquals(listOf("Repeater Device 1"), viewModel.state.value.repeatersWithLocation.map { it.name })
    }

    @Test @OriginalCase("LoadRepeatersTests::Initial repeatersWithLocation is empty()")
    fun `Initial repeatersWithLocation is empty`() = losScenario {
        assertTrue(holder().state.value.repeatersWithLocation.isEmpty())
    }

    // endregion

    // region ElevationInterpolationTests

    private val twoSampleProfile = listOf(
        ElevationSample(SAN_FRANCISCO, 100.0, 0.0),
        ElevationSample(OAKLAND, 200.0, 1000.0),
    )

    @Test @OriginalCase("ElevationInterpolationTests::elevationAt returns nil when profile is empty()")
    fun `elevationAt returns nil when profile is empty`() = losScenario {
        assertNull(holder().state.value.elevationAt(0.5))
    }

    @Test @OriginalCase("ElevationInterpolationTests::elevationAt interpolates between samples()")
    fun `elevationAt interpolates between samples`() = losScenario {
        val viewModel = holder()
        viewModel.setElevationProfileForTesting(twoSampleProfile)
        val state = viewModel.state.value
        assertTrue(abs(assertNotNull(state.elevationAt(0.5)) - 150) < 0.01)
        assertTrue(abs(assertNotNull(state.elevationAt(0.0)) - 100) < 0.01)
        assertTrue(abs(assertNotNull(state.elevationAt(1.0)) - 200) < 0.01)
    }

    @Test @OriginalCase("ElevationInterpolationTests::coordinateAt interpolates between samples()")
    fun `coordinateAt interpolates between samples`() = losScenario {
        val viewModel = holder()
        viewModel.setElevationProfileForTesting(twoSampleProfile)
        val midCoord = assertNotNull(viewModel.state.value.coordinateAt(0.5))
        val expectedLat = (SAN_FRANCISCO.latitude + OAKLAND.latitude) / 2
        val expectedLon = (SAN_FRANCISCO.longitude + OAKLAND.longitude) / 2
        assertTrue(abs(midCoord.latitude - expectedLat) < 0.001)
        assertTrue(abs(midCoord.longitude - expectedLon) < 0.001)
        // Exact Swift interpolation values (oracle mid.lat / mid.lon).
        assertBits("4042e513404ea4a9", midCoord.latitude, "mid.lat")
        assertBits("c05e9619652bd3c3", midCoord.longitude, "mid.lon")
    }

    @Test @OriginalCase("ElevationInterpolationTests::elevationAt returns nil when profile has single sample()")
    fun `elevationAt returns nil when profile has single sample`() = losScenario {
        val viewModel = holder()
        viewModel.setElevationProfileForTesting(listOf(ElevationSample(SAN_FRANCISCO, 100.0, 0.0)))
        assertNull(viewModel.state.value.elevationAt(0.5))
    }

    @Test @OriginalCase("ElevationInterpolationTests::elevationAt clamps pathFraction outside valid range()")
    fun `elevationAt clamps pathFraction outside valid range`() = losScenario {
        val viewModel = holder()
        viewModel.setElevationProfileForTesting(twoSampleProfile)
        val state = viewModel.state.value
        assertTrue(abs(assertNotNull(state.elevationAt(-0.5)) - 100) < 0.01)
        assertTrue(abs(assertNotNull(state.elevationAt(1.5)) - 200) < 0.01)
    }

    // endregion

    // region RepeaterLifecycleTests

    private fun obstructedResult() = PathAnalysisResult(
        distanceMeters = 10000.0,
        freeSpacePathLoss = 110.0,
        peakDiffractionLoss = 15.0,
        totalPathLoss = 125.0,
        clearanceStatus = ClearanceStatus.PARTIAL_OBSTRUCTION,
        worstClearancePercent = 45.0,
        obstructionPoints = listOf(ObstructionPoint(3000.0, 20.0, 45.0), ObstructionPoint(7000.0, 10.0, 55.0)),
        frequencyMHz = 906.0,
        refractionK = 1.0,
    )

    @Test @OriginalCase("RepeaterLifecycleTests::addRepeater places at worst obstruction point()")
    fun `addRepeater places at worst obstruction point`() = losScenario {
        val viewModel = holder()
        viewModel.setElevationProfileForTesting(flatProfile101())
        viewModel.setAnalysisStatusForTesting(obstructedResult())

        viewModel.addRepeater()

        val repeater = assertNotNull(viewModel.state.value.repeaterPoint)
        assertTrue(abs(repeater.pathFraction - 0.3) < 0.01)
        assertEquals(true, repeater.isOnPath)
        assertEquals(10.0, repeater.additionalHeight)
        assertEquals(100.0, repeater.groundElevation)
    }

    @Test @OriginalCase("RepeaterLifecycleTests::clearRepeater removes repeater()")
    fun `clearRepeater removes repeater`() = losScenario {
        val viewModel = holder()
        viewModel.setRepeaterPoint(RepeaterPoint(TEST_REPEATER_COORDINATE, pathFraction = 0.5))
        assertNotNull(viewModel.state.value.repeaterPoint)
        viewModel.clearRepeater()
        assertNull(viewModel.state.value.repeaterPoint)
    }

    @Test @OriginalCase("RepeaterLifecycleTests::updateRepeaterPosition updates pathFraction()")
    fun `updateRepeaterPosition updates pathFraction`() = losScenario {
        val viewModel = holder()
        viewModel.setRepeaterPoint(RepeaterPoint(TEST_REPEATER_COORDINATE, pathFraction = 0.5))
        viewModel.updateRepeaterPosition(0.7)
        assertEquals(0.7, viewModel.state.value.repeaterPoint?.pathFraction)
    }

    @Test @OriginalCase("RepeaterLifecycleTests::updateRepeaterPosition clamps to valid range()")
    fun `updateRepeaterPosition clamps to valid range`() = losScenario {
        val viewModel = holder()
        viewModel.setRepeaterPoint(RepeaterPoint(TEST_REPEATER_COORDINATE, pathFraction = 0.5))
        viewModel.updateRepeaterPosition(0.01)
        assertTrue(assertNotNull(viewModel.state.value.repeaterPoint).pathFraction >= 0.05)
        viewModel.updateRepeaterPosition(0.99)
        assertTrue(assertNotNull(viewModel.state.value.repeaterPoint).pathFraction <= 0.95)
    }

    @Test @OriginalCase("RepeaterLifecycleTests::updateRepeaterHeight updates additionalHeight()")
    fun `updateRepeaterHeight updates additionalHeight`() = losScenario {
        val viewModel = holder()
        viewModel.setRepeaterPoint(RepeaterPoint(TEST_REPEATER_COORDINATE, additionalHeight = 10.0, pathFraction = 0.5))
        viewModel.updateRepeaterHeight(25.0)
        assertEquals(25.0, viewModel.state.value.repeaterPoint?.additionalHeight)
    }

    @Test @OriginalCase("RepeaterLifecycleTests::updateRepeaterHeight clamps negative to zero()")
    fun `updateRepeaterHeight clamps negative to zero`() = losScenario {
        val viewModel = holder()
        viewModel.setRepeaterPoint(RepeaterPoint(TEST_REPEATER_COORDINATE, additionalHeight = 10.0, pathFraction = 0.5))
        viewModel.updateRepeaterHeight(-5.0)
        assertEquals(0.0, viewModel.state.value.repeaterPoint?.additionalHeight)
    }

    @Test @OriginalCase("RepeaterLifecycleTests::clearPointA removes repeater()")
    fun `clearPointA removes repeater`() = losScenario {
        val viewModel = holder()
        viewModel.setPointA(SAN_FRANCISCO)
        viewModel.setRepeaterPoint(RepeaterPoint(TEST_REPEATER_COORDINATE, pathFraction = 0.5))
        viewModel.clearPointA()
        assertNull(viewModel.state.value.repeaterPoint)
    }

    @Test @OriginalCase("RepeaterLifecycleTests::clearPointB removes repeater()")
    fun `clearPointB removes repeater`() = losScenario {
        val viewModel = holder()
        viewModel.setPointA(SAN_FRANCISCO)
        viewModel.setPointB(OAKLAND)
        viewModel.setRepeaterPoint(RepeaterPoint(TEST_REPEATER_COORDINATE, pathFraction = 0.5))
        viewModel.clearPointB()
        assertNull(viewModel.state.value.repeaterPoint)
    }

    // endregion

    // region RelayAnalysisTests

    @Test @OriginalCase("RelayAnalysisTests::analyzeWithRepeater produces relay result()")
    fun `analyzeWithRepeater produces relay result`() = losScenario {
        val viewModel = holderWithBothElevations()
        viewModel.setElevationProfileForTesting(flatProfile101())
        viewModel.setRepeaterPoint(RepeaterPoint(TEST_REPEATER_COORDINATE, additionalHeight = 10.0, pathFraction = 0.5))

        viewModel.analyzeWithRepeater()

        val result = assertIs<AnalysisStatus.RelayResult>(viewModel.state.value.analysisStatus).result
        assertEquals("A", result.segmentAR.startLabel)
        assertEquals("R", result.segmentAR.endLabel)
        assertEquals("R", result.segmentRB.startLabel)
        assertEquals("B", result.segmentRB.endLabel)
        // On-path split at index 50 shares the junction sample; heights route A->R and R->B.
        val (ar, rb) = analyzer.calls.filter { it.kind == "segment" }
        assertEquals(51, ar.size)
        assertEquals(51, rb.size)
        assertEquals(5000.0, ar.lastDistance)
        assertEquals(5000.0, rb.firstDistance)
        assertEquals(listOf(7.0, 10.0, 10.0, 7.0), listOf(ar.startHeight, ar.endHeight, rb.startHeight, rb.endHeight))
        assertEquals(51, viewModel.state.value.profileSamples.size)
        assertEquals(51, viewModel.state.value.profileSamplesRB.size)
    }

    @Test @OriginalCase("RelayAnalysisTests::analyzeWithRepeater updates when repeater position changes()")
    fun `analyzeWithRepeater updates when repeater position changes`() = losScenario {
        val viewModel = holderWithBothElevations()
        viewModel.setElevationProfileForTesting(flatProfile101())
        viewModel.setRepeaterPoint(RepeaterPoint(TEST_REPEATER_COORDINATE, additionalHeight = 10.0, pathFraction = 0.5))
        viewModel.analyzeWithRepeater()
        val initial = assertIs<AnalysisStatus.RelayResult>(viewModel.state.value.analysisStatus).result
        val initialARDistance = initial.segmentAR.distanceMeters

        viewModel.updateRepeaterPosition(0.3)
        viewModel.analyzeWithRepeater()

        val updated = assertIs<AnalysisStatus.RelayResult>(viewModel.state.value.analysisStatus).result
        assertTrue(updated.segmentAR.distanceMeters < initialARDistance)
    }

    @Test @OriginalCase("RelayAnalysisTests::Off-path height and RF changes reuse cached profiles without network fetch()")
    fun `Off-path height and RF changes reuse cached profiles without network fetch`() = losScenario {
        val viewModel = holderWithBothElevations()
        val fetchCountAfterPoints = elevation.fetchCount

        viewModel.setRepeaterOffPath(Coordinate(37.79, -122.35))
        waitUntil("repeater elevation should load") { viewModel.state.value.repeaterPoint?.groundElevation != null }

        viewModel.analyzeWithRepeater()
        waitUntil("off-path analysis should complete") { viewModel.state.value.analysisStatus is AnalysisStatus.RelayResult }

        val fetchCountAfterAnalysis = elevation.fetchCount
        assertTrue(fetchCountAfterAnalysis > fetchCountAfterPoints)

        viewModel.updateRepeaterHeight(25.0)
        viewModel.analyzeWithRepeater()
        assertEquals(fetchCountAfterAnalysis, elevation.fetchCount)
        assertIs<AnalysisStatus.RelayResult>(viewModel.state.value.analysisStatus)

        viewModel.setRefractionK(4.0 / 3.0)
        settle()
        assertEquals(fetchCountAfterAnalysis, elevation.fetchCount)
        assertIs<AnalysisStatus.RelayResult>(viewModel.state.value.analysisStatus)
    }

    // endregion

    // region AnalysisTaskHygieneTests

    @Test @OriginalCase("AnalysisTaskHygieneTests::Re-analysis after an RF change clears isAnalyzing()")
    fun `Re-analysis after an RF change clears isAnalyzing`() = losScenario {
        val viewModel = holderWithBothElevations()
        viewModel.analyze()
        waitUntil("analysis should produce result") { viewModel.state.value.analysisStatus is AnalysisStatus.Result }

        viewModel.setRefractionK(4.0 / 3.0)
        assertEquals(true, viewModel.state.value.isAnalyzing)

        waitUntil("re-analysis should clear isAnalyzing") { !viewModel.state.value.isAnalyzing }
        assertEquals(false, viewModel.state.value.isAnalyzing)
        assertIs<AnalysisStatus.Result>(viewModel.state.value.analysisStatus)
        assertEquals(4.0 / 3.0, analyzer.calls.last().refractionK)
    }

    @Test @OriginalCase("AnalysisTaskHygieneTests::Re-analysis that supersedes an in-flight analysis does not strand isAnalyzing()")
    fun `Re-analysis that supersedes an in-flight analysis does not strand isAnalyzing`() = losScenario {
        val viewModel = holderWithBothElevations()
        elevation.fetchDelayMillis = 300

        viewModel.analyze()
        assertEquals(true, viewModel.state.value.isAnalyzing)
        settle()
        assertEquals(1, clock.pendingSleepers, "profile fetch should be in flight")

        viewModel.setElevationProfileForTesting(
            listOf(ElevationSample(SAN_FRANCISCO, 100.0, 0.0), ElevationSample(OAKLAND, 100.0, 1000.0)),
        )
        viewModel.setRefractionK(4.0 / 3.0)

        waitUntil("superseding re-analysis should clear isAnalyzing") { !viewModel.state.value.isAnalyzing }
        assertEquals(false, viewModel.state.value.isAnalyzing)
        // The superseded fetch was cancelled, so its 300 ms sleeper is gone.
        assertEquals(0, clock.pendingSleepers)
    }

    @Test @OriginalCase("AnalysisTaskHygieneTests::clearRepeater cancels the in-flight off-path elevation fetch()")
    fun `clearRepeater cancels the in-flight off-path elevation fetch`() = losScenario {
        val viewModel = holderWithBothElevations()
        elevation.fetchDelayMillis = 300

        viewModel.setRepeaterOffPath(Coordinate(37.79, -122.35))
        assertNull(viewModel.state.value.repeaterPoint?.groundElevation)
        settle()

        viewModel.clearRepeater()
        assertNull(viewModel.state.value.repeaterPoint)

        val newCoord = Coordinate(37.70, -122.30)
        viewModel.setRepeaterPoint(RepeaterPoint(newCoord, groundElevation = null, isOnPath = false, pathFraction = 0.5))

        clock.advanceBy(500)
        assertNull(viewModel.state.value.repeaterPoint?.groundElevation)
        assertNotNull(viewModel.state.value.repeaterPoint)
    }

    @Test @OriginalCase("AnalysisTaskHygieneTests::Cancelled off-path analysis does not surface an error or strand isAnalyzing()")
    fun `Cancelled off-path analysis does not surface an error or strand isAnalyzing`() = losScenario {
        val viewModel = holderWithBothElevations()
        viewModel.setRepeaterOffPath(Coordinate(37.79, -122.35))
        waitUntil("repeater elevation should load") { viewModel.state.value.repeaterPoint?.groundElevation != null }

        elevation.fetchDelayMillis = 300
        viewModel.analyzeWithRepeater()
        waitUntil("off-path analysis should start") { viewModel.state.value.isAnalyzing }

        viewModel.clear()

        waitUntil("cancelled off-path analysis should clear isAnalyzing") { !viewModel.state.value.isAnalyzing }
        clock.advanceBy(500)
        assertEquals(false, viewModel.state.value.isAnalyzing)
        if (viewModel.state.value.analysisStatus is AnalysisStatus.Error) {
            fail("Cancelled off-path analysis surfaced a user-visible error")
        }
        assertEquals(AnalysisStatus.Idle, viewModel.state.value.analysisStatus)
    }

    // endregion
}
