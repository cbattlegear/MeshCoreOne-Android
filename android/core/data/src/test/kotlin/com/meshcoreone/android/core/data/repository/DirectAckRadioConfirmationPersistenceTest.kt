// AndroidOnly: WP-202 Complete read-only persisted radio wildcard evidence through real Room.
package com.meshcoreone.android.core.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabaseLockedException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DirectAckRadioConfirmationPersistenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val failures = mutableListOf<PersistenceStoreException>()
    private lateinit var db: MeshCoreDatabase
    private lateinit var store: RoomPersistenceStore

    @Before fun open() {
        val immediate = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .addCallback(DirectAckStorage.callback).allowMainThreadQueries()
            .setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        store = newStore(db)
    }

    @After fun close() {
        try { runBlocking { store.close() } } finally { scope.cancel(); db.close() }
    }

    private fun newStore(database: MeshCoreDatabase) =
        RoomPersistenceStore(database, scope, RepositoryClock(), RepositoryIssueReporter { _, error -> failures += error })

    private suspend fun evidence(radio: RadioId = RADIO_A) =
        store.inspectDirectAckRadioConfirmationEvidence(radio)

    private suspend fun reasons(radio: RadioId = RADIO_A) =
        assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(evidence(radio)).also {
            assertEquals(radio, it.radioId)
        }.reasons.toSet()

    private suspend fun physical(
        state: String,
        radio: RadioId = RADIO_A,
        actual: Long? = null,
    ): DirectAckEmissionEntity {
        val wire = DirectAckWireEntity(radio.value, UUID.randomUUID(), UUID.randomUUID(), 123, actual)
        db.directAcks().wire(wire)
        db.directAcks().operation(DirectAckOperationEntity(
            wire.radioId, wire.claimId, wire.requestId, "EMISSION_TRACKING_REQUIRED", wire.predictedCode, 1, null,
        ))
        return DirectAckEmissionEntity(
            wire.radioId, wire.claimId, wire.requestId, UUID.randomUUID(), UUID.randomUUID(),
            wire.predictedCode, state, actual, if (state == "MAY_HAVE_WRITTEN") null else UUID.randomUUID(),
        ).also { db.directAckExclusions().emission(it) }
    }

    @Test fun noWildcardReportsOnlyExplicitRadioHistoryNotPerCodeEligibility() = runTest {
        assertEquals(DirectAckRadioConfirmationEvidence.NoPersistedWildcard(RADIO_A), evidence())
        physical("ACCEPTED", actual = 123)
        physical("ACCEPTED", actual = 123)
        physical("DEFINITELY_UNSENT")
        val neverInvoked = DirectAckWireEntity(RADIO_A.value, UUID.randomUUID(), UUID.randomUUID(), 123, null)
        db.directAcks().wire(neverInvoked)
        db.directAcks().operation(DirectAckOperationEntity(
            neverInvoked.radioId, neverInvoked.claimId, neverInvoked.requestId,
            "EMISSION_TRACKING_REQUIRED", neverInvoked.predictedCode, 1, null,
        ))
        assertEquals(DirectAckRadioConfirmationEvidence.NoPersistedWildcard(RADIO_A), evidence())
        val emissions = db.directAckExclusions().emissions(RADIO_A.value, 10)
        assertTrue(db.directAckExclusions().competingCode(RADIO_A.value, neverInvoked.claimId, 123))
        assertEquals(emissions, db.directAckExclusions().emissions(RADIO_A.value, 10))
        assertNull(db.directAcks().delivery(RADIO_A.value, neverInvoked.claimId))
    }

    @Test fun typedReasonsIncludeEveryTableWithoutCrossRadioLeakageOrMutation() = runTest {
        physical("MAY_HAVE_WRITTEN")
        physical("CONFLICT", actual = 456)
        val legacy = DirectAckWireEntity(RADIO_A.value, UUID.randomUUID(), UUID.randomUUID(), 789, 999)
        db.directAcks().wire(legacy)
        db.directAckExclusions().radio(DirectAckRadioExclusionEntity(RADIO_A.value, true))
        val emissions = db.directAckExclusions().emissions(RADIO_A.value, 10)
        val wires = db.directAcks().wires(RADIO_A.value, legacy.claimId)
        val operations = db.directAcks().operations(RADIO_A.value, legacy.claimId)
        assertEquals(DirectAckRadioConfirmationWildcardReason.entries.toSet(), reasons())
        assertEquals(DirectAckRadioConfirmationEvidence.NoPersistedWildcard(RADIO_B), evidence(RADIO_B))
        assertEquals(emissions, db.directAckExclusions().emissions(RADIO_A.value, 10))
        assertEquals(wires, db.directAcks().wires(RADIO_A.value, legacy.claimId))
        assertEquals(operations, db.directAcks().operations(RADIO_A.value, legacy.claimId))
        assertTrue(db.directAckExclusions().saturated(RADIO_A.value))
        assertNull(db.directAcks().delivery(RADIO_A.value, legacy.claimId))
        assertTrue(failures.isEmpty())
    }

    @Test fun conflictIsDistinctFromUnresolvedAndMalformedPhysicalStateFailsWithTypedInvalidData() = runTest {
        val conflict = physical("CONFLICT_UNSENT")
        assertEquals(setOf(DirectAckRadioConfirmationWildcardReason.CONFLICTING_PHYSICAL_EVIDENCE), reasons())
        db.directAckExclusions().emission(conflict.copy(state = "UNRECOGNIZED_STATE"))
        assertEquals(PersistenceStoreError.InvalidData,
            assertFailsWith<PersistenceStoreException> { evidence() }.error)
        db.directAckExclusions().emission(conflict.copy(state = "ACCEPTED", actualCode = null))
        assertEquals(PersistenceStoreError.InvalidData,
            assertFailsWith<PersistenceStoreException> { evidence() }.error)
    }

    @Test fun fullAggregateFindsWildcardBeyondDiagnosticCap() = runTest(timeout = 180.seconds) {
        db.withTransaction {
            repeat(4_100) { physical("ACCEPTED", actual = it.toLong()) }
        }
        val omitted = physical("MAY_HAVE_WRITTEN")
        val maximum = DirectAckRadioEvidenceLimits.MAX_DIAGNOSTIC_WIRES_PER_RADIO
        val sample = db.directAckExclusions().emissions(RADIO_A.value, maximum)
        val sampledUnknown = sample.firstOrNull { it.state == "MAY_HAVE_WRITTEN" }
        if (sampledUnknown != null) {
            val knownOutsideSample = db.directAckExclusions().emissions(RADIO_A.value, 4_101)
                .first { row -> sample.none { it.markRequestId == row.markRequestId } }
            db.directAckExclusions().emission(omitted.copy(state = "ACCEPTED", actualCode = 123, resolutionRequestId = UUID.randomUUID()))
            db.directAckExclusions().emission(knownOutsideSample.copy(
                state = "MAY_HAVE_WRITTEN", actualCode = null, resolutionRequestId = null,
            ))
        }
        assertFalse(db.directAckExclusions().emissions(RADIO_A.value, maximum).any { it.state == "MAY_HAVE_WRITTEN" })
        assertEquals(DirectAckRadioEvidenceCoverage.TRUNCATED,
            assertIs<DirectAckRadioEvidenceObservation.Found>(store.inspectDirectAckRadioEvidence(RADIO_A)).snapshot.coverage)
        assertEquals(setOf(DirectAckRadioConfirmationWildcardReason.UNRESOLVED_PHYSICAL_EMISSION), reasons())
        assertFalse(db.directAckExclusions().saturated(RADIO_A.value))
        assertEquals(4_101, db.directAckExclusions().count(RADIO_A.value).toInt())
    }

    @Test fun fileReopenReportsUnresolvedOriginalPhysicalEvidenceWithoutAnyHandleOrGrant() = runTest {
        val name = "confirmation-evidence-${UUID.randomUUID()}.db"
        var file = MeshCoreDatabase.open(context, name)
        var target = newStore(file)
        try {
            val token = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), RADIO_A)
            val lease = assertIs<DirectAckActivationResult.Activated>(
                target.activateDirectAckSession(token, UUID.randomUUID()),
            ).lease
            val row = message()
            target.saveMessage(row)
            val intent = assertIs<DirectAckIntentResult.Captured>(target.captureDirectAckIntent(
                lease, EntityKey(RADIO_A, row.id), UUID.randomUUID(), DirectAckMode.SEND, null,
            )).intent
            val wire = UUID.randomUUID()
            val attempt = assertIs<DirectAckBeginResult.Begun>(
                target.beginDirectAckAttemptRequiringEmissionTracking(intent, wire, 123u),
            ).attempt
            val marked = assertIs<DirectAckEmissionMarkResult.Marked>(
                target.markDirectAckWireMayHaveWritten(attempt, wire, UUID.randomUUID()),
            ).receipt
            val before = target.inspectDirectAckRadioConfirmationEvidence(RADIO_A)
            assertEquals(setOf(DirectAckRadioConfirmationWildcardReason.UNRESOLVED_PHYSICAL_EMISSION),
                assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(before).reasons.toSet())
            target.close()
            file.close()
            file = MeshCoreDatabase.open(context, name)
            target = newStore(file)
            assertEquals(before, target.inspectDirectAckRadioConfirmationEvidence(RADIO_A))
            assertEquals(DirectAckRadioConfirmationEvidence.NoPersistedWildcard(RADIO_B),
                target.inspectDirectAckRadioConfirmationEvidence(RADIO_B))
            assertEquals(marked.emissionRequestId, file.directAckExclusions().emissions(RADIO_A.value, 10).single().markRequestId)
            assertEquals(row, target.fetchMessage(EntityKey(RADIO_A, row.id)))
            assertNull(file.directAcks().delivery(RADIO_A.value, marked.evidence.claimRequestId))
        } finally { target.close(); file.close(); context.deleteDatabase(name) }
    }

    @Test fun separateHandlesObserveOneAtomicMultiTableSnapshotAndRolledBackWritesDoNotLeak() = runTest {
        val name = "confirmation-two-handles-${UUID.randomUUID()}.db"
        val first = MeshCoreDatabase.open(context, name)
        val second = MeshCoreDatabase.open(context, name)
        val target = newStore(second)
        val wire = DirectAckWireEntity(RADIO_A.value, UUID.randomUUID(), UUID.randomUUID(), 123, null)
        try {
            first.directAcks().wire(wire)
            assertEquals(setOf(DirectAckRadioConfirmationWildcardReason.UNTRACKED_LEGACY_ANCESTRY),
                assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(
                    target.inspectDirectAckRadioConfirmationEvidence(RADIO_A),
                ).reasons.toSet())
            val sqlite = second.openHelper.writableDatabase
            sqlite.beginTransaction()
            try {
                sqlite.query("PRAGMA busy_timeout=0").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
                sqlite.setTransactionSuccessful()
            } finally { sqlite.endTransaction() }
            val marker = DirectAckEmissionEntity(
                wire.radioId, wire.claimId, wire.requestId, UUID.randomUUID(), UUID.randomUUID(),
                123, "MAY_HAVE_WRITTEN", null, null,
            )
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            withContext(Dispatchers.IO) {
                withTimeout(30_000) {
                    val writer = async(Dispatchers.IO) {
                        first.withTransaction {
                            first.directAcks().operation(DirectAckOperationEntity(
                                wire.radioId, wire.claimId, wire.requestId,
                                "EMISSION_TRACKING_REQUIRED", wire.predictedCode, 1, null,
                            ))
                            first.directAckExclusions().emission(marker)
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                    entered.await()
                    try {
                        val locked = assertFailsWith<PersistenceStoreException> {
                            target.inspectDirectAckRadioConfirmationEvidence(RADIO_A)
                        }
                        assertIs<PersistenceStoreError.FetchFailed>(locked.error)
                        assertIs<SQLiteDatabaseLockedException>(locked.cause)
                    } finally { release.complete(Unit) }
                    writer.await()
                }
            }
            val committed = target.inspectDirectAckRadioConfirmationEvidence(RADIO_A)
            assertEquals(setOf(DirectAckRadioConfirmationWildcardReason.UNRESOLVED_PHYSICAL_EMISSION),
                assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(committed).reasons.toSet())
            assertFailsWith<IllegalStateException> {
                first.withTransaction {
                    first.directAckExclusions().emission(marker.copy(
                        state = "DEFINITELY_UNSENT", resolutionRequestId = UUID.randomUUID(),
                    ))
                    first.directAckExclusions().radio(DirectAckRadioExclusionEntity(RADIO_A.value, true))
                    error("controlled aggregate rollback")
                }
            }
            assertEquals(committed, target.inspectDirectAckRadioConfirmationEvidence(RADIO_A))
            assertEquals(DirectAckRadioConfirmationEvidence.NoPersistedWildcard(RADIO_B),
                target.inspectDirectAckRadioConfirmationEvidence(RADIO_B))
        } finally { target.close(); first.close(); second.close(); context.deleteDatabase(name) }
    }

    @Test fun closedStoreSqlFailureAndCancellationCannotReturnNoWildcard() = runTest {
        val caller = Job().also { it.cancel() }
        assertFailsWith<CancellationException> { withContext(caller) { evidence() } }
        assertTrue(failures.isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TABLE direct_ack_emissions")
        val failed = assertFailsWith<PersistenceStoreException> { evidence() }
        assertIs<PersistenceStoreError.FetchFailed>(failed.error)
        assertSame(failed, failures.single())
        store.close()
        assertFailsWith<PersistenceStoreException> { evidence() }
        assertEquals(2, failures.size)
    }
}
