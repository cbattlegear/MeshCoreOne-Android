// AndroidOnly: WP-303 Request identity and stale foreground callback regression tests.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.EnrollmentRequest
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.Failure
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.Observation
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.Operation
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.Result
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.Step
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationEnrollmentProducerTest {
    private val copy = EnrollmentWarningCopy("Title", "Unknown or lost, not restored.", "Create", "Cancel")

    @Test fun noActionAllocatesNothingAndResumeDoesNotInferFirstInstall() = runTest {
        val f = Fixture(this)
        f.producer.resume(f.owner, copy)
        runCurrent()
        assertEquals(0, f.ids)
        assertEquals(1, f.resumes)
        assertTrue(f.presentations.isEmpty())
        assertEquals(NotificationEnrollmentProducer.State.Outcome(Result.NeedsAcknowledgment), f.producer.state.value)
    }

    @Test fun actualActionAllocatesIndependentTripleAndAcknowledgmentBeforeWarning() = runTest {
        val f = Fixture(this)
        f.producer.begin(f.owner, copy)
        runCurrent()
        val shown = assertIs<NotificationEnrollmentProducer.State.Warning>(f.producer.state.value).presentation
        val request = shown.request
        assertEquals(4, f.ids)
        assertEquals(4, setOf(request.operation.operationId, request.operation.storeId,
            request.operation.creationNonce, request.acknowledgmentId).size)
        assertEquals(null, request.expectedCurrent)
        assertEquals(copy, shown.copy)
        assertEquals(0, f.stages)
        assertEquals(0, f.enrollments)
    }

    @Test fun heldCompetingActionCannotAllocateOrReplaceRequest() = runTest {
        val f = Fixture(this)
        assertFalse(f.producer.attach(Any()))
        f.producer.begin(Any(), copy)
        f.producer.begin(f.owner, copy)
        f.producer.begin(f.owner, copy)
        runCurrent()
        assertEquals(4, f.ids)
        assertEquals(1, f.presentations.size)
    }

    @Test fun dismissalRetiresSynchronouslyBeforeInvalidationAndRejectsLateConfirm() = runTest {
        val f = Fixture(this)
        val shown = f.show()
        f.producer.dismiss(f.owner, shown)
        assertTrue(shown.retired)
        assertFalse(shown.invalidated)
        f.producer.confirm(f.owner, shown)
        runCurrent()
        assertTrue(shown.invalidated)
        assertEquals(0, f.stages)
        assertEquals(0, f.enrollments)
    }

    @Test fun rotationRetainsOperationButNeverRestoresPresentationAsConsent() = runTest {
        val f = Fixture(this)
        val old = f.show()
        f.producer.detach(f.owner)
        val replacement = Any()
        assertTrue(f.producer.attach(replacement))
        f.producer.confirm(f.owner, old)
        f.producer.begin(replacement, copy)
        runCurrent()
        val next = f.presentations.last()
        assertNotSame(old, next)
        assertSame(old.request, next.request)
        assertTrue(old.retired)
        assertEquals(4, f.ids)
        assertEquals(0, f.enrollments)
    }

    @Test fun backgroundDuringObservationDoesNotAllocateOrShowWarning() = runTest {
        val f = Fixture(this)
        f.readGate = CompletableDeferred()
        f.producer.begin(f.owner, copy)
        runCurrent()
        f.producer.detach(f.owner)
        f.readGate?.complete(Unit)
        runCurrent()
        assertEquals(0, f.ids)
        assertTrue(f.presentations.isEmpty())
    }

    @Test fun lockedCorruptOrMismatchedObservationDoesNotAllocate() = runTest {
        for (reason in listOf(Failure.STORAGE_UNAVAILABLE, Failure.CORRUPT_STORAGE, Failure.IDENTITY_MISMATCH)) {
            val f = Fixture(this)
            f.observation = Observation.Failed(reason)
            f.producer.begin(f.owner, copy)
            runCurrent()
            assertEquals(0, f.ids)
            assertTrue(f.presentations.isEmpty())
            assertEquals(NotificationEnrollmentProducer.State.Outcome(Result.Blocked(reason)), f.producer.state.value)
        }
    }

    @Test fun explicitStagedActionAloneCallsEnrollAndDuplicateClickCannotReplay() = runTest {
        val f = Fixture(this)
        val shown = f.show()
        f.producer.confirm(f.owner, shown)
        f.producer.confirm(f.owner, shown)
        runCurrent()
        assertEquals(1, f.stages)
        assertEquals(1, f.enrollments)
        assertTrue(shown.retired)
    }

    @Test fun retirementWhileStagingPreventsEnrollmentAndRequiresResume() = runTest {
        val f = Fixture(this)
        val shown = f.show()
        f.stageGate = CompletableDeferred()
        f.producer.confirm(f.owner, shown)
        runCurrent()
        f.producer.detach(f.owner)
        assertTrue(shown.retired)
        f.stageGate?.complete(Unit)
        runCurrent()
        assertEquals(0, f.enrollments)
        assertIs<Result.Indeterminate>(assertIs<NotificationEnrollmentProducer.State.Outcome>(f.producer.state.value).result)
        assertTrue(f.producer.attach(f.owner))
        f.producer.begin(f.owner, copy)
        f.producer.resume(f.owner, copy)
        runCurrent()
        assertEquals(4, f.ids)
        assertEquals(1, f.resumes)
    }

    @Test fun uncertainReturnDoesNotAllocateOrReauthorizeOnRetry() = runTest {
        val f = Fixture(this)
        val shown = f.show()
        f.enrollmentResult = Result.Indeterminate(shown.request.operation, Step.PREPARE)
        f.producer.confirm(f.owner, shown)
        runCurrent()
        f.producer.begin(f.owner, copy)
        f.producer.resume(f.owner, copy)
        runCurrent()
        assertEquals(1, f.stages)
        assertEquals(1, f.enrollments)
        assertEquals(1, f.resumes)
        assertEquals(4, f.ids)
    }

    @Test fun rejectedOrUncertainStagingNeverCallsEnrollment() = runTest {
        for (staging in NotificationEnrollmentProducer.Staging.entries.filter { it != NotificationEnrollmentProducer.Staging.STAGED }) {
            val f = Fixture(this)
            val shown = f.show()
            f.staging = staging
            f.producer.confirm(f.owner, shown)
            runCurrent()
            assertEquals(0, f.enrollments)
            f.producer.begin(f.owner, copy)
            runCurrent()
            assertEquals(4, f.ids)
        }
    }

    @Test fun resumeOfCompetingDurableOperationIsSupersededNotOriginalCompletion() = runTest {
        val f = Fixture(this)
        val shown = f.show()
        f.producer.dismiss(f.owner, shown)
        runCurrent()
        f.resumeResult = Result.Incomplete(Operation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
        f.producer.resume(f.owner, copy)
        runCurrent()
        assertEquals(NotificationEnrollmentProducer.State.Outcome(Result.Superseded), f.producer.state.value)
    }

    @Test fun renderedCopyCanonicalizationPreservesUtf16AndFieldBoundaries() {
        assertEquals("1:T2:\uD83D\uDE801:Y1:N", EnrollmentWarningCopy("T", "\uD83D\uDE80", "Y", "N").canonical)
        assertTrue(EnrollmentWarningCopy("ab", "c", "d", "e").canonical !=
            EnrollmentWarningCopy("a", "bc", "d", "e").canonical)
    }

    @Test fun onlyExplicitNewActionAfterDurableMissingHistoryCanAllocateAnotherGeneration() = runTest {
        val f = Fixture(this)
        val old = f.show()
        f.staging = NotificationEnrollmentProducer.Staging.INDETERMINATE
        f.producer.confirm(f.owner, old)
        runCurrent()
        f.producer.begin(f.owner, copy)
        runCurrent()
        assertEquals(4, f.ids)
        f.producer.resume(f.owner, copy)
        runCurrent()
        assertEquals(4, f.ids)
        f.producer.begin(f.owner, copy)
        runCurrent()
        assertEquals(8, f.ids)
        assertTrue(old.request.operation != f.presentations.last().request.operation)
        assertEquals(0, f.enrollments)
        assertEquals(1, f.stages)
    }

    @Test fun exactPriorObservationIsBoundAndChangedObservationCannotReuseOldWarning() = runTest {
        val f = Fixture(this)
        val prior = EnrollmentRecordFixture()
        f.observation = Observation.Current(prior.record)
        val original = f.show()
        assertEquals(prior.record, original.request.expectedCurrent)
        f.producer.dismiss(f.owner, original)
        runCurrent()
        f.observation = Observation.UnknownOrLost
        f.producer.begin(f.owner, copy)
        runCurrent()
        assertEquals(4, f.ids)
        assertEquals(1, f.presentations.size)
        assertEquals(NotificationEnrollmentProducer.State.Outcome(Result.Superseded), f.producer.state.value)
    }

    private class EnrollmentRecordFixture {
        val operation = Operation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val record = NotificationEnrollmentCoordinator.WitnessRecord(
            operation, UUID.randomUUID(), 1, NotificationEnrollmentCoordinator.WitnessPhase.AUTHORIZED, null,
        )
    }

    private inner class Fixture(val scope: TestScope) : NotificationEnrollmentProducer.Backend {
        val owner = Any()
        var ids = 0
        var stages = 0
        var enrollments = 0
        var resumes = 0
        var observation: Observation = Observation.UnknownOrLost
        var resumeResult: Result = Result.NeedsAcknowledgment
        var enrollmentResult: Result = Result.NeedsAcknowledgment
        var staging = NotificationEnrollmentProducer.Staging.STAGED
        var readGate: CompletableDeferred<Unit>? = null
        var stageGate: CompletableDeferred<Unit>? = null
        val presentations = mutableListOf<Shown>()
        val producer = NotificationEnrollmentProducer(scope, this) { UUID(0, (++ids).toLong()) }
            .also { it.attach(owner) }

        suspend fun show(): Shown {
            producer.begin(owner, copy)
            scope.runCurrent()
            return presentations.last()
        }

        override suspend fun observe(copy: EnrollmentWarningCopy): Observation {
            readGate?.await()
            return observation
        }

        override fun present(request: EnrollmentRequest, copy: EnrollmentWarningCopy) =
            Shown(request, copy).also { presentations += it }

        override suspend fun resume(copy: EnrollmentWarningCopy): Result {
            resumes++
            return resumeResult
        }

        inner class Shown(
            override val request: EnrollmentRequest,
            override val copy: EnrollmentWarningCopy,
        ) : NotificationEnrollmentProducer.Presentation {
            var retired = false
            var invalidated = false
            override fun retire() { retired = true }
            override suspend fun invalidate() { check(retired); invalidated = true }
            override suspend fun stageExplicitAction(): NotificationEnrollmentProducer.Staging {
                stages++
                stageGate?.await()
                return staging
            }
            override suspend fun enroll(): Result { enrollments++; return enrollmentResult }
        }
    }
}
