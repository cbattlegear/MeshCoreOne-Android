// AndroidOnly: WP-201 Atomic queued channel admission and process-owned acceptance suppression.
package com.meshcoreone.android.core.contracts.domain

import java.util.UUID

/**
 * Identity-only observation of the original caller-acceptance tuple, not a persisted commit, current
 * row, reconstructible handle or physical-delivery certificate. The producer privately binds actual
 * message incarnation and stable pending lifecycle; matching these public IDs never proves identity.
 */
data class ChannelQueueAcceptanceReceipt(
    val captureRequestId: UUID,
    val key: EntityKey,
    val pendingSendId: UUID,
    val completionRequestId: UUID,
    val mode: ChannelSendCompletionMode,
    val acceptedTimestamp: UInt,
)

enum class ChannelQueueUncertaintyReason {
    /** Real observed acceptance retained by this process owner, not yet superseded by exact history. */
    RETAINED_ACCEPTANCE,
    /** Relevant committed history exists but lacks proof of the original stable pending lifecycle. */
    UNPROVEN_COMPLETION_LINEAGE,
}

sealed interface ChannelQueueAcceptanceResult {
    data class Registered(val acceptance: ChannelQueueAcceptanceReceipt) : ChannelQueueAcceptanceResult
    data class AlreadyRegistered(val acceptance: ChannelQueueAcceptanceReceipt) : ChannelQueueAcceptanceResult
    data class Rejected(val reason: DirectAckRejection) : ChannelQueueAcceptanceResult
}

sealed interface ChannelQueueAdmissionResult {
    /** CURRENT actual message and selected CHANNEL pending payloads, with the post-write grant. */
    data class Ready(
        val attempt: ChannelFailureAttempt,
        val receipt: ChannelFailureReceipt,
        val postBumpCount: Long,
    ) : ChannelQueueAdmissionResult

    /** Exact still-current admission replay only: no additional bump, timestamp write or new grant. */
    data class AlreadyReady(
        val attempt: ChannelFailureAttempt,
        val receipt: ChannelFailureReceipt,
        val postBumpCount: Long,
    ) : ChannelQueueAdmissionResult

    /** Committed same-work history, not authority to index, clean up, remap, fail or send anything. */
    data class PreviouslyCompleted(val receipt: ChannelSendCompletionReceipt) : ChannelQueueAdmissionResult

    /** Park this work. No handle, bump, timestamp write, dispatch or implicit pending deletion. */
    data class Uncertain(
        val reason: ChannelQueueUncertaintyReason,
        val acceptance: ChannelQueueAcceptanceReceipt,
    ) : ChannelQueueAdmissionResult

    data class Rejected(val reason: DirectAckRejection) : ChannelQueueAdmissionResult
}

/**
 * Separate optional role on the SAME process-owned repository/issuer as channel failure/completion.
 * It adds no default implementation and changes neither frozen role nor generic Boolean persistence.
 * A missing role is explicitly unavailable; do not fall back to generic bump + later preparation.
 *
 * One RoomPersistenceStore/process owner retains acceptance fences across service close, lease
 * revocation and connection generations. Session roles must delegate to that same repository, not
 * instantiate a per-session registry. Teardown joins owned transport operations before successor
 * generation admission; known successful returns register synchronously before cancellation or
 * ownership checks can discard them. Runtime shutdown precedes process-store disposal. Destroying
 * or reopening the process owner loses this RAM evidence; neither process-death recovery nor an
 * unobserved callback outcome, RF proof or exactly-once physical-send guarantee is claimed.
 *
 * Stable pending lifecycle is separate from Direct ACK's per-UPDATE incarnation/revision. Preserve
 * lifecycle only while radio/pending ID/message relationship is unchanged; DELETE + INSERT, changed
 * relationship or message recreation cannot inherit it. Capture the real birth identity at issue,
 * not by a later ID lookup or backfill. All ordinary same-value DML still invalidates mutation grants.
 * Committed completion and retained acceptance use actual message incarnation + stable pending
 * lifecycle, not row status, DTO equality, timestamps or an ID-keyed success cache.
 */
interface ChannelQueueAdmissionAuthority {
    /**
     * Caller selects requestId BEFORE invocation and retains it across uncertain storage returns.
     * In ONE transaction validate the shared issuer/active lease/radio and actual outgoing channel
     * message, then select the actual first ordered pending row using repository ordering. Require
     * its CHANNEL kind and exact radio/message/channel relationship; derive mode from its isResend,
     * never a hydrated envelope. Missing message/pending is MISSING; invalid relationship, nonchannel,
     * incoming or DELIVERED is NOT_ELIGIBLE. A foreign handle is INVALID_HANDLE; retired lease is
     * STALE_LEASE. No rejection mutates product rows or grants authority.
     *
     * BEFORE any bump or timestamp DML, compare that actual message incarnation/pending lifecycle
     * against recorded completion. Exact same-work history returns PreviouslyCompleted without a new
     * handle even after ordinary lease replacement or generic pending updates. It takes precedence
     * over a matching retained acceptance fence. Without that commit, a matching process-owner fence
     * returns Uncertain(RETAINED_ACCEPTANCE). Relevant known completion missing original lifecycle
     * proof returns Uncertain(UNPROVEN_COMPLETION_LINEAGE); never manufacture coverage from current
     * rows, timestamps or IDs, nor quarantine unrelated work merely because it has no completion.
     * These no-handle results preserve every message/pending field and issue no cleanup authority.
     *
     * Otherwise apply the existing checked (attemptCount ?: 0) + 1 to ONLY that selected pending row,
     * then capture its post-bump mutation incarnation/revision and resulting message revision.
     * There is NO maximum-attempt policy or pre-bump cap. Checked overflow propagates without partial
     * writes. SEND always preserves the actual message timestamp; RESEND applies freshTimestamp ONLY
     * when postBumpCount == 1, otherwise preserves it. Apply any timestamp write and capture its own
     * resulting revision in this same transaction. Preserve status, counters, repeats and all sibling
     * pending rows. Ready contains actual CURRENT message/pending DTOs, selected pending ID and count;
     * dispatch uses those actual values, not the preflight DTO or freshTimestamp if it was not applied.
     *
     * A committed Ready binds issuer/lease, requestId, key and raw freshTimestamp, even when unused.
     * Distinguish this atomic admission from old create/prepare requests durably; an old QUEUE capture
     * cannot masquerade as an admission that bumped. Changed arguments/operation are CONFLICT.
     * Exact replay returns AlreadyReady only for the original admission version's still-current
     * grant and original count; it never bumps again, renews a successor version or recaptures.
     * Invalidation/deletion returns INVALIDATED/MISSING; retirement returns STALE_LEASE. A completion
     * for the actual same work instead returns PreviouslyCompleted, never another Ready grant.
     * A new legitimate pending lifecycle or recreated message may use a NEW admission request; it
     * cannot inherit prior acceptance or replay an old request into replacement authority.
     *
     * Storage failures/cancellation propagate unchanged; throws do not prove rollback. Reconcile only
     * the original request. Ready is storage eligibility, NOT proof of definitely unsent; missing
     * completion is never physical negative evidence. Known acceptance requires retaining the exact
     * original attempt/prewire completion UUID/mode/timestamp through storage/index/cleanup faults,
     * not another admission, bump, failure fallback or transport invocation.
     */
    suspend fun admitQueuedChannelSend(
        lease: DirectAckLease,
        requestId: UUID,
        key: EntityKey,
        freshTimestamp: UInt,
    ): ChannelQueueAdmissionResult

    /**
     * IMMEDIATELY after a genuine successful queued-channel transport return, before any suspension,
     * cancellation/ownership check or completion persistence, retain the original opaque attempt,
     * preselected completionRequestId, actual mode and actual invocation timestamp in the process
     * owner. Non-suspending and no database/network IO, async launch, status write or grant renewal.
     * This records caller-observed acceptance only, not a durable journal or permission to mutate.
     *
     * Authenticate the issuing owner and immutable capture metadata held in the opaque handle,
     * including actual message incarnation, stable pending lifecycle, pending relationship and
     * captured timestamp/mode. No later row lookup reconstructs them. Foreign/closed issuer is
     * INVALID_HANDLE; absent captured pending/birth identity or incompatible mode/timestamp is
     * NOT_ELIGIBLE. A genuinely issued handle may now have a revoked lease or invalidated revision:
     * still retain its ORIGINAL acceptance for suppression, but never renew any mutation authority.
     *
     * Registered/AlreadyRegistered return identity-only observations. Exact tuple replay is
     * idempotent; changed completion ID/mode/timestamp for that capture is CONFLICT. Other captures'
     * reports must not overwrite/erase an existing same-work fence. Serialize registration and
     * admission's fence check through the same process owner. Retain unresolved fences across
     * service claims.clear/revoke; no TTL, capacity eviction or ID-cache replacement may clear them.
     * Prune only when exact durable completion supersedes suppression or actual identity retirement
     * is proved, or at process-store disposal after runtime shutdown. Mere absence/read failure does
     * not prove retirement. A rejected note after known acceptance is explicit unavailability: park
     * and report it, never infer unsent, dispatch again or broadly clean pending rows.
     */
    fun noteAcceptedChannelAttempt(
        attempt: ChannelFailureAttempt,
        completionRequestId: UUID,
        mode: ChannelSendCompletionMode,
        acceptedTimestamp: UInt,
    ): ChannelQueueAcceptanceResult
}
