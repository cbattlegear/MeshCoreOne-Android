// AndroidOnly: WP-101 Prizm Mobile Observer foreground service (connectedDevice | location); a thin shell around PrizmObserverRuntime.
package com.meshcoreone.android.app.prizm

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.meshcoreone.android.app.MeshCoreApplication
import com.meshcoreone.android.core.l10n.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Holds the process alive and visible while uploads run. All logic lives in [PrizmObserverRuntime]; this class posts the
 * notification (counters, gate text, Stop), reports screen on/off, and stops itself when asked. The foreground type is
 * connectedDevice always and location only while precise location is granted (a location-typed start without it is refused).
 * Not sticky: the app restarts it from the foreground when the switch is still on.
 */
class PrizmObserverService : Service() {
    private var scope: CoroutineScope? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var observing: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = (application as? MeshCoreApplication)?.prizm?.runtime
        if (intent?.action == ACTION_STOP || runtime == null) {
            // startForeground is still owed once, or the platform kills the app for a startForegroundService that never promoted.
            promote(placeholder())
            runtime?.setUploadEnabled(false)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        promote(build(runtime.status.value.let { PrizmNotificationText.content(it, strings()) }))
        observe(runtime)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        scope?.cancel()
        super.onDestroy()
    }

    private fun observe(runtime: PrizmObserverRuntime) {
        if (observing != null) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        runtime.setScreenOn(getSystemService(PowerManager::class.java).isInteractive)
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = runtime.setScreenOn(intent.action == Intent.ACTION_SCREEN_ON)
        }.also {
            registerReceiver(it, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
        }
        observing = newScope.launch {
            var last: PrizmNotificationContent? = null
            runtime.status.collectLatest { status ->
                val content = PrizmNotificationText.content(status, strings())
                if (content != last) {
                    last = content
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(content))
                }
            }
        }
    }

    private fun promote(notification: Notification) {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        try {
            startForeground(NOTIFICATION_ID, notification, type)
        } catch (failure: RuntimeException) {
            stopSelf()
        }
    }

    private fun strings() = object : PrizmNotificationStrings {
        override fun waitingForNode() = getString(R.string.prizm_notification_waiting_node)
        override fun counts(heard: Int, uploaded: Int, metadataOnly: Int, queued: Int, withheld: Int) =
            getString(R.string.prizm_notification_counts, heard, uploaded, metadataOnly, queued, withheld)
    }

    private fun placeholder() = build(PrizmNotificationContent(getString(R.string.prizm_notification_waiting_node), ""))

    private fun build(content: PrizmNotificationContent): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.prizm_notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, PrizmObserverService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(getString(R.string.prizm_notification_title))
            .setContentText(content.summary)
            .setStyle(Notification.BigTextStyle().bigText(content.details))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.prizm_notification_stop), stop).build())
            .build()
    }

    companion object {
        const val CHANNEL_ID = "prizm_observer"
        const val NOTIFICATION_ID = 0x5052
        const val ACTION_STOP = "com.meshcoreone.android.prizm.STOP"
    }
}
