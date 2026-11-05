// AndroidOnly: WP-303 In-app scan picker port over BluetoothScanPairingService and the BLE scan coordinator.
package com.meshcoreone.android.app.container.onboarding

import com.meshcoreone.android.core.connectivity.ble.BleScanCoordinator
import com.meshcoreone.android.core.connectivity.pairing.BluetoothEndpoint
import com.meshcoreone.android.core.connectivity.pairing.BluetoothScanPairingService
import com.meshcoreone.android.core.connectivity.pairing.DeviceEndpointIdentity
import com.meshcoreone.android.core.connectivity.pairing.KnownEndpointStore
import com.meshcoreone.android.core.ui.RSSIScanTracker
import com.meshcoreone.android.core.ui.ScanDiscovery
import com.meshcoreone.android.feature.onboarding.OnboardingScanDiscovery
import com.meshcoreone.android.feature.onboarding.OnboardingScanFallbackPort
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Presented only when companion association is unsupported ([BluetoothScanPairingService] is the pairing service).
 * Each [discoveries] collection owns one scan and one RSSI tracker; cancelling the collection stops the scan.
 */
class ScanFallbackPort(
    private val service: BluetoothScanPairingService,
    private val scans: BleScanCoordinator,
    private val knownEndpoints: KnownEndpointStore,
    private val scope: CoroutineScope,
    private val isBondedAddress: (String) -> Boolean,
    private val clock: Clock = Clock.systemUTC(),
) : OnboardingScanFallbackPort {
    override val isRequested: StateFlow<Boolean> get() = service.isPresenting
    private val discoveredEndpoints = ConcurrentHashMap<UUID, BluetoothEndpoint>()

    override fun discoveries(): Flow<List<OnboardingScanDiscovery>> = channelFlow {
        val tracker = RSSIScanTracker<UUID>(clock)
        launch {
            discoveredEndpoints.clear()
            tracker.consume(scans.startBleScanning().map { device ->
                val endpoint = BluetoothEndpoint(device.id, DeviceEndpointIdentity.canonicalAddress(device.address), null)
                discoveredEndpoints[device.id] = endpoint
                ScanDiscovery(device.id, device.name, device.rssi.toLong())
            })
        }
        tracker.devices.collect { devices ->
            send(devices.values.map { OnboardingScanDiscovery(it.discovery.id, it.discovery.name, it.tier) })
        }
    }

    override fun select(id: UUID) {
        val endpoint = discoveredEndpoints[id]
        if (endpoint == null) {
            service.fail(IllegalStateException("Selected BLE discovery no longer has an endpoint"))
            return
        }
        scope.launch {
            try {
                val stability = DeviceEndpointIdentity.stability(endpoint.address, bonded = isBondedAddress(endpoint.address))
                knownEndpoints.remember(endpoint, stability)
                discoveredEndpoints.remove(id)
                service.select(id)
            } catch (cancelled: CancellationException) {
                service.cancel()
                throw cancelled
            } catch (failure: Exception) {
                service.fail(failure)
            }
        }
    }

    override fun cancel() = service.cancel()
}
