// AndroidOnly: WP-204 Nonactivated authenticated notification enrollment persistence protocol.
package com.meshcoreone.android.core.datastore.enrollment

import java.util.UUID

data class WitnessOperation(val operationId: UUID, val storeId: UUID, val creationNonce: UUID)

enum class JournalPhase { PREPARING, BOUND }

data class WitnessCreationReceipt(
    val operation: WitnessOperation,
    val revision: Long,
    val phase: JournalPhase,
    val creationNonce: UUID,
    val witnessId: UUID?,
) {
    internal fun valid(): Boolean = creationNonce == operation.creationNonce && when (phase) {
        JournalPhase.PREPARING -> revision == 1L && witnessId == null
        JournalPhase.BOUND -> revision == 2L && witnessId != null
    }
}

enum class WitnessPhase { AUTHORIZED, ACCEPTED, BOUND }

data class EnrollmentWitnessRecord(
    val operation: WitnessOperation,
    val witnessId: UUID,
    val revision: Long,
    val phase: WitnessPhase,
    val receipt: WitnessCreationReceipt?,
) {
    internal fun valid(): Boolean = when (phase) {
        WitnessPhase.AUTHORIZED -> revision == 1L && receipt == null
        WitnessPhase.ACCEPTED -> revision == 2L && receipt?.let {
            it.valid() && it.operation == operation && it.phase == JournalPhase.PREPARING
        } == true
        WitnessPhase.BOUND -> revision == 3L && receipt?.let {
            it.valid() && it.operation == operation && it.phase == JournalPhase.BOUND && it.witnessId == witnessId
        } == true
    }
}

enum class WitnessProblem {
    ACKNOWLEDGMENT_REQUIRED, ACKNOWLEDGMENT_MISMATCH, MISSING_AUTHENTICATOR,
    LOCKED, CORRUPT_STORAGE, AUTHENTICATION_FAILED, STORAGE_UNAVAILABLE, INVALID_RECEIPT,
}

sealed interface WitnessObservation {
    /** Read-only absence is never first-install evidence or permission to create anything. */
    data object UnknownOrLost : WitnessObservation
    data class Current(val record: EnrollmentWitnessRecord) : WitnessObservation
    data class Failed(val problem: WitnessProblem) : WitnessObservation
}

data class WitnessAuthorizationRequest(
    val operation: WitnessOperation,
    val acknowledgmentId: UUID,
    val expectedCurrent: EnrollmentWitnessRecord?,
)

/**
 * Trusted foreground boundary, deliberately without a default implementation. Implementations must
 * verify a real acknowledgment of the versioned loss warning and exact prior observation/operation,
 * not a Boolean, a UUID's existence, DB absence, or successful biometric authentication alone.
 * It must independently durably consume that acknowledgment before returning NewlyVerified, and
 * return AlreadyConsumed on replay (including joint witness DB/key loss). Only an uninterrupted
 * first return may be NewlyVerified; uncertainty must not be retried as a fresh grant.
 * The warning UI and its verified, single-operation acknowledgment producer do not exist yet.
 */
fun interface ForegroundWitnessAcknowledgment {
    suspend fun verify(request: WitnessAuthorizationRequest): ForegroundAcknowledgmentResult
}

sealed interface ForegroundAcknowledgmentResult {
    data class NewlyVerified(val request: WitnessAuthorizationRequest) : ForegroundAcknowledgmentResult
    data object AlreadyConsumed : ForegroundAcknowledgmentResult
    data object Indeterminate : ForegroundAcknowledgmentResult
    data object Required : ForegroundAcknowledgmentResult
    data object Rejected : ForegroundAcknowledgmentResult
}

sealed interface WitnessMutation {
    /** Only the uninterrupted successful return of a NEW authorization sets newlyAuthorized. */
    data class Stored(val record: EnrollmentWitnessRecord, val newlyAuthorized: Boolean = false) : WitnessMutation
    data class NotRetained(val problem: WitnessProblem) : WitnessMutation
    data object Stale : WitnessMutation
    data class Indeterminate(val operation: WitnessOperation) : WitnessMutation
}
