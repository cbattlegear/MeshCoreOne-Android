// AndroidOnly: WP-303 Process entry: creates the AppContainer once and feeds it the process foreground/background edges.
package com.meshcoreone.android.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.meshcoreone.android.app.container.AndroidAppContainerFactory
import com.meshcoreone.android.app.container.AppContainer
import com.meshcoreone.android.app.state.ProcessForegroundState
import com.meshcoreone.android.core.contracts.domain.ConnectionIssue
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.runtime.ConnectionError
import com.meshcoreone.android.platform.widgets.ConnectionRequestOrigin
import com.meshcoreone.android.platform.widgets.ConnectionRequestResult
import com.meshcoreone.android.platform.widgets.WidgetConnectionBridge
import com.meshcoreone.android.platform.widgets.WidgetConnectionOwner
import com.meshcoreone.android.platform.widgets.WidgetConnectionView
import com.meshcoreone.android.platform.widgets.WidgetRadioStatus
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Creates the process container on the main dispatcher at `onCreate` and reports process visibility to it: the first
 * started activity is the foreground edge (`handleBecameActive` then `handleReturnToForeground`), the last stopped
 * activity the background edge. Registered in the manifest through `android:name` (a coordinator edit, see WP-303.md).
 */
class MeshCoreApplication : Application() {
    private val logger: Logger = Logger.getLogger("com.mc1.MeshCoreApplication")
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = CompletableDeferred<AppContainer>()
    val foreground = ProcessForegroundState()

    /** Completes with the process container once it is built; fails if construction failed (storage unavailable). */
    val container: kotlinx.coroutines.Deferred<AppContainer> get() = pending

    override fun onCreate() {
        super.onCreate()
        installWidgetConnectionOwner()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (foreground.activityStarted()) onForegroundEdge()
            }

            override fun onActivityStopped(activity: Activity) {
                if (foreground.activityStopped()) onBackgroundEdge()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        processScope.launch {
            try {
                val built = AndroidAppContainerFactory.create(this@MeshCoreApplication, processScope, foreground)
                pending.complete(built)
                built.start()
            } catch (cancelled: CancellationException) {
                pending.completeExceptionally(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                logger.log(Level.SEVERE, "Process container failed to start: ${failure.message}")
                pending.completeExceptionally(failure)
            }
        }
    }

    private fun installWidgetConnectionOwner() {
        WidgetConnectionBridge.install(this, processScope, object : WidgetConnectionOwner {
            override val status: Flow<WidgetConnectionView>
                get() = kotlinx.coroutines.flow.flow {
                    val current = pending.await()
                    emitAll(current.connectionManager.snapshot.map { snapshot ->
                        WidgetConnectionView(
                            status = when (snapshot.state) {
                                DeviceConnectionState.DISCONNECTED -> WidgetRadioStatus.DISCONNECTED
                                DeviceConnectionState.CONNECTING -> WidgetRadioStatus.CONNECTING
                                DeviceConnectionState.CONNECTED -> WidgetRadioStatus.CONNECTED
                                DeviceConnectionState.SYNCING -> WidgetRadioStatus.SYNCING
                                DeviceConnectionState.READY -> WidgetRadioStatus.READY
                            },
                            hasSavedDevice = current.connectionPort.lastConnectedDeviceId != null,
                            reconnecting = snapshot.blePhase ==
                                com.meshcoreone.android.core.contracts.domain.BleLinkPhase.AUTO_RECONNECTING,
                            permissionDenied = snapshot.issue is ConnectionIssue.PermissionDenied,
                        )
                    })
                }

            override suspend fun requestConnection(origin: ConnectionRequestOrigin): ConnectionRequestResult {
                val current = pending.await()
                if (current.connectionManager.connectionState != DeviceConnectionState.DISCONNECTED) {
                    return ConnectionRequestResult.AlreadyActive
                }
                val deviceId = current.connectionPort.lastConnectedDeviceId
                    ?: return ConnectionRequestResult.NoSavedDevice
                return try {
                    current.connectionPort.connect(deviceId, forceReconnect = false)
                    ConnectionRequestResult.Requested
                } catch (missing: ConnectionError.DeviceNotFound) {
                    ConnectionRequestResult.NoSavedDevice
                } catch (denied: SecurityException) {
                    ConnectionRequestResult.PermissionDenied
                }
            }
        })
    }

    private fun onForegroundEdge() {
        processScope.launch {
            val state = (runCatchingContainer() ?: return@launch).appState
            state.handleBecameActive()
            state.handleReturnToForeground()
        }
    }

    private fun onBackgroundEdge() {
        processScope.launch { runCatchingContainer()?.appState?.handleEnterBackground() }
    }

    private suspend fun runCatchingContainer(): AppContainer? = try {
        pending.await()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        null
    }
}
