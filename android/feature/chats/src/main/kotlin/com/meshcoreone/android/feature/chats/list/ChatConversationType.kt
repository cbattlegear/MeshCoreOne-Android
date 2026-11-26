// PortedFrom: MC1/Views/Chats/ChatConversationType.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID

/** Conversation discriminator for the unified chat view (timeline/composer live in WP-307/308). */
sealed interface ChatConversationType {
    data class Dm(val contact: ContactDTO) : ChatConversationType
    data class Channel(val channel: ChannelDTO) : ChatConversationType

    fun navigationTitle(strings: ChatListStrings): String = when (this) {
        is Dm -> contact.displayName
        is Channel -> channel.displayName(strings)
    }

    /** Empty means the header's second line is hidden (an unresolved region shows nothing). */
    fun navigationSubtitle(strings: ChatListStrings, deviceDefaultFloodScopeName: String?): String = when (this) {
        is Dm -> if (contact.isFloodRouted) strings.floodRouting() else strings.directHops(contact.pathHopCount)
        is Channel -> {
            val region = effectiveFloodRegionName(channel.floodScope, deviceDefaultFloodScopeName)
            when (region) {
                null -> ""
                else -> strings.headerRegion(
                    if (region == deviceDefaultFloodScopeName) strings.scopedDefault(region) else region,
                )
            }
        }
    }

    val conversationID: UUID
        get() = when (this) {
            is Dm -> contact.id
            is Channel -> channel.id
        }

    /** Draft-store key; channels key on the slot index (not the row UUID) to match slot-based cleanup. */
    val draftConversationID: ChatConversationID
        get() = when (this) {
            is Dm -> ChatConversationID.dm(contact.radioId, contact.id)
            is Channel -> ChatConversationID.channel(channel.radioId, channel.index)
        }

    /** Coincides with [draftConversationID] today; named separately so the namespaces can diverge. */
    val coordinatorID: ChatConversationID get() = draftConversationID

    val radioId: RadioId
        get() = when (this) {
            is Dm -> contact.radioId
            is Channel -> channel.radioId
        }

    val unreadCount: Long
        get() = when (this) {
            is Dm -> contact.unreadCount
            is Channel -> channel.unreadCount
        }

    val isPublicStyleChannel: Boolean get() = (this as? Channel)?.channel?.isEncryptedChannel == false

    /** Channels named "wardriving" (case-insensitive, optional leading "#") suppress inline map previews. */
    val suppressesMapPreviews: Boolean
        get() {
            val channel = (this as? Channel)?.channel ?: return false
            val trimmed = channel.name.trim()
            val normalized = if (trimmed.startsWith("#")) trimmed.drop(1) else trimmed
            return ChatTextMatching.caseInsensitiveEquals(normalized, MAP_PREVIEW_SUPPRESSED_CHANNEL_NAME)
        }

    fun replacingContact(contact: ContactDTO): ChatConversationType = if (this is Dm) Dm(contact) else this
    fun replacingChannel(channel: ChannelDTO): ChatConversationType = if (this is Channel) Channel(channel) else this

    private companion object {
        const val MAP_PREVIEW_SUPPRESSED_CHANNEL_NAME = "wardriving"
    }
}

/**
 * Region the header shows: mirrors `ChannelFloodScopeResolver.resolve(..., supportsUnscopedFloodSend = false)`
 * reduced to its only displayable outcome (an explicit or inherited region).
 */
internal fun effectiveFloodRegionName(scope: ChannelFloodScope, deviceDefaultFloodScopeName: String?): String? = when (scope) {
    ChannelFloodScope.Inherit -> deviceDefaultFloodScopeName?.takeIf { it.isNotEmpty() }
    ChannelFloodScope.AllRegions -> null
    is ChannelFloodScope.Region -> scope.name
}
