// AndroidOnly: WP-201 Explicit copy-only recovery of retained channel drafts without origin adoption.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList

/**
 * Separate from active chat history and send authority. Lists outgoing channel drafts whose origin
 * is unknown or cannot be proved against one enabled, resolved current channel occupant.
 * Pending/sending/retrying/failed rows and rows with a retained pending send are recoverable.
 *
 * Results preserve the original ID, text and nullable occupant proof. Reading never adopts, deletes,
 * changes status or enqueues a row. Explicit copy creates a new intent in the user's selected chat;
 * it must not stamp or retry the recovered row. Re-read its EntityKey before copying if needed.
 */
interface ChannelDraftRecoveryPersisting {
    /**
     * Filters by radio and recovery eligibility before pagination. Newest creation date first,
     * then ascending stored UUID for ties; no sender clustering or selected-slot fallback.
     * Limit/offset must be nonnegative; zero limit returns no rows. Each call is a database snapshot,
     * not a stable multi-call cursor or a carried display/dispatch witness.
     */
    suspend fun fetchRecoverableChannelDrafts(
        radioId: RadioId, limit: Long = 50, offset: Long = 0,
    ): SnapshotList<MessageDTO>
}
