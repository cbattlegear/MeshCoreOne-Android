// AndroidOnly: WP-201 Durable direct-ACK producer authority and transaction receipts.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.SnapshotList
import java.util.UUID

/** Storage-issued handles, not reconstructible identity DTOs. Implementations reject foreign issuers. */
interface DirectAckLease
interface DirectAckAttempt

enum class DirectAckRejection {
    INVALID_HANDLE, STALE_LEASE, INVALIDATED, MISSING, NOT_ELIGIBLE, CONFLICT,
}

enum class DirectAckPhase { REGISTERED, ACCEPTED, FAILED, DELIVERED }

/**
 * Storage dispatch admission, not product/UI resend intent. RESEND permits a real new wire dispatch
 * against a terminal baseline (including a pre-dispatch creation callback's write); it implies no
 * sendCount increment or Resent event. No-wire cleanup must use DirectPendingCompletionAuthority.
 */
enum class DirectAckMode { SEND, RESEND }

/**
 * CURRENT means the persisted lease, incarnation and mutation revision still match, not permission
 * to regress a terminal phase. All other values deny mutation, even when history records delivery.
 */
enum class DirectAckPermission { CURRENT, INVALIDATED, STALE_LEASE, MISSING }

data class DirectAckWireReceipt(
    val requestId: UUID,
    val predictedAckCode: UInt,
    val acceptedAckCode: UInt?,
)

/** The immutable snapshot at delivery commit, never evidence about a later/recreated current row. */
data class DirectAckDelivery(
    val ackCode: UInt,
    val roundTripTime: UInt?,
    val message: MessageDTO,
)

sealed interface DirectAckBookkeeping {
    /** Set RETRYING and these counters, preserving the existing nonterminal guard. */
    data class RetryStarted(val retryAttempt: Long, val maxRetryAttempts: Long) : DirectAckBookkeeping

    /** Set SENT only for a nonterminal row; preserve counters, like clearRetryingToSent. */
    data object RetryExhausted : DirectAckBookkeeping

    /** Checked increment, once per operation request, including after this claim's own delivery. */
    data object ResendCountIncrement : DirectAckBookkeeping
}

data class DirectAckBookkeepingReceipt(val requestId: UUID, val change: DirectAckBookkeeping)

data class DirectAckPendingReceipt(val requestId: UUID, val pendingSendId: UUID)

/**
 * Persisted history plus a transaction-bound permission observation. Public values confer no
 * authority. message is the last own committed snapshot, not an unguarded fetch of the current row.
 * Wire and bookkeeping histories survive invalidation for commit-then-throw reconciliation.
 */
data class DirectAckReceipt(
    val key: EntityKey,
    val requestId: UUID,
    val mode: DirectAckMode,
    val initialStatus: MessageStatus,
    val phase: DirectAckPhase,
    val wires: SnapshotList<DirectAckWireReceipt>,
    val bookkeeping: SnapshotList<DirectAckBookkeepingReceipt>,
    val pendingCompletions: SnapshotList<DirectAckPendingReceipt>,
    val message: MessageDTO,
    val delivery: DirectAckDelivery?,
    val permission: DirectAckPermission,
)

sealed interface DirectAckActivationResult {
    data class Activated(val lease: DirectAckLease) : DirectAckActivationResult
    data class AlreadyActivated(val lease: DirectAckLease) : DirectAckActivationResult
    data class Rejected(val reason: DirectAckRejection) : DirectAckActivationResult
}

sealed interface DirectAckBeginResult {
    data class Begun(val attempt: DirectAckAttempt, val receipt: DirectAckReceipt) : DirectAckBeginResult
    data class AlreadyBegun(val attempt: DirectAckAttempt, val receipt: DirectAckReceipt) : DirectAckBeginResult
    data class Rejected(val reason: DirectAckRejection) : DirectAckBeginResult
}

sealed interface DirectAckMutationResult {
    data class Applied(val receipt: DirectAckReceipt) : DirectAckMutationResult
    /** Historical exact-operation commit; never regrants mutation permission. */
    data class AlreadyApplied(val receipt: DirectAckReceipt) : DirectAckMutationResult
    /** Own retry/status work cannot regress a delivery that won the race. */
    data class AlreadyDelivered(val receipt: DirectAckReceipt) : DirectAckMutationResult
    data class Rejected(val reason: DirectAckRejection) : DirectAckMutationResult
}

sealed interface DirectAckObservation {
    data class Found(val receipt: DirectAckReceipt) : DirectAckObservation
    data class Rejected(val reason: DirectAckRejection) : DirectAckObservation
}

sealed interface DirectAckRevocationResult {
    data object Revoked : DirectAckRevocationResult
    data object AlreadyRevoked : DirectAckRevocationResult
    data class Rejected(val reason: DirectAckRejection) : DirectAckRevocationResult
}

/**
 * Optional, additive producer capability; it does not extend or default-implement MessagePersisting.
 * Consumers must explicitly surface an unavailable capability, not substitute generic Boolean writes.
 *
 * Every operation validates persisted issuer, lease, true message incarnation and mutation revision
 * in the same transaction as its writes/receipt. Generic message or related queue DML (including
 * same-value writes, retries, replacement, deletion/recreation and restore entries skipped on import)
 * invalidates grants at the storage layer, across raw DAOs and other database handles. An own write
 * may conditionally renew only its prevalidated grant in that transaction, never revive an invalid
 * grant. An in-memory lock, matching DTO/UUID or readback does not establish this authority.
 *
 * Request IDs are caller-known before invocation and retained on uncertain return. Exact replay
 * observes the persisted operation; a reused ID with different inputs is CONFLICT. Actual storage
 * failures and cancellation propagate unchanged: neither proves rollback nor creates an Uncertain
 * success receipt. Inspect/replay reconciles persisted evidence without resending the wire command.
 * This interface does not promise durable ingress of an ACK seen only in process memory.
 */
interface DirectAckAuthority {
    /**
     * Explicit process-owned activation atomically fences the previous lease. Exact request replay
     * cannot replace a newer lease. Distinct activations strictly advance generation within an epoch;
     * retired epochs/issuer contexts cannot reactivate via late requests or reconstructed handles.
     * Cross-epoch activation requires a genuinely new process owner, not UUID ordering.
     */
    suspend fun activateDirectAckSession(token: SessionToken, requestId: UUID): DirectAckActivationResult

    /**
     * Register a logical claim AND first predicted code before dispatch, binding the actual current
     * outgoing direct-message incarnation/revision. SEND excludes FAILED/DELIVERED; explicit RESEND
     * preserves existing terminal status while authorizing only this new dispatch's codes. A baseline
     * DELIVERED row is not this claim's own delivered receipt. Generic external retry remapping happens
     * before begin and invalidates the previous claim. A new logical claim atomically fences any
     * previous claim on this incarnation, including a previous explicit resend.
     * Replay returns a handle only for the same still-current claim; it never recreates invalidated
     * authority. Logical/wire request tombstones remain until lease revocation/replacement.
     * A nonnull pendingSendId captures that exact pending-row incarnation/revision before dispatch;
     * missing or replaced pending state is a non-success, never inferred from matching UUIDs.
     */
    suspend fun beginDirectAckAttempt(
        lease: DirectAckLease,
        key: EntityKey,
        requestId: UUID,
        firstWireRequestId: UUID,
        predictedAckCode: UInt,
        mode: DirectAckMode,
        pendingSendId: UUID?,
    ): DirectAckBeginResult

    /** Before each additional wire dispatch; preserve all prior codes on this same logical claim. */
    suspend fun registerDirectAckDispatch(
        attempt: DirectAckAttempt,
        wireRequestId: UUID,
        predictedAckCode: UInt,
    ): DirectAckMutationResult

    /**
     * Bind the actual returned code to its registered wire request, adding rather than replacing
     * predicted/prior codes. Late acceptance may record binding history after own FAILED/DELIVERED,
     * but must not regress the message or restore mutation permission after invalidation.
     * An initially FAILED/DELIVERED RESEND baseline retains its terminal status on acceptance;
     * only a genuine registered ACK can settle delivery, never a generic SENT relabel.
     */
    suspend fun recordDirectSendAccepted(
        attempt: DirectAckAttempt,
        wireRequestId: UUID,
        ackCode: UInt,
    ): DirectAckMutationResult

    /**
     * Own failure only; a competing same-value FAILED write is not this attempt's failure.
     * Neither failure nor retry/acceptance may regress a preexisting DELIVERED resend baseline.
     */
    suspend fun recordDirectFailure(attempt: DirectAckAttempt): DirectAckMutationResult

    /**
     * Atomic own SENT/FAILED (or pre-return registered predicted-code) delivery, with no intermediate
     * public SENT promotion. Only registered predicted or accepted codes are eligible. Applied and
     * AlreadyApplied carry DELIVERED history and its exact code/RTT/committed DTO. Exact tuple replay
     * can report historical delivery without granting mutation or describing the current row;
     * a different delivery tuple is CONFLICT. A RESEND of an initially DELIVERED row may commit its
     * new registered wire tuple without ever downgrading that row's status.
     */
    suspend fun settleDirectAck(
        attempt: DirectAckAttempt,
        ackCode: UInt,
        roundTripTime: UInt?,
    ): DirectAckMutationResult

    /**
     * Durable operation-ID journaling prevents double increments after commit-then-throw. Retry
     * changes on own delivery return AlreadyDelivered without regression. Post-delivery sendCount
     * requires the still-current lease/incarnation/revision and never regrants ACK authority.
     */
    suspend fun applyDirectAckBookkeeping(
        attempt: DirectAckAttempt,
        requestId: UUID,
        change: DirectAckBookkeeping,
    ): DirectAckMutationResult

    /**
     * Complete only the pending-row incarnation/revision captured by begin, atomically preserving
     * the still-current logical claim for late ACKs. Never delete a replacement row. Exact request
     * replay reports its persisted completion without deleting anything newly inserted; generic
     * deletion remains invalidating when it actually mutates a row.
     */
    suspend fun completeDirectPending(
        attempt: DirectAckAttempt,
        pendingSendId: UUID,
        requestId: UUID,
    ): DirectAckMutationResult

    /** Historical evidence only; never returns a handle or upgrades permission. */
    suspend fun inspectDirectAckAttempt(lease: DirectAckLease, requestId: UUID): DirectAckObservation

    /** Idempotent durable revocation fences pending writes before any successor can act. */
    suspend fun revokeDirectAckSession(lease: DirectAckLease): DirectAckRevocationResult
}
