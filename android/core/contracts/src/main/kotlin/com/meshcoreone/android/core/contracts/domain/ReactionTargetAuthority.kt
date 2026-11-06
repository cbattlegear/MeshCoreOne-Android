// AndroidOnly: WP-201 Independent original-selection channel reaction contract.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.SnapshotList
import java.time.Instant

/** Storage-issued original message/occupant incarnation and reaction-identity revision binding. */
interface ChannelReactionTarget

/**
 * Original receive occurrence, not a radio/slot lookup. There is NO live Proven issuer or late
 * capture API here. Current ChannelReceiveEnvelope producers can supply only Unproven.
 */
sealed interface ChannelReactionOccurrence {
    val envelope: ChannelReceiveEnvelope

    data class Unproven(
        override val envelope: ChannelReceiveEnvelope,
        val reason: ChannelReactionUnprovenReason,
    ) : ChannelReactionOccurrence

    /**
     * Only a private storage-authenticated issuer implementation is accepted. Public implementations,
     * envelope.origin, object/UUID equality and copied envelopes are not authority.
     * Future issuance must bind this EXACT original event/session/radio/index/wire and durable
     * occupant incarnation at an ingress boundary ordered against ALL participating occupant changes.
     * Poller dequeue, current-slot reads and a process event owner do not establish that boundary.
     * Distinct message/reaction occurrences can share an origin, never substitute for each other.
     */
    interface Proven : ChannelReactionOccurrence
}

enum class ChannelReactionUnprovenReason {
    UNORDERED_INGRESS, MISSING_ORIGIN, LEGACY_OR_RESTORED, INVALID_PROOF, UNRESOLVED_ORIGIN,
}

/** Every selected history row remains visible, including rows which cannot carry reaction authority. */
sealed interface ChannelReactionHistoryEntry {
    val message: MessageDTO
}

/** Actual row and opaque binding from the SAME original save or selection transaction. */
data class CapturedChannelReactionTarget(
    val target: ChannelReactionTarget,
    override val message: MessageDTO,
) : ChannelReactionHistoryEntry

data class UnprovenChannelReactionMessage(
    override val message: MessageDTO,
    val reason: ChannelReactionUnprovenReason,
) : ChannelReactionHistoryEntry

/** Original anchored history selection/order, with no replay or indexing side effects. */
data class ChannelReactionWindow(
    val messages: SnapshotList<ChannelReactionHistoryEntry>,
    val hasMore: Boolean,
)

/** Domain-local rejection, independent of send/ACK/session authority. */
enum class ChannelReactionRejection { INVALID_HANDLE, NOT_ELIGIBLE, CONFLICT }

sealed interface ChannelReactionIngressResult {
    data class Inserted(val captured: CapturedChannelReactionTarget) : ChannelReactionIngressResult
    /** Original insert committed; continue normal harvest/delivery/notification, but NEVER index it. */
    data class InsertedUnproven(
        val message: MessageDTO,
        val reason: ChannelReactionUnprovenReason,
    ) : ChannelReactionIngressResult
    /** Existing-row upsert committed, but no original insertion or reaction authority is certified. */
    data class Existing(val key: EntityKey) : ChannelReactionIngressResult
    data class Rejected(val reason: ChannelReactionRejection) : ChannelReactionIngressResult
}

sealed interface ChannelReactionSelectionResult<out T> {
    data class Selected<T>(val value: T) : ChannelReactionSelectionResult<T>
    /** Authenticated occurrence, actual original query completed, no same-origin eligible match. */
    data object NoMatch : ChannelReactionSelectionResult<Nothing>
    data class Unproven(
        val reason: ChannelReactionUnprovenReason,
    ) : ChannelReactionSelectionResult<Nothing>
    data object Stale : ChannelReactionSelectionResult<Nothing>
    data class Rejected(
        val reason: ChannelReactionRejection,
    ) : ChannelReactionSelectionResult<Nothing>
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
    data class Unproven(val reason: ChannelReactionUnprovenReason) : ChannelReactionApplyResult
    data class Rejected(val reason: ChannelReactionRejection) : ChannelReactionApplyResult
}

/**
 * Independent optional reaction-only role. No defaults, generic persistence replacement, DM
 * migration, or mutation authority inferred from a public DTO/key.
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
 * Historical A origin survives live slot retirement to B; same-original-A reaction may still apply.
 * B-origin reaction cannot match A. Slot lifecycle changes fence FUTURE issuance, not erase A history.
 * Each event's own session binding is authenticated; separate message/reaction occurrences need
 * not share an occurrence UUID or session generation if their original occupant provenance matches.
 * Missing/legacy/restored/unresolved original provenance stays visible without a target. Never stamp
 * today's occupant on migrated history, incoming MessageDTOs, or a reaction selected by hash.
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
 * EACH pending/replay reaction retains its own original occurrence as well as target, including
 * native batches and MCO v1/v3. Unproven reaction wire enters explicit bounded non-redrivable custody,
 * not ordinary chat, native matching or later re-selection; it must not be silently dropped.
 *
 * Storage exceptions and cancellation propagate, including possible commit-before-throw. There is
 * no operation journal or successor-recovery promise. An uncertain result is not rollback, safe
 * retry/reselection or permission to discard original pending work. Stale is not success.
 */
interface ChannelReactionTargetAuthority {
    /**
     * ORIGINAL incoming channel save, preserving existing saveMessage upsert semantics in one TX.
     * Require incoming direction, channelIndex and no contactID. Bind this DTO to the original
     * occurrence's actual wire/session/radio/index under the original decoding/correction rules.
     * Issue Inserted(actual row+target) only if genuinely absent AND its original occurrence has
     * authenticated ordered-ingress proof; atomically persist message AND its exact original origin.
     * No MessageDTO, occurrence UUID, envelope.origin or current-slot lookup can mint that proof.
     * Otherwise preserve the ordinary insert and return InsertedUnproven(actual row, reason), with
     * no target/index authority. This is a committed save, NOT a failed-save log or dropped delivery.
     * An existing row is saved with the original upsert behavior and returns Existing with NO new
     * target; old authority cannot survive replacement
     * merely because fields match. Existing means save committed: continue ordinary saved-message
     * delivery/harvest/notification behavior separately from reaction eligibility.
     *
     * Rejected malformed input writes nothing. Unproven/foreign origin alone does not reject normal
     * delivery: save without authority. No original provenance is refreshed on generic upsert.
     * A thrown return is UNKNOWN, not a recovered insertion receipt.
     * Later Existing cannot recreate a lost target. Own harvest mutations must preserve the admitted
     * reaction identity lineage; a late ID read is not a substitute for this original capture.
     */
    suspend fun saveIncomingChannelMessageWithReactionTarget(
        message: MessageDTO,
        occurrence: ChannelReactionOccurrence,
    ): ChannelReactionIngressResult

    /**
     * ORIGINAL native sender/hash/window selection. Read actual row+identity in the SAME read TX;
     * preserve existing sender, ordering, stored-time bounds and limit semantics. Authenticate the
     * REACTION occurrence itself and verify parsedReaction against its actual wire; derive radio/
     * channel from that occurrence. Select only messages with matching persisted ORIGINAL occupant
     * provenance, not today's slot. Unproven/Stale/Rejected are not NoMatch or permission to reselect.
     * A missing message origin never becomes a candidate. This is not old-cache/pending recapture.
     */
    suspend fun findChannelReactionTarget(
        occurrence: ChannelReactionOccurrence,
        parsedReaction: ParsedReaction,
        localNodeName: String?,
        timestampWindow: ClosedRange<UInt>,
        limit: Long,
    ): ChannelReactionSelectionResult<CapturedChannelReactionTarget>

    /**
     * ORIGINAL bounded DB fallback candidates, actual rows+bindings in one read TX. Native/MCO
     * consumers match THESE snapshots using existing format rules. Stored timestamp windows and
     * createdAt ordering/limit are not substitutes for corrected/old indexed pending targets or
     * most-recently-indexed collision semantics. Do not recapture DTOs selected before this query.
     * Authenticate the original REACTION occurrence and require same persisted origin for EVERY
     * candidate. NoMatch is an actual empty eligible query; unproven/stale/rejected is distinct.
     */
    suspend fun fetchChannelReactionTargets(
        occurrence: ChannelReactionOccurrence,
        timestampWindow: ClosedRange<UInt>,
        limit: Long,
    ): ChannelReactionSelectionResult<SnapshotList<CapturedChannelReactionTarget>>

    /**
     * ORIGINAL offset history read, preserving fetchMessages filter/display order/limit/offset.
     * Return EVERY original selected row: captured target only with authentic already-persisted
     * origin, otherwise UnprovenChannelReactionMessage with the same actual DTO. Never filter
     * unproven history or change order/offset/hasMore. Mint bindings in the same read TX, not from
     * today's slot or a second read of displayed IDs. No indexing, persistence, pending drain or
     * notification: neutral invalidation may call
     * this read without preparation. Carry original bindings through the preparation hook without
     * DTO equality, UUID recapture or positional substitution after replacement.
     */
    suspend fun fetchChannelReactionWindow(
        radioId: RadioId,
        channelIndex: UByte,
        limit: Long,
        offset: Long,
    ): SnapshotList<ChannelReactionHistoryEntry>

    /**
     * ORIGINAL anchored refresh: preserve fetchMessageWindow count-at/after anchor, max(floor,count),
     * checked extra-row/hasMore logic and display ordering, including more than 200 loaded rows.
     * The same original read captures bindings and has no indexing/replay side effects.
     * Preserve unproven rows exactly as in offset history; their presence contributes to hasMore.
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
     * be null. Authenticate the SAME retained original REACTION occurrence used for selection/replay,
     * validate ReactionDTO derivation from its actual wire and require exact message/reaction
     * original occupant origin equality. Message/reaction occurrence IDs need NOT be equal: they
     * are separate events. Neither public marker implementations nor current-slot comparison grant
     * authority. Stale/Unproven/Rejected write nothing. No pre-suspension-only check, replacement-row
     * grant, generic key-based summary callback or inferred benign-write regrant is sufficient.
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
        occurrence: ChannelReactionOccurrence,
    ): ChannelReactionApplyResult
}
