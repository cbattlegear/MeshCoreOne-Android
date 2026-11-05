// AndroidOnly: WP-201 Exact pending-row completion when no direct-message wire attempt is made.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.PendingSendDTO
import java.util.UUID

/** Storage-issued capture only; never a direct-ACK attempt or permission to send. */
interface DirectPendingCompletion

sealed interface DirectPendingCompletionReason {
    /** Storage verifies outgoing direct message, DELIVERED, and a non-resend DM pending row. */
    data object AlreadyDeliveredSend : DirectPendingCompletionReason

    /**
     * Configuration metadata, not delivery proof. Storage admits only exactly zero and verifies
     * the actual matching outgoing direct message and DM pending row.
     */
    data class ZeroAttempts(val configuredMaximumAttempts: Long) : DirectPendingCompletionReason
}

enum class DirectPendingCompletionPermission { CURRENT, INVALIDATED, STALE_LEASE, MISSING }

/**
 * Immutable capture/commit history, not the current rows or a publicly reconstructible grant.
 * completedRequestId records the exact deletion operation even after subsequent invalidation.
 * Null DTOs explicitly mean erased payload, not missing identity or fabricated success. CURRENT
 * requires both actual snapshots. Message deletion/key replacement or payload-field updates/redaction
 * purge both snapshots; pending deletion/replacement/update (including own completion) purges
 * pending payload while the message snapshot remains until message erasure. Identity/operation
 * tombstones remain, with non-CURRENT permission and no handle reissue or payload reconstruction.
 * Revoking/replacing/closing the lease atomically erases all its remaining message/pending snapshots
 * and marks these histories STALE_LEASE even when the ordinary message row still exists.
 */
data class DirectPendingCompletionReceipt(
    val captureRequestId: UUID,
    val key: EntityKey,
    val reason: DirectPendingCompletionReason,
    val message: MessageDTO?,
    val pendingSendId: UUID,
    val pending: PendingSendDTO?,
    val completedRequestId: UUID?,
    val permission: DirectPendingCompletionPermission,
)

sealed interface DirectPendingCaptureResult {
    data class Captured(
        val completion: DirectPendingCompletion,
        val receipt: DirectPendingCompletionReceipt,
    ) : DirectPendingCaptureResult
    data class AlreadyCaptured(
        val completion: DirectPendingCompletion,
        val receipt: DirectPendingCompletionReceipt,
    ) : DirectPendingCaptureResult
    data class Rejected(val reason: DirectAckRejection) : DirectPendingCaptureResult
}

sealed interface DirectPendingCompletionResult {
    data class Applied(val receipt: DirectPendingCompletionReceipt) : DirectPendingCompletionResult
    /** Exact historical commit; never deletes a replacement or restores capture permission. */
    data class AlreadyApplied(val receipt: DirectPendingCompletionReceipt) : DirectPendingCompletionResult
    data class Rejected(val reason: DirectAckRejection) : DirectPendingCompletionResult
}

sealed interface DirectPendingCompletionObservation {
    data class Found(val receipt: DirectPendingCompletionReceipt) : DirectPendingCompletionObservation
    data class Rejected(val reason: DirectAckRejection) : DirectPendingCompletionObservation
}

/**
 * Separate optional producer role for an already-delivered normal SEND or a zero-attempt drain.
 * It creates no ACK claim, predicted code, acceptance, delivery or sendCount update. An unavailable
 * implementation must be surfaced explicitly, not replaced by a generic delete or fake RESEND.
 *
 * Caller-known request IDs and storage-issued handles bind persisted issuer/lease and the actual
 * message AND pending-row incarnations/revisions. UUID equality or DTO readback is not authority.
 * Capture replay cannot renew invalidated bindings; changed inputs under the same ID are CONFLICT.
 * Storage failures/cancellation propagate unchanged and do not prove rollback. Inspection and exact
 * replay expose committed history, including after a lost return, without granting new permission.
 */
interface DirectPendingCompletionAuthority {
    /**
     * Capture AFTER the initial pending attempt bump and ALL legitimate no-dispatch finalization,
     * routing and message bookkeeping, then decide completion from this captured snapshot. Storage
     * revalidates the reason against actual matching direct rows in the capture transaction.
     * Generic message/pending writes after capture invalidate it; no context-wide exemption applies.
     * Exact replay returns a handle only for still-current bindings, never a new capture of a
     * replacement. No wire may be dispatched using this capture.
     */
    suspend fun captureDirectPendingCompletion(
        lease: DirectAckLease,
        key: EntityKey,
        pendingSendId: UUID,
        captureRequestId: UUID,
        reason: DirectPendingCompletionReason,
    ): DirectPendingCaptureResult

    /**
     * Atomically validate the captured lease/incarnations/revisions and delete only that pending
     * row. Preserve all message fields; do not create or regrant ACK authority. Existing storage
     * invalidation rules still apply to prior ACK claims; this is not a context-wide exemption.
     * Journal completionRequestId with the deletion. Exact replay returns AlreadyApplied even if
     * a replacement now exists, without deleting it; a different completion ID is CONFLICT.
     * Own committed deletion is history, not a generic missing-row success or authority renewal.
     * Its receipt retains pendingSendId/completedRequestId and the message unless erased, but erases
     * pending payload with non-CURRENT permission; it never persists the deleted pending DTO.
     */
    suspend fun completeDirectPendingWithoutDispatch(
        completion: DirectPendingCompletion,
        completionRequestId: UUID,
    ): DirectPendingCompletionResult

    /**
     * Historical snapshots/commit identity only, never a handle or permission upgrade. Retired
     * lease inspection retains IDs/commit request but returns erased DTO payloads.
     */
    suspend fun inspectDirectPendingCompletion(
        lease: DirectAckLease,
        captureRequestId: UUID,
    ): DirectPendingCompletionObservation
}
