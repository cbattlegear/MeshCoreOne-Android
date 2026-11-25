// PortedFrom: MC1Tests/ViewModels/NoiseFloorViewModelTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.diagnostics.noisefloor

import com.meshcoreone.android.feature.tools.diagnostics.support.OriginalCase
import com.meshcoreone.android.feature.tools.diagnostics.support.TestScheduler
import com.meshcoreone.android.feature.tools.diagnostics.support.XmlDiagnosticsText
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

internal fun reading(noiseFloor: Int, timestamp: Instant = Instant.EPOCH, rssi: Int = -80, snr: Double = 5.0) =
    NoiseFloorReading(UUID.randomUUID(), timestamp, noiseFloor.toShort(), rssi.toByte(), snr)

class NoiseFloorTest {
    private val scheduler = TestScheduler()
    private val text = XmlDiagnosticsText()
    private val holder = NoiseFloorStateHolder(scheduler.scope, text, scheduler.clock)
    private val state get() = holder.state.value

    // MARK: - NoiseFloorReading / NoiseFloorStatistics

    @Test @OriginalCase("NoiseFloorReadingTests::reading stores all values correctly()")
    fun `reading stores all values correctly`() {
        val timestamp = Instant.parse("2024-05-01T12:00:00Z")
        val value = NoiseFloorReading(UUID.randomUUID(), timestamp, -95, -80, 7.5)
        assertEquals(-95, value.noiseFloor.toInt())
        assertEquals(-80, value.lastRSSI.toInt())
        assertEquals(7.5, value.lastSNR)
        assertEquals(timestamp, value.timestamp)
    }

    @Test @OriginalCase("NoiseFloorStatisticsTests::statistics calculates min/max/avg correctly()")
    fun `statistics calculates min, max and avg correctly`() {
        val stats = NoiseFloorStatistics(min = -110, max = -80, average = -95.5)
        assertEquals(-110, stats.min.toInt())
        assertEquals(-80, stats.max.toInt())
        assertEquals(-95.5, stats.average)
    }

    // MARK: - NoiseFloorQuality

    @Test @OriginalCase("NoiseFloorQualityTests::excellent for noise floor <= -100()")
    fun `excellent for noise floor at most -100`() {
        assertEquals(NoiseFloorQuality.EXCELLENT, NoiseFloorQuality.from(-100))
        assertEquals(NoiseFloorQuality.EXCELLENT, NoiseFloorQuality.from(-110))
    }

    @Test @OriginalCase("NoiseFloorQualityTests::good for noise floor <= -90()")
    fun `good for noise floor at most -90`() {
        assertEquals(NoiseFloorQuality.GOOD, NoiseFloorQuality.from(-90))
        assertEquals(NoiseFloorQuality.GOOD, NoiseFloorQuality.from(-99))
    }

    @Test @OriginalCase("NoiseFloorQualityTests::fair for noise floor <= -80()")
    fun `fair for noise floor at most -80`() {
        assertEquals(NoiseFloorQuality.FAIR, NoiseFloorQuality.from(-80))
        assertEquals(NoiseFloorQuality.FAIR, NoiseFloorQuality.from(-89))
    }

    @Test @OriginalCase("NoiseFloorQualityTests::poor for noise floor > -80()")
    fun `poor for noise floor above -80`() {
        assertEquals(NoiseFloorQuality.POOR, NoiseFloorQuality.from(-79))
        assertEquals(NoiseFloorQuality.POOR, NoiseFloorQuality.from(-60))
    }

    @Test @OriginalCase("NoiseFloorQualityTests::label returns correct strings()")
    fun `label returns correct strings`() {
        val labels = NoiseFloorQuality.entries.associateWith { text.string(it.labelId) }
        assertEquals(
            mapOf(
                NoiseFloorQuality.EXCELLENT to "Excellent", NoiseFloorQuality.GOOD to "Good", NoiseFloorQuality.FAIR to "Fair",
                NoiseFloorQuality.POOR to "Poor", NoiseFloorQuality.UNKNOWN to "Unknown",
            ),
            labels,
        )
    }

    @Test @OriginalCase("NoiseFloorQualityTests::icon returns correct SF Symbols()", "platform-adaptation")
    fun `icon returns the source symbol tokens`() {
        assertEquals("checkmark.circle.fill", NoiseFloorQuality.EXCELLENT.sourceSymbol)
        assertEquals("circle.fill", NoiseFloorQuality.GOOD.sourceSymbol)
        assertEquals("exclamationmark.circle.fill", NoiseFloorQuality.FAIR.sourceSymbol)
        assertEquals("xmark.circle.fill", NoiseFloorQuality.POOR.sourceSymbol)
        assertEquals("questionmark.circle", NoiseFloorQuality.UNKNOWN.sourceSymbol)
    }

    // MARK: - NoiseFloorStateHolder

    @Test @OriginalCase("NoiseFloorViewModelTests::initial state is empty()")
    fun `initial state is empty`() {
        assertNull(state.currentReading)
        assertTrue(state.readings.isEmpty())
        assertFalse(state.isPolling)
        assertNull(state.errorMessage)
    }

    @Test @OriginalCase("NoiseFloorViewModelTests::statistics returns nil when no readings()")
    fun `statistics returns nil when no readings`() = assertNull(state.statistics)

    @Test @OriginalCase("NoiseFloorViewModelTests::statistics calculates correctly with readings()")
    fun `statistics calculates correctly with readings`() {
        holder.appendReading(reading(-100))
        holder.appendReading(reading(-90))
        holder.appendReading(reading(-95))
        assertEquals(NoiseFloorStatistics(-100, -90, -95.0), state.statistics)
    }

    @Test @OriginalCase("NoiseFloorViewModelTests::qualityLevel returns unknown when no reading()")
    fun `qualityLevel returns unknown when no reading`() = assertEquals(NoiseFloorQuality.UNKNOWN, state.qualityLevel)

    @Test @OriginalCase("NoiseFloorViewModelTests::qualityLevel returns correct quality for current reading()")
    fun `qualityLevel returns correct quality for current reading`() {
        holder.appendReading(reading(-105))
        assertEquals(NoiseFloorQuality.EXCELLENT, state.qualityLevel)
    }

    @Test @OriginalCase("NoiseFloorViewModelTests::appendReading adds to readings and updates current()")
    fun `appendReading adds to readings and updates current`() {
        holder.appendReading(reading(-95))
        assertEquals(1, state.readings.size)
        assertEquals(-95, state.currentReading?.noiseFloor?.toInt())
    }

    @Test @OriginalCase("NoiseFloorViewModelTests::appendReading respects maxReadings limit()")
    fun `appendReading respects maxReadings limit`() {
        repeat(250) { holder.appendReading(reading(-100 + it)) }
        assertEquals(200, state.readings.size)
        assertEquals(-50, state.readings.first().noiseFloor.toInt())
    }

    @Test @OriginalCase("NoiseFloorViewModelTests::appendReading invalidates cached statistics()")
    fun `appendReading invalidates cached statistics`() {
        holder.appendReading(reading(-100))
        assertEquals(-100, state.statistics?.min?.toInt())
        assertEquals(-100, state.statistics?.max?.toInt())
        holder.appendReading(reading(-90))
        assertEquals(-100, state.statistics?.min?.toInt())
        assertEquals(-90, state.statistics?.max?.toInt())
    }

    @Test @OriginalCase("NoiseFloorViewModelTests::stopPolling cancels task and sets isPolling false()", "platform-adaptation")
    fun `stopPolling cancels task and sets isPolling false`() {
        holder.startPolling { null }
        scheduler.runCurrent()
        assertTrue(state.isPolling)
        assertEquals(1, scheduler.clock.pendingSleepers)
        holder.stopPolling()
        scheduler.runCurrent()
        assertFalse(state.isPolling)
        assertEquals(0, scheduler.clock.pendingSleepers)
    }
}
