// AndroidOnly: WP-201 Atomic exact-submission retirement after a proved definitely-unsent attempt.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.QueuedChannelAttemptKey

data class QueuedChannelRetirement(val status: MessageStatus, val statusChanged: Boolean)

/**
 * Independent support role, not a default implementation on every MessagePersisting consumer.
 * The exact latest attempt must be definitely unsent and its submission READY without siblings.
 * Status and exact pending deletion commit together; callers must not write FAILED afterward.
 * A lost return or repeated missing submission is a typed failure, not a fabricated new receipt.
 */
interface QueuedChannelTerminalPersisting {
    suspend fun retireDefinitelyUnsentQueuedChannelAttempt(key: QueuedChannelAttemptKey): QueuedChannelRetirement

    /**
     * The caller supplies genuine definitely-unsent proof for the exact latest DISPATCHING attempt.
     * Settlement, pending deletion and FAILED-unless-DELIVERED commit together, never exposing READY.
     * Failure rolls back to DISPATCHING; a missing row or lost return cannot mint another receipt.
     */
    suspend fun settleAndRetireDefinitelyUnsentQueuedChannelAttempt(key: QueuedChannelAttemptKey): QueuedChannelRetirement
}
