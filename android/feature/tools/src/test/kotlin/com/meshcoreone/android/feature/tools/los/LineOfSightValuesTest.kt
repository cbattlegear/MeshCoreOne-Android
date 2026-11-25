// PortedFrom: MC1Tests/ViewModels/LineOfSightViewModelTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.los

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class LineOfSightValuesTest {
    // region SelectedPointTests

    @Test @OriginalCase("SelectedPointTests::totalHeight is nil when groundElevation is nil()")
    fun `totalHeight is nil when groundElevation is nil`() {
        assertNull(SelectedPoint(SAN_FRANCISCO, contact = null, groundElevation = null, additionalHeight = 10.0).totalHeight)
    }

    @Test @OriginalCase("SelectedPointTests::totalHeight includes both ground and additional()")
    fun `totalHeight includes both ground and additional`() {
        assertEquals(110.0, SelectedPoint(SAN_FRANCISCO, contact = null, groundElevation = 100.0, additionalHeight = 10.0).totalHeight)
    }

    @Test @OriginalCase("SelectedPointTests::isLoadingElevation is true when groundElevation is nil()")
    fun `isLoadingElevation is true when groundElevation is nil`() {
        assertTrue(SelectedPoint(SAN_FRANCISCO, contact = null, groundElevation = null, additionalHeight = 0.0).isLoadingElevation)
    }

    @Test @OriginalCase("SelectedPointTests::isLoadingElevation is false when groundElevation is set()")
    fun `isLoadingElevation is false when groundElevation is set`() {
        assertEquals(false, SelectedPoint(SAN_FRANCISCO, contact = null, groundElevation = 100.0, additionalHeight = 0.0).isLoadingElevation)
    }

    // endregion

    // region AnalysisStatusEquatableTests

    @Test @OriginalCase("AnalysisStatusEquatableTests::idle equals idle()")
    fun `idle equals idle`() {
        assertEquals<AnalysisStatus>(AnalysisStatus.Idle, AnalysisStatus.Idle)
    }

    @Test @OriginalCase("AnalysisStatusEquatableTests::error with same message equals()")
    fun `error with same message equals`() {
        assertEquals<AnalysisStatus>(AnalysisStatus.Error("test"), AnalysisStatus.Error("test"))
    }

    @Test @OriginalCase("AnalysisStatusEquatableTests::error with different message not equal()")
    fun `error with different message not equal`() {
        assertNotEquals<AnalysisStatus>(AnalysisStatus.Error("test1"), AnalysisStatus.Error("test2"))
    }

    @Test @OriginalCase("AnalysisStatusEquatableTests::idle does not equal error()")
    fun `idle does not equal error`() {
        assertNotEquals<AnalysisStatus>(AnalysisStatus.Idle, AnalysisStatus.Error("test"))
    }

    // endregion

    // region RepeaterPointTests

    @Test @OriginalCase("RepeaterPointTests::RepeaterPoint clamps pathFraction to valid range()")
    fun `RepeaterPoint clamps pathFraction to valid range`() {
        assertTrue(RepeaterPoint(TEST_REPEATER_COORDINATE, pathFraction = 0.02).pathFraction >= 0.05)
        assertTrue(RepeaterPoint(TEST_REPEATER_COORDINATE, pathFraction = 0.98).pathFraction <= 0.95)
        assertEquals(0.5, RepeaterPoint(TEST_REPEATER_COORDINATE, pathFraction = 0.5).pathFraction)
        // Oracle rpclamp.*: exact clamp bounds, also through copy (the Swift didSet).
        assertBits("3fa999999999999a", RepeaterPoint(TEST_REPEATER_COORDINATE, pathFraction = -1.0).pathFraction)
        assertBits("3fee666666666666", RepeaterPoint(TEST_REPEATER_COORDINATE).copy(pathFraction = 0.98).pathFraction)
    }

    @Test @OriginalCase("RepeaterPointTests::RepeaterPoint has default height of 10m()")
    fun `RepeaterPoint has default height of 10m`() {
        assertEquals(10.0, RepeaterPoint(TEST_REPEATER_COORDINATE, pathFraction = 0.5).additionalHeight)
    }

    // endregion

    // region RepeaterViewModelTests

    @Test @OriginalCase("RepeaterViewModelTests::Initial repeaterPoint is nil()")
    fun `Initial repeaterPoint is nil`() = losScenario {
        assertNull(holder().state.value.repeaterPoint)
    }

    // endregion

    // region AnalysisStatusRelayTests

    private fun relay() = RelayPathAnalysisResult(
        segmentAR = SegmentAnalysisResult("A", "R", ClearanceStatus.CLEAR, 5000.0, 85.0),
        segmentRB = SegmentAnalysisResult("R", "B", ClearanceStatus.CLEAR, 3000.0, 90.0),
    )

    @Test @OriginalCase("AnalysisStatusRelayTests::relayResult case stores RelayPathAnalysisResult()")
    fun `relayResult case stores RelayPathAnalysisResult`() {
        val status: AnalysisStatus = AnalysisStatus.RelayResult(relay())
        assertEquals(8000.0, assertIs<AnalysisStatus.RelayResult>(status).result.totalDistanceMeters)
    }

    @Test @OriginalCase("AnalysisStatusRelayTests::relayResult equals relayResult with same data()")
    fun `relayResult equals relayResult with same data`() {
        val relayResult = relay()
        assertEquals<AnalysisStatus>(AnalysisStatus.RelayResult(relayResult), AnalysisStatus.RelayResult(relayResult))
        // Value equality, not identity, as with the Swift synthesized Equatable.
        assertEquals<AnalysisStatus>(AnalysisStatus.RelayResult(relay()), AnalysisStatus.RelayResult(relay()))
    }

    // endregion

    // region FrequencyParsingTests

    @Test @OriginalCase("FrequencyParsingTests::Parses a plain dot-decimal value()")
    fun `Parses a plain dot-decimal value`() = losScenario {
        assertEquals(868.5, holder().parseFrequency("868.5"))
    }

    @Test @OriginalCase("FrequencyParsingTests::Parses a comma-decimal value the same as dot-decimal()")
    fun `Parses a comma-decimal value the same as dot-decimal`() = losScenario {
        assertEquals(868.5, holder().parseFrequency("868,5"))
    }

    @Test @OriginalCase("FrequencyParsingTests::Parses an integer value()")
    fun `Parses an integer value`() = losScenario {
        assertEquals(906.0, holder().parseFrequency("906"))
    }

    @Test @OriginalCase("FrequencyParsingTests::Rejects empty input()")
    fun `Rejects empty input`() = losScenario {
        assertNull(holder().parseFrequency(""))
    }

    @Test @OriginalCase("FrequencyParsingTests::Rejects non-numeric input()")
    fun `Rejects non-numeric input`() = losScenario {
        assertNull(holder().parseFrequency("abc"))
    }

    @Test @OriginalCase("FrequencyParsingTests::Rejects zero()")
    fun `Rejects zero`() = losScenario {
        assertNull(holder().parseFrequency("0"))
    }

    @Test @OriginalCase("FrequencyParsingTests::Rejects negative values()")
    fun `Rejects negative values`() = losScenario {
        assertNull(holder().parseFrequency("-906"))
    }

    @Test @OriginalCase("FrequencyParsingTests::Editing format round-trips an integer back through the parser()")
    fun `Editing format round-trips an integer back through the parser`() = losScenario {
        val viewModel = holder()
        val formatted = viewModel.formatFrequencyForEditing(906.0)
        assertEquals("906", formatted)
        assertEquals(906.0, viewModel.parseFrequency(formatted))
    }

    @Test @OriginalCase("FrequencyParsingTests::Editing format uses a dot decimal separator and round-trips()")
    fun `Editing format uses a dot decimal separator and round-trips`() = losScenario {
        val viewModel = holder()
        val formatted = viewModel.formatFrequencyForEditing(868.5)
        assertEquals("868.5", formatted)
        assertEquals(868.5, viewModel.parseFrequency(formatted))
    }

    // endregion
}
