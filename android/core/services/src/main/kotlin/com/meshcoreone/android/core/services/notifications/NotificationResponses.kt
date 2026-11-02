// PortedFrom: MC1Services/Sources/MC1Services/Services/NotificationService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.notifications

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationStringProvider
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationActionDefinition
import com.meshcoreone.android.core.contracts.notifications.NotificationCategory
import com.meshcoreone.android.core.contracts.notifications.NotificationCategoryDefinition
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import java.util.UUID

/**
 * The Swift `userInfo` keys a payload carries. The Swift delegate methods test keys, not the
 * notification type, so the same key view drives presentation, response routing and removal.
 */
internal data class PayloadKeys(
    val contact: EntityKey?,
    val channelIndex: UByte?,
    val channelRadioId: RadioId?,
    val session: EntityKey?,
    val messageID: UUID?,
) {
    companion object {
        fun of(payload: NotificationPayload): PayloadKeys = when (payload) {
            is NotificationPayload.DirectMessage -> PayloadKeys(payload.contact, null, null, null, payload.messageID)
            is NotificationPayload.ChannelMessage ->
                PayloadKeys(null, payload.channelIndex, payload.radioId, null, payload.messageID)
            is NotificationPayload.RoomMessage -> PayloadKeys(null, null, null, payload.session, payload.messageID)
            is NotificationPayload.NewContact -> PayloadKeys(payload.contact, null, null, null, null)
            is NotificationPayload.Reaction -> {
                val hasChannel = payload.channelIndex != null && payload.radioId != null
                PayloadKeys(
                    payload.contact, payload.channelIndex.takeIf { hasChannel }, payload.radioId.takeIf { hasChannel },
                    null, payload.messageID,
                )
            }
            is NotificationPayload.LowBattery -> PayloadKeys(null, null, null, null, null)
            is NotificationPayload.QuickReplyFailed -> PayloadKeys(payload.contact, null, null, null, null)
            is NotificationPayload.ChannelQuickReplyFailed ->
                PayloadKeys(null, payload.channelIndex, payload.radioId, null, null)
        }
    }
}

/**
 * Swift `userNotificationCenter(_:willPresent:)`: false (no presentation options) when the payload
 * belongs to the conversation the user is viewing. Channels must match both index and radio.
 */
fun NotificationService.shouldPresentWhileForeground(payload: NotificationPayload): Boolean {
    val keys = PayloadKeys.of(payload)
    val active = activeConversation
    if (keys.contact != null && keys.contact.id == active.contactID) return false
    if (keys.channelIndex != null && keys.channelRadioId != null &&
        keys.channelIndex == active.channelIndex && keys.channelRadioId == active.channelRadioId
    ) return false
    if (keys.session != null && keys.session.id == active.roomSessionID) return false
    return true
}

/**
 * Swift `userNotificationCenter(_:didReceive:)`: routes a response to the installed callback.
 * A callback that is not installed drops the response, as Swift's optional call does. A callback
 * failure is contained; Swift callbacks cannot throw.
 */
suspend fun NotificationService.didReceive(response: NotificationResponse) {
    didReceiveWithResult(response)
}

enum class NotificationResponseDispatch {
    ACTION_HANDLED,
    TAP_CALLBACK_INVOKED,
    TAP_CALLBACK_MISSING,
}

/**
 * Reports whether a tap had a callback without changing the existing optional-callback behavior.
 * A callback failure is logged and reported as invoked; cancellation still propagates.
 */
suspend fun NotificationService.didReceiveWithResult(response: NotificationResponse): NotificationResponseDispatch {
    val keys = PayloadKeys.of(response.payload)
    return contained("didReceive", NotificationResponseDispatch.TAP_CALLBACK_INVOKED) {
        when (response.action) {
            NotificationAction.REPLY -> {
                val text = response.userText ?: return@contained NotificationResponseDispatch.ACTION_HANDLED
                if (keys.contact != null) {
                    onQuickReply?.invoke(keys.contact, text)
                } else if (keys.channelIndex != null && keys.channelRadioId != null) {
                    onChannelQuickReply?.invoke(keys.channelRadioId, keys.channelIndex, text)
                }
                NotificationResponseDispatch.ACTION_HANDLED
            }
            NotificationAction.MARK_READ -> {
                routeMarkRead(keys)
                NotificationResponseDispatch.ACTION_HANDLED
            }
            null -> {
                if (routeTap(response.payload, keys)) NotificationResponseDispatch.TAP_CALLBACK_INVOKED
                else NotificationResponseDispatch.TAP_CALLBACK_MISSING
            }
            NotificationAction.DISMISS -> NotificationResponseDispatch.ACTION_HANDLED
        }
    }
}

private suspend fun NotificationService.routeMarkRead(keys: PayloadKeys) {
    val messageID = keys.messageID ?: return
    if (keys.contact != null) {
        onMarkAsRead?.invoke(keys.contact, messageID)
    } else if (keys.channelIndex != null && keys.channelRadioId != null) {
        onChannelMarkAsRead?.invoke(keys.channelRadioId, keys.channelIndex, messageID)
    } else if (keys.session != null) {
        onRoomMarkAsRead?.invoke(keys.session, messageID)
    }
}

private suspend fun NotificationService.routeTap(payload: NotificationPayload, keys: PayloadKeys): Boolean {
    return if (payload is NotificationPayload.Reaction) {
        onReactionNotificationTapped?.let { it(keys.contact, keys.channelIndex, keys.channelRadioId, payload.messageID); true }
            ?: false
    } else if (keys.contact != null) {
        if (payload is NotificationPayload.NewContact) {
            onNewContactNotificationTapped?.let { it(keys.contact); true } ?: false
        } else {
            onNotificationTapped?.let { it(keys.contact); true } ?: false
        }
    } else if (keys.channelIndex != null && keys.channelRadioId != null) {
        onChannelNotificationTapped?.let { it(keys.channelRadioId, keys.channelIndex); true } ?: false
    } else if (keys.session != null) {
        onRoomNotificationTapped?.let { it(keys.session); true } ?: false
    } else false
}

/** Swift `registerCategories()`: the actions each category offers, with English fallbacks. */
internal fun notificationCategoryDefinitions(strings: NotificationStringProvider?): SnapshotList<NotificationCategoryDefinition> {
    val reply = NotificationActionDefinition(
        NotificationAction.REPLY, strings?.replyActionTitle ?: "Reply",
        textInputButtonTitle = strings?.sendButtonTitle ?: "Send",
        textInputPlaceholder = strings?.messagePlaceholder ?: "Message...",
    )
    val markRead = NotificationActionDefinition(
        NotificationAction.MARK_READ, strings?.markAsReadActionTitle ?: "Mark as Read",
        textInputButtonTitle = null, textInputPlaceholder = null,
    )
    return SnapshotList.of(
        NotificationCategoryDefinition(NotificationCategory.DIRECT_MESSAGE, SnapshotList.of(reply, markRead)),
        NotificationCategoryDefinition(NotificationCategory.CHANNEL_MESSAGE, SnapshotList.of(reply, markRead)),
        NotificationCategoryDefinition(NotificationCategory.ROOM_MESSAGE, SnapshotList.of(markRead)),
        NotificationCategoryDefinition(NotificationCategory.REACTION, SnapshotList.empty()),
        NotificationCategoryDefinition(NotificationCategory.LOW_BATTERY, SnapshotList.empty()),
    )
}
