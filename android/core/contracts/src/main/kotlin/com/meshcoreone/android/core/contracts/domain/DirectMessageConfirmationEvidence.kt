// AndroidOnly: WP-201 Read-only persisted message confirmation diagnostics.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.SnapshotSet

/**
 * Persisted incarnation and mutation revision observed with the current row. Diagnostic identity
 * only: public construction/equality confers no authority and no mutation API accepts this value.
 * Reopen retains persisted identity; replacement changes incarnation and mutation changes revision.
 */
data class DirectMessageConfirmationIdentity(
    val incarnationId: String,
    val revision: Long,
) {
    init {
        require(incarnationId.length == 64 && incarnationId.all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
        }) { "Diagnostic incarnation must preserve the stored 64-character hexadecimal identity" }
        require(revision >= 0) { "Diagnostic revision must be nonnegative" }
    }
}

enum class DirectMessageConfirmationUncertaintyReason {
    TARGET_PROVENANCE_UNPROVEN,
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

sealed interface DirectMessageConfirmationEvidence {
    val key: EntityKey

    /** The actual current message row was absent in this atomic read, not a proof of no send. */
    data class Missing(override val key: EntityKey) : DirectMessageConfirmationEvidence

    /** The actual current row is not an outgoing direct message. No delivery claim is made. */
    data class NotEligible(override val key: EntityKey) : DirectMessageConfirmationEvidence

    /**
     * Historical atomic current-row observation, never a settlement receipt or no-write proof.
     * Preserve actual DELIVERED status; reasons must not downgrade it or invent an own ACK tuple.
     * Positive known uncertainty can coexist with unproven provenance. There is no green variant.
     */
    data class Present(
        override val key: EntityKey,
        val identity: DirectMessageConfirmationIdentity,
        val status: MessageStatus,
        val reasons: SnapshotSet<DirectMessageConfirmationUncertaintyReason>,
    ) : DirectMessageConfirmationEvidence {
        init {
            require(reasons.isNotEmpty()) { "Message confirmation evidence requires uncertainty reasons" }
        }
    }
}

/**
 * Separate optional diagnostic capability; no attempt, lease, claim or ACK code is reconstructed.
 * One read transaction binds the actual current outgoing DM, its persisted incarnation/revision
 * and status to complete retained ancestry on that exact radio/message/incarnation. Assess every
 * target claim's valid accepted physical actuals AND genuine retained registered-wire accepted
 * bindings against every different claim's retained known predicted/actual codes, including other
 * claims for the same message. Legacy accepted actuals remain diagnostic evidence with legacy and
 * unproven reasons, never eligibility. Target predictions and the public DTO ACK are not actual
 * bindings. Include all applicable
 * target/foreign unresolved, conflicting, legacy and sticky-saturation reasons. Retired leases,
 * erased claim payloads and noncurrent grants must not hide retained positive collision evidence.
 * Use bounded SQL EXISTS/indexed aggregates, not capped samples or unbounded materialized histories.
 * A tracked wire's accepted binding without a valid matching physical ACCEPTED row for that exact
 * wire/code contributes the corresponding target/foreign conflicting reason. It is not discarded
 * as an uninvoked wire or blessed by a different valid target wire. Preserve the actual binding
 * as diagnostic code evidence, without upgrading settlement eligibility or repairing any rows.
 *
 * Current storage has no genuine birth/no-write proof. Every Present therefore includes
 * TARGET_PROVENANCE_UNPROVEN, even when other known reasons exist or status is DELIVERED. Empty
 * claims/wires, a public FAILED row, attempt counts, known codes or a complete scan of retained SQL
 * rows cannot certify complete physical history. Legacy/preclaim/imported and pre-authority
 * migration history remain unproven; deleted historic rows cannot be inferred never emitted.
 * Independently include PREAUTHORITY_HISTORY_UNKNOWN whenever the durable DB-wide preauthority
 * marker is set, even for radios with no surviving old rows. Read it atomically, not as a cached
 * service flag or synthetic wire/saturation. It can coexist with every other uncertainty reason.
 * No future green/no-conflict or sufficient-provenance behavior is admitted by this interface.
 *
 * Missing and NotEligible describe actual current rows, not query failure. Missing/malformed
 * persisted identity or malformed stored evidence uses existing typed storage failure paths,
 * never Missing, an empty result or an apparent clear. Errors/closure/cancellation propagate
 * unchanged. Missing capability is explicitly unavailable. No mutation, repair, authority renewal,
 * fake excluded claim, settlement-as-read or fabricated ACK is permitted.
 *
 * Each call rebinds the current persisted incarnation atomically. Consumers fence request/graph/
 * session/radio and diagnostic incarnation/revision; key or DTO equality cannot bind a timeline
 * row to that incarnation. Replacement discards old identity-specific state. Unknown binding,
 * missing evidence and read failure remain unavailable/unconfirmed, not definitely unsent/FAILED.
 * Every observation can immediately become stale. This suspend snapshot supplies no event stream,
 * cache, polling worker or restart authority; consumers must separately own any serial requery.
 */
interface DirectMessageConfirmationEvidenceInspecting {
    suspend fun inspectDirectMessageConfirmationEvidence(
        key: EntityKey,
    ): DirectMessageConfirmationEvidence
}
