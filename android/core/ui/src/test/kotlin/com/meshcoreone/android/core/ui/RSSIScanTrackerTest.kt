// PortedFrom: MC1Tests/Views/Components/RSSIScanTrackerTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.ui

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RSSIScanTrackerTest : SourceCaseProof() {
    private val id = UUID.fromString("12345678-1234-1234-1234-123456789ABC")
    private val now = Instant.ofEpochSecond(1_700_000_000)
    private fun tracker() = RSSIScanTracker<UUID>(Clock.fixed(now, ZoneOffset.UTC))

    @OriginalCase("RSSIScanTrackerTests::consume ingests a usable discovery and exposes its name and tier()")
    @Test fun ingests() = runTest {
        val tracker = tracker()
        tracker.consume(flowOf(ScanDiscovery(id, "Radio", -50)))
        prove { assertTrue(tracker.isAdvertising(id)); assertEquals("Radio", tracker.devices.value[id]?.discovery?.name)
            assertEquals(RSSITuning.SignalTier.STRONG, tracker.signalTier(id)) }
    }
    @OriginalCase("RSSIScanTrackerTests::a later packet without a name keeps the previously advertised name and smooths RSSI()")
    @Test fun remembersName() = runTest {
        val tracker = tracker()
        tracker.consume(flowOf(ScanDiscovery(id, "Radio", -50), ScanDiscovery(id, null, -55)))
        prove { assertEquals("Radio", tracker.devices.value[id]?.discovery?.name)
            assertEquals(RSSITuning.smooth(-55, -50), tracker.devices.value[id]?.discovery?.rssi)
            assertEquals(-51L, tracker.devices.value[id]?.discovery?.rssi) }
    }
    @OriginalCase("RSSIScanTrackerTests::unknown ids report no tier and are not advertising()")
    @Test fun unknown() = prove {
        val tracker = tracker()
        assertNull(tracker.signalTier(id)); assertFalse(tracker.isAdvertising(id))
    }
    @OriginalCase("RSSIScanTrackerTests::an unusable reading is skipped and creates no entry(rssi : Int)", 3)
    @Test fun unavailable() = runTest {
        val trackers = listOf(-127L, 0L, 10L).map { rssi ->
            tracker().also { it.consume(flowOf(ScanDiscovery(id, "Radio", rssi))) }
        }
        prove(3) { for (tracker in trackers) {
            assertFalse(tracker.isAdvertising(id)); assertNull(tracker.signalTier(id))
        } }
    }
    @OriginalCase("RSSIScanTrackerTests::expireStale drops a peripheral last seen before the stale window()")
    @Test fun stale() = runTest {
        val tracker = tracker()
        tracker.consume(flowOf(ScanDiscovery(id, "Radio", -50)))
        tracker.expireStale(now.plusSeconds(5))
        prove { assertFalse(tracker.isAdvertising(id)); assertNull(tracker.signalTier(id)); assertNull(tracker.devices.value[id]) }
    }
    @OriginalCase("RSSIScanTrackerTests::expireStale keeps a freshly seen peripheral()")
    @Test fun fresh() = runTest {
        val tracker = tracker()
        tracker.consume(flowOf(ScanDiscovery(id, "Radio", -50)))
        tracker.expireStale(now)
        prove { assertTrue(tracker.isAdvertising(id)) }
    }

    @Test fun exactStaleBoundaryIsKeptAndCancellationClosesOnlyTheCallerScan() = runTest {
        val tracker = tracker()
        tracker.ingest(ScanDiscovery(id, "Radio", -50))
        tracker.expireStale(now.plusSeconds(4))
        assertTrue(tracker.isAdvertising(id))
        var closed = false
        val consume = async {
            tracker.consume(flow {
                try { emit(ScanDiscovery(id, "Radio", -50)); awaitCancellation() }
                finally { closed = true }
            })
        }
        runCurrent()
        consume.cancelAndJoin()
        assertTrue(closed)
        assertTrue(tracker.isAdvertising(id))
        val snapshot = tracker.devices.value
        tracker.ingest(ScanDiscovery(id, "New name", -70))
        assertEquals("Radio", snapshot[id]?.discovery?.name)
    }
}
