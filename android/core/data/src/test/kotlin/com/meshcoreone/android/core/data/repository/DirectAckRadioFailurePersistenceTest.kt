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

    @Test fun genericAcceptedTrackedWireWithoutMatchingPhysicalAcceptanceIsNeverNoWriteProof() = runTest {
        val lease = activate()
        val missing = begin(lease)
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(missing.attempt, missing.wire, 999u))
        val foreign = begin(lease)
        accept(foreign, 999u)
        mark(foreign)
        nonfailing(missing, DirectAckRadioNonfailingReason.TARGET_CONFLICTING_PHYSICAL_EVIDENCE,
            DirectAckRadioNonfailingReason.UNRESOLVED_OTHER_CLAIM_EMISSION)
        for (acceptFirst in listOf(false, true)) {
            val unsent = begin(lease)
            val marker = mark(unsent)
            if (acceptFirst) assertIs<DirectAckMutationResult.Applied>(
                store.recordDirectSendAccepted(unsent.attempt, unsent.wire, 999u),
            )
            assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
                marker.handle, UUID.randomUUID(), DirectAckEmissionResolution.DefinitelyUnsent,
            ))
            if (!acceptFirst) assertIs<DirectAckMutationResult.Applied>(
                store.recordDirectSendAccepted(unsent.attempt, unsent.wire, 999u),
            )
            nonfailing(unsent, DirectAckRadioNonfailingReason.TARGET_CONFLICTING_PHYSICAL_EVIDENCE)
        }
        val warning = assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(
            store.inspectDirectAckRadioConfirmationEvidence(RADIO_A),
        )
        assertTrue(DirectAckRadioConfirmationWildcardReason.CONFLICTING_PHYSICAL_EVIDENCE in warning.reasons)
    }

    @Test fun laterPhysicalActualMustNotEraseAnEarlierUnmatchedGenericAcceptedActual() = runTest {
        val lease = activate()
        val target = begin(lease)
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(target.attempt, target.wire, 999u))
        val other = begin(lease)
        accept(other, 999u)
        accept(target, 888u)
        assertEquals(888L, db.directAcks().wire(RADIO_A.value, target.id, target.wire)?.acceptedCode)
        nonfailing(target, DirectAckRadioNonfailingReason.TARGET_CONFLICTING_PHYSICAL_EVIDENCE)
        val result = assertIs<DirectAckRadioSettlementResult.Nonsettling>(
            store.settleDirectAckExcludingRadioHistory(target.attempt, 888u, 42u),
        )
        assertTrue(DirectAckRadioNonsettlingReason.TARGET_ACCEPTANCE_UNRESOLVED in result.reasons)
        val warning = assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(
            store.inspectDirectAckRadioConfirmationEvidence(RADIO_A),
        )
        assertTrue(DirectAckRadioConfirmationWildcardReason.CONFLICTING_PHYSICAL_EVIDENCE in warning.reasons)
        assertEquals(target.row, store.fetchMessage(EntityKey(RADIO_A, target.row.id)))
    }

    @Test fun everyGenericActualSurvivesProductDeletionLeaseRetirementAndReopenIncludingForeignCollision() = runTest {
        for (physicalCode in listOf(888u, 999u)) {
            val name = "generic-acceptance-history-${UUID.randomUUID()}.db"
            var file = MeshCoreDatabase.open(context, name)
            var target = newStore(file)
            try {
                val old = begin(activate(target), target)
                val generic = assertIs<DirectAckMutationResult.Applied>(
                    target.recordDirectSendAccepted(old.attempt, old.wire, 999u),
                ).receipt
                assertTrue(generic.bookkeeping.isEmpty())
                assertTrue(generic.pendingCompletions.isEmpty())
                assertEquals(listOf(DirectAckWireReceipt(old.wire, 123u, 999u)), generic.wires.toList())
                val facts = file.directAcks().operations(RADIO_A.value, old.id).filter {
                    it.kind == "TRACKED_WIRE_ACCEPTANCE:${old.wire}"
                }
                assertEquals(1, facts.size)
                assertEquals(999L, facts.single().argument)
                repeat(3) {
                    assertEquals(generic, assertIs<DirectAckMutationResult.AlreadyApplied>(
                        target.recordDirectSendAccepted(old.attempt, old.wire, 999u),
                    ).receipt)
                }
                accept(old, physicalCode, target)
                val retained = file.directAcks().operations(RADIO_A.value, old.id)
                assertTrue(retained.containsAll(facts))
                assertEquals(physicalCode.toLong(), file.directAcks().wire(RADIO_A.value, old.id, old.wire)?.acceptedCode)
                target.deleteMessage(EntityKey(RADIO_A, old.row.id))
                assertIs<DirectAckRevocationResult.Revoked>(target.revokeDirectAckSession(old.lease))
                assertNull(file.directAcks().claim(RADIO_A.value, old.id)?.snapshot)
                assertEquals(retained, file.directAcks().operations(RADIO_A.value, old.id))
                target.close()
                file.close()
                file = MeshCoreDatabase.open(context, name)
                target = newStore(file)
                assertNull(target.fetchMessage(EntityKey(RADIO_A, old.row.id)))
                assertEquals(retained, file.directAcks().operations(RADIO_A.value, old.id))
                assertEquals(facts, file.directAcks().operations(RADIO_A.value, old.id).filter {
                    it.kind == "TRACKED_WIRE_ACCEPTANCE:${old.wire}"
                })
                val nextLease = assertIs<DirectAckActivationResult.Activated>(target.activateDirectAckSession(
                    token.copy(epoch = ProcessEpoch(UUID.randomUUID())), UUID.randomUUID(),
                )).lease
                val current = begin(nextLease, target, row = message(contactID = UUID.randomUUID()))
                assertNotEquals(old.row.contactID, current.row.contactID)
                accept(current, 999u, target)
                nonfailing(current, DirectAckRadioNonfailingReason.KNOWN_OTHER_CLAIM_CODE,
                    target = target, database = file)
                val outcome = assertIs<DirectAckRadioSettlementResult.Nonsettling>(
                    target.settleDirectAckExcludingRadioHistory(current.attempt, 999u, 42u),
                )
                assertTrue(DirectAckRadioNonsettlingReason.KNOWN_OTHER_CLAIM_CODE in outcome.reasons)
                if (physicalCode == 888u) {
                    assertTrue(DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION in outcome.reasons)
                    nonfailing(current, DirectAckRadioNonfailingReason.CONFLICTING_OTHER_CLAIM_EVIDENCE,
                        target = target, database = file)
                    assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException> {
                        target.inspectDirectAckRadioEvidence(RADIO_A)
                    }.error)
                    assertTrue(DirectAckRadioConfirmationWildcardReason.CONFLICTING_PHYSICAL_EVIDENCE in
                        assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(
                            target.inspectDirectAckRadioConfirmationEvidence(RADIO_A),
                        ).reasons)
                } else {
                    assertEquals(DirectAckRadioConfirmationEvidence.NoPersistedWildcard(RADIO_A),
                        target.inspectDirectAckRadioConfirmationEvidence(RADIO_A))
                    assertIs<DirectAckRadioEvidenceObservation.Found>(target.inspectDirectAckRadioEvidence(RADIO_A))
                }
                assertEquals(current.row, target.fetchMessage(EntityKey(RADIO_A, current.row.id)))
                assertNull(file.directAcks().delivery(RADIO_A.value, current.id))
            } finally { target.close(); file.close(); context.deleteDatabase(name) }
        }
    }

    @Test fun genericAcceptanceJournalRollbackCommitThenCancellationReplayAndDenialsPreservePublicMeaning() = runTest {
        val lease = activate()
        val claim = begin(lease)
        val before = db.directAcks().operations(RADIO_A.value, claim.id)
        val identity = db.directAcks().incarnation(RADIO_A.value, claim.row.id, "message")
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_acceptance_fact BEFORE INSERT ON direct_ack_operations " +
                "WHEN substr(NEW.kind,1,24)='TRACKED_WIRE_ACCEPTANCE:' " +
                "BEGIN SELECT RAISE(ABORT,'controlled acceptance failure'); END",
        )
        assertFailsWith<PersistenceStoreException> { store.recordDirectSendAccepted(claim.attempt, claim.wire, 999u) }
        assertEquals(1, reported.size)
        assertEquals(before, db.directAcks().operations(RADIO_A.value, claim.id))
        assertNull(db.directAcks().wire(RADIO_A.value, claim.id, claim.wire)?.acceptedCode)
        assertEquals(identity, db.directAcks().incarnation(RADIO_A.value, claim.row.id, "message"))
        assertEquals(claim.row, store.fetchMessage(EntityKey(RADIO_A, claim.row.id)))
        assertEquals(DirectAckPhase.REGISTERED, assertIs<DirectAckObservation.Found>(
            store.inspectDirectAckAttempt(lease, claim.id),
        ).receipt.phase)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_acceptance_fact")
        val caller = Job()
        assertFailsWith<CancellationException> {
            withContext(caller) {
                assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(claim.attempt, claim.wire, 999u))
                caller.cancel()
                ensureActive()
            }
        }
        val committed = db.directAcks().operations(RADIO_A.value, claim.id)
        assertEquals(1, committed.count { it.kind == "TRACKED_WIRE_ACCEPTANCE:${claim.wire}" })
        repeat(3) {
            val replay = assertIs<DirectAckMutationResult.AlreadyApplied>(
                store.recordDirectSendAccepted(claim.attempt, claim.wire, 999u),
            ).receipt
            assertTrue(replay.bookkeeping.isEmpty())
            assertTrue(replay.pendingCompletions.isEmpty())
            assertEquals(listOf(DirectAckWireReceipt(claim.wire, 123u, 999u)), replay.wires.toList())
            assertEquals(committed, db.directAcks().operations(RADIO_A.value, claim.id))
        }
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckMutationResult.Rejected>(
            store.recordDirectSendAccepted(claim.attempt, claim.wire, 888u),
        ).reason)
        assertEquals(DirectAckRejection.MISSING, assertIs<DirectAckMutationResult.Rejected>(
            store.recordDirectSendAccepted(claim.attempt, UUID.randomUUID(), 999u),
        ).reason)
        assertEquals(committed, db.directAcks().operations(RADIO_A.value, claim.id))
        val fact = committed.single { it.kind == "TRACKED_WIRE_ACCEPTANCE:${claim.wire}" }
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckMutationResult.Rejected>(
            store.applyDirectAckBookkeeping(claim.attempt, fact.requestId, DirectAckBookkeeping.RetryStarted(1, 4)),
        ).reason)
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckMutationResult.Rejected>(
            store.registerDirectAckDispatchRequiringEmissionTracking(claim.attempt, fact.requestId, 777u),
        ).reason)
        assertNull(db.directAcks().wire(RADIO_A.value, claim.id, fact.requestId))
        assertEquals(committed, db.directAcks().operations(RADIO_A.value, claim.id))
        for (retire in listOf(false, true)) {
            val stale = begin(lease)
            val original = db.directAcks().operations(RADIO_A.value, stale.id)
            if (retire) assertIs<DirectAckRevocationResult.Revoked>(store.revokeDirectAckSession(lease))
            else db.messages().setStatus(RADIO_A.value, stale.row.id, MessageStatus.SENT.rawValue)
            assertEquals(if (retire) DirectAckRejection.STALE_LEASE else DirectAckRejection.INVALIDATED,
                assertIs<DirectAckMutationResult.Rejected>(
                    store.recordDirectSendAccepted(stale.attempt, stale.wire, 999u),
                ).reason)
            assertEquals(original, db.directAcks().operations(RADIO_A.value, stale.id))
            assertNull(db.directAcks().wire(RADIO_A.value, stale.id, stale.wire)?.acceptedCode)
        }
        assertEquals(DirectAckPermission.STALE_LEASE, assertIs<DirectAckMutationResult.AlreadyApplied>(
            store.recordDirectSendAccepted(claim.attempt, claim.wire, 999u),
        ).receipt.permission)
        assertEquals(committed, db.directAcks().operations(RADIO_A.value, claim.id))
    }

    @Test fun acceptanceBirthInsertionFailureRollsBackBothInitialAndRetryRegistration() = runTest {
        val lease = activate()
        val row = message()
        store.saveMessage(row)
        val id = UUID.randomUUID()
        val wire = UUID.randomUUID()
        val intent = assertIs<DirectAckIntentResult.Captured>(store.captureDirectAckIntent(
            lease, EntityKey(RADIO_A, row.id), id, DirectAckMode.SEND, null,
        )).intent
        val trigger = "CREATE TRIGGER reject_acceptance_birth BEFORE INSERT ON direct_ack_operations " +
            "WHEN substr(NEW.kind,1,28)='ACCEPTANCE_HISTORY_REQUIRED:' " +
            "BEGIN SELECT RAISE(ABORT,'controlled birth failure'); END"
        db.openHelper.writableDatabase.execSQL(trigger)
        assertFailsWith<PersistenceStoreException> {
            store.beginDirectAckAttemptRequiringEmissionTracking(intent, wire, 123u)
        }
        assertNull(db.directAcks().claim(RADIO_A.value, id))
        assertTrue(db.directAcks().wires(RADIO_A.value, id).isEmpty())
        assertTrue(db.directAcks().operations(RADIO_A.value, id).isEmpty())
        assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_acceptance_birth")
        val attempt = assertIs<DirectAckBeginResult.Begun>(
            store.beginDirectAckAttemptRequiringEmissionTracking(intent, wire, 123u),
        ).attempt
        val before = db.directAcks().operations(RADIO_A.value, id)
        assertEquals(2, before.size)
        assertIs<DirectAckBeginResult.AlreadyBegun>(
            store.beginDirectAckAttemptRequiringEmissionTracking(intent, wire, 123u),
        )
        assertEquals(before, db.directAcks().operations(RADIO_A.value, id))
        val retry = UUID.randomUUID()
        db.openHelper.writableDatabase.execSQL(trigger)
        assertFailsWith<PersistenceStoreException> {
            store.registerDirectAckDispatchRequiringEmissionTracking(attempt, retry, 456u)
        }
        assertNull(db.directAcks().wire(RADIO_A.value, id, retry))
        assertEquals(before, db.directAcks().operations(RADIO_A.value, id))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_acceptance_birth")
        assertIs<DirectAckMutationResult.Applied>(
            store.registerDirectAckDispatchRequiringEmissionTracking(attempt, retry, 456u),
        )
        val registered = db.directAcks().operations(RADIO_A.value, id)
        assertEquals(4, registered.size)
        assertIs<DirectAckMutationResult.AlreadyApplied>(
            store.registerDirectAckDispatchRequiringEmissionTracking(attempt, retry, 456u),
        )
        assertEquals(registered, db.directAcks().operations(RADIO_A.value, id))
        assertEquals(2, reported.size)
    }

    @Test fun olderTrackedWireNeverAcquiresAcceptanceBirthCoverageFromReplayMatchingPhysicalOrNewRetry() = runTest {
        val lease = activate()
        val older = begin(lease, tracked = false)
        db.directAcks().operation(DirectAckOperationEntity(
            RADIO_A.value, older.id, older.wire, "EMISSION_TRACKING_REQUIRED", 123, 1, null,
        ))
        assertIs<DirectAckMutationResult.AlreadyApplied>(
            store.registerDirectAckDispatchRequiringEmissionTracking(older.attempt, older.wire, 123u),
        )
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(older.attempt, older.wire, 999u))
        accept(older, 999u)
        nonfailing(older, DirectAckRadioNonfailingReason.TARGET_UNTRACKED_LEGACY_ANCESTRY)
        val retry = UUID.randomUUID()
        assertIs<DirectAckMutationResult.Applied>(
            store.registerDirectAckDispatchRequiringEmissionTracking(older.attempt, retry, 666u),
        )
        accept(older.copy(wire = retry), 666u)
        val own = assertIs<DirectAckRadioSettlementResult.Nonsettling>(
            store.settleDirectAckExcludingRadioHistory(older.attempt, 666u, 42u),
        )
        assertTrue(DirectAckRadioNonsettlingReason.TARGET_ACCEPTANCE_UNRESOLVED in own.reasons)
        val operations = db.directAcks().operations(RADIO_A.value, older.id)
        assertTrue(operations.none { it.kind == "ACCEPTANCE_HISTORY_REQUIRED:${older.wire}" })
        assertEquals(1, operations.count { it.kind == "ACCEPTANCE_HISTORY_REQUIRED:$retry" })
        assertIs<DirectAckMutationResult.AlreadyApplied>(store.recordDirectSendAccepted(older.attempt, older.wire, 999u))
        assertEquals(operations, db.directAcks().operations(RADIO_A.value, older.id))
        val current = begin(lease)
        accept(current, 999u)
        nonfailing(current, DirectAckRadioNonfailingReason.UNTRACKED_OTHER_CLAIM_ANCESTRY,
            DirectAckRadioNonfailingReason.KNOWN_OTHER_CLAIM_CODE)
        val foreign = assertIs<DirectAckRadioSettlementResult.Nonsettling>(
            store.settleDirectAckExcludingRadioHistory(current.attempt, 999u, 42u),
        )
        assertTrue(DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION in foreign.reasons)
        assertTrue(DirectAckRadioConfirmationWildcardReason.UNTRACKED_LEGACY_ANCESTRY in
            assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(
                store.inspectDirectAckRadioConfirmationEvidence(RADIO_A),
            ).reasons)
        assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException> {
            store.inspectDirectAckRadioEvidence(RADIO_A)
        }.error)
    }

    @Test fun committedDeliveryReplayPrecedesUnrelatedMalformedHistoryButTupleAndProofCorruptionDoNot() = runTest {
        val lease = activate()
        val claim = begin(lease)
        accept(claim, 999u)
        val receipt = assertIs<DirectAckRadioSettlementReceipt.Applied>(
            assertIs<DirectAckRadioSettlementResult.Settled>(
                store.settleDirectAckExcludingRadioHistory(claim.attempt, 999u, 42u),
            ).receipt,
        ).receipt
        val foreign = begin(lease)
        val marker = mark(foreign)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE direct_ack_emissions SET state='DEFINITELY_UNSENT',actualCode=123 WHERE markRequestId=?",
            arrayOf<Any?>(marker.receipt.emissionRequestId.toString().uppercase()),
        )
        val operations = db.directAcks().operations(RADIO_A.value, claim.id)
        val identity = db.directAcks().incarnation(RADIO_A.value, claim.row.id, "message")
        assertEquals(receipt, assertIs<DirectAckRadioSettlementReceipt.AlreadyApplied>(
            assertIs<DirectAckRadioSettlementResult.Settled>(
                store.settleDirectAckExcludingRadioHistory(claim.attempt, 999u, 42u),
            ).receipt,
        ).receipt)
        assertEquals(operations, db.directAcks().operations(RADIO_A.value, claim.id))
        assertEquals(identity, db.directAcks().incarnation(RADIO_A.value, claim.row.id, "message"))
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckRadioSettlementResult.Rejected>(
            store.settleDirectAckExcludingRadioHistory(claim.attempt, 999u, 43u),
        ).reason)
        val proof = db.directAcks().radioDeliveries(RADIO_A.value, claim.id).single()
        db.directAcks().operation(proof.copy(pendingId = UUID.randomUUID()))
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckRadioSettlementResult.Rejected>(
            store.settleDirectAckExcludingRadioHistory(claim.attempt, 999u, 42u),
        ).reason)
        db.directAcks().operation(proof)
        val delivery = assertNotNull(db.directAcks().delivery(RADIO_A.value, claim.id))
        db.directAcks().delivery(delivery.copy(ackCode = 888))
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckRadioSettlementResult.Rejected>(
            store.settleDirectAckExcludingRadioHistory(claim.attempt, 999u, 42u),
        ).reason)
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(RADIO_A, claim.row.id))?.status)
        val next = begin(lease)
        accept(next, 888u)
        assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException> {
            store.settleDirectAckExcludingRadioHistory(next.attempt, 888u, 42u)
        }.error)
        assertEquals(next.row, store.fetchMessage(EntityKey(RADIO_A, next.row.id)))
        assertNull(db.directAcks().delivery(RADIO_A.value, next.id))
        assertTrue(db.directAcks().radioDeliveries(RADIO_A.value, next.id).isEmpty())
    }

    @Test fun malformedAcceptanceJournalRemainsExplicitEvenWithMatchingPhysicalAcceptanceAndSaturation() = runTest {
        val lease = activate()
        val claim = begin(lease)
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(claim.attempt, claim.wire, 999u))
        accept(claim, 999u)
        val fact = db.directAcks().operations(RADIO_A.value, claim.id).single {
            it.kind == "TRACKED_WIRE_ACCEPTANCE:${claim.wire}"
        }
        db.directAcks().operation(fact.copy(maximum = 2))
        assertIs<DirectAckMutationResult.AlreadyApplied>(store.recordDirectSendAccepted(claim.attempt, claim.wire, 999u))
        db.directAckExclusions().radio(DirectAckRadioExclusionEntity(RADIO_A.value, true))
        nonfailing(claim, DirectAckRadioNonfailingReason.TARGET_CONFLICTING_PHYSICAL_EVIDENCE,
            DirectAckRadioNonfailingReason.RADIO_HISTORY_SATURATED)
        assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException> {
            store.inspectDirectAckRadioConfirmationEvidence(RADIO_A)
        }.error)
        assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException> {
            store.inspectDirectAckRadioEvidence(RADIO_A)
        }.error)
        assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException> {
            store.settleDirectAckExcludingRadioHistory(claim.attempt, 999u, 42u)
        }.error)
        assertNull(db.directAcks().delivery(RADIO_A.value, claim.id))
        assertEquals(claim.row, store.fetchMessage(EntityKey(RADIO_A, claim.row.id)))
    }

    @Test fun foreignTrackedAcceptedWithoutMarkerBlocksFailureAndSettlementAndCannotBeOmittedFromDiagnostics() = runTest {
        val lease = activate()
        val target = begin(lease)
        accept(target, 888u)
        val foreign = begin(lease)
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(foreign.attempt, foreign.wire, 999u))
        nonfailing(target, DirectAckRadioNonfailingReason.CONFLICTING_OTHER_CLAIM_EVIDENCE)
        val row = store.fetchMessage(EntityKey(RADIO_A, target.row.id))
        val result = assertIs<DirectAckRadioSettlementResult.Nonsettling>(
            store.settleDirectAckExcludingRadioHistory(target.attempt, 888u, 42u),
        )
        assertTrue(DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION in result.reasons)
        assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, target.row.id)))
        assertNull(db.directAcks().delivery(RADIO_A.value, target.id))
        val warning = assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(
            store.inspectDirectAckRadioConfirmationEvidence(RADIO_A),
        )
        assertTrue(DirectAckRadioConfirmationWildcardReason.CONFLICTING_PHYSICAL_EVIDENCE in warning.reasons)
        val unavailable = assertFailsWith<PersistenceStoreException> { store.inspectDirectAckRadioEvidence(RADIO_A) }
        assertEquals(PersistenceStoreError.InvalidData, unavailable.error)
        assertEquals(1, reported.size)
        assertEquals(DirectAckRadioConfirmationEvidence.NoPersistedWildcard(RADIO_B),
            store.inspectDirectAckRadioConfirmationEvidence(RADIO_B))
    }

    @Test fun realPhysicalAcceptanceReconcilesGenericAcceptanceAndLaterUnsentRetryDoesNotContradictIt() = runTest {
        val claim = begin(activate())
        val physical = mark(claim)
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(claim.attempt, claim.wire, 999u))
        nonfailing(claim, DirectAckRadioNonfailingReason.TARGET_CONFLICTING_PHYSICAL_EVIDENCE)
        assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
            physical.handle, UUID.randomUUID(), DirectAckEmissionResolution.Accepted(999u),
        ))
        val retry = mark(claim)
        assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
            retry.handle, UUID.randomUUID(), DirectAckEmissionResolution.DefinitelyUnsent,
        ))
        assertEquals(DirectAckRadioConfirmationEvidence.NoPersistedWildcard(RADIO_A),
            store.inspectDirectAckRadioConfirmationEvidence(RADIO_A))
        assertIs<DirectAckRadioEvidenceObservation.Found>(store.inspectDirectAckRadioEvidence(RADIO_A))
        assertIs<DirectAckRadioFailureResult.Applied>(store.recordDirectFailureExcludingRadioHistory(claim.attempt))
        assertIs<DirectAckRadioSettlementResult.Settled>(
            store.settleDirectAckExcludingRadioHistory(claim.attempt, 999u, 42u),
        )
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(RADIO_A, claim.row.id))?.status)
    }

    @Test fun contradictorySecondTrackedWireBlocksStrictSettlementWithoutChangingGenericCompatibility() = runTest {
        val claim = begin(activate())
        accept(claim, 999u)
        val second = UUID.randomUUID()
        assertIs<DirectAckMutationResult.Applied>(
            store.registerDirectAckDispatchRequiringEmissionTracking(claim.attempt, second, 777u),
        )
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(claim.attempt, second, 888u))
        val row = store.fetchMessage(EntityKey(RADIO_A, claim.row.id))
        val result = assertIs<DirectAckRadioSettlementResult.Nonsettling>(
            store.settleDirectAckExcludingRadioHistory(claim.attempt, 999u, 42u),
        )
        assertTrue(DirectAckRadioNonsettlingReason.TARGET_ACCEPTANCE_UNRESOLVED in result.reasons)
        nonfailing(claim, DirectAckRadioNonfailingReason.TARGET_CONFLICTING_PHYSICAL_EVIDENCE)
        assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, claim.row.id)))
        assertNull(db.directAcks().delivery(RADIO_A.value, claim.id))
        assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(claim.attempt, 999u, 42u))
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(RADIO_A, claim.row.id))?.status)
    }

    @Test fun saturationCoveredAcceptanceIsExpectedMissingPhysicalHistoryNotMalformedData() = runTest {
        val lease = activate()
        val claims = listOf("NONE", "UNSENT", "MAY", "ACCEPTED", "DIFFERENT").associateWith { begin(lease) }
        for ((kind, claim) in claims) {
            when (kind) {
                "UNSENT" -> {
                    val marker = mark(claim)
                    assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
                        marker.handle, UUID.randomUUID(), DirectAckEmissionResolution.DefinitelyUnsent,
                    ))
                }
                "MAY" -> mark(claim)
                "ACCEPTED" -> accept(claim, 999u)
                "DIFFERENT" -> {
                    assertIs<DirectAckMutationResult.Applied>(
                        store.recordDirectSendAccepted(claim.attempt, claim.wire, 999u),
                    )
                    accept(claim, 888u)
                }
            }
        }
        db.directAckExclusions().radio(DirectAckRadioExclusionEntity(RADIO_A.value, true))
        val physicalBefore = db.directAckExclusions().emissions(RADIO_A.value, 100)
        for ((kind, claim) in claims) {
            val coveredId = UUID.randomUUID()
            assertIs<DirectAckEmissionMarkResult.CoveredBySaturation>(
                store.markDirectAckWireMayHaveWritten(claim.attempt, claim.wire, coveredId),
            )
            val accepted = store.recordDirectSendAccepted(claim.attempt, claim.wire, if (kind == "DIFFERENT") 888u else 999u)
            if (kind == "ACCEPTED" || kind == "DIFFERENT") assertIs<DirectAckMutationResult.AlreadyApplied>(accepted)
                else assertIs<DirectAckMutationResult.Applied>(accepted)
            assertNull(db.directAckExclusions().emission(RADIO_A.value, claim.id, claim.wire, coveredId))
            nonfailing(claim, DirectAckRadioNonfailingReason.RADIO_HISTORY_SATURATED)
            val settlement = assertIs<DirectAckRadioSettlementResult.Nonsettling>(
                store.settleDirectAckExcludingRadioHistory(claim.attempt, 999u, 42u),
            )
            assertTrue(DirectAckRadioNonsettlingReason.RADIO_HISTORY_SATURATED in settlement.reasons)
        }
        assertEquals(physicalBefore, db.directAckExclusions().emissions(RADIO_A.value, 100))
        val diagnostic = assertIs<DirectAckRadioEvidenceObservation.Found>(store.inspectDirectAckRadioEvidence(RADIO_A)).snapshot
        assertEquals(DirectAckRadioEvidenceCoverage.SATURATED, diagnostic.coverage)
        val warning = assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(
            store.inspectDirectAckRadioConfirmationEvidence(RADIO_A),
        )
        assertTrue(DirectAckRadioConfirmationWildcardReason.STICKY_SATURATION in warning.reasons)
        assertFalse(DirectAckRadioConfirmationWildcardReason.CONFLICTING_PHYSICAL_EVIDENCE in warning.reasons)
        assertTrue(reported.isEmpty())
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
