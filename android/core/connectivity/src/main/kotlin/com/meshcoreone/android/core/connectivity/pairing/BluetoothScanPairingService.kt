// PortedFrom: MC1Services/Sources/MC1Services/Services/BluetoothScanPairingService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.connectivity.pairing

import java.util.UUID
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Direct-scan fallback pairing service for devices without CompanionDeviceManager setup support.
 *
 * `discoverDevice()` raises [isPresenting] and suspends; the UI host observes it, presents an
 * in-app scan picker (which runs the scan coordinator itself) and calls [select] or [cancel].
 * There is no app-visible system registry: registry operations are inert and every id is
 * connectable, so the runtime decides reachability at connect time.
 *
 * Cancellation of the awaiting coroutine is delivered as structured `CancellationException`
 * (never rewritten into a domain error). The cancellation handler runs synchronously during
 * `cancel()`, so a [select] racing it cannot resolve the discovery with a stale selection.
 */
class BluetoothScanPairingService : DevicePairingService {
    override var delegate: DevicePairingDelegate? = null

    private val lock = Any()
    private val presenting = MutableStateFlow(false)
    private var continuation: CancellableContinuation<UUID>? = null
    private var cancellationRequested = false

    /** `true` while the in-app scan picker should be presented. */
    val isPresenting: StateFlow<Boolean> = presenting.asStateFlow()

    override val isSessionActive: Boolean get() = false
    override val registeredDeviceCount: Int get() = 0
    override val hasSystemPairingRegistry: Boolean get() = false
    override val supportsSystemRename: Boolean get() = false

    override suspend fun activate() = Unit

    override suspend fun discoverDevice(): UUID {
        synchronized(lock) { cancellationRequested = false }
        // Single-flight: a stranded prior discovery is resolved before a new one is installed.
        resolve(Result.failure(DevicePairingError.Cancelled()))
        return suspendCancellableCoroutine { pending ->
            synchronized(lock) {
                continuation = pending
                presenting.value = true
            }
            pending.invokeOnCancellation {
                synchronized(lock) {
                    cancellationRequested = true
                    if (continuation === pending) {
                        continuation = null
                        presenting.value = false
                    }
                }
            }
        }
    }

    override fun isDeviceConnectable(id: UUID): Boolean = true
    override fun registeredDeviceInfos(): List<RegisteredDevice> = emptyList()
    override suspend fun removeDevice(id: UUID) = Unit
    override suspend fun renameDevice(id: UUID) = Unit
    override suspend fun clearStaleRegistrations() = Unit

    /** Called by the scan picker when the user selects a device. */
    fun select(id: UUID) = resolve(Result.success(id))

    /** Fails an in-app discovery when its selected endpoint cannot be retained for connection. */
    fun fail(failure: Throwable) = resolve(Result.failure(failure))

    /** Called when the user cancels or dismisses the scan picker. */
    fun cancel() = resolve(Result.failure(DevicePairingError.Cancelled()))

    private fun resolve(result: Result<UUID>) {
        val (pending, stale) = synchronized(lock) {
            presenting.value = false
            val pending = continuation ?: return
            continuation = null
            pending to (result.isSuccess && cancellationRequested)
        }
        if (stale) pending.resumeWith(Result.failure(DevicePairingError.Cancelled()))
        else pending.resumeWith(result)
    }
}
