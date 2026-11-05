// AndroidOnly: WP-201 Incarnation-bound channel acceptance bookkeeping and exact successful cleanup.
package com.meshcoreone.android.core.contracts.domain

import java.util.UUID

/** Storage-issued authority for one committed completion, never reconstructible from its receipt. */
interface ChannelSendCompletion

/** Product send bookkeeping, not ACK confirmation or evidence of recipient delivery. */
enum class ChannelSendCompletionMode { SEND, RESEND }

/**
 * Immutable caller-acceptance/commit identity plus a transaction-bound capture observation.
 * acceptedTimestamp is the actual UInt32 Unix-seconds timestamp submitted to the accepted channel
 * invocation; it must match the captured message timestamp, not a later wall clock or replacement.
 * mode and timestamp remain bound to completionRequestId across replay, invalidation and retirement.
 * pendingCompletionRequestId identifies exact own cleanup, not absence inferred by message key.
 * capture retains original capture/key/pending IDs; its live DTOs appear ONLY with CURRENT exact
 * bindings in that transaction. Noncurrent history has both DTOs null and reissues no authority.
 * No payload snapshots, ACK code, fabricated delivery tuple or indexing certificate are persisted here.
 */
data class ChannelSendCompletionReceipt(
    val capture: ChannelFailureReceipt,
    val completionRequestId: UUID,
    val mode: ChannelSendCompletionMode,
    val acceptedTimestamp: UInt,
    val pendingCompletionRequestId: UUID?,
)

sealed interface ChannelSendCompletionResult {
    data class Applied(
        val completion: ChannelSendCompletion,
        val receipt: ChannelSendCompletionReceipt,
    ) : ChannelSendCompletionResult

    /**
     * Exact historical commit. completion is nonnull ONLY while that exact committed version,
     * expected revisions and lease are still current; null does not renew or reconstruct a handle.
     */
    data class AlreadyApplied(
        val completion: ChannelSendCompletion?,
        val receipt: ChannelSendCompletionReceipt,
    ) : ChannelSendCompletionResult

    /** Preserve the bound DELIVERED row; no SENT/count/repeat write, completion or cleanup grant. */
    data class AlreadyDelivered(val capture: ChannelFailureReceipt) : ChannelSendCompletionResult
    data class Rejected(val reason: DirectAckRejection) : ChannelSendCompletionResult
}

sealed interface ChannelSendPendingCompletionResult {
    data class Applied(val receipt: ChannelSendCompletionReceipt) : ChannelSendPendingCompletionResult
    /** Exact historical deletion only; never deletes a replacement or renews a cleanup handle. */
    data class AlreadyApplied(val receipt: ChannelSendCompletionReceipt) : ChannelSendPendingCompletionResult
    data class Rejected(val reason: DirectAckRejection) : ChannelSendPendingCompletionResult
}

sealed interface ChannelSendCompletionObservation {
    /** Committed history only; this observation never issues a completion handle. */
    data class Found(val receipt: ChannelSendCompletionReceipt) : ChannelSendCompletionObservation

    /**
     * No completion commit is recorded for these exact IDs in this read transaction. This is NOT
     * definitely-unsent evidence, proof of rollback, permission to redispatch or a negative ACK.
     */
    data class NotRecorded(
        val captureRequestId: UUID,
        val completionRequestId: UUID,
    ) : ChannelSendCompletionObservation

    data class Rejected(val reason: DirectAckRejection) : ChannelSendCompletionObservation
}

/**
 * Optional additive success role sharing ChannelFailureAuthority's real issuer/session/capture.
 * It neither extends/default-implements generic persistence nor changes the frozen failure/event API.
 * A missing role is explicitly unavailable, not generic SENT, broad pending deletion or fake success.
 *
 * Caller chooses completionRequestId BEFORE the physical invocation on the already-issued capture.
 * Only a genuine successful transport return/callback supplies acceptance; request IDs, DTO equality,
 * predicted timestamps, a later fetch, firmware ACKs or other captures' caches do not manufacture it.
 * The caller retains the SAME opaque attempt, completion request, mode and actual timestamp through
 * storage faults, cancellation, indexing retries and uncertain commit outcomes. This records the
 * caller's observed acceptance, NOT proof of RF transmission, recipient delivery or exactly-once send.
 * It introduces no physical-dispatch journal and cannot recover acceptance lost only in RAM on death.
 *
 * After known acceptance, do not physically resend, bump pending attempts, prepare a replacement or
 * transfer accepted/completed/indexed flags by message ID. Retry only exact completion/cleanup or
 * inspect its commit. NotRecorded and thrown exceptions do not resolve physical uncertainty.
 * Generic DML, including same-value writes, still invalidates; never read back to renew a grant.
 * Storage failures/cancellation propagate unchanged, not fabricated Applied/Uncertain receipts.
 *
 * Success/index progress is scoped to the original capture/version and opaque completion, never just
 * radio/message key, timestamp or returned receipt. Indexing may occur after SENT and before cleanup;
 * an indexing failure retains this completion, without sending again or recapturing by key. A stale
 * grant retires that work explicitly; neither a newly prepared same-key row nor an old ID cache can
 * inherit success. A suspended external index callback is not atomic with the storage grant; this
 * contract certifies neither its RAM reaction cache nor its pending-reaction effects or exactly-once
 * indexing. Its separate consumer identity/retirement requirements must not be bypassed by read checks.
 *
 * Receipts are past observations, not permission for later ID-only SENT/Resent/Failed, mark-seen or
 * indexing effects on a replacement. Preserve successful observable behavior through neutral
 * PersistenceChanged(key) refetch or separately fenced consumers, not old status/DTO application.
 */
interface ChannelSendCompletionAuthority {
    /**
     * In ONE transaction validate the original opaque capture's issuer, active lease, message and
     * optional exact pending incarnations/revisions, bind the caller-acceptance tuple and set SENT.
     * Require an actual outgoing channel row, captured timestamp equality and matching CHANNEL pending
     * relationship; a captured pending's isResend must agree with mode. SEND preserves counters/repeats.
     * RESEND additionally performs the existing checked sendCount increment, sets heardRepeats to
     * zero and deletes only this bound message's repeats, all atomically with SENT and the journal.
     * Preserve DELIVERED without regression, count/repeat mutation or issuing completion authority.
     *
     * Own writes alone advance the expected message revision in that transaction and issue the
     * completion handle; do not broaden generic-writer exemptions or delete any pending row here.
     * A committed completion prevents new failure/remap/new completion on the old failure handle.
     * One capture has at most one accepted completion; another completion ID or changed tuple is
     * CONFLICT. A remap successor is not another physical acceptance of an already-completed capture.
     * Exact same-operation replay is checked before current eligibility and is read-only even after
     * invalidation, cleanup, retirement or replacement; return its real permission/DTO absence and
     * a handle only for its still-current exact committed version. Never reapply resend bookkeeping.
     * Rejection or rollback writes no partial SENT/count/repeat/acceptance/completion history.
     * A thrown return alone does not establish rollback; reconcile the exact original request.
     */
    suspend fun recordChannelSendCompleted(
        attempt: ChannelFailureAttempt,
        completionRequestId: UUID,
        mode: ChannelSendCompletionMode,
        acceptedTimestamp: UInt,
    ): ChannelSendCompletionResult

    /**
     * After the caller's required indexing has actually returned, atomically validate this issued
     * completion and its current lease/message/pending bindings, then delete ONLY its captured
     * pending row and journal pendingCompletionRequestId. Null captured pending ID is NOT_ELIGIBLE;
     * no broad message-key deletion, current-row substitution or readback-as-grant is permitted.
     * Preserve every message field, including SENT/DELIVERED, counters and repeats. Own deletion
     * retires the completion/capture grant; both receipt DTOs are null, with exact pending/commit IDs
     * retained. Exact same cleanup replay precedes current-row eligibility and returns AlreadyApplied
     * without touching any replacement. A different cleanup request after own commit is CONFLICT.
     * This commits cleanup only, not a certificate that external indexing was identity-safe.
     */
    suspend fun completeChannelSendPending(
        completion: ChannelSendCompletion,
        pendingCompletionRequestId: UUID,
    ): ChannelSendPendingCompletionResult

    /**
     * Read persisted tuple/cleanup history by exact original capture and completion request.
     * Never create a grant, return replacement DTOs, treat missing history as definitely unsent or
     * erase a committed outcome because its lease/grant is now stale. Foreign issuers remain invalid.
     */
    suspend fun inspectChannelSendCompletion(
        lease: DirectAckLease,
        captureRequestId: UUID,
        completionRequestId: UUID,
    ): ChannelSendCompletionObservation
}
