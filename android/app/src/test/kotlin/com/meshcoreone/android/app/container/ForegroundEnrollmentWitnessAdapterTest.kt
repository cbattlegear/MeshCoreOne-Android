// AndroidOnly: WP-204 Exact coordinator/witness mapping and no-fresh-permission regressions.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator as Enrollment
import com.meshcoreone.android.core.datastore.enrollment.EnrollmentWitnessRecord
import com.meshcoreone.android.core.datastore.enrollment.JournalPhase
import com.meshcoreone.android.core.datastore.enrollment.WitnessAuthorizationRequest
import com.meshcoreone.android.core.datastore.enrollment.WitnessCreationReceipt
import com.meshcoreone.android.core.datastore.enrollment.WitnessMutation
import com.meshcoreone.android.core.datastore.enrollment.WitnessObservation
import com.meshcoreone.android.core.datastore.enrollment.WitnessOperation
import com.meshcoreone.android.core.datastore.enrollment.WitnessPhase
import com.meshcoreone.android.core.datastore.enrollment.WitnessProblem
import java.io.IOException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ForegroundEnrollmentWitnessAdapterTest {
    private val operation = WitnessOperation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    private val witnessId = UUID.randomUUID()
    private val preparing = WitnessCreationReceipt(operation, 1, JournalPhase.PREPARING, operation.creationNonce, null)
    private val bound = WitnessCreationReceipt(operation, 2, JournalPhase.BOUND, operation.creationNonce, witnessId)
    private val authorized = EnrollmentWitnessRecord(operation, witnessId, 1, WitnessPhase.AUTHORIZED, null)
    private val accepted = authorized.copy(revision = 2, phase = WitnessPhase.ACCEPTED, receipt = preparing)
    private val completed = authorized.copy(revision = 3, phase = WitnessPhase.BOUND, receipt = bound)
    private val enrollmentOperation = Enrollment.Operation(operation.operationId, operation.storeId, operation.creationNonce)
    private val enrollmentPreparing = Enrollment.CreationReceipt(
        enrollmentOperation, 1, Enrollment.Phase.PREPARING, operation.creationNonce, null,
    )
    private val enrollmentBound = Enrollment.CreationReceipt(
        enrollmentOperation, 2, Enrollment.Phase.BOUND, operation.creationNonce, witnessId,
    )
    private val enrollmentAuthorized = Enrollment.WitnessRecord(
        enrollmentOperation, witnessId, 1, Enrollment.WitnessPhase.AUTHORIZED, null,
    )
    private val enrollmentAccepted = Enrollment.WitnessRecord(
        enrollmentOperation, witnessId, 2, Enrollment.WitnessPhase.ACCEPTED, enrollmentPreparing,
    )
    private val enrollmentCompleted = Enrollment.WitnessRecord(
        enrollmentOperation, witnessId, 3, Enrollment.WitnessPhase.BOUND, enrollmentBound,
    )
    private val recordPairs get() = listOf(
        authorized to enrollmentAuthorized, accepted to enrollmentAccepted, completed to enrollmentCompleted,
    )

    @Test
    fun requestRetainsEveryPredeclaredIdentityAndExactPriorRecord() = runBlocking {
        val request = Enrollment.EnrollmentRequest(
            Enrollment.Operation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
            UUID.randomUUID(), enrollmentCompleted,
        )
        val expected = WitnessAuthorizationRequest(
            WitnessOperation(request.operation.operationId, request.operation.storeId, request.operation.creationNonce),
            request.acknowledgmentId, completed,
        )
        val adapter = adapter(authorize = {
            assertEquals(expected, it)
            WitnessMutation.Stale
        })
        assertEquals(Enrollment.WitnessWrite.Stale, adapter.authorize(request))
        assertEquals(
            expected.copy(expectedCurrent = null),
            request.copy(expectedCurrent = null).toWitnessRequest(),
        )
    }

    @Test
    fun observationsPreserveUnknownLostAndEveryRecordPhase() = runBlocking {
        assertEquals(Enrollment.Observation.UnknownOrLost, adapter().observe())
        for ((record, expected) in recordPairs) {
            val observed = assertIs<Enrollment.Observation.Current>(
                adapter(read = { WitnessObservation.Current(record) }).observe(),
            )
            assertEquals(expected, observed.record)
            assertEquals(record, observed.record.toWitnessRecord())
            assertEquals(record.operation.operationId, observed.record.operation.operationId)
            assertEquals(record.operation.storeId, observed.record.operation.storeId)
            assertEquals(record.operation.creationNonce, observed.record.operation.creationNonce)
            assertEquals(record.witnessId, observed.record.witnessId)
            assertEquals(record.revision, observed.record.revision)
        }
    }

    @Test
    fun storedReplayNeverAcquiresNewPreparePermission() = runBlocking {
        for ((record, expected) in recordPairs) {
            for (fresh in listOf(false, true)) {
                val write = assertIs<Enrollment.WitnessWrite.Stored>(
                    adapter(authorize = { WitnessMutation.Stored(record, fresh) }).authorize(request()),
                )
                assertEquals(expected, write.record)
                assertEquals(record, write.record.toWitnessRecord())
                assertEquals(fresh, write.newlyAuthorized)
            }
        }
        val replay = assertIs<Enrollment.WitnessWrite.Stored>(
            adapter(authorize = { WitnessMutation.Stored(authorized) }).authorize(request()),
        )
        assertFalse(replay.newlyAuthorized)
    }

    @Test
    fun acceptAndCompleteRetainCausalReceiptNonceAndStableWitnessIdentity() = runBlocking {
        val adapter = adapter(
            accept = { expected, receipt ->
                assertEquals(authorized, expected)
                assertEquals(preparing, receipt)
                WitnessMutation.Stored(accepted)
            },
            complete = { expected, receipt ->
                assertEquals(accepted, expected)
                assertEquals(bound, receipt)
                WitnessMutation.Stored(completed)
            },
        )
        assertEquals(
            Enrollment.WitnessWrite.Stored(enrollmentAccepted),
            adapter.accept(enrollmentAuthorized, enrollmentPreparing),
        )
        assertEquals(
            Enrollment.WitnessWrite.Stored(enrollmentCompleted),
            adapter.complete(enrollmentAccepted, enrollmentBound),
        )
        val mismatched = bound.copy(creationNonce = UUID.randomUUID(), witnessId = UUID.randomUUID())
        assertEquals(mismatched, mismatched.toEnrollmentReceipt().toWitnessReceipt())
    }

    @Test
    fun everyTypedFailureAndUncertainOperationRemainNonSuccess() = runBlocking {
        val failures = mapOf(
            WitnessProblem.ACKNOWLEDGMENT_REQUIRED to Enrollment.Failure.ACKNOWLEDGMENT_REQUIRED,
            WitnessProblem.ACKNOWLEDGMENT_MISMATCH to Enrollment.Failure.ACKNOWLEDGMENT_DECLINED,
            WitnessProblem.MISSING_AUTHENTICATOR to Enrollment.Failure.IDENTITY_MISMATCH,
            WitnessProblem.AUTHENTICATION_FAILED to Enrollment.Failure.IDENTITY_MISMATCH,
            WitnessProblem.LOCKED to Enrollment.Failure.STORAGE_UNAVAILABLE,
            WitnessProblem.STORAGE_UNAVAILABLE to Enrollment.Failure.STORAGE_UNAVAILABLE,
            WitnessProblem.CORRUPT_STORAGE to Enrollment.Failure.CORRUPT_STORAGE,
            WitnessProblem.INVALID_RECEIPT to Enrollment.Failure.INVALID_RECEIPT,
        )
        assertEquals(WitnessProblem.entries.toSet(), failures.keys)
        for ((problem, expected) in failures) {
            assertEquals(
                Enrollment.Observation.Failed(expected),
                adapter(read = { WitnessObservation.Failed(problem) }).observe(),
            )
            assertEquals(
                Enrollment.WitnessWrite.NotRetained(expected),
                adapter(authorize = { WitnessMutation.NotRetained(problem) }).authorize(request()),
            )
        }
        assertEquals(
            Enrollment.WitnessWrite.Indeterminate(operation.toEnrollmentOperation()),
            adapter(authorize = { WitnessMutation.Indeterminate(operation) }).authorize(request()),
        )
    }

    @Test
    fun adapterDoesNotRetryOrTranslateCancellationAndUnexpectedStorageFailureIntoConsent() = runBlocking {
        var calls = 0
        val cancelled = adapter(authorize = { calls++; throw CancellationException("cancelled") })
        assertFailsWith<CancellationException> { cancelled.authorize(request()) }
        assertEquals(1, calls)
        val failed = adapter(authorize = { calls++; throw IOException("unavailable") })
        assertFailsWith<IOException> { failed.authorize(request()) }
        assertEquals(2, calls)
    }

    private fun request() = Enrollment.EnrollmentRequest(
        operation.toEnrollmentOperation(), UUID.randomUUID(), null,
    )

    private fun adapter(
        read: suspend () -> WitnessObservation = { WitnessObservation.UnknownOrLost },
        authorize: suspend (WitnessAuthorizationRequest) -> WitnessMutation = { error("Unexpected authorization") },
        accept: suspend (EnrollmentWitnessRecord, WitnessCreationReceipt) -> WitnessMutation =
            { _, _ -> error("Unexpected acceptance") },
        complete: suspend (EnrollmentWitnessRecord, WitnessCreationReceipt) -> WitnessMutation =
            { _, _ -> error("Unexpected completion") },
    ) = ForegroundEnrollmentWitnessAdapter(read, authorize, accept, complete)
}
