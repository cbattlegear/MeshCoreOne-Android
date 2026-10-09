// AndroidOnly: WP-403 Optional Quick Settings request surface; the AppContainer remains the only connection owner.
package com.meshcoreone.android.platform.widgets

import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.l10n.generated.WidgetLocalizableStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MeshConnectionTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        render(WidgetStatusStore(this).read())
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val result = WidgetConnectionBridge.request(this@MeshConnectionTileService, ConnectionRequestOrigin.QUICK_SETTINGS)
            render(WidgetStatusStore(this@MeshConnectionTileService).read())
            if (result is ConnectionRequestResult.NoSavedDevice ||
                result is ConnectionRequestResult.UserLocked ||
                result is ConnectionRequestResult.PermissionDenied ||
                result is ConnectionRequestResult.BackgroundRestricted ||
                result is ConnectionRequestResult.OwnerUnavailable ||
                result is ConnectionRequestResult.Failed
            ) openApp()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(view: WidgetConnectionView) {
        qsTile?.apply {
            state = when (view.status) {
                WidgetRadioStatus.READY -> Tile.STATE_ACTIVE
                WidgetRadioStatus.CONNECTING, WidgetRadioStatus.CONNECTED, WidgetRadioStatus.SYNCING ->
                    Tile.STATE_UNAVAILABLE
                WidgetRadioStatus.DISCONNECTED -> Tile.STATE_INACTIVE
            }
            label = getString(R.string.widget_app_name)
            subtitle = when {
                view.permissionDenied -> getString(WidgetLocalizableStrings.openMeshCoreOne)
                view.reconnecting -> getString(AppLocalizableStrings.commonStatusConnecting)
                view.status == WidgetRadioStatus.DISCONNECTED -> getString(WidgetLocalizableStrings.disconnected)
                view.status == WidgetRadioStatus.READY -> getString(AppLocalizableStrings.commonStatusReady)
                else -> getString(AppLocalizableStrings.commonStatusConnecting)
            }
            contentDescription = "$label, $subtitle"
            updateTile()
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        if (WidgetPlatformPolicy.usesPendingIntentTileLaunch(Build.VERSION.SDK_INT)) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    403,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            startActivityAndCollapse(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    companion object {
        fun requestListeningState(context: Context) {
            requestListeningState(
                context,
                ComponentName(context, MeshConnectionTileService::class.java),
            )
        }
    }
}
