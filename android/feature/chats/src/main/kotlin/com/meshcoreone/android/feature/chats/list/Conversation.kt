// PortedFrom: MC1/Models/Conversation.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Models/ChatFilter.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Models/Conversation+Filtering.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ConversationSnapshot.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import java.time.Instant
import java.util.UUID

/** A row in the chat list: direct chat, channel, or room. */
sealed interface Conversation {
    val id: UUID
    val lastMessageDate: Instant?
    val unreadCount: Long
    val notificationLevel: NotificationLevel
    val isFavorite: Boolean
    val isMuted: Boolean get() = notificationLevel == NotificationLevel.MUTED
    val isUnread: Boolean get() = unreadCount > 0 || when (this) {
        is Direct -> contact.unreadMentionCount > 0
        is Channel -> channel.unreadMentionCount > 0
        is Room -> false
    }

    fun displayName(strings: ChatListStrings): String

    data class Direct(val contact: ContactDTO) : Conversation {
        override val id: UUID get() = contact.id
        override val lastMessageDate: Instant? get() = contact.lastMessageDate
        override val unreadCount: Long get() = contact.unreadCount
        override val notificationLevel: NotificationLevel get() = if (contact.isMuted) NotificationLevel.MUTED else NotificationLevel.ALL
        override val isFavorite: Boolean get() = contact.isFavorite
        override fun displayName(strings: ChatListStrings): String = contact.displayName
    }

    data class Channel(val channel: ChannelDTO) : Conversation {
        override val id: UUID get() = channel.id
        override val lastMessageDate: Instant? get() = channel.lastMessageDate
        override val unreadCount: Long get() = channel.unreadCount
        override val notificationLevel: NotificationLevel get() = channel.notificationLevel
        override val isFavorite: Boolean get() = channel.isFavorite
        override fun displayName(strings: ChatListStrings): String = channel.displayName(strings)
    }

    data class Room(val session: RemoteNodeSessionDTO) : Conversation {
        override val id: UUID get() = session.id
        override val lastMessageDate: Instant? get() = session.lastMessageDate
        override val unreadCount: Long get() = session.unreadCount
        override val notificationLevel: NotificationLevel get() = session.notificationLevel
        override val isFavorite: Boolean get() = session.isFavorite
        override fun displayName(strings: ChatListStrings): String = session.name
    }
}

/** Localized channel name with the index-based fallback (`ChannelDTO+DisplayName.swift`). */
fun ChannelDTO.displayName(strings: ChatListStrings): String =
    name.ifEmpty { strings.channelDefaultName(index.toInt()) }

/** Filter options for the Chats list; [sourceName] is the Swift raw value. */
enum class ChatFilter(val sourceName: String) {
    ALL("all"), UNREAD("unread"), DIRECT_MESSAGES("directMessages"), CHANNELS("channels"), ROOMS("rooms"),
}

/**
 * Filters by category and search text. A non-empty search ignores the selected filter and searches
 * every conversation by display name.
 */
fun List<Conversation>.filtered(filter: ChatFilter, searchText: String, strings: ChatListStrings): List<Conversation> {
    if (searchText.isNotEmpty()) return filter { ChatTextMatching.standardContains(it.displayName(strings), searchText) }
    return when (filter) {
        ChatFilter.ALL -> this
        ChatFilter.UNREAD -> filter { it.isUnread && !it.isMuted }
        ChatFilter.DIRECT_MESSAGES -> filter { it is Conversation.Direct }
        ChatFilter.CHANNELS -> filter { it is Conversation.Channel }
        ChatFilter.ROOMS -> filter { it is Conversation.Room }
    }
}

/** Complete, internally consistent favorite/other split, committed as a single value. */
data class ConversationSnapshot(val favorites: List<Conversation>, val others: List<Conversation>) {
    val all: List<Conversation> get() = favorites + others

    companion object {
        val EMPTY = ConversationSnapshot(emptyList(), emptyList())
    }
}
