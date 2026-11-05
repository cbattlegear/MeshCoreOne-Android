// AndroidOnly: WP-202 Atomic guarded failure admission with complete radio evidence and exact origin proof.
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
class DirectAckRadioFailurePersistenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val immediate = Executor { it.run() }
    private val token = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), RADIO_A)
    private val reported = mutableListOf<PersistenceStoreException>()
    private lateinit var db: MeshCoreDatabase
    private lateinit var store: RoomPersistenceStore

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .addCallback(DirectAckStorage.callback).allowMainThreadQueries()
            .setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        store = newStore(db)
    }

    @After fun close() {
        try { runBlocking { store.close() } } finally { owner.cancel(); db.close() }
    }

    private fun newStore(database: MeshCoreDatabase) =
        RoomPersistenceStore(database, owner, RepositoryClock(), RepositoryIssueReporter { _, error -> reported += error })

    private data class Claim(
        val lease: DirectAckLease,
        val attempt: DirectAckAttempt,
        val id: UUID,
        val wire: UUID,
        val row: MessageDTO,
    )

    private suspend fun activate(target: RoomPersistenceStore = store) =
        assertIs<DirectAckActivationResult.Activated>(target.activateDirectAckSession(token, UUID.randomUUID())).lease

    private suspend fun begin(
        lease: DirectAckLease,
        target: RoomPersistenceStore = store,
        tracked: Boolean = true,
        row: MessageDTO = message(),
        mode: DirectAckMode = DirectAckMode.SEND,
    ): Claim {
        target.saveMessage(row)
        val id = UUID.randomUUID()
        val intent = assertIs<DirectAckIntentResult.Captured>(target.captureDirectAckIntent(
            lease, EntityKey(RADIO_A, row.id), id, mode, null,
        )).intent
        val wire = UUID.randomUUID()
        val result = if (tracked) target.beginDirectAckAttemptRequiringEmissionTracking(intent, wire, 123u)
            else target.beginDirectAckAttempt(intent, wire, 123u)
        return Claim(lease, assertIs<DirectAckBeginResult.Begun>(result).attempt, id, wire, row)
    }

    private suspend fun mark(claim: Claim, target: RoomPersistenceStore = store) =
        assertIs<DirectAckEmissionMarkResult.Marked>(
            target.markDirectAckWireMayHaveWritten(claim.attempt, claim.wire, UUID.randomUUID()),
        )

    private suspend fun accept(claim: Claim, code: UInt, target: RoomPersistenceStore = store): DirectAckEmissionMarkResult.Marked {
        val marker = mark(claim, target)
        assertIs<DirectAckEmissionMutationResult.Applied>(target.resolveDirectAckWireEmission(
            marker.handle, UUID.randomUUID(), DirectAckEmissionResolution.Accepted(code),
        ))
        return marker
    }

    private suspend fun nonfailing(
        claim: Claim,
        vararg expected: DirectAckRadioNonfailingReason,
        target: RoomPersistenceStore = store,
        database: MeshCoreDatabase = db,
    ) {
        val key = EntityKey(RADIO_A, claim.row.id)
        val row = target.fetchMessage(key)
        val receipt = assertIs<DirectAckObservation.Found>(target.inspectDirectAckAttempt(claim.lease, claim.id)).receipt
        val identity = database.directAcks().incarnation(RADIO_A.value, claim.row.id, "message")
        val operations = database.directAcks().operations(RADIO_A.value, claim.id)
        val result = assertIs<DirectAckRadioFailureResult.Nonfailing>(
            target.recordDirectFailureExcludingRadioHistory(claim.attempt),
        )
        assertTrue(expected.isNotEmpty())
        assertTrue(result.reasons.containsAll(expected.toList()))
        assertEquals(row, target.fetchMessage(key))
        assertEquals(receipt, assertIs<DirectAckObservation.Found>(
            target.inspectDirectAckAttempt(claim.lease, claim.id),
        ).receipt)
        assertEquals(identity, database.directAcks().incarnation(RADIO_A.value, claim.row.id, "message"))
        assertEquals(operations, database.directAcks().operations(RADIO_A.value, claim.id))
        assertTrue(database.directAcks().radioFailures(RADIO_A.value, claim.id).isEmpty())
        assertNull(database.directAcks().delivery(RADIO_A.value, claim.id))
    }

    @Test fun ownMayAndEarlierMayBeforeLaterUnsentNeverBecomeFailed() = runTest {
        val claim = begin(activate(), row = message(status = MessageStatus.PENDING))
        mark(claim)
        nonfailing(claim, DirectAckRadioNonfailingReason.TARGET_UNRESOLVED_PHYSICAL_EMISSION)
        val later = mark(claim)
        assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
            later.handle, UUID.randomUUID(), DirectAckEmissionResolution.DefinitelyUnsent,
        ))
        nonfailing(claim, DirectAckRadioNonfailingReason.TARGET_UNRESOLVED_PHYSICAL_EMISSION)
    }

    @Test fun ownLegacyConflictAndMalformedRowsRetainUncertaintyWithoutMutation() = runTest {
        val lease = activate()
        val legacy = begin(lease, tracked = false)
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(legacy.attempt, legacy.wire, 456u))
        nonfailing(legacy, DirectAckRadioNonfailingReason.TARGET_UNTRACKED_LEGACY_ANCESTRY)
        val conflict = begin(lease)
        val marker = accept(conflict, 999u)
        assertIs<DirectAckEmissionMutationResult.Conflict>(store.resolveDirectAckWireEmission(
            marker.handle, UUID.randomUUID(), DirectAckEmissionResolution.DefinitelyUnsent,
        ))
        nonfailing(conflict, DirectAckRadioNonfailingReason.TARGET_CONFLICTING_PHYSICAL_EVIDENCE)
        val malformed = begin(lease)
        val malformedMarker = mark(malformed)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE direct_ack_emissions SET state='DEFINITELY_UNSENT',actualCode=123 " +
                "WHERE markRequestId=?",
            arrayOf<Any?>(malformedMarker.receipt.emissionRequestId.toString().uppercase()),
        )
        nonfailing(malformed, DirectAckRadioNonfailingReason.TARGET_CONFLICTING_PHYSICAL_EVIDENCE)
    }

    @Test fun anyAcceptedActualCollisionAndEveryForeignWildcardKindBlockFailure() = runTest {
        val lease = activate()
        val target = begin(lease)
        accept(target, 999u)
        accept(target, 888u)
        val other = begin(lease)
        accept(other, 888u)
        nonfailing(target, DirectAckRadioNonfailingReason.KNOWN_OTHER_CLAIM_CODE)
        mark(other)
        nonfailing(target, DirectAckRadioNonfailingReason.UNRESOLVED_OTHER_CLAIM_EMISSION)
        val conflict = accept(other, 777u)
        assertIs<DirectAckEmissionMutationResult.Conflict>(store.resolveDirectAckWireEmission(
            conflict.handle, UUID.randomUUID(), DirectAckEmissionResolution.Accepted(666u),
        ))
        val legacy = begin(lease, tracked = false)
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(legacy.attempt, legacy.wire, 555u))
        db.directAckExclusions().radio(DirectAckRadioExclusionEntity(RADIO_A.value, true))
        nonfailing(target, DirectAckRadioNonfailingReason.KNOWN_OTHER_CLAIM_CODE,
            DirectAckRadioNonfailingReason.UNRESOLVED_OTHER_CLAIM_EMISSION,
            DirectAckRadioNonfailingReason.CONFLICTING_OTHER_CLAIM_EVIDENCE,
            DirectAckRadioNonfailingReason.UNTRACKED_OTHER_CLAIM_ANCESTRY,
            DirectAckRadioNonfailingReason.RADIO_HISTORY_SATURATED)
    }

    @Test fun noMarkerAndAllExplicitlyUnsentRemainSafeDespiteForeignMayButSaturationAlwaysBlocks() = runTest {
        val lease = activate()
        val foreign = begin(lease)
        mark(foreign)
        for (unsent in listOf(false, true)) {
            val claim = begin(lease)
            if (unsent) {
                repeat(2) {
                    val marker = mark(claim)
                    assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
                        marker.handle, UUID.randomUUID(), DirectAckEmissionResolution.DefinitelyUnsent,
                    ))
                }
            }
            val applied = assertIs<DirectAckRadioFailureResult.Applied>(
                store.recordDirectFailureExcludingRadioHistory(claim.attempt),
            ).receipt
            assertEquals(MessageStatus.FAILED, applied.message?.status)
            assertTrue(applied.bookkeeping.isEmpty())
            assertTrue(applied.pendingCompletions.isEmpty())
            assertNull(applied.delivery)
            assertEquals(1, db.directAcks().radioFailures(RADIO_A.value, claim.id).size)
        }
        val representedUnsent = begin(lease)
        val unsentMarker = mark(representedUnsent)
        assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
            unsentMarker.handle, UUID.randomUUID(), DirectAckEmissionResolution.DefinitelyUnsent,
        ))
        db.directAckExclusions().radio(DirectAckRadioExclusionEntity(RADIO_A.value, true))
        val saturated = begin(lease)
        assertIs<DirectAckEmissionMarkResult.CoveredBySaturation>(
            store.markDirectAckWireMayHaveWritten(saturated.attempt, saturated.wire, UUID.randomUUID()),
        )
        assertTrue(db.directAckExclusions().emissions(RADIO_A.value, 100).none { it.claimId == saturated.id })
        nonfailing(saturated, DirectAckRadioNonfailingReason.RADIO_HISTORY_SATURATED)
        assertIs<DirectAckEmissionMarkResult.CoveredBySaturation>(
            store.markDirectAckWireMayHaveWritten(representedUnsent.attempt, representedUnsent.wire, UUID.randomUUID()),
        )
        nonfailing(representedUnsent, DirectAckRadioNonfailingReason.RADIO_HISTORY_SATURATED)
    }

    @Test fun guardedFailureReplayIsHistoricalAndCannotBeForgedFromLegacyFailure() = runTest {
        val lease = activate()
        val claim = begin(lease)
        accept(claim, 999u)
        val applied = assertIs<DirectAckRadioFailureResult.Applied>(
            store.recordDirectFailureExcludingRadioHistory(claim.attempt),
        ).receipt
        val proof = db.directAcks().radioFailures(RADIO_A.value, claim.id).single()
        val foreign = begin(lease)
        mark(foreign)
        db.directAckExclusions().radio(DirectAckRadioExclusionEntity(RADIO_A.value, true))
        val row = store.fetchMessage(EntityKey(RADIO_A, claim.row.id))
        assertEquals(applied, assertIs<DirectAckRadioFailureResult.AlreadyApplied>(
            store.recordDirectFailureExcludingRadioHistory(claim.attempt),
        ).receipt)
        db.messages().setStatus(RADIO_A.value, claim.row.id, MessageStatus.FAILED.rawValue)
        val invalidated = assertIs<DirectAckRadioFailureResult.AlreadyApplied>(
            store.recordDirectFailureExcludingRadioHistory(claim.attempt),
        ).receipt
        assertEquals(DirectAckPermission.INVALIDATED, invalidated.permission)
        assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, claim.row.id)))
        assertEquals(proof, db.directAcks().radioFailures(RADIO_A.value, claim.id).single())
        assertIs<DirectAckRevocationResult.Revoked>(store.revokeDirectAckSession(lease))
        val retired = assertIs<DirectAckRadioFailureResult.AlreadyApplied>(
            store.recordDirectFailureExcludingRadioHistory(claim.attempt),
        ).receipt
        assertEquals(DirectAckPermission.STALE_LEASE, retired.permission)
        assertNull(retired.message)
        val nextLease = assertIs<DirectAckActivationResult.Activated>(
            store.activateDirectAckSession(token.copy(generation = Generation(2)), UUID.randomUUID()),
        ).lease
        val generic = begin(nextLease)
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectFailure(generic.attempt))
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckRadioFailureResult.Rejected>(
            store.recordDirectFailureExcludingRadioHistory(generic.attempt),
        ).reason)
        assertTrue(db.directAcks().radioFailures(RADIO_A.value, generic.id).isEmpty())
    }

    @Test fun ownDeliveryAfterGuardedFailureAndInitialDeliveredResendAlwaysWin() = runTest {
        val lease = activate()
        val claim = begin(lease)
        accept(claim, 999u)
        assertIs<DirectAckRadioFailureResult.Applied>(store.recordDirectFailureExcludingRadioHistory(claim.attempt))
        assertIs<DirectAckRadioSettlementResult.Settled>(
            store.settleDirectAckExcludingRadioHistory(claim.attempt, 999u, 42u),
        )
        db.directAckExclusions().radio(DirectAckRadioExclusionEntity(RADIO_A.value, true))
        val delivered = assertIs<DirectAckRadioFailureResult.AlreadyDelivered>(
            store.recordDirectFailureExcludingRadioHistory(claim.attempt),
        ).receipt
        assertEquals(MessageStatus.DELIVERED, delivered.message?.status)
        assertEquals(42u, delivered.delivery?.roundTripTime)
        val resend = begin(lease, row = message(status = MessageStatus.DELIVERED), mode = DirectAckMode.RESEND)
        val baseline = assertIs<DirectAckRadioFailureResult.AlreadyDelivered>(
            store.recordDirectFailureExcludingRadioHistory(resend.attempt),
        ).receipt
        assertNull(baseline.delivery)
        assertEquals(MessageStatus.DELIVERED, baseline.message?.status)
        assertTrue(db.directAcks().radioFailures(RADIO_A.value, resend.id).isEmpty())
    }

    @Test fun malformedForeignUnsentIsConflictNotSafeAndStaleUnprovedFailureIsRejected() = runTest {
        val lease = activate()
        val target = begin(lease)
        accept(target, 999u)
        val foreign = begin(lease)
        val marker = mark(foreign)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE direct_ack_emissions SET state='DEFINITELY_UNSENT',actualCode=999 " +
                "WHERE markRequestId=?",
            arrayOf<Any?>(marker.receipt.emissionRequestId.toString().uppercase()),
        )
        nonfailing(target, DirectAckRadioNonfailingReason.CONFLICTING_OTHER_CLAIM_EVIDENCE)
        db.messages().setStatus(RADIO_A.value, target.row.id, MessageStatus.SENT.rawValue)
        val row = store.fetchMessage(EntityKey(RADIO_A, target.row.id))
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckRadioFailureResult.Rejected>(
            store.recordDirectFailureExcludingRadioHistory(target.attempt),
        ).reason)
        assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, target.row.id)))
        assertTrue(db.directAcks().radioFailures(RADIO_A.value, target.id).isEmpty())
    }

    @Test fun proofInsertionFailureRollsBackStatusRevisionAndClaimAndCommitThenCancelReplays() = runTest {
        val claim = begin(activate())
        val before = assertIs<DirectAckObservation.Found>(store.inspectDirectAckAttempt(claim.lease, claim.id)).receipt
        val identity = db.directAcks().incarnation(RADIO_A.value, claim.row.id, "message")
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_guarded_failure BEFORE INSERT ON direct_ack_operations " +
                "WHEN NEW.kind='RADIO_FAILURE' BEGIN SELECT RAISE(ABORT,'controlled guarded failure'); END",
        )
        assertFailsWith<PersistenceStoreException> { store.recordDirectFailureExcludingRadioHistory(claim.attempt) }
        assertEquals(1, reported.size)
        assertEquals(claim.row, store.fetchMessage(EntityKey(RADIO_A, claim.row.id)))
        assertEquals(identity, db.directAcks().incarnation(RADIO_A.value, claim.row.id, "message"))
        assertEquals(before, assertIs<DirectAckObservation.Found>(store.inspectDirectAckAttempt(claim.lease, claim.id)).receipt)
        assertTrue(db.directAcks().radioFailures(RADIO_A.value, claim.id).isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_guarded_failure")
        val caller = Job()
        assertFailsWith<CancellationException> {
            withContext(caller) {
                assertIs<DirectAckRadioFailureResult.Applied>(store.recordDirectFailureExcludingRadioHistory(claim.attempt))
                caller.cancel()
                ensureActive()
            }
        }
        assertIs<DirectAckRadioFailureResult.AlreadyApplied>(store.recordDirectFailureExcludingRadioHistory(claim.attempt))
        assertEquals(1, db.directAcks().radioFailures(RADIO_A.value, claim.id).size)
    }

    @Test fun cancellationDuringProofInsertionRollsBackFailureAndDoesNotBecomeGuardedReplay() = runTest {
        var cancelOnProof: Job? = null
        var cancellations = 0
        val local = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .addCallback(DirectAckStorage.callback).allowMainThreadQueries()
            .setQueryExecutor(immediate).setTransactionExecutor(immediate)
            .setQueryCallback({ sql, arguments ->
                if (sql.contains("INTO `direct_ack_operations`") && arguments.contains("RADIO_FAILURE")) {
                    cancelOnProof?.let { cancellations++; it.cancel() }
                }
            }, immediate).build()
        val target = newStore(local)
        try {
            val claim = begin(activate(target), target)
            val identity = local.directAcks().incarnation(RADIO_A.value, claim.row.id, "message")
            val caller = Job()
            cancelOnProof = caller
            assertFailsWith<CancellationException> {
                withContext(caller) { target.recordDirectFailureExcludingRadioHistory(claim.attempt) }
            }
            cancelOnProof = null
            assertEquals(1, cancellations)
            assertEquals(claim.row, target.fetchMessage(EntityKey(RADIO_A, claim.row.id)))
            assertEquals(identity, local.directAcks().incarnation(RADIO_A.value, claim.row.id, "message"))
            assertEquals(DirectAckPhase.REGISTERED, assertIs<DirectAckObservation.Found>(
                target.inspectDirectAckAttempt(claim.lease, claim.id),
            ).receipt.phase)
            assertTrue(local.directAcks().radioFailures(RADIO_A.value, claim.id).isEmpty())
            assertTrue(reported.isEmpty())
            assertIs<DirectAckRadioFailureResult.Applied>(target.recordDirectFailureExcludingRadioHistory(claim.attempt))
        } finally { cancelOnProof = null; target.close(); local.close() }
    }

    @Test fun guardedProofSurvivesRestoreAndReopenButNeverReissuesAnAttemptOrOverwritesAReplacement() = runTest {
        val name = "guarded-failure-reopen-${UUID.randomUUID()}.db"
        var file = MeshCoreDatabase.open(context, name)
        var target = newStore(file)
        try {
            val claim = begin(activate(target), target)
            assertIs<DirectAckRadioFailureResult.Applied>(target.recordDirectFailureExcludingRadioHistory(claim.attempt))
            val proof = file.directAcks().radioFailures(RADIO_A.value, claim.id).single()
            target.withBackupRestore { _, _ -> Unit }
            val restored = assertIs<DirectAckRadioFailureResult.AlreadyApplied>(
                target.recordDirectFailureExcludingRadioHistory(claim.attempt),
            ).receipt
            assertEquals(DirectAckPermission.STALE_LEASE, restored.permission)
            assertNull(restored.message)
            assertEquals(proof, file.directAcks().radioFailures(RADIO_A.value, claim.id).single())
            target.deleteMessage(EntityKey(RADIO_A, claim.row.id))
            target.saveMessage(claim.row)
            assertIs<DirectAckRadioFailureResult.AlreadyApplied>(
                target.recordDirectFailureExcludingRadioHistory(claim.attempt),
            )
            assertEquals(claim.row, target.fetchMessage(EntityKey(RADIO_A, claim.row.id)))
            target.close()
            file.close()
            file = MeshCoreDatabase.open(context, name)
            target = newStore(file)
            assertEquals(proof, file.directAcks().radioFailures(RADIO_A.value, claim.id).single())
            assertEquals(DirectAckRejection.INVALID_HANDLE, assertIs<DirectAckRadioFailureResult.Rejected>(
                target.recordDirectFailureExcludingRadioHistory(claim.attempt),
            ).reason)
            assertEquals(claim.row, target.fetchMessage(EntityKey(RADIO_A, claim.row.id)))
        } finally { target.close(); file.close(); context.deleteDatabase(name) }
    }

    @Test fun competingDatabaseAttemptFailsBusyThenFullHistoryPreventsFailure() = runTest {
        val name = "guarded-failure-handles-${UUID.randomUUID()}.db"
        val writer = MeshCoreDatabase.open(context, name)
        val reader = MeshCoreDatabase.open(context, name)
        val target = newStore(reader)
        try {
            val claim = begin(activate(target), target)
            accept(claim, 999u, target)
            val sqlite = reader.openHelper.writableDatabase
            sqlite.beginTransaction()
            try {
                sqlite.query("PRAGMA busy_timeout=0").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
                sqlite.setTransactionSuccessful()
            } finally { sqlite.endTransaction() }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            withContext(Dispatchers.IO) {
                withTimeout(30_000) {
                    val competing = async(Dispatchers.IO) {
                        writer.withTransaction {
                            val wire = DirectAckWireEntity(RADIO_A.value, UUID.randomUUID(), UUID.randomUUID(), 123, null)
                            writer.directAcks().wire(wire)
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                    entered.await()
                    try {
                        val failure = assertFailsWith<PersistenceStoreException> {
                            target.recordDirectFailureExcludingRadioHistory(claim.attempt)
                        }
                        assertIs<SQLiteDatabaseLockedException>(failure.cause)
                    } finally { release.complete(Unit) }
                    competing.await()
                }
            }
            nonfailing(claim, DirectAckRadioNonfailingReason.UNTRACKED_OTHER_CLAIM_ANCESTRY,
                target = target, database = reader)
        } finally { target.close(); writer.close(); reader.close(); context.deleteDatabase(name) }
    }
}
