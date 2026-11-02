// PortedFrom: MC1Services/Sources/MC1Services/Services/NotificationService.swift@db14559b39d32322b06477c6ae676112f583db50
// Neutral delivery seam between WP-215 notification policy (core:services) and the WP-401 platform adapter.
package com.meshcoreone.android.core.contracts.notifications

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import java.util.UUID

/** Swift `NotificationCategory`: which actions a posted notification offers. */
enum class NotificationCategory(val rawValue: String) {
    DIRECT_MESSAGE("DIRECT_MESSAGE"),
    CHANNEL_MESSAGE("CHANNEL_MESSAGE"),
    ROOM_MESSAGE("ROOM_MESSAGE"),
    REACTION("REACTION"),
    LOW_BATTERY("LOW_BATTERY"),
}

/** Swift `NotificationAction`: the identifiers of the actions a category offers. */
enum class NotificationAction(val rawValue: String) {
    REPLY("REPLY_ACTION"),
    MARK_READ("MARK_READ_ACTION"),
    DISMISS("DISMISS_ACTION"),
}

/**
 * Typed replacement for the Swift `userInfo` dictionary. The delivery adapter stores it with the
 * posted notification and hands it back in [DeliveredNotification] and [NotificationResponse].
 * Conversation identifiers carry their radio ([EntityKey]/[RadioId]) because Android rows are
 * partitioned by radio, so an action can never be resolved against another radio's rows.
 */
sealed interface NotificationPayload {
    /** `type: directMessage` with `contactID` and `messageID`. */
    data class DirectMessage(val contact: EntityKey, val messageID: UUID) : NotificationPayload

    /** `type: channelMessage` with `channelIndex`, `radioID` and `messageID`. */
    data class ChannelMessage(val radioId: RadioId, val channelIndex: UByte, val messageID: UUID) : NotificationPayload

    /** `type: roomMessage` with `roomName`, `sessionID` and `messageID`. */
    data class RoomMessage(val roomName: String, val session: EntityKey, val messageID: UUID) : NotificationPayload

    /** `type: newContact` with `contactID`. */
    data class NewContact(val contact: EntityKey) : NotificationPayload

    /** `type: reaction` with `messageID` and either `contactID` or `channelIndex` plus `radioID`. */
    data class Reaction(
        val messageID: UUID,
        val contact: EntityKey?,
        val channelIndex: UByte?,
        val radioId: RadioId?,
    ) : NotificationPayload

    /** `type: lowBattery` with `batteryPercentage`. */
    data class LowBattery(val batteryPercentage: Long) : NotificationPayload

    /** `type: quickReplyFailed` with `contactID`. */
    data class QuickReplyFailed(val contact: EntityKey) : NotificationPayload

    /** `type: channelQuickReplyFailed` with `channelIndex` and `radioID`. */
    data class ChannelQuickReplyFailed(val radioId: RadioId, val channelIndex: UByte) : NotificationPayload
}

/**
 * One notification to deliver (Swift `UNNotificationRequest` plus its content). [badge] is the
 * badge number to show with it, or null to leave the badge alone; [threadIdentifier] groups
 * notifications of one conversation.
 */
data class NotificationRequest(
    val id: NotificationId,
    val category: NotificationCategory?,
    val title: String,
    val body: String,
    val soundEnabled: Boolean,
    val badge: Long?,
    val threadIdentifier: String?,
    val payload: NotificationPayload,
)

/** A notification still shown by the platform. [payload] is null for notifications this policy did not post. */
data class DeliveredNotification(
    val id: NotificationId,
    val payload: NotificationPayload?,
    val postIdentity: DeliveredPostIdentity? = null,
)

/** Android concurrency adaptation: opaque identity of one submitted platform post, not a logical ID. */
data class DeliveredPostIdentity(val value: UUID)

enum class SnapshotRemovalOutcome { REMOVED, SUPERSEDED, UNKNOWN_IDENTITY }

/** The user's interaction with a posted notification; a null [action] is the default tap. */
data class NotificationResponse(val payload: NotificationPayload, val action: NotificationAction?, val userText: String?)

/** Platform permission state for posting notifications. */
enum class NotificationAuthorizationStatus { NOT_DETERMINED, DENIED, AUTHORIZED }

/** One action button; the text-input titles are set only for an inline-reply action. */
data class NotificationActionDefinition(
    val action: NotificationAction,
    val title: String,
    val textInputButtonTitle: String?,
    val textInputPlaceholder: String?,
)

/** The actions offered by one [NotificationCategory]. */
data class NotificationCategoryDefinition(
    val category: NotificationCategory,
    val actions: SnapshotList<NotificationActionDefinition>,
)

/**
 * Platform delivery (Swift `UNUserNotificationCenter`). Implemented by the WP-401 process adapter.
 * Members may throw; the WP-215 policy contains every failure so notification problems never
 * block messaging.
 */
interface NotificationDeliveryPort {
    suspend fun authorizationStatus(): NotificationAuthorizationStatus
    suspend fun requestAuthorization(): Boolean
    suspend fun registerCategories(categories: SnapshotList<NotificationCategoryDefinition>)
    suspend fun post(request: NotificationRequest): NotificationPostResult
    suspend fun setBadgeCount(count: Long)
    suspend fun deliveredNotifications(): SnapshotList<DeliveredNotification>
    suspend fun removeDelivered(ids: SnapshotList<NotificationId>)
    /** One outcome per selected snapshot; unknown identity must never fall back to logical-ID removal. */
    suspend fun removeDeliveredSnapshot(selected: SnapshotList<DeliveredNotification>): SnapshotList<SnapshotRemovalOutcome>
}
