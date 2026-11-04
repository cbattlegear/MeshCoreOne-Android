// AndroidOnly: WP-209 Observable uncertainty without inventing radio-drain or reconciliation authority.
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.model.RadioId

/**
 * An observation, not write admission, an import result, or a token authorizing reconciliation.
 * Cached contacts can still be read. Required state applies only to [radioId]; reconnecting or
 * refreshing does not clear it. Presentation must not turn Clear into acknowledgement/navigation.
 */
sealed interface ContactWriteRecoveryState {
    val radioId: RadioId

    /** No recovery obligation is currently recorded; an exchange may still be in progress. */
    data class Clear(override val radioId: RadioId) : ContactWriteRecoveryState

    data class Required(
        override val radioId: RadioId,
        val reason: ContactWriteRecoveryReason,
        val markerRecording: ContactWriteMarkerRecording,
        /** Describes exact accepted writes only; it never implies every radio obligation is settled. */
        val localCompletion: ContactWriteLocalCompletion = ContactWriteLocalCompletion.UNAVAILABLE,
    ) : ContactWriteRecoveryState
}

enum class ContactWriteRecoveryReason {
    /** A dispatched change may have reached the radio; cancellation did not roll it back. */
    RADIO_OUTCOME_UNCERTAIN,
    /** Acceptance is known, but this observation cannot authorize settlement of local state. */
    RADIO_ACCEPTED_RECONCILIATION_REQUIRED,
    /** A terminal rejection is known, but its recorded uncertainty has not yet been settled. */
    RADIO_REJECTION_SETTLEMENT_PENDING,
    /** The dispatch is proven unsent, but its recorded uncertainty has not yet been settled. */
    RADIO_UNSENT_SETTLEMENT_PENDING,
    /** Every owned uncertainty was settled, but the durable false-marker commit is pending. */
    MARKER_CLEAR_PENDING,
    /** A recovery obligation exists without a dispatch receipt identifying its radio result. */
    OPERATION_OUTCOME_UNAVAILABLE,
    /** A true preference was observed without this process's original exchange provenance. */
    STORED_MARKER_WITHOUT_PROVENANCE,
}

enum class ContactWriteMarkerRecording {
    /** The preference role reported success; this is not independent disk/process-death evidence. */
    SET_SUCCEEDED,
    /** A true-marker write failed; even a true in-memory preference is not durable proof. */
    RETRY_PENDING,
    /** A false-marker write failed after exact settlement proof; retry is read/write-access driven. */
    CLEAR_RETRY_PENDING,
    /** A flag was read, but this process did not witness its successful recording. */
    LOADED_FLAG_UNVERIFIED,
}

/** Local completion evidence for accepted dispatches only, never for the whole radio obligation. */
enum class ContactWriteLocalCompletion {
    /** No accepted-dispatch completion evidence is available. */
    UNAVAILABLE,
    /** At least one exact accepted dispatch still lacks successful local completion. */
    PENDING,
    /** Some accepted dispatches completed, but another owner/provenance remains unresolved. */
    PARTIAL,
    /** Every known accepted dispatch completed locally; other uncertainty can still remain. */
    COMPLETE,
}
