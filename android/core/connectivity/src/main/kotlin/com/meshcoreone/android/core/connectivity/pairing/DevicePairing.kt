// PortedFrom: MC1Services/Sources/MC1Services/Services/DevicePairingService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/DevicePairingDelegate.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/DevicePairingError.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Errors/PairingError.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.connectivity.pairing

import java.util.UUID

/** A system-registry entry as `(id, name)`; the id is an endpoint handle, never radio identity. */
data class RegisteredDevice(val id: UUID, val name: String)

/**
 * Callbacks from the system pairing registry. On Android these come from CompanionDeviceManager
 * disassociation/failure events; the scan fallback never fires them.
 */
interface DevicePairingDelegate {
    fun devicePairingDidRemoveDevice(service: DevicePairingService, id: UUID)
    fun devicePairingDidFailPairing(service: DevicePairingService, id: UUID)
}

/**
 * Platform-neutral seam for device discovery and system pairing-registry management.
 * `CompanionPairingService` is backed by CompanionDeviceManager; `BluetoothScanPairingService`
 * is the in-app scan fallback when companion association is unavailable.
 */
interface DevicePairingService {
    var delegate: DevicePairingDelegate?
    val isSessionActive: Boolean
    val registeredDeviceCount: Int
    val hasSystemPairingRegistry: Boolean
    val supportsSystemRename: Boolean
    suspend fun activate()
    suspend fun discoverDevice(): UUID
    fun isDeviceConnectable(id: UUID): Boolean
    fun registeredDeviceInfos(): List<RegisteredDevice>
    suspend fun removeDevice(id: UUID)
    suspend fun renameDevice(id: UUID)
    suspend fun clearStaleRegistrations()
}

/** Benign control-flow signals from the pairing seam; not connection failures. */
sealed class DevicePairingError(message: String) : Exception(message) {
    /** The picker was dismissed or a system removal was declined. */
    class Cancelled : DevicePairingError("Device selection was cancelled.")
    /** A pairing flow is already running; the re-entrant request was ignored. */
    class AlreadyInProgress : DevicePairingError("Device pairing is already in progress.")
    /** The system chooser cannot be presented yet (no foreground activity/UI host). */
    class PickerUnavailable : DevicePairingError("Device picker is temporarily unavailable.")
}

/** Failures after a device was selected; the device id drives the recovery UI. */
sealed class PairingError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    abstract val deviceId: UUID

    class ConnectionFailed(
        override val deviceId: UUID,
        val underlying: Throwable,
        private val authenticationFailure: Boolean,
        /** The guided recovery the UI offers; auth failures default to "remove and retry". */
        val recovery: PairingRecovery =
            if (authenticationFailure) PairingRecovery.RemoveAndRetry else PairingRecovery.None,
    ) : PairingError("Connection failed: ${underlying.message}", underlying) {
        override val isAuthenticationFailure: Boolean get() = authenticationFailure
    }

    class DeviceConnectedToOtherApp(override val deviceId: UUID) :
        PairingError("Device is connected to another app.")

    /**
     * True when the underlying link failure is an auth/encryption failure. Classification is
     * performed once at the throw site by the connection port, never by matching message text.
     */
    open val isAuthenticationFailure: Boolean get() = false
}

/** Recovery routes for a failed pairing connect. */
enum class PairingRecovery {
    None,
    /** Remove the association (and, API 36+, its bond) and pair again. */
    RemoveAndRetry,
    /** A stale bond the app cannot remove (before API 36): the user forgets it in Bluetooth settings. */
    ForgetInBluetoothSettings,
}
