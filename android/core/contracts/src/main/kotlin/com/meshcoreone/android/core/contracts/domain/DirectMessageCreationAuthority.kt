// AndroidOnly: WP-208 Atomic creation and definitely-unsent failure authority for an inline DM.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageStatus
import java.util.UUID

interface DirectMessageCreation

sealed interface DirectMessageCreationResult {
    data class Created(val creation: DirectMessageCreation) : DirectMessageCreationResult
    data class AlreadyCreated(val creation: DirectMessageCreation) : DirectMessageCreationResult
    data class Rejected(val reason: DirectAckRejection) : DirectMessageCreationResult
}

data class DirectMessageCreationFailureReceipt(val status: MessageStatus, val statusChanged: Boolean)

sealed interface DirectMessageCreationFailureResult {
    data class Applied(val receipt: DirectMessageCreationFailureReceipt) : DirectMessageCreationFailureResult
    data class AlreadyApplied(val receipt: DirectMessageCreationFailureReceipt) : DirectMessageCreationFailureResult
    data class Rejected(val reason: DirectAckRejection) : DirectMessageCreationFailureResult
}

data class DirectMessageCreationResolutionReceipt(val message: MessageDTO, val statusChanged: Boolean)

sealed interface DirectMessageCreationResolutionResult {
    data class Applied(val receipt: DirectMessageCreationResolutionReceipt) : DirectMessageCreationResolutionResult
    data class AlreadyApplied(val receipt: DirectMessageCreationResolutionReceipt) : DirectMessageCreationResolutionResult
    data class Rejected(val reason: DirectAckRejection) : DirectMessageCreationResolutionResult
}

/**
 * An inline creation binds the persisted message incarnation to its logical dispatch request.
 * Exact replay cannot insert or fail a replacement. Failure is ineligible after wire registration;
 * a lost creation return is recovered by inspection, not by inserting during failure cleanup.
 */
interface DirectMessageCreationAuthority {
    suspend fun createDirectMessage(
        lease: DirectAckLease,
        message: MessageDTO,
        creationRequestId: UUID,
        claimRequestId: UUID,
    ): DirectMessageCreationResult

    suspend fun recoverDirectMessageCreation(
        lease: DirectAckLease,
        key: EntityKey,
        creationRequestId: UUID,
        claimRequestId: UUID,
    ): DirectMessageCreationResult

    suspend fun captureCreatedDirectIntent(creation: DirectMessageCreation): DirectAckIntentResult

    suspend fun captureCreatedDirectRetryIntent(
        creation: DirectMessageCreation,
        mode: DirectAckMode,
    ): DirectAckIntentResult

    suspend fun failDirectMessageCreation(
        creation: DirectMessageCreation,
        failureRequestId: UUID,
    ): DirectMessageCreationFailureResult

    suspend fun failCreatedDirectRetryMessage(
        creation: DirectMessageCreation,
        failureRequestId: UUID,
    ): DirectMessageCreationFailureResult

    suspend fun resolveCreatedDirectWithoutDispatch(
        creation: DirectMessageCreation,
        requestId: UUID,
        resolveToSent: Boolean,
        attempt: DirectAckAttempt?,
    ): DirectMessageCreationResolutionResult
}
