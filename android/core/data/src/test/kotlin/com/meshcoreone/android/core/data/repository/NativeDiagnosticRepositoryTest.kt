// AndroidOnly: WP-202 Actual trace, log/cache, node-history and diagnostic enrichment regression assertions.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PayloadType
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NativeDiagnosticRepositoryTest : RepositoryTest() {
    @Test fun simulatorSnapshotBatchRechecksMillisecondKeysAndNodeHistory() = runTest {
        val firstNode = Bytes.of(0x10, 0x20)
        val secondNode = Bytes.of(0x30, 0x40)
        val negative = NodeStatusSnapshotDTO(timestamp = Instant.ofEpochSecond(-1, 999_250_000), nodePublicKey = firstNode)
        val sameMillisecond = NodeStatusSnapshotDTO(timestamp = Instant.ofEpochSecond(0, 200_000), nodePublicKey = firstNode)
        val previousMillisecond = NodeStatusSnapshotDTO(timestamp = Instant.ofEpochSecond(-1, 998_500_000), nodePublicKey = firstNode)
        assertEquals(2L to 1L, store.batchInsertNodeStatusSnapshots(
            listOf(negative, sameMillisecond, previousMillisecond).snapshot(), emptySet(),
        ))
        assertEquals(setOf(firstNode to 0L, firstNode to -1L), store.existingNodeStatusSnapshotKeys())

        val staleKeys = store.existingNodeStatusSnapshotKeys()
        val raced = NodeStatusSnapshotDTO(timestamp = AT, nodePublicKey = secondNode)
        db.nodeSnapshots().insert(raced.toEntity())
        val later = raced.copy(id = UUID.randomUUID(), timestamp = AT.plusSeconds(60))
        val unrelated = NodeStatusSnapshotDTO(timestamp = AT, nodePublicKey = Bytes.of(0x50))
        assertEquals(1L to 1L, store.batchInsertNodeStatusSnapshots(
            listOf(later, unrelated).snapshot(), staleKeys, skipNodesWithHistory = true,
        ))
        assertEquals(listOf(raced), store.fetchNodeStatusSnapshots(secondNode, null))
        assertEquals(listOf(unrelated), store.fetchNodeStatusSnapshots(unrelated.nodePublicKey, null))
    }

    @Test fun simulatorSnapshotBatchRollsBackWhenASecondInsertConflicts() = runTest {
        val key = Bytes.of(0x42)
        val duplicateId = UUID.randomUUID()
        val first = NodeStatusSnapshotDTO(id = duplicateId, timestamp = AT, nodePublicKey = key)
        val second = first.copy(timestamp = AT.plusSeconds(1))
        assertFailsWith<PersistenceStoreException> {
            store.batchInsertNodeStatusSnapshots(listOf(first, second).snapshot(), emptySet())
        }
        assertTrue(store.fetchNodeStatusSnapshots(key, null).isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun partialBatchFlushUsesExactlyOneSecondOfTheOwnedTestScheduler() = runTest(scheduler) {
        val entry = rx()
        store.saveRxLogEntry(entry)
        val scheduled = requireNotNull(owner.coroutineContext[Job]).children.single().children.single()
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertFalse(scheduled.isCompleted)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM rx_log_entries").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        advanceTimeBy(1)
        runCurrent()
        scheduled.join()
        assertEquals(1L, store.rxInitiatedSaveCount)
        assertEquals(entry.id, assertNotNull(db.rxLogs().byId(RADIO_A.value, entry.id)).id)
    }

    @Test fun tracePathsPreserveBytesHashWidthsNamesRunsAndScopedCascade() = runTest {
        val initial = TracePathRunDTO(UUID.randomUUID(), AT, true, 3_000_000_000L, SnapshotList.of(-1.5, 7.0))
        val path = store.createSavedTracePath(RADIO_A, "before", Bytes.of(0x80, 0xFF), 2, initial)
        assertEquals(Bytes.of(0x80, 0xFF), path.pathBytes)
        assertEquals(2L, path.hashSize)
        assertEquals(listOf(initial), path.runs)
        store.updateSavedTracePathName(entity(id = path.id), "after")
        val later = initial.copy(id = UUID.randomUUID(), date = AT.plusNanos(1), success = false)
        store.appendTracePathRun(entity(id = path.id), later)
        assertEquals("after", assertNotNull(store.fetchSavedTracePath(entity(id = path.id))).name)
        assertEquals(listOf(initial, later), assertNotNull(store.fetchSavedTracePath(entity(id = path.id))).runs)
        val other = path.copy(radioId = RADIO_B)
        db.tracePaths().insert(other.toEntity())
        db.traceRuns().insert(initial.toEntity(RADIO_B, path.id))
        store.deleteSavedTracePath(entity(id = path.id))
        assertNull(store.fetchSavedTracePath(entity(id = path.id)))
        assertTrue(db.traceRuns().forPath(RADIO_A.value, path.id).isEmpty())
        assertEquals(listOf(initial), assertNotNull(store.fetchSavedTracePath(entity(RADIO_B, path.id))).runs)
    }

    @Test fun traceEncodingFailureRollsBackTheParentInsteadOfReplacingRunsWithEmptyData() = runTest {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val bad = TracePathRunDTO(UUID.randomUUID(), AT, true, 1, SnapshotList.of(value))
            val failure = assertFailsWith<PersistenceStoreException> {
                store.createSavedTracePath(RADIO_A, "invalid run", Bytes.of(0x80), 1, bad)
            }
            assertEquals("trace hops", assertIs<DatabaseValueException>(failure.cause).field)
            assertTrue(store.fetchSavedTracePaths(RADIO_A).isEmpty())
            assertNull(db.traceRuns().byId(RADIO_A.value, bad.id))
        }
        val bad = TracePathRunDTO(UUID.randomUUID(), AT, true, 1, SnapshotList.of(Double.NaN))
        val missing = assertFailsWith<PersistenceStoreException> {
            store.appendTracePathRun(entity(id = UUID.randomUUID()), bad)
        }
        assertEquals(PersistenceStoreError.FetchFailed("SavedTracePath not found"), missing.error)
    }

    @Test fun emptyFiniteNegativeFractionalAndZeroTraceRunsKeepTheirEncodedBytes() = runTest {
        val empty = TracePathRunDTO(UUID.randomUUID(), AT, true, 1, SnapshotList.empty())
        val path = store.createSavedTracePath(RADIO_A, "finite", Bytes.of(0x80), 1, empty)
        assertEquals(Bytes.utf8("[]"), assertNotNull(db.traceRuns().byId(RADIO_A.value, empty.id)).hopsData)
        val finite = empty.copy(id = UUID.randomUUID(), date = AT.plusNanos(1), hopsSNR = SnapshotList.of(-1.5, 0.125, 7.0))
        store.appendTracePathRun(entity(id = path.id), finite)
        assertEquals(Bytes.utf8("[-1.5,0.125,7]"), assertNotNull(db.traceRuns().byId(RADIO_A.value, finite.id)).hopsData)
        val zeros = empty.copy(id = UUID.randomUUID(), date = AT.plusNanos(2), hopsSNR = SnapshotList.of(0.0, -0.0))
        store.appendTracePathRun(entity(id = path.id), zeros)
        assertEquals(Bytes.utf8("[0,-0]"), assertNotNull(db.traceRuns().byId(RADIO_A.value, zeros.id)).hopsData)
        val runs = assertNotNull(store.fetchSavedTracePath(entity(id = path.id))).runs
        assertEquals(3, runs.size)
        assertTrue(runs[0].hopsSNR.isEmpty())
        assertEquals(listOf(-1.5, 0.125, 7.0), runs[1].hopsSNR)
        assertEquals(2, runs[2].hopsSNR.size)
        assertTrue(runs[2].hopsSNR.all { it == 0.0 })
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val bad = empty.copy(id = UUID.randomUUID(), hopsSNR = SnapshotList.of(value))
            assertFailsWith<PersistenceStoreException> { store.appendTracePathRun(entity(id = path.id), bad) }
            assertNull(db.traceRuns().byId(RADIO_A.value, bad.id))
            assertEquals(3, assertNotNull(store.fetchSavedTracePath(entity(id = path.id))).runs.size)
        }
    }

    @Test fun malformedStoredTraceDataSurfacesItsConverterCauseAndLeavesTheRowIntact() = runTest {
        val run = TracePathRunDTO(UUID.randomUUID(), AT, true, 1, SnapshotList.empty())
        val path = store.createSavedTracePath(RADIO_A, "path", Bytes.of(0x80), 1, run)
        val raw = assertNotNull(db.traceRuns().byId(RADIO_A.value, run.id))
        db.traceRuns().upsert(raw.copy(hopsData = Bytes.of(0xFF)))
        val failure = assertFailsWith<PersistenceStoreException> { store.fetchSavedTracePath(entity(id = path.id)) }
        assertEquals("trace hops", assertIs<DatabaseValueException>(failure.cause).field)
        assertEquals(Bytes.of(0xFF), assertNotNull(db.traceRuns().byId(RADIO_A.value, run.id)).hopsData)
    }

    @Test fun debugLogsUseTheGlobalScopeAndStrictTimeThenHardCountRetention() = runTest {
        val entries = listOf(
            DebugLogEntryDTO.create(DebugLogLevel.INFO, "app", "before", "before", timestamp = AT.minusNanos(1)),
            DebugLogEntryDTO.create(DebugLogLevel.NOTICE, "app", "at", "at", timestamp = AT),
            DebugLogEntryDTO.create(DebugLogLevel.WARNING, "app", "one", "one", timestamp = AT.plusNanos(1)),
            DebugLogEntryDTO.create(DebugLogLevel.ERROR, "app", "two", "two", timestamp = AT.plusNanos(2)),
        )
        store.saveDebugLogEntries(entries.snapshot())
        assertEquals(4L, store.countDebugLogEntries())
        assertEquals(listOf("two", "one", "at"), store.fetchDebugLogEntries(AT).map { it.message })
        store.pruneDebugLogEntries(AT, 2)
        assertEquals(listOf("two", "one"), store.fetchDebugLogEntries(AT).map { it.message })
        assertEquals(2L, store.countDebugLogEntries())
        store.saveDevice(device())
        store.deleteDeviceData(assertNotNull(store.fetchDevice(RADIO_A)).id)
        assertEquals(2L, store.countDebugLogEntries())
        store.clearDebugLogEntries()
        assertEquals(0L, store.countDebugLogEntries())
    }

    @Test fun urlCacheUpsertKeepsCaseNullableFieldsBytesAndExactDatesOutsideRadioWipes() = runTest {
        val first = LinkPreviewDataDTO("https://example.invalid/Case", "before", Bytes.of(0x80), Bytes.EMPTY,
            3_000_000_000, null, Instant.ofEpochSecond(-1, 500_000_000))
        store.saveLinkPreview(first)
        assertEquals(first, store.fetchLinkPreview(first.url))
        assertNull(store.fetchLinkPreview("https://example.invalid/case"))
        val second = first.copy(title = null, imageData = Bytes.EMPTY, iconData = null, imageHeight = Long.MAX_VALUE,
            fetchedAt = AT.plusNanos(1))
        store.saveLinkPreview(second)
        store.saveDevice(device())
        store.deleteDeviceData(assertNotNull(store.fetchDevice(RADIO_A)).id)
        assertEquals(second, store.fetchLinkPreview(first.url))
    }

    @Test fun statusTelemetryAndNeighborsEnrichOneWindowWhileLocationAndStatusAreFirstWins() = runTest {
        val telemetry = SnapshotList.of(TelemetrySnapshotEntry(1, "temperature", -12.5))
        val first = store.recordNodeStatusSnapshot(key(), null, telemetry, null, NodeLocationFix(1.0, 2.0, 3.0))
        clock.now = AT.plusSeconds(1)
        val metrics = NodeStatusMetrics(batteryMillivolts = UShort.MAX_VALUE, lastRSSI = Short.MIN_VALUE,
            noiseFloor = Short.MAX_VALUE, uptimeSeconds = UInt.MAX_VALUE, rxAirtimeSeconds = 0x8000_0000u,
            sentDirect = UInt.MAX_VALUE, postedCount = UShort.MAX_VALUE)
        val neighbors = SnapshotList.of(NeighborSnapshotEntry(Bytes.of(0x80, 0xFF), -7.5, Long.MAX_VALUE))
        assertEquals(first, store.recordNodeStatusSnapshot(key(), metrics, SnapshotList.empty(), neighbors,
            NodeLocationFix(4.0, 5.0, 6.0)))
        clock.now = AT.plusSeconds(2)
        assertEquals(first, store.recordNodeStatusSnapshot(key(), metrics.copy(uptimeSeconds = 1u),
            null, null, null))
        val row = assertNotNull(store.fetchLatestNodeStatusSnapshot(key()))
        assertEquals(UInt.MAX_VALUE, row.uptimeSeconds)
        assertEquals(UShort.MAX_VALUE, row.batteryMillivolts)
        assertEquals(Short.MIN_VALUE, row.lastRSSI)
        assertEquals(Short.MAX_VALUE, row.noiseFloor)
        assertEquals(0x8000_0000u, row.rxAirtimeSeconds)
        assertEquals(UInt.MAX_VALUE, row.sentDirect)
        assertEquals(UShort.MAX_VALUE, row.postedCount)
        assertEquals(SnapshotList.empty(), row.telemetryEntries)
        assertEquals(neighbors, row.neighborSnapshots)
        assertEquals(1.0, row.latitude)
        assertEquals(2.0, row.longitude)
        assertEquals(3.0, row.altitude)
        assertEquals(AT, row.timestamp)
    }

    @Test fun exactMinimumIntervalCreatesANewRowButSparseBaselinesIgnoreTheCurrentWindow() = runTest {
        val earlyNeighbors = SnapshotList.of(NeighborSnapshotEntry(Bytes.of(0x80), 1.0, 1))
        val early = store.recordNodeStatusSnapshot(key(), NodeStatusMetrics(uptimeSeconds = 10u), null,
            earlyNeighbors, null)
        clock.now = AT.plus(NodeSnapshotPolicy.minimumInterval)
        val current = store.recordNodeStatusSnapshot(key(), null, SnapshotList.empty(), SnapshotList.empty(), null)
        assertNotEquals(early, current)
        assertEquals(listOf(AT, clock.now), store.fetchNodeStatusSnapshots(key(), null).map { it.timestamp })
        assertEquals(current, store.fetchNodeStatusSnapshots(key(), clock.now).single().id)
        val baseline = store.fetchNeighborBaseline(key(), clock)
        assertEquals(early, baseline.previous?.id)
        assertEquals(setOf(Bytes.of(0x80)), baseline.seenPrefixes)
        assertEquals(early, store.fetchPreviousStatusSnapshot(key(), clock.now.plusNanos(1))?.id)
        store.deleteOldNodeStatusSnapshots(clock.now)
        assertEquals(listOf(current), store.fetchNodeStatusSnapshots(key(), null).map { it.id })
    }

    @Test fun twoStoreInstancesStillCaptureOneSharedKeyWindowAndGuardItsRadioReferences() = runTest {
        val other = newStore()
        try {
            val ids = listOf(
                async { store.recordNodeStatusSnapshot(key(), NodeStatusMetrics(uptimeSeconds = 1u), null, null, null) },
                async { other.recordNodeStatusSnapshot(key(), null, SnapshotList.empty(), null, null) },
            ).awaitAll()
            assertEquals(1, ids.toSet().size)
            val a = device()
            val b = device(RADIO_B)
            store.saveDevice(a)
            store.saveDevice(b)
            store.saveRemoteNodeSessionDTO(session())
            store.saveRemoteNodeSessionDTO(session(RADIO_B))
            store.deleteDeviceData(a.id)
            assertEquals(ids.first(), assertNotNull(store.fetchLatestNodeStatusSnapshot(key())).id)
            store.deleteDeviceData(b.id)
            assertNull(store.fetchLatestNodeStatusSnapshot(key()))
        } finally {
            other.close()
        }
    }

    @Test fun senderPrefixFallbackAppliesAllPredicatesBeforeItsTwentyCandidateLimit() = runTest {
        val match = rx(channelIndex = null, payload = PayloadType.TEXT_MESSAGE)
            .copy(packetPayload = Bytes.of(0, 0x80))
        store.saveRxLogEntry(match)
        for (i in 1..20) store.saveRxLogEntry(rx(channelIndex = null, payload = PayloadType.TEXT_MESSAGE,
            receivedAt = AT.plusNanos(i.toLong())).copy(packetPayload = Bytes.of(0, 0x81)))
        assertNull(store.findRxLogEntryBySenderPrefix(RADIO_A, 0x80u, AT))
        val newest = match.copy(id = UUID.randomUUID(), receivedAt = AT.plusNanos(21))
        store.saveRxLogEntry(newest)
        assertEquals(newest.id, assertNotNull(store.findRxLogEntryBySenderPrefix(RADIO_A, 0x80u, AT)).id)
        store.saveRxLogEntry(newest.copy(id = UUID.randomUUID(), receivedAt = AT.plusNanos(22), channelIndex = 7u))
        assertEquals(newest.id, assertNotNull(store.findRxLogEntryBySenderPrefix(RADIO_A, 0x80u, AT)).id)
        assertNull(store.findRxLogEntryBySenderPrefix(RADIO_B, 0x80u, AT))
    }

    @Test fun decryptionUpdatesClearNullableFieldsRetainRawNibblesAndIgnoreAnotherRadio() = runTest {
        val raw = rx().copy(payloadTypeBits = 0x0Cu, senderTimestamp = 1u, decryptStatus = DecryptStatus.HMAC_FAILED)
        store.saveRxLogEntry(raw)
        store.saveRxLogEntry(raw.copy(radioId = RADIO_B))
        store.batchUpdateRxLogDecryption(RADIO_A, SnapshotList.of(RxLogDecryptionUpdate(raw.id, null, null, UInt.MAX_VALUE)))
        val updated = store.fetchRxLogEntries(RADIO_A).single()
        assertNull(updated.channelIndex)
        assertNull(updated.channelName)
        assertEquals(UInt.MAX_VALUE, updated.senderTimestamp)
        assertEquals(DecryptStatus.SUCCESS, updated.decryptStatus)
        assertEquals(0x0C.toUByte(), updated.payloadTypeBits)
        assertEquals(DecryptStatus.HMAC_FAILED, store.fetchRxLogEntries(RADIO_B).single().decryptStatus)
        assertFailsWith<PersistenceStoreException> {
            store.batchUpdateRxLogRegion(RADIO_A, SnapshotList.of(
                RxLogRegionUpdate(raw.id, null, SnapshotList.empty()),
                RxLogRegionUpdate(raw.id, "duplicate", SnapshotList.of("duplicate"))))
        }
        assertNull(store.fetchRxLogEntries(RADIO_A).single().regionScope)
    }
}
