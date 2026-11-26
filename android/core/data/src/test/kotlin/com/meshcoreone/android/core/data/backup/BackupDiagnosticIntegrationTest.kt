// PortedFrom: MC1Services/Tests/MC1ServicesTests/BackupIntegrationTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupDiagnosticIntegrationTest : BackupRoomTest() {
    @OriginalCase("BackupIntegrationTests::Import onto existing saved trace path merges runs without duplicating them()")
    @Test fun pathRunsMergeByStablePathIdentityAndGlobalRunUuid() = runBlocking {
        val existingRun = run(id(10), AT.minusSeconds(120)).copy(roundTripMs = 180)
        val importedRun = run(id(100), AT).copy(roundTripMs = 95)
        val existingPath = path().copy(name = "Shared Route", runs = SnapshotList.of(existingRun))
        service.importBackup(envelope(paths = listOf(existingPath)), store)
        val value = envelope(paths = listOf(path(id = id(90)).copy(name = "Shared Route", runs = SnapshotList.of(importedRun))))
        val first = service.importBackup(value, store)
        assertEquals(0L, first.count(BackupModelKind.SAVED_TRACE_PATHS).inserted)
        assertEquals(1L, first.count(BackupModelKind.SAVED_TRACE_PATHS).skipped)
        assertEquals(1L, first.count(BackupModelKind.SAVED_TRACE_PATHS).merged); assertTrue(first.hasRestoredChanges)
        assertEquals(setOf(existingRun.id, importedRun.id), db.traceRuns().forPath(RADIO.value, existingPath.id).map { it.id }.toSet())
        val second = service.importBackup(value, store)
        assertEquals(0L, second.count(BackupModelKind.SAVED_TRACE_PATHS).inserted)
        assertEquals(1L, second.count(BackupModelKind.SAVED_TRACE_PATHS).skipped)
        assertEquals(0L, second.count(BackupModelKind.SAVED_TRACE_PATHS).merged); assertFalse(second.hasRestoredChanges)
        assertEquals(2, db.traceRuns().forPath(RADIO.value, existingPath.id).size)
    }

    @OriginalCase("BackupIntegrationTests::Import reusing a local run's id under a different path drops the duplicate run rather than relocating it()")
    @Test fun globalTraceRunUuidCannotRelocateExistingHistory() = runBlocking {
        val pathA = path().copy(name = "Path A")
        service.importBackup(envelope(paths = listOf(pathA)), store)
        val pathB = path(pathBytes = Bytes.of(0xAB, 0xCD, 0xEF, 1), id = id(90)).copy(name = "Path B", runs = SnapshotList.of(run().copy(roundTripMs = 999)))
        val result = service.importBackup(envelope(paths = listOf(pathB)), store)
        assertEquals(1L, result.count(BackupModelKind.SAVED_TRACE_PATHS).inserted)
        assertEquals(2, db.tracePaths().forRadio(RADIO.value).size)
        assertEquals(id(10), db.traceRuns().forPath(RADIO.value, pathA.id).single().id)
        assertTrue(db.traceRuns().forPath(RADIO.value, pathB.id).isEmpty())
        assertEquals(1, db.traceRuns().backupAll().size)
    }

    @OriginalCase("BackupIntegrationTests::Import preserves distinct node snapshots recorded within the same second()")
    @Test fun snapshotKeysCoalesceOnlyWithinSourceMillisecond() = runBlocking {
        val first = snapshot(date = fraction("1700000400.100"))
        val second = snapshot(id(120), fraction("1700000400.900")).copy(batteryMillivolts = null,
            telemetryEntries = SnapshotList.of(TelemetrySnapshotEntry(1, "temperature", 21.5)))
        db.nodeSnapshots().insert(first.toEntity())
        val result = service.importBackup(envelope(snapshots = listOf(first.copy(id = id(121)), second)), store)
        assertEquals(1L, result.count(BackupModelKind.NODE_STATUS_SNAPSHOTS).inserted)
        assertEquals(1L, result.count(BackupModelKind.NODE_STATUS_SNAPSHOTS).skipped)
        val actual = db.nodeSnapshots().history(first.nodePublicKey).map { it.toDTO() }
        assertEquals(listOf(first.timestamp, second.timestamp), actual.map { it.timestamp })
        assertEquals(1, actual.last().telemetryEntries?.size)
        val nearby = second.copy(id = id(122), timestamp = fraction("1700000400.9009999"))
        assertEquals(1L, service.importBackup(envelope(snapshots = listOf(nearby)), store).count(BackupModelKind.NODE_STATUS_SNAPSHOTS).skipped)
        assertEquals(2, db.nodeSnapshots().history(first.nodePublicKey).size)
    }

    @OriginalCase("BackupIntegrationTests::Discovered nodes import into fresh store, dedup on re-import()")
    @Test fun discoveredBusinessKeyDedupNeverRefreshesLocalPayload() = runBlocking {
        val dto = discovered().copy(publicKey = key(0x11), name = "Node-A", typeRawValue = 2u,
            lastAdvertTimestamp = 1u, latitude = 1.0, longitude = 2.0)
        val value = envelope(devices = listOf(device(publicKey = key(0xDD))), discovered = listOf(dto))
        val first = service.importBackup(value, store)
        assertEquals(1L, first.count(BackupModelKind.DISCOVERED_NODES).inserted)
        assertEquals("Node-A", db.discoveredNodes().forRadio(RADIO.value).single().name)
        val second = service.importBackup(value, store)
        assertEquals(0L, second.count(BackupModelKind.DISCOVERED_NODES).inserted)
        assertEquals(1L, second.count(BackupModelKind.DISCOVERED_NODES).skipped)
        val third = service.importBackup(envelope(discovered = listOf(dto.copy(id = id(1010), name = "Node-A-UPDATED",
            lastHeard = java.time.Instant.ofEpochSecond(1_800_000_000), lastAdvertTimestamp = 99u,
            latitude = 9.0, longitude = 9.0, inboundHopCount = 3, inboundHopAdvertTimestamp = 99u))), store)
        assertEquals(0L, third.count(BackupModelKind.DISCOVERED_NODES).inserted)
        assertEquals(1L, third.count(BackupModelKind.DISCOVERED_NODES).skipped)
        val actual = db.discoveredNodes().forRadio(RADIO.value)
        assertEquals(1, actual.size); assertEquals("Node-A", actual.single().name); assertEquals(1L, actual.single().lastAdvertTimestamp)
    }

    @OriginalCase("BackupIntegrationTests::Discovered nodes survive full export → import into fresh store()")
    @Test fun discoveredFullNativePipelineRetainsEveryField() = runBlocking {
        val dto = discovered().copy(publicKey = key(0x22), name = "Discovered-Repeater", typeRawValue = 2u,
            latitude = 51.5, longitude = -0.12, lastAdvertTimestamp = 7u, outPathLength = 3u,
            outPath = Bytes.of(0xAA, 0xBB, 0xCC), inboundHopCount = 2, inboundHopAdvertTimestamp = 7u)
        db.discoveredNodes().insert(dto.toEntity())
        val backup = AppBackupCodec().parseBackup(service.export(store).data)
        assertEquals(1, backup.discoveredNodes.size); assertTrue(backup.manifest.validate(backup))
        db.discoveredNodes().clearRadio(RADIO.value)
        assertEquals(1L, service.importBackup(backup, store).count(BackupModelKind.DISCOVERED_NODES).inserted)
        val actual = db.discoveredNodes().forRadio(RADIO.value).single().toDTO()
        assertEquals(dto.copy(id = actual.id), actual)
    }

    @OriginalCase("BackupIntegrationTests::Discovered nodes remap to local radioID when device publicKey matches()")
    @Test fun discoveredRadioPublicKeyMatchRetainsLocalPartition() = runBlocking {
        seed(envelope(devices = listOf(device(OTHER_RADIO, key(0xEE)))))
        service.importBackup(envelope(devices = listOf(device(publicKey = key(0xEE))),
            discovered = listOf(discovered().copy(publicKey = key(0x33), name = "Foreign-Node"))), store)
        assertEquals(1, db.discoveredNodes().forRadio(OTHER_RADIO.value).size)
        assertTrue(db.discoveredNodes().forRadio(RADIO.value).isEmpty())
    }

    @OriginalCase("BackupIntegrationTests::Discovered nodes skip after remap when local already has the business key()")
    @Test fun remappedDiscoveredBusinessKeyKeepsCommittedState() = runBlocking {
        seed(envelope(devices = listOf(device(OTHER_RADIO, key(0xEF)))))
        val local = discovered(radio = OTHER_RADIO).copy(publicKey = key(0x44), name = "Local-Keep", lastAdvertTimestamp = 1u)
        service.importBackup(envelope(discovered = listOf(local)), store)
        val result = service.importBackup(envelope(devices = listOf(device(publicKey = key(0xEF))),
            discovered = listOf(local.copy(id = id(1010), radioId = RADIO, name = "Foreign-Overwrite", typeRawValue = 2u, lastAdvertTimestamp = 9u,
                latitude = 9.0, longitude = 9.0, inboundHopCount = 4, inboundHopAdvertTimestamp = 9u))), store)
        assertEquals(0L, result.count(BackupModelKind.DISCOVERED_NODES).inserted); assertEquals(1L, result.count(BackupModelKind.DISCOVERED_NODES).skipped)
        assertEquals("Local-Keep", db.discoveredNodes().forRadio(OTHER_RADIO.value).single().name)
        assertTrue(db.discoveredNodes().forRadio(RADIO.value).isEmpty())
    }

    @OriginalCase("BackupIntegrationTests::Import trims discovered nodes over the per-radio cap()")
    @Test fun freshDiscoveryCapDropsOldestAndBalancesCounts() = runBlocking {
        val cap = RoomPersistenceStore.MAX_DISCOVERED_NODES.toInt()
        val result = service.importBackup(envelope(discovered = (0..cap).map { discovered(it) }), store)
        val counts = result.count(BackupModelKind.DISCOVERED_NODES)
        assertEquals(cap.toLong(), counts.inserted); assertEquals(0L, counts.skipped); assertEquals(1L, counts.dropped)
        assertEquals(cap + 1L, counts.inserted + counts.skipped + counts.dropped)
        val actual = db.discoveredNodes().forRadio(RADIO.value)
        assertEquals(cap, actual.size); assertFalse(actual.any { it.name == "N0" }); assertTrue(actual.any { it.name == "N$cap" })
    }

    @OriginalCase("BackupIntegrationTests::Import into at-cap store keeps committed nodes and drops the incoming overflow()")
    @Test fun committedDiscoveryRowsAlwaysWinAtCap() = runBlocking {
        val cap = RoomPersistenceStore.MAX_DISCOVERED_NODES.toInt()
        service.importBackup(envelope(discovered = (0 until cap).map { discovered(it).copy(name = "Old$it") }), store)
        val result = service.importBackup(envelope(discovered = (2000..2004).map {
            discovered(it).copy(name = "New$it", lastHeard = java.time.Instant.ofEpochSecond(1_800_000_000L + it))
        }), store)
        assertEquals(0L, result.count(BackupModelKind.DISCOVERED_NODES).inserted)
        assertEquals(5L, result.count(BackupModelKind.DISCOVERED_NODES).dropped)
        val actual = db.discoveredNodes().forRadio(RADIO.value)
        assertEquals(cap, actual.size); assertTrue(actual.none { it.name.startsWith("New") })
    }

    @OriginalCase("BackupIntegrationTests::Import fills partial room under the per-radio cap()")
    @Test fun partialDiscoveryCapSelectsOnlyNewestIncoming() = runBlocking {
        val cap = RoomPersistenceStore.MAX_DISCOVERED_NODES.toInt()
        service.importBackup(envelope(discovered = (0 until cap - 2).map { discovered(it).copy(name = "Seed$it") }), store)
        val result = service.importBackup(envelope(discovered = (2000..2004).map {
            discovered(it).copy(name = "New$it", lastHeard = java.time.Instant.ofEpochSecond(1_800_000_000L + it))
        }), store)
        assertEquals(2L, result.count(BackupModelKind.DISCOVERED_NODES).inserted)
        assertEquals(3L, result.count(BackupModelKind.DISCOVERED_NODES).dropped)
        val actual = db.discoveredNodes().forRadio(RADIO.value)
        assertEquals(cap, actual.size); assertEquals(cap - 2, actual.count { it.name.startsWith("Seed") })
        assertEquals(setOf("New2003", "New2004"), actual.filter { it.name.startsWith("New") }.map { it.name }.toSet())
    }

    @OriginalCase("BackupIntegrationTests::Import skips discovered nodes with invalid public key size()")
    @Test fun malformedDiscoveryKeyIsExplicitlyCountedSkipped() = runBlocking {
        val bad = discovered().copy(publicKey = Bytes(ByteArray(16) { 0x11 }), name = "Bad-Key")
        val good = discovered(1).copy(publicKey = key(0x22), name = "Good-Key")
        val result = service.importBackup(envelope(discovered = listOf(bad, good)), store)
        assertEquals(1L, result.count(BackupModelKind.DISCOVERED_NODES).inserted)
        assertEquals(1L, result.count(BackupModelKind.DISCOVERED_NODES).skipped)
        assertEquals("Good-Key", db.discoveredNodes().forRadio(RADIO.value).single().name)
    }

    @OriginalCase("BackupIntegrationTests::Import null-islands invalid discovered coordinates()")
    @Test fun invalidDiscoveryFixIsSanitizedNotPlotted() = runBlocking {
        val result = service.importBackup(envelope(discovered = listOf(discovered().copy(name = "Bad-Coords", latitude = 999.0, longitude = -122.0))), store)
        assertEquals(1L, result.count(BackupModelKind.DISCOVERED_NODES).inserted)
        val actual = db.discoveredNodes().forRadio(RADIO.value).single().toDTO()
        assertEquals(0.0, actual.latitude, 0.0); assertEquals(0.0, actual.longitude, 0.0); assertFalse(actual.hasLocation)
    }

    @OriginalCase("BackupIntegrationTests::Import assigns fresh DiscoveredNode.id so an id collision cannot upsert the local row()")
    @Test fun discoverySurrogateCollisionCannotOverwriteLocalBusinessIdentity() = runBlocking {
        val local = discovered().copy(publicKey = key(0xA1), name = "Local-Node", latitude = 1.0, longitude = 1.0)
        db.discoveredNodes().insert(local.toEntity())
        val backup = local.copy(publicKey = key(0xB1), name = "Backup-Node", typeRawValue = 2u,
            lastHeard = java.time.Instant.ofEpochSecond(1_800_000_000), latitude = 2.0, longitude = 2.0)
        assertEquals(1L, service.importBackup(envelope(discovered = listOf(backup)), store).count(BackupModelKind.DISCOVERED_NODES).inserted)
        val actual = db.discoveredNodes().forRadio(RADIO.value)
        assertEquals(2, actual.size)
        assertEquals(local, actual.single { it.publicKey == local.publicKey }.toDTO())
        assertNotEquals(local.id, actual.single { it.publicKey == backup.publicKey }.id)
        assertEquals("Backup-Node", actual.single { it.publicKey == backup.publicKey }.name)
    }

    @OriginalCase("BackupIntegrationTests::Import truncates oversized discovered node name and outPath to protocol limits()")
    @Test fun discoveredNameAndPathUseExactProtocolBounds() = runBlocking {
        val name = "N".repeat(ProtocolLimits.MAX_USABLE_NAME_BYTES + 20)
        val path = Bytes(ByteArray(ProtocolLimits.MAX_PATH_SIZE + 16) { 0xAB.toByte() })
        val result = service.importBackup(envelope(discovered = listOf(discovered().copy(name = name, outPath = path))), store)
        assertEquals(1L, result.count(BackupModelKind.DISCOVERED_NODES).inserted)
        assertEquals(0L, result.count(BackupModelKind.DISCOVERED_NODES).skipped)
        val actual = db.discoveredNodes().forRadio(RADIO.value).single().toDTO()
        assertEquals(name.take(ProtocolLimits.MAX_USABLE_NAME_BYTES), actual.name)
        assertEquals(ProtocolLimits.MAX_PATH_SIZE, actual.outPath.size)
        assertEquals(path.prefix(ProtocolLimits.MAX_PATH_SIZE), actual.outPath)
    }
}
