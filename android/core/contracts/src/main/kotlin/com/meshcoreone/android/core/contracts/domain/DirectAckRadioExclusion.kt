// AndroidOnly: WP-201 Persisted radio-wide direct-ACK emission evidence contract.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.SnapshotSet
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Storage-issued exact-emission refinement handle; public IDs and DTO equality cannot recreate it. */
interface DirectAckEmissionHandle

sealed interface DirectAckRadioWireOutcome {
    /** Persisted before dispatch; neither physical transmission nor ACK origin is thereby proven. */
    data object MayHaveWritten : DirectAckRadioWireOutcome

    /** Only an explicit transport guarantee that no bytes were written can produce this state. */
    data object DefinitelyUnsent : DirectAckRadioWireOutcome

    /** Untracked legacy wire with no known accepted actual code. */
    data object LegacyUnknown : DirectAckRadioWireOutcome

    /**
     * The accepted code is known, but untracked history cannot rule out earlier physical emissions.
     * The code remains known evidence while the ancestry remains wildcard ambiguity.
     */
    data class LegacyAcceptedUnknown(val ackCode: UInt) : DirectAckRadioWireOutcome

    /** Actual accepted code for this physical emission. */
    data class Accepted(val ackCode: UInt) : DirectAckRadioWireOutcome

    /**
     * Contradictory outcomes are retained, never overwritten. This is always wildcard ambiguity;
     * the first accepted code remains available as a known competing value without an unbounded
     * contradictory-code history.
     */
    data class Conflict(
        val firstAcceptedAckCode: UInt?,
        val definitelyUnsentObserved: Boolean,
    ) : DirectAckRadioWireOutcome
}

/** Immutable, non-authoritative evidence. Storage validates and reads the canonical rows itself. */
data class DirectAckRadioWireEvidence(
    val radioId: RadioId,
    val claimRequestId: UUID,
    val wireRequestId: UUID,
    /** Null only for legacy wire-level evidence that has no physical-emission identity. */
    val emissionRequestId: UUID?,
    val predictedAckCode: UInt,
    val outcome: DirectAckRadioWireOutcome,
)

enum class DirectAckRadioEvidenceCoverage { COMPLETE, TRUNCATED, SATURATED }

object DirectAckRadioEvidenceLimits {
    const val MAX_DIAGNOSTIC_WIRES_PER_RADIO = 4_096
}

/**
 * Bounded diagnostic evidence only. COMPLETE means the snapshot contains every retained physical
 * emission and genuine legacy markerless row; tracked wires with no physical marker are omitted.
 * TRUNCATED means diagnostic rows were omitted; SATURATED means a durable radio-wide wildcard was
 * committed. preauthorityHistoryUnknown is independently read from durable DB-wide metadata in
 * the same transaction, including for radios with no surviving rows. COMPLETE covers retained
 * journal rows only, not unrecorded physical prehistory. Neither a public snapshot nor an empty
 * snapshot authorizes a delivery mutation. Callers must supply the actual flag; no default exists.
 * Unproved physical history before current app-owned authority includes external sends on a fresh
 * installation as well as pre-journal upgrades. Fresh local DB creation is not clean-radio proof.
 * A false flag is not an external-radio clean-history certificate; no reset/clearing API is supplied.
 */
data class DirectAckRadioEvidenceSnapshot(
    val radioId: RadioId,
    val wires: SnapshotList<DirectAckRadioWireEvidence>,
    val coverage: DirectAckRadioEvidenceCoverage,
    val preauthorityHistoryUnknown: Boolean,
)

data class DirectAckEmissionReceipt(
    val emissionRequestId: UUID,
    /**
     * Latest committed resolution metadata, not the identity of every replayed operation.
     * An AlreadyApplied replay of an older resolution may carry a newer ID here.
     * Null means no resolution has committed; this field is not a deduplication journal.
     */
    val resolutionRequestId: UUID?,
    val evidence: DirectAckRadioWireEvidence,
)

data class DirectAckRadioSaturationReceipt(val radioId: RadioId)

sealed interface DirectAckEmissionMarkResult {
    data class Marked(
        val handle: DirectAckEmissionHandle,
        val receipt: DirectAckEmissionReceipt,
    ) : DirectAckEmissionMarkResult

    data class AlreadyMarked(
        val handle: DirectAckEmissionHandle,
        val receipt: DirectAckEmissionReceipt,
    ) : DirectAckEmissionMarkResult

    /**
     * An already-persisted sticky wildcard covers this send. No handle is fabricated and ordinary
     * journal growth never creates this result.
     */
    data class CoveredBySaturation(
        val receipt: DirectAckRadioSaturationReceipt,
    ) : DirectAckEmissionMarkResult

    data class Rejected(val reason: DirectAckRejection) : DirectAckEmissionMarkResult
}

sealed interface DirectAckEmissionResolution {
    data object DefinitelyUnsent : DirectAckEmissionResolution
    data class Accepted(val ackCode: UInt) : DirectAckEmissionResolution
}

sealed interface DirectAckEmissionMutationResult {
    /** Evidence refinement only; this is not a delivered message receipt. */
    data class Applied(val receipt: DirectAckEmissionReceipt) : DirectAckEmissionMutationResult

    /** Exact evidence replay only; this is not a delivered message receipt. */
    data class AlreadyApplied(val receipt: DirectAckEmissionReceipt) : DirectAckEmissionMutationResult

    /** The conflicting fact was durably retained and now makes this wire a wildcard. */
    data class Conflict(val receipt: DirectAckEmissionReceipt) : DirectAckEmissionMutationResult

    data class Rejected(val reason: DirectAckRejection) : DirectAckEmissionMutationResult
}

sealed interface DirectAckEmissionObservation {
    data class Found(val receipt: DirectAckEmissionReceipt) : DirectAckEmissionObservation

    /** No marker was found; this is not evidence that the wire was unsent. */
    data object NotFound : DirectAckEmissionObservation

    data class CoveredBySaturation(
        val receipt: DirectAckRadioSaturationReceipt,
    ) : DirectAckEmissionObservation

    data class Rejected(val reason: DirectAckRejection) : DirectAckEmissionObservation
}

sealed interface DirectAckRadioEvidenceObservation {
    data class Found(val snapshot: DirectAckRadioEvidenceSnapshot) : DirectAckRadioEvidenceObservation
    data class Rejected(val reason: DirectAckRejection) : DirectAckRadioEvidenceObservation
}

enum class DirectAckRadioNonsettlingReason {
    TARGET_CODE_UNREGISTERED,
    TARGET_ACCEPTANCE_UNRESOLVED,
    KNOWN_OTHER_CLAIM_CODE,
    UNRESOLVED_OTHER_CLAIM_EMISSION,
    /** Diagnostic-snapshot classification only; SQL settlement checks the complete journal. */
    RADIO_HISTORY_INCOMPLETE,
    RADIO_HISTORY_SATURATED,
    PREAUTHORITY_HISTORY_UNKNOWN,
}

/**
 * Diagnostic classifier for contract tests and UI-free inspection only. Its result is never a
 * storage receipt or mutation permission; settlement must repeat the complete comparison atomically.
 */
object DirectAckRadioEvidenceAssessment {
    fun nonsettlingReasons(
        targetClaimRequestId: UUID,
        ackCode: UInt,
        history: DirectAckRadioEvidenceSnapshot,
    ): SnapshotSet<DirectAckRadioNonsettlingReason> {
        val reasons = linkedSetOf<DirectAckRadioNonsettlingReason>()
        if (history.preauthorityHistoryUnknown) {
            reasons.add(DirectAckRadioNonsettlingReason.PREAUTHORITY_HISTORY_UNKNOWN)
        }
        val radioWires = history.wires.filter { it.radioId == history.radioId }
        val targetWires = radioWires.filter { it.claimRequestId == targetClaimRequestId }

        val targetHasAcceptedCode = targetWires.any {
            (it.outcome as? DirectAckRadioWireOutcome.Accepted)?.ackCode == ackCode
        }
        if (!targetHasAcceptedCode) {
            if (targetWires.any {
                    it.outcome == DirectAckRadioWireOutcome.MayHaveWritten ||
                        it.outcome == DirectAckRadioWireOutcome.LegacyUnknown ||
                        it.outcome is DirectAckRadioWireOutcome.LegacyAcceptedUnknown ||
                        it.outcome is DirectAckRadioWireOutcome.Conflict
                }) {
                reasons.add(DirectAckRadioNonsettlingReason.TARGET_ACCEPTANCE_UNRESOLVED)
            } else {
                reasons.add(DirectAckRadioNonsettlingReason.TARGET_CODE_UNREGISTERED)
            }
        }

        for (wire in radioWires) {
            if (wire.claimRequestId == targetClaimRequestId) continue
            when (val outcome = wire.outcome) {
                DirectAckRadioWireOutcome.DefinitelyUnsent -> Unit
                DirectAckRadioWireOutcome.LegacyUnknown,
                DirectAckRadioWireOutcome.MayHaveWritten -> {
                    reasons.add(DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION)
                    if (wire.predictedAckCode == ackCode) {
                        reasons.add(DirectAckRadioNonsettlingReason.KNOWN_OTHER_CLAIM_CODE)
                    }
                }
                is DirectAckRadioWireOutcome.LegacyAcceptedUnknown -> {
                    reasons.add(DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION)
                    if (wire.predictedAckCode == ackCode || outcome.ackCode == ackCode) {
                        reasons.add(DirectAckRadioNonsettlingReason.KNOWN_OTHER_CLAIM_CODE)
                    }
                }
                is DirectAckRadioWireOutcome.Conflict -> {
                    reasons.add(DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION)
                    if (wire.predictedAckCode == ackCode || outcome.firstAcceptedAckCode == ackCode) {
                        reasons.add(DirectAckRadioNonsettlingReason.KNOWN_OTHER_CLAIM_CODE)
                    }
                }
                is DirectAckRadioWireOutcome.Accepted -> {
                    if (wire.predictedAckCode == ackCode || outcome.ackCode == ackCode) {
                        reasons.add(DirectAckRadioNonsettlingReason.KNOWN_OTHER_CLAIM_CODE)
                    }
                }
            }
        }

        when (history.coverage) {
            DirectAckRadioEvidenceCoverage.COMPLETE -> Unit
            DirectAckRadioEvidenceCoverage.TRUNCATED ->
                reasons.add(DirectAckRadioNonsettlingReason.RADIO_HISTORY_INCOMPLETE)
            DirectAckRadioEvidenceCoverage.SATURATED ->
                reasons.add(DirectAckRadioNonsettlingReason.RADIO_HISTORY_SATURATED)
        }
        return SnapshotSet(reasons)
    }
}

/**
 * Optional, additive capability. It does not replace or default-implement DirectAckAuthority.
 * Consumers must surface an unavailable capability and must not fall back to settleDirectAck.
 *
 * markDirectAckWireMayHaveWritten validates the exact current attempt/wire grant and commits a
 * MAY_HAVE_WRITTEN marker keyed by physical emissionRequestId before each transport invocation.
 * emissionRequestId is fresh per invocation even when wireRequestId is reused for retry/backoff.
 * A commit-uncertain caller replays or inspects that exact ID; transport is never replayed to
 * reconcile metadata. Inspection does not mint a handle; replaying the exact marker request recovers
 * the storage-issued handle when one was committed. The exact authoritative marker journal has no
 * total-row cap and retains every emission identity, replay result, contradiction and unresolved
 * fact. Storage failures/cancellation propagate and dispatch cannot proceed without a committed
 * marker or an already-persisted CoveredBySaturation result. Normal journal growth never creates
 * saturation or sacrifices exact per-emission evidence.
 *
 * The handle refines only its exact persisted physical emission. resolutionRequestId is caller-known
 * before resolution and exact replay is idempotent; reusing it for different input is CONFLICT.
 * DefinitelyUnsent is nonconflicting only before accepted-code evidence; contradictory accepted/
 * unsent facts are retained as Conflict and remain wildcard ambiguity. Accepted carries the actual
 * returned code, preserves prediction history, and may refine after grant invalidation without
 * restoring or changing message authority. It atomically binds this exact emission's actual code
 * and matching registered-wire acceptance metadata. It may advance the claim's ACCEPTED phase only
 * while the original grant remains current; it never changes product delivery status.
 * DefinitelyUnsent refines only that physical emission and cannot clear another marker with the
 * same registered wireRequestId. The legacy recordDirectSendAccepted API has no physical emission
 * ID and must never clear per-emission MAY_HAVE_WRITTEN evidence; only this exact-handle resolver
 * can do so. Markerless legacy wires remain wildcard-ambiguous even when a wire-level accepted code
 * exists; LegacyAcceptedUnknown preserves that code as known competitor evidence. A later emission
 * marker never erases untracked legacy ancestry.
 *
 * Marker registration and settlement share a database-level per-radio serialization/fence across
 * every Room handle. In a race, the marker transaction and settlement transaction have a single
 * commit order: if the marker commits first, settlement sees it; if settlement commits first, the
 * competing transport has not been called yet because its marker is a pre-dispatch requirement.
 * An in-memory mutex or previously inspected snapshot cannot provide this ordering.
 * A marker persistence failure or cancellation propagates unchanged and the transport call does not
 * happen. Firmware rejection after bytes were written is not DefinitelyUnsent; the marker stays
 * MAY_HAVE_WRITTEN unless an exact accepted result or explicit zero-write proof refines that handle.
 *
 * inspectDirectAckRadioEvidence is bounded and diagnostic to MAX_DIAGNOSTIC_WIRES_PER_RADIO:
 * TRUNCATED means the sample omitted rows; SATURATED means a sticky wildcard already exists in
 * persisted history (for example, conservative legacy migration). Neither sample state authorizes
 * settlement. The storage query must check every persisted competing emission, using bounded/paged
 * working memory or an equivalent database-side query without dropping exact journal rows.
 * settleDirectAckExcludingRadioHistory validates the current target grant and compares all claims
 * for that radio in the same serialized storage transaction as delivery. For the target claim,
 * any matching Accepted actual code from the valid claim is eligible, including multiple physical
 * emissions that reused one wireRequestId; an earlier accepted emission remains eligible across a
 * prepared or differently accepted retry. For every other claim, a completed emission contributes
 * both its predicted and accepted codes as competitors; any unresolved
 * MAY_HAVE_WRITTEN/LegacyUnknown/LegacyAcceptedUnknown/Conflict emission is a wildcard.
 * DefinitelyUnsent excludes only that exact physical emission. The diagnostic classifier reports
 * TRUNCATED snapshots as RADIO_HISTORY_INCOMPLETE, but settlement does not trust or depend on a
 * diagnostic snapshot: its database transaction compares the complete durable history and may
 * settle selectively when a diagnostic sample would be truncated. Persisted SATURATED, unresolved
 * competitors, and known code collisions return typed Nonsettling reasons without a delivery
 * mutation. Only Settled(Applied/AlreadyApplied)
 * permits the consumer to complete its delivery receipt or publish delivered state. Nonsettling
 * abandons only the exact pending ACK candidate, leaves the logical claim waiting, and preserves
 * the product row. A successful send response or evidence refinement is not a delivery fallback.
 *
 * Authoritative marker retention is not count-bounded and has no TTL/eviction/reset rule. Diagnostic
 * inspection is bounded by MAX_DIAGNOSTIC_WIRES_PER_RADIO and reports TRUNCATED rather than implying
 * completeness. Any already-persisted SATURATED fact remains sticky across lease replacement,
 * reconnect and process restart. A raw ACK observed only in RAM is not durably ingested or promised
 * to replay after process death.
 */
interface DirectAckRadioExclusionAuthority {
    /**
     * Atomically create the first wire and immutable emission-tracking-required provenance.
     * Exact replay returns AlreadyBegun only when the original wire has matching tracked
     * provenance; reusing the operation/wire identity with different inputs, crossing a generic
     * untracked operation, or trying to upgrade an existing legacy wire is Rejected(CONFLICT).
     * This method has no begin-then-tag gap. Generic DirectAckAuthority begin remains the legacy
     * untracked path.
     */
    suspend fun beginDirectAckAttemptRequiringEmissionTracking(
        intent: DirectAckIntent,
        firstWireRequestId: UUID,
        predictedAckCode: UInt,
    ): DirectAckBeginResult

    /**
     * Atomically register a retry wire with immutable emission-tracking-required provenance.
     * Exact tracked replay returns AlreadyApplied; collisions and attempts to upgrade a legacy wire,
     * cross a generic untracked operation, or downgrade a tracked attempt return
     * Rejected(CONFLICT). Generic DirectAckAuthority registration remains the legacy untracked path.
     */
    suspend fun registerDirectAckDispatchRequiringEmissionTracking(
        attempt: DirectAckAttempt,
        wireRequestId: UUID,
        predictedAckCode: UInt,
    ): DirectAckMutationResult

    /**
     * emissionRequestId is new for each physical transport invocation. Replaying the same exact
     * claim/wire/emission operation returns its persisted handle; reusing that ID with different
     * input is CONFLICT. The wire must have been created with the tracking-required methods above;
     * an untracked legacy wire cannot be upgraded by marking, and rejection occurs before a marker
     * write. A marker must be committed before the caller invokes transport.
     */
    suspend fun markDirectAckWireMayHaveWritten(
        attempt: DirectAckAttempt,
        wireRequestId: UUID,
        emissionRequestId: UUID,
    ): DirectAckEmissionMarkResult

    /** Inspection never infers unsent from absence or creates a refinement handle. */
    suspend fun inspectDirectAckWireEmission(
        attempt: DirectAckAttempt,
        wireRequestId: UUID,
        emissionRequestId: UUID,
    ): DirectAckEmissionObservation

    /**
     * The caller supplies a resolutionRequestId before the operation. Exact ID/input replay is
     * AlreadyApplied; reusing an ID with different input returns Conflict. A fresh ID carrying the
     * same evidence records that ID as the latest resolution and returns Applied. Contradictory
     * facts return Conflict with sticky wildcard evidence, preserving the first accepted actual
     * and any definitely-unsent fact. Accepted atomically updates only this emission, its
     * registered-wire actual metadata, and the current claim's ACCEPTED phase when its original
     * grant is still valid. It never renews authority or changes delivery status.
     *
     * Every resolution operation's identity, exact input and committed result must be durably
     * retained atomically with the evidence mutation, independently of the emission row's latest
     * resolutionRequestId. Within a claim, the ID binds the exact wire, physical emission and
     * resolution input: reusing it for another emission is Conflict even with the same code.
     * The caller uses a fresh ID for each distinct operation; the first journal entry is immutable.
     * Later resolutions and database/process reopen do not retire earlier
     * replay identities. After E/r1 Accepted(123) and E/r2 Accepted(123), replaying r1 after reopen
     * is still AlreadyApplied; reusing r1 with Accepted(456) or DefinitelyUnsent is Conflict.
     * Replaying r1 neither replaces latest metadata r2 nor restores earlier evidence/authority.
     * Existing sticky Conflict evidence must never be cleared by replaying an earlier success.
     * File-reopen replay requires a retained genuine opaque handle, validated against its persisted
     * original issuer/lease/emission binding, not equality with the receiver's current issuer.
     * This historical refinement never reauthorizes the product grant or relaxes issuer fencing
     * on attempt/lease APIs. Process death cannot reconstruct an opaque handle through this API;
     * durable operation retention is not a promise of handle recovery or restart replay.
     * Storage failures/cancellation propagate unchanged; a latest-ID comparison or readback alone
     * cannot establish whether an older operation committed.
     */
    suspend fun resolveDirectAckWireEmission(
        handle: DirectAckEmissionHandle,
        resolutionRequestId: UUID,
        resolution: DirectAckEmissionResolution,
    ): DirectAckEmissionMutationResult

    /**
     * On an unsaturated radio, bounded diagnostics cannot represent tracked-wire acceptance without
     * matching valid physical acceptance. Surface existing typed invalid-data/unavailable failure
     * for that inconsistency, never fabricate a legacy/physical row or report COMPLETE emptiness.
     * On a sticky saturated radio, CoveredBySaturation legitimately permits an unrecorded physical
     * invocation followed by generic wire acceptance. Retain SATURATED coverage and actual stored
     * physical rows; the unmatched wire binding alone must not cause invalid-data failure there,
     * even alongside earlier UNSENT/MAY/mismatched rows. Structural malformed data still fails.
     */
    suspend fun inspectDirectAckRadioEvidence(
        radioId: RadioId,
    ): DirectAckRadioEvidenceObservation

    /**
     * Accepted-actual-only settlement, unlike the unchanged generic settleDirectAck contract.
     * A prediction alone is never permission: buffer a pre-return raw ACK, persist the physical
     * emission's actual returned code, then reconcile only a matching actual code. Once accepted,
     * that target emission's prediction is retained history, not eligibility. Earlier accepted
     * emissions of the same current claim remain eligible across retries. Full radio-history
     * exclusion and current-grant validation occur in the same transaction as delivery.
     * Consult durable DB-wide preauthority-history uncertainty in that transaction for every
     * requested radio, including radios with no surviving rows or deleted/readded identities.
     * PREAUTHORITY_HISTORY_UNKNOWN blocks new settlement without inventing a wire or saturation.
     * Preserve genuine committed delivery and exact historical replay precedence.
     * On an unsaturated radio, a tracked registered-wire accepted code without a valid ACCEPTED row
     * for that exact wire/code is unresolved cross-table evidence. Target inconsistency yields
     * TARGET_ACCEPTANCE_UNRESOLVED even if another target wire has a valid accepted emission;
     * foreign inconsistency yields UNRESOLVED_OTHER_CLAIM_EMISSION. No generic behavior changes.
     * Sticky saturation instead explains possible unrecorded CoveredBySaturation acceptance;
     * that missing match alone is not corruption, but saturation still blocks new settlement.
     */
    suspend fun settleDirectAckExcludingRadioHistory(
        attempt: DirectAckAttempt,
        ackCode: UInt,
        roundTripTime: UInt?,
    ): DirectAckRadioSettlementResult
}

sealed interface DirectAckRadioSettlementResult {
    data class Settled(val receipt: DirectAckRadioSettlementReceipt) : DirectAckRadioSettlementResult
    data class Nonsettling(
        val reasons: SnapshotSet<DirectAckRadioNonsettlingReason>,
    ) : DirectAckRadioSettlementResult

    data class Rejected(val reason: DirectAckRejection) : DirectAckRadioSettlementResult
}

sealed interface DirectAckRadioSettlementReceipt {
    data class Applied(val receipt: DirectAckReceipt) : DirectAckRadioSettlementReceipt
    data class AlreadyApplied(val receipt: DirectAckReceipt) : DirectAckRadioSettlementReceipt
}

enum class DirectAckRadioConfirmationWildcardReason {
    UNRESOLVED_PHYSICAL_EMISSION,
    CONFLICTING_PHYSICAL_EVIDENCE,
    UNTRACKED_LEGACY_ANCESTRY,
    STICKY_SATURATION,
    PREAUTHORITY_HISTORY_UNKNOWN,
}

/**
 * Complete persisted radio-wide wildcard observation at one atomic storage snapshot, not delivery
 * eligibility, a grant, or a proof that known ACK-code collisions are absent. It may become stale
 * immediately; settlement must independently validate its complete history and current grant.
 */
sealed interface DirectAckRadioConfirmationEvidence {
    val radioId: RadioId

    /** No persisted wildcard was observed in this snapshot; known code collisions may still exist. */
    data class NoPersistedWildcard(
        override val radioId: RadioId,
    ) : DirectAckRadioConfirmationEvidence

    data class PersistedWildcard(
        override val radioId: RadioId,
        val reasons: SnapshotSet<DirectAckRadioConfirmationWildcardReason>,
    ) : DirectAckRadioConfirmationEvidence {
        init {
            require(reasons.isNotEmpty()) { "Persisted wildcard evidence requires at least one reason" }
        }
    }
}

/**
 * Optional read-only capability, separate from settlement and diagnostic sampling. A missing
 * capability must be surfaced as unavailable, never as NoPersistedWildcard or an empty history.
 * Storage failures and cancellation propagate unchanged through existing typed failure paths.
 * There is no default implementation, grant/handle issuance, authority renewal or mutation.
 */
interface DirectAckRadioConfirmationEvidenceInspecting {
    /**
     * Return the exact requested radio and every applicable typed wildcard reason from its complete
     * persisted history in one atomic read transaction, including retired leases and generations.
     * MAY_HAVE_WRITTEN/unresolved physical emissions, physical evidence conflicts, untracked legacy
     * ancestry even with a known accepted code or later markers, and already-persisted sticky
     * saturation must remain distinguishable. Known accepted and definitely-unsent physical rows
     * alone are not wildcards; tracked-but-unmarked registered wires are not physical emissions.
     * Include PREAUTHORITY_HISTORY_UNKNOWN from durable DB-wide metadata for every requested
     * radio, even without any radio/message/wire rows. It is not a synthetic legacy emission or
     * sticky radio saturation and may coexist with all other reasons.
     * On an unsaturated radio, a tracked wire's accepted binding without a valid physical wire/code
     * contributes CONFLICTING_PHYSICAL_EVIDENCE; do not omit it as tracked-but-unmarked.
     * Sticky saturation can legitimately cover an unrecorded invocation and wire acceptance;
     * retain STICKY_SATURATION, not an automatic conflict solely for its missing physical match.
     *
     * No sample, diagnostic row cap, synthetic excluded claim, lease/grant, generation filter or
     * TTL may narrow this query. The read never creates/clears evidence or saturation. Absence of
     * persisted wildcards does not establish per-code uniqueness, recipient delivery, message
     * eligibility or permission to settle; this snapshot cannot replace atomic settlement.
     */
    suspend fun inspectDirectAckRadioConfirmationEvidence(
        radioId: RadioId,
    ): DirectAckRadioConfirmationEvidence
}

/** Subscriber-owned observation events; neither event establishes delivery eligibility. */
sealed interface DirectAckRadioConfirmationObservation {
    val radioId: RadioId

    /**
     * A new complete read is starting; previous evidence remains explicitly historical.
     * Consumers may retain that historical notice without flashing a loading indicator.
     */
    data class Inspecting(
        override val radioId: RadioId,
    ) : DirectAckRadioConfirmationObservation

    /** Historical atomic observation, not an instantaneous all-clear or permission to settle. */
    data class Snapshot(
        val evidence: DirectAckRadioConfirmationEvidence,
    ) : DirectAckRadioConfirmationObservation {
        override val radioId: RadioId get() = evidence.radioId
    }
}

/**
 * Optional, separate cold observation capability. Each collector owns its observation lifetime;
 * consumers explicitly surface absence as unavailable and start each collection in a checking
 * state. Evidence is not a cache of current authority and settlement remains independent.
 * The active radio graph shares one subscription across its consumers to avoid duplicate scans;
 * a replacement graph or lifecycle resubscription starts a fresh observation, not a cached clear.
 */
interface DirectAckRadioConfirmationEvidenceObserving {
    /**
     * Emit Inspecting for the requested radio before each complete atomic read, then Snapshot
     * carrying that read's exact-radio evidence. Reads are serial and do not overlap; no cached
     * initial snapshot, replay, conflation or distinct suppression may replace a completed read.
     * Consumers fence collection lifetime, radio and session so late results cannot cross owners.
     *
     * The initial read starts immediately on collection. After each Snapshot, wait a cancellable
     * 1,000 milliseconds before starting the next read, only while subscribed. Complete reads repeat
     * independently of writer notification, so persisted changes from other Room/raw SQL handles
     * are eventually observed even without multi-instance invalidation. Same-process invalidation
     * may accelerate a read but cannot replace or indefinitely postpone this fallback.
     *
     * This is snapshot polling, not a per-commit event log or synchronous freshness guarantee.
     * Intermediate commits may coalesce; query duration, scheduling and collector backpressure
     * add latency, so there is no hard one-second observation-age bound. Every NoPersistedWildcard
     * remains historical, never an all-clear, permission or proof of recipient delivery.
     *
     * Storage failures and cancellation terminate unchanged, never a no-wildcard fallback.
     * Store closure must end observation with the existing typed close/read failure, not complete
     * successfully or emit cached evidence. Consumers surface error or unexpected normal completion
     * as unavailable/uncertain rather than retain an apparent clear. Cancellation ends this
     * collector's reads; no detached worker or publisher is created. No expiry, evidence mutation,
     * schema reset or send-policy change is implied.
     */
    fun observeDirectAckRadioConfirmationEvidence(
        radioId: RadioId,
    ): Flow<DirectAckRadioConfirmationObservation>
}

enum class DirectAckRadioNonfailingReason {
    TARGET_UNRESOLVED_PHYSICAL_EMISSION,
    TARGET_CONFLICTING_PHYSICAL_EVIDENCE,
    TARGET_UNTRACKED_LEGACY_ANCESTRY,
    KNOWN_OTHER_CLAIM_CODE,
    UNRESOLVED_OTHER_CLAIM_EMISSION,
    CONFLICTING_OTHER_CLAIM_EVIDENCE,
    UNTRACKED_OTHER_CLAIM_ANCESTRY,
    RADIO_HISTORY_SATURATED,
    PREAUTHORITY_HISTORY_UNKNOWN,
}

sealed interface DirectAckRadioFailureResult {
    data class Applied(val receipt: DirectAckReceipt) : DirectAckRadioFailureResult
    /** Historical exact guarded-failure commit; not current proof or renewed permission. */
    data class AlreadyApplied(val receipt: DirectAckReceipt) : DirectAckRadioFailureResult
    /** Own delivery or protected initial DELIVERED baseline; never fabricate a delivery tuple. */
    data class AlreadyDelivered(val receipt: DirectAckReceipt) : DirectAckRadioFailureResult

    /** Failure was not written; uncertainty is not recipient delivery or an invented ACK. */
    data class Nonfailing(
        val reasons: SnapshotSet<DirectAckRadioNonfailingReason>,
    ) : DirectAckRadioFailureResult {
        init {
            require(reasons.isNotEmpty()) { "Nonfailing evidence requires at least one reason" }
        }
    }

    data class Rejected(val reason: DirectAckRejection) : DirectAckRadioFailureResult
}

/**
 * Optional, additive atomic failure capability. It does not replace DirectAckAuthority's generic
 * recordDirectFailure method or authorize failure through a public diagnostic/UI snapshot.
 * A missing capability must be explicitly unavailable, never a generic failure-write fallback.
 *
 * A new failure write validates the opaque attempt's current lease/incarnation/revision and
 * complete persisted radio history in the same serialized transaction as the mutation. Do not
 * sample, truncate, filter retired generations, invent an ACK code or infer no-write from missing
 * diagnostic rows. Foreign/missing/stale/ineligible authority uses existing DirectAckRejection
 * meanings and must never mutate a replacement row.
 *
 * Consult durable DB-wide preauthority-history uncertainty in that transaction. Report
 * PREAUTHORITY_HISTORY_UNKNOWN independently of RADIO_HISTORY_SATURATED; neither absent rows,
 * an empty claim history nor this attempt's unmarked/all-unsent rows prove that an older message
 * never emitted. Unknown foreign prehistory cannot have delivered a target only if a genuine,
 * independent whole-target no-write proof exists; current legacy/imported rows have no such birth
 * certificate. Do not infer this exception from new local DB/session identity or retrofit proof.
 * The preauthority marker is durable for every requested radio, including deleted/readded radios.
 * Unproved external history on fresh installations is UNKNOWN too; new local DB/session identity
 * cannot certify a clean radio. There is no TTL/reset or invented certificate. Sending remains
 * permitted under its ordinary admission; new confirmation can remain unavailable indefinitely.
 *
 * Sticky radio saturation ALWAYS blocks a new failure with RADIO_HISTORY_SATURATED, including
 * tracked attempts with no physical rows or only DefinitelyUnsent rows. CoveredBySaturation can
 * authorize a physical invocation without an emission row/handle; these rows cannot prove that
 * no additional covered invocation occurred. This contract provides no target-specific proof
 * that overrides saturation. Delivery and historical guarded-replay ordering below is unchanged.
 *
 * Target MAY, conflict (including malformed evidence), or untracked legacy ancestry without a
 * definite-no-write proof is Nonfailing, not FAILED. On a nonsaturated radio only, complete tracked
 * provenance proving no physical invocation, or every target emission DefinitelyUnsent, permits
 * the known-safe no-write failure path regardless of unrelated foreign radio history. A later
 * unsent retry cannot clear an older possible emission; a transport error alone is not proof.
 * The proof must reconcile registered-wire acceptance with physical ancestry, not inspect only
 * emission rows. On an unsaturated radio, a tracked wire's retained accepted code requires a valid ACCEPTED
 * physical row for the same wire and actual code. No marker, only UNSENT/MAY rows, or only
 * mismatched actuals is contradictory/unresolved ancestry, not an uninvoked or definitely-unsent
 * target. Classify target/foreign inconsistency with the corresponding conflicting reason.
 * On a sticky saturated radio, CoveredBySaturation permits an unrecorded invocation and generic
 * acceptance without a matching physical row. That gap alone, including alongside earlier
 * UNSENT/MAY/mismatched rows, is expected rather than corruption. Saturation still blocks every
 * new failure; never use this exception as no-write proof or manufacture a marker/handle.
 * Generic recordDirectSendAccepted can create such a binding. For tracked wires, retain every
 * successfully committed generic accepted actual immutably in the same transaction as acceptance.
 * Strict assessments must reconcile every retained binding, not merely the mutable latest wire
 * code. A later physical acceptance or generic acceptance cannot erase an earlier unmatched code.
 * After generic Accepted(999) without a marker followed by physical Accepted(888), the unmatched
 * 999 still denies the unsaturated no-write/consistency proof. A genuine matching physical 999 can
 * establish a match for that binding without deleting its history or regranting mutation authority.
 * Keep generic eligibility, results and public receipt shape unchanged; internal evidence is not
 * a synthetic wire or delivery receipt. Commit-then-throw replay must not lose committed bindings.
 * Do not fabricate a marker or legacy ancestry, silently discard the accepted binding, or repair
 * rows during assessment. Distinct genuine accepted and unsent physical invocations sharing one
 * logical wire are valid: an unsent retry does not contradict or erase an earlier accepted send.
 *
 * Otherwise, foreign wildcard ancestry/emissions block failure. For
 * accepted target emissions, ANY accepted actual code colliding with any other claim's known
 * predicted/actual code blocks failure; one unambiguous code cannot prove another physical send
 * failed. Report every applicable typed reason, distinguishing target uncertainty from foreign
 * uncertainty. Malformed target/foreign evidence is conflicting evidence, never accepted/unsent.
 * A Nonfailing result writes neither FAILED nor an invented delivery and renews no grant.
 *
 * Own delivery wins first as AlreadyDelivered without mutation. Preserve the existing
 * AlreadyDelivered guard for an initially DELIVERED RESEND baseline too, without fabricating this
 * attempt's own delivery tuple. Otherwise exact replay of a previously committed guarded failure
 * is AlreadyApplied/read-only even after later blockers,
 * revocation or invalidation, with the receipt's actual permission and payload-erasure semantics.
 * Persist guarded-operation identity atomically with failure to reconcile commit-then-throw;
 * an older generic recordDirectFailure commit without guarded proof is Rejected(CONFLICT), not a
 * guarded replay or newly established proof.
 * New writes preserve existing terminal/resend guards and known-safe failure positives. Historical
 * replay never regrants authority, replaces a newer failure or rewrites product state.
 *
 * Storage failures and cancellation propagate unchanged.
 */
interface DirectAckRadioFailureAuthority {
    suspend fun recordDirectFailureExcludingRadioHistory(
        attempt: DirectAckAttempt,
    ): DirectAckRadioFailureResult
}
