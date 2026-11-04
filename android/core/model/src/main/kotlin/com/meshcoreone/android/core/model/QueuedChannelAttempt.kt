// AndroidOnly: WP-201 Durable queued channel submission and wire-attempt identities, not exactly-once delivery.
package com.meshcoreone.android.core.model

import java.util.UUID

enum class QueuedChannelDispatchState(val rawValue: Long) {
    READY(0), DISPATCHING(1), ACCEPTED(2), UNCERTAIN(3);
    companion object {
        fun fromRawValue(value: Long): QueuedChannelDispatchState? = entries.firstOrNull { it.rawValue == value }
    }
}

enum class QueuedChannelAttemptPhase(val rawValue: Long) {
    DISPATCHING(0), ACCEPTED(1), DEFINITELY_UNSENT(2), UNCERTAIN(3);
    companion object {
        fun fromRawValue(value: Long): QueuedChannelAttemptPhase? = entries.firstOrNull { it.rawValue == value }
    }
}

enum class QueuedChannelAttemptOutcome(val phase: QueuedChannelAttemptPhase, val submissionState: QueuedChannelDispatchState) {
    ACCEPTED(QueuedChannelAttemptPhase.ACCEPTED, QueuedChannelDispatchState.ACCEPTED),
    DEFINITELY_UNSENT(QueuedChannelAttemptPhase.DEFINITELY_UNSENT, QueuedChannelDispatchState.READY),
    UNCERTAIN(QueuedChannelAttemptPhase.UNCERTAIN, QueuedChannelDispatchState.UNCERTAIN),
}

data class QueuedChannelAttemptKey(val radioId: RadioId, val pendingID: UUID, val messageID: UUID, val attemptID: UUID)

data class QueuedChannelAttempt(
    val key: QueuedChannelAttemptKey,
    val channelIndex: UByte,
    val channelOccupantID: UUID,
    val wireTimestamp: UInt,
    val isResend: Boolean,
    val phase: QueuedChannelAttemptPhase,
    val isCompleted: Boolean,
)

enum class QueuedChannelAttemptConflict {
    MISSING_SUBMISSION, MISMATCHED_SUBMISSION, UNKNOWN_SUBMISSION, SUBMISSION_NOT_READY,
    ATTEMPT_ID_REUSED, MISSING_ATTEMPT, CONFLICTING_SETTLEMENT, NOT_ACCEPTED, RETAINED_SUBMISSION,
}
