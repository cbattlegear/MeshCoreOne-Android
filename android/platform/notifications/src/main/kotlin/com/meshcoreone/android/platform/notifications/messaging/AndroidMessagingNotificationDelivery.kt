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
import com.meshcoreone.android.core.contracts.notifications.DeliveredPostIdentity
import com.meshcoreone.android.core.contracts.notifications.SnapshotRemovalOutcome
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope

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
            synchronized(postingLock) {
                manager.notify(tag(request.id), NOTIFICATION_ID, notification)
                submittedPosts[tag(request.id)] = requireNotNull(notification.extras.getString(EXTRA_POST_ID))
            }
            publishConversationShortcut(request)
            NotificationPostResult.Posted
        } catch (_: SecurityException) {
            NotificationPostResult.PermissionDenied
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
            val rawIdentity = active.notification.extras.getString(EXTRA_POST_ID)
            val identity = rawIdentity?.let {
                try { DeliveredPostIdentity(UUID.fromString(it)) } catch (_: IllegalArgumentException) { null }
            }
            DeliveredNotification(NotificationId(tag), payload, identity)
        })

    override suspend fun removeDeliveredSnapshot(
        selected: SnapshotList<DeliveredNotification>,
    ): SnapshotList<SnapshotRemovalOutcome> = SnapshotList(selected.map { notification ->
        synchronized(postingLock) {
            val identity = notification.postIdentity?.value?.toString()
            val key = tag(notification.id)
            val submitted = submittedPosts[key]
            when {
                identity == null || submitted == null -> SnapshotRemovalOutcome.UNKNOWN_IDENTITY
                submitted != identity -> SnapshotRemovalOutcome.SUPERSEDED
                else -> {
                    manager.cancel(key, NOTIFICATION_ID)
                    submittedPosts.remove(key)
                    SnapshotRemovalOutcome.REMOVED
                }
            }
        }
    })

    override suspend fun removeDelivered(ids: SnapshotList<NotificationId>) {
        synchronized(postingLock) {
            ids.forEach {
                manager.cancel(tag(it), NOTIFICATION_ID)
                submittedPosts.remove(tag(it))
            }
        }
    }

    fun retryPendingActions() {
        NotificationActionDispatcher.retry()
    }

    internal fun buildNotification(request: NotificationRequest): Notification {
        val launchIntent = contentLaunchIntent(request)
        val postId = requireNotNull(launchIntent.getStringExtra(EXTRA_POST_ID))
        val contentIntent = contentPendingIntent(request, launchIntent)
        val payloadExtras = launchIntent.extras
        val builder = Notification.Builder(context, channelFor(request.payload, request.soundEnabled))
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(request.title)
            .setContentText(request.body)
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
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

        definitions[request.category]?.actions?.forEach { definition ->
            when (definition.action) {
                NotificationAction.REPLY -> {
                    val remoteInput = RemoteInput.Builder(NotificationActionReceiver.REMOTE_INPUT_KEY)
                        .setLabel(definition.textInputPlaceholder)
                        .build()
                    builder.addAction(
                        Notification.Action.Builder(
                            null,
                            definition.title,
                            actionPendingIntent(request, definition.action, postId, mutable = true),
                        ).addRemoteInput(remoteInput).build(),
                    )
                }
                NotificationAction.MARK_READ -> builder.addAction(
                    Notification.Action.Builder(
                        null,
                        definition.title,
                        actionPendingIntent(request, definition.action, postId, mutable = false),
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
        action: NotificationAction,
        postId: String,
        mutable: Boolean,
    ): PendingIntent {
        val actionName = action.rawValue
        val intent = NotificationPayloadCodec.write(
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(ACTION_PREFIX + actionName)
                .setData(
                    Uri.parse("meshcore-notification://action/${Uri.encode(request.id.value)}/$actionName")
                        .buildUpon().appendQueryParameter("post", postId).build(),
                )
                .putExtra(EXTRA_POST_ID, postId)
                .putExtra(NotificationActionReceiver.EXTRA_ACTION, action.rawValue),
            request.payload,
        )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode(request.id, actionName), intent, flags)
    }

    internal fun contentPendingIntent(
        request: NotificationRequest,
        launchIntent: Intent = contentLaunchIntent(request),
    ): PendingIntent {
        return PendingIntent.getActivity(
            context,
            requestCode(request.id, ACTION_OPEN),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal fun contentLaunchIntent(request: NotificationRequest): Intent {
        val postId = UUID.randomUUID().toString()
        return NotificationPayloadCodec.write(
            Intent(context, NotificationOpenActivity::class.java)
                .setAction(ACTION_PREFIX + ACTION_OPEN)
                .putExtra(EXTRA_POST_ID, postId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setData(
                    Uri.parse("meshcore-notification://action/${Uri.encode(request.id.value)}/$ACTION_OPEN")
                        .buildUpon().appendQueryParameter("post", postId).build(),
                ),
            request.payload,
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
        private val postingLock = Any()
        private const val MAX_TRACKED_POSTS = 256
        private val submittedPosts = object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > MAX_TRACKED_POSTS
        }
        internal const val EXTRA_POST_ID = "mc1.notification.post-id"

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
