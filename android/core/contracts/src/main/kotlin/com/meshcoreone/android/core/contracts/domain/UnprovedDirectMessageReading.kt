// AndroidOnly: WP-201 Radio-partitioned, read-only presentation of unproved direct receive occurrences.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import java.time.Instant
import java.util.UUID

enum class UnprovedDirectMessageProvenance {
    UNRESOLVED,
    LOCAL_CANDIDATE,
    LEGACY_UNKNOWN,
}

/** No sender, contact-navigation, reply or dispatch authority is carried by this projection. */
data class UnprovedDirectMessage(
    val occurrenceID: UUID,
    val radioId: RadioId,
    val text: String,
    /** Original wire seconds, including zero; null when a correction lost the original value. */
    val wireTimestamp: UInt?,
    /** Recorded receive time for a fenced occurrence; legacy creation time is not receive evidence. */
    val receivedAt: Instant?,
    val provenance: UnprovedDirectMessageProvenance,
)

interface UnprovedDirectMessageReading {
    /**
     * Includes marked local candidates and unresolved rows, plus incoming legacy null-contact rows.
     * Newest stored sort date first with a stable occurrence-ID tie-breaker. Invalid paging and
     * storage errors throw; an empty page is not an error fallback. Reading never adopts or marks read.
     */
    suspend fun fetchUnprovedDirectMessages(
        radioId: RadioId,
        limit: Long = 50,
        offset: Long = 0,
    ): SnapshotList<UnprovedDirectMessage>
}
