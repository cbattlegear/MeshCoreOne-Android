// AndroidOnly: WP-202 Real raw-SQL corruption controls fail closed before warning clear or settlement.
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
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DirectAckMalformedEvidenceTest {
    private data class Claim(
        val attempt: DirectAckAttempt,
        val receipt: DirectAckReceipt,
        val emission: DirectAckEmissionEntity,
    )

    @Test fun malformedLegacyWireBeyondPhysicalSampleFailsEveryNewAssessmentWithoutHidingOtherReasons() =
        runTest(timeout = 180.seconds) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val immediate = Executor { it.run() }
            val db = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
                .addCallback(DirectAckStorage.callback).allowMainThreadQueries()
                .setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
            val failures = mutableListOf<PersistenceStoreException>()
            val store = RoomPersistenceStore(db, owner, RepositoryClock(),
                RepositoryIssueReporter { _, error -> failures += error })
            try {
                val lease = assertIs<DirectAckActivationResult.Activated>(store.activateDirectAckSession(
                    SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), RADIO_A), UUID.randomUUID())).lease
                val row = message(status = MessageStatus.PENDING)
                val queued = pending(messageID = row.id)
                store.saveMessage(row)
                store.upsertPendingSend(queued)
                val claimId = UUID.randomUUID()
                val key = EntityKey(RADIO_A, row.id)
                val intent = assertIs<DirectAckIntentResult.Captured>(store.captureDirectAckIntent(
                    lease, key, claimId, DirectAckMode.SEND, queued.id)).intent
                val wire = UUID.randomUUID()
                val attempt = assertIs<DirectAckBeginResult.Begun>(
                    store.beginDirectAckAttemptRequiringEmissionTracking(intent, wire, 123u)).attempt
                val marker = assertIs<DirectAckEmissionMarkResult.Marked>(
                    store.markDirectAckWireMayHaveWritten(attempt, wire, UUID.randomUUID()))
                assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
                    marker.handle, UUID.randomUUID(), DirectAckEmissionResolution.Accepted(999u)))
                suspend fun assertMalformedPublicReads() {
                    withUnchangedDirectAckState(db, key, claimId) {
                        for (read in listOf<suspend () -> Any>(
                            { store.inspectDirectMessageConfirmationEvidence(key) },
                            { store.inspectDirectAckRadioConfirmationEvidence(RADIO_A) },
                            { store.inspectDirectAckRadioEvidence(RADIO_A) },
                            { store.settleDirectAckExcludingRadioHistory(attempt, 999u, 42u) },
                            { store.recordDirectFailureExcludingRadioHistory(attempt) },
                        )) {
                            assertEquals(PersistenceStoreError.InvalidData,
                                assertFailsWith<PersistenceStoreException> { read() }.error)
                        }
                    }
                }
                val initiallyMalformed = DirectAckWireEntity(
                    RADIO_A.value, UUID.randomUUID(), UUID.randomUUID(), -1, null)
                db.directAcks().wire(initiallyMalformed)
                assertMalformedPublicReads()
                db.directAcks().wire(initiallyMalformed.copy(predictedCode = 123))
                db.withTransaction {
                    repeat(4_100) {
                        val known = DirectAckWireEntity(RADIO_A.value, UUID.randomUUID(), UUID.randomUUID(), 789, 888)
                        db.directAcks().wire(known)
                        db.directAcks().operation(DirectAckOperationEntity(
                            known.radioId, known.claimId, known.requestId,
                            "EMISSION_TRACKING_REQUIRED", known.predictedCode, 1, null))
                        db.directAcks().operation(DirectAckOperationEntity(
                            known.radioId, known.claimId, UUID.randomUUID(),
                            "ACCEPTANCE_HISTORY_REQUIRED:${known.requestId}", known.predictedCode, 1, null))
                        db.directAckExclusions().emission(DirectAckEmissionEntity(
                            known.radioId, known.claimId, known.requestId, UUID.randomUUID(), UUID.randomUUID(),
                            known.predictedCode, "ACCEPTED", known.acceptedCode, UUID.randomUUID()))
                    }
                }
                val maximum = DirectAckRadioEvidenceLimits.MAX_DIAGNOSTIC_WIRES_PER_RADIO
                assertEquals(maximum, db.directAckExclusions().emissions(RADIO_A.value, maximum).size)
                val legacy = DirectAckWireEntity(RADIO_A.value, UUID.randomUUID(), UUID.randomUUID(), 123, null)
                val badInputs = listOf(
                    legacy.copy(predictedCode = -1),
                    legacy.copy(predictedCode = 4_294_967_296),
                    legacy.copy(acceptedCode = -1),
                    legacy.copy(acceptedCode = 4_294_967_296),
                )
                for (bad in badInputs) {
                    db.directAcks().wire(bad)
                    assertTrue(db.directAcks().operations(RADIO_A.value, legacy.claimId).isEmpty())
                    assertTrue(db.directAckExclusions().emissions(RADIO_A.value, Int.MAX_VALUE)
                        .none { it.claimId == legacy.claimId })
                    assertTrue(db.directAckExclusions().legacyWires(RADIO_A.value, 0).isEmpty())
                    assertTrue(db.directAckExclusions().malformedRegisteredHistory(RADIO_A.value))
                    assertMalformedPublicReads()
                    assertEquals(setOf(DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN),
                        assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(
                            store.inspectDirectAckRadioConfirmationEvidence(RADIO_B)).reasons.toSet())
                }
                assertEquals(25, failures.size)
                db.directAcks().wire(legacy)
                withUnchangedDirectAckState(db, key, claimId) {
                    val result = assertIs<DirectAckRadioSettlementResult.Nonsettling>(
                        store.settleDirectAckExcludingRadioHistory(attempt, 999u, 42u))
                    assertTrue(DirectAckRadioNonsettlingReason.PREAUTHORITY_HISTORY_UNKNOWN in result.reasons)
                    assertTrue(DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION in result.reasons)
                }
                val sampled = db.directAckExclusions().emissions(RADIO_A.value, maximum)
                val omitted = db.directAckExclusions().emissions(RADIO_A.value, Int.MAX_VALUE)
                    .first { row -> sampled.none { it.markRequestId == row.markRequestId } }
                db.directAckExclusions().emission(omitted.copy(state = "DEFINITELY_UNSENT"))
                assertFalse(db.directAckExclusions().emissions(RADIO_A.value, maximum)
                    .any { it.markRequestId == omitted.markRequestId })
                withUnchangedDirectAckState(db, key, claimId) {
                    assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException> {
                        store.inspectDirectAckRadioEvidence(RADIO_A)
                    }.error)
                    val refusal = assertIs<DirectAckRadioFailureResult.Nonfailing>(
                        store.recordDirectFailureExcludingRadioHistory(attempt))
                    assertTrue(DirectAckRadioNonfailingReason.PREAUTHORITY_HISTORY_UNKNOWN in refusal.reasons)
                    assertTrue(DirectAckRadioNonfailingReason.CONFLICTING_OTHER_CLAIM_EVIDENCE in refusal.reasons)
                }
                db.directAckExclusions().emission(omitted)
                assertEquals(DirectAckRadioEvidenceCoverage.TRUNCATED,
                    assertIs<DirectAckRadioEvidenceObservation.Found>(
                        store.inspectDirectAckRadioEvidence(RADIO_A)).snapshot.coverage)
            } finally { store.close(); owner.cancel(); db.close() }
        }

    @Test fun malformedTargetOrCompetitorCannotBeAcceptedAsUnsentClearOrDelivery() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val immediate = Executor { it.run() }
        val db = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .addCallback(DirectAckStorage.callback).allowMainThreadQueries()
            .setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        val failures = mutableListOf<PersistenceStoreException>()
        val store = RoomPersistenceStore(db, owner, RepositoryClock(), RepositoryIssueReporter { _, error -> failures += error })
        try {
            val lease = assertIs<DirectAckActivationResult.Activated>(store.activateDirectAckSession(
                SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), RADIO_A), UUID.randomUUID(),
            )).lease
            suspend fun begin(code: UInt): Claim {
                val row = message()
                store.saveMessage(row)
                val id = UUID.randomUUID()
                val intent = assertIs<DirectAckIntentResult.Captured>(store.captureDirectAckIntent(
                    lease, EntityKey(RADIO_A, row.id), id, DirectAckMode.SEND, null,
                )).intent
                val wire = UUID.randomUUID()
                val attempt = assertIs<DirectAckBeginResult.Begun>(
                    store.beginDirectAckAttemptRequiringEmissionTracking(intent, wire, 123u),
                ).attempt
                val marker = assertIs<DirectAckEmissionMarkResult.Marked>(
                    store.markDirectAckWireMayHaveWritten(attempt, wire, UUID.randomUUID()),
                )
                assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
                    marker.handle, UUID.randomUUID(), DirectAckEmissionResolution.Accepted(code),
                ))
                return Claim(attempt, assertIs<DirectAckObservation.Found>(
                    store.inspectDirectAckAttempt(lease, id),
                ).receipt, assertNotNull(db.directAckExclusions().emission(
                    RADIO_A.value, id, wire, marker.receipt.emissionRequestId,
                )))
            }
            val target = begin(999u)
            val competitor = begin(888u)
            val corruptions = listOf<Pair<String, (DirectAckEmissionEntity) -> DirectAckEmissionEntity>>(
                "unsent with actual" to { it.copy(state = "DEFINITELY_UNSENT", actualCode = 123) },
                "unsent without resolution" to { it.copy(state = "DEFINITELY_UNSENT", actualCode = null, resolutionRequestId = null) },
                "accepted without resolution" to { it.copy(resolutionRequestId = null) },
                "accepted without actual" to { it.copy(actualCode = null) },
                "negative accepted actual" to { it.copy(actualCode = -1) },
                "overflow accepted actual" to { it.copy(actualCode = 4_294_967_296) },
                "negative prediction" to { it.copy(predictedCode = -1) },
                "overflow prediction" to { it.copy(predictedCode = 4_294_967_296) },
                "unknown state" to { it.copy(state = "UNRECOGNIZED") },
                "may with actual" to { it.copy(state = "MAY_HAVE_WRITTEN", resolutionRequestId = null) },
                "may with resolution" to { it.copy(state = "MAY_HAVE_WRITTEN", actualCode = null) },
                "conflict without resolution" to { it.copy(state = "CONFLICT", resolutionRequestId = null) },
            )
            for (claim in listOf(target, competitor)) {
                for ((name, corrupt) in corruptions) {
                    val invalid = corrupt(claim.emission)
                    db.openHelper.writableDatabase.execSQL(
                        "UPDATE direct_ack_emissions SET predictedCode=?,state=?,actualCode=?,resolutionRequestId=? " +
                            "WHERE radioId=? AND claimId=? AND wireId=? AND markRequestId=?",
                        arrayOf<Any?>(invalid.predictedCode, invalid.state, invalid.actualCode,
                            invalid.resolutionRequestId?.toString()?.uppercase(), invalid.radioId.toString().uppercase(),
                            invalid.claimId.toString().uppercase(), invalid.wireId.toString().uppercase(),
                            invalid.markRequestId.toString().uppercase()),
                    )
                    val row = store.fetchMessage(target.receipt.key)
                    val identity = db.directAcks().incarnation(RADIO_A.value, target.receipt.key.id, "message")
                    val journal = db.directAcks().operations(RADIO_A.value, target.receipt.requestId)
                    assertTrue(db.directAckExclusions().confirmationEvidence(RADIO_A.value).malformedPhysicalEvidence, name)
                    if (claim == competitor) {
                        assertTrue(db.directAckExclusions().unresolvedCompetitor(
                            RADIO_A.value, target.receipt.requestId,
                        ), name)
                    } else {
                        assertTrue(db.directAckExclusions().unresolvedCurrentClaim(
                            RADIO_A.value, target.receipt.requestId,
                        ), name)
                        assertFalse(db.directAckExclusions().acceptedCurrentWire(
                            RADIO_A.value, target.receipt.requestId, 999,
                        ), name)
                    }
                    assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException>(name) {
                        store.inspectDirectAckRadioConfirmationEvidence(RADIO_A)
                    }.error)
                    assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException>(name) {
                        store.settleDirectAckExcludingRadioHistory(target.attempt, 999u, 42u)
                    }.error)
                    assertEquals(setOf(DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN),
                        assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(
                            store.inspectDirectAckRadioConfirmationEvidence(RADIO_B)).reasons.toSet())
                    assertEquals(row, store.fetchMessage(target.receipt.key), name)
                    assertEquals(identity, db.directAcks().incarnation(RADIO_A.value, target.receipt.key.id, "message"), name)
                    assertEquals(journal, db.directAcks().operations(RADIO_A.value, target.receipt.requestId), name)
                    assertNull(db.directAcks().delivery(RADIO_A.value, target.receipt.requestId), name)
                    db.directAckExclusions().emission(claim.emission)
                }
            }
            assertEquals(48, failures.size)
            val additional = assertIs<DirectAckEmissionMarkResult.Marked>(
                store.markDirectAckWireMayHaveWritten(
                    target.attempt, target.emission.wireId, UUID.randomUUID(),
                ),
            )
            assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
                additional.handle, UUID.randomUUID(), DirectAckEmissionResolution.Accepted(999u),
            ))
            db.directAckExclusions().emission(target.emission.copy(state = "DEFINITELY_UNSENT"))
            assertTrue(db.directAckExclusions().acceptedCurrentWire(RADIO_A.value, target.receipt.requestId, 999))
            assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException> {
                store.settleDirectAckExcludingRadioHistory(target.attempt, 999u, 42u)
            }.error)
            assertNull(db.directAcks().delivery(RADIO_A.value, target.receipt.requestId))
            db.directAckExclusions().emission(target.emission)
            db.directAckExclusions().emission(competitor.emission.copy(
                state = "DEFINITELY_UNSENT", actualCode = null, resolutionRequestId = UUID.randomUUID(),
            ))
            assertFalse(db.directAckExclusions().unresolvedCompetitor(RADIO_A.value, target.receipt.requestId))
            assertEquals(setOf(DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN),
                assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(
                    store.inspectDirectAckRadioConfirmationEvidence(RADIO_A)).reasons.toSet())
            withUnchangedDirectAckState(db, target.receipt.key, target.receipt.requestId) {
                assertEquals(setOf(DirectAckRadioNonsettlingReason.PREAUTHORITY_HISTORY_UNKNOWN),
                    assertIs<DirectAckRadioSettlementResult.Nonsettling>(
                        store.settleDirectAckExcludingRadioHistory(target.attempt, 999u, 42u)).reasons.toSet())
            }
            assertEquals(999u, assertIs<DirectAckMutationResult.Applied>(
                store.settleDirectAck(target.attempt, 999u, 42u)).receipt.delivery?.ackCode)
        } finally { store.close(); owner.cancel(); db.close() }
    }
}
