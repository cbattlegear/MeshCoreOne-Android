// PortedFrom: MC1Services/Sources/MC1Services/Connection/ConnectionManager+BLE.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.connectivity.ble

import com.meshcoreone.android.core.ble.BleError
import com.meshcoreone.android.core.ble.BleLinkDiagnostics
import com.meshcoreone.android.core.ble.BlePhase
import com.meshcoreone.android.core.ble.BleTransportException
import com.meshcoreone.android.core.ble.BluetoothAvailability
import com.meshcoreone.android.core.connectivity.ConnectivityError
import com.meshcoreone.android.core.connectivity.bond.BondFailure
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.connectivity.pairing.DevicePairingService
import java.util.UUID

/** Platform link facts the runtime's health/adoption ladder consumes (runtime `PlatformLinkState`). */
data class LinkStateSnapshot(
    val connected: Boolean,
    val autoReconnecting: Boolean,
    val bluetoothPoweredOff: Boolean,
    val connectedDeviceId: UUID?,
    val phase: String,
    val systemConnected: Boolean,
)

/** Typed link-failure roles; the host maps them onto the runtime's `LinkFailure` hierarchy. */
enum class LinkFailureKind {
    AuthenticationFailed, ConnectionTimeout, ConnectionFailed, BluetoothPoweredOff,
    BluetoothUnavailable, BluetoothUnauthorized, DeviceConnectedToOtherApp, DeviceNotFound,
}

/** The live BLE link generation, as reported by the core:ble transport the host owns. */
data class BleLinkSnapshot(val deviceId: UUID, val diagnostics: BleLinkDiagnostics)

/** GATT clients the OS reports as connected (`BluetoothManager.getConnectedDevices(GATT)`). */
interface SystemLinkProbe {
    suspend fun isDeviceConnectedToSystem(deviceId: UUID): Boolean
    suspend fun systemConnectedDeviceIds(): Set<UUID>
}

/**
 * Android projection of the BLE state-machine queries used by the connection health ladder.
 * There is no CoreBluetooth-style OS auto-reconnect: `autoReconnecting` only reports an
 * explicit owner reconnect that core:ble is running.
 */
class BleLinkInspector(
    private val probe: SystemLinkProbe,
    private val currentLink: () -> BleLinkSnapshot?,
    private val adapterAvailability: () -> BluetoothAvailability,
) {
    suspend fun linkState(deviceId: UUID): LinkStateSnapshot {
        val link = currentLink()
        val phase = link?.diagnostics?.phase ?: BlePhase.Idle
        val availability = link?.diagnostics?.availability ?: adapterAvailability()
        val connected = phase == BlePhase.Connected
        return LinkStateSnapshot(
            connected = connected,
            autoReconnecting = phase == BlePhase.AutoReconnecting,
            bluetoothPoweredOff = availability == BluetoothAvailability.PoweredOff,
            connectedDeviceId = link?.deviceId?.takeIf { connected },
            phase = phase.sourceName,
            systemConnected = probe.isDeviceConnectedToSystem(deviceId),
        )
    }

    /**
     * Whether a device appears connected to another app: false during an owner reconnect, while
     * this app is already connected, or when the live link is ours.
     */
    suspend fun isDeviceConnectedToOtherApp(deviceId: UUID, connectionState: DeviceConnectionState): Boolean {
        val state = linkState(deviceId)
        if (state.autoReconnecting) return false
        if (connectionState != DeviceConnectionState.DISCONNECTED) return false
        if (state.connected && state.connectedDeviceId == deviceId) return false
        return state.systemConnected
    }

    /** A system-connected NUS peripheral is ours when last connected, mid-pair, or still associated. */
    fun isOwnedSystemConnectedLink(
        deviceId: UUID,
        lastConnectedDeviceId: UUID?,
        pairingInProgress: Boolean,
        pairing: DevicePairingService,
    ): Boolean {
        if (deviceId == lastConnectedDeviceId) return true
        if (pairingInProgress) return true
        if (pairing.hasSystemPairingRegistry) return pairing.isDeviceConnectable(deviceId)
        return false
    }

    suspend fun diagnosticsSummary(
        sessionPresent: Boolean,
        servicesPresent: Boolean,
        activeReconnectDeviceId: UUID?,
        sessionRebuildDeviceId: UUID?,
    ): String {
        val link = currentLink()
        val diagnostics = link?.diagnostics
        fun short(id: UUID?) = id?.toString()?.uppercase()?.take(8) ?: "none"
        val connected = diagnostics?.phase == BlePhase.Connected
        return "BLE: state=${(diagnostics?.availability ?: adapterAvailability()).name}, " +
            "phase=${(diagnostics?.phase ?: BlePhase.Idle).sourceName}, " +
            "bond=${diagnostics?.bond?.name ?: "none"}, " +
            "isConnected=$connected, " +
            "isAutoReconnecting=${diagnostics?.phase == BlePhase.AutoReconnecting}, " +
            "connectedDevice=${short(link?.deviceId?.takeIf { connected })}, " +
            "sessionPresent=$sessionPresent, " +
            "servicesPresent=$servicesPresent, " +
            "activeReconnectDevice=${short(activeReconnectDeviceId)}, " +
            "sessionRebuildDevice=${short(sessionRebuildDeviceId)}"
    }

    companion object {
        /** `BluetoothAdapter.STATE_OFF` / `STATE_TURNING_OFF`: values are stable public API constants. */
        private const val STATE_OFF = 10
        private const val STATE_TURNING_OFF = 13

        /**
         * User-actionable availability for the pickers. Only states a person can resolve get a
         * distinct value; transient states stay `Ready` so the picker keeps scanning.
         */
        fun bluetoothAvailability(adapterPresent: Boolean, adapterState: Int, connectPermitted: Boolean): BluetoothAvailability =
            when {
                !adapterPresent -> BluetoothAvailability.Unavailable
                !connectPermitted -> BluetoothAvailability.Unauthorized
                adapterState == STATE_OFF || adapterState == STATE_TURNING_OFF -> BluetoothAvailability.PoweredOff
                else -> BluetoothAvailability.Ready
            }

        fun isDeviceNotFoundError(failure: Throwable): Boolean =
            failure is ConnectivityError.DeviceNotFound ||
                (failure as? BleTransportException)?.error == BleError.DeviceNotFound

        /** Locale-independent classification at the throw site; walks the cause chain once. */
        fun classify(failure: Throwable): LinkFailureKind? {
            var current: Throwable? = failure
            val seen = HashSet<Throwable>()
            while (current != null && seen.add(current)) {
                when (current) {
                    is BondFailure -> return if (current.authentication) LinkFailureKind.AuthenticationFailed
                        else LinkFailureKind.ConnectionFailed
                    is SecurityException -> return LinkFailureKind.BluetoothUnauthorized
                    is ConnectivityError.DeviceNotFound -> return LinkFailureKind.DeviceNotFound
                    is BleTransportException -> return when (current.error) {
                        BleError.AuthenticationFailed, is BleError.PairingFailed, is BleError.BondRequired ->
                            LinkFailureKind.AuthenticationFailed
                        BleError.BluetoothPoweredOff -> LinkFailureKind.BluetoothPoweredOff
                        BleError.BluetoothUnavailable, is BleError.PlatformApiUnavailable -> LinkFailureKind.BluetoothUnavailable
                        BleError.BluetoothUnauthorized -> LinkFailureKind.BluetoothUnauthorized
                        BleError.ConnectionTimeout -> LinkFailureKind.ConnectionTimeout
                        BleError.DeviceConnectedToOtherApp -> LinkFailureKind.DeviceConnectedToOtherApp
                        BleError.DeviceNotFound -> LinkFailureKind.DeviceNotFound
                        else -> LinkFailureKind.ConnectionFailed
                    }
                }
                current = current.cause
            }
            return null
        }
    }
}
