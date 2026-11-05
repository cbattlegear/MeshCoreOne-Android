// AndroidOnly: WP-204 Exact nonactivated coordinator-to-durable-witness adaptation.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator as Enrollment
import com.meshcoreone.android.core.datastore.enrollment.EnrollmentWitnessRecord
import com.meshcoreone.android.core.datastore.enrollment.JournalPhase
import com.meshcoreone.android.core.datastore.enrollment.NotificationEnrollmentWitness
import com.meshcoreone.android.core.datastore.enrollment.WitnessAuthorizationRequest
import com.meshcoreone.android.core.datastore.enrollment.WitnessCreationReceipt
import com.meshcoreone.android.core.datastore.enrollment.WitnessMutation
import com.meshcoreone.android.core.datastore.enrollment.WitnessObservation
import com.meshcoreone.android.core.datastore.enrollment.WitnessOperation
import com.meshcoreone.android.core.datastore.enrollment.WitnessPhase
import com.meshcoreone.android.core.datastore.enrollment.WitnessProblem

/**
 * Preserves the witness's exact identities and one-return prepare permission. Construction alone
 * installs no producer, notification ingress, journal or publication capability.
 */
internal class ForegroundEnrollmentWitnessAdapter internal constructor(
    private val read: suspend () -> WitnessObservation,
    private val authorizeRequest: suspend (WitnessAuthorizationRequest) -> WitnessMutation,
    private val acceptReceipt: suspend (EnrollmentWitnessRecord, WitnessCreationReceipt) -> WitnessMutation,
    private val completeReceipt: suspend (EnrollmentWitnessRecord, WitnessCreationReceipt) -> WitnessMutation,
) : Enrollment.Witness {
    constructor(witness: NotificationEnrollmentWitness) : this(
        witness::observe, witness::authorize, witness::accept, witness::complete,
    )

    override suspend fun observe(): Enrollment.Observation = when (val result = read()) {
        WitnessObservation.UnknownOrLost -> Enrollment.Observation.UnknownOrLost
        is WitnessObservation.Current -> Enrollment.Observation.Current(result.record.toEnrollmentRecord())
        is WitnessObservation.Failed -> Enrollment.Observation.Failed(result.problem.toEnrollmentFailure())
    }

    override suspend fun authorize(request: Enrollment.EnrollmentRequest): Enrollment.WitnessWrite =
        authorizeRequest(request.toWitnessRequest()).toEnrollmentWrite()

    override suspend fun accept(
        expected: Enrollment.WitnessRecord,
        preparing: Enrollment.CreationReceipt,
    ): Enrollment.WitnessWrite =
        acceptReceipt(expected.toWitnessRecord(), preparing.toWitnessReceipt()).toEnrollmentWrite()

    override suspend fun complete(
        expected: Enrollment.WitnessRecord,
        bound: Enrollment.CreationReceipt,
    ): Enrollment.WitnessWrite =
        completeReceipt(expected.toWitnessRecord(), bound.toWitnessReceipt()).toEnrollmentWrite()
}

internal fun Enrollment.EnrollmentRequest.toWitnessRequest() = WitnessAuthorizationRequest(
    operation.toWitnessOperation(), acknowledgmentId, expectedCurrent?.toWitnessRecord(),
)

internal fun WitnessOperation.toEnrollmentOperation() = Enrollment.Operation(operationId, storeId, creationNonce)

internal fun Enrollment.Operation.toWitnessOperation() = WitnessOperation(operationId, storeId, creationNonce)

internal fun EnrollmentWitnessRecord.toEnrollmentRecord() = Enrollment.WitnessRecord(
    operation.toEnrollmentOperation(), witnessId, revision,
    when (phase) {
        WitnessPhase.AUTHORIZED -> Enrollment.WitnessPhase.AUTHORIZED
        WitnessPhase.ACCEPTED -> Enrollment.WitnessPhase.ACCEPTED
        WitnessPhase.BOUND -> Enrollment.WitnessPhase.BOUND
    },
    receipt?.toEnrollmentReceipt(),
)

internal fun Enrollment.WitnessRecord.toWitnessRecord() = EnrollmentWitnessRecord(
    operation.toWitnessOperation(), witnessId, revision,
    when (phase) {
        Enrollment.WitnessPhase.AUTHORIZED -> WitnessPhase.AUTHORIZED
        Enrollment.WitnessPhase.ACCEPTED -> WitnessPhase.ACCEPTED
        Enrollment.WitnessPhase.BOUND -> WitnessPhase.BOUND
    },
    receipt?.toWitnessReceipt(),
)

internal fun WitnessCreationReceipt.toEnrollmentReceipt() = Enrollment.CreationReceipt(
    operation.toEnrollmentOperation(), revision,
    when (phase) {
        JournalPhase.PREPARING -> Enrollment.Phase.PREPARING
        JournalPhase.BOUND -> Enrollment.Phase.BOUND
    },
    creationNonce, witnessId,
)

internal fun Enrollment.CreationReceipt.toWitnessReceipt() = WitnessCreationReceipt(
    operation.toWitnessOperation(), revision,
    when (phase) {
        Enrollment.Phase.PREPARING -> JournalPhase.PREPARING
        Enrollment.Phase.BOUND -> JournalPhase.BOUND
    },
    creationNonce, witnessId,
)

private fun WitnessMutation.toEnrollmentWrite(): Enrollment.WitnessWrite = when (this) {
    is WitnessMutation.Stored -> Enrollment.WitnessWrite.Stored(record.toEnrollmentRecord(), newlyAuthorized)
    is WitnessMutation.NotRetained -> Enrollment.WitnessWrite.NotRetained(problem.toEnrollmentFailure())
    WitnessMutation.Stale -> Enrollment.WitnessWrite.Stale
    is WitnessMutation.Indeterminate -> Enrollment.WitnessWrite.Indeterminate(operation.toEnrollmentOperation())
}

private fun WitnessProblem.toEnrollmentFailure(): Enrollment.Failure = when (this) {
    WitnessProblem.ACKNOWLEDGMENT_REQUIRED -> Enrollment.Failure.ACKNOWLEDGMENT_REQUIRED
    WitnessProblem.ACKNOWLEDGMENT_MISMATCH -> Enrollment.Failure.ACKNOWLEDGMENT_DECLINED
    WitnessProblem.MISSING_AUTHENTICATOR, WitnessProblem.AUTHENTICATION_FAILED ->
        Enrollment.Failure.IDENTITY_MISMATCH
    WitnessProblem.LOCKED, WitnessProblem.STORAGE_UNAVAILABLE -> Enrollment.Failure.STORAGE_UNAVAILABLE
    WitnessProblem.CORRUPT_STORAGE -> Enrollment.Failure.CORRUPT_STORAGE
    WitnessProblem.INVALID_RECEIPT -> Enrollment.Failure.INVALID_RECEIPT
}
