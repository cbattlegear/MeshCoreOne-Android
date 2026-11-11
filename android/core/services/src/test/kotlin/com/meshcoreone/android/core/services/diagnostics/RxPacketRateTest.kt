// PortedFrom: MC1Tests/State/LiveActivityManagerTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: discriminating rounding, real event ingestion, reset and observer cancellation.
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.PayloadType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RxPacketRateTest {
    private val now = Instant.parse("2001-01-12T13:46:40Z")
    private fun calculate(vararg offsets: Long, windowSeconds: Long = 60) =
        RxPacketRate.calculate(offsets.map(now::minusSeconds), now, Duration.ofSeconds(windowSeconds))

    @Test fun noTimestampsReturnsZero() { assertEquals(0L, calculate()) }
    @Test fun sevenPacketsInsideDefaultWindow() { assertEquals(7L, calculate(5, 10, 15, 20, 25, 30, 35)) }
    @Test fun oldPacketsExcluded() { assertEquals(2L, calculate(10, 59, 61, 120)) }
    @Test fun exactCutoffIncluded() { assertEquals(1L, calculate(60)) }
    @Test fun customWindowNormalizesToMinute() { assertEquals(12L, calculate(1, 2, 3, windowSeconds = 15)) }

    @Test fun fractionalValuesRoundRatherThanTruncateOrRoundAllFractionsUp() {
        assertEquals(2L, calculate(1, windowSeconds = 40))
        assertEquals(1L, calculate(1, windowSeconds = 50))
        assertEquals(0L, RxPacketRate.calculate(listOf(now.minusSeconds(60).minusNanos(1)), now))
        assertEquals(1L, calculate(-1)) // Source applies only the inclusive lower cutoff.
        assertFailsWith<IllegalArgumentException> { calculate(windowSeconds = 0) }
        assertFailsWith<IllegalArgumentException> { calculate(windowSeconds = -1) }
    }

    @Test fun eventIngestionCountsWithoutSubscribersAndIgnoresHistoryReopeningAndClearing() = runTest {
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val harness = RxLogSvcHarness(clock = clock, scope = backgroundScope)
        val service = harness.service
        val radio = rxLogSvcRadio()
        val event = MeshEvent.RxLogData(rxLogSvcParsed(PayloadType.RAW_CUSTOM, Bytes.of(1, 2)))
        service.startEventMonitoring(radio)
        runCurrent()
        repeat(1025) { harness.session.emit(event) }
        runCurrent()
        assertEquals(1025L, service.packetRates().first())
        assertEquals(500, service.loadExistingEntries().size)
        repeat(2) { service.loadExistingEntries() }
        service.clearEntries()
        assertEquals(1025L, service.packetRates().first())
        service.stopEventMonitoring()
        assertEquals(0L, service.packetRates().first())
        service.startEventMonitoring(radio)
        runCurrent()
        assertEquals(0L, service.packetRates().first())
        harness.session.emit(event)
        runCurrent()
        assertEquals(1L, service.packetRates().first())
        service.stopEventMonitoring()
    }

    @Test fun decayIncludesBoundaryAndCancelledObservationDoesNotPollOrStopIngestion() = runTest {
        var clockReads = 0
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant {
                clockReads++
                return now.plusMillis(testScheduler.currentTime)
            }
        }
        val rate = RxPacketRate(clock)
        val observed = mutableListOf<Long>()
        val observer = backgroundScope.launch { rate.rates().collect { observed += it } }
        runCurrent()
        rate.record()
        runCurrent()
        assertEquals(listOf(0L, 1L), observed)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1L, observed.last())
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(0L, observed.last())
        observer.cancelAndJoin()
        val readsAtCancellation = clockReads
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(readsAtCancellation, clockReads)
        rate.record()
        assertEquals(1L, rate.rates().first())
        rate.reset()
        assertEquals(0L, rate.rates().first())
    }

    @Test fun saveFailureStillCountsRfButCancelledOrStoppedWorkCannotReviveRate() = runTest {
        val harness = RxLogSvcHarness(clock = Clock.fixed(now, ZoneOffset.UTC), scope = backgroundScope)
        val service = harness.service
        val parsed = rxLogSvcParsed(PayloadType.RAW_CUSTOM, Bytes.of(3))
        service.startEventMonitoring(rxLogSvcRadio())
        runCurrent()
        harness.store.saveFailure = IllegalStateException("disk unavailable")
        harness.session.emit(MeshEvent.RxLogData(parsed))
        runCurrent()
        assertEquals(1L, service.packetRates().first())
        val cancelled = launch {
            currentCoroutineContext().cancel()
            assertFailsWith<CancellationException> { service.process(parsed) }
        }
        cancelled.join()
        assertEquals(1L, service.packetRates().first())
        service.stopEventMonitoring()
        service.process(parsed)
        assertEquals(0L, service.packetRates().first())
    }
}
