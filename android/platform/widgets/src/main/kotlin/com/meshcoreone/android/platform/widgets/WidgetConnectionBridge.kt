// AndroidOnly: WP-403 Cold-start-safe, single-owner widget/tile connection requests and redacted cache.
package com.meshcoreone.android.platform.widgets

import android.content.Context
import android.os.UserManager
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.glance.appwidget.updateAll
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

enum class WidgetRadioStatus { DISCONNECTED, CONNECTING, CONNECTED, SYNCING, READY }

object WidgetPlatformPolicy {
    @ChecksSdkIntAtLeast(api = 34)
    fun usesPendingIntentTileLaunch(sdkInt: Int): Boolean = sdkInt >= 34
}

data class WidgetConnectionView(
    val status: WidgetRadioStatus,
    val hasSavedDevice: Boolean,
    val reconnecting: Boolean = false,
    val permissionDenied: Boolean = false,
)

enum class ConnectionRequestOrigin { WIDGET, QUICK_SETTINGS }

sealed interface ConnectionRequestResult {
    data object Requested : ConnectionRequestResult
    data object AlreadyActive : ConnectionRequestResult
    data object NoSavedDevice : ConnectionRequestResult
    data object UserLocked : ConnectionRequestResult
    data object PermissionDenied : ConnectionRequestResult
    data object BackgroundRestricted : ConnectionRequestResult
    data object OwnerUnavailable : ConnectionRequestResult
    data class Failed(val type: String) : ConnectionRequestResult
}

interface WidgetConnectionOwner {
    val status: Flow<WidgetConnectionView>
    suspend fun requestConnection(origin: ConnectionRequestOrigin): ConnectionRequestResult
}

object WidgetConnectionBridge {
    private const val OWNER_TIMEOUT_MILLIS = 15_000L
    private val owner = AtomicReference<WidgetConnectionOwner?>()
    private val requestLock = Mutex()

    fun install(context: Context, scope: CoroutineScope, connectionOwner: WidgetConnectionOwner) {
        owner.set(connectionOwner)
        scope.launch {
            connectionOwner.status.collect { view ->
                try {
                    WidgetStatusStore(context).write(view)
                    MeshStatusWidget().updateAll(context)
                    MeshConnectionTileService.requestListeningState(context)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: RuntimeException) {
                    logger.log(Level.WARNING, "Optional widget status refresh failed", failure)
                }
            }
        }
    }

    suspend fun request(context: Context, origin: ConnectionRequestOrigin): ConnectionRequestResult {
        val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked != false
        if (!unlocked) return ConnectionRequestResult.UserLocked
        return requestLock.withLock {
            val current = owner.get() ?: return@withLock ConnectionRequestResult.OwnerUnavailable
            try {
                withTimeout(OWNER_TIMEOUT_MILLIS) { current.requestConnection(origin) }
            } catch (timeout: TimeoutCancellationException) {
                ConnectionRequestResult.OwnerUnavailable
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (security: SecurityException) {
                ConnectionRequestResult.PermissionDenied
            } catch (failure: Exception) {
                ConnectionRequestResult.Failed(failure::class.java.simpleName)
            }
        }
    }

    internal fun resetForTesting() {
        owner.set(null)
    }

    private val logger = Logger.getLogger("com.mc1.WidgetConnectionBridge")
}

internal class WidgetStatusStore(context: Context) {
    private val context = context.applicationContext

    fun read(): WidgetConnectionView {
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == false) {
            return WidgetConnectionView(WidgetRadioStatus.DISCONNECTED, hasSavedDevice = false)
        }
        val values = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val status = runCatching {
            WidgetRadioStatus.valueOf(values.getString(KEY_STATUS, null).orEmpty())
        }.getOrDefault(WidgetRadioStatus.DISCONNECTED)
        return WidgetConnectionView(
            status = status,
            hasSavedDevice = values.getBoolean(KEY_SAVED, false),
            reconnecting = values.getBoolean(KEY_RECONNECTING, false),
            permissionDenied = values.getBoolean(KEY_PERMISSION, false),
        )
    }

    fun write(view: WidgetConnectionView) {
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_STATUS, view.status.name)
            .putBoolean(KEY_SAVED, view.hasSavedDevice)
            .putBoolean(KEY_RECONNECTING, view.reconnecting)
            .putBoolean(KEY_PERMISSION, view.permissionDenied)
            .apply()
    }

    private companion object {
        const val NAME = "mc1.widget-status"
        const val KEY_STATUS = "status"
        const val KEY_SAVED = "saved"
        const val KEY_RECONNECTING = "reconnecting"
        const val KEY_PERMISSION = "permission"
    }
}
