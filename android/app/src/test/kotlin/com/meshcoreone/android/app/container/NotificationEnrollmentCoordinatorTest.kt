// AndroidOnly: WP-303 Deterministic enrollment ordering and failure tests; not platform persistence proof.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.CreationReceipt
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.EnrollmentRequest
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.Failure
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.JournalResult
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.Observation
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.Operation
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.Phase
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.Result
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.Step
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.WitnessPhase
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.WitnessRecord
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.WitnessWrite
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationEnrollmentCoordinatorTest {
    @Test
    fun acknowledgedNewGenerationBindsOnlyAfterBothDurablePortAcknowledgments() = runTest {
        val f = Fixture()
        val result = assertIs<Result.BoundSnapshot>(f.coordinator().enroll(f.request))
        assertEquals(
            listOf("authorize", "prepare", "accept", "bind", "complete", "reconcile"),
            f.events,
        )
        assertEquals(f.operation, result.receipt.operation)
        assertEquals(f.witness.current, result.witness)
        assertEquals(f.witness.current?.witnessId, result.receipt.witnessId)
        assertEquals(Phase.BOUND, result.receipt.phase)
    }

    @Test
    fun missingAcknowledgmentCannotCreateOrAdoptAJournal() = runTest {
        val f = Fixture()
        f.witness.acknowledgments.clear()
        assertEquals(Result.Blocked(Failure.ACKNOWLEDGMENT_REQUIRED), f.coordinator().enroll(f.request))
        assertNull(f.journal.receipt)
        assertNull(f.witness.current)
        assertEquals(listOf("authorize"), f.events)
    }

    @Test
    fun unknownJointLossIsNotFreshAndStartupNeverCreatesOrAdoptsExistingStorage() = runTest {
        for (existing in listOf(false, true)) {
            val f = Fixture()
            if (existing) f.journal.receipt = f.preparing()
            val original = f.journal.receipt
            assertEquals(Result.NeedsAcknowledgment, f.coordinator().inspect())
            assertEquals(Result.NeedsAcknowledgment, f.coordinator().resume())
            assertEquals(original, f.journal.receipt)
            assertTrue(f.events.isEmpty())
        }
    }

    @Test
    fun lockedCorruptOrMismatchedWitnessNeverStartsJournalWork() = runTest {
        for (failure in listOf(Failure.STORAGE_UNAVAILABLE, Failure.CORRUPT_STORAGE, Failure.IDENTITY_MISMATCH)) {
            val f = Fixture()
            f.witness.readFailure = failure
            assertEquals(Result.Blocked(failure), f.coordinator().resume())
            assertEquals(Result.Blocked(failure), f.coordinator().inspect())
            assertTrue(f.events.isEmpty())
        }
    }

    @Test
    fun startupInspectionLeavesAuthorizedAndAcceptedOperationsIncompleteWithoutWrites() = runTest {
        for (phase in listOf(WitnessPhase.AUTHORIZED, WitnessPhase.ACCEPTED)) {
            val f = Fixture()
            f.witness.current = f.record(phase)
            assertEquals(Result.Incomplete(f.operation), f.coordinator().inspect())
            assertTrue(f.events.isEmpty())
        }
    }

    @Test
    fun lostReturnsAtEveryWriteResumeOnlyTheSamePositivelyRetainedOperation() = runTest {
        for (step in listOf(Step.AUTHORIZE, Step.PREPARE, Step.ACCEPT_WITNESS, Step.BIND_JOURNAL, Step.COMPLETE_WITNESS)) {
            val f = Fixture()
            f.lostReturn = step
            assertEquals(Result.Indeterminate(f.operation, step), f.coordinator().enroll(f.request))
            val nonce = f.journal.receipt?.creationNonce
            f.lostReturn = null
            if (step == Step.AUTHORIZE) {
                assertEquals(Result.Blocked(Failure.NOT_RETAINED), f.coordinator().resume())
                assertEquals(0, f.journal.creations)
                continue
            }
            val resumed = assertIs<Result.BoundSnapshot>(f.coordinator().resume())
            assertEquals(f.operation, resumed.receipt.operation)
            if (nonce != null) assertEquals(nonce, resumed.receipt.creationNonce)
            assertEquals(1, f.journal.creations)
            assertEquals(1, f.witness.authorizations)
        }
    }

    @Test
    fun cancellationAfterEveryCommittedPortWritePropagatesAndRestartReconciles() = runTest {
        for (step in listOf(Step.AUTHORIZE, Step.PREPARE, Step.ACCEPT_WITNESS, Step.BIND_JOURNAL, Step.COMPLETE_WITNESS)) {
            val f = Fixture()
            f.cancelAfter = step
            assertFailsWith<CancellationException> { f.coordinator().enroll(f.request) }
            val nonce = f.journal.receipt?.creationNonce
            f.cancelAfter = null
            if (step == Step.AUTHORIZE) {
                assertEquals(Result.Blocked(Failure.NOT_RETAINED), f.coordinator().resume())
                assertEquals(0, f.journal.creations)
                continue
            }
            val resumed = assertIs<Result.BoundSnapshot>(f.coordinator().resume())
            if (nonce != null) assertEquals(nonce, resumed.receipt.creationNonce)
            assertEquals(1, f.journal.creations)
            assertEquals(1, f.witness.authorizations)
        }
    }

    @Test
    fun unretainedAuthorizationDoesNotPrepareAndUncertainAuthorizationDoesNotGuessFreshness() = runTest {
        val f = Fixture()
        f.witness.authorizeOverride = WitnessWrite.NotRetained(Failure.NOT_RETAINED)
        assertEquals(Result.Blocked(Failure.NOT_RETAINED), f.coordinator().enroll(f.request))
        assertNull(f.journal.receipt)
        f.witness.authorizeOverride = WitnessWrite.Indeterminate(f.operation)
        assertEquals(Result.Indeterminate(f.operation, Step.AUTHORIZE), f.coordinator().enroll(f.request))
        assertEquals(Result.NeedsAcknowledgment, f.coordinator().resume())
        assertNull(f.journal.receipt)
    }

    @Test
    fun foreignIndeterminateOperationNeverBecomesAnAuthorizedRetry() = runTest {
        val f = Fixture()
        f.witness.authorizeOverride =
            WitnessWrite.Indeterminate(Operation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
        assertEquals(Result.Blocked(Failure.INVALID_RECEIPT), f.coordinator().enroll(f.request))
        assertNull(f.journal.receipt)
    }

    @Test
    fun preparingReceiptRequiresExactOperationRevisionPhaseAndNoPriorWitness() = runTest {
        for (field in listOf("store", "operation", "revision", "phase", "witness", "nonce")) {
            val test = Fixture()
            val correct = test.preparing()
            val receipt = when (field) {
                "store" -> correct.copy(operation = correct.operation.copy(storeId = UUID.randomUUID()))
                "operation" -> correct.copy(operation = correct.operation.copy(operationId = UUID.randomUUID()))
                "revision" -> correct.copy(revision = 2)
                "phase" -> correct.copy(phase = Phase.BOUND)
                "nonce" -> correct.copy(creationNonce = UUID.randomUUID())
                else -> correct.copy(witnessId = UUID.randomUUID())
            }
            test.journal.prepareOverride = JournalResult.Observed(receipt)
            assertEquals(Result.Blocked(Failure.INVALID_RECEIPT), test.coordinator().enroll(test.request))
            assertFalse("accept" in test.events)
            assertFalse("bind" in test.events)
        }
    }

    @Test
    fun changedCreationNonceOrBoundWitnessCannotCompleteEnrollment() = runTest {
        for (field in listOf("nonce", "witness", "operation", "revision", "phase")) {
            val f = Fixture()
            f.journal.alterBound = { receipt ->
                when (field) {
                    "nonce" -> receipt.copy(creationNonce = UUID.randomUUID())
                    "witness" -> receipt.copy(witnessId = UUID.randomUUID())
                    "operation" -> receipt.copy(operation = receipt.operation.copy(storeId = UUID.randomUUID()))
                    "revision" -> receipt.copy(revision = 3)
                    else -> receipt.copy(phase = Phase.PREPARING)
                }
            }
            assertEquals(Result.Blocked(Failure.INVALID_RECEIPT), f.coordinator().enroll(f.request))
            assertFalse("complete" in f.events)
            assertEquals(WitnessPhase.ACCEPTED, f.witness.current?.phase)
        }
    }

    @Test
    fun absentReplacedOrCorruptJournalNeverBecomesBoundOnReopen() = runTest {
        for (failure in listOf(Failure.NOT_RETAINED, Failure.IDENTITY_MISMATCH, Failure.CORRUPT_STORAGE, Failure.STORAGE_UNAVAILABLE)) {
            val f = Fixture()
            assertIs<Result.BoundSnapshot>(f.coordinator().enroll(f.request))
            val old = f.journal.receipt
            f.events.clear()
            f.journal.readOverride = JournalResult.NotRetained(failure)
            assertEquals(Result.Blocked(failure), f.coordinator().inspect())
            assertEquals(old, f.journal.receipt)
            assertEquals(listOf("reconcile"), f.events)
        }
    }

    @Test
    fun sameStoreUuidWithDifferentCausalNonceOrWitnessIsNotContinuity() = runTest {
        for (replaceNonce in listOf(false, true)) {
            val f = Fixture()
            val bound = assertIs<Result.BoundSnapshot>(f.coordinator().enroll(f.request))
            f.journal.readOverride = JournalResult.Observed(
                if (replaceNonce) bound.receipt.copy(creationNonce = UUID.randomUUID())
                else bound.receipt.copy(witnessId = UUID.randomUUID()),
            )
            assertEquals(Result.Blocked(Failure.IDENTITY_MISMATCH), f.coordinator().inspect())
        }
    }

    @Test
    fun successorWitnessDuringBindFencesOldCompletionWithoutOverwritingSuccessor() = runTest {
        val f = Fixture()
        val successor = f.record(WitnessPhase.AUTHORIZED).copy(
            operation = Operation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
            witnessId = UUID.randomUUID(),
        )
        f.journal.afterBind = { f.witness.current = successor }
        assertEquals(Result.Superseded, f.coordinator().enroll(f.request))
        assertEquals(successor, f.witness.current)
        assertFalse("complete" in f.events)
    }

    @Test
    fun successorDuringFinalReadCannotPublishOldBoundSnapshot() = runTest {
        val f = Fixture()
        val successor = f.record(WitnessPhase.AUTHORIZED).copy(witnessId = UUID.randomUUID())
        f.journal.afterRead = { f.witness.current = successor }
        assertEquals(Result.Superseded, f.coordinator().enroll(f.request))
        assertEquals(successor, f.witness.current)
    }

    @Test
    fun staleAcknowledgmentCannotReplaceCurrentGeneration() = runTest {
        val f = Fixture()
        val current = f.record(WitnessPhase.AUTHORIZED)
        f.witness.current = current
        val stale = f.request.copy(operation = Operation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
        assertEquals(Result.Superseded, f.coordinator().enroll(stale))
        assertEquals(current, f.witness.current)
        assertNull(f.journal.receipt)
    }

    @Test
    fun witnessTransitionMustPreserveIdentityAndExactRevision() = runTest {
        val f = Fixture()
        f.witness.alterAccepted = { it.copy(revision = it.revision + 1) }
        assertEquals(Result.Blocked(Failure.INVALID_RECEIPT), f.coordinator().enroll(f.request))
        assertFalse("bind" in f.events)
    }

    @Test
    fun invalidPersistedWitnessCannotReachRequireNotNullOrAnyJournalWrite() = runTest {
        val f = Fixture()
        f.witness.current = f.record(WitnessPhase.ACCEPTED).copy(receipt = null)
        assertEquals(Result.Blocked(Failure.INVALID_RECEIPT), f.coordinator().resume())
        assertEquals(Result.Blocked(Failure.INVALID_RECEIPT), f.coordinator().inspect())
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun authorizationMustReturnBeforeAnyJournalPreparation() = runTest {
        val f = Fixture()
        val hold = CompletableDeferred<Unit>()
        f.witness.authorizeHold = hold
        val enrollment = async { f.coordinator().enroll(f.request) }
        runCurrent()
        assertEquals(listOf("authorize"), f.events)
        assertNull(f.journal.receipt)
        hold.complete(Unit)
        assertIs<Result.BoundSnapshot>(enrollment.await())
    }

    @Test
    fun journalPrepareFailureLeavesAuthorizedOperationForExplicitSameOperationRetry() = runTest {
        val f = Fixture()
        f.journal.prepareOverride = JournalResult.NotRetained(Failure.CORRUPT_STORAGE)
        assertEquals(Result.Blocked(Failure.CORRUPT_STORAGE), f.coordinator().enroll(f.request))
        assertEquals(WitnessPhase.AUTHORIZED, f.witness.current?.phase)
        assertNull(f.journal.receipt)
        assertEquals(1, f.witness.authorizations)
    }

    @Test
    fun lostPrepareReturnFollowedByStoreLossNeverCreatesReplacementStorage() = runTest {
        val f = Fixture()
        f.lostReturn = Step.PREPARE
        assertEquals(Result.Indeterminate(f.operation, Step.PREPARE), f.coordinator().enroll(f.request))
        assertEquals(1, f.journal.creations)
        f.journal.receipt = null
        f.lostReturn = null
        f.events.clear()
        assertEquals(Result.Blocked(Failure.NOT_RETAINED), f.coordinator().resume())
        assertEquals(listOf("reconcile"), f.events)
        assertEquals(1, f.journal.creations)
    }

    @Test
    fun replayedForegroundAcknowledgmentCannotRecreateMissingGeneration() = runTest {
        val f = Fixture()
        f.cancelAfter = Step.AUTHORIZE
        assertFailsWith<CancellationException> { f.coordinator().enroll(f.request) }
        f.cancelAfter = null
        f.events.clear()
        assertEquals(Result.Blocked(Failure.NOT_RETAINED), f.coordinator().enroll(f.request))
        assertEquals(listOf("authorize", "reconcile"), f.events)
        assertEquals(1, f.witness.authorizations)
        assertEquals(0, f.journal.creations)
    }

    @Test
    fun lostPrepareReturnThenSameOperationStoreReplacementCannotAdoptObservedNonceOnRestart() = runTest {
        val f = Fixture()
        f.lostReturn = Step.PREPARE
        assertEquals(Result.Indeterminate(f.operation, Step.PREPARE), f.coordinator().enroll(f.request))
        val replacementOperation = f.operation.copy(creationNonce = UUID.randomUUID())
        f.journal.receipt = CreationReceipt(
            replacementOperation, 1, Phase.PREPARING, replacementOperation.creationNonce, null,
        )
        val replacement = f.journal.receipt
        f.lostReturn = null
        f.events.clear()
        assertEquals(Result.Blocked(Failure.IDENTITY_MISMATCH), f.coordinator().resume())
        assertEquals(listOf("reconcile"), f.events)
        assertEquals(replacement, f.journal.receipt)
        assertEquals(WitnessPhase.AUTHORIZED, f.witness.current?.phase)
        assertNull(f.witness.current?.receipt)
        assertEquals(1, f.journal.creations)
    }

    @Test
    fun preparedReplacementObservationIsRejectedEvenWhenPortReturnsItAsObserved() = runTest {
        val f = Fixture()
        f.cancelAfter = Step.AUTHORIZE
        assertFailsWith<CancellationException> { f.coordinator().enroll(f.request) }
        f.cancelAfter = null
        val foreign = f.operation.copy(creationNonce = UUID.randomUUID())
        f.journal.readOverride = JournalResult.Observed(CreationReceipt(foreign, 1, Phase.PREPARING, foreign.creationNonce, null))
        assertEquals(Result.Blocked(Failure.INVALID_RECEIPT), f.coordinator().resume())
        assertFalse("accept" in f.events)
    }

    @Test
    fun initialPrepareCannotReplaceIndependentlyPredeclaredNonceBeforeWitnessAcceptance() = runTest {
        val f = Fixture()
        val replacement = f.operation.copy(creationNonce = UUID.randomUUID())
        f.journal.prepareOverride =
            JournalResult.Observed(CreationReceipt(replacement, 1, Phase.PREPARING, replacement.creationNonce, null))
        assertEquals(Result.Blocked(Failure.INVALID_RECEIPT), f.coordinator().enroll(f.request))
        assertFalse("accept" in f.events)
        assertFalse("bind" in f.events)
    }

    @Test
    fun authorizedWitnessMustHaveExactInitialRevisionBeforeAnyPreparation() = runTest {
        val f = Fixture()
        f.witness.current = f.record(WitnessPhase.AUTHORIZED).copy(revision = 2)
        assertEquals(Result.Blocked(Failure.INVALID_RECEIPT), f.coordinator().resume())
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun acceptedRestartRejectsJournalAlreadyBoundToDifferentWitnessWithoutOverwritingIt() = runTest {
        val f = Fixture()
        f.witness.current = f.record(WitnessPhase.ACCEPTED)
        val foreign = f.record(WitnessPhase.BOUND).receipt!!.copy(witnessId = UUID.randomUUID())
        f.journal.receipt = foreign
        assertEquals(Result.Blocked(Failure.IDENTITY_MISMATCH), f.coordinator().resume())
        assertEquals(foreign, f.journal.receipt)
        assertEquals(WitnessPhase.ACCEPTED, f.witness.current?.phase)
        assertFalse("complete" in f.events)
    }

    private class Fixture {
        val operation = Operation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val request = EnrollmentRequest(operation, UUID.randomUUID(), null)
        val witnessId: UUID = UUID.randomUUID()
        val nonce: UUID = operation.creationNonce
        val events = mutableListOf<String>()
        var lostReturn: Step? = null
        var cancelAfter: Step? = null
        val witness = TestWitness()
        val journal = TestJournal()
        fun coordinator() = NotificationEnrollmentCoordinator(witness, journal)
        fun preparing() = CreationReceipt(operation, 1, Phase.PREPARING, nonce, null)
        fun record(phase: WitnessPhase) = WitnessRecord(
            operation, witnessId, phase.ordinal + 1L, phase,
            when (phase) {
                WitnessPhase.AUTHORIZED -> null
                WitnessPhase.ACCEPTED -> preparing()
                WitnessPhase.BOUND -> preparing().copy(revision = 2, phase = Phase.BOUND, witnessId = witnessId)
            },
        )

        fun after(step: Step) {
            if (cancelAfter == step) throw CancellationException("Test lost return after $step")
        }

        inner class TestWitness : NotificationEnrollmentCoordinator.Witness {
            var current: WitnessRecord? = null
            var authorizations = 0
            val acknowledgments = mutableSetOf(request.acknowledgmentId)
            var readFailure: Failure? = null
            var authorizeOverride: WitnessWrite? = null
            var authorizeHold: CompletableDeferred<Unit>? = null
            var alterAccepted: ((WitnessRecord) -> WitnessRecord)? = null

            override suspend fun observe(): Observation =
                readFailure?.let { Observation.Failed(it) } ?: current?.let { Observation.Current(it) }
                    ?: Observation.UnknownOrLost

            override suspend fun authorize(request: EnrollmentRequest): WitnessWrite {
                events += "authorize"
                authorizeOverride?.let { return it }
                if (request.acknowledgmentId !in acknowledgments) {
                    return WitnessWrite.NotRetained(Failure.ACKNOWLEDGMENT_REQUIRED)
                }
                current?.takeIf { it.operation == request.operation }?.let {
                    return WitnessWrite.Stored(it)
                }
                if (current != request.expectedCurrent) return WitnessWrite.Stale
                current = record(WitnessPhase.AUTHORIZED).copy(operation = request.operation)
                authorizations++
                authorizeHold?.await()
                return written(Step.AUTHORIZE, newlyAuthorized = true)
            }

            override suspend fun accept(expected: WitnessRecord, preparing: CreationReceipt): WitnessWrite {
                events += "accept"
                if (current != expected) return WitnessWrite.Stale
                val next = expected.copy(revision = expected.revision + 1, phase = WitnessPhase.ACCEPTED, receipt = preparing)
                current = alterAccepted?.invoke(next) ?: next
                return written(Step.ACCEPT_WITNESS)
            }

            override suspend fun complete(expected: WitnessRecord, bound: CreationReceipt): WitnessWrite {
                events += "complete"
                if (current != expected) return WitnessWrite.Stale
                current = expected.copy(revision = expected.revision + 1, phase = WitnessPhase.BOUND, receipt = bound)
                return written(Step.COMPLETE_WITNESS)
            }

            private fun written(step: Step, newlyAuthorized: Boolean = false): WitnessWrite {
                after(step)
                return if (lostReturn == step) WitnessWrite.Indeterminate(operation)
                else WitnessWrite.Stored(requireNotNull(current), newlyAuthorized)
            }
        }

        inner class TestJournal : NotificationEnrollmentCoordinator.Journal {
            var receipt: CreationReceipt? = null
            var creations = 0
            var prepareOverride: JournalResult? = null
            var readOverride: JournalResult? = null
            var alterBound: ((CreationReceipt) -> CreationReceipt)? = null
            var afterBind: (() -> Unit)? = null
            var afterRead: (() -> Unit)? = null

            override suspend fun prepare(operation: Operation): JournalResult {
                events += "prepare"
                prepareOverride?.let { return it }
                if (receipt == null) {
                    receipt = preparing().copy(operation = operation)
                    creations++
                }
                return observed(Step.PREPARE)
            }

            override suspend fun bind(preparing: CreationReceipt, witnessId: UUID): JournalResult {
                events += "bind"
                val existing = receipt ?: return JournalResult.NotRetained(Failure.NOT_RETAINED)
                val expectedBound = preparing.copy(revision = 2, phase = Phase.BOUND, witnessId = witnessId)
                if (existing != preparing && existing != expectedBound) {
                    return JournalResult.NotRetained(Failure.IDENTITY_MISMATCH)
                }
                receipt = expectedBound
                    .let { alterBound?.invoke(it) ?: it }
                afterBind?.invoke()
                return observed(Step.BIND_JOURNAL)
            }

            override suspend fun reconcile(operation: Operation, expectedNonce: UUID): JournalResult {
                events += "reconcile"
                val result = readOverride ?: receipt?.let {
                    if (it.operation == operation && it.creationNonce == expectedNonce) JournalResult.Observed(it)
                    else JournalResult.NotRetained(Failure.IDENTITY_MISMATCH)
                }
                    ?: JournalResult.NotRetained(Failure.NOT_RETAINED)
                afterRead?.invoke()
                return result
            }

            private fun observed(step: Step): JournalResult {
                after(step)
                return if (lostReturn == step) JournalResult.Indeterminate(operation)
                else JournalResult.Observed(requireNotNull(receipt))
            }
        }
    }
}
