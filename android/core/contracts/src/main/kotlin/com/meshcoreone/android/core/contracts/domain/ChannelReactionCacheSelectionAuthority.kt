// AndroidOnly: WP-201 Original occurrence-authenticated cached reaction selection, not an origin issuer.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.SnapshotList
import java.time.Instant
import java.util.UUID

sealed interface ChannelReactionCacheCandidate {
    val indexedAt: Instant
    val indexOrder: Long

    data class Bound(
        val captured: CapturedChannelReactionTarget,
        override val indexedAt: Instant,
        override val indexOrder: Long,
    ) : ChannelReactionCacheCandidate

    data class Unbound(
        val messageID: UUID,
        val text: String,
        val timestamp: UInt,
        override val indexedAt: Instant,
        override val indexOrder: Long,
    ) : ChannelReactionCacheCandidate
}

/**
 * Original immutable bounded cache snapshot, ordered by strictly ascending original indexOrder.
 * The ordinal is its zero-based list position, NOT indexOrder, row ID or a later cache lookup.
 * Bound text/time are captured.message.text/reactionTimestamp; no duplicate public rebinding fields.
 */
data class ChannelReactionCacheSelectionRequest(
    val occurrence: ChannelReactionOccurrence,
    val parsedReaction: ParsedReaction,
    val candidates: SnapshotList<ChannelReactionCacheCandidate>,
) {
    init {
        require(candidates.zipWithNext().all { (left, right) -> left.indexOrder < right.indexOrder }) {
            "Cache candidates must retain unique ascending original index order"
        }
    }
}

sealed interface ChannelReactionCacheSelectionIssueReason {
    data object Unbound : ChannelReactionCacheSelectionIssueReason
    data object Stale : ChannelReactionCacheSelectionIssueReason
    data class Unproven(val reason: ChannelReactionUnprovenReason) : ChannelReactionCacheSelectionIssueReason
    /** Persisted evidence cannot resolve eligibility; not a caught storage exception or rollback. */
    data object Unresolved : ChannelReactionCacheSelectionIssueReason
    data class Rejected(val reason: ChannelReactionRejection) : ChannelReactionCacheSelectionIssueReason
}

/** Null ordinal means the original occurrence or whole batch could not be authenticated. */
data class ChannelReactionCacheSelectionIssue(
    val ordinal: Int?,
    val reason: ChannelReactionCacheSelectionIssueReason,
)

sealed interface ChannelReactionCacheSelectionResult {
    val request: ChannelReactionCacheSelectionRequest

    /** Shape only, not public proof: consumers accept only the actual authority's returned result. */
    data class Selected(
        override val request: ChannelReactionCacheSelectionRequest,
        val ordinal: Int,
    ) : ChannelReactionCacheSelectionResult {
        init {
            require(ordinal in request.candidates.indices)
            require(request.candidates[ordinal] is ChannelReactionCacheCandidate.Bound)
        }

        val candidate: ChannelReactionCacheCandidate.Bound
            get() = request.candidates[ordinal] as ChannelReactionCacheCandidate.Bound
    }

    /** Authenticated complete original selection with no eligible same-origin format match. */
    data class NoMatch(
        override val request: ChannelReactionCacheSelectionRequest,
    ) : ChannelReactionCacheSelectionResult

    /** Terminal for this original selection; retain the entire exact snapshot without fallback. */
    data class Blocked(
        override val request: ChannelReactionCacheSelectionRequest,
        val issues: SnapshotList<ChannelReactionCacheSelectionIssue>,
    ) : ChannelReactionCacheSelectionResult {
        init {
            require(issues.isNotEmpty())
            require(issues.all { it.ordinal == null || it.ordinal in request.candidates.indices })
            require(issues.mapNotNull { it.ordinal }.let { it == it.distinct().sorted() }) {
                "Per-member issues must retain original order without duplicate ordinals"
            }
        }
    }
}

/**
 * Separate optional role, with no default, issuer, generic-authority cast or current-slot lookup.
 * ORIGINAL cache selection only: bound pending work must never call this to reselect a successor.
 *
 * In ONE consistent read transaction authenticate the SAME retained original reaction occurrence,
 * parsedReaction derivation from its exact wire and each original cached handle/message incarnation/
 * identity revision and match-key membership. Accept only own private issuer implementations;
 * public Proven/target markers, UUID/content/hash equality and identity-row existence are not proof.
 * Classify ALL snapshot members BEFORE ranking. A genuinely authenticated different origin may be
 * excluded then; missing/stale/foreign-issued/unknown evidence or an unbound member is Blocked,
 * never silently filtered into NoMatch. Unresolved means actual persisted uncertainty, not a
 * fabricated receipt for exceptions. A stale historical target is not recaptured by ID.
 *
 * Among genuinely authenticated same-origin matches preserve existing wire format matching, then
 * choose greatest indexedAt using STRICT greater-than; first equal in original input order wins.
 * indexedAt/indexOrder are original ranking metadata, never origin authority. No clocks, LRU touch,
 * cache mutation, reindex, database fallback selection or speculative apply-then-reselect.
 * NoMatch requires an authenticated completed selection (including genuine empty/all-known-foreign);
 * even an empty snapshot with an unproved occurrence is Blocked, not a successful empty query.
 *
 * Return the SAME request object. Selected.ordinal identifies that exact original Bound member,
 * retaining its captured handle/reference. Blocked retains the request and original affected
 * ordinals/reasons; consumers validate correlation and preserve it in bounded non-redrivable custody,
 * never treat it as a DB miss or discard unbound members. No result constructor grants authenticity.
 * Missing role is explicit unavailable custody, not compatibility fallback or a fake positive role.
 *
 * Historical authentic A/A may match after live slot B retires A, including separately authenticated
 * sessions. B cannot match A. No current slot equality or same-event UUID requirement is introduced.
 * Final applyChannelReaction independently validates the SAME original occurrence and predecessor/
 * origin/revision in its SAME WRITE transaction after suspension. This read result is not a lease
 * or mutation permit. Exceptions/cancellation propagate with the caller retaining the exact request;
 * uncertain outcomes never authorize retry, recapture or a new selection.
 * Current storage has NO positive occurrence issuer and can only report explicit Blocked outcomes.
 */
interface ChannelReactionCacheSelectionAuthority {
    suspend fun selectChannelReactionCacheTarget(
        request: ChannelReactionCacheSelectionRequest,
    ): ChannelReactionCacheSelectionResult
}
