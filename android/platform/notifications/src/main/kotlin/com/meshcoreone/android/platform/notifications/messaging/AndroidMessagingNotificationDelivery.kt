// AndroidOnly: WP-401 Native Android channels, MessagingStyle, conversation shortcuts and guarded actions.
package com.meshcoreone.android.platform.notifications.messaging

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.contracts.notifications.DeliveredNotification
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationActionDefinition
import com.meshcoreone.android.core.contracts.notifications.NotificationAuthorizationStatus
import com.meshcoreone.android.core.contracts.notifications.NotificationCategory
import com.meshcoreone.android.core.contracts.notifications.NotificationCategoryDefinition
import com.meshcoreone.android.core.contracts.notifications.NotificationDeliveryPort
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.Failure
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.IssuedAction
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.IssuedRecord
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.PublicationAttempt
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.PublicationResult
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface NotificationReplyPublicationResult {
    data class AttemptReturned(val issuance: IssuedRecord) : NotificationReplyPublicationResult
    data class AttemptHeld(val issuance: IssuedRecord) : NotificationReplyPublicationResult
    data class Rejected(val reason: Failure) : NotificationReplyPublicationResult
    data class Indeterminate(val token: UUID) : NotificationReplyPublicationResult
    data class NotAttempted(val reason: Reason) : NotificationReplyPublicationResult
    data class AttemptFailed(val issuance: IssuedRecord, val reason: PlatformFailure) :
        NotificationReplyPublicationResult

    enum class Reason { INVALID_REQUEST, REPLY_ACTION_UNAVAILABLE, NOTIFICATION_PERMISSION_DENIED }
    enum class PlatformFailure { SECURITY_DENIED, INVALID_PLATFORM_REQUEST, CORRUPT_RECEIPT }
}

class AndroidMessagingNotificationDelivery(
    context: Context,
    scope: CoroutineScope,
    routeAction: suspend (NotificationResponse) -> Boolean,
) : NotificationDeliveryPort {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)
    private val shortcutManager = this.context.getSystemService(ShortcutManager::class.java)
    private val definitions = ConcurrentHashMap<NotificationCategory, NotificationCategoryDefinition>()
    private val badge = AtomicLong(0)

    init {
        NotificationActionDispatcher.install(scope, routeAction)
    }

    override suspend fun authorizationStatus(): NotificationAuthorizationStatus =
        if (Build.VERSION.SDK_INT < 33) {
            NotificationAuthorizationStatus.AUTHORIZED
        } else if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationAuthorizationStatus.AUTHORIZED
        } else {
            NotificationAuthorizationStatus.DENIED
        }

    /**
     * Permission prompting remains owned by the existing onboarding/settings Activity flow. This process adapter
     * never launches UI from a service or receiver; it reports the current result only.
     */
    override suspend fun requestAuthorization(): Boolean =
        authorizationStatus() == NotificationAuthorizationStatus.AUTHORIZED

    override suspend fun registerCategories(categories: SnapshotList<NotificationCategoryDefinition>) {
        categories.forEach { definitions[it.category] = it }
        val messages = context.getString(AppSettingsStrings.notificationsChannelMessages)
        val activity = context.getString(AppLocalizableStrings.notificationsDiscoveryContact)
        val alerts = context.getString(AppSettingsStrings.notificationsLowBattery)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_MESSAGES, messages, NotificationManager.IMPORTANCE_HIGH),
                silentChannel(CHANNEL_MESSAGES_SILENT, messages),
                NotificationChannel(CHANNEL_ACTIVITY, activity, NotificationManager.IMPORTANCE_DEFAULT),
                silentChannel(CHANNEL_ACTIVITY_SILENT, activity),
                NotificationChannel(CHANNEL_ALERTS, alerts, NotificationManager.IMPORTANCE_DEFAULT),
                silentChannel(CHANNEL_ALERTS_SILENT, alerts),
            ),
        )
    }

    override suspend fun post(request: NotificationRequest): NotificationPostResult {
        if (authorizationStatus() != NotificationAuthorizationStatus.AUTHORIZED) {
            return NotificationPostResult.PermissionDenied
        }
        val notification = buildNotification(request)
        return try {
            manager.notify(tag(request.id), NOTIFICATION_ID, notification)
            publishConversationShortcut(request)
            NotificationPostResult.Posted
        } catch (_: SecurityException) {
            NotificationPostResult.PermissionDenied
        }
    }

    /** Unwired one-shot path; a returned attempt is not evidence that Android displayed the notification. */
    internal suspend fun publishIssuedReplyOnce(
        request: NotificationRequest,
        expectedStoreId: UUID,
        proposal: IssuedAction,
        maxReplyCodeUnits: Int,
        attempt: PublicationAttempt,
        store: NotificationReplyStagingStore,
    ): NotificationReplyPublicationResult {
        val category = request.category
            ?: return NotificationReplyPublicationResult.NotAttempted(
                NotificationReplyPublicationResult.Reason.INVALID_REQUEST,
            )
        val payload = request.payload
        if (request.id.value.isEmpty() || proposal.logicalNotificationId != request.id ||
            proposal.payload != payload || proposal.post.version != 1 || proposal.post.action != "REPLY" ||
            (category == NotificationCategory.DIRECT_MESSAGE && payload !is NotificationPayload.DirectMessage) ||
            (category == NotificationCategory.CHANNEL_MESSAGE && payload !is NotificationPayload.ChannelMessage) ||
            category !in setOf(NotificationCategory.DIRECT_MESSAGE, NotificationCategory.CHANNEL_MESSAGE)
        ) return NotificationReplyPublicationResult.NotAttempted(
            NotificationReplyPublicationResult.Reason.INVALID_REQUEST,
        )
        val configuredActions = definitions[category]?.actions
            ?: return NotificationReplyPublicationResult.NotAttempted(
                NotificationReplyPublicationResult.Reason.REPLY_ACTION_UNAVAILABLE,
            )
        if (configuredActions.count { it.action == NotificationAction.REPLY } != 1) {
            return NotificationReplyPublicationResult.NotAttempted(
                NotificationReplyPublicationResult.Reason.REPLY_ACTION_UNAVAILABLE,
            )
        }
        if (authorizationStatus() != NotificationAuthorizationStatus.AUTHORIZED) {
            return NotificationReplyPublicationResult.NotAttempted(
                NotificationReplyPublicationResult.Reason.NOTIFICATION_PERMISSION_DENIED,
            )
        }

        return when (val claimed = store.reservePublicationAttempt(
            expectedStoreId, proposal, maxReplyCodeUnits, attempt,
        )) {
            is PublicationResult.NewlyClaimed -> {
                currentCoroutineContext().ensureActive()
                val record = claimed.record
                if (record.storeId != expectedStoreId || record.action != proposal ||
                    record.maxReplyCodeUnits != maxReplyCodeUnits || record.publicationAttempt != attempt
                ) return NotificationReplyPublicationResult.AttemptFailed(
                    record, NotificationReplyPublicationResult.PlatformFailure.CORRUPT_RECEIPT,
                )
                try {
                    val replyPendingIntent = issuedReplyPendingIntent(expectedStoreId, proposal)
                    val notification = buildNotification(request, replyPendingIntent, configuredActions)
                    currentCoroutineContext().ensureActive()
                    manager.notify(tag(request.id), NOTIFICATION_ID, notification)
                    publishConversationShortcut(request)
                    NotificationReplyPublicationResult.AttemptReturned(record)
                } catch (_: SecurityException) {
                    NotificationReplyPublicationResult.AttemptFailed(
                        record, NotificationReplyPublicationResult.PlatformFailure.SECURITY_DENIED,
                    )
                } catch (_: IllegalArgumentException) {
                    NotificationReplyPublicationResult.AttemptFailed(
                        record, NotificationReplyPublicationResult.PlatformFailure.INVALID_PLATFORM_REQUEST,
                    )
                }
            }
            is PublicationResult.Observed -> NotificationReplyPublicationResult.AttemptHeld(claimed.record)
            is PublicationResult.Rejected -> NotificationReplyPublicationResult.Rejected(claimed.reason)
            is PublicationResult.Indeterminate -> NotificationReplyPublicationResult.Indeterminate(claimed.token)
        }
    }

    override suspend fun setBadgeCount(count: Long) {
        badge.set(count.coerceAtLeast(0))
    }

    override suspend fun deliveredNotifications(): SnapshotList<DeliveredNotification> =
        SnapshotList(manager.activeNotifications.mapNotNull { active ->
            val platformTag = active.tag?.takeIf { it.startsWith(TAG_PREFIX) } ?: return@mapNotNull null
            val tag = platformTag.removePrefix(TAG_PREFIX)
            val payload = NotificationPayloadCodec.read(Intent().putExtras(active.notification.extras))
            DeliveredNotification(NotificationId(tag), payload)
        })

    override suspend fun removeDelivered(ids: SnapshotList<NotificationId>) {
        ids.forEach { manager.cancel(tag(it), NOTIFICATION_ID) }
    }

    fun retryPendingActions() {
        NotificationActionDispatcher.retry()
    }

    internal fun buildNotification(
        request: NotificationRequest,
        issuedReplyPendingIntent: PendingIntent? = null,
        actionDefinitions: List<NotificationActionDefinition>? = null,
    ): Notification {
        val contentIntent = actionPendingIntent(request, action = null, mutable = false)
        val payloadExtras = NotificationPayloadCodec.write(Intent(), request.payload).extras
        val builder = Notification.Builder(context, channelFor(request.payload, request.soundEnabled))
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(request.title)
            .setContentText(request.body)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setNumber((request.badge ?: badge.get()).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            .setCategory(
                if (request.category == NotificationCategory.LOW_BATTERY) Notification.CATEGORY_STATUS
                else Notification.CATEGORY_MESSAGE,
            )
            .addExtras(payloadExtras)
        request.threadIdentifier?.let(builder::setGroup)

        if (request.category in MESSAGE_CATEGORIES) {
            val sender = Person.Builder().setName(request.title).build()
            val user = Person.Builder().setName(context.applicationInfo.loadLabel(context.packageManager)).build()
            builder.setStyle(
                Notification.MessagingStyle(user)
                    .setConversationTitle(request.title)
                    .addMessage(request.body, System.currentTimeMillis(), sender),
            )
            builder.setShortcutId(shortcutId(request))
        } else {
            builder.setStyle(Notification.BigTextStyle().bigText(request.body))
        }

        (actionDefinitions ?: definitions[request.category]?.actions.orEmpty()).forEach { definition ->
            when (definition.action) {
                NotificationAction.REPLY -> {
                    val remoteInput = RemoteInput.Builder(NotificationActionReceiver.REMOTE_INPUT_KEY)
                        .setLabel(definition.textInputPlaceholder)
                        .build()
                    builder.addAction(
                        Notification.Action.Builder(
                            null,
                            definition.title,
                            issuedReplyPendingIntent
                                ?: actionPendingIntent(request, definition.action, mutable = true),
                        ).addRemoteInput(remoteInput).build(),
                    )
                }
                NotificationAction.MARK_READ -> builder.addAction(
                    Notification.Action.Builder(
                        null,
                        definition.title,
                        actionPendingIntent(request, definition.action, mutable = false),
                    ).build(),
                )
                NotificationAction.DISMISS -> Unit
            }
        }

        val publicVersion = Notification.Builder(context, channelFor(request.payload, request.soundEnabled))
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(context.applicationInfo.loadLabel(context.packageManager))
            .setContentText(
                if (request.category in MESSAGE_CATEGORIES) {
                    context.getString(AppSettingsStrings.notificationsChannelMessages)
                } else {
                    context.getString(AppLocalizableStrings.openMeshCoreOne)
                },
            )
            .build()
        return builder.setPublicVersion(publicVersion).build()
    }

    internal fun actionPendingIntent(
        request: NotificationRequest,
        action: NotificationAction?,
        mutable: Boolean,
    ): PendingIntent {
        val actionName = action?.rawValue ?: ACTION_OPEN
        val intent = NotificationPayloadCodec.write(
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(ACTION_PREFIX + actionName)
                .setData(Uri.parse("meshcore-notification://action/${Uri.encode(request.id.value)}/$actionName"))
                .putExtra(NotificationActionReceiver.EXTRA_ACTION, action?.rawValue),
            request.payload,
        )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode(request.id, actionName), intent, flags)
    }

    private fun issuedReplyPendingIntent(storeId: UUID, proposal: IssuedAction): PendingIntent {
        val identity = Uri.Builder().scheme("meshcore-notification").authority("issued-reply")
            .appendPath("v1").appendPath(storeId.toString()).appendPath(proposal.token.toString()).build()
        val intent = Intent(context, UnactivatedIssuedReplyReceiver::class.java)
            .setAction(NotificationReplyIssuanceAdapter.ACTION)
            .setData(identity)
            .putExtra("mc1.notification.issued-reply.store", storeId.toString())
            .putExtra("mc1.notification.issued-reply.token", proposal.token.toString())
            .putExtra("mc1.notification.type", "issued-reply-v1")
        return PendingIntent.getBroadcast(
            context,
            requestCode(proposal.logicalNotificationId, "ISSUED_REPLY_V1"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun publishConversationShortcut(request: NotificationRequest) {
        if (request.category !in MESSAGE_CATEGORIES || request.threadIdentifier == null) return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val person = Person.Builder().setName(request.title).build()
        val shortcut = ShortcutInfo.Builder(context, shortcutId(request))
            .setShortLabel(request.title.take(40))
            .setLongLived(true)
            .setPerson(person)
            .setCategories(setOf(CONVERSATION_CATEGORY))
            .setIntent(launch)
            .setIcon(Icon.createWithResource(context, context.applicationInfo.icon))
            .build()
        try {
            shortcutManager.pushDynamicShortcut(shortcut)
        } catch (_: RuntimeException) {
            // A rate-limited or disabled launcher shortcut must not change the notification post result.
        }
    }

    private fun channelFor(payload: NotificationPayload, soundEnabled: Boolean): String {
        val audible = when (payload) {
        is NotificationPayload.DirectMessage,
        is NotificationPayload.ChannelMessage,
        is NotificationPayload.RoomMessage -> CHANNEL_MESSAGES
        is NotificationPayload.LowBattery -> CHANNEL_ALERTS
        else -> CHANNEL_ACTIVITY
        }
        return if (soundEnabled) audible else "${audible}_silent"
    }

    private fun silentChannel(id: String, name: String) =
        NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
        }

    private fun shortcutId(request: NotificationRequest): String {
        val stable = request.threadIdentifier ?: request.id.value
        return "conversation-" + MessageDigest.getInstance("SHA-256")
            .digest(stable.toByteArray(StandardCharsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
    }

    private fun tag(id: NotificationId) = TAG_PREFIX + id.value
    private fun requestCode(id: NotificationId, action: String) = 31 * id.value.hashCode() + action.hashCode()

    companion object {
        internal const val CHANNEL_MESSAGES = "mesh_messages"
        internal const val CHANNEL_MESSAGES_SILENT = "mesh_messages_silent"
        internal const val CHANNEL_ACTIVITY = "mesh_activity"
        internal const val CHANNEL_ACTIVITY_SILENT = "mesh_activity_silent"
        internal const val CHANNEL_ALERTS = "mesh_device_alerts"
        internal const val CHANNEL_ALERTS_SILENT = "mesh_device_alerts_silent"
        internal const val NOTIFICATION_ID = 401
        internal const val ACTION_PREFIX = "com.meshcoreone.android.notification."
        internal const val ACTION_OPEN = "OPEN"
        internal const val TAG_PREFIX = "mesh-message:"
        internal const val CONVERSATION_CATEGORY = "com.meshcoreone.android.category.CONVERSATION"
        internal val MESSAGE_CATEGORIES = setOf(
            NotificationCategory.DIRECT_MESSAGE,
            NotificationCategory.CHANNEL_MESSAGE,
            NotificationCategory.ROOM_MESSAGE,
        )
    }
}
