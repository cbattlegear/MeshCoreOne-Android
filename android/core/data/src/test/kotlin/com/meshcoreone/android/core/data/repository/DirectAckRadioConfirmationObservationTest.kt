// AndroidOnly: WP-202 Collector-owned full-query polling across ordinary same-file Room handles.
package com.meshcoreone.android.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DirectAckRadioConfirmationObservationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun reader(name: String, queryCount: AtomicInteger): MeshCoreDatabase =
        Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .addCallback(DirectAckStorage.callback)
            .setQueryCallback({ sql, _ ->
                if (sql.contains("AS unresolvedPhysicalEmission")) queryCount.incrementAndGet()
            }, Executor { it.run() }).build()

    private suspend fun nextSnapshot(
        events: Channel<DirectAckRadioConfirmationObservation>,
        radio: RadioId = RADIO_A,
    ): DirectAckRadioConfirmationEvidence {
        assertEquals(DirectAckRadioConfirmationObservation.Inspecting(radio), events.receive())
        val snapshot = assertIs<DirectAckRadioConfirmationObservation.Snapshot>(events.receive())
        assertEquals(radio, snapshot.radioId)
        return snapshot.evidence
    }

    @Test fun subscribedPollingSeesOtherHandleDaoAndRawCommitsWithoutMultiInstanceNotifications() = runTest {
        val name = "confirmation-observe-handles-${UUID.randomUUID()}.db"
        val writer = MeshCoreDatabase.open(context, name)
        val queries = AtomicInteger()
        val database = reader(name, queries)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = RoomPersistenceStore(database, owner, RepositoryClock())
        val events = Channel<DirectAckRadioConfirmationObservation>()
        var collector: Job? = null
        try {
            val observations = store.observeDirectAckRadioConfirmationEvidence(RADIO_A)
            assertEquals(0, queries.get())
            collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                observations.collect { events.send(it) }
            }
            assertEquals(setOf(DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN),
                assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(nextSnapshot(events)).reasons.toSet())
            runCurrent()
            assertEquals(1, queries.get())
            advanceTimeBy(999)
            runCurrent()
            assertEquals(1, queries.get())
            assertTrue(events.tryReceive().isFailure)

            val wire = DirectAckWireEntity(RADIO_A.value, UUID.randomUUID(), UUID.randomUUID(), 123, null)
            val marker = DirectAckEmissionEntity(
                wire.radioId, wire.claimId, wire.requestId, UUID.randomUUID(), UUID.randomUUID(),
                123, "MAY_HAVE_WRITTEN", null, null,
            )
            writer.withTransaction {
                writer.directAcks().wire(wire)
                writer.directAcks().operation(DirectAckOperationEntity(
                    wire.radioId, wire.claimId, wire.requestId,
                    "EMISSION_TRACKING_REQUIRED", wire.predictedCode, 1, null,
                ))
                writer.directAcks().operation(DirectAckOperationEntity(
                    wire.radioId, wire.claimId, UUID.randomUUID(),
                    "ACCEPTANCE_HISTORY_REQUIRED:${wire.requestId}", wire.predictedCode, 1, null,
                ))
                writer.directAckExclusions().emission(marker)
            }
            advanceTimeBy(1)
            runCurrent()
            assertEquals(setOf(DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN,
                DirectAckRadioConfirmationWildcardReason.UNRESOLVED_PHYSICAL_EMISSION),
                assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(nextSnapshot(events)).reasons.toSet())
            runCurrent()

            writer.openHelper.writableDatabase.execSQL(
                "UPDATE direct_ack_emissions SET state='CONFLICT',actualCode=123,resolutionRequestId=? WHERE radioId=?",
                arrayOf<Any?>(UUID.randomUUID().toString().uppercase(), RADIO_A.value.toString().uppercase()),
            )
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(setOf(DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN,
                DirectAckRadioConfirmationWildcardReason.CONFLICTING_PHYSICAL_EVIDENCE),
                assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(nextSnapshot(events)).reasons.toSet())
            runCurrent()

            val legacy = DirectAckWireEntity(RADIO_A.value, UUID.randomUUID(), UUID.randomUUID(), 456, 789)
            writer.openHelper.writableDatabase.execSQL(
                "INSERT INTO direct_ack_wires VALUES (?,?,?,?,?)",
                arrayOf<Any?>(legacy.radioId.toString().uppercase(), legacy.claimId.toString().uppercase(),
                    legacy.requestId.toString().uppercase(), legacy.predictedCode, legacy.acceptedCode),
            )
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(setOf(DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN,
                DirectAckRadioConfirmationWildcardReason.CONFLICTING_PHYSICAL_EVIDENCE,
                DirectAckRadioConfirmationWildcardReason.UNTRACKED_LEGACY_ANCESTRY),
                assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(nextSnapshot(events)).reasons.toSet())
            runCurrent()

            writer.directAcks().operation(DirectAckOperationEntity(
                legacy.radioId, legacy.claimId, legacy.requestId,
                "EMISSION_TRACKING_REQUIRED", legacy.predictedCode, 1, null,
            ))
            writer.openHelper.writableDatabase.execSQL(
                "INSERT INTO direct_ack_radio_exclusions VALUES (?,1)",
                arrayOf<Any?>(RADIO_A.value.toString().uppercase()),
            )
            val other = marker.copy(radioId = RADIO_B.value, markRequestId = UUID.randomUUID())
            writer.directAckExclusions().emission(other)
            advanceTimeBy(1_000)
            runCurrent()
            val beforeRollback = nextSnapshot(events)
            assertEquals(setOf(DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN,
                DirectAckRadioConfirmationWildcardReason.CONFLICTING_PHYSICAL_EVIDENCE,
                DirectAckRadioConfirmationWildcardReason.UNTRACKED_LEGACY_ANCESTRY,
                DirectAckRadioConfirmationWildcardReason.STICKY_SATURATION),
                assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(beforeRollback).reasons.toSet())
            runCurrent()
            val otherEvents = store.observeDirectAckRadioConfirmationEvidence(RADIO_B).take(2).toList()
            assertEquals(DirectAckRadioConfirmationObservation.Inspecting(RADIO_B), otherEvents.first())
            assertEquals(setOf(DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN,
                DirectAckRadioConfirmationWildcardReason.UNRESOLVED_PHYSICAL_EMISSION),
                assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(
                    assertIs<DirectAckRadioConfirmationObservation.Snapshot>(otherEvents.last()).evidence,
                ).reasons.toSet())

            assertFailsWith<IllegalStateException> {
                writer.withTransaction {
                    writer.directAckExclusions().emission(marker.copy(
                        state = "DEFINITELY_UNSENT", resolutionRequestId = UUID.randomUUID(),
                    ))
                    writer.directAckExclusions().radio(DirectAckRadioExclusionEntity(RADIO_A.value, false))
                    error("controlled observation rollback")
                }
            }
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(beforeRollback, nextSnapshot(events))
            runCurrent()
            collector.cancelAndJoin()
            val stopped = queries.get()
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(stopped, queries.get())
            assertTrue(events.tryReceive().isFailure)
            assertEquals("CONFLICT", writer.directAckExclusions().emission(
                wire.radioId, wire.claimId, wire.requestId, marker.markRequestId,
            )?.state)
            assertTrue(writer.directAckExclusions().saturated(RADIO_A.value))
        } finally {
            collector?.cancelAndJoin()
            events.close()
            store.close()
            owner.cancel()
            writer.close()
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun commitDuringInitialInspectionIsReadAndSqlOrClosedStoreFailuresTerminateInsteadOfClearing() = runTest {
        val name = "confirmation-observe-errors-${UUID.randomUUID()}.db"
        val writer = MeshCoreDatabase.open(context, name)
        val queries = AtomicInteger()
        val database = reader(name, queries)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val reported = mutableListOf<PersistenceStoreException>()
        val store = RoomPersistenceStore(database, owner, RepositoryClock(), RepositoryIssueReporter { _, error -> reported += error })
        val events = Channel<DirectAckRadioConfirmationObservation>()
        val terminal = CompletableDeferred<PersistenceStoreException>()
        val beginInitialRead = CompletableDeferred<Unit>()
        var collector: Job? = null
        try {
            collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                terminal.complete(assertFailsWith<PersistenceStoreException> {
                    var initial = true
                    store.observeDirectAckRadioConfirmationEvidence(RADIO_A).collect {
                        events.send(it)
                        if (initial && it is DirectAckRadioConfirmationObservation.Inspecting) {
                            initial = false
                            beginInitialRead.await()
                        }
                    }
                })
            }
            assertEquals(DirectAckRadioConfirmationObservation.Inspecting(RADIO_A), events.receive())
            writer.directAckExclusions().radio(DirectAckRadioExclusionEntity(RADIO_A.value, true))
            beginInitialRead.complete(Unit)
            val initial = assertIs<DirectAckRadioConfirmationObservation.Snapshot>(events.receive())
            assertEquals(setOf(DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN,
                DirectAckRadioConfirmationWildcardReason.STICKY_SATURATION),
                assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(initial.evidence).reasons.toSet())
            runCurrent()
            writer.openHelper.writableDatabase.execSQL("DROP TABLE direct_ack_emissions")
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(DirectAckRadioConfirmationObservation.Inspecting(RADIO_A), events.receive())
            val failure = terminal.await()
            assertIs<PersistenceStoreError.FetchFailed>(failure.error)
            assertSame(failure, reported.single())
            collector.join()
            assertTrue(events.tryReceive().isFailure)
            val stopped = queries.get()
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(stopped, queries.get())
            store.close()
            val closedEvents = mutableListOf<DirectAckRadioConfirmationObservation>()
            val closed = assertFailsWith<PersistenceStoreException> {
                store.observeDirectAckRadioConfirmationEvidence(RADIO_A).collect { closedEvents += it }
            }
            assertIs<PersistenceStoreError.FetchFailed>(closed.error)
            assertEquals(listOf(DirectAckRadioConfirmationObservation.Inspecting(RADIO_A)), closedEvents)
            assertEquals(2, reported.size)
        } finally {
            collector?.cancelAndJoin()
            events.close()
            store.close()
            owner.cancel()
            writer.close()
            database.close()
            context.deleteDatabase(name)
        }
    }
}
