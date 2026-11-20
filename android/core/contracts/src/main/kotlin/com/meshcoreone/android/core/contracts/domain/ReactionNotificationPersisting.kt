// AndroidOnly: WP-215 Storage-issued custody of committed reaction notification requests.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import java.util.UUID

data class PendingReactionNotification(val occurrence: EntityKey, val messageID: UUID, val token: String)

interface ReactionNotificationPersisting {
    suspend fun pendingReactionNotifications(radioId: RadioId): SnapshotList<PendingReactionNotification>
    suspend fun fetchPendingReactionNotification(work: PendingReactionNotification): CommittedReaction?
    /** Conditional and idempotent: an old consumer cannot acknowledge a new same-ID admission. */
    suspend fun acknowledgeReactionNotification(work: PendingReactionNotification)
}
