// AndroidOnly: WP-201 Independent original-selection channel reaction contract.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.SnapshotList
import java.time.Instant

/** Storage-issued original message/occupant incarnation and reaction-identity revision binding. */
interface ChannelReactionTarget

/** Actual row and opaque binding from the SAME original save or selection transaction. */
data class CapturedChannelReactionTarget(
    val target: ChannelReactionTarget,
    val message: MessageDTO,
)

/** Original anchored history selection/order, with no replay or indexing side effects. */
data class ChannelReactionWindow(
    val messages: SnapshotList<CapturedChannelReactionTarget>,
    val hasMore: Boolean,
)

/** Domain-local rejection, independent of send/ACK/session authority. */
enum class ChannelReactionRejection { INVALID_HANDLE, NOT_ELIGIBLE, CONFLICT }

sealed interface ChannelReactionIngressResult {
    data class Inserted(val captured: CapturedChannelReactionTarget) : ChannelReactionIngressResult
    /** Existing-row upsert committed, but no original insertion or reaction authority is certified. */
    data class Existing(val key: EntityKey) : ChannelReactionIngressResult
    data class Rejected(val reason: ChannelReactionRejection) : ChannelReactionIngressResult
}

sealed interface ChannelReactionApplyResult {
    /**
     * Atomic reaction/summary commit against the original target. successor is the actual post-write
     * snapshot and explicit binding. inserted=false is not a new-reaction event or saved log.
     * summary equals successor.message.reactionSummary; an empty summary is legitimate.
     * No status, dispatch, pending-cleanup or send-completion authority is issued here.
     */
    data class Applied(
        val successor: CapturedChannelReactionTarget,
        val summary: String,
        val inserted: Boolean,
    ) : ChannelReactionApplyResult

    /** Missing/replaced/identity-invalidated target; no reaction or summary writes. */
    data object Stale : ChannelReactionApplyResult
    data class Rejected(val reason: ChannelReactionRejection) : ChannelReactionApplyResult
}

/**
 * Independent optional reaction-only role. No defaults, generic persistence replacement, DM
 * migration, external issuer dependency or mutation authority inferred from a public DTO/key.
 * The implementation accepts only its own authenticated opaque target, not caller implementations.
 *
 * A separately admitted persisted identity must distinguish delete/reinsert/restore and same-key
 * replacement even when all public fields match. It must also distinguish reaction-relevant
 * channel/occupant/sender/text/reactionTimestamp/direction changes from proved-benign status/read/
 * path/heard-repeat/summary changes. Benign changes preserve safe lineage, not blanket exemptions
 * or refreshed authority obtained by key/equality. Actual identity/migration support is required;
 * this interface is not an alternate identity store or a claim that main already implements it.
 * Generic same-key save/upsert, including equal-value upsert, invalidates the old target. This is
 * distinct from admitted targeted benign writes. senderTimestamp participates in reaction identity
 * because reactionTimestamp uses it instead of a corrected stored timestamp.
 *
 * Recorded original occupant identity is not today's channel slot. Historical reactions may bind
 * safe original history without active-slot equality, but cannot remap to a replacement occupant,
 * infer unknown original provenance or claim current radio/dispatch authority.
 *
 * Cache/index-triggered replay MUST retain bindings from original save/history selection. Later
 * UUID/hash/text/timestamp/ranking-hint queries cannot recover an indexed original's incarnation.
 * Only a genuinely never-targeted wire lookup may begin a new candidate selection. A bound stale
 * pending/cache target never becomes unbound or falls back to another selection.
 *
 * Consumers preserve indexedAt/collision/LRU order and update or evict only the exact predecessor
 * binding. Requeue unfinished original pending entries with receivedAt, arrival ordinals and chosen
 * binding intact, including on cancellation. Ordinary replay failure is surfaced without suppressing
 * delivery/notifications for an already-saved incoming message. inserted=false/Stale/Rejected are
 * not new ReactionReceived/saved-log outcomes. Neutral refetch never performs reaction replay.
 *
 * Storage exceptions and cancellation propagate, including possible commit-before-throw. There is
 * no operation journal or successor-recovery promise. An uncertain result is not rollback, safe
 * retry/reselection or permission to discard original pending work. Stale is not success.
 */
interface ChannelReactionTargetAuthority {
    /**
     * ORIGINAL incoming channel save, preserving existing saveMessage upsert semantics in one TX.
     * Require incoming direction, channelIndex and no contactID. Issue Inserted(actual row+target)
     * only if genuinely absent before insertion. An existing row is saved with the original upsert
     * behavior and returns Existing with NO new target; old authority cannot survive replacement
     * merely because fields match. Existing means save committed: continue ordinary saved-message
     * delivery/harvest/notification behavior separately from reaction eligibility.
     *
     * Rejected writes nothing. A thrown return is UNKNOWN, not a recovered insertion receipt.
     * Later Existing cannot recreate a lost target. Own harvest mutations must preserve the admitted
     * reaction identity lineage; a late ID read is not a substitute for this original capture.
     */
    suspend fun saveIncomingChannelMessageWithReactionTarget(
        message: MessageDTO,
    ): ChannelReactionIngressResult

    /**
     * ORIGINAL native sender/hash/window selection. Read actual row+identity in the SAME read TX;
     * preserve existing sender, ordering, stored-time bounds and limit semantics. null is actual
     * no-match, not a swallowed error. This is not an old-cache/pending replay capture operation.
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
     * ORIGINAL bounded DB fallback candidates, actual rows+bindings in one read TX. Native/MCO
     * consumers match THESE snapshots using existing format rules. Stored timestamp windows and
     * createdAt ordering/limit are not substitutes for corrected/old indexed pending targets or
     * most-recently-indexed collision semantics. Do not recapture DTOs selected before this query.
     */
    suspend fun fetchChannelReactionTargets(
        radioId: RadioId,
        channelIndex: UByte,
        timestampWindow: ClosedRange<UInt>,
        limit: Long,
    ): SnapshotList<CapturedChannelReactionTarget>

    /**
     * ORIGINAL offset history read, preserving fetchMessages filter/display order/limit/offset.
     * Mint bindings with these actual rows in the same read TX, never a second read of displayed
     * IDs. No indexing, persistence, pending drain or notification: neutral invalidation may call
     * this read without preparation. Carry original bindings through the preparation hook without
     * DTO equality, UUID recapture or positional substitution after replacement.
     */
    suspend fun fetchChannelReactionWindow(
        radioId: RadioId,
        channelIndex: UByte,
        limit: Long,
        offset: Long,
    ): SnapshotList<CapturedChannelReactionTarget>

    /**
     * ORIGINAL anchored refresh: preserve fetchMessageWindow count-at/after anchor, max(floor,count),
     * checked extra-row/hasMore logic and display ordering, including more than 200 loaded rows.
     * The same original read captures bindings and has no indexing/replay side effects.
     */
    suspend fun fetchChannelReactionWindow(
        radioId: RadioId,
        channelIndex: UByte,
        anchorSortDate: Instant?,
        floorLimit: Long,
    ): ChannelReactionWindow

    /**
     * ONE write TX validates issuer, exact original persisted message/occupant incarnation and
     * reaction-identity revision/envelope. Reaction radio/message/channel must match; contactID must
     * be null. Stale/Rejected write nothing. No pre-suspension-only check, replacement-row grant,
     * generic key-based summary callback or inferred benign-write regrant is sufficient.
     *
     * In that SAME TX: dedup by existing sender/emoji semantics; insert only if new; fetch newest
     * 100 reactions in existing order; compute the canonical-equivalence/count/earliest-time summary
     * using existing policy; update this bound row only; return explicit successor and actual row.
     * Preserve every unrelated field and admitted benign lineage. No duplicate REPLACE, partial
     * insert/summary on rollback, send bookkeeping, pending deletion or independent grant renewal.
     */
    suspend fun applyChannelReaction(
        target: ChannelReactionTarget,
        reaction: ReactionDTO,
    ): ChannelReactionApplyResult
}
