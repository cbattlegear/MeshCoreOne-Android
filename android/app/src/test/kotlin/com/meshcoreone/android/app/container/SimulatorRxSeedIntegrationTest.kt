// AndroidOnly: WP-217 Simulator RX reseeding through the production Room adapter.
package com.meshcoreone.android.app.container

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.RxLogRetention
import com.meshcoreone.android.core.contracts.domain.RxLogRegionUpdate
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.data.repository.SimulatorRxSeedOutcome
import com.meshcoreone.android.core.data.repository.SimulatorRxSeedPresence
import com.meshcoreone.android.core.data.repository.SimulatorRxSeedResult
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.toDTO
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.simulator.MockDataProvider
import com.meshcoreone.android.core.services.simulator.SimulatorConnectionMode
import com.meshcoreone.android.core.services.simulator.SimulatorSeedStore
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SimulatorRxSeedIntegrationTest : RoomProcessTest() {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun oldFixtureRowsOutsideTheDefaultWindowAndOtherRadiosArePreserved() = withStores { stores ->
        val local = stores.single()
        val fixtures = initialFixtures(local)
        val radio = MockDataProvider.simulatorRadioId
        local.batchUpdateRxLogRegion(
            radio,
            SnapshotList.of(
                RxLogRegionUpdate(fixtures[0].id, "kept-first", SnapshotList.of("kept-first")),
                RxLogRegionUpdate(fixtures[1].id, "kept-second", SnapshotList.of("kept-second")),
            ),
        )
        val preservedFixtures = local.fetchRxLogEntries(radio, 10).associateBy { it.id }
        assertEquals("kept-first", preservedFixtures.getValue(fixtures[0].id).regionScope)
        assertEquals("kept-second", preservedFixtures.getValue(fixtures[1].id).regionScope)

        val otherRadio = RadioId(UUID.fromString("00000000-0000-0000-0000-0000000000FF"))
        val otherRow = fixtures[0].copy(radioId = otherRadio, regionScope = "other-radio")
        local.saveRxLogEntry(otherRow)
        local.flushPendingRxLogEntries()
        addNewerRows(local, fixtures)
        val before = local.fetchRxLogEntries(radio, 600).associateBy { it.id }

        seedAt(LATER, RoomSimulatorSeedStore(local))
        local.flushPendingRxLogEntries()

        assertEquals(before, local.fetchRxLogEntries(radio, 600).associateBy { it.id })
        assertEquals(preservedFixtures, before.filterKeys { it in preservedFixtures })
        assertEquals(listOf(otherRow), local.fetchRxLogEntries(otherRadio, 10))
        assertEquals(503L, local.countRxLogEntries(radio))
    }

    @Test fun oldPendingFixtureIsNotReplacedOrFlushedByReseeding() = withStores { stores ->
        val local = stores.single()
        val fixtures = initialFixtures(local)
        val radio = MockDataProvider.simulatorRadioId
        addNewerRows(local, fixtures)
        val stored = fixtures[0]
        val pending = stored.copy(
            regionScope = "pending-personalization",
            regionScopeMatches = SnapshotList.of("pending-personalization"),
        )
        val adapter = RoomSimulatorSeedStore(local)
        val injecting = object : SimulatorSeedStore by adapter {
            override suspend fun seedRxLogEntries(
                radioId: RadioId,
                buildEntries: () -> SnapshotList<RxLogEntryDTO>,
            ) {
                local.saveRxLogEntry(pending)
                assertEquals(stored.regionScope, db.rxLogs().byId(radio.value, stored.id)?.regionScope)
                assertFalse(local.fetchRxLogEntries(radio, 500).any { it.id == pending.id })
                adapter.seedRxLogEntries(radioId, buildEntries)
            }
        }

        seedAt(LATER, injecting)

        assertEquals(pending, local.fetchRxLogEntries(radio, 600).single { it.id == pending.id })
        assertEquals(stored.regionScope, db.rxLogs().byId(radio.value, stored.id)?.regionScope)
        local.flushPendingRxLogEntries()
        assertEquals(pending, local.fetchRxLogEntries(radio, 600).single { it.id == pending.id })
        assertEquals("pending-personalization", db.rxLogs().byId(radio.value, stored.id)?.regionScope)
        assertEquals(503L, local.countRxLogEntries(radio))
    }

    @Test fun storedCaseAliasIsRejectedWithoutMutationByTheProductionSeed() = withStores { stores ->
        val local = stores.single()
        val fixtures = initialFixtures(local)
        val radio = MockDataProvider.simulatorRadioId
        local.batchUpdateRxLogRegion(
            radio,
            SnapshotList.of(RxLogRegionUpdate(fixtures[0].id, "stored-alias", SnapshotList.of("stored-alias"))),
        )
        val canonical = fixtures[0].id.toString().uppercase(java.util.Locale.ROOT)
        val alias = canonical.lowercase(java.util.Locale.ROOT)
        db.openHelper.writableDatabase.execSQL("UPDATE rx_log_entries SET id = lower(id) WHERE id = '$canonical'")
        val beforeIDs = storedRxIDs()
        val beforeRows = storedRxSnapshot()
        assertTrue(alias in beforeIDs)
        assertFalse(canonical in beforeIDs)
        try {
            val failure = assertFailsWith<PersistenceStoreException> {
                seedAt(LATER, RoomSimulatorSeedStore(local))
            }
            assertEquals(PersistenceStoreError.InvalidData, failure.error)
            assertEquals(beforeIDs, storedRxIDs())
            assertEquals(beforeRows, storedRxSnapshot())
        } finally {
            db.openHelper.writableDatabase.execSQL("UPDATE rx_log_entries SET id = upper(id) WHERE id = '$alias'")
        }
    }

    @Test fun storedCaseTwinsFailBeforeAnotherFixtureCanBeInserted() = withStores { stores ->
        val local = stores.single()
        val fixtures = initialFixtures(local)
        val radio = MockDataProvider.simulatorRadioId
        val canonical = fixtures[0].id.toString().uppercase(java.util.Locale.ROOT)
        val alias = canonical.lowercase(java.util.Locale.ROOT)
        val original = assertNotNull(db.rxLogs().byId(radio.value, fixtures[0].id))
        assertEquals(1, db.rxLogs().delete(radio.value, fixtures[1].id))
        db.openHelper.writableDatabase.execSQL("UPDATE rx_log_entries SET id = lower(id) WHERE id = '$canonical'")
        try {
            db.rxLogs().insert(
                original.copy(regionScope = "canonical-twin", regionScopeMatches = SnapshotList.of("canonical-twin")),
            )
            val before = storedRxProjection()
            val beforeRows = storedRxSnapshot()
            assertEquals(2, before.size)
            assertEquals(setOf(alias, canonical), before.map { it.first }.toSet())
            assertTrue(before.any { it.first == canonical && it.second == "canonical-twin" })
            assertTrue(before.any { it.first == alias && it.second == original.regionScope })

            val failure = assertFailsWith<PersistenceStoreException> {
                seedAt(LATER, RoomSimulatorSeedStore(local))
            }

            assertEquals(PersistenceStoreError.InvalidData, failure.error)
            assertEquals("RX log UUID case aliases require explicit data repair; automatic overwrite or normalization is refused",
                failure.cause?.message)
            assertEquals(before, storedRxProjection())
            assertEquals(beforeRows, storedRxSnapshot())
        } finally {
            db.openHelper.writableDatabase.execSQL("DELETE FROM rx_log_entries")
        }
    }

    @Test fun pendingAgainstAStoredCaseAliasFailsBeforeTheGenericFlush() = withStores { stores ->
        val local = stores.single()
        val fixtures = initialFixtures(local)
        val radio = MockDataProvider.simulatorRadioId
        val canonical = fixtures[0].id.toString().uppercase(java.util.Locale.ROOT)
        val alias = canonical.lowercase(java.util.Locale.ROOT)
        val sql = db.openHelper.writableDatabase
        val pending = fixtures[0].copy(
            regionScope = "pending-alias-conflict",
            regionScopeMatches = SnapshotList.of("pending-alias-conflict"),
        )
        val adapter = RoomSimulatorSeedStore(local)
        val injecting = object : SimulatorSeedStore by adapter {
            override suspend fun seedRxLogEntries(
                radioId: RadioId,
                buildEntries: () -> SnapshotList<RxLogEntryDTO>,
            ) {
                local.saveRxLogEntry(pending)
                sql.execSQL("UPDATE rx_log_entries SET id = lower(id) WHERE id = '$canonical'")
                adapter.seedRxLogEntries(radioId, buildEntries)
            }
        }

        try {
            val failure = assertFailsWith<PersistenceStoreException> { seedAt(LATER, injecting) }
            assertEquals(PersistenceStoreError.InvalidData, failure.error)
            assertEquals("RX log UUID case aliases require explicit data repair; automatic overwrite or normalization is refused",
                failure.cause?.message)
            val originalIDs = storedRxIDs()
            assertTrue(alias in originalIDs)
            assertFalse(canonical in originalIDs)
            assertNull(db.rxLogs().byId(radio.value, pending.id))

            val repeated = assertFailsWith<PersistenceStoreException> {
                adapter.seedRxLogEntries(radio) { SnapshotList.of(fixtures[0]) }
            }
            assertEquals(PersistenceStoreError.InvalidData, repeated.error)
            assertEquals(originalIDs, storedRxIDs())
            sql.execSQL("UPDATE rx_log_entries SET id = upper(id) WHERE id = '$alias'")
            assertEquals(pending, local.fetchRxLogEntries(radio, 10).single { it.id == pending.id })
        } finally {
            sql.execSQL("UPDATE rx_log_entries SET id = upper(id) WHERE id = '$alias'")
            local.clearRxLogEntries(radio)
        }
    }

    @Test fun failedFixtureInsertPreservesPendingAndRetryInsertsBothFixtures() = withStores { stores ->
        val local = stores.single()
        val fixtures = initialFixtures(local)
        val radio = MockDataProvider.simulatorRadioId
        deleteFixtures(fixtures)
        val pending = extraRow(fixtures[0], 10_000, "pending-during-failure")
        val adapter = RoomSimulatorSeedStore(local)
        val injecting = object : SimulatorSeedStore by adapter {
            override suspend fun seedRxLogEntries(
                radioId: RadioId,
                buildEntries: () -> SnapshotList<RxLogEntryDTO>,
            ) {
                local.saveRxLogEntry(pending)
                adapter.seedRxLogEntries(radioId, buildEntries)
            }
        }
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_simulator_rx BEFORE INSERT ON rx_log_entries " +
                "BEGIN SELECT RAISE(ABORT, 'simulator RX insert rejected'); END",
        )
        try {
            val failure = assertFailsWith<PersistenceStoreException> { seedAt(LATER, injecting) }
            assertTrue(failure.cause?.message?.contains("simulator RX insert rejected") == true)
            assertEquals(listOf(pending), local.fetchRxLogEntries(radio, 10))
            assertEquals(0L, db.rxLogs().count(radio.value))
            assertNull(db.rxLogs().byId(radio.value, pending.id))
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_simulator_rx")
        }

        seedAt(LATER, adapter)
        val after = local.fetchRxLogEntries(radio, 10).associateBy { it.id }
        assertEquals(fixtures.map { it.id }.toSet() + pending.id, after.keys)
        assertEquals(pending, after.getValue(pending.id))
        assertEquals(3L, db.rxLogs().count(radio.value))
    }

    @Test fun cancellationBeforeRxPreservesPendingAndRetrySeedsAbsentFixtures() = withStores { stores ->
        val local = stores.single()
        val fixtures = initialFixtures(local)
        val radio = MockDataProvider.simulatorRadioId
        deleteFixtures(fixtures)
        val pending = extraRow(fixtures[0], 20_000, "pending-during-cancellation")
        val adapter = RoomSimulatorSeedStore(local)
        val cancellation = CancellationException("before simulator RX insert")
        val cancelling = object : SimulatorSeedStore by adapter {
            override suspend fun seedRxLogEntries(
                radioId: RadioId,
                buildEntries: () -> SnapshotList<RxLogEntryDTO>,
            ) {
                local.saveRxLogEntry(pending)
                currentCoroutineContext().cancel(cancellation)
                adapter.seedRxLogEntries(radioId, buildEntries)
            }
        }
        val observed = AtomicReference<Throwable?>()
        val attempt = CoroutineScope(currentCoroutineContext()).launch {
            try {
                seedAt(LATER, cancelling)
            } catch (failure: CancellationException) {
                observed.set(failure)
            }
        }
        attempt.join()
        assertTrue(attempt.isCancelled)
        assertTrue(observed.get() === cancellation || observed.get()?.cause === cancellation)
        assertEquals(listOf(pending), local.fetchRxLogEntries(radio, 10))
        assertEquals(0L, db.rxLogs().count(radio.value))
        assertNull(db.rxLogs().byId(radio.value, pending.id))

        seedAt(LATER, adapter)
        val after = local.fetchRxLogEntries(radio, 10).associateBy { it.id }
        assertEquals(fixtures.map { it.id }.toSet() + pending.id, after.keys)
        assertEquals(pending, after.getValue(pending.id))
        assertEquals(3L, db.rxLogs().count(radio.value))
    }

    @Test fun failureOnSecondFixtureRollsBackTheWholeBatchForRetry() = withStores { stores ->
        val local = stores.single()
        val fixtures = initialFixtures(local)
        val radio = MockDataProvider.simulatorRadioId
        deleteFixtures(fixtures)
        val before = storedRxSnapshot()
        val rejectedID = fixtures[1].id.toString().uppercase(java.util.Locale.ROOT)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_second_simulator_rx BEFORE INSERT ON rx_log_entries " +
                "WHEN NEW.id = '$rejectedID' BEGIN SELECT RAISE(ABORT, 'second RX rejected'); END",
        )
        try {
            val failure = assertFailsWith<PersistenceStoreException> {
                seedAt(LATER, RoomSimulatorSeedStore(local))
            }
            assertTrue(failure.cause?.message?.contains("second RX rejected") == true)
            assertEquals(before, storedRxSnapshot())
            assertNull(db.rxLogs().byId(radio.value, fixtures[0].id))
            assertNull(db.rxLogs().byId(radio.value, fixtures[1].id))
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_second_simulator_rx")
        }

        seedAt(LATER.plusSeconds(600), RoomSimulatorSeedStore(local))
        val after = local.fetchRxLogEntries(radio, 10).associateBy { it.id }
        for (fixture in fixtures) assertEquals(
            fixture.receivedAt.plusSeconds(LATER.epochSecond - FIRST.epochSecond + 600),
            after.getValue(fixture.id).receivedAt,
        )
        assertEquals(2L, db.rxLogs().count(radio.value))
    }

    @Test fun cancellationAfterCommittedBatchKeepsBothFixturesForRetry() = withStores { stores ->
        val local = stores.single()
        val fixtures = initialFixtures(local)
        val radio = MockDataProvider.simulatorRadioId
        deleteFixtures(fixtures)
        val entries = SnapshotList(fixtures.map {
            it.copy(receivedAt = it.receivedAt.plusSeconds(LATER.epochSecond - FIRST.epochSecond))
        })
        val cancellation = CancellationException("after simulator RX batch commit")
        val observed = AtomicReference<Throwable?>()
        val visited = mutableListOf<List<UUID>>()
        val attempt = CoroutineScope(currentCoroutineContext()).launch {
            val caller = currentCoroutineContext()
            try {
                seedRoomSimulatorRxEntries(radio, { entries }) { batch ->
                    val result = local.seedSimulatorRxBatchIfAbsent(batch)
                    visited.add(batch.map { it.id })
                    caller.cancel(cancellation)
                    result
                }
            } catch (failure: CancellationException) {
                observed.set(failure)
            }
        }
        attempt.join()
        assertTrue(attempt.isCancelled)
        assertTrue(observed.get() === cancellation || observed.get()?.cause === cancellation)
        assertEquals(listOf(entries.map { it.id }), visited)
        val committed = entries.associate { it.id to assertNotNull(db.rxLogs().byId(radio.value, it.id)) }

        RoomSimulatorSeedStore(local).seedRxLogEntries(radio) { entries }
        for (entry in entries) assertEquals(committed.getValue(entry.id), db.rxLogs().byId(radio.value, entry.id))
        assertEquals(2L, db.rxLogs().count(radio.value))
    }

    @Test fun cancellingDuringAnRxFixtureFailurePreservesBothCauses() = withStores { stores ->
        val adapter = RoomSimulatorSeedStore(stores.single())
        val radio = MockDataProvider.simulatorRadioId
        val failures = listOf(
            PersistenceStoreException(PersistenceStoreError.InvalidData),
            IllegalArgumentException("fixture argument"),
            IllegalStateException("fixture state"),
            ArithmeticException("fixture arithmetic"),
            DateTimeException("fixture time"),
        )
        for (failure in failures) {
            val activeFailure = assertFailsWith<Exception> {
                adapter.seedRxLogEntries(radio) { throw failure }
            }
            assertTrue(activeFailure === failure)
            val cancellation = CancellationException("during RX fixture build")
            val observed = AtomicReference<Throwable?>()
            val attempt = CoroutineScope(currentCoroutineContext()).launch {
                val caller = currentCoroutineContext()
                try {
                    adapter.seedRxLogEntries(radio) {
                        caller.cancel(cancellation)
                        throw failure
                    }
                } catch (caught: Exception) {
                    observed.set(caught)
                }
            }
            attempt.join()
            assertTrue(attempt.isCancelled)
            val propagated = observed.get()
            assertTrue(propagated === cancellation || propagated?.cause === cancellation)
            assertTrue(propagated?.suppressed?.any { it === failure } == true)
        }
        assertEquals(0L, db.rxLogs().count(radio.value))
    }

    @Test fun runtimePropagatesFinalPendingFlushFailureAndRetries() = withStores { stores ->
        val local = stores.single()
        val fixtures = initialFixtures(local)
        val radio = MockDataProvider.simulatorRadioId
        assertEquals(1, db.rxLogs().delete(radio.value, fixtures[0].id))
        val pending = fixtures[0].copy(
            regionScope = "pending-runtime-value",
            regionScopeMatches = SnapshotList.of("pending-runtime-value"),
        )
        val adapter = RoomSimulatorSeedStore(local)
        var inject = true
        val injecting = object : SimulatorSeedStore by adapter {
            override suspend fun seedRxLogEntries(
                radioId: RadioId,
                buildEntries: () -> SnapshotList<RxLogEntryDTO>,
            ) {
                if (inject) {
                    local.saveRxLogEntry(pending)
                    inject = false
                }
                adapter.seedRxLogEntries(radioId, buildEntries)
            }
        }
        var finishCalls = 0
        val link = SimulatorRuntimeLink(
            injecting,
            finishSeed = {
                finishCalls += 1
                local.flushPendingRxLogEntries()
            },
            mode = SimulatorConnectionMode(
                clock = Clock.fixed(LATER, ZoneOffset.UTC),
                connectDelay = 0.milliseconds,
            ),
        )
        val id = pending.id.toString().uppercase(java.util.Locale.ROOT)
        link.connectSimulator()
        try {
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_runtime_pending BEFORE INSERT ON rx_log_entries " +
                    "WHEN NEW.id = '$id' BEGIN SELECT RAISE(ABORT, 'runtime RX flush rejected'); END",
            )
            try {
                val failure = assertFailsWith<PersistenceStoreException> { link.seedSimulator() }
                assertTrue(failure.cause?.message?.contains("runtime RX flush rejected") == true)
                assertEquals(1, finishCalls)
                assertNull(db.rxLogs().byId(radio.value, pending.id))
                assertNotNull(db.rxLogs().byId(radio.value, fixtures[1].id))
                assertEquals(pending, local.fetchRxLogEntries(radio, 10).single { it.id == pending.id })
            } finally {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_runtime_pending")
            }
            link.seedSimulator()
            assertEquals(2, finishCalls)
            assertEquals("pending-runtime-value", db.rxLogs().byId(radio.value, pending.id)?.regionScope)
            assertEquals(2L, db.rxLogs().count(radio.value))
        } finally {
            link.transport.disconnect()
        }
    }

    @Test fun runtimeReportsLaterNodeStatusFailureAfterRxBatchCommitAndRetries() = withStores { stores ->
        val local = stores.single()
        val fixtures = initialFixtures(local)
        val radio = MockDataProvider.simulatorRadioId
        assertEquals(1, db.rxLogs().delete(radio.value, fixtures[0].id))
        assertTrue(db.nodeSnapshots().backupAll().isNotEmpty())
        db.openHelper.writableDatabase.execSQL("DELETE FROM node_status_snapshots")
        val pending = extraRow(fixtures[1], 10_000, "committed-before-node-failure")
        val adapter = RoomSimulatorSeedStore(local)
        var inject = true
        val injecting = object : SimulatorSeedStore by adapter {
            override suspend fun seedRxLogEntries(
                radioId: RadioId,
                buildEntries: () -> SnapshotList<RxLogEntryDTO>,
            ) {
                if (inject) {
                    local.saveRxLogEntry(pending)
                    inject = false
                }
                adapter.seedRxLogEntries(radioId, buildEntries)
                assertEquals(pending.toEntity(), db.rxLogs().byId(radio.value, pending.id))
                assertNotNull(db.rxLogs().byId(radio.value, fixtures[0].id))
            }
        }
        var finishCalls = 0
        val link = SimulatorRuntimeLink(
            injecting,
            finishSeed = {
                finishCalls += 1
                local.flushPendingRxLogEntries()
            },
            mode = SimulatorConnectionMode(
                clock = Clock.fixed(LATER, ZoneOffset.UTC),
                connectDelay = 0.milliseconds,
            ),
        )
        link.connectSimulator()
        try {
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_node_history BEFORE INSERT ON node_status_snapshots " +
                    "BEGIN SELECT RAISE(ABORT, 'node history rejected'); END",
            )
            try {
                val failure = assertFailsWith<PersistenceStoreException> { link.seedSimulator() }
                assertTrue(failure.error is PersistenceStoreError.SaveFailed)
                assertTrue(failure.cause?.message?.contains("node history rejected") == true)
                assertEquals(0, finishCalls)
                assertTrue(db.nodeSnapshots().backupAll().isEmpty())
                assertEquals(pending.toEntity(), db.rxLogs().byId(radio.value, pending.id))
                for (fixture in fixtures) assertNotNull(db.rxLogs().byId(radio.value, fixture.id))
            } finally {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_node_history")
            }
            val committedRx = storedRxSnapshot()
            link.seedSimulator()
            assertEquals(1, finishCalls)
            assertEquals(committedRx, storedRxSnapshot())
            assertTrue(db.nodeSnapshots().backupAll().isNotEmpty())
        } finally {
            link.transport.disconnect()
        }
    }

    @Test fun runtimeCancellationAfterRxBatchCommitDoesNotFinishAndRetries() = withStores { stores ->
        val local = stores.single()
        val fixtures = initialFixtures(local)
        val radio = MockDataProvider.simulatorRadioId
        assertEquals(1, db.rxLogs().delete(radio.value, fixtures[0].id))
        val pending = extraRow(fixtures[1], 10_000, "committed-before-node-cancellation")
        val cancellation = CancellationException("after simulator RX batch, before node history")
        val observed = AtomicReference<Throwable?>()
        val adapter = RoomSimulatorSeedStore(local)
        var inject = true
        var cancelAtNode = true
        var batchReturned = false
        val injecting = object : SimulatorSeedStore by adapter {
            override suspend fun seedRxLogEntries(
                radioId: RadioId,
                buildEntries: () -> SnapshotList<RxLogEntryDTO>,
            ) {
                if (inject) {
                    local.saveRxLogEntry(pending)
                    inject = false
                }
                adapter.seedRxLogEntries(radioId, buildEntries)
                assertEquals(pending.toEntity(), db.rxLogs().byId(radio.value, pending.id))
                assertNotNull(db.rxLogs().byId(radio.value, fixtures[0].id))
                batchReturned = true
            }

            override suspend fun fetchLatestNodeStatusSnapshot(nodePublicKey: Bytes): NodeStatusSnapshotDTO? {
                if (cancelAtNode) {
                    assertTrue(batchReturned)
                    cancelAtNode = false
                    currentCoroutineContext().cancel(cancellation)
                    throw cancellation
                }
                return adapter.fetchLatestNodeStatusSnapshot(nodePublicKey)
            }
        }
        var finishCalls = 0
        val link = SimulatorRuntimeLink(
            injecting,
            finishSeed = {
                finishCalls += 1
                local.flushPendingRxLogEntries()
            },
            mode = SimulatorConnectionMode(
                clock = Clock.fixed(LATER, ZoneOffset.UTC),
                connectDelay = 0.milliseconds,
            ),
        )
        link.connectSimulator()
        try {
            val attempt = CoroutineScope(currentCoroutineContext()).launch {
                try {
                    link.seedSimulator()
                } catch (failure: CancellationException) {
                    observed.set(failure)
                }
            }
            attempt.join()
            assertTrue(attempt.isCancelled)
            assertTrue(observed.get() === cancellation || observed.get()?.cause === cancellation)
            assertTrue(batchReturned)
            assertEquals(0, finishCalls)
            assertEquals(pending.toEntity(), db.rxLogs().byId(radio.value, pending.id))
            for (fixture in fixtures) assertNotNull(db.rxLogs().byId(radio.value, fixture.id))
            val committedRx = storedRxSnapshot()

            link.seedSimulator()
            assertEquals(1, finishCalls)
            assertEquals(committedRx, storedRxSnapshot())
        } finally {
            link.transport.disconnect()
        }
    }

    @Test fun runtimePrunesPendingRadioAfterInsertingSimulatorFixture() =
        assertRuntimePruning(RetentionDisposition.INSERTED)

    @Test fun runtimePrunesPendingRadioAfterPreservingStoredSimulatorFixture() =
        assertRuntimePruning(RetentionDisposition.PRESERVED_STORED)

    @Test fun runtimePrunesPendingRadioAfterPreservingPendingSimulatorFixture() =
        assertRuntimePruning(RetentionDisposition.PRESERVED_PENDING)

    private fun assertRuntimePruning(disposition: RetentionDisposition) = withStores { stores ->
        val local = stores.single()
        val fixtures = initialFixtures(local)
        val radio = MockDataProvider.simulatorRadioId
        val otherRadio = RadioId(UUID.fromString("00000000-0000-0000-0000-0000000000FF"))
        val threshold = RxLogRetention.KEEP_COUNT + RxLogRetention.PRUNE_THRESHOLD
        val storedFixtures = if (disposition == RetentionDisposition.PRESERVED_STORED) {
            fixtures
        } else {
            assertEquals(1, db.rxLogs().delete(radio.value, fixtures[0].id))
            fixtures.drop(1)
        }
        val initialCount = if (disposition == RetentionDisposition.INSERTED) threshold - 1 else threshold
        val ownRows = (1..(initialCount - storedFixtures.size).toInt()).map { extraRow(fixtures[0], it, "own") }
        val otherRows = (1..threshold.toInt()).map { extraRow(fixtures[0], it, "other").copy(radioId = otherRadio) }
        db.rxLogs().upsert((ownRows + otherRows).map { it.toEntity() })
        assertEquals(initialCount, db.rxLogs().count(radio.value))
        assertEquals(threshold, db.rxLogs().count(otherRadio.value))

        val pending = if (disposition == RetentionDisposition.PRESERVED_PENDING) {
            fixtures[0].copy(
                receivedAt = FIRST.plusSeconds(10_000),
                regionScope = "own-pending",
                regionScopeMatches = SnapshotList.of("own-pending"),
            )
        } else {
            extraRow(fixtures[0], 10_000, "own-pending")
        }
        val otherPending = extraRow(fixtures[0], 10_000, "other-pending").copy(radioId = otherRadio)
        val adapter = RoomSimulatorSeedStore(local)
        var ownExpected: Map<UUID, RxLogEntryDTO>? = null
        var otherExpected: Map<UUID, RxLogEntryDTO>? = null
        val injecting = object : SimulatorSeedStore by adapter {
            override suspend fun seedRxLogEntries(
                radioId: RadioId,
                buildEntries: () -> SnapshotList<RxLogEntryDTO>,
            ) {
                local.saveRxLogEntry(otherPending)
                local.saveRxLogEntry(pending)
                val own = local.fetchRxLogEntries(radio, threshold + 1).toList()
                val other = local.fetchRxLogEntries(otherRadio, threshold + 1).toList()
                assertEquals(if (disposition == RetentionDisposition.INSERTED) threshold else threshold + 1,
                    own.size.toLong())
                assertEquals(threshold + 1, other.size.toLong())
                adapter.seedRxLogEntries(radioId) {
                    buildEntries().also { entries ->
                        val rows = if (disposition == RetentionDisposition.INSERTED) {
                            own + entries.single { it.id == fixtures[0].id }.toEntity().toDTO()
                        } else {
                            own
                        }
                        ownExpected = rows.sortedByDescending { it.receivedAt }
                            .take(RxLogRetention.KEEP_COUNT.toInt()).associateBy { it.id }
                        otherExpected = other.sortedByDescending { it.receivedAt }
                            .take(RxLogRetention.KEEP_COUNT.toInt()).associateBy { it.id }
                    }
                }
                if (disposition == RetentionDisposition.INSERTED) {
                    assertEquals(RxLogRetention.KEEP_COUNT, db.rxLogs().count(radio.value))
                    assertEquals(RxLogRetention.KEEP_COUNT, db.rxLogs().count(otherRadio.value))
                    assertEquals(pending.toEntity(), db.rxLogs().byId(radio.value, pending.id))
                    assertEquals(otherPending.toEntity(), db.rxLogs().byId(otherRadio.value, otherPending.id))
                } else {
                    assertEquals(threshold, db.rxLogs().count(radio.value))
                    assertEquals(threshold, db.rxLogs().count(otherRadio.value))
                    assertNull(db.rxLogs().byId(radio.value, pending.id))
                    assertNull(db.rxLogs().byId(otherRadio.value, otherPending.id))
                }
                when (disposition) {
                    RetentionDisposition.INSERTED -> assertNotNull(db.rxLogs().byId(radio.value, fixtures[0].id))
                    RetentionDisposition.PRESERVED_STORED ->
                        assertEquals(fixtures[0].toEntity(), db.rxLogs().byId(radio.value, fixtures[0].id))
                    RetentionDisposition.PRESERVED_PENDING ->
                        assertEquals(pending, local.fetchRxLogEntries(radio, threshold + 1).single { it.id == pending.id })
                }
            }
        }
        var finishCalls = 0
        val link = SimulatorRuntimeLink(
            injecting,
            finishSeed = {
                finishCalls += 1
                local.flushPendingRxLogEntries()
            },
            mode = SimulatorConnectionMode(
                clock = Clock.fixed(LATER, ZoneOffset.UTC),
                connectDelay = 0.milliseconds,
            ),
        )
        link.connectSimulator()
        try {
            link.seedSimulator()
            assertEquals(1, finishCalls)
            assertEquals(RxLogRetention.KEEP_COUNT, db.rxLogs().count(otherRadio.value))
            assertEquals(RxLogRetention.KEEP_COUNT, db.rxLogs().count(radio.value))
            assertEquals(assertNotNull(ownExpected), local.fetchRxLogEntries(radio, threshold + 1).associateBy { it.id })
            assertEquals(assertNotNull(otherExpected), local.fetchRxLogEntries(otherRadio, threshold + 1).associateBy { it.id })
            assertEquals(pending.toEntity(), db.rxLogs().byId(radio.value, pending.id))
            assertEquals(otherPending.toEntity(), db.rxLogs().byId(otherRadio.value, otherPending.id))
        } finally {
            link.transport.disconnect()
        }
    }

    private enum class RetentionDisposition { INSERTED, PRESERVED_STORED, PRESERVED_PENDING }

    @Test fun runtimePrunesAfterInsertingSimulatorFixtureWithoutPending() =
        assertRuntimeSeedOnlyPruning(retryInNewContext = false)

    @Test fun runtimeRetryInNewContextPrunesAfterFailedAtomicSeed() =
        assertRuntimeSeedOnlyPruning(retryInNewContext = true)

    private fun assertRuntimeSeedOnlyPruning(retryInNewContext: Boolean) =
        withStores(count = if (retryInNewContext) 2 else 1) { stores ->
            val local = stores[0]
            val fixtures = initialFixtures(local)
            val radio = MockDataProvider.simulatorRadioId
            val otherRadio = RadioId(UUID.fromString("00000000-0000-0000-0000-0000000000FF"))
            val threshold = RxLogRetention.KEEP_COUNT + RxLogRetention.PRUNE_THRESHOLD
            val missing = fixtures[0]
            assertEquals(1, db.rxLogs().delete(radio.value, missing.id))
            val retained = fixtures[1].copy(receivedAt = LATER.plusSeconds(1))
            db.rxLogs().upsert(retained.toEntity())
            val ownRows = (1..(threshold - 1).toInt()).map { extraRow(fixtures[0], it, "own") }
            val otherRows = (1..(threshold + 1).toInt())
                .map { extraRow(fixtures[0], it, "other").copy(radioId = otherRadio) }
            db.rxLogs().upsert((ownRows + otherRows).map { it.toEntity() })
            assertEquals(threshold, db.rxLogs().count(radio.value))
            assertEquals(threshold + 1, db.rxLogs().count(otherRadio.value))
            assertNull(db.rxLogs().byId(radio.value, missing.id))
            val expectedOther = local.fetchRxLogEntries(otherRadio, threshold + 1).associateBy { it.id }
            val initialRows = local.fetchRxLogEntries(radio, threshold).toList()
            val initialSnapshot = storedRxSnapshot()

            var inserted: RxLogEntryDTO? = null
            var afterBatch: Map<UUID, RxLogEntryDTO>? = null
            val adapter = RoomSimulatorSeedStore(local)
            val capturing = object : SimulatorSeedStore by adapter {
                override suspend fun seedRxLogEntries(
                    radioId: RadioId,
                    buildEntries: () -> SnapshotList<RxLogEntryDTO>,
                ) {
                    adapter.seedRxLogEntries(radioId) {
                        buildEntries().also { entries ->
                            assertEquals(listOf(missing.id, retained.id), entries.map { it.id })
                            inserted = entries.first().toEntity().toDTO()
                        }
                    }
                    assertEquals(RxLogRetention.KEEP_COUNT, db.rxLogs().count(radio.value))
                    assertEquals(retained.toEntity(), db.rxLogs().byId(radio.value, retained.id))
                    assertNotNull(db.rxLogs().byId(radio.value, missing.id))
                    afterBatch = local.fetchRxLogEntries(radio, threshold + 1).associateBy { it.id }
                }
            }
            var finishCalls = 0
            val firstLink = SimulatorRuntimeLink(
                capturing,
                finishSeed = {
                    finishCalls += 1
                    local.flushPendingRxLogEntries()
                },
                mode = SimulatorConnectionMode(
                    clock = Clock.fixed(LATER, ZoneOffset.UTC),
                    connectDelay = 0.milliseconds,
                ),
            )
            firstLink.connectSimulator()
            try {
                if (retryInNewContext) {
                    val rawRadio = radio.value.toString().uppercase(java.util.Locale.ROOT)
                    db.openHelper.writableDatabase.execSQL(
                        "CREATE TRIGGER reject_seed_only_prune BEFORE DELETE ON rx_log_entries " +
                            "WHEN OLD.radioId = '$rawRadio' BEGIN SELECT RAISE(ABORT, 'seed-only prune rejected'); END",
                    )
                    try {
                        val failure = assertFailsWith<PersistenceStoreException> { firstLink.seedSimulator() }
                        assertTrue(failure.error is PersistenceStoreError.SaveFailed)
                        assertTrue(failure.cause?.message?.contains("seed-only prune rejected") == true)
                        assertEquals(0, finishCalls)
                        assertEquals(threshold, db.rxLogs().count(radio.value))
                        assertNull(db.rxLogs().byId(radio.value, missing.id))
                        assertNull(afterBatch)
                        assertEquals(initialSnapshot, storedRxSnapshot())
                    } finally {
                        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_seed_only_prune")
                    }
                } else {
                    firstLink.seedSimulator()
                    assertEquals(1, finishCalls)
                }
            } finally {
                firstLink.transport.disconnect()
            }

            val expected = (initialRows + assertNotNull(inserted)).sortedByDescending { it.receivedAt }
                .take(RxLogRetention.KEEP_COUNT.toInt()).associateBy { it.id }
            if (!retryInNewContext) assertEquals(expected, afterBatch)
            if (retryInNewContext) {
                var retryFinishCalls = 0
                val retry = SimulatorRuntimeLink(
                    RoomSimulatorSeedStore(stores[1]),
                    finishSeed = {
                        retryFinishCalls += 1
                        stores[1].flushPendingRxLogEntries()
                    },
                    mode = SimulatorConnectionMode(
                        clock = Clock.fixed(LATER, ZoneOffset.UTC),
                        connectDelay = 0.milliseconds,
                    ),
                )
                retry.connectSimulator()
                try {
                    retry.seedSimulator()
                    assertEquals(1, retryFinishCalls)
                } finally {
                    retry.transport.disconnect()
                }
            }
            assertEquals(RxLogRetention.KEEP_COUNT, db.rxLogs().count(radio.value))
            assertEquals(expected, local.fetchRxLogEntries(radio, threshold + 1).associateBy { it.id })
            assertEquals(threshold + 1, db.rxLogs().count(otherRadio.value))
            assertEquals(expectedOther, local.fetchRxLogEntries(otherRadio, threshold + 1).associateBy { it.id })
        }

    @Test fun runtimeBatchMatchesGenericPendingAndInsertRetention() =
        assertRuntimeBatchWithPending(rejectSurvivorMutation = false)

    @Test fun runtimeBatchRejectsSurvivorMutationAndRetries() =
        assertRuntimeBatchWithPending(rejectSurvivorMutation = true)

    private fun assertRuntimeBatchWithPending(rejectSurvivorMutation: Boolean) = withStores(2) { stores ->
        val local = stores[0]
        val baseline = stores[1]
        val fixtures = initialFixtures(local)
        val radio = MockDataProvider.simulatorRadioId
        val baselineRadio = RadioId(UUID.fromString("00000000-0000-0000-0000-0000000000FF"))
        val threshold = RxLogRetention.KEEP_COUNT + RxLogRetention.PRUNE_THRESHOLD
        val missing = fixtures[0]
        assertEquals(1, db.rxLogs().delete(radio.value, missing.id))
        val ownRows = (1..(threshold - 1).toInt()).map { extraRow(missing, it, "batch-baseline") }
        db.rxLogs().upsert(ownRows.map { it.toEntity() })
        val initialRows = local.fetchRxLogEntries(radio, threshold).toList()
        assertEquals(threshold, initialRows.size.toLong())
        db.rxLogs().upsert(initialRows.map { it.copy(radioId = baselineRadio).toEntity() })
        val initialSnapshot = storedRxSnapshot()
        val pending = fixtures[1].copy(
            receivedAt = LATER.plusSeconds(1),
            regionScope = "pending-fixture-update",
            regionScopeMatches = SnapshotList.of("pending-fixture-update"),
        )
        val adapter = RoomSimulatorSeedStore(local)
        var inject = true
        var attempts = 0
        var inserted: RxLogEntryDTO? = null
        var builtEntries: SnapshotList<RxLogEntryDTO>? = null
        var afterBatch: Map<UUID, RxLogEntryDTO>? = null
        val injecting = object : SimulatorSeedStore by adapter {
            override suspend fun seedRxLogEntries(
                radioId: RadioId,
                buildEntries: () -> SnapshotList<RxLogEntryDTO>,
            ) {
                if (inject) {
                    local.saveRxLogEntry(pending)
                    inject = false
                }
                attempts += 1
                assertEquals(pending, local.fetchRxLogEntries(radio, threshold + 1).single { it.id == pending.id })
                adapter.seedRxLogEntries(radioId) {
                    buildEntries().also { entries ->
                        assertEquals(listOf(missing.id, pending.id), entries.map { it.id })
                        builtEntries = entries
                        inserted = entries.first().toEntity().toDTO()
                    }
                }
                assertEquals(RxLogRetention.KEEP_COUNT, db.rxLogs().count(radio.value))
                assertEquals(pending.toEntity(), db.rxLogs().byId(radio.value, pending.id))
                afterBatch = local.fetchRxLogEntries(radio, threshold + 1).associateBy { it.id }
            }
        }
        var finishCalls = 0
        val link = SimulatorRuntimeLink(
            injecting,
            finishSeed = {
                finishCalls += 1
                local.flushPendingRxLogEntries()
            },
            mode = SimulatorConnectionMode(
                clock = Clock.fixed(LATER, ZoneOffset.UTC),
                connectDelay = 0.milliseconds,
            ),
        )
        link.connectSimulator()
        try {
            if (rejectSurvivorMutation) {
                val rawRadio = radio.value.toString().uppercase(java.util.Locale.ROOT)
                val fixtureID = missing.id.toString().uppercase(java.util.Locale.ROOT)
                val survivorID = ownRows.last().id.toString().uppercase(java.util.Locale.ROOT)
                db.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER alter_seed_survivor AFTER INSERT ON rx_log_entries " +
                        "WHEN NEW.id = '$fixtureID' BEGIN " +
                        "UPDATE rx_log_entries SET regionScope = 'trigger mutation' " +
                        "WHERE radioId = '$rawRadio' AND id = '$survivorID'; END",
                )
                try {
                    val failure = assertFailsWith<PersistenceStoreException> { link.seedSimulator() }
                    assertTrue(failure.error is PersistenceStoreError.SaveFailed)
                    assertEquals(0, finishCalls)
                    assertEquals(1, attempts)
                    assertNull(afterBatch)
                    assertNull(db.rxLogs().byId(radio.value, missing.id))
                    assertEquals(initialSnapshot, storedRxSnapshot())
                    assertEquals(pending, local.fetchRxLogEntries(radio, threshold + 1).single { it.id == pending.id })
                    assertEquals(fixtures[1].toEntity(), db.rxLogs().byId(radio.value, pending.id))
                } finally {
                    db.openHelper.writableDatabase.execSQL("DROP TRIGGER alter_seed_survivor")
                }
                injecting.seedRxLogEntries(radio) { assertNotNull(builtEntries) }
                assertEquals(2, attempts)
                assertEquals(pending.toEntity(), db.rxLogs().byId(radio.value, pending.id))
            }
            link.seedSimulator()
            assertEquals(if (rejectSurvivorMutation) 3 else 1, attempts)
            assertEquals(1, finishCalls)
        } finally {
            link.transport.disconnect()
        }

        val insertedRow = assertNotNull(inserted)
        baseline.saveRxLogEntry(pending.copy(radioId = baselineRadio))
        baseline.saveRxLogEntry(insertedRow.copy(radioId = baselineRadio))
        baseline.flushPendingRxLogEntries()
        assertEquals(RxLogRetention.KEEP_COUNT, db.rxLogs().count(baselineRadio.value))
        val expected = baseline.fetchRxLogEntries(baselineRadio, threshold + 1)
            .map { it.copy(radioId = radio) }.associateBy { it.id }
        assertEquals(RxLogRetention.KEEP_COUNT, db.rxLogs().count(radio.value))
        assertEquals(expected, afterBatch)
        assertEquals(expected, local.fetchRxLogEntries(radio, threshold + 1).associateBy { it.id })

        val earlyRows = (initialRows + insertedRow).sortedByDescending { it.receivedAt }
            .take(RxLogRetention.KEEP_COUNT.toInt())
        assertFalse(earlyRows.any { it.id == pending.id })
        val earlyThenPending = (earlyRows + pending).associateBy { it.id }
        assertEquals(RxLogRetention.KEEP_COUNT + 1, earlyThenPending.size.toLong())
        assertEquals(expected, earlyThenPending.filterKeys { it in expected })
        assertEquals(1, (earlyThenPending.keys - expected.keys).size)
    }

    @Test fun runtimePreservationOnlyDoesNotPruneAnOversizedRadio() = withStores { stores ->
        val local = stores.single()
        val fixtures = initialFixtures(local)
        val radio = MockDataProvider.simulatorRadioId
        val threshold = RxLogRetention.KEEP_COUNT + RxLogRetention.PRUNE_THRESHOLD
        val rows = (1..(threshold - 1).toInt()).map { extraRow(fixtures[0], it, "preserved") }
        db.rxLogs().upsert(rows.map { it.toEntity() })
        assertEquals(threshold + 1, db.rxLogs().count(radio.value))
        val before = storedRxSnapshot()
        val link = SimulatorRuntimeLink(
            RoomSimulatorSeedStore(local),
            finishSeed = { local.flushPendingRxLogEntries() },
            mode = SimulatorConnectionMode(
                clock = Clock.fixed(LATER, ZoneOffset.UTC),
                connectDelay = 0.milliseconds,
            ),
        )
        link.connectSimulator()
        try {
            link.seedSimulator()
            assertEquals(before, storedRxSnapshot())
            assertEquals(threshold + 1, db.rxLogs().count(radio.value))
        } finally {
            link.transport.disconnect()
        }
    }

    @Test fun runtimeRejectsUnrelatedPendingAliasBeforePreservingFixtures() =
        assertRuntimeRejectsUnrelatedPendingAlias(missingFixture = false)

    @Test fun runtimeRejectsUnrelatedPendingAliasBeforeInsertingFixture() =
        assertRuntimeRejectsUnrelatedPendingAlias(missingFixture = true)

    private fun assertRuntimeRejectsUnrelatedPendingAlias(missingFixture: Boolean) = withStores { stores ->
        val local = stores.single()
        val fixtures = initialFixtures(local)
        val radio = MockDataProvider.simulatorRadioId
        if (missingFixture) assertEquals(1, db.rxLogs().delete(radio.value, fixtures[0].id))
        val stored = extraRow(fixtures[0], 10, "raw-alias")
        db.rxLogs().insert(stored.toEntity())
        val canonical = stored.id.toString().uppercase(java.util.Locale.ROOT)
        val alias = canonical.lowercase(java.util.Locale.ROOT)
        assertTrue(canonical != alias)
        val pending = stored.copy(
            regionScope = "pending-alias-update",
            regionScopeMatches = SnapshotList.of("pending-alias-update"),
        )
        val adapter = RoomSimulatorSeedStore(local)
        var inject = true
        var before: List<List<Pair<Int, Any?>>>? = null
        val injecting = object : SimulatorSeedStore by adapter {
            override suspend fun seedRxLogEntries(
                radioId: RadioId,
                buildEntries: () -> SnapshotList<RxLogEntryDTO>,
            ) {
                if (inject) {
                    local.saveRxLogEntry(pending)
                    db.openHelper.writableDatabase.execSQL(
                        "UPDATE rx_log_entries SET id = lower(id) WHERE id = '$canonical'",
                    )
                    before = storedRxSnapshot()
                    inject = false
                }
                adapter.seedRxLogEntries(radioId, buildEntries)
            }
        }
        var finishCalls = 0
        val link = SimulatorRuntimeLink(
            injecting,
            finishSeed = {
                finishCalls += 1
                local.flushPendingRxLogEntries()
            },
            mode = SimulatorConnectionMode(
                clock = Clock.fixed(LATER, ZoneOffset.UTC),
                connectDelay = 0.milliseconds,
            ),
        )
        link.connectSimulator()
        try {
            val failure = assertFailsWith<PersistenceStoreException> { link.seedSimulator() }
            val retry = assertFailsWith<PersistenceStoreException> {
                adapter.seedRxLogEntries(radio) { SnapshotList(fixtures) }
            }
            for (refusal in listOf(failure, retry)) {
                assertEquals(PersistenceStoreError.InvalidData, refusal.error)
                assertEquals(
                    "RX log UUID case aliases require explicit data repair; automatic overwrite or normalization is refused",
                    refusal.cause?.message,
                )
            }
            assertEquals(0, finishCalls)
            assertEquals(before, storedRxSnapshot())
            if (missingFixture) assertNull(db.rxLogs().byId(radio.value, fixtures[0].id))
            db.openHelper.writableDatabase.execSQL("UPDATE rx_log_entries SET id = upper(id) WHERE id = '$alias'")
            assertEquals(pending, local.fetchRxLogEntries(radio, 10).single { it.id == pending.id })
            link.seedSimulator()
            assertEquals(1, finishCalls)
            assertEquals(1L, db.rxLogs().countSimulatorIdentity(radio.value, pending.id))
            assertEquals(pending.toEntity(), db.rxLogs().byId(radio.value, pending.id))
            for (fixture in fixtures) assertNotNull(db.rxLogs().byId(radio.value, fixture.id))
        } finally {
            link.transport.disconnect()
            db.openHelper.writableDatabase.execSQL("UPDATE rx_log_entries SET id = upper(id) WHERE id = '$alias'")
            local.clearRxLogEntries(radio)
        }
    }

    @Test fun runtimeBatchReportsExistingFixtureEvictedByRetention() = withStores(2) { stores ->
        val local = stores[0]
        val control = stores[1]
        val fixtures = initialFixtures(local)
        val radio = MockDataProvider.simulatorRadioId
        val controlRadio = RadioId(UUID.fromString("00000000-0000-0000-0000-0000000000FF"))
        val threshold = RxLogRetention.KEEP_COUNT + RxLogRetention.PRUNE_THRESHOLD
        val missing = fixtures[0]
        val stored = fixtures[1]
        assertEquals(1, db.rxLogs().delete(radio.value, missing.id))
        val newerRows = (1..(threshold - 1).toInt()).map { extraRow(missing, it, "fixture-eviction") }
        db.rxLogs().upsert(newerRows.map { it.toEntity() })
        val initialRows = local.fetchRxLogEntries(radio, threshold).toList()
        assertEquals(threshold, initialRows.size.toLong())
        db.rxLogs().upsert(initialRows.map { it.copy(radioId = controlRadio).toEntity() })

        val adapter = RoomSimulatorSeedStore(local)
        val observing = object : SimulatorSeedStore by adapter {
            override suspend fun seedRxLogEntries(
                radioId: RadioId,
                buildEntries: () -> SnapshotList<RxLogEntryDTO>,
            ) {
                val entries = buildEntries()
                assertEquals(listOf(missing.id, stored.id), entries.map { it.id })
                val controlEntries = SnapshotList(entries.map { it.copy(radioId = controlRadio) })
                val outcomes = control.seedSimulatorRxBatchIfAbsent(controlEntries)
                assertEquals(listOf(missing.id, stored.id), outcomes.map { it.id })
                assertEquals(SimulatorRxSeedResult.INSERTED, outcomes[0].decision)
                assertEquals(SimulatorRxSeedPresence.STORED, outcomes[0].presence)
                assertEquals(SimulatorRxSeedResult.PRESERVED_STORED, outcomes[1].decision)
                assertEquals(SimulatorRxSeedPresence.PRUNED, outcomes[1].presence)
                adapter.seedRxLogEntries(radioId) { entries }
            }
        }
        val link = SimulatorRuntimeLink(
            observing,
            finishSeed = { local.flushPendingRxLogEntries() },
            mode = SimulatorConnectionMode(
                clock = Clock.fixed(LATER, ZoneOffset.UTC),
                connectDelay = 0.milliseconds,
            ),
        )
        link.connectSimulator()
        try {
            link.seedSimulator()
            assertEquals(RxLogRetention.KEEP_COUNT, db.rxLogs().count(radio.value))
            assertEquals(RxLogRetention.KEEP_COUNT, db.rxLogs().count(controlRadio.value))
            assertNotNull(db.rxLogs().byId(radio.value, missing.id))
            assertNull(db.rxLogs().byId(radio.value, stored.id))
            val expected = control.fetchRxLogEntries(controlRadio, threshold + 1)
                .map { it.copy(radioId = radio) }.associateBy { it.id }
            assertEquals(expected, local.fetchRxLogEntries(radio, threshold + 1).associateBy { it.id })
        } finally {
            link.transport.disconnect()
        }
    }

    @Test fun runtimeAtomicSeedRollbackSurvivesFileReopenAndRetries() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = temporary.root.resolve("simulator-rx-${UUID.randomUUID()}.db").absolutePath
        val immediate = Executor { it.run() }
        fun open() = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        val firstDb = open()
        val firstOwner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(TestCoroutineScheduler()))
        val firstStore = RoomPersistenceStore(firstDb, firstOwner)
        var firstClosed = false
        try {
            val fixtures = initialFixtures(firstStore)
            val radio = MockDataProvider.simulatorRadioId
            val threshold = RxLogRetention.KEEP_COUNT + RxLogRetention.PRUNE_THRESHOLD
            val missing = fixtures[0]
            assertEquals(1, firstDb.rxLogs().delete(radio.value, missing.id))
            val retained = fixtures[1].copy(receivedAt = LATER.plusSeconds(1))
            firstDb.rxLogs().upsert(retained.toEntity())
            val rows = (1..(threshold - 1).toInt()).map { extraRow(missing, it, "reopen") }
            firstDb.rxLogs().upsert(rows.map { it.toEntity() })
            val initialRows = firstStore.fetchRxLogEntries(radio, threshold).toList()
            assertEquals(threshold, initialRows.size.toLong())
            val before = storedRxSnapshot(firstDb)
            var inserted: RxLogEntryDTO? = null
            var finishCalls = 0
            val adapter = RoomSimulatorSeedStore(firstStore)
            val observing = object : SimulatorSeedStore by adapter {
                override suspend fun seedRxLogEntries(
                    radioId: RadioId,
                    buildEntries: () -> SnapshotList<RxLogEntryDTO>,
                ) {
                    adapter.seedRxLogEntries(radioId) {
                        buildEntries().also { entries ->
                            inserted = entries.single { it.id == missing.id }.toEntity().toDTO()
                        }
                    }
                }
            }
            val first = SimulatorRuntimeLink(
                observing,
                finishSeed = {
                    finishCalls += 1
                    firstStore.flushPendingRxLogEntries()
                },
                mode = SimulatorConnectionMode(
                    clock = Clock.fixed(LATER, ZoneOffset.UTC),
                    connectDelay = 0.milliseconds,
                ),
            )
            first.connectSimulator()
            try {
                firstDb.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER reject_reopen_prune BEFORE DELETE ON rx_log_entries " +
                        "BEGIN SELECT RAISE(ABORT, 'reopen prune rejected'); END",
                )
                try {
                    val failure = assertFailsWith<PersistenceStoreException> { first.seedSimulator() }
                    assertTrue(failure.error is PersistenceStoreError.SaveFailed)
                    assertTrue(failure.cause?.message?.contains("reopen prune rejected") == true)
                    assertEquals(0, finishCalls)
                    assertEquals(before, storedRxSnapshot(firstDb))
                    assertNull(firstDb.rxLogs().byId(radio.value, missing.id))
                } finally {
                    firstDb.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_reopen_prune")
                }
            } finally {
                first.transport.disconnect()
            }
            firstStore.close()
            firstDb.close()
            firstOwner.cancel()
            firstClosed = true

            val reopened = open()
            val reopenedOwner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(TestCoroutineScheduler()))
            val reopenedStore = RoomPersistenceStore(reopened, reopenedOwner)
            try {
                assertEquals(before, storedRxSnapshot(reopened))
                val retry = SimulatorRuntimeLink(
                    RoomSimulatorSeedStore(reopenedStore),
                    finishSeed = { reopenedStore.flushPendingRxLogEntries() },
                    mode = SimulatorConnectionMode(
                        clock = Clock.fixed(LATER, ZoneOffset.UTC),
                        connectDelay = 0.milliseconds,
                    ),
                )
                retry.connectSimulator()
                try {
                    retry.seedSimulator()
                } finally {
                    retry.transport.disconnect()
                }
                val expected = (initialRows + assertNotNull(inserted)).sortedByDescending { it.receivedAt }
                    .take(RxLogRetention.KEEP_COUNT.toInt()).associateBy { it.id }
                assertEquals(RxLogRetention.KEEP_COUNT, reopened.rxLogs().count(radio.value))
                assertEquals(expected, reopenedStore.fetchRxLogEntries(radio, threshold + 1).associateBy { it.id })
                assertEquals(retained.toEntity(), reopened.rxLogs().byId(radio.value, retained.id))
            } finally {
                try {
                    reopenedStore.close()
                } finally {
                    reopenedOwner.cancel()
                    reopened.close()
                }
            }
        } finally {
            try {
                if (!firstClosed) firstStore.close()
            } finally {
                if (!firstClosed) {
                    firstOwner.cancel()
                    firstDb.close()
                }
                context.deleteDatabase(name)
            }
        }
    }

    @Test fun mismatchedFixtureRadioFailsBeforeAnyRxInsert() = withStores { stores ->
        val local = stores.single()
        val fixtures = initialFixtures(local)
        val radio = MockDataProvider.simulatorRadioId
        deleteFixtures(fixtures)
        val wrongRadio = RadioId(UUID.fromString("00000000-0000-0000-0000-0000000000FF"))
        val failure = assertFailsWith<PersistenceStoreException> {
            RoomSimulatorSeedStore(local).seedRxLogEntries(radio) {
                SnapshotList.of(fixtures[0], fixtures[1].copy(radioId = wrongRadio))
            }
        }
        assertEquals(PersistenceStoreError.InvalidData, failure.error)
        assertEquals("Simulator RX fixture radio does not match the seed scope", failure.cause?.message)
        assertEquals(0L, db.rxLogs().count(radio.value))
        assertEquals(0L, db.rxLogs().count(wrongRadio.value))
    }

    @Test fun roomRxBatchBoundaryRejectsInvalidIdentitiesBeforeCallingTheStore() = withStores { stores ->
        val fixtures = initialFixtures(stores.single())
        val radio = MockDataProvider.simulatorRadioId
        val before = storedRxSnapshot()
        var calls = 0
        val invalid = listOf(SnapshotList.empty<RxLogEntryDTO>(), SnapshotList.of(fixtures[0], fixtures[0]))
        for (entries in invalid) {
            val failure = assertFailsWith<PersistenceStoreException> {
                seedRoomSimulatorRxEntries(radio, { entries }) {
                    calls += 1
                    SnapshotList.empty()
                }
            }
            assertEquals(PersistenceStoreError.InvalidData, failure.error)
        }
        assertEquals(0, calls)
        assertEquals(before, storedRxSnapshot())
    }

    @Test fun roomRxBatchBoundaryRejectsInconsistentOutcomes() = withStores { stores ->
        val fixtures = initialFixtures(stores.single())
        val radio = MockDataProvider.simulatorRadioId
        val entries = SnapshotList(fixtures)
        val first = fixtures[0].id
        val second = fixtures[1].id
        fun outcome(id: UUID, decision: SimulatorRxSeedResult, presence: SimulatorRxSeedPresence) =
            SimulatorRxSeedOutcome(id = id, decision = decision, presence = presence)
        val inserted = outcome(first, SimulatorRxSeedResult.INSERTED, SimulatorRxSeedPresence.STORED)
        val stored = outcome(second, SimulatorRxSeedResult.PRESERVED_STORED, SimulatorRxSeedPresence.STORED)
        val invalid = listOf(
            SnapshotList.of(inserted),
            SnapshotList.of(stored, inserted),
            SnapshotList.of(outcome(first, SimulatorRxSeedResult.INSERTED, SimulatorRxSeedPresence.PRUNED), stored),
            SnapshotList.of(
                outcome(first, SimulatorRxSeedResult.PRESERVED_STORED, SimulatorRxSeedPresence.PRUNED),
                stored,
            ),
            SnapshotList.of(
                outcome(first, SimulatorRxSeedResult.PRESERVED_PENDING, SimulatorRxSeedPresence.STORED),
                stored,
            ),
            SnapshotList.of(
                inserted,
                outcome(second, SimulatorRxSeedResult.PRESERVED_PENDING, SimulatorRxSeedPresence.PENDING),
            ),
        )
        val before = storedRxSnapshot()
        var calls = 0
        for (outcomes in invalid) {
            val failure = assertFailsWith<PersistenceStoreException> {
                seedRoomSimulatorRxEntries(radio, { entries }) { batch ->
                    calls += 1
                    assertEquals(entries, batch)
                    outcomes
                }
            }
            assertEquals(PersistenceStoreError.SaveFailed("Simulator RX batch returned inconsistent outcomes"), failure.error)
        }
        assertEquals(invalid.size, calls)
        assertEquals(before, storedRxSnapshot())
    }

    @Test fun roomRxBatchBoundaryAcceptsTruthfulPendingAndPrunedOutcomes() = withStores { stores ->
        val fixtures = initialFixtures(stores.single())
        val radio = MockDataProvider.simulatorRadioId
        val entries = SnapshotList(fixtures)
        val first = fixtures[0].id
        val second = fixtures[1].id
        fun outcome(id: UUID, decision: SimulatorRxSeedResult, presence: SimulatorRxSeedPresence) =
            SimulatorRxSeedOutcome(id = id, decision = decision, presence = presence)
        val valid = listOf(
            SnapshotList.of(
                outcome(first, SimulatorRxSeedResult.INSERTED, SimulatorRxSeedPresence.STORED),
                outcome(second, SimulatorRxSeedResult.PRESERVED_STORED, SimulatorRxSeedPresence.PRUNED),
            ),
            SnapshotList.of(
                outcome(first, SimulatorRxSeedResult.INSERTED, SimulatorRxSeedPresence.STORED),
                outcome(second, SimulatorRxSeedResult.PRESERVED_PENDING, SimulatorRxSeedPresence.STORED),
            ),
            SnapshotList.of(
                outcome(first, SimulatorRxSeedResult.INSERTED, SimulatorRxSeedPresence.STORED),
                outcome(second, SimulatorRxSeedResult.PRESERVED_PENDING, SimulatorRxSeedPresence.PRUNED),
            ),
            SnapshotList.of(
                outcome(first, SimulatorRxSeedResult.PRESERVED_PENDING, SimulatorRxSeedPresence.PENDING),
                outcome(second, SimulatorRxSeedResult.PRESERVED_STORED, SimulatorRxSeedPresence.STORED),
            ),
        )
        val before = storedRxSnapshot()
        var calls = 0
        for (outcomes in valid) {
            seedRoomSimulatorRxEntries(radio, { entries }) { batch ->
                calls += 1
                assertEquals(entries, batch)
                outcomes
            }
        }
        assertEquals(valid.size, calls)
        assertEquals(before, storedRxSnapshot())
    }

    @Test fun concurrentIndependentStoresCommitEachAbsentFixtureOnce() = withStores(2) { stores ->
        val fixtures = initialFixtures(stores[0])
        val radio = MockDataProvider.simulatorRadioId
        deleteFixtures(fixtures)
        val arrived = AtomicInteger()
        val firstReady = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        fun gated(local: RoomPersistenceStore): SimulatorSeedStore {
            val adapter = RoomSimulatorSeedStore(local)
            return object : SimulatorSeedStore by adapter {
                override suspend fun seedRxLogEntries(
                    radioId: RadioId,
                    buildEntries: () -> SnapshotList<RxLogEntryDTO>,
                ) {
                    if (arrived.incrementAndGet() == 1) firstReady.complete(Unit) else ready.complete(Unit)
                    withTimeout(10_000) { ready.await() }
                    adapter.seedRxLogEntries(radioId, buildEntries)
                }
            }
        }

        coroutineScope {
            val first = async(Dispatchers.Default) { seedAt(LATER, gated(stores[0])) }
            withTimeout(10_000) { firstReady.await() }
            val second = async(Dispatchers.Default) { seedAt(LATER.plusSeconds(600), gated(stores[1])) }
            first.await()
            second.await()
        }

        assertEquals(2L, db.rxLogs().count(radio.value))
        val after = stores[0].fetchRxLogEntries(radio, 10).associateBy { it.id }
        assertEquals(fixtures.map { it.id }.toSet(), after.keys)
        for (fixture in fixtures) {
            val observed = assertNotNull(after[fixture.id])
            val firstTime = fixture.receivedAt.plusSeconds(LATER.epochSecond - FIRST.epochSecond)
            assertTrue(observed.receivedAt == firstTime || observed.receivedAt == firstTime.plusSeconds(600))
        }
        stores.forEach { it.flushPendingRxLogEntries() }
        assertEquals(after, stores[0].fetchRxLogEntries(radio, 10).associateBy { it.id })
        assertEquals(2L, db.rxLogs().count(radio.value))
    }

    @Test fun anotherStoresPendingRowRetainsItsOrdinaryLaterUpsert() = withStores(2) { stores ->
        val fixtures = initialFixtures(stores[0])
        val radio = MockDataProvider.simulatorRadioId
        deleteFixtures(fixtures)
        val pending = fixtures[0].copy(
            regionScope = "other-context-pending",
            regionScopeMatches = SnapshotList.of("other-context-pending"),
        )
        stores[0].saveRxLogEntry(pending)

        seedAt(LATER, RoomSimulatorSeedStore(stores[1]))

        val inserted = assertNotNull(db.rxLogs().byId(radio.value, pending.id))
        assertEquals(fixtures[0].regionScope, inserted.regionScope)
        assertEquals(pending, stores[0].fetchRxLogEntries(radio, 10).single { it.id == pending.id })
        stores[0].flushPendingRxLogEntries()
        assertEquals(pending, stores[1].fetchRxLogEntries(radio, 10).single { it.id == pending.id })
        assertEquals(2L, db.rxLogs().count(radio.value))
    }

    private suspend fun initialFixtures(local: RoomPersistenceStore): List<RxLogEntryDTO> {
        seedAt(FIRST, RoomSimulatorSeedStore(local))
        local.flushPendingRxLogEntries()
        val fixtures = local.fetchRxLogEntries(MockDataProvider.simulatorRadioId, 10).sortedBy { it.receivedAt }
        assertEquals(2, fixtures.size)
        return fixtures
    }

    private suspend fun addNewerRows(local: RoomPersistenceStore, fixtures: List<RxLogEntryDTO>) {
        repeat(501) { index -> local.saveRxLogEntry(extraRow(fixtures[0], index + 1, "newer")) }
        local.flushPendingRxLogEntries()
        val radio = MockDataProvider.simulatorRadioId
        assertEquals(503L, local.countRxLogEntries(radio))
        val newest = local.fetchRxLogEntries(radio, 500).map { it.id }.toSet()
        assertTrue(fixtures.none { it.id in newest })
    }

    private suspend fun deleteFixtures(fixtures: List<RxLogEntryDTO>) {
        for (fixture in fixtures) {
            assertEquals(1, db.rxLogs().delete(fixture.radioId.value, fixture.id))
        }
    }

    private fun storedRxIDs(): Set<String> = db.openHelper.writableDatabase.query(
        "SELECT id FROM rx_log_entries",
    ).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private fun storedRxProjection(): List<Triple<String, String?, Long>> = db.openHelper.writableDatabase.query(
        "SELECT id, regionScope, receivedAt_seconds FROM rx_log_entries ORDER BY id",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(Triple(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
            }
        }
    }

    private fun storedRxSnapshot(database: MeshCoreDatabase = db): List<List<Pair<Int, Any?>>> =
        database.openHelper.writableDatabase.query(
        "SELECT * FROM rx_log_entries ORDER BY radioId, id",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add((0 until cursor.columnCount).map { column ->
                    val type = cursor.getType(column)
                    val value: Any? = when (type) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column).toRawBits()
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
                        else -> error("Unsupported SQLite column type: $type")
                    }
                    type to value
                })
            }
        }
    }

    private fun extraRow(template: RxLogEntryDTO, ordinal: Int, scope: String): RxLogEntryDTO =
        template.copy(
            id = UUID(0x7000000000000000L, ordinal.toLong()),
            receivedAt = FIRST.plusSeconds(ordinal.toLong()),
            regionScope = scope,
            regionScopeMatches = SnapshotList.of(scope),
        )

    private suspend fun seedAt(time: Instant, seedStore: SimulatorSeedStore) {
        SimulatorConnectionMode(clock = Clock.fixed(time, ZoneOffset.UTC)).seedDataStore(seedStore)
    }

    private fun withStores(count: Int = 1, block: suspend (List<RoomPersistenceStore>) -> Unit) {
        val scheduler = TestCoroutineScheduler()
        val owners = List(count) { CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler)) }
        val stores = owners.map { RoomPersistenceStore(db, it) }
        try {
            runBlocking { block(stores) }
        } finally {
            try {
                runBlocking { stores.forEach { it.close() } }
            } finally {
                owners.forEach { it.cancel() }
            }
        }
    }

    private companion object {
        val FIRST: Instant = Instant.ofEpochSecond(1_700_000_000)
        val LATER: Instant = FIRST.plusSeconds(86_400)
    }
}
