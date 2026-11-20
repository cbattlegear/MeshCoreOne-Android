// AndroidOnly: WP-215 Direct timeline snapshots include storage-issued message incarnations.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.SnapshotList
import java.time.Instant

interface DirectTimelinePersisting {
    suspend fun fetchDirectTimeline(contact: EntityKey, limit: Long, offset: Long): SnapshotList<MessageDTO>
    suspend fun fetchDirectTimelineWindow(contact: EntityKey, anchorSortDate: Instant?, floorLimit: Long): MessageWindow
    suspend fun fetchDirectTimelineMessage(contact: EntityKey, message: EntityKey): MessageDTO?
}
