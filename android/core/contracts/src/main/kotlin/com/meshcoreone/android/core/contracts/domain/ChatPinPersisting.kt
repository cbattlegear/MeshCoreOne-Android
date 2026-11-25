// AndroidOnly: WP-306 Phone-local chat pin writes, independent of radio favorite commands.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO

interface ChatPinPersisting {
    suspend fun setContactPinned(expected: ContactDTO, isPinned: Boolean)
    suspend fun setChannelPinned(expected: ChannelDTO, isPinned: Boolean)
    suspend fun setRoomPinned(expected: RemoteNodeSessionDTO, isPinned: Boolean)
}
