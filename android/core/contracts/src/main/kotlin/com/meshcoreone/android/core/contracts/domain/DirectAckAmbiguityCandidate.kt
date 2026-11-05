// AndroidOnly: WP-201 Nonactivated ACK ambiguity conformance candidate, not storage authority.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.SnapshotSet
import java.util.UUID

/** Potential emission is not proof of physical emission or incoming ACK origin. */
enum class DirectAckEmissionEvidence { PREPARED, POTENTIAL_EMISSION, LEGACY_UNKNOWN }

/**
 * Nonpayload diagnostic evidence only. Public values confer no authority. Claim request IDs must
 * identify distinct persisted logical claims, including across leases and replacement incarnations.
 * PREPARED requires affirmative evidence that this candidate's pre-send marker never committed;
 * an old registration without that distinction is LEGACY_UNKNOWN, never inferred PREPARED.
 */
data class DirectAckAmbiguityWire(
    val radioId: RadioId,
    val claimRequestId: UUID,
    val wireRequestId: UUID,
    val predictedCode: UInt,
    val acceptedCode: UInt?,
    val emission: DirectAckEmissionEvidence,
)

enum class DirectAckNonsettlingReason {
    PRE_WIRE,
    UNKNOWN_TARGET_EMISSION,
    UNREGISTERED_CODE,
    KNOWN_CROSS_CLAIM_CODE,
    UNRESOLVED_PRIOR_EMISSION,
}

sealed interface DirectAckAmbiguityDecision {
    /** Absence of known ambiguity in supplied evidence, NOT permission or an origin witness. */
    data object NoKnownAmbiguity : DirectAckAmbiguityDecision

    data class Nonsettling(val reasons: SnapshotSet<DirectAckNonsettlingReason>) : DirectAckAmbiguityDecision
}

/**
 * Executable, nonactivated conformance candidate. No dispatch, mutation, expiry, or authority lookup.
 * A producer must independently validate current issuer/lease/incarnation/revision and capture ALL
 * relevant radio history atomically with settlement. Invalidation, retirement, reconnect and elapsed
 * time do not exclude an earlier possible emission. Missing history cannot be supplied as empty proof.
 * Same-valid-claim retries share a delivery target; a new claim is distinct even for equal payload
 * and incarnation. Legacy registered-only wires conservatively retain unknown emission evidence.
 *
 * Nonsettling leaves delivery unchanged; it does not prevent send, acceptance, or SendCompleted.
 * Confirmation uncertainty can remain indefinite. No production caller is wired to this candidate.
 */
object DirectAckAmbiguityCandidate {
    fun assess(
        target: DirectAckAmbiguityWire,
        ackCode: UInt,
        radioHistory: SnapshotList<DirectAckAmbiguityWire>,
    ): DirectAckAmbiguityDecision {
        val reasons = linkedSetOf<DirectAckNonsettlingReason>()
        when (target.emission) {
            DirectAckEmissionEvidence.PREPARED -> reasons.add(DirectAckNonsettlingReason.PRE_WIRE)
            DirectAckEmissionEvidence.LEGACY_UNKNOWN ->
                reasons.add(DirectAckNonsettlingReason.UNKNOWN_TARGET_EMISSION)
            DirectAckEmissionEvidence.POTENTIAL_EMISSION -> Unit
        }
        if (target.predictedCode != ackCode && target.acceptedCode != ackCode) {
            reasons.add(DirectAckNonsettlingReason.UNREGISTERED_CODE)
        }
        for (wire in radioHistory) {
            if (wire.radioId != target.radioId || wire.claimRequestId == target.claimRequestId) continue
            if (wire.emission == DirectAckEmissionEvidence.PREPARED) continue
            if (wire.acceptedCode == null) {
                reasons.add(DirectAckNonsettlingReason.UNRESOLVED_PRIOR_EMISSION)
            }
            if (wire.predictedCode == ackCode || wire.acceptedCode == ackCode) {
                reasons.add(DirectAckNonsettlingReason.KNOWN_CROSS_CLAIM_CODE)
            }
        }
        return if (reasons.isEmpty()) DirectAckAmbiguityDecision.NoKnownAmbiguity
        else DirectAckAmbiguityDecision.Nonsettling(SnapshotSet(reasons))
    }
}
