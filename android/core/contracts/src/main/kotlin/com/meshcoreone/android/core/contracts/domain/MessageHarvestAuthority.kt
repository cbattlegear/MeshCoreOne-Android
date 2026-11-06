// AndroidOnly: WP-201 Save-issued row lifetime and independently authenticated incoming RX enrichment.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageRepeatDTO
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.SnapshotList

/** Marker only. Storage accepts ONLY its own private original-save incarnation/revision receipt. */
interface SavedMessageRowReceipt

data class SavedMessageRow(
    val message: MessageDTO,
    val receipt: SavedMessageRowReceipt,
    val reactionCapture: CapturedChannelReactionTarget? = null,
) {
    init {
        require(reactionCapture == null || reactionCapture.message === message)
    }
}

/**
 * Deliberately excludes pre-save content-correlated RX enrichment. Defer those fields to harvest,
 * preserving independently received wire pathLength/SNR. Never silently strip enriched input.
 */
data class ChannelMessageRowSaveRequest(
    val message: MessageDTO,
    val occurrence: ChannelReactionOccurrence,
) {
    init {
        require(message.direction == MessageDirection.INCOMING && message.contactID == null)
        require(message.radioId == occurrence.envelope.session.radioId)
        require(message.channelIndex == occurrence.envelope.message.channelIndex)
        require(message.pathLength == occurrence.envelope.message.pathLength)
        require(message.snr == occurrence.envelope.message.snr)
        require(message.pathNodes == null && message.routeType == null && message.regionScope == null)
        require(message.regionScopeMatches.isEmpty())
    }
}

/** Original 67ec outcome plus storage-only row lineage, captured in the SAME original save TX. */
data class RowBoundChannelIngress(
    val original: ChannelReactionIngressResult,
    val savedRow: SavedMessageRow?,
) {
    init {
        when (original) {
            is ChannelReactionIngressResult.Rejected -> require(savedRow == null)
            is ChannelReactionIngressResult.Inserted -> {
                require(savedRow != null && savedRow.message === original.captured.message)
                require(savedRow.reactionCapture === original.captured)
            }
            is ChannelReactionIngressResult.InsertedUnproven -> {
                require(savedRow != null && savedRow.message === original.message)
                require(savedRow.reactionCapture == null)
            }
            is ChannelReactionIngressResult.Existing -> {
                require(savedRow != null && savedRow.message.id == original.key.id)
                require(savedRow.message.radioId == original.key.radioId && savedRow.reactionCapture == null)
            }
        }
    }
}

/** Each original RX candidate is independent; a row receipt/current key/hash is NOT its origin. */
sealed interface OriginalRxCandidate {
    val entry: RxLogEntryDTO

    data class Unproven(
        override val entry: RxLogEntryDTO,
        val reason: ChannelReactionUnprovenReason,
    ) : OriginalRxCandidate

    /**
     * No issuer exists. Future storage accepts only its own recognized private implementation,
     * authenticating this EXACT original RX raw occurrence/session/radio/index/wire and persisted
     * occupant, with an authoritative join to successful channel selection. Public implementations,
     * copied/equal entry/UUID/hash or today's decoder key never provide that association.
     */
    interface Proven : OriginalRxCandidate
}

data class IncomingPathHarvestRequest(
    val savedRow: SavedMessageRow,
    val candidates: SnapshotList<OriginalRxCandidate>,
) {
    init {
        require(candidates.zipWithNext().all { (left, right) ->
            left.entry.receivedAt <= right.entry.receivedAt
        }) { "RX candidates must retain stable receivedAt order; equal dates retain input order" }
    }
}

sealed interface IncomingPathHarvestIssueReason {
    data object Stale : IncomingPathHarvestIssueReason
    data class Unproven(val reason: ChannelReactionUnprovenReason) : IncomingPathHarvestIssueReason
    data object Unresolved : IncomingPathHarvestIssueReason
    data class Rejected(val reason: ChannelReactionRejection) : IncomingPathHarvestIssueReason
}

/** Null ordinal means the row receipt/original batch is unavailable or invalid. */
data class IncomingPathHarvestIssue(
    val ordinal: Int?,
    val reason: IncomingPathHarvestIssueReason,
)

/** One actual inserted repeat and its checked cumulative count, for post-commit event delivery. */
data class IncomingPathHarvestRepeat(
    val repeat: MessageRepeatDTO,
    val heardRepeatsAfter: Long,
)

sealed interface IncomingPathHarvestResult {
    val request: IncomingPathHarvestRequest

    data class Completed(
        override val request: IncomingPathHarvestRequest,
        val successor: SavedMessageRow,
        val adoptedCandidateOrdinal: Int?,
        val insertedRepeats: SnapshotList<IncomingPathHarvestRepeat>,
    ) : IncomingPathHarvestResult {
        init {
            require(successor.message.id == request.savedRow.message.id)
            require(successor.message.radioId == request.savedRow.message.radioId)
            require(adoptedCandidateOrdinal == null || adoptedCandidateOrdinal in request.candidates.indices)
            require(insertedRepeats.all { it.repeat.messageID == successor.message.id })
        }
    }

    /** Entire batch is retained; no adoption, repeat/count or other message writes occurred. */
    data class Blocked(
        override val request: IncomingPathHarvestRequest,
        val issues: SnapshotList<IncomingPathHarvestIssue>,
    ) : IncomingPathHarvestResult {
        init {
            require(issues.isNotEmpty())
            require(issues.all { it.ordinal == null || it.ordinal in request.candidates.indices })
            require(issues.mapNotNull { it.ordinal }.let { it == it.distinct().sorted() })
        }
    }
}

/**
 * Optional CHANNEL-INCOMING role, separate from generic Boolean/DM/sent-echo persistence.
 * No defaults, receipt recapture by ID or caller-provided issuer implementations.
 *
 * Original save preserves all 67ec Inserted/InsertedUnproven/Existing/Rejected distinctions.
 * Authenticate DTO derivation under original decoding/time-correction rules. RX-derived enrichment
 * is deferred: reject already enriched input before DML, never certify fields copied by a current-key
 * lookup. Save actual row and privately bind exact stored incarnation/reaction-identity revision/
 * original save in SAME TX; return actual DTO/receipt, including committed Unproven and Existing.
 * Existing gets only the new upsert successor's storage lineage: NOT original insert, same-origin
 * duplicate proof, original occurrence recovery or reaction target. Never save then read to mint.
 * Row receipt alone does NOT authenticate receive origin or grant reaction/index authority.
 *
 * Harvest: in ONE WRITE TX first authenticate receipt/actual original row incarnation and revision,
 * then EVERY exact RX candidate's independent original proof, message/occupant/session association,
 * GROUP_TEXT/SUCCESS decoded sender/body/slot/original reactionTimestamp (senderTimestamp when
 * corrected, NOT corrected stored timestamp) and canonical-equivalent dedup key.
 * Current storage has no positive RX issuer: Unproven and every unrecognized public Proven marker
 * cause explicit Blocked with ZERO DML, not a miss, skipped candidate or a fabricated positive path.
 * Unknown/stale/foreign-issued evidence blocks the WHOLE batch; no partial apply. Empty input may
 * complete only after genuine row validation and same-TX actual snapshot; this claims no RX proof.
 * Earlier legitimate logs are not rejected merely for preceding the message save.
 *
 * With future genuine proof, retain stable receivedAt order and first equal. Null path adopts the
 * earliest matching path with no repeat/count; atomically adopt THAT candidate's pathNodes,
 * RX pathLength, routeType, regionScope and regionScopeMatches while retaining wire message SNR.
 * No candidate leaves wire pathLength/SNR and null/unset route/region unchanged.
 * Empty path is KNOWN, not null. Further canonical-equal
 * paths are no-ops; additional paths dedup by radio+RX ID globally AND this message's pathNodes.
 * Each genuinely new distinct path atomically inserts its MessageRepeatDTO and checked count
 * increment; preserve receivedAt/path length/nodes/SNR/RSSI/RX ID. Return actual final DTO, row
 * successor, inserted repeat/count facts and adoption ordinal in the SAME TX, no follow-up ID read.
 * Do not turn incoming rules into sent-echo dedup policy. Route/region derive from that SAME earliest
 * independently authenticated candidate, never from a separate current-key content lookup.
 *
 * Own admitted path/repeat/count writes must preserve any existing authentic reaction-target benign
 * lineage OR return its explicit storage-authenticated SAME-TX successor in successor.reactionCapture.
 * Never lose a valid capture silently, remint from DTO equality, or index an Unproven/Existing row.
 * No unrelated field mutation, reaction origin issuance, pending cleanup or status authority.
 *
 * Return the SAME harvest request. Public result constructors are shapes, not authority. Post-commit
 * events use only actual returned facts. Exceptions/cancellation propagate and can follow commit:
 * caller retains exact save/harvest request, original receipt and cause as UNKNOWN/non-redrivable
 * custody, not rollback or retry. A known committed original save with failed later harvest remains
 * known committed delivery-incomplete, never relabeled save-UNKNOWN. No receipt recovery journal,
 * automatic replay or safe-redrive promise. Existing generic/OFF behavior remains unchanged.
 */
interface MessageHarvestAuthority {
    suspend fun saveIncomingChannelMessageWithRowReceipt(
        request: ChannelMessageRowSaveRequest,
    ): RowBoundChannelIngress

    suspend fun harvestIncomingPaths(
        request: IncomingPathHarvestRequest,
    ): IncomingPathHarvestResult
}
