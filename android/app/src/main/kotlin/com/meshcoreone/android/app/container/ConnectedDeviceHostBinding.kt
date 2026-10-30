// AndroidOnly: WP-303 Routes the connection snapshot and foreground state to the connectedDevice service, and companion presence to reconnects.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.connectivity.presence.CompanionPresenceDispatcher
import com.meshcoreone.android.core.connectivity.presence.PresenceAction
import com.meshcoreone.android.core.connectivity.presence.PresenceContext
import com.meshcoreone.android.core.connectivity.presence.PresenceEvent
import com.meshcoreone.android.core.connectivity.presence.PresenceEventSink
import com.meshcoreone.android.core.connectivity.presence.PresenceReconnectPolicy
import com.meshcoreone.android.core.connectivity.service.ConnectedDeviceHostingController
import com.meshcoreone.android.core.connectivity.service.ConnectedDeviceServiceHost
import com.meshcoreone.android.core.connectivity.service.HostingInput
import com.meshcoreone.android.core.contracts.domain.Capability
import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.contracts.domain.ConnectionSnapshot
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The platform facts and effects the hosting and presence routes need; the application supplies the Android values. */
interface HostEnvironment {
    val connectPermissionGranted: Boolean
    val userUnlocked: Boolean
    val bluetoothEnabled: Boolean

    /** A reconnect that is retrying while the snapshot reads DISCONNECTED (the runtime's watchdog or auto-reconnect). */
    val reconnectInProgress: Boolean
    val lastConnectedDeviceId: UUID?
    val lanOnly: Boolean
    val isSimulator: Boolean get() = false
}

/** Effects a presence decision can request. */
interface PresenceEffects {
    suspend fun reconnect(deviceId: UUID)
    fun refreshAssociations()
    suspend fun forgetAssociation(deviceId: UUID)
    fun permissionRevoked(capability: Capability)
    fun deferredUntilUnlock(deviceId: UUID)
}

/**
 * Keeps exactly one connectedDevice foreground service held while a live connection is wanted (WP-206 policy), routes
 * the service's start/stop callbacks to the hosting controller, and turns companion presence events into reconnect
 * decisions. Everything runs on [scope]; [close] detaches every hook so a stale process owner cannot be driven.
 */
class ConnectedDeviceHostBinding(
    private val snapshots: Flow<ConnectionSnapshot>,
    private val foreground: Flow<Boolean>,
    private val snapshotNow: () -> ConnectionSnapshot,
    private val controller: ConnectedDeviceHostingController,
    private val environment: HostEnvironment,
    private val effects: PresenceEffects,
    private val scope: CoroutineScope,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    private var hosting: Job? = null
    private val sink = PresenceEventSink { event -> handlePresence(event) }
    private var attached = false

    /** Installs the service callbacks and presence sink and starts following state; calling again is a no-op. */
    fun start() {
        synchronized(lock) {
            if (attached) return
            attached = true
            ConnectedDeviceServiceHost.onStarted = { controller.onServiceStarted() }
            ConnectedDeviceServiceHost.onStartFailed = { cause -> controller.onServiceStartFailed(cause) }
            ConnectedDeviceServiceHost.onStopped = { controller.onServiceStopped() }
            ConnectedDeviceServiceHost.onFailure = onFailure
            hosting = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                combine(snapshots, foreground) { snapshot, inForeground -> snapshot to inForeground }
                    .collect { (snapshot, inForeground) -> evaluate(snapshot, inForeground) }
            }
        }
        CompanionPresenceDispatcher.attach(sink)
    }

    private fun evaluate(snapshot: ConnectionSnapshot, inForeground: Boolean) {
        try {
            controller.update(
                HostingInput(
                    intent = if (environment.isSimulator) ConnectionIntent.None else snapshot.intent,
                    state = snapshot.state,
                    reconnectInProgress = !environment.isSimulator && environment.reconnectInProgress,
                    appInForeground = inForeground,
                    connectPermissionGranted = environment.connectPermissionGranted,
                    lanOnly = environment.lanOnly,
                ),
            )
        } catch (failure: Exception) {
            // The platform start/stop cannot be allowed to end the collector that keeps hosting in step.
            onFailure(failure)
        }
    }

    private fun handlePresence(event: PresenceEvent) {
        if (event is PresenceEvent.Appeared && environment.isSimulator) return
        val snapshot = snapshotNow()
        val action = PresenceReconnectPolicy.decide(
            event,
            PresenceContext(
                intent = snapshot.intent,
                userDisconnected = snapshot.intent == ConnectionIntent.UserDisconnected,
                lastConnectedDeviceId = environment.lastConnectedDeviceId,
                connectionState = snapshot.state,
                reconnectInProgress = environment.reconnectInProgress,
                userUnlocked = environment.userUnlocked,
                bluetoothConnectGranted = environment.connectPermissionGranted,
                bluetoothEnabled = environment.bluetoothEnabled,
            ),
        )
        scope.launch {
            try {
                when (action) {
                    is PresenceAction.Reconnect -> if (!environment.isSimulator) effects.reconnect(action.deviceId)
                    is PresenceAction.DeferUntilUnlock -> effects.deferredUntilUnlock(action.deviceId)
                    is PresenceAction.PermissionRevoked -> effects.permissionRevoked(action.capability)
                    is PresenceAction.ForgetAssociation -> effects.forgetAssociation(action.deviceId)
                    PresenceAction.RefreshAssociations -> effects.refreshAssociations()
                    is PresenceAction.Ignore -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onFailure(failure)
            }
        }
    }

    fun close() {
        val job = synchronized(lock) {
            if (!attached) return
            attached = false
            hosting.also { hosting = null }
        }
        job?.cancel()
        CompanionPresenceDispatcher.detach(sink)
        ConnectedDeviceServiceHost.onStarted = null
        ConnectedDeviceServiceHost.onStartFailed = null
        ConnectedDeviceServiceHost.onStopped = null
        ConnectedDeviceServiceHost.onFailure = null
    }
}
