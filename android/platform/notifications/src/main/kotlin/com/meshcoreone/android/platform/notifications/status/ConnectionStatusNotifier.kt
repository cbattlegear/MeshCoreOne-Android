// AndroidOnly: WP-402 Publishes connection status through the one connectedDevice foreground-service notification.
package com.meshcoreone.android.platform.notifications.status

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.meshcoreone.android.core.contracts.notifications.NotificationAuthorizationStatus
import java.util.logging.Level
import java.util.logging.Logger

/** Everything the notifier needs about the current moment; built by the app from the owner's snapshot. */
data class ConnectionStatusInput(val status: ConnectionStatus, val canConnect: Boolean)

/**
 * Not a connection owner: it only renders. The foreground-service notification (WP-206
 * `ConnectedDeviceService`, id [FOREGROUND_NOTIFICATION_ID]) is the single status surface; updates reuse
 * its id so no second notification appears. Wire [notificationFor] as the service's
 * `ConnectedDeviceNotificationProvider`, then call [update] on every snapshot change. Denied permission,
 * a blocked channel or any platform failure is a [StatusPostResult]; it never throws into the connection.
 */
class ConnectionStatusNotifier(
    context: Context,
    private val factory: ConnectionStatusNotificationFactory,
    private val authorizationStatus: () -> NotificationAuthorizationStatus = {
        NotificationAuthorizationStatus.AUTHORIZED
    },
    private val sdkIntFull: Int = PlatformSdkVersion.currentFull(),
) {
    private val appContext: Context = context.applicationContext ?: context
    private val manager: NotificationManager = appContext.getSystemService(NotificationManager::class.java)

    @Volatile
    private var latest: ConnectionStatusInput = ConnectionStatusInput(
        ConnectionStatus(ConnectionStatusKind.CONNECTING, null, false), canConnect = false,
    )

    /** The notification the foreground service must show at start; always returns one (the service's start contract). */
    fun notificationFor(channelId: String): Notification = render(channelId, latest)

    fun surface(): StatusSurface {
        val authorization = authorizationStatus()
        return ConnectionStatusAvailability.evaluate(
            granted = authorization == NotificationAuthorizationStatus.AUTHORIZED,
            appEnabled = manager.areNotificationsEnabled(),
            channelBlocked = manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE,
            promptShown = authorization != NotificationAuthorizationStatus.NOT_DETERMINED,
        )
    }

    fun update(input: ConnectionStatusInput): StatusPostResult {
        latest = input
        if (surface() != StatusSurface.NOTIFICATION) return StatusPostResult.SURFACE_UNAVAILABLE
        return try {
            ensureChannel()
            manager.notify(FOREGROUND_NOTIFICATION_ID, render(CHANNEL_ID, input))
            StatusPostResult.POSTED
        } catch (failure: RuntimeException) {
            logger.log(Level.WARNING, "Connection status notification failed", failure)
            StatusPostResult.FAILED
        }
    }

    private fun render(channelId: String, input: ConnectionStatusInput): Notification {
        val decision = LiveUpdateEligibility.evaluate(input.status, sdkIntFull, promotionGranted())
        return factory.build(channelId, input.status, input.canConnect, decision == LiveUpdateDecision.Promote)
    }

    private fun promotionGranted(): Boolean =
        sdkIntFull >= LiveUpdateEligibility.MIN_SDK_FULL && PromotionGrant.isGranted(manager)

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, factoryLabel(), NotificationManager.IMPORTANCE_LOW))
    }

    private fun factoryLabel(): CharSequence = appContext.applicationInfo.loadLabel(appContext.packageManager)

    companion object {
        /** Must equal WP-206 `ConnectedDeviceServiceHost.CHANNEL_ID` / `NOTIFICATION_ID` (this module cannot depend on core:connectivity). */
        const val CHANNEL_ID = "connected_device"
        const val FOREGROUND_NOTIFICATION_ID = 0x4d43
        private val logger: Logger = Logger.getLogger("com.mc1.ConnectionStatusNotifier")
    }
}

/** Isolated API-36.1 call; [ConnectionStatusNotifier] guards it with the full SDK version. */
internal object PromotionGrant {
    @SuppressLint("NewApi")
    fun isGranted(manager: NotificationManager): Boolean = try {
        manager.canPostPromotedNotifications()
    } catch (failure: RuntimeException) {
        false
    }
}
