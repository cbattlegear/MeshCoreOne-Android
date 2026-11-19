// PortedFrom: MC1Services/Sources/MC1Services/Connection/ConnectionManager+Pairing.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.connectivity.pairing

import com.meshcoreone.android.core.connectivity.ConnectivityClock
import com.meshcoreone.android.core.connectivity.ConnectivityDiagnostics
import com.meshcoreone.android.core.connectivity.ConnectivityError
import com.meshcoreone.android.core.connectivity.ble.SystemLinkProbe
import com.meshcoreone.android.core.connectivity.bond.BondInspector
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.DevicePersisting
import com.meshcoreone.android.core.model.DeviceDTO
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Disconnect reasons the pairing flow requests; the host maps them onto runtime reasons. */
enum class PairingDisconnectReason { FORGET_DEVICE, FACTORY_RESET, DEVICE_REMOVED_FROM_SETTINGS, PAIRING_FAILED }

/**
 * The connection operations pairing drives. WP-303 adapts the process `ConnectionManager`
 * (core:runtime) to this port; core:connectivity may not depend on core:runtime.
 */
interface PairingConnectionPort {
    val connectionState: DeviceConnectionState
    val connectedDevice: DeviceDTO?
    val activeConnectionAttemptDeviceId: UUID?
    fun setPairingActivity(pairingInProgress: Boolean, pairingFlowActive: Boolean)
    suspend fun connect(deviceId: UUID, forceFullSync: Boolean, forceReconnect: Boolean)
    suspend fun disconnect(reason: PairingDisconnectReason)
    /** Physically closes the retained/active link without changing intent. */
    suspend fun disconnectTransport()
    /** Cancels the reconnect-coordinator UI timeout and releases its device claim. */
    suspend fun clearReconnectingDevice()
    /** Defensive UI backstop after a cancelled pairing (`connectionState = .disconnected`). */
    fun markDisconnected()
    suspend fun clearPersistedConnection(deviceId: UUID)
    fun isAuthenticationFailure(failure: Throwable): Boolean
    fun isDeviceConnectedToOtherApp(failure: Throwable): Boolean
}

/**
 * Device pairing, forget and registry reconciliation (ConnectionManager+Pairing). Pairing never
 * removes companion associations except to clean up a cancelled, partially-completed pairing;
 * unsaved associations are only listed for, and removed after, explicit user confirmation.
 */
class PairingCoordinator(
    private val connection: PairingConnectionPort,
    val pairing: DevicePairingService,
    private val devices: DevicePersisting,
    private val links: SystemLinkProbe,
    private val stopScanning: () -> Unit,
    private val clock: ConnectivityClock,
    private val scope: CoroutineScope,
    private val diagnostics: ConnectivityDiagnostics = ConnectivityDiagnostics.NONE,
    private val ensureBonded: (suspend (UUID) -> Unit)? = null,
    private val endpoints: KnownEndpointStore? = null,
    private val bonds: BondInspector? = null,
) : DevicePairingDelegate {
    private val lock = Any()
    private var pairingInProgress = false
    private var pairingFlowActive = false

    /** Test seams mirroring the source DEBUG overrides. */
    var otherAppWaitStrategy: (suspend (UUID) -> Boolean)? = null
    var adoptionSettleStrategy: (suspend () -> Unit)? = null
    var adoptionSettleTimeout: Duration = PAIRING_ADOPTION_SETTLE_TIMEOUT

    init { pairing.delegate = this }

    val isPairingInProgress: Boolean get() = synchronized(lock) { pairingInProgress }
    val isPairingFlowActive: Boolean get() = synchronized(lock) { pairingFlowActive }

    /** Restores a pairing flag (test state seeding, or a host rebuilding after a configuration change). */
    fun setPairingFlags(inProgress: Boolean = isPairingInProgress, flowActive: Boolean = isPairingFlowActive) {
        synchronized(lock) { pairingInProgress = inProgress; pairingFlowActive = flowActive }
        publishActivity()
    }

    /**
     * Discovers a new device through the platform pairing service, then connects through the
     * shared runtime connect ceremony (adoption, switch-device, circuit breaker). Throws
     * [DevicePairingError.AlreadyInProgress] on re-entry, [PairingError.DeviceConnectedToOtherApp],
     * [PairingError.ConnectionFailed] or the caller's cancellation.
     */
    suspend fun pairNewDevice() {
        synchronized(lock) {
            if (pairingInProgress) throw DevicePairingError.AlreadyInProgress()
            pairingInProgress = true
        }
        publishActivity()
        try {
            stopScanning()
            pairing.activate()
            val deviceId = pairing.discoverDevice()
            connectAfterDiscovery(deviceId)
        } finally {
            synchronized(lock) { pairingInProgress = false }
            publishActivity()
        }
    }

    private suspend fun connectAfterDiscovery(deviceId: UUID) {
        try {
            ensureBonded?.invoke(deviceId)
            if (waitForOtherAppReconnection(deviceId)) {
                diagnostics.report("[OtherAppCheck] System-connected after pairing; connecting", null)
            }
            currentCoroutineContext().ensureActive()
            connection.connect(deviceId, forceFullSync = true, forceReconnect = true)
            if (!connection.connectionState.isOperational) waitForPairingAdoptionToSettle()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { cleanupPartialPairing(deviceId) }
            throw cancelled
        } catch (failure: Exception) {
            if (connection.isDeviceConnectedToOtherApp(failure)) throw PairingError.DeviceConnectedToOtherApp(deviceId)
            if (!currentCoroutineContext().isActive) {
                withContext(NonCancellable) { cleanupPartialPairing(deviceId) }
                throw CancellationException("Pairing cancelled").apply { initCause(failure) }
            }
            diagnostics.report("pairing.connect", failure)
            val authentication = connection.isAuthenticationFailure(failure) ||
                (failure is com.meshcoreone.android.core.connectivity.bond.BondFailure && failure.authentication)
            throw PairingError.ConnectionFailed(deviceId, failure, authentication, recoveryFor(deviceId, authentication))
        }
    }

    /**
     * An auth failure while Android still holds a bond means the radio dropped its keys. API 36+
     * removes the bond with the association; older releases cannot, so the user must forget the
     * radio in Bluetooth settings before pairing again.
     */
    private fun recoveryFor(deviceId: UUID, authentication: Boolean): PairingRecovery {
        if (!authentication) return PairingRecovery.None
        val inspector = bonds ?: return PairingRecovery.RemoveAndRetry
        return try {
            if (!inspector.canRemoveBonds && inspector.isBonded(deviceId)) PairingRecovery.ForgetInBluetoothSettings
            else PairingRecovery.RemoveAndRetry
        } catch (failure: Exception) {
            diagnostics.report("pairing.bondInspection", failure)
            PairingRecovery.RemoveAndRetry
        }
    }

    /**
     * Removes a cancelled, partially-paired association. The association removal also removes the
     * system bond on API 36+ (`CompanionDeviceManager.removeBond`); on older releases any bond the
     * flow created stays in Bluetooth settings.
     */
    private suspend fun cleanupPartialPairing(deviceId: UUID) {
        try { pairing.removeDevice(deviceId) }
        catch (failure: Exception) { diagnostics.report("pairing.cleanupPartial.remove", failure) }
        bestEffort("pairing.cleanupPartial.reconnect") { connection.clearReconnectingDevice() }
        bestEffort("pairing.cleanupPartial.transport") { connection.disconnectTransport() }
        connection.markDisconnected()
    }

    /** Pairing leftover-link wait: an adopted link must reach an operational session or fail. */
    suspend fun waitForPairingAdoptionToSettle() {
        adoptionSettleStrategy?.let { return it() }
        val deadline = clock.elapsed + adoptionSettleTimeout
        while (true) {
            currentCoroutineContext().ensureActive()
            val state = connection.connectionState
            if (state.isOperational) {
                if (connection.connectedDevice == null) throw ConnectivityError.ConnectionFailed(PAIRING_ADOPTION_MISSING_SESSION_DETAIL)
                return
            }
            if (state == DeviceConnectionState.DISCONNECTED) throw ConnectivityError.ConnectionFailed(PAIRING_ADOPTION_FAILED_DETAIL)
            if (clock.elapsed >= deadline) throw ConnectivityError.ConnectionFailed(PAIRING_ADOPTION_TIMED_OUT_DETAIL)
            clock.sleep(PAIRING_ADOPTION_SETTLE_POLL_INTERVAL)
        }
    }

    /**
     * Polls for another app's link reappearing after association disrupted it. Returns `true`
     * when the device is system-connected; cancellation propagates to the caller.
     */
    suspend fun waitForOtherAppReconnection(deviceId: UUID): Boolean {
        otherAppWaitStrategy?.let { return it(deviceId) }
        for (check in 1..OTHER_APP_MAX_CHECKS) {
            currentCoroutineContext().ensureActive()
            if (links.isDeviceConnectedToSystem(deviceId)) return true
            if (check < OTHER_APP_MAX_CHECKS) clock.sleep(OTHER_APP_CHECK_INTERVAL)
        }
        return false
    }

    /** Associations with no `Device` row; omits the live connection and the in-flight attempt. */
    suspend fun systemAccessoriesMissingDeviceRecord(): List<SystemPairedAccessory> {
        if (!pairing.hasSystemPairingRegistry) return emptyList()
        pairing.activate()
        val protectedIds = setOfNotNull(connection.connectedDevice?.id, connection.activeConnectionAttemptDeviceId)
        val result = mutableListOf<SystemPairedAccessory>()
        for (info in pairing.registeredDeviceInfos()) {
            if (info.id in protectedIds) continue
            val existing = try { devices.fetchDevice(info.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                diagnostics.report("pairing.missingRecord.fetch", failure)
                continue
            }
            if (existing == null) result += SystemPairedAccessory(info.id, info.name)
        }
        return result
    }

    /** Removes only requested associations that still have no `Device` row. */
    suspend fun removeSystemAccessoriesMissingDeviceRecord(ids: List<UUID>) {
        if (!pairing.hasSystemPairingRegistry) return
        val allowed = systemAccessoriesMissingDeviceRecord().map { it.id }.toSet()
        for (id in ids) if (id in allowed) pairing.removeDevice(id)
    }

    /**
     * Guided "remove and retry" for a failed connect after pairing. Demotes the row to a ghost so
     * a re-paired radio resolves the same radio id and reattaches its data.
     */
    suspend fun removeFailedPairing(deviceId: UUID) {
        // Bond clear first: Android bond verification lives on the live link generation, which the
        // transport teardown below discards (the source cleared a process-wide state machine last).
        connection.clearPersistedConnection(deviceId)
        connection.disconnectTransport()
        pairing.removeDevice(deviceId)
        devices.demoteDeviceToGhost(deviceId)
        endpoints?.forget(deviceId)
    }

    /** Forgets the connected device; `deleteData` hard-deletes, otherwise the row is ghosted. */
    suspend fun forgetDevice(deleteData: Boolean) {
        val deviceId = connection.connectedDevice?.id ?: throw ConnectivityError.NotConnected()
        if (!pairing.isDeviceConnectable(deviceId)) throw ConnectivityError.DeviceNotFound()
        pairing.removeDevice(deviceId)
        connection.disconnect(PairingDisconnectReason.FORGET_DEVICE)
        bestEffort("forgetDevice.store") {
            if (deleteData) devices.deleteDeviceAndData(deviceId) else devices.demoteDeviceToGhost(deviceId)
        }
        endpoints?.let { store -> bestEffort("forgetDevice.endpoint") { store.forget(deviceId) } }
        connection.clearPersistedConnection(deviceId)
    }

    /** Factory-reset forget by id: best-effort, never throws (except cancellation). */
    suspend fun forgetDevice(id: UUID) {
        connection.disconnect(PairingDisconnectReason.FACTORY_RESET)
        bestEffort("forgetDeviceById.registry") { pairing.removeDevice(id) }
        bestEffort("forgetDeviceById.store") { devices.deleteDeviceAndData(id) }
        endpoints?.let { store -> bestEffort("forgetDeviceById.endpoint") { store.forget(id) } }
        connection.clearPersistedConnection(id)
    }

    /**
     * Forgets the registry entry then ghosts the row. A declined removal throws
     * [DevicePairingError.Cancelled] and leaves the row listed.
     */
    suspend fun deleteDevice(id: UUID) {
        val tearDown = connection.connectedDevice?.id == id || connection.activeConnectionAttemptDeviceId == id
        if (tearDown) setPairingFlags(flowActive = true)
        try {
            pairing.removeDevice(id)
            if (tearDown) connection.disconnect(PairingDisconnectReason.FORGET_DEVICE)
            devices.demoteDeviceToGhost(id)
            endpoints?.forget(id)
            connection.clearPersistedConnection(id)
        } finally {
            if (tearDown) setPairingFlags(flowActive = false)
        }
    }

    fun hasAccessory(deviceId: UUID): Boolean = pairing.isDeviceConnectable(deviceId)

    suspend fun fetchSavedDevices(): List<DeviceDTO> = devices.fetchDevices().toList()

    val pairedAccessoryInfos: List<RegisteredDevice> get() = pairing.registeredDeviceInfos()

    suspend fun renameCurrentDevice() {
        val deviceId = connection.connectedDevice?.id ?: throw ConnectivityError.NotConnected()
        if (!pairing.isDeviceConnectable(deviceId)) throw ConnectivityError.DeviceNotFound()
        pairing.renameDevice(deviceId)
    }

    suspend fun clearStalePairings() = pairing.clearStaleRegistrations()

    /** The system registry removed a device (Settings). Bond clear is ordered first. */
    override fun devicePairingDidRemoveDevice(service: DevicePairingService, id: UUID) {
        launchCallback("pairing.didRemove") {
            connection.clearPersistedConnection(id)
            if (connection.connectedDevice?.id == id) connection.disconnect(PairingDisconnectReason.DEVICE_REMOVED_FROM_SETTINGS)
            // The registry can still list the id; ghosting would turn an authorized radio into a leftover.
            if (pairing.isDeviceConnectable(id)) return@launchCallback
            bestEffort("pairing.didRemove.demote") { devices.demoteDeviceToGhost(id) }
        }
    }

    /** Pairing failed (wrong PIN): drop the row so the device can appear in the picker again. */
    override fun devicePairingDidFailPairing(service: DevicePairingService, id: UUID) {
        launchCallback("pairing.didFail") {
            connection.clearPersistedConnection(id)
            if (connection.connectedDevice?.id == id) connection.disconnect(PairingDisconnectReason.PAIRING_FAILED)
            bestEffort("pairing.didFail.delete") { devices.deleteDevice(id) }
        }
    }

    private fun launchCallback(operation: String, action: suspend () -> Unit): Job = scope.launch {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { diagnostics.report(operation, failure) }
    }

    private suspend inline fun bestEffort(operation: String, action: () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { diagnostics.report(operation, failure) }
    }

    private fun publishActivity() {
        val (inProgress, flow) = synchronized(lock) { pairingInProgress to pairingFlowActive }
        connection.setPairingActivity(inProgress, flow)
    }

    companion object {
        val PAIRING_ADOPTION_SETTLE_POLL_INTERVAL: Duration = 50.milliseconds
        val PAIRING_ADOPTION_SETTLE_TIMEOUT: Duration = 15.seconds
        const val PAIRING_ADOPTION_FAILED_DETAIL = "adoption failed"
        const val PAIRING_ADOPTION_MISSING_SESSION_DETAIL = "adoption rebuilt no session"
        const val PAIRING_ADOPTION_TIMED_OUT_DETAIL = "adoption timed out"
        const val OTHER_APP_MAX_CHECKS = 6
        val OTHER_APP_CHECK_INTERVAL: Duration = 400.milliseconds
    }
}
