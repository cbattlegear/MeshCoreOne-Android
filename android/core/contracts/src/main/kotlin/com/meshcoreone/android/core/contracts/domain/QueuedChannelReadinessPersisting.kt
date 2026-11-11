// AndroidOnly: WP-201 Exact READY channel submission accounting, distinct from physical wire-attempt claims.
package com.meshcoreone.android.core.contracts.domain

import java.util.UUID

interface QueuedChannelReadinessPersisting {
    // One invocation per consumer drain, not per physical backoff attempt. A lost return is not permission to bump again.
    suspend fun incrementReadyQueuedChannelAttemptCount(pending: EntityKey, messageID: UUID): Long
}
