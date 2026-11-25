// PortedFrom: MC1Services/Tests/MC1ServicesTests/NodeSnapshotServiceTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.model.NeighborSnapshotEntry
import com.meshcoreone.android.core.model.NodeLocationFix
import com.meshcoreone.android.core.model.NodeStatusMetrics
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.TelemetrySnapshotEntry
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.StatusResponse
import java.time.Duration
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.TestFactory

class NodeSnapshotServiceTest {
    private val testPublicKey = nodeConfigBytes(0x42, 32)
    private val testFix = NodeLocationFix(latitude = 37.7749, longitude = -122.4194)

    /** The Swift fixture's in-memory PersistenceStore, here an in-memory store sharing the service clock. */
    private class Fixture {
        val clock = NodeConfigTestClock()
        val store = NodeConfigSnapshotStore(clock)
        val service = NodeSnapshotService(store, clock, NodeConfigLogger.NONE)
        fun ago(seconds: Long) = clock.peek().minusSeconds(seconds)
    }

    private fun metrics(battery: Int, uptime: UInt?) = NodeStatusMetrics(
        batteryMillivolts = battery.toUShort(), lastSNR = 8.5, lastRSSI = -87, noiseFloor = -120, uptimeSeconds = uptime,
        rxAirtimeSeconds = 100u, packetsSent = 500u, packetsReceived = 1000u,
    )

    /** A neighbor prefix at the realistic on-wire length (RepeaterAdminService.defaultPubkeyPrefixLength = 6). */
    private fun neighborPrefix(seed: Int): Bytes = Bytes(ByteArray(6) { (seed + it).toByte() })

    private fun neighbors(vararg entries: NeighborSnapshotEntry) = SnapshotList.of(*entries)
    private fun neighbor(prefix: Bytes, snr: Double, secondsAgo: Long) = NeighborSnapshotEntry(prefix, snr, secondsAgo)
    private val prefix1234 = Bytes.of(0x01, 0x02, 0x03, 0x04)

    @TestFactory
    fun sourceCases() = nodeConfigSourceCases(
        "NodeSnapshotServiceTests",
        "Record returns an ID on first capture" to {
            val f = Fixture()
            assertNotNull(f.service.recordSnapshot(testPublicKey, status = metrics(3850, 3600u)))
        },
        "Second status within the window enriches the same row, not a new one" to {
            val f = Fixture()
            val first = assertNotNull(f.service.recordSnapshot(testPublicKey, status = metrics(3850, 3600u)))
            val second = f.service.recordSnapshot(testPublicKey, status = metrics(3900, 7200u))
            assertEquals(first, second, "An in-window status capture returns the existing row's ID")
            val snapshots = f.service.fetchSnapshots(testPublicKey)
            assertEquals(1, snapshots.size, "No second snapshot is created within the window")
            assertEquals(3850.toUShort(), snapshots[0].batteryMillivolts, "A row already carrying status is not overwritten")
            assertEquals(3600u, snapshots[0].uptimeSeconds)
        },
        "Different nodes get independent snapshots" to {
            val f = Fixture()
            val first = f.service.recordSnapshot(testPublicKey, status = metrics(3850, 3600u))
            val second = f.service.recordSnapshot(nodeConfigBytes(0x99, 32), status = metrics(3700, 1800u))
            assertNotNull(first); assertNotNull(second)
            assertNotEquals(first, second, "Different nodes are not throttled against each other")
        },
        "Neighbors enrich the in-window snapshot" to {
            val f = Fixture()
            val statusId = f.service.recordSnapshot(testPublicKey, status = metrics(3850, 3600u))
            val neighborId = f.service.recordSnapshot(testPublicKey, neighbors = neighbors(neighbor(prefix1234, 5.5, 30)))
            assertEquals(statusId, neighborId, "Neighbors land on the existing in-window row")
            val latest = f.store.fetchLatestNodeStatusSnapshot(testPublicKey)
            assertEquals(1, latest?.neighborSnapshots?.size)
            assertEquals(5.5, latest?.neighborSnapshots?.first()?.snr)
        },
        "Telemetry enriches the in-window snapshot" to {
            val f = Fixture()
            val statusId = f.service.recordSnapshot(testPublicKey, status = metrics(3850, 3600u))
            val telemetryId = f.service.recordSnapshot(testPublicKey, telemetry = SnapshotList.of(TelemetrySnapshotEntry(0, "temperature", 32.5)))
            assertEquals(statusId, telemetryId, "Telemetry lands on the existing in-window row")
            val latest = f.store.fetchLatestNodeStatusSnapshot(testPublicKey)
            assertEquals(1, latest?.telemetryEntries?.size)
            assertEquals(32.5, latest?.telemetryEntries?.first()?.value)
        },
        "A location fix persists on capture" to {
            val f = Fixture()
            f.service.recordSnapshot(testPublicKey, status = metrics(3850, 3600u), location = testFix)
            val latest = f.store.fetchLatestNodeStatusSnapshot(testPublicKey)
            assertEquals(37.7749, latest?.latitude); assertEquals(-122.4194, latest?.longitude)
        },
        "A capture without a fix leaves location nil" to {
            val f = Fixture()
            f.service.recordSnapshot(testPublicKey, status = metrics(3850, 3600u))
            val latest = f.store.fetchLatestNodeStatusSnapshot(testPublicKey)
            assertNotNull(latest); assertNull(latest.latitude); assertNull(latest.longitude)
        },
        "A GPS-only capture persists the fix on a fresh row" to {
            val f = Fixture()
            assertNotNull(f.service.recordSnapshot(testPublicKey, location = testFix))
            val latest = assertNotNull(f.store.fetchLatestNodeStatusSnapshot(testPublicKey))
            assertEquals(37.7749, latest.latitude)
            assertNull(latest.uptimeSeconds, "A GPS-only row carries no status")
        },
        "An in-window fix is first-wins" to {
            val f = Fixture()
            f.service.recordSnapshot(testPublicKey, location = testFix)
            f.service.recordSnapshot(testPublicKey, location = NodeLocationFix(40.0, -100.0))
            val snapshots = f.service.fetchSnapshots(testPublicKey)
            assertEquals(1, snapshots.size)
            assertEquals(37.7749, snapshots[0].latitude, "The first-captured fix is not overwritten")
        },
        "An in-window no-fix response does not erase an existing fix" to {
            val f = Fixture()
            f.service.recordSnapshot(testPublicKey, location = testFix)
            f.service.recordSnapshot(testPublicKey, status = metrics(3850, 3600u))
            assertEquals(37.7749, f.store.fetchLatestNodeStatusSnapshot(testPublicKey)?.latitude, "A later no-fix response preserves the good fix")
        },
        "An in-window fix enriches a row that had none" to {
            val f = Fixture()
            f.service.recordSnapshot(testPublicKey, status = metrics(3850, 3600u))
            f.service.recordSnapshot(testPublicKey, location = testFix)
            assertEquals(1, f.service.fetchSnapshots(testPublicKey).size, "The fix lands on the existing in-window row, not a new one")
            val latest = f.store.fetchLatestNodeStatusSnapshot(testPublicKey)
            assertEquals(37.7749, latest?.latitude, "A fix enriches the previously fix-less row")
            assertEquals(-122.4194, latest?.longitude)
        },
        "Fetch snapshots returns ascending order" to {
            val f = Fixture()
            f.store.insert(testPublicKey, timestamp = f.ago(10), batteryMillivolts = 3800u)
            f.store.insert(testPublicKey, timestamp = f.ago(20), batteryMillivolts = 3600u)
            val snapshots = f.service.fetchSnapshots(testPublicKey)
            assertEquals(listOf<UShort?>(3600u, 3800u), snapshots.map { it.batteryMillivolts })
        },
        "Prune only deletes snapshots older than cutoff" to {
            val f = Fixture()
            f.store.insert(testPublicKey, timestamp = f.ago(60), batteryMillivolts = 3600u)
            val recentId = f.store.insert(testPublicKey, timestamp = f.ago(10), batteryMillivolts = 3800u)
            f.service.pruneOldSnapshots(f.ago(30))
            val remaining = f.service.fetchSnapshots(testPublicKey)
            assertEquals(1, remaining.size, "Old snapshot should be pruned, recent should remain")
            assertEquals(recentId, remaining.first().id)
        },
        "Prune with future cutoff does not delete recent snapshots" to {
            val f = Fixture()
            f.store.insert(testPublicKey, batteryMillivolts = 3850u)
            f.service.pruneOldSnapshots(f.clock.peek().minus(Duration.ofDays(365)))
            assertEquals(1, f.service.fetchSnapshots(testPublicKey).size, "Recent snapshot should not be pruned")
        },
        "Fetch snapshots with since filter" to {
            val f = Fixture()
            f.store.insert(testPublicKey, timestamp = f.ago(30), batteryMillivolts = 3600u)
            f.store.insert(testPublicKey, timestamp = f.ago(5), batteryMillivolts = 3800u)
            val snapshots = f.service.fetchSnapshots(testPublicKey, since = f.ago(15))
            assertEquals(listOf<UShort?>(3800u), snapshots.map { it.batteryMillivolts })
        },
        "Enrichment data survives save -> enrich -> fetchAll round-trip" to {
            val f = Fixture()
            val id1 = f.store.insert(testPublicKey, timestamp = f.ago(20), batteryMillivolts = 3600u, lastSNR = 7.0, lastRSSI = -90, noiseFloor = -120)
            val id2 = f.store.insert(testPublicKey, timestamp = f.ago(10), batteryMillivolts = 3800u, lastSNR = 8.5, lastRSSI = -85, noiseFloor = -118)
            f.store.updateSnapshotTelemetry(id1, SnapshotList.of(TelemetrySnapshotEntry(0, "temperature", 25.0)))
            f.store.updateSnapshotTelemetry(id2, SnapshotList.of(TelemetrySnapshotEntry(0, "temperature", 30.0)))
            f.store.updateSnapshotNeighbors(id1, neighbors(neighbor(prefix1234, 5.0, 60)))
            f.store.updateSnapshotNeighbors(id2, neighbors(neighbor(Bytes.of(0x05, 0x06, 0x07, 0x08), 9.0, 10)))
            val snapshots = f.service.fetchSnapshots(testPublicKey)
            assertEquals(2, snapshots.size)
            assertEquals(25.0, snapshots[0].telemetryEntries?.single()?.value, "Snapshot 1 telemetry should persist")
            assertEquals(5.0, snapshots[0].neighborSnapshots?.single()?.snr, "Snapshot 1 neighbors should persist")
            assertEquals(30.0, snapshots[1].telemetryEntries?.single()?.value, "Snapshot 2 telemetry should persist")
            assertEquals(9.0, snapshots[1].neighborSnapshots?.single()?.snr, "Snapshot 2 neighbors should persist")
        },
        "Status + telemetry + neighbors in one window collapse onto a single row" to {
            val f = Fixture()
            val snapshotId = assertNotNull(f.service.recordSnapshot(testPublicKey, status = metrics(3700, 3600u)), "First capture should return an ID")
            val enrichedId = f.service.recordSnapshot(
                testPublicKey,
                telemetry = SnapshotList.of(TelemetrySnapshotEntry(0, "temperature", 28.5), TelemetrySnapshotEntry(1, "humidity", 65.0)),
                neighbors = neighbors(neighbor(Bytes.of(0xAA, 0xBB, 0xCC, 0xDD), 6.5, 45)),
            )
            assertEquals(snapshotId, enrichedId)
            val snapshots = f.service.fetchSnapshots(testPublicKey)
            assertEquals(1, snapshots.size)
            assertEquals(2, snapshots[0].telemetryEntries?.size, "Both telemetry entries should persist")
            assertEquals(1, snapshots[0].neighborSnapshots?.size, "Neighbor entry should persist")
        },
        "Telemetry-first then status-within-window backfills status onto the single snapshot" to {
            val f = Fixture()
            val telemetryId = assertNotNull(
                f.service.recordSnapshot(testPublicKey, telemetry = SnapshotList.of(TelemetrySnapshotEntry(1, "temperature", 21.5))),
                "Telemetry-only capture should create a snapshot",
            )
            val status = NodeStatusMetrics(
                batteryMillivolts = 3900u, lastSNR = 9.0, lastRSSI = -84, noiseFloor = -119, uptimeSeconds = 7200u,
                rxAirtimeSeconds = 150u, packetsSent = 600u, packetsReceived = 1200u, receiveErrors = 3u,
            )
            assertEquals(telemetryId, f.service.recordSnapshot(testPublicKey, status = status), "Status enriches the telemetry-only row, no new snapshot")
            val snapshots = f.service.fetchSnapshots(testPublicKey)
            assertEquals(1, snapshots.size, "Should remain a single snapshot carrying both data sets")
            assertEquals(21.5, snapshots[0].telemetryEntries?.first()?.value, "Telemetry should be preserved")
            assertEquals(7200u, snapshots[0].uptimeSeconds, "Status counters should be backfilled")
            assertEquals(3900.toUShort(), snapshots[0].batteryMillivolts)
            assertEquals(3u, snapshots[0].receiveErrors)
        },
        "Neighbors captured before any status persist on a fresh snapshot" to {
            val f = Fixture()
            assertNotNull(f.service.recordSnapshot(testPublicKey, neighbors = neighbors(neighbor(Bytes.of(0x0A, 0x0B, 0x0C, 0x0D), 4.0, 90))))
            val snapshots = f.service.fetchSnapshots(testPublicKey)
            assertEquals(1, snapshots.size)
            assertEquals(1, snapshots[0].neighborSnapshots?.size, "Neighbor data should persist on a fresh row")
            assertNull(snapshots[0].uptimeSeconds, "A neighbor-only row carries no status fields yet")
        },
        "Previous neighbor snapshot skips a more recent status-only row" to {
            val f = Fixture()
            val neighborId = f.store.insert(testPublicKey, timestamp = f.ago(3600), batteryMillivolts = 3700u, lastSNR = 6.0, uptimeSeconds = 3600u)
            f.store.updateSnapshotNeighbors(neighborId, neighbors(neighbor(prefix1234, 6.0, 30)))
            f.store.insert(testPublicKey, timestamp = f.ago(1800), batteryMillivolts = 3850u, lastSNR = 8.0, uptimeSeconds = 7200u)
            val previous = f.service.neighborBaseline(testPublicKey).previous
            assertEquals(6.0, previous?.neighborSnapshots?.first()?.snr, "Returns the neighbor-bearing row, not the newer status-only row")
        },
        "Previous neighbor snapshot excludes the current in-window capture" to {
            val f = Fixture()
            val priorId = f.store.insert(testPublicKey, timestamp = f.ago(3600), batteryMillivolts = 3700u, uptimeSeconds = 3600u)
            f.store.updateSnapshotNeighbors(priorId, neighbors(neighbor(prefix1234, 6.0, 30)))
            f.service.recordSnapshot(testPublicKey, neighbors = neighbors(neighbor(prefix1234, 9.0, 5)))
            val previous = f.service.neighborBaseline(testPublicKey).previous
            assertEquals(6.0, previous?.neighborSnapshots?.first()?.snr, "The current in-window row is excluded; baseline is the prior neighbor capture")
        },
        "Previous neighbor snapshot is nil when no prior neighbor data exists" to {
            val f = Fixture()
            f.service.recordSnapshot(testPublicKey, status = metrics(3850, 3600u))
            assertNull(f.service.neighborBaseline(testPublicKey).previous, "Status-only history yields no neighbor baseline")
        },
        "Previous neighbor snapshot returns an out-of-window neighbor row as the baseline" to {
            val f = Fixture()
            val priorId = f.store.insert(testPublicKey, timestamp = f.ago(3600), batteryMillivolts = 3700u, uptimeSeconds = 3600u)
            f.store.updateSnapshotNeighbors(priorId, neighbors(neighbor(prefix1234, 6.0, 30)))
            assertEquals(6.0, f.service.neighborBaseline(testPublicKey).previous?.neighborSnapshots?.first()?.snr)
        },
        "Previous neighbor snapshot is nil when the only neighbor row is the in-window capture" to {
            val f = Fixture()
            f.service.recordSnapshot(testPublicKey, neighbors = neighbors(neighbor(prefix1234, 9.0, 5)))
            assertNull(f.service.neighborBaseline(testPublicKey).previous, "The in-window capture is excluded and there is no prior neighbor row")
        },
        "Seen neighbor prefixes span all history, not just the previous snapshot" to {
            val f = Fixture()
            val prefixA = neighborPrefix(0xA0); val prefixB = neighborPrefix(0xB0)
            val olderId = f.store.insert(testPublicKey, timestamp = f.ago(7200), batteryMillivolts = 3700u, uptimeSeconds = 3600u)
            f.store.updateSnapshotNeighbors(olderId, neighbors(neighbor(prefixA, 6.0, 30)))
            val recentId = f.store.insert(testPublicKey, timestamp = f.ago(3600), batteryMillivolts = 3800u, uptimeSeconds = 5400u)
            f.store.updateSnapshotNeighbors(recentId, neighbors(neighbor(prefixB, 7.0, 30)))

            val baseline = f.service.neighborBaseline(testPublicKey)
            assertEquals(prefixB, baseline.previous?.neighborSnapshots?.first()?.publicKeyPrefix, "The previous baseline is the most recent neighbor-bearing row")
            assertEquals(false, baseline.previous?.neighborSnapshots?.any { it.publicKeyPrefix == prefixA }, "A is absent from the immediately-previous snapshot")
            assertTrue(prefixA in baseline.seenPrefixes, "A remains seen via older history")
            assertTrue(prefixB in baseline.seenPrefixes, "B is seen in the previous snapshot")

            val prefixC = neighborPrefix(0xC0)
            f.service.recordSnapshot(testPublicKey, neighbors = neighbors(neighbor(prefixC, 9.0, 5)))
            val afterCapture = f.service.neighborBaseline(testPublicKey)
            assertTrue(prefixA in afterCapture.seenPrefixes, "Historical prefixes survive a new capture")
            assertFalse(prefixC in afterCapture.seenPrefixes, "The in-window capture is excluded from the seen set")
        },
        "Previous status snapshot skips a more recent neighbor-only row" to {
            val f = Fixture()
            f.store.insert(testPublicKey, timestamp = f.ago(3600), batteryMillivolts = 3700u, uptimeSeconds = 3600u)
            f.service.recordSnapshot(testPublicKey, neighbors = neighbors(neighbor(prefix1234, 9.0, 5)))
            val previous = f.service.previousStatusSnapshot(testPublicKey, f.clock.instant())
            assertEquals(3600u, previous?.uptimeSeconds, "Returns the status-bearing row, not the newer neighbor-only row")
        },
        "Previous status snapshot includes an in-window status capture" to {
            val f = Fixture()
            f.service.recordSnapshot(testPublicKey, status = metrics(3850, 3600u))
            val previous = f.service.previousStatusSnapshot(testPublicKey, f.clock.instant())
            assertEquals(3600u, previous?.uptimeSeconds, "The in-window status row is a usable baseline")
        },
        "Previous status snapshot is nil when only neighbor data exists" to {
            val f = Fixture()
            f.service.recordSnapshot(testPublicKey, neighbors = neighbors(neighbor(prefix1234, 9.0, 5)))
            assertNull(f.service.previousStatusSnapshot(testPublicKey, f.clock.instant()), "Neighbor-only history yields no status baseline")
        },
        "Concurrent in-window captures never duplicate a snapshot" to {
            val f = Fixture()
            val (statusId, telemetryId) = coroutineScope {
                val status = async(Dispatchers.Default) { f.service.recordSnapshot(testPublicKey, status = metrics(3850, 3600u)) }
                val telemetry = async(Dispatchers.Default) {
                    f.service.recordSnapshot(testPublicKey, telemetry = SnapshotList.of(TelemetrySnapshotEntry(0, "temperature", 19.0)))
                }
                status.await() to telemetry.await()
            }
            assertNotNull(statusId); assertNotNull(telemetryId)
            assertEquals(statusId, telemetryId, "Concurrent captures resolve to the same in-window row")
            assertEquals(1, f.service.fetchSnapshots(testPublicKey).size, "Atomic record collapses concurrent captures into one row")
        },
        "NodeStatusMetrics(status:) maps the six per-type packet counters" to {
            val status = StatusResponse(
                publicKeyPrefix = nodeConfigBytes(0x42, 6), battery = 3800, txQueueLength = 1, noiseFloor = -110, lastRSSI = -85,
                packetsReceived = 2000u, packetsSent = 1000u, airtime = 500u, uptime = 3600u, sentFlood = 200u, sentDirect = 100u,
                receivedFlood = 400u, receivedDirect = 300u, fullEvents = 7, lastSNR = 9.0, directDuplicates = 11,
                floodDuplicates = 22, rxAirtime = 100u, layout = StatusResponse.Layout.REPEATER, receiveErrors = 3u,
            )
            val metrics = NodeStatusMetrics.fromStatus(status)
            assertEquals(100u, metrics.sentDirect); assertEquals(200u, metrics.sentFlood)
            assertEquals(300u, metrics.receivedDirect); assertEquals(400u, metrics.receivedFlood)
            // Duplicate counters are Int on the wire; the metrics conversion clamps to UInt32.
            assertEquals(11u, metrics.directDuplicates); assertEquals(22u, metrics.floodDuplicates)
        },
    )

    @TestFactory
    fun nativeCases() = nodeConfigNativeCases(
        "store failures and store-internal timeouts map to fallbacks; the caller's own cancellation propagates" to {
            val cancelCaller = java.util.concurrent.atomic.AtomicBoolean(false)
            val failing = object : com.meshcoreone.android.core.contracts.domain.NodeSnapshotPersisting by NodeConfigSnapshotStore(NodeConfigTestClock()) {
                override suspend fun recordNodeStatusSnapshot(
                    nodePublicKey: Bytes, status: NodeStatusMetrics?, telemetry: SnapshotList<TelemetrySnapshotEntry>?,
                    neighbors: SnapshotList<NeighborSnapshotEntry>?, location: NodeLocationFix?,
                ) = throw IllegalStateException("disk full")
                override suspend fun fetchNodeStatusSnapshots(nodePublicKey: Bytes, since: java.time.Instant?): SnapshotList<NodeStatusSnapshotDTO> {
                    if (cancelCaller.get()) kotlinx.coroutines.currentCoroutineContext().cancel()
                    throw kotlin.coroutines.cancellation.CancellationException("store-internal timeout")
                }
                override suspend fun deleteOldNodeStatusSnapshots(olderThan: java.time.Instant) = throw IllegalStateException("locked")
            }
            val service = NodeSnapshotService(failing, NodeConfigTestClock(), NodeConfigLogger.NONE)
            assertNull(service.recordSnapshot(testPublicKey, status = metrics(3850, 3600u)))
            service.pruneOldSnapshots(java.time.Instant.EPOCH)
            // Swift logs every store error and falls back; a timeout raised inside the store is one.
            assertTrue(service.fetchSnapshots(testPublicKey).isEmpty())
            cancelCaller.set(true)
            val cancelled = kotlinx.coroutines.coroutineScope { async { service.fetchSnapshots(testPublicKey) } }
            assertFailsWith<kotlin.coroutines.cancellation.CancellationException> { cancelled.await() }
        },
    )
}
