// AndroidOnly: WP-202 Issue #614 Actual Room controls for atomic simulator fixture batches.
package com.meshcoreone.android.core.data.repository

import android.database.Cursor
import androidx.room.Room
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.RxLogEntryEntity
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.canonicalString
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SimulatorRxSeedBatchRoomTest : RepositoryTest() {
    @get:Rule val temporary = TemporaryFolder()
    private val radio = RadioId(UUID.fromString("abcdefab-1111-2222-3333-444444444444"))
    private val firstID = UUID.fromString("abcdefab-5555-6666-7777-888888888888")
    private val secondID = UUID.fromString("abcdefab-9999-aaaa-bbbb-cccccccccccc")

    private fun repository(database: MeshCoreDatabase = db) =
        RoomRepositoryContext(database, clock, owner, RepositoryIssueReporter { _, failure ->
            synchronized(issues) { issues += failure }
        })

    private fun storedRows(count: Int, on: RadioId = radio): List<RxLogEntryEntity> = (0 until count).map {
        rx(on, UUID(0x1234L, it.toLong()), receivedAt = AT.plusSeconds(it.toLong())).toEntity()
    }

    private fun rawRows(): List<List<Pair<Int, Any?>>> =
        db.openHelper.writableDatabase.query("SELECT * FROM rx_log_entries ORDER BY radioId, id").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                    val type = cursor.getType(column)
                    val value: Any? = when (type) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column).toRawBits()
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
                        else -> error("Unsupported SQLite column type $type")
                    }
                    type to value
                })
            }
        }

    private fun outcome(
        row: RxLogEntryEntity,
        decision: SimulatorRxSeedResult,
        presence: SimulatorRxSeedPresence = SimulatorRxSeedPresence.STORED,
    ) = SimulatorRxSeedOutcome(row.id, decision, presence)

    @Test fun publicDtoBatchReturnsOrderedDecisionsAndFinalPresence() = runTest {
        val first = rx(radio, firstID)
        val second = rx(radio, secondID)
        store.saveRxLogEntry(second)
        assertEquals(
            listOf(outcome(first.toEntity(), SimulatorRxSeedResult.INSERTED),
                outcome(second.toEntity(), SimulatorRxSeedResult.PRESERVED_PENDING)),
            store.seedSimulatorRxBatchIfAbsent(SnapshotList.of(first, second)).toList(),
        )
        assertEquals(setOf(first.toEntity(), second.toEntity()), db.rxLogs().newest(radio.value, -1).toSet())
        assertEquals(
            listOf(outcome(first.toEntity(), SimulatorRxSeedResult.PRESERVED_STORED),
                outcome(second.toEntity(), SimulatorRxSeedResult.PRESERVED_STORED)),
            store.seedSimulatorRxBatchIfAbsent(SnapshotList.of(first, second)).toList(),
        )
    }

    @Test fun mixedInsertionFlushesTheActualPendingBatchAndPrunesOnceWithoutResurrection() = runTest {
        for (refreshVictim in listOf(false, true)) {
            val stored = storedRows(1100)
            val foreign = storedRows(1100, RADIO_B)
            db.rxLogs().upsert(stored + foreign)
            val first = rx(radio, firstID, receivedAt = AT.plusSeconds(3000)).toEntity()
            val second = stored.first()
            val pending = second.copy(
                packetHash = "real pending update",
                receivedAt = rx(receivedAt = if (refreshVictim) AT.plusSeconds(4000) else AT).toEntity().receivedAt,
            )
            val foreignPending = rx(RADIO_B, secondID, receivedAt = AT.plusSeconds(3000)).toEntity()
            val repository = repository()
            try {
                repository.saveRx(pending)
                repository.saveRx(foreignPending)
                val expected = (stored.drop(1) + pending + first).sortedBy { it.receivedAt.toInstant() }.takeLast(1000)
                val results = repository.seedSimulatorRxBatch(listOf(first, second))
                assertEquals(listOf(
                    outcome(first, SimulatorRxSeedResult.INSERTED),
                    outcome(second, SimulatorRxSeedResult.PRESERVED_PENDING,
                        if (refreshVictim) SimulatorRxSeedPresence.STORED else SimulatorRxSeedPresence.PRUNED),
                ), results)
                assertEquals(expected.toSet(), db.rxLogs().newest(radio.value, -1).toSet())
                assertEquals((foreign.drop(101) + foreignPending).toSet(),
                    db.rxLogs().newest(RADIO_B.value, -1).toSet())
                assertTrue(repository.read("batchPendingConsumed") { pendingRx.isEmpty() })
                assertEquals(1L, repository.rxInitiatedSaveCount)
                repository.flushRx()
                assertEquals(1L, repository.rxInitiatedSaveCount)
                assertEquals(expected.toSet(), db.rxLogs().newest(radio.value, -1).toSet(),
                    "The former pending victim must not be reinserted by a later clean flush")
            } finally {
                repository.close()
                db.rxLogs().clearRadio(radio.value)
                db.rxLogs().clearRadio(RADIO_B.value)
            }
        }
    }

    @Test fun storedSecondFixtureDecisionPrecedesPruningAndTruthfullyReportsItsEviction() = runTest {
        val stored = storedRows(1100)
        val untouched = storedRows(1101, RADIO_B)
        db.rxLogs().upsert(stored + untouched)
        val first = rx(radio, firstID, receivedAt = AT.plusSeconds(3000)).toEntity()
        val second = stored.first()
        val repository = repository()
        try {
            assertEquals(listOf(
                outcome(first, SimulatorRxSeedResult.INSERTED),
                outcome(second, SimulatorRxSeedResult.PRESERVED_STORED, SimulatorRxSeedPresence.PRUNED),
            ), repository.seedSimulatorRxBatch(listOf(first, second)))
            assertEquals((stored.drop(101) + first).toSet(), db.rxLogs().newest(radio.value, -1).toSet())
            assertNull(db.rxLogs().byId(radio.value, second.id))
            assertEquals(untouched.toSet(), db.rxLogs().newest(RADIO_B.value, -1).toSet())
            assertEquals(0L, repository.rxInitiatedSaveCount)
            repository.flushRx()
            assertEquals(1000L, db.rxLogs().count(radio.value))
        } finally {
            repository.close()
        }
    }

    @Test fun mixedPendingVictimUpdateMatchesTheRealOrdinaryGenericBatchSurvivors() = runTest {
        val stored = storedRows(1100)
        val baseline = stored.map { it.copy(radioId = RADIO_B.value) }
        db.rxLogs().upsert(stored + baseline)
        val first = rx(radio, firstID, receivedAt = AT.plusSeconds(3000)).toEntity()
        val second = stored.first()
        val pending = second.copy(packetHash = "pending update of the oldest victim")
        val batch = repository()
        val generic = repository()
        try {
            batch.saveRx(pending)
            generic.saveRx(pending.copy(radioId = RADIO_B.value))
            generic.saveRx(first.copy(radioId = RADIO_B.value))
            generic.flushRx()
            val expected = db.rxLogs().newest(RADIO_B.value, -1).map { it.copy(radioId = radio.value) }.toSet()
            assertEquals(1000, expected.size)
            assertEquals(listOf(
                outcome(first, SimulatorRxSeedResult.INSERTED),
                outcome(second, SimulatorRxSeedResult.PRESERVED_PENDING, SimulatorRxSeedPresence.PRUNED),
            ), batch.seedSimulatorRxBatch(listOf(first, second)))
            assertEquals(expected, db.rxLogs().newest(radio.value, -1).toSet())
            batch.flushRx()
            assertEquals(expected, db.rxLogs().newest(radio.value, -1).toSet(),
                "The pending victim must not resurrect into the former early-prune 1,001-row result")
            assertNull(db.rxLogs().byId(radio.value, pending.id))
            assertEquals(1L, batch.rxInitiatedSaveCount)
            assertEquals(1L, generic.rxInitiatedSaveCount)
        } finally {
            batch.close()
            generic.close()
        }
    }

    @Test fun entirelyPreservedOversizedBatchDoesNoDmlAndLeavesRealPendingUntouched() = runTest {
        val stored = storedRows(1101)
        db.rxLogs().upsert(stored)
        val pending = stored.first().copy(packetHash = "pending fixture")
        val foreignPending = rx(RADIO_B, secondID).toEntity()
        val repository = repository()
        val sql = db.openHelper.writableDatabase
        try {
            repository.saveRx(pending)
            repository.saveRx(foreignPending)
            val before = rawRows()
            val exactPending = repository.read("preservedBatchBaseline") { pendingRx.toMap() }
            for (event in listOf("INSERT", "UPDATE", "DELETE")) {
                sql.execSQL("CREATE TRIGGER reject_preserved_$event BEFORE $event ON rx_log_entries " +
                    "BEGIN SELECT RAISE(ABORT, 'preserved batch performed DML'); END")
            }
            try {
                assertEquals(listOf(
                    outcome(stored.first(), SimulatorRxSeedResult.PRESERVED_PENDING, SimulatorRxSeedPresence.PENDING),
                    outcome(stored.last(), SimulatorRxSeedResult.PRESERVED_STORED),
                ), repository.seedSimulatorRxBatch(listOf(stored.first(), stored.last())))
                assertEquals(before, rawRows())
                assertEquals(exactPending, repository.read("preservedBatchPending") { pendingRx.toMap() })
                assertEquals(0L, repository.rxInitiatedSaveCount)
            } finally {
                for (event in listOf("INSERT", "UPDATE", "DELETE")) sql.execSQL("DROP TRIGGER reject_preserved_$event")
            }
        } finally {
            repository.close()
        }
    }

    @Test fun aPendingWriteCannotChangeAFuturePendingTargetBeforeItsOwnUpsert() = runTest {
        val stored = storedRows(1100)
        db.rxLogs().upsert(stored)
        val first = rx(radio, firstID, receivedAt = AT.plusSeconds(3000)).toEntity()
        val second = stored.last()
        val pendingFirst = rx(RADIO_B, secondID).toEntity()
        val pendingLater = second.copy(packetHash = "intended later pending value")
        val repository = repository()
        val sql = db.openHelper.writableDatabase
        try {
            repository.saveRx(pendingFirst)
            repository.saveRx(pendingLater)
            val before = rawRows()
            val pendingBefore = repository.read("allInputPendingBaseline") { pendingRx.toMap() }
            sql.execSQL("CREATE TRIGGER mutate_future_pending AFTER INSERT ON rx_log_entries " +
                "WHEN NEW.id = '${pendingFirst.id.canonicalString()}' BEGIN UPDATE rx_log_entries " +
                "SET packetHash = 'unauthorized intermediate value' WHERE id = '${second.id.canonicalString()}'; END")
            try {
                val failure = assertFailsWith<PersistenceStoreException> {
                    repository.seedSimulatorRxBatch(listOf(first, second))
                }
                assertIs<PersistenceStoreError.SaveFailed>(failure.error)
                assertEquals(before, rawRows(),
                    "A later legitimate pending upsert must not hide an earlier trigger mutation")
                assertEquals(pendingBefore, repository.read("allInputPendingRollback") { pendingRx.toMap() })
                assertEquals(0L, repository.rxInitiatedSaveCount)
            } finally {
                sql.execSQL("DROP TRIGGER mutate_future_pending")
            }
            assertEquals(listOf(outcome(first, SimulatorRxSeedResult.INSERTED),
                outcome(second, SimulatorRxSeedResult.PRESERVED_PENDING)),
                repository.seedSimulatorRxBatch(listOf(first, second)))
            assertEquals(pendingLater, db.rxLogs().byId(radio.value, second.id))
        } finally {
            repository.close()
        }
    }

    @Test fun realGenericFlushRadiosParticipateButAnUntrackedOversizedRadioStaysUntouched() = runTest {
        for (hasPending in listOf(false, true)) {
            val tracked = storedRows(1101, RADIO_B)
            val untouched = storedRows(1101, RADIO_A)
            db.rxLogs().upsert(tracked + untouched)
            val first = rx(radio, firstID).toEntity()
            val second = rx(radio, secondID).toEntity()
            val pending = rx(radio).toEntity()
            val repository = repository()
            try {
                repository.saveRx(tracked.last())
                repository.write("persistTrackedPending") { save() }
                if (hasPending) repository.saveRx(pending)
                repository.seedSimulatorRxBatch(listOf(first, second))
                assertEquals(tracked.drop(101).toSet(), db.rxLogs().newest(RADIO_B.value, -1).toSet())
                assertEquals(untouched.toSet(), db.rxLogs().newest(RADIO_A.value, -1).toSet())
                assertEquals(if (hasPending) setOf(pending, first, second) else setOf(first, second),
                    db.rxLogs().newest(radio.value, -1).toSet())
                assertEquals(1L, repository.rxInitiatedSaveCount)
            } finally {
                repository.close()
                db.rxLogs().clearRadio(radio.value)
                db.rxLogs().clearRadio(RADIO_A.value)
                db.rxLogs().clearRadio(RADIO_B.value)
            }
        }
    }

    @Test fun fixtureInsertCannotMutateAStoredSurvivorDeleteToBypassThresholdOrRenameALaterFixture() = runTest {
        val stored = storedRows(1100)
        db.rxLogs().upsert(stored)
        val first = rx(radio, firstID, receivedAt = AT.plusSeconds(3000)).toEntity()
        val second = stored.last()
        val pending = rx(RADIO_B, secondID).toEntity()
        val repository = repository()
        val sql = db.openHelper.writableDatabase
        try {
            repository.saveRx(pending)
            val before = rawRows()
            val pendingBefore = repository.read("triggerPendingBaseline") { pendingRx.toMap() }
            val mutations = listOf(
                "UPDATE rx_log_entries SET packetHash = 'trigger mutation' WHERE id = '${second.id.canonicalString()}';",
                "DELETE FROM rx_log_entries WHERE radioId = '${radio.value.canonicalString()}' " +
                    "AND id = '${stored.first().id.canonicalString()}';",
                "UPDATE rx_log_entries SET id = lower(id) WHERE id = '${second.id.canonicalString()}';",
            )
            for (mutation in mutations) {
                sql.execSQL("CREATE TRIGGER corrupt_batch_insert AFTER INSERT ON rx_log_entries " +
                    "WHEN NEW.id = '${first.id.canonicalString()}' BEGIN $mutation END")
                try {
                    val failure = assertFailsWith<PersistenceStoreException> {
                        repository.seedSimulatorRxBatch(listOf(first, second))
                    }
                    assertIs<PersistenceStoreError.SaveFailed>(failure.error)
                    assertSame(failure, issues.last())
                    assertEquals(before, rawRows())
                    assertEquals(pendingBefore, repository.read("triggerPendingAfterRollback") { pendingRx.toMap() })
                    assertEquals(0L, repository.rxInitiatedSaveCount)
                } finally {
                    sql.execSQL("DROP TRIGGER corrupt_batch_insert")
                }
            }
            assertEquals(SimulatorRxSeedPresence.STORED, repository.seedSimulatorRxBatch(listOf(first, second))[0].presence)
            assertEquals(1000L, db.rxLogs().count(radio.value))
            assertEquals(pending, db.rxLogs().byId(RADIO_B.value, pending.id))
        } finally {
            repository.close()
        }
    }

    @Test fun secondFixtureAndDeleteFailuresRollbackEveryInsertionPendingWriteAndPruneBeforeRetry() = runTest {
        val stored = storedRows(1100)
        db.rxLogs().upsert(stored)
        val first = rx(radio, firstID, receivedAt = AT.plusSeconds(3000)).toEntity()
        val second = rx(radio, secondID, receivedAt = AT.plusSeconds(4000)).toEntity()
        val pending = rx(RADIO_B).toEntity()
        val repository = repository()
        val sql = db.openHelper.writableDatabase
        try {
            repository.saveRx(pending)
            val before = rawRows()
            val pendingBefore = repository.read("batchFailurePendingBaseline") { pendingRx.toMap() }
            val triggers = listOf(
                "BEFORE INSERT ON rx_log_entries WHEN NEW.id = '${second.id.canonicalString()}' " +
                    "BEGIN SELECT RAISE(ABORT, 'second batch fixture rejected'); END",
                "BEFORE DELETE ON rx_log_entries BEGIN SELECT RAISE(ABORT, 'batch prune rejected'); END",
            )
            for (trigger in triggers) {
                sql.execSQL("CREATE TRIGGER reject_batch $trigger")
                try {
                    val failure = assertFailsWith<PersistenceStoreException> {
                        repository.seedSimulatorRxBatch(listOf(first, second))
                    }
                    assertIs<PersistenceStoreError.SaveFailed>(failure.error)
                    assertEquals(before, rawRows())
                    assertEquals(pendingBefore, repository.read("batchFailurePendingAfter") { pendingRx.toMap() })
                    assertEquals(0L, repository.rxInitiatedSaveCount)
                } finally {
                    sql.execSQL("DROP TRIGGER reject_batch")
                }
            }
            assertEquals(listOf(outcome(first, SimulatorRxSeedResult.INSERTED),
                outcome(second, SimulatorRxSeedResult.INSERTED)),
                repository.seedSimulatorRxBatch(listOf(first, second)))
            assertEquals((stored.drop(102) + first + second).toSet(), db.rxLogs().newest(radio.value, -1).toSet())
            assertTrue(repository.read("batchRetryPendingConsumed") { pendingRx.isEmpty() })
            assertEquals(1L, repository.rxInitiatedSaveCount)
        } finally {
            repository.close()
        }
    }

    @Test fun cancellationAfterActualPendingInsertAndPruneDmlRollsBackTheWholeBatch() = runTest {
        val stored = storedRows(1100)
        db.rxLogs().upsert(stored)
        val first = rx(radio, firstID, receivedAt = AT.plusSeconds(3000)).toEntity()
        val second = stored.last()
        val pending = rx(RADIO_B).toEntity()
        val repository = repository()
        try {
            repository.saveRx(pending)
            val before = rawRows()
            coroutineScope {
                val attempt = async {
                    repository.write("cancelAtomicSimulatorBatch") {
                        val result = insertSimulatorRxBatchIfAbsent(listOf(first, second))
                        assertTrue(result.inserted)
                        assertEquals(1000L, database.rxLogs().count(radio.value))
                        assertEquals(pending, database.rxLogs().byId(RADIO_B.value, pending.id))
                        assertNotNull(currentCoroutineContext()[Job]).cancel()
                        currentCoroutineContext().ensureActive()
                    }
                }
                assertFailsWith<CancellationException> { attempt.await() }
            }
            assertEquals(before, rawRows())
            assertEquals(mapOf(EntityKey(RADIO_B, pending.id) to pending),
                repository.read("batchCancellationPending") { pendingRx.toMap() })
            assertEquals(0L, repository.rxInitiatedSaveCount)
            repository.seedSimulatorRxBatch(listOf(first, second))
            assertEquals(1000L, db.rxLogs().count(radio.value))
            assertTrue(repository.read("batchCancellationRetry") { pendingRx.isEmpty() })
        } finally {
            repository.close()
        }
    }

    @Test fun aNewFixtureInsideTheNormalCutFailsVisiblyAndRetryRemainsBlocked() = runTest {
        val stored = storedRows(1100)
        db.rxLogs().upsert(stored)
        val first = rx(radio, firstID, receivedAt = AT.minusSeconds(1)).toEntity()
        val second = stored.last()
        val pending = rx(RADIO_B).toEntity()
        val repository = repository()
        try {
            repository.saveRx(pending)
            val before = rawRows()
            repeat(2) {
                val failure = assertFailsWith<PersistenceStoreException> {
                    repository.seedSimulatorRxBatch(listOf(first, second))
                }
                assertIs<PersistenceStoreError.SaveFailed>(failure.error)
                assertSame(failure, issues.last())
                assertEquals(before, rawRows())
                assertEquals(mapOf(EntityKey(RADIO_B, pending.id) to pending),
                    repository.read("oldBatchFixturePending") { pendingRx.toMap() })
                assertEquals(0L, repository.rxInitiatedSaveCount)
            }
        } finally {
            repository.close()
        }
    }

    @Test fun batchThresholdAndTiesKeepTheOrdinaryTargetAndAllStrictlyNewerRows() = runTest {
        for (count in listOf(1098, 1099, 1100)) {
            val stored = storedRows(count).mapIndexed { index, row ->
                if (index < 200) row.copy(receivedAt = rx(receivedAt = AT).toEntity().receivedAt) else row
            }
            db.rxLogs().upsert(stored)
            val first = rx(radio, firstID, receivedAt = AT.plusSeconds(3000)).toEntity()
            val second = rx(radio, secondID, receivedAt = AT.plusSeconds(4000)).toEntity()
            val repository = repository()
            try {
                assertEquals(listOf(outcome(first, SimulatorRxSeedResult.INSERTED),
                    outcome(second, SimulatorRxSeedResult.INSERTED)),
                    repository.seedSimulatorRxBatch(listOf(first, second)))
                val actual = db.rxLogs().newest(radio.value, -1)
                val target = if (count + 2 > 1100) 1000 else count + 2
                assertEquals(target, actual.size)
                assertTrue(actual.containsAll(stored.drop(200) + first + second))
                assertEquals(target - (count - 200) - 2, actual.count { it.receivedAt.toInstant() == AT })
                assertTrue(actual.all { it in stored || it == first || it == second })
                assertEquals(0L, repository.rxInitiatedSaveCount)
            } finally {
                repository.close()
                db.rxLogs().clearRadio(radio.value)
            }
        }
    }

    @Test fun reopenBeforeFinishFlushSeesTheCommittedPairAndPendingRetentionTogether() = runTest {
        val name = temporary.root.resolve("simulator-batch.db").absolutePath
        val immediate = Executor { it.run() }
        fun open() = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        val firstDb = open()
        val firstContext = repository(firstDb)
        val stored = storedRows(1100)
        val first = rx(radio, firstID, receivedAt = AT.plusSeconds(3000)).toEntity()
        val second = stored.last()
        val pending = rx(radio, secondID, receivedAt = AT.plusSeconds(4000)).toEntity()
        try {
            firstDb.rxLogs().upsert(stored)
            firstContext.saveRx(pending)
            firstContext.seedSimulatorRxBatch(listOf(first, second))
            firstDb.close()
            val reopened = open()
            val newContext = repository(reopened)
            try {
                val expected = (stored.drop(102) + first + pending).toSet()
                assertEquals(expected, reopened.rxLogs().newest(radio.value, -1).toSet())
                assertEquals(listOf(outcome(first, SimulatorRxSeedResult.PRESERVED_STORED),
                    outcome(second, SimulatorRxSeedResult.PRESERVED_STORED)),
                    newContext.seedSimulatorRxBatch(listOf(first, second)))
                newContext.flushRx()
                assertEquals(expected, reopened.rxLogs().newest(radio.value, -1).toSet())
                assertEquals(0L, newContext.rxInitiatedSaveCount)
            } finally {
                newContext.close()
                reopened.close()
            }
        } finally {
            firstContext.close()
            firstDb.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun invalidBatchAndOriginalAliasPreflightRefuseBeforeAnyPendingDml() = runTest {
        val first = rx(radio, firstID).toEntity()
        val second = rx(radio, secondID).toEntity()
        val pending = rx(RADIO_B).toEntity()
        val repository = repository()
        val sql = db.openHelper.writableDatabase
        try {
            repository.saveRx(pending)
            val before = rawRows()
            for (batch in listOf(emptyList(), listOf(first, first), listOf(first, second.copy(radioId = RADIO_B.value)))) {
                assertEquals(PersistenceStoreError.InvalidData,
                    assertFailsWith<PersistenceStoreException> { repository.seedSimulatorRxBatch(batch) }.error)
                assertEquals(before, rawRows())
            }
            db.rxLogs().insert(second)
            sql.execSQL("UPDATE rx_log_entries SET id = lower(id)")
            val aliasBefore = rawRows()
            assertEquals(PersistenceStoreError.InvalidData,
                assertFailsWith<PersistenceStoreException> { repository.seedSimulatorRxBatch(listOf(second)) }.error)
            assertEquals(aliasBefore, rawRows())
            db.rxLogs().insert(second.copy(packetHash = "case twin"))
            val twinsBefore = rawRows()
            assertEquals(PersistenceStoreError.InvalidData,
                assertFailsWith<PersistenceStoreException> { repository.seedSimulatorRxBatch(listOf(first, second)) }.error)
            assertEquals(twinsBefore, rawRows())
            assertEquals(mapOf(EntityKey(RADIO_B, pending.id) to pending),
                repository.read("invalidBatchPending") { pendingRx.toMap() })
            assertEquals(0L, repository.rxInitiatedSaveCount)
        } finally {
            repository.close()
        }
    }

    @Test fun aliasPlusPendingInTheSecondFixtureRefusesBeforeInsertingTheFirstOrFlushingAnyPending() = runTest {
        val first = rx(radio, firstID).toEntity()
        val second = rx(radio, secondID).toEntity()
        db.rxLogs().insert(second)
        val repository = repository()
        val pending = second.copy(packetHash = "original canonical pending")
        try {
            repository.saveRx(pending)
            db.openHelper.writableDatabase.execSQL("UPDATE rx_log_entries SET radioId = lower(radioId), id = lower(id)")
            val before = rawRows()
            val failure = assertFailsWith<PersistenceStoreException> {
                repository.seedSimulatorRxBatch(listOf(first, second))
            }
            assertEquals(PersistenceStoreError.InvalidData, failure.error)
            assertEquals("RX log UUID case aliases require explicit data repair; automatic overwrite or normalization is refused",
                failure.cause?.message)
            assertEquals(before, rawRows())
            assertEquals(mapOf(EntityKey(radio, second.id) to pending),
                repository.read("batchAliasPending") { pendingRx.toMap() })
            assertEquals(0L, repository.rxInitiatedSaveCount)
        } finally {
            // Explicit cleanup does not claim that generic alias upsert has been repaired.
            db.openHelper.writableDatabase.execSQL("DELETE FROM rx_log_entries")
            repository.close()
        }
    }

    @Test fun independentContextsCommitOneWholeWinningPairRatherThanInterleavingDecisions() = runBlocking<Unit> {
        val first = repository()
        val second = repository()
        val one = listOf(rx(radio, firstID).toEntity(), rx(radio, secondID).toEntity())
        val two = one.map { it.copy(packetHash = "second context") }
        val start = CompletableDeferred<Unit>()
        try {
            val results = coroutineScope {
                val a = async(Dispatchers.Default) { start.await(); first.seedSimulatorRxBatch(one) }
                val b = async(Dispatchers.Default) { start.await(); second.seedSimulatorRxBatch(two) }
                start.complete(Unit)
                withTimeout(10_000) { listOf(a.await(), b.await()) }
            }
            assertEquals(1, results.count { batch -> batch.all { it.decision == SimulatorRxSeedResult.INSERTED } })
            assertEquals(1, results.count { batch -> batch.all { it.decision == SimulatorRxSeedResult.PRESERVED_STORED } })
            val expected = if (results[0].first().decision == SimulatorRxSeedResult.INSERTED) one else two
            assertEquals(expected.toSet(), db.rxLogs().newest(radio.value, -1).toSet())
            assertTrue(results.flatten().all { it.presence == SimulatorRxSeedPresence.STORED })
        } finally {
            first.close()
            second.close()
        }
    }

    @Test fun unrelatedPendingAliasesAndTwinsOnEitherRadioRefuseBeforeAnyBatchDml() = runTest {
        val first = rx(radio, firstID).toEntity()
        val second = rx(radio, secondID).toEntity()
        val sql = db.openHelper.writableDatabase
        val otherRadio = RadioId(UUID.fromString("abcdefbb-1111-2222-3333-444444444444"))
        for (pendingRadio in listOf(radio, otherRadio)) {
            for (columns in listOf(listOf("id"), listOf("radioId"), listOf("radioId", "id"))) {
                for (twins in listOf(false, true)) {
                    val original = rx(pendingRadio, UUID.fromString("abcdefab-dddd-eeee-ffff-111111111111")).toEntity()
                    db.rxLogs().insert(original)
                    val repository = repository()
                    val pending = original.copy(packetHash = "retained original pending")
                    val earlierPending = rx(RADIO_A).toEntity()
                    try {
                        repository.saveRx(earlierPending)
                        repository.saveRx(pending)
                        sql.execSQL("UPDATE rx_log_entries SET ${columns.joinToString { "$it = lower($it)" }}")
                        if (twins) db.rxLogs().insert(original.copy(packetHash = "stored canonical twin"))
                        val before = rawRows()
                        val pendingBefore = repository.read("unrelatedAliasPendingBaseline") { pendingRx.toMap() }
                        val failure = assertFailsWith<PersistenceStoreException> {
                            repository.seedSimulatorRxBatch(listOf(first, second))
                        }
                        assertEquals(PersistenceStoreError.InvalidData, failure.error)
                        assertEquals(
                            "RX log UUID case aliases require explicit data repair; automatic overwrite or normalization is refused",
                            failure.cause?.message,
                        )
                        assertSame(failure, issues.last())
                        assertEquals(before, rawRows(), "Even the earlier safe pending write must not run")
                        assertEquals(pendingBefore, repository.read("unrelatedAliasPendingRollback") { pendingRx.toMap() })
                        assertEquals(0L, repository.rxInitiatedSaveCount)
                        assertNull(db.rxLogs().byId(radio.value, first.id))
                        assertNull(db.rxLogs().byId(radio.value, second.id))
                        db.rxLogs().insert(first)
                        db.rxLogs().insert(second)
                        val preservedBefore = rawRows()
                        val preservedFailure = assertFailsWith<PersistenceStoreException> {
                            repository.seedSimulatorRxBatch(listOf(first, second))
                        }
                        assertEquals(failure.error, preservedFailure.error)
                        assertEquals(failure.cause?.message, preservedFailure.cause?.message)
                        assertSame(preservedFailure, issues.last())
                        assertEquals(preservedBefore, rawRows(),
                            "Pure preservation must refuse before finishSeed can flush alias-conflicting pending")
                        assertEquals(pendingBefore, repository.read("preservedAliasPendingRollback") { pendingRx.toMap() })
                        assertEquals(0L, repository.rxInitiatedSaveCount)
                    } finally {
                        // Explicit disposal keeps unchanged generic upsert out of the refused operation.
                        sql.execSQL("DELETE FROM rx_log_entries")
                        repository.close()
                        sql.execSQL("DELETE FROM rx_log_entries")
                    }
                }
            }
        }
    }

    @Test fun independentWalHandlesArbitrateTheWholePairAndReopenKeepsBothWinningValues() = runBlocking<Unit> {
        val name = temporary.root.resolve("simulator-batch-handles.db").absolutePath
        val immediate = Executor { it.run() }
        fun open() = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .allowMainThreadQueries().setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        val firstDb = open()
        val secondDb = open()
        val first = repository(firstDb)
        val second = repository(secondDb)
        val one = listOf(rx(radio, firstID).toEntity(), rx(radio, secondID).toEntity())
        val two = one.map { it.copy(packetHash = "independent handle") }
        try {
            firstDb.openHelper.writableDatabase
            secondDb.openHelper.writableDatabase
            val start = CompletableDeferred<Unit>()
            val results = coroutineScope {
                val a = async(Dispatchers.Default) { start.await(); first.seedSimulatorRxBatch(one) }
                val b = async(Dispatchers.Default) { start.await(); second.seedSimulatorRxBatch(two) }
                start.complete(Unit)
                withTimeout(10_000) { listOf(a.await(), b.await()) }
            }
            assertEquals(1, results.count { rows -> rows.all { it.decision == SimulatorRxSeedResult.INSERTED } })
            assertEquals(1, results.count { rows -> rows.all { it.decision == SimulatorRxSeedResult.PRESERVED_STORED } })
            val winner = if (results[0].first().decision == SimulatorRxSeedResult.INSERTED) one else two
            assertEquals(winner.toSet(), secondDb.rxLogs().newest(radio.value, -1).toSet())
            first.close()
            second.close()
            firstDb.close()
            secondDb.close()
            val reopened = open()
            val newContext = repository(reopened)
            try {
                assertEquals(winner.toSet(), reopened.rxLogs().newest(radio.value, -1).toSet())
                assertEquals(one.map { outcome(it, SimulatorRxSeedResult.PRESERVED_STORED) },
                    newContext.seedSimulatorRxBatch(one))
                assertEquals(winner.toSet(), reopened.rxLogs().newest(radio.value, -1).toSet())
            } finally {
                newContext.close()
                reopened.close()
            }
        } finally {
            first.close()
            second.close()
            firstDb.close()
            secondDb.close()
            context.deleteDatabase(name)
        }
    }
}
