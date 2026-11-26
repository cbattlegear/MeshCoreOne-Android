// PortedFrom: MC1/State/NavigationCoordinator.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: Navigation-only events preserve actual callback fields without fabricating message IDs.
package com.meshcoreone.android.app.navigation

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.ChannelNotificationTarget
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID

sealed interface NotificationNavigationRequest {
    val radioId: RadioId?

    data class Direct(val contact: EntityKey) : NotificationNavigationRequest {
        override val radioId: RadioId get() = contact.radioId
    }
    data class NewContact(val contact: EntityKey, val manualAddContacts: Boolean) : NotificationNavigationRequest {
        override val radioId: RadioId get() = contact.radioId
    }
    data class Channel(
        override val radioId: RadioId,
        val index: UByte,
        val messageId: UUID? = null,
        val displayTarget: ChannelNotificationTarget = ChannelNotificationTarget.Unproved,
        val messageIncarnation: String? = null,
    ) : NotificationNavigationRequest
    data class Reaction(
        val contact: EntityKey?, val channelIndex: UByte?, val channelRadioId: RadioId?, val messageId: UUID,
        val occurrenceId: UUID? = null,
        val directTargetIncarnation: String? = null,
        val directOccurrenceToken: String? = null,
    ) : NotificationNavigationRequest {
        // A legacy channel tap opens the local Chats list; it doesn't address a radio.
        override val radioId: RadioId? get() = contact?.radioId ?: channelRadioId.takeIf { occurrenceId != null }
    }
    data class Room(val session: EntityKey) : NotificationNavigationRequest {
        override val radioId: RadioId get() = session.radioId
    }
    data object Unsupported : NotificationNavigationRequest {
        override val radioId: RadioId? = null
    }

    companion object {
        fun fromPayload(payload: NotificationPayload, manualAddContacts: Boolean): NotificationNavigationRequest =
            when (payload) {
                is NotificationPayload.DirectMessage -> Direct(payload.contact)
                is NotificationPayload.QuickReplyFailed -> Direct(payload.contact)
                is NotificationPayload.NewContact -> NewContact(payload.contact, manualAddContacts)
                is NotificationPayload.ChannelMessage ->
                    Channel(payload.radioId, payload.channelIndex, payload.messageID, payload.displayTarget, payload.messageIncarnation)
                is NotificationPayload.ChannelQuickReplyFailed -> Channel(payload.radioId, payload.channelIndex)
                is NotificationPayload.Reaction -> Reaction(
                    payload.contact, payload.channelIndex, payload.radioId, payload.messageID, payload.occurrenceId,
                    payload.directTargetIncarnation,
                    payload.directOccurrenceToken,
                )
                is NotificationPayload.RoomMessage -> Room(payload.session)
                else -> Unsupported
            }
    }
}
