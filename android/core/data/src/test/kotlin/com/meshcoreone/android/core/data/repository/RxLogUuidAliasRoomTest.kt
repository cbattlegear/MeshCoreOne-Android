// AndroidOnly: WP-202 Real-Room RX UUID alias rejection and lossless batching regression controls (#615).
package com.meshcoreone.android.core.data.repository

import android.database.Cursor
import androidx.room.Room
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.*
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RxLogUuidAliasRoomTest : RepositoryTest() {
    private val radio = RadioId(UUID.fromString("ABCDEFAB-1234-5678-90AB-ABCDEFABCDEF"))
    private val entryId = UUID.fromString("FEDCBAFE-1234-5678-90AB-FEDCBAFEDCBA")
    private fun entry() = rx(radio, entryId).copy(channelName = "original", regionScope = "original")

    private fun mixed(value: String): String = value.mapIndexed { index, c ->
        if (index % 2 == 0) c.lowercaseChar() else c
    }.joinToString("")

    /** Insert real full payloads first; only raw physical key spellings bypass Room's converter. */
    private suspend fun seedPhysical(
        database: MeshCoreDatabase, value: RxLogEntryDTO,
        radioText: String = value.radioId.canonicalString, idText: String = value.id.canonicalString(),
    ) {
        val temporary = UUID.randomUUID()
        database.rxLogs().insert(value.copy(id = temporary).toEntity())
        database.openHelper.writableDatabase.execSQL(
            "UPDATE rx_log_entries SET radioId = ?, id = ? WHERE radioId = ? AND id = ?",
            arrayOf(radioText, idText, value.radioId.canonicalString, temporary.canonicalString()),
        )
    }

    private fun snapshot(database: MeshCoreDatabase = db): List<List<Any?>> =
        database.openHelper.readableDatabase.query(
            "SELECT * FROM rx_log_entries ORDER BY radioId COLLATE BINARY, id COLLATE BINARY",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { column ->
                        when (cursor.getType(column)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
                            else -> cursor.getString(column)
                        }
                    })
                }
            }
        }

    private fun removeAliasFixtures(database: MeshCoreDatabase = db) {
        // Test-only cleanup, after preservation assertions, lets retained pending fixtures close normally.
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM rx_log_entries WHERE radioId COLLATE BINARY != UPPER(radioId) " +
                "OR id COLLATE BINARY != UPPER(id)",
        )
    }

    private fun assertAliasFailure(failure: PersistenceStoreException) {
        assertEquals(PersistenceStoreError.InvalidData, failure.error)
        assertContains(assertNotNull(failure.cause?.message), "RX log UUID case aliases")
        assertSame(failure, issues.last())
    }

    @Test fun radioEntryAndBothLowercaseOrMixedAliasesRejectBeforeEnqueueWithoutChangingPayloads() = runTest {
        val original = entry()
        val upperRadio = radio.canonicalString
        val upperId = entryId.canonicalString()
        for ((rawRadio, rawId) in listOf(
            upperRadio.lowercase() to upperId,
            upperRadio to upperId.lowercase(),
            upperRadio.lowercase() to upperId.lowercase(),
            mixed(upperRadio) to upperId,
            upperRadio to mixed(upperId),
            mixed(upperRadio) to mixed(upperId),
        )) {
            seedPhysical(db, original, rawRadio, rawId)
            val before = snapshot()
            val failure = assertFailsWith<PersistenceStoreException> {
                store.saveRxLogEntry(original.copy(channelName = "must not overwrite"))
            }
            assertAliasFailure(failure)
            assertEquals(before, snapshot())
            assertEquals(0L, store.rxInitiatedSaveCount)
            store.flushPendingRxLogEntries()
            assertEquals(before, snapshot())
            removeAliasFixtures()
            assertTrue(snapshot().isEmpty())
        }
        // Failed admissions must not poison another radio's count, buffer or later healthy saves.
        store.saveRxLogEntry(rx(RADIO_B))
        store.saveRxLogEntry(original)
        store.flushPendingRxLogEntries()
        assertEquals(1L, store.countRxLogEntries(radio))
        assertEquals(1L, store.countRxLogEntries(RADIO_B))
    }

    @Test fun equalAndConflictingPhysicalDuplicatesNeverCollapseIntoAnOverlayOrRetentionWinner() = runTest {
        val original = entry()
        for (alias in listOf(original, original.copy(channelName = "conflicting", regionScope = "other"))) {
            db.rxLogs().insert(original.toEntity())
            seedPhysical(db, alias, radio.canonicalString.lowercase(), mixed(entryId.canonicalString()))
            val before = snapshot()
            try {
                assertEquals(2, before.size)
                assertAliasFailure(assertFailsWith { store.fetchRxLogEntries(radio) })
                assertAliasFailure(assertFailsWith { store.countRxLogEntries(radio) })
                assertAliasFailure(assertFailsWith { store.saveRxLogEntry(original) })
                assertAliasFailure(assertFailsWith { store.pruneRxLogEntries(radio, 0, 0) })
                assertAliasFailure(assertFailsWith { store.clearRxLogEntries(radio) })
                assertEquals(before, snapshot())
            } finally {
                removeAliasFixtures()
                db.rxLogs().clearRadio(radio.value)
            }
        }
    }

    @Test fun aliasDiscoveredAfterAdmissionRetainsPendingOverlayAndRollsBackUnrelatedSave() = runTest {
        val pending = entry()
        store.saveRxLogEntry(pending)
        val canonicalDisk = pending.copy(channelName = "disk canonical")
        db.rxLogs().insert(canonicalDisk.toEntity())
        seedPhysical(db, pending.copy(channelName = "disk alias"), idText = mixed(entryId.canonicalString()))
        val before = snapshot()
        val channel = ChannelDTO(radioId = RADIO_B, index = 1u, name = "must roll back")
        try {
            assertAliasFailure(assertFailsWith { store.fetchRxLogEntries(radio) })
            assertAliasFailure(assertFailsWith { store.saveChannel(channel) })
            assertNull(db.channels().byId(RADIO_B.value, channel.id))
            assertAliasFailure(assertFailsWith { store.flushPendingRxLogEntries() })
            assertEquals(before, snapshot())
            assertEquals(0L, store.rxInitiatedSaveCount)
        } finally {
            removeAliasFixtures()
        }
        // No failed transaction cleared the pending value; normal canonical upsert semantics resume.
        assertEquals("original", store.fetchRxLogEntries(radio).single().channelName)
        store.flushPendingRxLogEntries()
        assertEquals("original", db.rxLogs().byId(radio.value, pending.id)?.channelName)
        assertEquals(1L, store.rxInitiatedSaveCount)
    }

    @Test fun enrichmentAndDeviceDeletionRefuseAliasesRatherThanUpdatingOrDeletingOnlyCanonicalRows() = runTest {
        val original = entry()
        val device = device(radio)
        store.saveDevice(device)
        db.rxLogs().insert(original.toEntity())
        seedPhysical(db, original.copy(channelName = "alias"), radioText = mixed(radio.canonicalString))
        val before = snapshot()
        try {
            assertAliasFailure(assertFailsWith {
                store.batchUpdateRxLogRegion(radio, SnapshotList.of(
                    com.meshcoreone.android.core.contracts.domain.RxLogRegionUpdate(entryId, "changed", SnapshotList.empty()),
                ))
            })
            assertAliasFailure(assertFailsWith {
                store.batchUpdateRxLogDecryption(radio, SnapshotList.of(
                    com.meshcoreone.android.core.contracts.domain.RxLogDecryptionUpdate(entryId, 2u, "changed", 5u),
                ))
            })
            assertAliasFailure(assertFailsWith { store.deleteDeviceAndData(device.id) })
            assertNotNull(db.devices().byId(device.id))
            assertEquals(before, snapshot())
        } finally {
            removeAliasFixtures()
        }
        store.batchUpdateRxLogRegion(radio, SnapshotList.of(
            com.meshcoreone.android.core.contracts.domain.RxLogRegionUpdate(entryId, "updated", SnapshotList.empty()),
        ))
        assertEquals("updated", store.fetchRxLogEntries(radio).single().regionScope)
        store.clearRxLogEntries(radio)
        assertTrue(snapshot().isEmpty())
    }

    @Test fun repeatedCanonicalSavesKeepOneLogicalEntryAndTheOriginalTwentyCallBatchBoundary() = runTest(scheduler) {
        val original = entry()
        repeat(19) { index -> store.saveRxLogEntry(original.copy(channelName = "pending-$index")) }
        assertEquals(0L, store.rxInitiatedSaveCount)
        assertTrue(snapshot().isEmpty())
        assertEquals(1L, store.countRxLogEntries(radio))
        store.saveRxLogEntry(original.copy(channelName = "twentieth"))
        assertEquals(1L, store.rxInitiatedSaveCount)
        assertEquals(1, snapshot().size)
        assertEquals("twentieth", db.rxLogs().byId(radio.value, original.id)?.channelName)
        store.saveRxLogEntry(original.copy(channelName = "timed"))
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertEquals("twentieth", db.rxLogs().byId(radio.value, original.id)?.channelName)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("timed", db.rxLogs().byId(radio.value, original.id)?.channelName)
        assertEquals(2L, store.rxInitiatedSaveCount)
        assertEquals(1L, store.countRxLogEntries(radio))
        assertEquals(1, snapshot().size)
    }

    @Test fun aliasBlocksAutomaticRetentionWithoutDeletingAnyOfTheConflictingOrOldestRows() = runTest {
        val rows = (0 until 1100).map { index ->
            rx(radio, receivedAt = AT.plusNanos(index.toLong()), timestamp = index.toUInt()).toEntity()
        }
        db.rxLogs().upsert(rows)
        val pending = entry().copy(receivedAt = AT.plusNanos(1100))
        store.saveRxLogEntry(pending)
        seedPhysical(db, entry().copy(receivedAt = AT.minusSeconds(1)), idText = entryId.canonicalString().lowercase())
        val before = snapshot()
        try {
            assertAliasFailure(assertFailsWith { store.flushPendingRxLogEntries() })
            assertEquals(before, snapshot())
            assertEquals(1101, snapshot().size)
            assertEquals(0L, store.rxInitiatedSaveCount)
        } finally {
            removeAliasFixtures()
        }
        store.flushPendingRxLogEntries()
        assertEquals(1000L, db.rxLogs().count(radio.value))
        val kept = store.fetchRxLogEntries(radio, 1200)
        assertEquals(pending.id, kept.first().id)
        assertEquals(101u, kept.last().senderTimestamp)
    }

    @Test fun failureHalfwayThroughFlushRollsBackAllRowsAndRetainsTheOriginalPendingBatch() = runTest {
        val first = entry()
        val second = rx(radio, receivedAt = AT.plusNanos(1))
        store.saveRxLogEntry(first)
        store.saveRxLogEntry(second)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_second_rx BEFORE INSERT ON rx_log_entries " +
                "WHEN NEW.id = '${second.id.canonicalString()}' " +
                "BEGIN SELECT RAISE(FAIL, 'controlled RX batch failure'); END",
        )
        try {
            val failure = assertFailsWith<PersistenceStoreException> { store.flushPendingRxLogEntries() }
            assertIs<PersistenceStoreError.SaveFailed>(failure.error)
            assertSame(failure, issues.last())
            assertTrue(snapshot().isEmpty())
            assertEquals(2L, store.countRxLogEntries(radio))
            assertEquals(0L, store.rxInitiatedSaveCount)
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_second_rx")
        }
        store.flushPendingRxLogEntries()
        assertEquals(setOf(first.id, second.id), store.fetchRxLogEntries(radio).map { it.id }.toSet())
        assertEquals(1L, store.rxInitiatedSaveCount)
    }

    @Test fun triggerCreatedAliasIsDetectedBeforePruningAndRollsBackItsInsertWithTheBatch() = runTest {
        val original = entry()
        store.saveRxLogEntry(original)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER alias_after_insert AFTER INSERT ON rx_log_entries " +
                "BEGIN UPDATE rx_log_entries SET id = LOWER(id) WHERE rowid = NEW.rowid; END",
        )
        try {
            assertAliasFailure(assertFailsWith { store.flushPendingRxLogEntries() })
            assertTrue(snapshot().isEmpty())
            assertEquals(1L, store.countRxLogEntries(radio))
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER alias_after_insert")
        }
        store.flushPendingRxLogEntries()
        assertEquals(1, snapshot().size)
        assertNotNull(db.rxLogs().byId(radio.value, entryId))
    }

    @Test fun timerAndCloseFailuresRetainTheBatchWithoutSpinningOrMarkingTheStoreClosed() = runTest(scheduler) {
        val original = entry()
        store.saveRxLogEntry(original)
        seedPhysical(db, original.copy(channelName = "legacy"), idText = entryId.canonicalString().lowercase())
        val before = snapshot()
        try {
            runCurrent()
            advanceTimeBy(1000)
            runCurrent()
            assertAliasFailure(issues.last())
            val reports = issues.size
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(reports, issues.size)
            assertAliasFailure(assertFailsWith { store.close() })
            assertEquals(before, snapshot())
            assertEquals(0L, store.rxInitiatedSaveCount)
        } finally {
            removeAliasFixtures()
        }
        assertEquals(original.id, store.fetchRxLogEntries(radio).single().id)
        store.close()
        assertEquals(1L, db.rxLogs().count(radio.value))
        assertEquals(1L, store.rxInitiatedSaveCount)
    }

    @Test fun cancellationAfterStagedRealWritesRollsBackAndKeepsThePendingRows() = runTest {
        val repository = RoomRepositoryContext(db, clock, owner, RepositoryIssueReporter { _, error -> issues += error })
        val original = entry()
        try {
            repository.saveRx(original.toEntity())
            val cancellation = CancellationException("after actual pending RX writes")
            val thrown = assertFailsWith<CancellationException> {
                repository.write("controlledRxCancellation") {
                    persistPendingRx()
                    throw cancellation
                }
            }
            // Coroutine stack-trace recovery may copy the exception, retaining the original as cause.
            assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === cancellation },
                "The propagated cancellation must retain the actual original cause")
            assertTrue(snapshot().isEmpty(), "Cancelled staged RX inserts must roll back")
            assertEquals(1, repository.read("pendingAfterCancellation") { rxRows(radio).size },
                "Cancellation must retain the original pending batch")
            repository.flushRx()
            assertEquals(1L, db.rxLogs().count(radio.value))
        } finally {
            repository.close()
        }
    }

    @Test fun independentFileBackedContextsRevalidateAfterAnAliasWriterCommitsAndPreserveBothValues() = runTest {
        val name = "rx-alias-${UUID.randomUUID()}.db"
        val immediate = Executor { it.run() }
        fun open() = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        val firstDb = open()
        val secondDb = open()
        val first = RoomPersistenceStore(firstDb, owner, clock, RepositoryIssueReporter { _, error -> issues += error })
        val second = RoomPersistenceStore(secondDb, owner, clock, RepositoryIssueReporter { _, error -> issues += error })
        try {
            val pending = entry()
            first.saveRxLogEntry(pending)
            second.saveRxLogEntry(pending.copy(channelName = "other context"))
            second.flushPendingRxLogEntries()
            // A raw legacy writer on the second physical connection adds an alias after admission.
            seedPhysical(secondDb, pending.copy(channelName = "legacy alias"),
                radioText = radio.canonicalString.lowercase(), idText = mixed(entryId.canonicalString()))
            val before = snapshot(secondDb)
            assertAliasFailure(assertFailsWith { first.flushPendingRxLogEntries() })
            assertAliasFailure(assertFailsWith { first.fetchRxLogEntries(radio) })
            assertEquals(before, snapshot(firstDb))
            assertEquals(before, snapshot(secondDb))
            assertEquals(0L, first.rxInitiatedSaveCount)
            removeAliasFixtures(secondDb)
            assertEquals("original", first.fetchRxLogEntries(radio).single().channelName)
            first.flushPendingRxLogEntries()
            assertEquals(1L, firstDb.rxLogs().count(radio.value))
            assertEquals("original", second.fetchRxLogEntries(radio).single().channelName)
        } finally {
            removeAliasFixtures(secondDb)
            first.close()
            second.close()
            firstDb.close()
            secondDb.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun lateCancellationCannotKeepACommittedBatchPendingOrMakeItDuplicateOnNextFlush() = runTest {
        var toCancel: Job? = null
        val immediate = Executor { it.run() }
        val commitExecutor = Executor { command ->
            command.run()
            toCancel?.cancel(CancellationException("after actual RX transaction commit"))
        }
        val database = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(commitExecutor).build()
        val repository = RoomRepositoryContext(database, clock, owner, RepositoryIssueReporter { _, error -> issues += error })
        try {
            val original = entry()
            repository.saveRx(original.toEntity())
            val operation = launch(start = CoroutineStart.UNDISPATCHED) {
                toCancel = currentCoroutineContext()[Job]
                repository.flushRx()
            }
            operation.join()
            toCancel = null
            assertTrue(operation.isCancelled)
            assertEquals(1L, database.rxLogs().count(radio.value))
            assertEquals(1L, repository.rxInitiatedSaveCount)
            repository.flushRx()
            assertEquals(1L, database.rxLogs().count(radio.value))
            assertEquals(1L, repository.rxInitiatedSaveCount)
        } finally {
            toCancel = null
            repository.close()
            database.close()
        }
    }
}
