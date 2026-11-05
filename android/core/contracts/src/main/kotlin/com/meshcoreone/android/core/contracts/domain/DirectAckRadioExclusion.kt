// AndroidOnly: WP-201 Persisted radio-wide direct-ACK emission evidence contract.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.SnapshotSet
import java.util.UUID

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
 * committed. Neither a public snapshot nor an empty snapshot authorizes a delivery mutation.
 */
data class DirectAckRadioEvidenceSnapshot(
    val radioId: RadioId,
    val wires: SnapshotList<DirectAckRadioWireEvidence>,
    val coverage: DirectAckRadioEvidenceCoverage,
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
     * resolutionRequestId. Later resolutions and database/process reopen do not retire earlier
     * replay identities. After E/r1 Accepted(123) and E/r2 Accepted(123), replaying r1 after reopen
     * is still AlreadyApplied; reusing r1 with Accepted(456) or DefinitelyUnsent is Conflict.
     * Replaying r1 neither replaces latest metadata r2 nor restores earlier evidence/authority.
     * Existing sticky Conflict evidence must never be cleared by replaying an earlier success.
     * Storage failures/cancellation propagate unchanged; a latest-ID comparison or readback alone
     * cannot establish whether an older operation committed.
     */
    suspend fun resolveDirectAckWireEmission(
        handle: DirectAckEmissionHandle,
        resolutionRequestId: UUID,
        resolution: DirectAckEmissionResolution,
    ): DirectAckEmissionMutationResult

    suspend fun inspectDirectAckRadioEvidence(
        radioId: RadioId,
    ): DirectAckRadioEvidenceObservation

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
