// AndroidOnly: WP-201 Incarnation-bound channel failure and exact pending-row cleanup.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.PendingSendDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.TextType
import java.time.Instant
import java.util.UUID

/** Storage-issued, context/lease-bound and revision-fenced; public receipt values are not a handle. */
interface ChannelFailureAttempt

/** Stable identity of the channel occupant that owned a history slot when a message was created. */
data class ChannelHistoryScope(
    val radioId: RadioId,
    val channelIndex: UByte,
    val expectedOccupantID: UUID,
)

/** Caller-stable creation intent. messageID is allocated once and reused only for exact reconciliation. */
data class ChannelMessageCreation(
    val messageID: UUID,
    val origin: ChannelHistoryScope,
    val text: String,
    val textType: TextType,
)

data class ChannelMessageCreationReceipt(
    val origin: ChannelHistoryScope,
    val envelope: MessageDTO,
)

sealed interface ChannelMessageCreationResult {
    data class Created(
        val attempt: ChannelFailureAttempt,
        val receipt: ChannelMessageCreationReceipt,
    ) : ChannelMessageCreationResult

    /** Exact replay may return a handle only for the same live issuer; receipt is always read-only. */
    data class Reconciled(
        val attempt: ChannelFailureAttempt?,
        val receipt: ChannelMessageCreationReceipt,
    ) : ChannelMessageCreationResult

    data class Rejected(val reason: DirectAckRejection) : ChannelMessageCreationResult
}

enum class ChannelFailureCleanup { KEEP_PENDING, DELETE_CAPTURED_PENDING }

enum class ChannelFailureOperation { REMAPPED_TO_PENDING, FAILED, FAILED_AND_PENDING_DELETED }

data class ChannelFailureOperationReceipt(
    val requestId: UUID,
    val operation: ChannelFailureOperation,
)

/**
 * Persisted capture/own-operation identity plus transaction-bound current payload, not a row cache.
 * captureRequestId identifies the original creation/preparation even after a conditional remap.
 * No message/pending payload is persisted in authority history. Return actual live DTOs ONLY after
 * the exact incarnation/revision and active lease are proved in the same read transaction. CURRENT
 * requires message and any captured pending payload; otherwise both DTOs are null, never read from a
 * replacement or reconstructed. Null pending with null pendingSendId means no pending was captured,
 * not permission to delete by message ID. Generic mutation, deletion, replacement or own pending
 * cleanup invalidates the grant; lease retirement/close reports STALE_LEASE. Nonpayload identity,
 * immutable capture-argument binding and operation tombstones survive for commit reconciliation.
 */
data class ChannelFailureReceipt(
    val captureRequestId: UUID,
    val key: EntityKey,
    val message: MessageDTO?,
    val pendingSendId: UUID?,
    val pending: PendingSendDTO?,
    val permission: DirectAckPermission,
    val operations: SnapshotList<ChannelFailureOperationReceipt>,
)

sealed interface ChannelFailureCaptureResult {
    data class Captured(
        val attempt: ChannelFailureAttempt,
        val receipt: ChannelFailureReceipt,
    ) : ChannelFailureCaptureResult
    data class AlreadyCaptured(
        val attempt: ChannelFailureAttempt,
        val receipt: ChannelFailureReceipt,
    ) : ChannelFailureCaptureResult
    data class Rejected(val reason: DirectAckRejection) : ChannelFailureCaptureResult
}

sealed interface ChannelFailureRemapResult {
    data class Remapped(
        val attempt: ChannelFailureAttempt,
        val receipt: ChannelFailureReceipt,
    ) : ChannelFailureRemapResult
    /** Return the existing successor only while its exact grant is still current. */
    data class AlreadyRemapped(
        val attempt: ChannelFailureAttempt,
        val receipt: ChannelFailureReceipt,
    ) : ChannelFailureRemapResult
    data class AlreadyDelivered(val receipt: ChannelFailureReceipt) : ChannelFailureRemapResult
    data class Rejected(val reason: DirectAckRejection) : ChannelFailureRemapResult
}

sealed interface ChannelFailureResult {
    data class Applied(val receipt: ChannelFailureReceipt) : ChannelFailureResult
    /** Historical exact commit, not renewed permission or permission to delete a replacement. */
    data class AlreadyApplied(val receipt: ChannelFailureReceipt) : ChannelFailureResult
    /** Preserve DELIVERED; this result commits neither failure nor pending deletion. */
    data class AlreadyDelivered(val receipt: ChannelFailureReceipt) : ChannelFailureResult
    data class Rejected(val reason: DirectAckRejection) : ChannelFailureResult
}

sealed interface ChannelFailureObservation {
    data class Found(val receipt: ChannelFailureReceipt) : ChannelFailureObservation
    data class Rejected(val reason: DirectAckRejection) : ChannelFailureObservation
}

/**
 * Optional channel-only role; no extension/default implementation or change to generic persistence.
 * A current-row channel predicate excludes a replacement DM but does NOT bind an earlier channel.
 * These handles bind the actual message incarnation/revision, and any captured pending incarnation/
 * revision, in the creation/preparation transaction. A later DTO read, matching fields/UUIDs, empty
 * journal or legacy envelope cannot recover an earlier identity or certify physical send history.
 * Consumers use returned actual snapshots and share one attempt across service/queue ownership.
 *
 * Every write conditionally validates issuer, persisted session lease and exact bindings in its
 * transaction. Generic message/pending DML, including same-value writes, invalidates the grant.
 * Only the exact own typed write may advance its expected revision in that same transaction.
 * Requests are caller-known BEFORE invocation; immutable arguments and committed operations permit
 * commit-then-throw inspection/replay. Changed arguments under the same request are CONFLICT.
 * Storage exceptions/cancellation propagate unchanged: neither proves rollback or an Uncertain result.
 *
 * A receipt proves a past commit, NEVER authority for a later message-ID-only Failed publication.
 * Use identity-safe storage observation, neutral invalidation that only refetches actual state without
 * applying a status/mark-seen effect, or separately fenced event consumers. A readback before
 * publication is not a grant. Missing capability/capture must surface unavailability and retain
 * uncertain work, not fall back to generic FAILED, broad deletion or capture from an old DTO.
 */
interface ChannelFailureAuthority {
    /**
     * Explicit channel-role access to the SAME durable session issuer used by direct authority;
     * channel-only consumers need not implement/depend on the whole DirectAckAuthority interface.
     * One session owner shares its token/request ID across roles, not two independent activations.
     * Atomically fence a prior lease; exact activation replay never replaces a newer lease or
     * reactivates a retired issuer/epoch. This is lifecycle ownership, not a physical-history proof.
     */
    suspend fun activateChannelFailureSession(
        token: SessionToken,
        requestId: UUID,
    ): DirectAckActivationResult

    /**
     * Atomically INSERT a new outgoing PENDING channel message and capture its true identity.
     * Require matching lease radio, nonnull channelIndex and null contactID. Never upsert an existing
     * key. Exact request replay returns only the still-current original capture, never overwrites or
     * captures a replacement. No pending row is created and no physical-history proof is issued.
     */
    suspend fun createChannelFailureAttempt(
        lease: DirectAckLease,
        creationRequestId: UUID,
        message: MessageDTO,
    ): ChannelFailureCaptureResult

    /**
     * Atomically create an outgoing pending channel message and persist its original occupant scope.
     * messageID is the stable intent identity; retries must reuse it with the same origin, text,
     * text type, timestamp and createdAt. Return the canonical stored DTO, never a DTO sampled from
     * a replacement session. Exact reconciliation never overwrites a different message or origin;
     * a non-current historical message yields a receipt without a renewed handle.
     */
    suspend fun createOrReconcileChannelMessage(
        lease: DirectAckLease,
        creation: ChannelMessageCreation,
        timestamp: UInt,
        createdAt: Instant,
    ): ChannelMessageCreationResult

    /**
     * Atomically read the actual outgoing channel row, validate any exact CHANNEL pending row's
     * radio/message/channel relationship, apply optional resendTimestamp, then capture both bindings.
     * Null timestamp preserves the actual timestamp; no attempt-count bump occurs here. Queue callers
     * prepare after their admitted bump, before dispatch/query, and consume these returned snapshots.
     * This binds rows AT preparation, not a prior DTO, envelope or preflight bump's historical identity.
     * Do not use preparation to renew a stale/invalidated attempt or recover a missing original grant.
     * DELIVERED is ineligible; exact replay cannot apply the timestamp twice or bind replacement rows.
     */
    suspend fun prepareChannelFailureAttempt(
        lease: DirectAckLease,
        prepareRequestId: UUID,
        key: EntityKey,
        pendingSendId: UUID?,
        resendTimestamp: UInt?,
    ): ChannelFailureCaptureResult

    /**
     * Conditionally set the bound non-DELIVERED message to PENDING, preserving counters/payload and
     * the exact pending row. Journal and return a successor handle for the SAME incarnations with
     * the new expected message revision. The prior version cannot perform a new operation.
     * Exact remap replay returns its successor only while current; otherwise reject without regrant.
     * Inspection still exposes its historical commit. Delivered wins without remap or new authority.
     */
    suspend fun remapChannelFailureAttempt(
        attempt: ChannelFailureAttempt,
        requestId: UUID,
    ): ChannelFailureRemapResult

    /**
     * In ONE transaction validate all bindings, preserve DELIVERED, write FAILED and journal the exact
     * operation. KEEP_PENDING retains this handle's own updated grant for later exact terminal cleanup.
     * DELETE_CAPTURED_PENDING additionally requires a captured pending row and deletes ONLY that row;
     * it atomically journals FAILED_AND_PENDING_DELETED and retires the grant with both DTOs null.
     * Neither mode uses a broad message-key deletion. Own prior FAILED remains eligible for exact
     * cleanup; an independent FAILED/same-value write invalidates the grant instead.
     * Exact same-operation replay precedes current-row eligibility and returns AlreadyApplied even
     * after replacement/retirement, with actual noncurrent permission/erasure and no new mutation.
     * A different cleanup mode under the same request is CONFLICT. Other stale/missing/ineligible
     * states are explicit rejections, not a readback guess or a success-shaped fallback.
     */
    suspend fun recordChannelFailure(
        attempt: ChannelFailureAttempt,
        requestId: UUID,
        cleanup: ChannelFailureCleanup,
    ): ChannelFailureResult

    /** Read persisted capture/operation history only; never issue/reconstruct a handle or renew a grant. */
    suspend fun inspectChannelFailureAttempt(
        lease: DirectAckLease,
        captureRequestId: UUID,
    ): ChannelFailureObservation

    /**
     * Revoke the shared durable lease, idempotently fencing both roles' grants. Lifecycle ownership
     * revokes once after admitted persistence completion, not independently for each capability.
     * Revoking an older lease cannot revoke a successor; no product message/pending row is deleted.
     */
    suspend fun revokeChannelFailureSession(lease: DirectAckLease): DirectAckRevocationResult
}
