// AndroidOnly: WP-201 Original-selection channel reaction targets and atomic incarnation-bound mutation.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import java.time.Instant

/**
 * Storage-issued binding to an original selected message incarnation AND expected mutation revision.
 * Only the issuing producer's private implementation is accepted. Implementing this interface,
 * reconstructing public fields or possessing an equal DTO/key does not create authority.
 */
interface ChannelReactionTarget

/**
 * Actual row and target captured together in one storage transaction, not a caller's old DTO.
 * The snapshot is matching/display metadata, not a replacement for the opaque target. It may become
 * stale immediately after return. It certifies neither a current radio occupant nor dispatch.
 */
data class CapturedChannelReactionTarget(
    val target: ChannelReactionTarget,
    val message: MessageDTO,
)

/** Same anchored history-window result/order as MessageWindow, with original transaction bindings. */
data class ChannelReactionWindow(
    val messages: SnapshotList<CapturedChannelReactionTarget>,
    val hasMore: Boolean,
)

sealed interface ChannelReactionIngressResult {
    data class Inserted(val captured: CapturedChannelReactionTarget) : ChannelReactionIngressResult
    /** Existing-row save committed, not a new insertion or reaction-target issuance. */
    data class Existing(val key: EntityKey) : ChannelReactionIngressResult
    data class Rejected(val reason: DirectAckRejection) : ChannelReactionIngressResult
}

sealed interface ChannelReactionTargetCaptureResult {
    data class Captured(val captured: CapturedChannelReactionTarget) : ChannelReactionTargetCaptureResult
    data object Stale : ChannelReactionTargetCaptureResult
    data class Rejected(val reason: DirectAckRejection) : ChannelReactionTargetCaptureResult
}

sealed interface ChannelReactionMutationResult {
    /**
     * The reaction and summary transaction committed against the exact predecessor.
     * successor contains the actual post-write row and expected revision; retire the predecessor.
     * inserted=false means sender/emoji was already present, not a new-reaction event/log.
     * summary equals successor.message.reactionSummary, including the legitimate empty summary.
     *
     * completionSuccessor is issued ONLY for a completion-derived target whose exact original
     * completion lineage was validated and advanced in this same transaction. A candidate-query
     * target never grants pending cleanup or acquires another completion's lineage.
     */
    data class Applied(
        val successor: CapturedChannelReactionTarget,
        val summary: String,
        val inserted: Boolean,
        val completionSuccessor: ChannelSendCompletion?,
    ) : ChannelReactionMutationResult

    /** Missing, replaced or revision-invalidated original; no reaction, summary or grant write. */
    data object Stale : ChannelReactionMutationResult
    data class Rejected(val reason: DirectAckRejection) : ChannelReactionMutationResult
}

sealed interface CompletedChannelReactionIndexResult {
    /**
     * Actual bound cache insertion and ordered pending replay returned successfully. completion
     * remains the original completion or its storage-issued own-write successor, never a new send.
     * This is not a storage journal or an exactly-once indexing certificate.
     */
    data class Indexed(
        val target: CapturedChannelReactionTarget,
        val completion: ChannelSendCompletion,
    ) : CompletedChannelReactionIndexResult

    /** Retain/retire original work explicitly; neither cleanup nor redispatch is authorized. */
    data object Stale : CompletedChannelReactionIndexResult
    data class Rejected(val reason: DirectAckRejection) : CompletedChannelReactionIndexResult
}

/**
 * Separate optional role: no defaults, changes to generic ReactionPersisting, DM migration or
 * reconstruction from an ID-only read. Absence is explicit unavailability, not generic fallback.
 *
 * A target records the existing durable message incarnation and expected revision, plus the
 * original selected envelope and recorded channel origin when present. Compare original stored
 * origin/incarnation/envelope, never substitute today's channel slot/occupant. Retained history
 * does not require active slot equality. Legacy rows lacking origin evidence remain unproved as
 * to occupant; their actual selected row is not a fabricated original-occupant certificate.
 * This role neither clears unresolved radio authority nor issues a dispatch/receive-origin grant.
 *
 * Same-key upsert/update, delete/recreate, restore, revision change or another issuer cannot be
 * repaired by a later key lookup, DTO equality or unconditional regrant. Even equal-value writes
 * invalidate expected revisions. Only exact predecessor-validated own writes may issue successors.
 *
 * Unbound incoming save results, history windows and old cache entries cannot issue targets.
 * Only a genuinely never-targeted wire lookup may use the real matching/candidate query below.
 * An already indexed/cached/trigger-selected message cannot be upgraded by later ID, DTO, hash,
 * text, timestamp or ranking-hint equality: an identical replacement passes all those checks.
 * Protected ingress indexing requires the original-save bound capture below and propagation
 * through own reception mutations, supplied by a separately admitted producer prerequisite.
 * History must use the original bound window read and preserve its binding through preparation.
 * These generic candidate queries are NOT equivalent to indexed/pending replay: stored timestamp
 * differs from reactionTimestamp for corrected rows, and createdAt/limit differs from indexedAt.
 * Preserve original cache collision/arrival ordering, not a truncated current-clock fallback.
 * Once selected, pending work retains its target across failures/requeue. A stale bound target
 * never becomes unbound and never falls back to replacement selection.
 *
 * Storage errors and cancellation propagate, including possible commit-before-throw. Without an
 * exact persisted operation receipt, a lost successor is UNKNOWN, not rollback or safe replay.
 * Retain original pending/acceptance identities; do not reselect, blindly retry insertion, claim
 * success, delete pending work or physically resend to recover an uncertain result.
 */
interface ChannelReactionTargetAuthority {
    /**
     * ORIGINAL incoming channel save: preserve existing saveMessage upsert semantics atomically.
     * Require incoming direction, channelIndex and no contactID. If the key was genuinely absent
     * before this operation's insert, return its actual post-insert row/binding. If it existed,
     * perform the existing save and return Existing with NO reaction target or claim this operation
     * originally inserted it. That generic existing-row write still invalidates old target authority.
     * Existing is not an index/new-insertion success, but does not falsely report save rollback.
     * Do not substitute this for an existing generic save unless its consumer is actually adapted.
     *
     * No request ledger or recovered insertion receipt is promised. A commit followed by a thrown
     * return is UNKNOWN; retry cannot turn an Existing row into a target. Subsequent harvest/read/
     * status operations need admitted exact predecessor lineage or a reviewed durable identity
     * revision which preserves only proved-benign changes; generic equality cannot refresh it.
     */
    suspend fun saveIncomingChannelMessageWithReactionTarget(
        message: MessageDTO,
    ): ChannelReactionIngressResult

    /**
     * Read and validate the ORIGINAL issued completion's current issuer/lease/capture/message/
     * optional pending incarnation and revision in one transaction. Issue a target bound to that
     * exact completion lineage and actual output row; never use a key, receipt or historical
     * AlreadyApplied(null) to renew it. A stale completion returns Stale, with no row or grant.
     */
    suspend fun captureChannelReactionTarget(
        completion: ChannelSendCompletion,
    ): ChannelReactionTargetCaptureResult

    /**
     * Original native sender/hash/window selection, with row + durable identity read together.
     * Preserve existing candidate ordering, bounds, local outgoing sender and hash semantics.
     * null means actual no match in this transaction, never a swallowed error or stale-token retry.
     * timestampWindow is the existing stored-time query bound, not inferred cache/wire provenance.
     * This is not a cache/pending replay selector or original-save/history-window capture API.
     */
    suspend fun findChannelReactionTarget(
        radioId: RadioId,
        channelIndex: UByte,
        parsedReaction: ParsedReaction,
        localNodeName: String?,
        timestampWindow: ClosedRange<UInt>,
        limit: Long,
    ): CapturedChannelReactionTarget?

    /**
     * Original ordered/windowed candidate selection for native/MCO v1/v3 consumers. Every actual
     * snapshot and target comes from the same read transaction. Consumers perform their existing
     * format-specific matching against THESE snapshots, not DTOs fetched before capture. Preserve
     * the existing bounded DB fallback semantics; do not use this as old/corrected/unbounded indexed
     * pending replay or replace most-recently-indexed collision selection with createdAt ordering.
     */
    suspend fun fetchChannelReactionTargets(
        radioId: RadioId,
        channelIndex: UByte,
        timestampWindow: ClosedRange<UInt>,
        limit: Long,
    ): SnapshotList<CapturedChannelReactionTarget>

    /**
     * ORIGINAL history-window read, replacing the unbound fetch at its source, not a second capture
     * by displayed IDs. Preserve existing fetchMessages filter/order/limit/offset. Return each actual
     * row with its incarnation/revision target from the same read transaction. No indexing, reaction
     * persistence, queue drain or notification occurs here: neutral invalidation may call this read
     * without preparation. Consumers must carry the original binding through their preparation hook,
     * not recover it by DTO equality, list position after replacement or a later key query.
     */
    suspend fun fetchChannelReactionWindow(
        radioId: RadioId,
        channelIndex: UByte,
        limit: Long,
        offset: Long,
    ): SnapshotList<CapturedChannelReactionTarget>

    /**
     * ORIGINAL anchored history refresh, preserving fetchMessageWindow's anchor/floor behavior,
     * full loaded range, checked bounds, display ordering and hasMore. Do not truncate to the
     * reaction fallback's 200 rows or reinterpret anchorSortDate as a wire timestamp. Like the
     * offset read, capture actual rows+targets together and perform no replay/indexing side effects.
     */
    suspend fun fetchChannelReactionWindow(
        radioId: RadioId,
        channelIndex: UByte,
        anchorSortDate: Instant?,
        floorLimit: Long,
    ): ChannelReactionWindow

    /**
     * ONE write transaction validates issuer, exact message incarnation/revision, selected envelope
     * and original recorded origin. reaction radio/message/channel must match and contactID be null.
     * Validate before any reaction write; Stale/Rejected leaves all state untouched.
     *
     * In that SAME transaction: deduplicate by existing sender/emoji semantics; insert only if new;
     * fetch the actual newest 100 reactions in existing order; build the canonical-equivalence/
     * count/earliest-time summary using the existing policy; update only this bound message;
     * issue the exact post-write successor. No ID-only summary callback or cross-transaction check.
     * No partial insert/summary on rollback and no unconditional REPLACE of a duplicate reaction.
     *
     * For a completion-derived target, prevalidate its exact still-current completion and pending
     * lineage too. Only this own summary write may conditionally advance that lineage and return
     * its completionSuccessor atomically, preserving capture/completion/cleanup request identities.
     * No generic-writer exemption, lease renewal, acceptance/count/repeat rewrite, pending deletion
     * or revival of an independently invalidated completion is allowed. Other targets return null
     * completionSuccessor. The producer must supply real successor support, not fabricate a handle.
     */
    suspend fun applyChannelReaction(
        target: ChannelReactionTarget,
        reaction: ReactionDTO,
    ): ChannelReactionMutationResult
}

/**
 * Consumer bridge, independently implemented by the reaction/cache owner. The original completion
 * is mandatory; the legacy ID-only indexer cannot satisfy this role.
 *
 * Validate the radio before taking pending work. Cache the exact captured target with its actual
 * envelope; unbound legacy entries cannot overwrite bound entries by UUID. Replay pending in order,
 * consuming each Applied successor for that exact predecessor only. Advance/evict cache entries
 * only by predecessor identity, preserving indexed-at/LRU order rather than inventing a new arrival.
 * Never substitute another already-cached same-key binding after waiting for serialization.
 *
 * Requeue unfinished original pending entries on stale/error/cancellation with their receivedAt,
 * queue order and chosen binding intact. Do not lose drained batches, clear a stale binding or
 * report indexed/new-reaction success for unavailable/stale/rejected work. Reaction replay failure
 * must not suppress ordinary delivery/notification of an already saved inbound message; surface the
 * error and retain pending work, while cancellation still propagates. Consumer tests must prove
 * this behavior; the interface itself supplies no cache, queue or concurrency implementation.
 */
fun interface CompletedChannelReactionIndexer {
    suspend fun indexCompletedChannel(
        completion: ChannelSendCompletion,
        localNodeName: String,
    ): CompletedChannelReactionIndexResult
}
