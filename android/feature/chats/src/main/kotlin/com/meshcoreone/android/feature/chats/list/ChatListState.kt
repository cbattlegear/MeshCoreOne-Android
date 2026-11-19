// AndroidOnly: WP-306 Immutable list state replacing the @Observable ChatViewModel buffers.
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import java.util.UUID

/** Text a banner/alert can carry before the Compose layer resolves it to localized copy. */
sealed interface ChatListMessage {
    data object LoadConversationsFailed : ChatListMessage
    data class Verbatim(val text: String) : ChatListMessage

    /** Presented through the app's shared error mapper. */
    data class Failure(val cause: Throwable) : ChatListMessage
}

/** A channel deletion that failed, surfaced in a retry alert. */
data class ChannelDeleteFailure(val channel: ChannelDTO, val message: ChatListMessage)

data class ChatListState(
    val contacts: List<ContactDTO> = emptyList(),
    val channels: List<ChannelDTO> = emptyList(),
    val roomSessions: List<RemoteNodeSessionDTO> = emptyList(),
    val pendingRemovalIds: Set<UUID> = emptySet(),
    val pendingChannelRemovals: List<ChannelDTO> = emptyList(),
    val deletingIds: Set<UUID> = emptySet(),
    val togglingFavoriteId: UUID? = null,
    val lastMessages: Map<UUID, MessageDTO> = emptyMap(),
    val failedSendIds: Set<UUID> = emptySet(),
    val snapshot: ConversationSnapshot = ConversationSnapshot.EMPTY,
    val snapshotGeneration: Int = 0,
    val hasLoadedOnce: Boolean = false,
    val isLoading: Boolean = false,
    val errorBanner: ChatListMessage? = null,
    val errorAlert: ChatListMessage? = null,
    val channelDeleteFailure: ChannelDeleteFailure? = null,
    val roomToDelete: RemoteNodeSessionDTO? = null,
    val roomToAuthenticate: RemoteNodeSessionDTO? = null,
    val searchText: String = "",
    val selectedFilter: ChatFilter = ChatFilter.ALL,
) {
    val favoriteConversations: List<Conversation> get() = snapshot.favorites
    val nonFavoriteConversations: List<Conversation> get() = snapshot.others
    val allConversations: List<Conversation> get() = snapshot.all

    fun lastMessagePreview(id: UUID): String? = lastMessages[id]?.text
    fun conversationHasFailedSend(id: UUID): Boolean = id in failedSendIds
    fun isDeletePending(id: UUID): Boolean = id in pendingRemovalIds || id in deletingIds
}
