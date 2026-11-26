// PortedFrom: MC1/Views/Chats/Navigation/ChatRoute.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import java.util.UUID

/** Navigation target out of the chat list. Equality and hashing use kind + conversation id only. */
sealed interface ChatRoute {
    enum class Kind { DIRECT, CHANNEL, ROOM }

    val kind: Kind
    val conversationID: UUID

    data class Direct(val contact: ContactDTO) : ChatRoute {
        override val kind get() = Kind.DIRECT
        override val conversationID: UUID get() = contact.id
        override fun equals(other: Any?) = other is ChatRoute && kind == other.kind && conversationID == other.conversationID
        override fun hashCode() = 31 * kind.hashCode() + conversationID.hashCode()
    }

    data class Channel(val channel: ChannelDTO) : ChatRoute {
        override val kind get() = Kind.CHANNEL
        override val conversationID: UUID get() = channel.id
        override fun equals(other: Any?) = other is ChatRoute && kind == other.kind && conversationID == other.conversationID
        override fun hashCode() = 31 * kind.hashCode() + conversationID.hashCode()
    }

    data class Room(val session: RemoteNodeSessionDTO) : ChatRoute {
        override val kind get() = Kind.ROOM
        override val conversationID: UUID get() = session.id
        override fun equals(other: Any?) = other is ChatRoute && kind == other.kind && conversationID == other.conversationID
        override fun hashCode() = 31 * kind.hashCode() + conversationID.hashCode()
    }

    /** Conversation payload to prefetch, or null for rooms (separate view with its own load path). */
    val chatConversationType: ChatConversationType?
        get() = when (this) {
            is Direct -> ChatConversationType.Dm(contact)
            is Channel -> ChatConversationType.Channel(channel)
            is Room -> null
        }

    val roomIsConnected: Boolean? get() = (this as? Room)?.session?.isConnected

    fun toConversation(): Conversation = when (this) {
        is Direct -> Conversation.Direct(contact)
        is Channel -> Conversation.Channel(channel)
        is Room -> Conversation.Room(session)
    }

    /** The same route carrying the latest payload, or null when the conversation no longer exists. */
    fun refreshedPayload(conversations: List<Conversation>): ChatRoute? =
        conversations.map(::from).firstOrNull { it.kind == kind && it.conversationID == conversationID }

    companion object {
        fun from(conversation: Conversation): ChatRoute = when (conversation) {
            is Conversation.Direct -> Direct(conversation.contact)
            is Conversation.Channel -> Channel(conversation.channel)
            is Conversation.Room -> Room(conversation.session)
        }
    }
}
