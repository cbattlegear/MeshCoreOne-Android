// AndroidOnly: WP-202 Real Room physical-emission history and atomic exclusion settlement.
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
class DirectAckRadioExclusionPersistenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
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
        try { runBlocking { store.close() } } finally { scope.cancel(); db.close() }
    }

    private fun newStore(database: MeshCoreDatabase, ownerScope: CoroutineScope = scope) =
        RoomPersistenceStore(database, ownerScope, RepositoryClock(), RepositoryIssueReporter { _, error -> reported += error })

    private fun immediateBusyFailure(database: MeshCoreDatabase) {
        val sqlite = database.openHelper.writableDatabase
        sqlite.beginTransaction()
        try {
            sqlite.query("PRAGMA busy_timeout=0").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            sqlite.setTransactionSuccessful()
        } finally { sqlite.endTransaction() }
    }

    private suspend fun activate(target: RoomPersistenceStore = store, session: SessionToken = token) =
        assertIs<DirectAckActivationResult.Activated>(target.activateDirectAckSession(session, UUID.randomUUID())).lease

    private data class Claim(
        val lease: DirectAckLease,
        val id: UUID,
        val wire: UUID,
        val intent: DirectAckIntent,
        val attempt: DirectAckAttempt,
        val row: MessageDTO,
    )

    private suspend fun begin(
        lease: DirectAckLease,
        code: UInt = 123u,
        target: RoomPersistenceStore = store,
        row: MessageDTO = message(),
        tracked: Boolean = true,
    ): Claim {
        target.saveMessage(row)
        val id = UUID.randomUUID()
        val wire = UUID.randomUUID()
        val intent = assertIs<DirectAckIntentResult.Captured>(target.captureDirectAckIntent(
            lease, EntityKey(row.radioId, row.id), id, DirectAckMode.SEND, null,
        )).intent
        val result = if (tracked) target.beginDirectAckAttemptRequiringEmissionTracking(intent, wire, code)
            else target.beginDirectAckAttempt(intent, wire, code)
        val attempt = assertIs<DirectAckBeginResult.Begun>(result).attempt
        return Claim(lease, id, wire, intent, attempt, row)
    }

    private suspend fun mark(
        claim: Claim,
        target: RoomPersistenceStore = store,
        wire: UUID = claim.wire,
        emission: UUID = UUID.randomUUID(),
    ) = assertIs<DirectAckEmissionMarkResult.Marked>(
        target.markDirectAckWireMayHaveWritten(claim.attempt, wire, emission),
    )

    private suspend fun accept(
        claim: Claim,
        code: UInt,
        target: RoomPersistenceStore = store,
        wire: UUID = claim.wire,
    ): DirectAckEmissionMarkResult.Marked {
        val marked = mark(claim, target, wire)
        assertIs<DirectAckEmissionMutationResult.Applied>(target.resolveDirectAckWireEmission(
            marked.handle, UUID.randomUUID(), DirectAckEmissionResolution.Accepted(code),
        ))
        return marked
    }

    private suspend fun observed(claim: Claim, target: RoomPersistenceStore = store) =
        assertIs<DirectAckObservation.Found>(target.inspectDirectAckAttempt(claim.lease, claim.id)).receipt

    private suspend fun history(target: RoomPersistenceStore = store, radio: RadioId = RADIO_A) =
        assertIs<DirectAckRadioEvidenceObservation.Found>(target.inspectDirectAckRadioEvidence(radio)).snapshot

    private suspend fun nonsettlingWithoutMutation(
        claim: Claim,
        code: UInt,
        reason: DirectAckRadioNonsettlingReason,
        target: RoomPersistenceStore = store,
        database: MeshCoreDatabase = db,
    ) {
        val key = EntityKey(claim.row.radioId, claim.row.id)
        val row = target.fetchMessage(key)
        val receipt = observed(claim, target)
        val identity = database.directAcks().incarnation(claim.row.radioId.value, claim.row.id, "message")
        val operations = database.directAcks().operations(claim.row.radioId.value, claim.id)
        val result = assertIs<DirectAckRadioSettlementResult.Nonsettling>(
            target.settleDirectAckExcludingRadioHistory(claim.attempt, code, 999u),
        )
        assertTrue(reason in result.reasons)
        assertEquals(row, target.fetchMessage(key))
        assertEquals(receipt, observed(claim, target))
        assertEquals(identity, database.directAcks().incarnation(claim.row.radioId.value, claim.row.id, "message"))
        assertEquals(operations, database.directAcks().operations(claim.row.radioId.value, claim.id))
        assertNull(database.directAcks().delivery(claim.row.radioId.value, claim.id))
    }

    @Test fun ownActualAcceptanceAndFailureSettleAtomicallyAndReplayOnlyExactNewReceipt() = runTest {
        val claim = begin(activate())
        val marked = mark(claim)
        val resolution = UUID.randomUUID()
        val accepted = DirectAckEmissionResolution.Accepted(456u)
        assertIs<DirectAckEmissionMutationResult.Applied>(
            store.resolveDirectAckWireEmission(marked.handle, resolution, accepted),
        )
        assertIs<DirectAckEmissionMutationResult.AlreadyApplied>(
            store.resolveDirectAckWireEmission(marked.handle, resolution, accepted),
        )
        assertEquals(claim.row, store.fetchMessage(EntityKey(RADIO_A, claim.row.id)))
        assertEquals(DirectAckPhase.ACCEPTED, observed(claim).phase)
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectFailure(claim.attempt))
        nonsettlingWithoutMutation(claim, 123u, DirectAckRadioNonsettlingReason.TARGET_CODE_UNREGISTERED)
        val settled = assertIs<DirectAckRadioSettlementResult.Settled>(
            store.settleDirectAckExcludingRadioHistory(claim.attempt, 456u, 42u),
        )
        val applied = assertIs<DirectAckRadioSettlementReceipt.Applied>(settled.receipt)
        val row = assertNotNull(store.fetchMessage(EntityKey(RADIO_A, claim.row.id)))
        assertEquals(MessageStatus.DELIVERED, row.status)
        assertEquals(456u, row.ackCode)
        assertEquals(42u, row.roundTripTime)
        assertEquals(row, applied.receipt.delivery?.message)
        assertTrue(applied.receipt.bookkeeping.isEmpty())
        db.messages().upsert(row.toEntity())
        assertIs<DirectAckRadioSettlementReceipt.AlreadyApplied>(
            assertIs<DirectAckRadioSettlementResult.Settled>(
                store.settleDirectAckExcludingRadioHistory(claim.attempt, 456u, 42u),
            ).receipt,
        )
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckRadioSettlementResult.Rejected>(
            store.settleDirectAckExcludingRadioHistory(claim.attempt, 456u, 43u),
        ).reason)
    }

    @Test fun legacyDeliveryReceiptCannotBeRecastAsExclusionSettlement() = runTest {
        val claim = begin(activate(), tracked = false)
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(claim.attempt, claim.wire, 456u))
        assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(claim.attempt, 456u, 12u))
        val row = store.fetchMessage(EntityKey(RADIO_A, claim.row.id))
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckRadioSettlementResult.Rejected>(
            store.settleDirectAckExcludingRadioHistory(claim.attempt, 456u, 12u),
        ).reason)
        assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, claim.row.id)))
    }

    @Test fun preparedAndLegacyAcceptanceCannotResolveAPhysicalMayMarker() = runTest {
        val claim = begin(activate())
        nonsettlingWithoutMutation(claim, 123u, DirectAckRadioNonsettlingReason.TARGET_CODE_UNREGISTERED)
        val marked = mark(claim)
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(claim.attempt, claim.wire, 456u))
        assertEquals(DirectAckRadioWireOutcome.MayHaveWritten, history().wires.single().outcome)
        nonsettlingWithoutMutation(claim, 456u, DirectAckRadioNonsettlingReason.TARGET_ACCEPTANCE_UNRESOLVED)
        assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
            marked.handle, UUID.randomUUID(), DirectAckEmissionResolution.Accepted(456u),
        ))
        assertIs<DirectAckRadioSettlementResult.Settled>(
            store.settleDirectAckExcludingRadioHistory(claim.attempt, 456u, 1u),
        )
    }

    @Test fun exactMarkerReplayInspectionAndOpaqueIssuerChecksDoNotInventHandles() = runTest {
        val claim = begin(activate())
        val id = UUID.randomUUID()
        assertIs<DirectAckEmissionObservation.NotFound>(store.inspectDirectAckWireEmission(claim.attempt, claim.wire, id))
        val marked = mark(claim, emission = id)
        assertEquals(marked.receipt, assertIs<DirectAckEmissionObservation.Found>(
            store.inspectDirectAckWireEmission(claim.attempt, claim.wire, id),
        ).receipt)
        assertEquals(marked.receipt, assertIs<DirectAckEmissionMarkResult.AlreadyMarked>(
            store.markDirectAckWireMayHaveWritten(claim.attempt, claim.wire, id),
        ).receipt)
        val wire = UUID.randomUUID()
        store.registerDirectAckDispatchRequiringEmissionTracking(claim.attempt, wire, 789u)
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckEmissionMarkResult.Rejected>(
            store.markDirectAckWireMayHaveWritten(claim.attempt, wire, id),
        ).reason)
        assertEquals(DirectAckRejection.INVALID_HANDLE, assertIs<DirectAckEmissionMutationResult.Rejected>(
            store.resolveDirectAckWireEmission(object : DirectAckEmissionHandle {}, UUID.randomUUID(),
                DirectAckEmissionResolution.DefinitelyUnsent),
        ).reason)
        val other = newStore(db)
        try {
            assertIs<DirectAckEmissionMutationResult.Applied>(
                other.resolveDirectAckWireEmission(marked.handle, UUID.randomUUID(),
                    DirectAckEmissionResolution.DefinitelyUnsent),
            )
            assertEquals(DirectAckRejection.INVALID_HANDLE, assertIs<DirectAckRadioSettlementResult.Rejected>(
                other.settleDirectAckExcludingRadioHistory(claim.attempt, 123u, 1u),
            ).reason)
            assertEquals(claim.row, other.fetchMessage(EntityKey(RADIO_A, claim.row.id)))
        } finally { other.close() }
        assertEquals(1, history().wires.size)
    }

    @Test fun trackedCreationIsAtomicAndGenericCrossingOrLegacyUpgradeAlwaysConflicts() = runTest {
        val lease = activate()
        val row = message()
        store.saveMessage(row)
        val id = UUID.randomUUID()
        val wire = UUID.randomUUID()
        val intent = assertIs<DirectAckIntentResult.Captured>(store.captureDirectAckIntent(
            lease, EntityKey(RADIO_A, row.id), id, DirectAckMode.SEND, null,
        )).intent
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_tracking BEFORE INSERT ON direct_ack_operations " +
                "WHEN NEW.kind = 'EMISSION_TRACKING_REQUIRED' BEGIN SELECT RAISE(ABORT,'controlled tracking failure'); END",
        )
        assertFailsWith<PersistenceStoreException> {
            store.beginDirectAckAttemptRequiringEmissionTracking(intent, wire, 123u)
        }
        assertEquals(1, reported.size)
        assertNull(db.directAcks().claim(RADIO_A.value, id))
        assertNull(db.directAcks().wire(RADIO_A.value, id, wire))
        assertNull(db.directAcks().operation(RADIO_A.value, id, wire))
        assertEquals(DirectAckPermission.CURRENT, assertIs<DirectAckIntentObservation.Found>(
            store.inspectDirectAckIntent(lease, id),
        ).receipt.permission)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_tracking")
        val begun = assertIs<DirectAckBeginResult.Begun>(
            store.beginDirectAckAttemptRequiringEmissionTracking(intent, wire, 123u),
        )
        assertIs<DirectAckBeginResult.AlreadyBegun>(
            store.beginDirectAckAttemptRequiringEmissionTracking(intent, wire, 123u),
        )
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckBeginResult.Rejected>(
            store.beginDirectAckAttempt(intent, wire, 123u),
        ).reason)
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckBeginResult.Rejected>(
            store.beginDirectAckAttemptRequiringEmissionTracking(intent, wire, 124u),
        ).reason)
        val retry = UUID.randomUUID()
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckMutationResult.Rejected>(
            store.registerDirectAckDispatch(begun.attempt, retry, 789u),
        ).reason)
        assertNull(db.directAcks().wire(RADIO_A.value, id, retry))
        assertIs<DirectAckMutationResult.Applied>(
            store.registerDirectAckDispatchRequiringEmissionTracking(begun.attempt, retry, 789u),
        )
        assertIs<DirectAckMutationResult.AlreadyApplied>(
            store.registerDirectAckDispatchRequiringEmissionTracking(begun.attempt, retry, 789u),
        )
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckMutationResult.Rejected>(
            store.registerDirectAckDispatchRequiringEmissionTracking(begun.attempt, retry, 790u),
        ).reason)
        assertTrue(history().wires.isEmpty())

        val legacy = begin(lease, tracked = false)
        assertIs<DirectAckBeginResult.AlreadyBegun>(
            store.beginDirectAckAttempt(legacy.intent, legacy.wire, 123u),
        )
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckBeginResult.Rejected>(
            store.beginDirectAckAttemptRequiringEmissionTracking(legacy.intent, legacy.wire, 123u),
        ).reason)
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckMutationResult.Rejected>(
            store.registerDirectAckDispatchRequiringEmissionTracking(legacy.attempt, UUID.randomUUID(), 456u),
        ).reason)
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckEmissionMarkResult.Rejected>(
            store.markDirectAckWireMayHaveWritten(legacy.attempt, legacy.wire, UUID.randomUUID()),
        ).reason)
        assertNull(db.directAcks().operation(RADIO_A.value, legacy.id, legacy.wire))
        assertEquals(DirectAckRadioWireOutcome.LegacyUnknown, history().wires.single().outcome)
        assertNull(history().wires.single().emissionRequestId)
    }

    @Test fun trackedNeverInvokedWiresDoNotCompeteEvenWhenPredictionMatchesAck() = runTest {
        val lease = activate()
        val neverInvoked = begin(lease, 999u)
        val retry = UUID.randomUUID()
        assertIs<DirectAckMutationResult.Applied>(
            store.registerDirectAckDispatchRequiringEmissionTracking(neverInvoked.attempt, retry, 999u),
        )
        assertTrue(history().wires.isEmpty())
        nonsettlingWithoutMutation(neverInvoked, 999u, DirectAckRadioNonsettlingReason.TARGET_CODE_UNREGISTERED)
        val target = begin(lease, 888u)
        accept(target, 999u)
        assertIs<DirectAckRadioSettlementResult.Settled>(
            store.settleDirectAckExcludingRadioHistory(target.attempt, 999u, 42u),
        )
        assertEquals(1, history().wires.size)
        assertEquals(0, db.directAckExclusions().legacyCount(RADIO_A.value).toInt())
    }

    @Test fun legacyHistorySurvivesLaterUnsentMarkerAndKnownAcceptanceAcrossReopen() = runTest {
        for (accepted in listOf(false, true)) {
            val name = "radio-exclusion-legacy-${UUID.randomUUID()}.db"
            var file = MeshCoreDatabase.open(context, name)
            var target = newStore(file)
            try {
                val old = begin(activate(target), target = target, tracked = false)
                if (accepted) assertIs<DirectAckMutationResult.Applied>(
                    target.recordDirectSendAccepted(old.attempt, old.wire, 456u),
                )
                val physical = UUID.randomUUID()
                file.directAckExclusions().emission(DirectAckEmissionEntity(
                    RADIO_A.value, old.id, old.wire, UUID.randomUUID(), physical,
                    123, "DEFINITELY_UNSENT", null, UUID.randomUUID(),
                ))
                target.close()
                file.close()
                file = MeshCoreDatabase.open(context, name)
                target = newStore(file)
                val nextToken = token.copy(epoch = ProcessEpoch(UUID.randomUUID()))
                val next = begin(activate(target, nextToken), 888u, target)
                accept(next, 999u, target)
                val legacy = history(target).wires.single { it.emissionRequestId == null }
                assertEquals(old.id, legacy.claimRequestId)
                assertEquals(if (accepted) DirectAckRadioWireOutcome.LegacyAcceptedUnknown(456u)
                    else DirectAckRadioWireOutcome.LegacyUnknown, legacy.outcome)
                assertEquals(DirectAckRadioWireOutcome.DefinitelyUnsent,
                    history(target).wires.single { it.emissionRequestId == physical }.outcome)
                nonsettlingWithoutMutation(next, 999u,
                    DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION, target, file)
            } finally { target.close(); file.close(); context.deleteDatabase(name) }
        }
    }

    @Test fun sameWirePhysicalRetryCannotEraseEarlierUnknownAndEarlierAcceptedCodeStaysEligible() = runTest {
        val lease = activate()
        val foreign = begin(lease)
        val first = mark(foreign)
        val second = mark(foreign)
        assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
            second.handle, UUID.randomUUID(), DirectAckEmissionResolution.DefinitelyUnsent,
        ))
        val target = begin(lease, 888u)
        accept(target, 999u)
        nonsettlingWithoutMutation(target, 999u, DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION)
        assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
            first.handle, UUID.randomUUID(), DirectAckEmissionResolution.Accepted(456u),
        ))
        assertIs<DirectAckRadioSettlementResult.Settled>(
            store.settleDirectAckExcludingRadioHistory(target.attempt, 999u, 1u),
        )
        val nextPhysical = accept(foreign, 789u)
        val evidence = history().wires.filter { it.claimRequestId == foreign.id }
        assertEquals(3, evidence.size)
        assertTrue(evidence.any { it.outcome == DirectAckRadioWireOutcome.Accepted(456u) })
        assertTrue(evidence.any { it.outcome == DirectAckRadioWireOutcome.Accepted(789u) })
        assertEquals(DirectAckRadioWireOutcome.Accepted(789u),
            evidence.single { it.emissionRequestId == nextPhysical.receipt.emissionRequestId }.outcome)
        assertIs<DirectAckRadioSettlementResult.Settled>(
            store.settleDirectAckExcludingRadioHistory(foreign.attempt, 456u, 2u),
        )
    }

    @Test fun knownForeignPredictionAndActualBothCompeteButDifferentRadioDoesNot() = runTest {
        val lease = activate()
        val foreign = begin(lease, 123u)
        accept(foreign, 456u)
        for (code in listOf(123u, 456u)) {
            val target = begin(lease, code)
            accept(target, code)
            nonsettlingWithoutMutation(target, code, DirectAckRadioNonsettlingReason.KNOWN_OTHER_CLAIM_CODE)
        }
        val otherToken = token.copy(radioId = RADIO_B)
        val otherLease = activate(session = otherToken)
        val other = begin(otherLease, 123u, row = message(radio = RADIO_B))
        accept(other, 123u)
        assertIs<DirectAckRadioSettlementResult.Settled>(
            store.settleDirectAckExcludingRadioHistory(other.attempt, 123u, 1u),
        )
        val disjoint = begin(lease, 888u)
        accept(disjoint, 999u)
        assertIs<DirectAckRadioSettlementResult.Settled>(
            store.settleDirectAckExcludingRadioHistory(disjoint.attempt, 999u, 1u),
        )
    }

    @Test fun markerlessUnknownRemainsWildcardAndMarkerlessKnownRetainsBothCodes() = runTest {
        val lease = activate()
        val old = begin(lease, tracked = false)
        val target = begin(lease, 888u)
        accept(target, 999u)
        val snapshot = history()
        assertEquals(DirectAckRadioEvidenceCoverage.COMPLETE, snapshot.coverage)
        assertEquals(DirectAckRadioWireOutcome.LegacyUnknown,
            snapshot.wires.single { it.claimRequestId == old.id }.outcome)
        nonsettlingWithoutMutation(target, 999u, DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION)
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(old.attempt, old.wire, 456u))
        assertEquals(DirectAckRadioWireOutcome.LegacyAcceptedUnknown(456u),
            history().wires.single { it.claimRequestId == old.id }.outcome)
        nonsettlingWithoutMutation(target, 999u, DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION)
        assertTrue(db.directAckExclusions().competingCode(RADIO_A.value, target.id, 123))
        assertTrue(db.directAckExclusions().competingCode(RADIO_A.value, target.id, 456))
    }

    @Test fun contradictoryExactEmissionFactsStayWildcardAndDoNotEraseFirstAcceptedCode() = runTest {
        val lease = activate()
        val foreign = begin(lease)
        val first = accept(foreign, 456u)
        val request = UUID.randomUUID()
        val conflict = assertIs<DirectAckEmissionMutationResult.Conflict>(store.resolveDirectAckWireEmission(
            first.handle, request, DirectAckEmissionResolution.DefinitelyUnsent,
        ))
        assertEquals(DirectAckRadioWireOutcome.Conflict(456u, true), conflict.receipt.evidence.outcome)
        assertIs<DirectAckEmissionMutationResult.Conflict>(store.resolveDirectAckWireEmission(
            first.handle, request, DirectAckEmissionResolution.Accepted(789u),
        ))
        assertEquals(DirectAckRadioWireOutcome.Conflict(456u, true),
            history().wires.single().outcome)
        val target = begin(lease, 888u)
        accept(target, 999u)
        nonsettlingWithoutMutation(target, 999u, DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION)
    }

    @Test fun acceptedAfterUnsentAndChangedInputForSameResolutionIdArePersistedConflicts() = runTest {
        val claim = begin(activate())
        val marked = mark(claim)
        val request = UUID.randomUUID()
        assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
            marked.handle, request, DirectAckEmissionResolution.DefinitelyUnsent,
        ))
        val conflict = assertIs<DirectAckEmissionMutationResult.Conflict>(store.resolveDirectAckWireEmission(
            marked.handle, request, DirectAckEmissionResolution.Accepted(456u),
        ))
        assertEquals(DirectAckRadioWireOutcome.Conflict(456u, true), conflict.receipt.evidence.outcome)
        nonsettlingWithoutMutation(claim, 456u, DirectAckRadioNonsettlingReason.TARGET_ACCEPTANCE_UNRESOLVED)
    }

    @Test fun everyResolutionIdentityReplaysAcrossFileReopenWithoutClearingLaterConflictOrMetadata() = runTest {
        val name = "radio-exclusion-resolution-journal-${UUID.randomUUID()}.db"
        var file = MeshCoreDatabase.open(context, name)
        var target = newStore(file)
        try {
            val claim = begin(activate(target), target = target)
            val first = mark(claim, target)
            val second = mark(claim, target)
            val r1 = UUID.randomUUID()
            val r2 = UUID.randomUUID()
            val r3 = UUID.randomUUID()
            val accepted = DirectAckEmissionResolution.Accepted(123u)
            assertIs<DirectAckEmissionMutationResult.Applied>(target.resolveDirectAckWireEmission(first.handle, r1, accepted))
            assertIs<DirectAckEmissionMutationResult.Applied>(target.resolveDirectAckWireEmission(first.handle, r2, accepted))
            assertIs<DirectAckEmissionMutationResult.Applied>(target.resolveDirectAckWireEmission(
                second.handle, r3, DirectAckEmissionResolution.Accepted(456u),
            ))
            val firstOperation = assertNotNull(file.directAcks().operation(RADIO_A.value, claim.id, r1))
            val secondOperation = assertNotNull(file.directAcks().operation(RADIO_A.value, claim.id, r2))
            assertEquals(123L, firstOperation.argument)
            assertEquals(123L, firstOperation.maximum)
            assertTrue(firstOperation.kind.endsWith(":ACCEPTED"))
            target.close()
            file.close()
            file = MeshCoreDatabase.open(context, name)
            target = newStore(file)
            val before = history(target)
            val replay = assertIs<DirectAckEmissionMutationResult.AlreadyApplied>(
                target.resolveDirectAckWireEmission(first.handle, r1, accepted),
            )
            assertEquals(r2, replay.receipt.resolutionRequestId)
            assertEquals(before, history(target))
            assertEquals(456L, file.directAcks().wire(RADIO_A.value, claim.id, claim.wire)?.acceptedCode)
            assertEquals(DirectAckRejection.INVALID_HANDLE, assertIs<DirectAckRadioSettlementResult.Rejected>(
                target.settleDirectAckExcludingRadioHistory(claim.attempt, 123u, 42u),
            ).reason)

            val conflict = assertIs<DirectAckEmissionMutationResult.Conflict>(
                target.resolveDirectAckWireEmission(first.handle, r1, DirectAckEmissionResolution.Accepted(789u)),
            )
            assertEquals(DirectAckRadioWireOutcome.Conflict(123u, false), conflict.receipt.evidence.outcome)
            assertEquals(firstOperation, file.directAcks().operation(RADIO_A.value, claim.id, r1))
            assertEquals(DirectAckRadioWireOutcome.Conflict(123u, false),
                assertIs<DirectAckEmissionMutationResult.AlreadyApplied>(
                    target.resolveDirectAckWireEmission(first.handle, r1, accepted),
                ).receipt.evidence.outcome)
            assertIs<DirectAckEmissionMutationResult.Conflict>(
                target.resolveDirectAckWireEmission(first.handle, r1, DirectAckEmissionResolution.DefinitelyUnsent),
            )
            assertIs<DirectAckEmissionMutationResult.Conflict>(
                target.resolveDirectAckWireEmission(second.handle, r1, DirectAckEmissionResolution.Accepted(456u)),
            )
            assertEquals(firstOperation, file.directAcks().operation(RADIO_A.value, claim.id, r1))
            assertEquals(DirectAckRadioWireOutcome.Conflict(456u, false),
                assertIs<DirectAckEmissionMutationResult.AlreadyApplied>(target.resolveDirectAckWireEmission(
                    second.handle, r3, DirectAckEmissionResolution.Accepted(456u),
                )).receipt.evidence.outcome)
            val r4 = UUID.randomUUID()
            assertIs<DirectAckEmissionMutationResult.Conflict>(
                target.resolveDirectAckWireEmission(first.handle, r4, DirectAckEmissionResolution.DefinitelyUnsent),
            )
            assertNotNull(file.directAcks().operation(RADIO_A.value, claim.id, r4))
            target.close()
            file.close()
            file = MeshCoreDatabase.open(context, name)
            target = newStore(file)
            val final = assertIs<DirectAckEmissionMutationResult.AlreadyApplied>(
                target.resolveDirectAckWireEmission(first.handle, r2, accepted),
            )
            assertEquals(r4, final.receipt.resolutionRequestId)
            assertEquals(DirectAckRadioWireOutcome.Conflict(123u, true), final.receipt.evidence.outcome)
            assertEquals(final.receipt, assertIs<DirectAckEmissionMutationResult.Conflict>(
                target.resolveDirectAckWireEmission(first.handle, r4, DirectAckEmissionResolution.DefinitelyUnsent),
            ).receipt)
            assertEquals(secondOperation, file.directAcks().operation(RADIO_A.value, claim.id, r2))
            assertEquals(claim.row, target.fetchMessage(EntityKey(RADIO_A, claim.row.id)))
            assertNull(file.directAcks().delivery(RADIO_A.value, claim.id))
        } finally { target.close(); file.close(); context.deleteDatabase(name) }
    }

    @Test fun genericDaoRawSqlAndRecreationInvalidateOnlyProductGrantNotRadioHistory() = runTest {
        val lease = activate()
        for (mutation in listOf<suspend (MessageDTO) -> Unit>(
            { db.messages().setStatus(RADIO_A.value, it.id, 4) },
            { db.messages().upsert(it.toEntity()) },
            { store.saveMessage(it) },
            { db.messages().delete(RADIO_A.value, it.id); db.messages().insert(it.toEntity()) },
            { db.openHelper.writableDatabase.execSQL("UPDATE messages SET statusRawValue=statusRawValue") },
            { db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO messages SELECT * FROM messages") },
        )) {
            val claim = begin(lease)
            accept(claim, 456u)
            assertIs<DirectAckMutationResult.Applied>(store.recordDirectFailure(claim.attempt))
            val emissions = db.directAckExclusions().emissions(RADIO_A.value, 4096)
            mutation(assertNotNull(store.fetchMessage(EntityKey(RADIO_A, claim.row.id))))
            val independent = store.fetchMessage(EntityKey(RADIO_A, claim.row.id))
            assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckRadioSettlementResult.Rejected>(
                store.settleDirectAckExcludingRadioHistory(claim.attempt, 456u, 999u),
            ).reason)
            assertEquals(independent, store.fetchMessage(EntityKey(RADIO_A, claim.row.id)))
            assertNull(db.directAcks().delivery(RADIO_A.value, claim.id))
            assertEquals(emissions, db.directAckExclusions().emissions(RADIO_A.value, 4096))
        }
    }

    @Test fun lateAcceptanceAfterRestoreOrLeaseRetirementRefinesHistoryWithoutRenewingProductAuthority() = runTest {
        val lease = activate()
        val old = begin(lease)
        val marked = mark(old)
        store.withBackupRestore { _, _ -> Unit }
        val before = observed(old)
        val row = store.fetchMessage(EntityKey(RADIO_A, old.row.id))
        val result = assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
            marked.handle, UUID.randomUUID(), DirectAckEmissionResolution.Accepted(456u),
        ))
        assertEquals(DirectAckRadioWireOutcome.Accepted(456u), result.receipt.evidence.outcome)
        assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, old.row.id)))
        assertEquals(before.phase, observed(old).phase)
        assertEquals(DirectAckPermission.INVALIDATED, observed(old).permission)
        assertEquals(456u, observed(old).wires.single().acceptedAckCode)
        val nextLease = activate(session = token.copy(generation = Generation(2)))
        val next = begin(nextLease, 888u)
        val pending = mark(next)
        assertIs<DirectAckRevocationResult.Revoked>(store.revokeDirectAckSession(nextLease))
        assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
            pending.handle, UUID.randomUUID(), DirectAckEmissionResolution.Accepted(999u),
        ))
        assertEquals(DirectAckPermission.STALE_LEASE, observed(next).permission)
        assertEquals(DirectAckPhase.REGISTERED, observed(next).phase)
        assertEquals(next.row, store.fetchMessage(EntityKey(RADIO_A, next.row.id)))
        assertEquals(2, history().wires.size)
    }

    @Test fun oldUnknownSurvivesProcessCloseNewGraphFileReopenAndRestore() = runTest {
        val name = "radio-exclusion-reopen-${UUID.randomUUID()}.db"
        var file = MeshCoreDatabase.open(context, name)
        var ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var target = newStore(file, ownerScope)
        try {
            val old = begin(activate(target), target = target)
            val unresolved = mark(old, target).receipt.evidence
            target.close()
            ownerScope.cancel()
            file.close()
            file = MeshCoreDatabase.open(context, name)
            ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            target = newStore(file, ownerScope)
            assertEquals(unresolved, history(target).wires.single())
            val nextToken = token.copy(epoch = ProcessEpoch(UUID.randomUUID()))
            val next = begin(activate(target, nextToken), 888u, target)
            accept(next, 999u, target)
            nonsettlingWithoutMutation(next, 999u,
                DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION, target, file)
            assertEquals(unresolved, history(target).wires.single { it.claimRequestId == old.id })
            target.withBackupRestore { _, _ -> Unit }
            assertEquals(DirectAckRadioWireOutcome.MayHaveWritten,
                history(target).wires.single { it.claimRequestId == old.id }.outcome)
            val afterRestore = begin(assertIs<DirectAckActivationResult.Activated>(
                target.activateDirectAckSession(nextToken.copy(generation = Generation(2)), UUID.randomUUID()),
            ).lease, 777u, target)
            accept(afterRestore, 666u, target)
            nonsettlingWithoutMutation(afterRestore, 666u,
                DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION, target, file)
        } finally {
            target.close()
            ownerScope.cancel()
            file.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun markerWriteFailureAndCancellationAreExplicitAndCannotFabricateCommittedEvidence() = runTest {
        val claim = begin(activate())
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_emission BEFORE INSERT ON direct_ack_emissions " +
                "BEGIN SELECT RAISE(ABORT,'controlled emission failure'); END",
        )
        assertFailsWith<PersistenceStoreException> {
            store.markDirectAckWireMayHaveWritten(claim.attempt, claim.wire, UUID.randomUUID())
        }
        assertEquals(1, reported.size)
        assertEquals(0, db.directAckExclusions().count(RADIO_A.value).toInt())
        assertEquals(claim.row, store.fetchMessage(EntityKey(RADIO_A, claim.row.id)))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_emission")

        var cancelOnMarker: Job? = null
        val local = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .addCallback(DirectAckStorage.callback).allowMainThreadQueries()
            .setQueryExecutor(immediate).setTransactionExecutor(immediate)
            .setQueryCallback({ sql, _ ->
                if (sql.contains("INTO `direct_ack_emissions`")) cancelOnMarker?.cancel()
            }, immediate).build()
        val target = newStore(local)
        try {
            val localClaim = begin(activate(target), target = target)
            val emission = UUID.randomUUID()
            val caller = Job()
            cancelOnMarker = caller
            assertFailsWith<CancellationException> {
                withContext(caller) {
                    target.markDirectAckWireMayHaveWritten(localClaim.attempt, localClaim.wire, emission)
                }
            }
            cancelOnMarker = null
            assertIs<DirectAckEmissionObservation.NotFound>(
                target.inspectDirectAckWireEmission(localClaim.attempt, localClaim.wire, emission),
            )
            assertEquals(0, local.directAckExclusions().count(RADIO_A.value).toInt())
        } finally { cancelOnMarker = null; target.close(); local.close() }
    }

    @Test fun cancellationAfterCommittedMarkerRecoversExactMetadataWithoutTransportReplay() = runTest {
        val claim = begin(activate())
        val emission = UUID.randomUUID()
        val caller = Job()
        assertFailsWith<CancellationException> {
            withContext(caller) {
                store.markDirectAckWireMayHaveWritten(claim.attempt, claim.wire, emission)
                caller.cancel()
                ensureActive()
            }
        }
        assertIs<DirectAckEmissionObservation.Found>(
            store.inspectDirectAckWireEmission(claim.attempt, claim.wire, emission),
        )
        val recovered = assertIs<DirectAckEmissionMarkResult.AlreadyMarked>(
            store.markDirectAckWireMayHaveWritten(claim.attempt, claim.wire, emission),
        )
        assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
            recovered.handle, UUID.randomUUID(), DirectAckEmissionResolution.DefinitelyUnsent,
        ))
        assertEquals(1, db.directAckExclusions().count(RADIO_A.value).toInt())
    }

    @Test fun resolverAndSettlementFailuresRollBackWireMetadataEvidenceAndDeliveryTogether() = runTest {
        val claim = begin(activate())
        val marked = mark(claim)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_resolution BEFORE UPDATE ON direct_ack_emissions " +
                "BEGIN SELECT RAISE(ABORT,'controlled resolution failure'); END",
        )
        val request = UUID.randomUUID()
        val accepted = DirectAckEmissionResolution.Accepted(456u)
        assertFailsWith<PersistenceStoreException> {
            store.resolveDirectAckWireEmission(marked.handle, request, accepted)
        }
        assertEquals(1, reported.size)
        assertNull(db.directAcks().wire(RADIO_A.value, claim.id, claim.wire)?.acceptedCode)
        assertEquals(DirectAckPhase.REGISTERED, observed(claim).phase)
        assertEquals(marked.receipt, assertIs<DirectAckEmissionObservation.Found>(
            store.inspectDirectAckWireEmission(claim.attempt, claim.wire, marked.receipt.emissionRequestId),
        ).receipt)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_resolution")
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_resolution_journal BEFORE INSERT ON direct_ack_operations " +
                "WHEN NEW.kind LIKE 'EMISSION_RESOLUTION:%' BEGIN SELECT RAISE(ABORT,'controlled resolution journal failure'); END",
        )
        assertFailsWith<PersistenceStoreException> {
            store.resolveDirectAckWireEmission(marked.handle, request, accepted)
        }
        assertEquals(2, reported.size)
        assertNull(db.directAcks().wire(RADIO_A.value, claim.id, claim.wire)?.acceptedCode)
        assertNull(db.directAcks().operation(RADIO_A.value, claim.id, request))
        assertEquals(DirectAckPhase.REGISTERED, observed(claim).phase)
        assertEquals(marked.receipt, assertIs<DirectAckEmissionObservation.Found>(
            store.inspectDirectAckWireEmission(claim.attempt, claim.wire, marked.receipt.emissionRequestId),
        ).receipt)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_resolution_journal")
        assertIs<DirectAckEmissionMutationResult.Applied>(
            store.resolveDirectAckWireEmission(marked.handle, request, accepted),
        )

        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_radio_delivery BEFORE INSERT ON direct_ack_operations " +
                "WHEN NEW.kind = 'RADIO_DELIVERY' BEGIN SELECT RAISE(ABORT,'controlled settlement failure'); END",
        )
        assertFailsWith<PersistenceStoreException> {
            store.settleDirectAckExcludingRadioHistory(claim.attempt, 456u, 42u)
        }
        assertEquals(3, reported.size)
        assertEquals(claim.row, store.fetchMessage(EntityKey(RADIO_A, claim.row.id)))
        assertEquals(DirectAckPhase.ACCEPTED, observed(claim).phase)
        assertNull(db.directAcks().delivery(RADIO_A.value, claim.id))
        assertTrue(db.directAcks().radioDeliveries(RADIO_A.value, claim.id).isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_radio_delivery")
        assertIs<DirectAckRadioSettlementReceipt.Applied>(assertIs<DirectAckRadioSettlementResult.Settled>(
            store.settleDirectAckExcludingRadioHistory(claim.attempt, 456u, 42u),
        ).receipt)
    }

    @Test fun preexistingStickyWildcardIsNotClearedAndDoesNotFabricateAMarkerHandle() = runTest {
        val claim = begin(activate())
        val last = accept(claim, 456u)
        assertEquals(DirectAckRadioEvidenceCoverage.COMPLETE, history().coverage)
        assertFalse(db.directAckExclusions().saturated(RADIO_A.value))
        db.directAckExclusions().radio(DirectAckRadioExclusionEntity(RADIO_A.value, true))
        val overflow = UUID.randomUUID()
        assertIs<DirectAckEmissionMarkResult.CoveredBySaturation>(
            store.markDirectAckWireMayHaveWritten(claim.attempt, claim.wire, overflow),
        )
        assertIs<DirectAckEmissionObservation.CoveredBySaturation>(
            store.inspectDirectAckWireEmission(claim.attempt, claim.wire, overflow),
        )
        assertEquals(1, db.directAckExclusions().count(RADIO_A.value).toInt())
        assertEquals(DirectAckRadioEvidenceCoverage.SATURATED, history().coverage)
        assertEquals(1, history().wires.size)
        nonsettlingWithoutMutation(claim, 456u, DirectAckRadioNonsettlingReason.RADIO_HISTORY_SATURATED)
        assertIs<DirectAckEmissionMarkResult.AlreadyMarked>(store.markDirectAckWireMayHaveWritten(
            claim.attempt, claim.wire, last.receipt.emissionRequestId,
        ))
        store.withBackupRestore { _, _ -> Unit }
        val next = begin(activate(session = token.copy(generation = Generation(2))))
        assertIs<DirectAckEmissionMarkResult.CoveredBySaturation>(
            store.markDirectAckWireMayHaveWritten(next.attempt, next.wire, UUID.randomUUID()),
        )
        assertTrue(db.directAckExclusions().saturated(RADIO_A.value))
    }

    @Test fun moreThan4096KnownAndUnsentEmissionsRetainExactReplayAndSelectiveLivenessAcrossReopen() =
        runTest(timeout = 180.seconds) {
            val name = "radio-exclusion-known-history-${UUID.randomUUID()}.db"
            var file = MeshCoreDatabase.open(context, name)
            var target = newStore(file)
            try {
                val old = begin(activate(target), target = target)
                val first = mark(old, target)
                val resolution = UUID.randomUUID()
                val accepted = DirectAckEmissionResolution.Accepted(456u)
                assertIs<DirectAckEmissionMutationResult.Applied>(
                    target.resolveDirectAckWireEmission(first.handle, resolution, accepted),
                )
                repeat(4099) { index ->
                    val physical = mark(old, target)
                    assertIs<DirectAckEmissionMutationResult.Applied>(target.resolveDirectAckWireEmission(
                        physical.handle, UUID.randomUUID(),
                        if (index % 2 == 0) DirectAckEmissionResolution.DefinitelyUnsent else accepted,
                    ))
                    if (index == 4094) {
                        assertEquals(4096, file.directAckExclusions().count(RADIO_A.value).toInt())
                        assertEquals(DirectAckRadioEvidenceCoverage.COMPLETE, history(target).coverage)
                        assertFalse(file.directAckExclusions().saturated(RADIO_A.value))
                    }
                }
                assertEquals(4100, file.directAckExclusions().count(RADIO_A.value).toInt())
                assertEquals(4096, history(target).wires.size)
                assertEquals(DirectAckRadioEvidenceCoverage.TRUNCATED, history(target).coverage)
                assertFalse(file.directAckExclusions().saturated(RADIO_A.value))
                assertIs<DirectAckEmissionMutationResult.AlreadyApplied>(
                    target.resolveDirectAckWireEmission(first.handle, resolution, accepted),
                )
                val persisted = assertNotNull(file.directAckExclusions().emission(
                    RADIO_A.value, old.id, old.wire, first.receipt.emissionRequestId,
                ))
                target.close()
                file.close()
                file = MeshCoreDatabase.open(context, name)
                target = newStore(file)
                assertEquals(persisted, file.directAckExclusions().emission(
                    RADIO_A.value, old.id, old.wire, first.receipt.emissionRequestId,
                ))
                val nextToken = token.copy(epoch = ProcessEpoch(UUID.randomUUID()))
                val next = begin(activate(target, nextToken), 888u, target)
                accept(next, 999u, target)
                assertIs<DirectAckRadioSettlementResult.Settled>(
                    target.settleDirectAckExcludingRadioHistory(next.attempt, 999u, 42u),
                )
                assertEquals(MessageStatus.DELIVERED,
                    assertNotNull(target.fetchMessage(EntityKey(RADIO_A, next.row.id))).status)
                assertFalse(file.directAckExclusions().saturated(RADIO_A.value))

                val unknown = begin(next.lease, 777u, target)
                val unknownId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
                mark(unknown, target, emission = unknownId)
                target.close()
                file.close()
                file = MeshCoreDatabase.open(context, name)
                target = newStore(file)
                val finalToken = token.copy(epoch = ProcessEpoch(UUID.randomUUID()))
                val final = begin(activate(target, finalToken), 222u, target)
                accept(final, 111u, target)
                assertTrue(history(target).wires.size <= 4096)
                assertEquals(DirectAckRadioEvidenceCoverage.TRUNCATED, history(target).coverage)
                nonsettlingWithoutMutation(final, 111u,
                    DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION, target, file)
                assertEquals("MAY_HAVE_WRITTEN", assertNotNull(file.directAckExclusions().emission(
                    RADIO_A.value, unknown.id, unknown.wire, unknownId,
                )).state)
            } finally { target.close(); file.close(); context.deleteDatabase(name) }
        }

    @Test fun boundedDiagnosticTruncationIsNotASettlementGrant() = runTest {
        val claim = begin(activate())
        accept(claim, 456u)
        db.withTransaction {
            repeat(4096) {
                val wire = DirectAckWireEntity(RADIO_A.value, UUID.randomUUID(), UUID.randomUUID(), 999, 999)
                db.directAcks().wire(wire)
                db.directAcks().operation(DirectAckOperationEntity(
                    wire.radioId, wire.claimId, wire.requestId, "EMISSION_TRACKING_REQUIRED", wire.predictedCode, 1, null,
                ))
                db.directAckExclusions().emission(DirectAckEmissionEntity(
                    wire.radioId, wire.claimId, wire.requestId, UUID.randomUUID(), UUID.randomUUID(),
                    wire.predictedCode, "ACCEPTED", 999, UUID.randomUUID(),
                ))
            }
        }
        val snapshot = history()
        assertEquals(4096, snapshot.wires.size)
        assertEquals(DirectAckRadioEvidenceCoverage.TRUNCATED, snapshot.coverage)
        assertIs<DirectAckRadioSettlementResult.Settled>(
            store.settleDirectAckExcludingRadioHistory(claim.attempt, 456u, 1u),
        )
    }

    @Test fun staleCleanDiagnosticCannotBypassCompetitorCommittedOnSeparateRoomHandle() = runTest {
        val name = "radio-exclusion-race-${UUID.randomUUID()}.db"
        val first = MeshCoreDatabase.open(context, name)
        val second = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .addCallback(DirectAckStorage.callback).build()
        val target = newStore(second)
        try {
            val claim = begin(activate(target), 888u, target)
            accept(claim, 999u, target)
            immediateBusyFailure(second)
            assertEquals(emptySet(), DirectAckRadioEvidenceAssessment.nonsettlingReasons(
                claim.id, 999u, history(target),
            ).toSet())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            withContext(Dispatchers.IO) {
                withTimeout(30_000) {
                    val competitor = async(Dispatchers.IO) {
                        first.withTransaction {
                            first.directAcks().wire(DirectAckWireEntity(
                                RADIO_A.value, UUID.randomUUID(), UUID.randomUUID(), 123, null,
                            ))
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                    entered.await()
                    val blocked = assertFailsWith<PersistenceStoreException> {
                        target.settleDirectAckExcludingRadioHistory(claim.attempt, 999u, 999u)
                    }
                    assertIs<SQLiteDatabaseLockedException>(blocked.cause)
                    release.complete(Unit)
                    competitor.await()
                    val result = assertIs<DirectAckRadioSettlementResult.Nonsettling>(
                        target.settleDirectAckExcludingRadioHistory(claim.attempt, 999u, 999u),
                    )
                    assertTrue(DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION in result.reasons)
                }
            }
            assertEquals(claim.row, target.fetchMessage(EntityKey(RADIO_A, claim.row.id)))
            assertNull(second.directAcks().delivery(RADIO_A.value, claim.id))
            nonsettlingWithoutMutation(claim, 999u,
                DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION, target, second)
        } finally { target.close(); first.close(); second.close(); context.deleteDatabase(name) }
    }

    @Test fun atomicTrackedRegistrationWithoutPhysicalCallRacesSettlementWithoutInventingAmbiguity() = runTest {
        val name = "radio-exclusion-registration-${UUID.randomUUID()}.db"
        val first = MeshCoreDatabase.open(context, name)
        val second = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .addCallback(DirectAckStorage.callback).build()
        val target = newStore(second)
        try {
            val claim = begin(activate(target), 888u, target)
            accept(claim, 999u, target)
            immediateBusyFailure(second)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            withContext(Dispatchers.IO) {
                withTimeout(30_000) {
                    val registration = async(Dispatchers.IO) {
                        first.withTransaction {
                            val wire = DirectAckWireEntity(
                                RADIO_A.value, UUID.randomUUID(), UUID.randomUUID(), 999, null,
                            )
                            first.directAcks().wire(wire)
                            first.directAcks().operation(DirectAckOperationEntity(
                                wire.radioId, wire.claimId, wire.requestId, "EMISSION_TRACKING_REQUIRED",
                                wire.predictedCode, 1, null,
                            ))
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                    entered.await()
                    val blocked = assertFailsWith<PersistenceStoreException> {
                        target.settleDirectAckExcludingRadioHistory(claim.attempt, 999u, 42u)
                    }
                    assertIs<SQLiteDatabaseLockedException>(blocked.cause)
                    release.complete(Unit)
                    registration.await()
                    assertIs<DirectAckRadioSettlementReceipt.Applied>(
                        assertIs<DirectAckRadioSettlementResult.Settled>(
                            target.settleDirectAckExcludingRadioHistory(claim.attempt, 999u, 42u),
                        ).receipt,
                    )
                }
            }
            assertEquals(MessageStatus.DELIVERED,
                assertNotNull(target.fetchMessage(EntityKey(RADIO_A, claim.row.id))).status)
            assertEquals(0, second.directAckExclusions().legacyCount(RADIO_A.value).toInt())
            assertEquals(1, history(target).wires.size)
        } finally { target.close(); first.close(); second.close(); context.deleteDatabase(name) }
    }
}
