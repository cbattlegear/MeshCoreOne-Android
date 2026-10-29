// AndroidOnly: WP-303 Host adapters the connectivity module cannot own (it may not depend on core:runtime).
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.connectivity.ConnectivityPlatform
import com.meshcoreone.android.core.connectivity.pairing.PairingConnectionPort
import com.meshcoreone.android.core.connectivity.pairing.PairingDisconnectReason
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.runtime.ConnectionError
import com.meshcoreone.android.core.runtime.ConnectionManager
import com.meshcoreone.android.core.runtime.ConnectionPlatform
import com.meshcoreone.android.core.runtime.LinkFailure
import com.meshcoreone.android.core.runtime.PlatformLinkState
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import java.util.UUID

/** The runtime's [ConnectionPlatform] over the connectivity module's composed platform facts. */
class RuntimePlatformAdapter(val connectivity: ConnectivityPlatform) : ConnectionPlatform {
    override val hasSystemPairingRegistry: Boolean get() = connectivity.hasSystemPairingRegistry
    override val registryActive: Boolean get() = connectivity.registryActive
    override suspend fun activate() = connectivity.activate()
    override suspend fun foreground(active: Boolean) = connectivity.foreground(active)

    override suspend fun state(target: ConnectionTarget): PlatformLinkState {
        val id = (target as? ConnectionTarget.Bluetooth)?.deviceId
            ?: return PlatformLinkState(false, false, false, null, "idle", false)
        val state = connectivity.linkState(id)
        return PlatformLinkState(
            state.connected, state.autoReconnecting, state.bluetoothPoweredOff,
            state.connectedDeviceId, state.phase, state.systemConnected,
        )
    }

    override suspend fun isRegistered(deviceId: UUID): Boolean = connectivity.isRegistered(deviceId)

    override suspend fun targetForDevice(deviceId: UUID): ConnectionTarget? =
        connectivity.endpointFor(deviceId)?.let { ConnectionTarget.Bluetooth(it.handle, it.deviceId) }

    override suspend fun adoptSystemLink(target: ConnectionTarget): Boolean =
        (target as? ConnectionTarget.Bluetooth)?.let { connectivity.adoptSystemLink(it.deviceId) } ?: false

    override fun classifyFailure(failure: Throwable): LinkFailure? =
        failure as? LinkFailure ?: connectivity.classifyFailure(failure)?.let { linkFailureFor(it, failure) }
}

/** The pairing coordinator's connection port over the runtime manager. */
class RuntimePairingPort(
    private val manager: ConnectionManager,
    private val platform: ConnectionPlatform,
) : PairingConnectionPort {
    override val connectionState: DeviceConnectionState get() = manager.connectionState
    override val connectedDevice: DeviceDTO? get() = manager.connectedDevice
    override val activeConnectionAttemptDeviceId: UUID? get() = manager.activeConnectionAttemptDeviceId

    override fun setPairingActivity(pairingInProgress: Boolean, pairingFlowActive: Boolean) =
        manager.setPairingActivity(pairingInProgress, pairingFlowActive)

    override suspend fun connect(deviceId: UUID, forceFullSync: Boolean, forceReconnect: Boolean) {
        val target = platform.targetForDevice(deviceId) ?: throw ConnectionError.DeviceNotFound()
        manager.connect(target, forceFullSync, forceReconnect)
    }

    override suspend fun disconnect(reason: PairingDisconnectReason) {
        manager.disconnect(
            when (reason) {
                PairingDisconnectReason.FORGET_DEVICE -> RuntimeDisconnectReason.FORGET_DEVICE
                PairingDisconnectReason.FACTORY_RESET -> RuntimeDisconnectReason.FACTORY_RESET
                PairingDisconnectReason.DEVICE_REMOVED_FROM_SETTINGS -> RuntimeDisconnectReason.DEVICE_REMOVED_FROM_SETTINGS
                PairingDisconnectReason.PAIRING_FAILED -> RuntimeDisconnectReason.PAIRING_FAILED
            },
        )
    }

    override suspend fun disconnectTransport() = manager.disconnectTransport(requireComplete = true)

    override suspend fun clearReconnectingDevice() {
        manager.reconnectionCoordinator.cancelTimeout()
        manager.reconnectionCoordinator.clearReconnectingDevice()
    }

    override fun markDisconnected() {
        if (!manager.connectionState.isOperational) manager.setConnectionState(DeviceConnectionState.DISCONNECTED)
    }

    override suspend fun clearPersistedConnection(deviceId: UUID) = manager.clearPersistedConnection(deviceId)

    override fun isAuthenticationFailure(failure: Throwable): Boolean =
        platform.classifyFailure(failure) is LinkFailure.AuthenticationFailed

    override fun isDeviceConnectedToOtherApp(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is LinkFailure.DeviceConnectedToOtherApp }

    private companion object { const val MAX_CAUSE_DEPTH = 8 }
}
