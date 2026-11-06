// AndroidOnly: WP-206 Composed platform facts for the runtime's connection platform role (adapted by the host at WP-303).
package com.meshcoreone.android.core.connectivity

import com.meshcoreone.android.core.connectivity.ble.BleLinkInspector
import com.meshcoreone.android.core.connectivity.ble.LinkFailureKind
import com.meshcoreone.android.core.connectivity.ble.LinkStateSnapshot
import com.meshcoreone.android.core.connectivity.pairing.BluetoothEndpoint
import com.meshcoreone.android.core.connectivity.pairing.CompanionPairingService
import com.meshcoreone.android.core.connectivity.pairing.CompanionSetupService
import com.meshcoreone.android.core.connectivity.pairing.DevicePairingService
import com.meshcoreone.android.core.connectivity.pairing.KnownEndpointStore
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import java.util.UUID

/**
 * Re-opens this app's own GATT client over a link the OS still reports as connected. Android has
 * no CoreBluetooth-style adoption of another process's peripheral object: "adoption" is an
 * explicit reconnect of the endpoint's registered link, started without awaiting completion.
 */
fun interface SystemLinkAdopter {
    suspend fun adopt(endpoint: BluetoothEndpoint): Boolean
}

/**
 * Everything the runtime's `ConnectionPlatform` asks of the platform, in connectivity types.
 * core:runtime is not a permitted dependency of core:connectivity, so the host maps this onto
 * `ConnectionPlatform`/`PlatformLinkState`/`LinkFailure` (see coordinator notes).
 */
class ConnectivityPlatform(
    val pairing: DevicePairingService,
    private val inspector: BleLinkInspector,
    private val knownEndpoints: KnownEndpointStore,
    private val adopter: SystemLinkAdopter,
    private val companionSession: CompanionSetupService? = null,
    private val onForegroundChanged: (Boolean) -> Unit = {},
) {
    val hasSystemPairingRegistry: Boolean get() = pairing.hasSystemPairingRegistry
    val registryActive: Boolean get() = pairing.isSessionActive

    suspend fun activate() = pairing.activate()

    /** Foreground re-reads associations (Settings removals) and lets the host re-check permissions. */
    fun foreground(active: Boolean) {
        if (active) companionSession?.refreshAssociations()
        onForegroundChanged(active)
    }

    suspend fun linkState(deviceId: UUID): LinkStateSnapshot = inspector.linkState(deviceId)

    suspend fun isDeviceConnectedToOtherApp(deviceId: UUID, connectionState: DeviceConnectionState): Boolean =
        inspector.isDeviceConnectedToOtherApp(deviceId, connectionState)

    fun isRegistered(deviceId: UUID): Boolean = pairing.isDeviceConnectable(deviceId)

    /** Association endpoint first (stable id + association id), then scan-fallback memory. */
    suspend fun endpointFor(deviceId: UUID): BluetoothEndpoint? =
        (pairing as? CompanionPairingService)?.endpoint(deviceId) ?: knownEndpoints.endpoint(deviceId)

    suspend fun adoptSystemLink(deviceId: UUID): Boolean {
        val endpoint = endpointFor(deviceId) ?: return false
        return adopter.adopt(endpoint)
    }

    fun classifyFailure(failure: Throwable): LinkFailureKind? = BleLinkInspector.classify(failure)
}
