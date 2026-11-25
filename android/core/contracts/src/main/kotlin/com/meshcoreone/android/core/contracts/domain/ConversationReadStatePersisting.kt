// AndroidOnly: WP-306 Explicit conversation-list read intent, adapted from upstream 122d6af without changing the pinned reference.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.ChannelDTO

/** Changes counters only, never message read/mention/failure flags. Available without a radio connection. */
interface ConversationReadStatePersisting {
    suspend fun setContactReadState(key: EntityKey, isRead: Boolean)
    suspend fun setChannelReadState(expected: ChannelDTO, isRead: Boolean)
    suspend fun setRoomReadState(key: EntityKey, isRead: Boolean)
}
