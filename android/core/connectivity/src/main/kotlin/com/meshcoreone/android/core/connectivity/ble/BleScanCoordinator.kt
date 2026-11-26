// PortedFrom: MC1Services/Sources/MC1Services/Connection/ConnectionManager+BLE.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.connectivity.ble

import com.meshcoreone.android.core.connectivity.ConnectivityDiagnostics
import java.util.UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.channels.Channel

/** One advertisement seen by the in-app scan picker. */
data class DiscoveredDevice(val id: UUID, val address: String, val name: String?, val rssi: Int)

/** Platform scanner boundary (BluetoothLeScanner filtered on the NUS service). */
interface BleScanGateway {
    /** Starts one scan session; results are delivered to [onDevice] until [stopScan]. */
    fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit)
    fun stopScan()
}

/**
 * Scanning is orthogonal to the connection lifecycle and works while connected. Each call to
 * [startBleScanning] supersedes the previous scan; only the newest collector's termination
 * stops the platform scan (request-id fence), so an older stream cannot stop a newer scan.
 */
class BleScanCoordinator(
    private val gateway: BleScanGateway,
    private val diagnostics: ConnectivityDiagnostics = ConnectivityDiagnostics.NONE,
) {
    private val lock = Any()
    private var requestId = 0L
    private var scanning = false

    val isScanning: Boolean get() = synchronized(lock) { scanning }

    fun startBleScanning(): Flow<DiscoveredDevice> = callbackFlow {
        val claimed = synchronized(lock) {
            if (scanning) stopLocked()
            requestId++
            requestId
        }
        val start = synchronized(lock) {
            if (requestId != claimed) false else { scanning = true; true }
        }
        if (start) {
            try {
                gateway.startScan(
                    onDevice = { device -> if (isCurrent(claimed)) trySend(device) },
                    onFailure = { failure ->
                        diagnostics.report("ble.scan", failure)
                        if (isCurrent(claimed)) close(failure)
                    },
                )
            } catch (failure: Exception) {
                synchronized(lock) { if (requestId == claimed) scanning = false }
                throw failure
            }
        }
        awaitClose {
            synchronized(lock) {
                if (requestId == claimed) {
                    requestId++
                    stopLocked()
                }
            }
        }
    }.buffer(Channel.UNLIMITED)

    fun stopBleScanning() {
        synchronized(lock) {
            requestId++
            stopLocked()
        }
    }

    private fun isCurrent(claimed: Long): Boolean = synchronized(lock) { requestId == claimed && scanning }

    private fun stopLocked() {
        if (!scanning) return
        scanning = false
        try { gateway.stopScan() } catch (failure: Exception) { diagnostics.report("ble.stopScan", failure) }
    }
}
