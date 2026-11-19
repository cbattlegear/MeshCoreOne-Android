// AndroidOnly: WP-214 Transactional proof of an incoming reaction summary mutation.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageDTO
import java.util.UUID

data class ReactionSummaryMutation(
    val operation: EntityKey,
    val messageID: UUID,
    val targetIncarnation: String,
    val summary: String?,
)

data class CommittedReactionSummary(val mutation: ReactionSummaryMutation, val target: MessageDTO)

/** Optional store authority; absence of this capability or a receipt never proves an uncertain update failed. */
interface ReactionSummaryReceipting {
    /** Commits the update and its immutable operation receipt in the same transaction. */
    suspend fun updateReactionSummary(mutation: ReactionSummaryMutation)
    /** Returns proof only while the original message incarnation and channel occupant remain current. */
    suspend fun fetchCommittedReactionSummary(operation: EntityKey): CommittedReactionSummary?
}
