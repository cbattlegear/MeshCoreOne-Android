// AndroidOnly: WP-201 Read-only historical channel origin and exact creation reconciliation.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageDTO
import java.time.Instant

/** What this read transaction proves about the ORIGINAL stored message, not occupant eligibility. */
enum class ChannelMessageStoredState {
    /** Original incarnation and full relevant origin/envelope digest match the actual stored DTO. */
    ORIGINAL,
    /** The original message is absent; this does not erase its committed creation. */
    MISSING,
    /** Replacement incarnation, invalidated origin or changed envelope fails original binding. */
    INVALIDATED,
    /** Available evidence cannot establish original-row binding; never fabricate a payload. */
    UNVERIFIED,
}

/** Original occupant/radio authority observation, independent of stored payload and session lease. */
enum class ChannelMessageOriginCurrentness {
    /** Persistent occupant epoch and resolved radio authority match the original stored scope. */
    CURRENT,
    /** Persistent evidence proves that original occupant authority was retired/replaced. */
    RETIRED,
    /** No adequate epoch/resolved-radio proof, including an unresolved pre-SET marker. */
    UNVERIFIED,
}

/**
 * Persisted ORIGINAL scope plus two independent observations; never a reconstructed current slot.
 * canonicalMessage is nonnull ONLY for storedState ORIGINAL after same-transaction incarnation and
 * full relevant origin/envelope digest verification. It is the actual row, not a stored old payload,
 * intended DTO, replacement or reconstruction from caller text. All other stored states require null.
 * Original payload may remain readable when originCurrentness is RETIRED/UNVERIFIED; that is read-only
 * history, not a usable SEND envelope. Even CURRENT grants no dispatch/mutation/cleanup permission.
 */
data class ChannelMessageInspectionReceipt(
    val key: EntityKey,
    val origin: ChannelHistoryScope,
    val storedState: ChannelMessageStoredState,
    val originCurrentness: ChannelMessageOriginCurrentness,
    val canonicalMessage: MessageDTO?,
)

/**
 * Historical committed CREATE proof for this EXACT caller-known tuple. Full immutable digest
 * comparison proves creation/timestamp/createdAt; echoing these matched arguments does not invent a
 * persisted payload snapshot. createdAt retains seconds AND nanoseconds. observation may be noncurrent
 * or have no canonical payload without losing the exact creation commit. No handle is issued.
 */
data class ChannelMessageCreationInspectionReceipt(
    val creation: ChannelMessageCreation,
    val timestamp: UInt,
    val createdAt: Instant,
    val observation: ChannelMessageInspectionReceipt,
)

sealed interface ChannelMessageOriginObservation {
    data class Found(val receipt: ChannelMessageInspectionReceipt) : ChannelMessageOriginObservation

    /** No original scoped origin record observed; not proof no message/CREATE exists or no send. */
    data class NotRecorded(val key: EntityKey) : ChannelMessageOriginObservation
    data class Rejected(val reason: DirectAckRejection) : ChannelMessageOriginObservation
}

sealed interface ChannelMessageCreationObservation {
    /** Exact historical commit, independent of active lease, current row or current occupant. */
    data class Committed(val receipt: ChannelMessageCreationInspectionReceipt) : ChannelMessageCreationObservation

    /**
     * A committed CREATE tombstone EXISTS for this stable key, but the requested immutable intent
     * does not match it. Do not label caller arguments as the committed intent. original is a safe
     * observation of the persisted original scope if available; otherwise null, not a guessed scope
     * or recreated old text/time from a one-way digest. This still proves a DIFFERENT creation exists.
     */
    data class Conflict(
        val key: EntityKey,
        val original: ChannelMessageInspectionReceipt?,
    ) : ChannelMessageCreationObservation

    /** No matching committed CREATE observed in this read; NOT proof of rollback or safe INSERT. */
    data class NotRecorded(val key: EntityKey) : ChannelMessageCreationObservation
    data class Rejected(val reason: DirectAckRejection) : ChannelMessageCreationObservation
}

/**
 * Separate optional READ-ONLY role. No extension/default implementation or change to frozen create,
 * failure, queue, completion or generic Boolean contracts. Implemented by the actual storage owner.
 * Missing capability surfaces unavailability, never a create/replay/readback-as-grant fallback.
 *
 * Neither query takes a lease: immutable key/creation supplies radio, and historical observation
 * requires neither an old opaque issuer object nor activation of a new connection. Reads work after
 * cancellation, lease revocation, session replacement and a newly opened storage context. They never
 * INSERT/upsert, activate/revoke, issue/reconstruct a handle, renew a version, bump attempts, clear
 * uncertainty, retire identity, modify user history or resolve a radio marker. Storage errors and
 * cancellation propagate unchanged. A thrown create/read is UNKNOWN, not implicit rollback/absence.
 *
 * Currentness is NOT UUID/slot equality. CURRENT requires actual persistent original occupant epoch
 * and resolved radio authority; absent shared durable proof, including the UUID-only producer base,
 * return UNVERIFIED while retaining known historical origin/commit. Unresolved radio state is not
 * cleared by observation or deletion/recreation of a message/pending row. Same-key authoritative
 * rename may preserve identity; replacement/clear/recreation/ABA/restore cannot inherit its proof.
 * Those mutation/gate/retention surfaces remain separately owned dependencies, not supplied here.
 *
 * Consumers retain original text/identity and park when origin is absent, unverified or retired;
 * never rebind to current occupant B from original A. A readable historical DTO is not permission
 * to send. Even CURRENT is only a transaction observation: actual dispatch still requires the
 * separately shared durable gate and exact live connection/token checks at the radio boundary.
 */
interface ChannelMessageInspectionAuthority {
    /**
     * Read persisted ORIGINAL origin by radio/message key, not the current channel slot. Found
     * survives original message deletion/replacement when its origin tombstone is retained, with
     * explicit storedState and null unsafe payload. Same UUID on a recreated message is a different
     * incarnation; equality of text/timestamps/DTO fields cannot certify the original.
     *
     * Expose canonicalMessage only after the actual original incarnation, stored origin and full
     * relevant envelope digest match in the same read transaction. Noncurrent authority alone does
     * not erase safely readable original history. Missing origin, including legacy/raw unbound rows,
     * returns NotRecorded without fabricating a scope from the current occupant or caller defaults.
     */
    suspend fun inspectChannelMessageOrigin(key: EntityKey): ChannelMessageOriginObservation

    /**
     * Look up committed CREATE by creation.origin.radioId + stable creation.messageID and compare the
     * FULL persisted immutable creation digest: original scope/radio/index/occupant, message ID, exact
     * text and textType, UInt Unix-seconds timestamp, Instant createdAt seconds+nanos and the existing
     * canonical creation defaults. Do not compare only ID, origin, current row or rounded timestamp.
     *
     * A matching CREATE tombstone establishes Committed even when its lease is stale/foreign to this
     * reopened reader, the original message has disappeared, origin was invalidated or current
     * occupant is different/unresolved. Safely observed payload/currentness is separate, never a
     * prerequisite that discards known exact commit. If original origin metadata is missing, matched
     * full-digest scope may identify the historical tuple but cannot certify a live row: report
     * UNVERIFIED/null payload, not a replacement or new INSERT.
     *
     * Existing CREATE with different immutable arguments returns Conflict, preserving the fact that
     * another intent already committed at this key. A non-CREATE collision or malformed intent is an
     * explicit Rejected(CONFLICT/NOT_ELIGIBLE), not a counterfeit historical receipt. Invalid storage
     * evidence is surfaced as the repository-standard error, not NotRecorded or false success.
     *
     * NotRecorded means only no committed matching CREATE observed in this transaction. It neither
     * certifies a still-unsettled writer rolled back nor authorizes blind recreation, new request ID,
     * resend or deletion. The caller retains the SAME original intent/time identity across uncertain
     * returns; mutation/replay, if separately admitted, must reconcile under the actual producer.
     */
    suspend fun inspectChannelMessageCreation(
        creation: ChannelMessageCreation,
        timestamp: UInt,
        createdAt: Instant,
    ): ChannelMessageCreationObservation
}
