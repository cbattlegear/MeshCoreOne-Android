// AndroidOnly: WP-214 Durable classified channel-reaction custody and conditional queue transitions.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import java.time.Instant
import java.util.UUID

enum class ReactionMutationPhase { BINDING, SAVE, SUMMARY }

data class ClassifiedReactionWork(
    val occurrenceID: UUID,
    val radioId: RadioId,
    val parsed: ParsedReaction,
    val channelIndex: UByte,
    val senderName: String,
    val rawText: String,
    val receivedAt: Instant,
    val localNodeName: String?,
    val deliveryContext: DeliveryContext?,
    val keyOrder: Long,
    val entryOrder: Long,
    val queued: Boolean,
    val targetID: UUID?,
    val targetIncarnation: String?,
    val notificationPending: Boolean,
    val mutation: ReactionMutationPhase?,
)

data class ReactionQueueSnapshot(val version: UUID?, val entries: SnapshotList<ClassifiedReactionWork>)

class ReactionQueueConflictException : Exception("Reaction queue transition does not match committed custody")
class ReactionQueueCapacityException(val limit: Int) : Exception("Durable reaction custody exceeds $limit entries")

interface ReactionQueuePersisting {
    /** Process-owned listener; deletion revokes queued work, including unconfirmed memory transitions. */
    fun addRadioDeletionListener(listener: (RadioId) -> Unit)

    suspend fun fetchReactionQueue(): ReactionQueueSnapshot

    /** Atomically replace native custody, including removal of completed/evicted work; reject a stale writer. */
    suspend fun replaceReactionQueue(expectedVersion: UUID?, next: ReactionQueueSnapshot)

    companion object {
        const val MAX_QUEUED = 100
        const val MAX_RETAINED = 200
    }
}
