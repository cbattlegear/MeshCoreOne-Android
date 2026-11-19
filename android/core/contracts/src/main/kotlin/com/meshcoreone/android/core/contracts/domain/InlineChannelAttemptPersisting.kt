// AndroidOnly: WP-201 Atomic durable admission for an immediate, unscheduled channel wire attempt.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.PendingSendDTO
import com.meshcoreone.android.core.model.QueuedChannelAttempt
import com.meshcoreone.android.core.model.QueuedChannelAttemptKey
import java.util.UUID

/**
 * Insert a fresh exact submission and its DISPATCHING attempt in one transaction, never as READY.
 * The caller still needs the actual session/occupant fence and may enter the wire only after return.
 * A failed transaction inserts neither row; a lost return may retain DISPATCHING, not retry permission.
 * Existing or conflicting identities fail rather than adopting, overwriting or replaying old intent.
 */
interface InlineChannelAttemptPersisting {
    suspend fun beginInlineChannelAttempt(
        submission: PendingSendDTO,
        key: QueuedChannelAttemptKey,
        expectedOccupantID: UUID,
        wireTimestamp: UInt,
    ): QueuedChannelAttempt
}
