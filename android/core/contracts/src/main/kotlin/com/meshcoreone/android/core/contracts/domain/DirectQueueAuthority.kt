// AndroidOnly: WP-208 Transaction-bound selection and cleanup of a queued direct message.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.PendingSendDTO
import java.util.UUID

/** An opaque, process-owned selection of actual persisted row incarnations. */
interface DirectQueueSelection

sealed interface DirectQueueCaptureResult {
    data class Captured(val selection: DirectQueueSelection, val pending: PendingSendDTO) : DirectQueueCaptureResult
    data class Rejected(val reason: DirectAckRejection) : DirectQueueCaptureResult
}

sealed interface DirectQueueIncarnationComparison {
    data object Same : DirectQueueIncarnationComparison
    data object Different : DirectQueueIncarnationComparison
    data class Rejected(val reason: DirectAckRejection) : DirectQueueIncarnationComparison
}

data class DirectQueueBumpReceipt(
    val selection: DirectQueueSelection,
    val pending: PendingSendDTO,
    val attemptCount: Long,
)

sealed interface DirectQueueBumpResult {
    data class Applied(val receipt: DirectQueueBumpReceipt) : DirectQueueBumpResult
    data class AlreadyApplied(val receipt: DirectQueueBumpReceipt) : DirectQueueBumpResult
    data class Rejected(val reason: DirectAckRejection) : DirectQueueBumpResult
}

data class DirectQueuePreparationReceipt(
    val selection: DirectQueueSelection,
    val message: MessageDTO,
    val statusResolved: Boolean,
)

sealed interface DirectQueuePreparationResult {
    data class Applied(val receipt: DirectQueuePreparationReceipt) : DirectQueuePreparationResult
    data class AlreadyApplied(val receipt: DirectQueuePreparationReceipt) : DirectQueuePreparationResult
    data class Rejected(val reason: DirectAckRejection) : DirectQueuePreparationResult
}

sealed interface DirectQueueMutation {
    /** Fail and remove the selected row only if its contact is still absent. */
    data object MissingContact : DirectQueueMutation

    /** Fail and remove the selected row only if this logical send never registered a wire. */
    data class DefinitelyUnsentFailure(val claimRequestId: UUID) : DirectQueueMutation

    /** Restore the selected non-delivered message to pending without deleting its queue row. */
    data object ReturnToPending : DirectQueueMutation
}

data class DirectQueueMutationReceipt(
    val pendingSendId: UUID,
    val status: MessageStatus?,
    val statusChanged: Boolean,
    val removed: Boolean,
)

sealed interface DirectQueueMutationResult {
    data class Applied(val receipt: DirectQueueMutationReceipt) : DirectQueueMutationResult
    /** A historical exact commit, not permission to mutate a replacement row. */
    data class AlreadyApplied(val receipt: DirectQueueMutationReceipt) : DirectQueueMutationResult
    data class Rejected(val reason: DirectAckRejection) : DirectQueueMutationResult
}

/**
 * Capture reads the pending and message incarnations together. Mutations validate those identities,
 * their revisions and the active lease in the same transaction as the write. A missing message is
 * captured as absence and must still be absent at mutation time. Exact operation replay is journaled;
 * UUID/DTO equality or a subsequent read alone cannot authorize a replacement. A tracked queued
 * attempt whose dispatch outcome was neither completed nor proven definitely unsent remains
 * unresolved across lease retirement; a fresh selection must surface UNRESOLVED_OUTCOME rather
 * than replay it. Explicit user retries require the exact persisted replacement incarnation.
 */
interface DirectQueueAuthority {
    suspend fun captureDirectQueueSelection(
        lease: DirectAckLease,
        key: EntityKey,
        pendingSendId: UUID,
    ): DirectQueueCaptureResult

    /** Compare issued pending incarnations; this is not permission to mutate either row. */
    fun compareDirectQueueSelections(
        first: DirectQueueSelection,
        second: DirectQueueSelection,
    ): DirectQueueIncarnationComparison

    /** Bump only the selected incarnation; exact replay never increments it a second time. */
    suspend fun bumpDirectQueueSelection(
        selection: DirectQueueSelection,
        requestId: UUID,
    ): DirectQueueBumpResult

    /**
     * Prepare the same pending and message incarnations atomically, recording the timestamp and
     * optional no-dispatch status transition once. The returned selection binds their new revision.
     * A same-incarnation message edit may be captured as the actual dispatch snapshot.
     */
    suspend fun prepareDirectQueueSelection(
        selection: DirectQueueSelection,
        requestId: UUID,
        timestamp: UInt?,
        resolveToSent: Boolean,
    ): DirectQueuePreparationResult

    /** Capture the actual wire inputs without rebinding a replaced pending or message incarnation. */
    suspend fun captureDirectQueueIntent(
        selection: DirectQueueSelection,
        requestId: UUID,
        mode: DirectAckMode,
    ): DirectAckIntentResult

    /** Capture a no-dispatch decision against the same selected incarnations. */
    suspend fun captureDirectQueueCompletion(
        selection: DirectQueueSelection,
        captureRequestId: UUID,
        reason: DirectPendingCompletionReason,
    ): DirectPendingCaptureResult

    /**
     * Refuse cleanup while a current claim or competing captured intent can act on the message.
     * DefinitelyUnsentFailure may invalidate its own captured intent, but not a registered claim.
     */
    suspend fun mutateDirectQueueSelection(
        selection: DirectQueueSelection,
        requestId: UUID,
        mutation: DirectQueueMutation,
    ): DirectQueueMutationResult
}
