// AndroidOnly: WP-402 Builds the ongoing connection notification (standard, or Live Update when eligible).
package com.meshcoreone.android.platform.notifications.status

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/** User-visible copy. The module owns no string resources, so the app supplies localized text; defaults are English. */
data class ConnectionStatusLabels(
    val connecting: String = "Connecting",
    val syncing: String = "Syncing",
    val reconnecting: String = "Reconnecting",
    val ready: String = "Connected",
    val disconnected: String = "Disconnected",
    val bluetoothOff: String = "Bluetooth is off",
    val permissionNeeded: String = "Permission needed to connect",
    val failed: String = "Connection problem",
    val connectAction: String = "Connect",
    val disconnectAction: String = "Disconnect",
    val permissionAction: String = "Allow",
    val appName: String = "MeshCore One",
) {
    fun title(kind: ConnectionStatusKind): String = when (kind) {
        ConnectionStatusKind.CONNECTING -> connecting
        ConnectionStatusKind.SYNCING -> syncing
        ConnectionStatusKind.RECONNECTING -> reconnecting
        ConnectionStatusKind.READY -> ready
        ConnectionStatusKind.DISCONNECTED -> disconnected
        ConnectionStatusKind.BLUETOOTH_OFF -> bluetoothOff
        ConnectionStatusKind.PERMISSION_NEEDED -> permissionNeeded
        ConnectionStatusKind.FAILED -> failed
    }
}

/** Host-supplied PendingIntents that need app knowledge (activities). Null means the action is not offered. */
interface ConnectionStatusIntents {
    /** Opens the app (tap on the notification). */
    fun contentIntent(): PendingIntent?

    /** Opens the screen where the missing connection permission can be granted. */
    fun permissionIntent(): PendingIntent?
}

/**
 * [receiver] is the app-registered, non-exported [ConnectionStatusActionReceiver]. All PendingIntents
 * are explicit and immutable. The notification is `ongoing` and has no custom view, so it qualifies for
 * the platform's promoted treatment only when [promote] is set by [LiveUpdateEligibility].
 */
class ConnectionStatusNotificationFactory(
    private val context: Context,
    private val receiver: ComponentName,
    private val intents: ConnectionStatusIntents,
    private val labels: ConnectionStatusLabels = ConnectionStatusLabels(),
) {
    fun build(channelId: String, status: ConnectionStatus, canConnect: Boolean, promote: Boolean): Notification {
        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(smallIcon())
            .setContentTitle(labels.title(status.kind))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setShowWhen(false)
        status.radioName?.takeIf { it.isNotBlank() }?.let { builder.setContentText(it) }
        intents.contentIntent()?.let { builder.setContentIntent(it) }
        val actions = ConnectionStatusMapper.actionsFor(status.kind, canConnect, intents.permissionIntent() != null)
        actions.forEach { builder.addAction(actionFor(it)) }
        if (promote && PlatformSdkVersion.currentFull() >= LiveUpdateEligibility.MIN_SDK_FULL) {
            LiveUpdateStyling.apply(builder, labels.title(status.kind))
        }
        return builder.build()
    }

    private fun smallIcon(): Icon {
        val appIcon = context.applicationInfo.icon
        return Icon.createWithResource(context, if (appIcon != 0) appIcon else android.R.drawable.stat_sys_data_bluetooth)
    }

    private fun actionFor(action: ConnectionStatusAction): Notification.Action {
        val icon = Icon.createWithResource(context, android.R.drawable.ic_menu_manage)
        return when (action) {
            ConnectionStatusAction.CONNECT ->
                Notification.Action.Builder(icon, labels.connectAction, broadcast(ConnectionStatusActionReceiver.ACTION_CONNECT)).build()
            ConnectionStatusAction.DISCONNECT ->
                Notification.Action.Builder(icon, labels.disconnectAction, broadcast(ConnectionStatusActionReceiver.ACTION_DISCONNECT)).build()
            ConnectionStatusAction.OPEN_PERMISSION_SETTINGS ->
                Notification.Action.Builder(icon, labels.permissionAction, requireNotNull(intents.permissionIntent())).build()
        }
    }

    private fun broadcast(action: String): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode(),
        Intent(action).setComponent(receiver).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Isolated API-36.1 calls; [PlatformSdkVersion] guards the only call site with the full SDK version. */
internal object LiveUpdateStyling {
    @SuppressLint("NewApi")
    fun apply(builder: Notification.Builder, shortText: String) {
        builder.setRequestPromotedOngoing(true)
        builder.setShortCriticalText(shortText.take(MAX_CHIP_CHARS))
        builder.setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
    }

    private const val MAX_CHIP_CHARS = 7
}
