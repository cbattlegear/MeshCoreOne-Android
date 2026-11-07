// PortedFrom: MC1Services/Sources/MC1Services/Services/AccessorySetupPairingService.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/DevicePairingFactory.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.connectivity.pairing

import com.meshcoreone.android.core.connectivity.ConnectivityDiagnostics
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * `DevicePairingService` backed by CompanionDeviceManager. Translates the UUID surface onto
 * companion associations and republishes association events through `DevicePairingDelegate`.
 * The registry gates connects (association membership), but association itself neither
 * connects GATT nor grants a background-execution exemption.
 */
class CompanionPairingService(
    private val companion: CompanionSetupServicing,
    private val diagnostics: ConnectivityDiagnostics = ConnectivityDiagnostics.NONE,
) : DevicePairingService, CompanionSetupDelegate {
    override var delegate: DevicePairingDelegate? = null

    init { companion.delegate = this }

    override val isSessionActive: Boolean get() = companion.isSessionActive
    override val registeredDeviceCount: Int get() = companion.pairedAccessories.size
    override val hasSystemPairingRegistry: Boolean get() = true
    /** Android has no system rename sheet for companion associations. */
    override val supportsSystemRename: Boolean get() = false

    override suspend fun activate() = companion.activateSession()

    override suspend fun discoverDevice(): UUID = try {
        companion.showPicker()
    } catch (dismissed: CompanionSetupError.PickerDismissed) {
        throw DevicePairingError.Cancelled()
    } catch (active: CompanionSetupError.PickerAlreadyActive) {
        throw DevicePairingError.AlreadyInProgress()
    } catch (restricted: CompanionSetupError.PickerRestricted) {
        throw DevicePairingError.PickerUnavailable()
    } catch (inactive: CompanionSetupError.SessionNotActive) {
        throw DevicePairingError.PickerUnavailable()
    }

    override fun isDeviceConnectable(id: UUID): Boolean = companion.accessory(id) != null

    override fun registeredDeviceInfos(): List<RegisteredDevice> =
        companion.pairedAccessories.map { RegisteredDevice(it.deviceId, it.displayName) }

    fun endpoint(id: UUID): BluetoothEndpoint? = companion.accessory(id)?.endpoint

    override suspend fun removeDevice(id: UUID) {
        val accessory = companion.accessory(id) ?: return
        try {
            companion.removeAccessory(accessory)
        } catch (declined: CompanionSetupError.UserCancelled) {
            throw DevicePairingError.Cancelled()
        }
    }

    override suspend fun renameDevice(id: UUID) {
        val accessory = companion.accessory(id) ?: return
        companion.renameAccessory(accessory)
    }

    override suspend fun clearStaleRegistrations() {
        for (accessory in companion.pairedAccessories) {
            currentCoroutineContext().ensureActive()
            try { companion.removeAccessory(accessory) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { diagnostics.report("clearStale.${accessory.associationId}", failure) }
        }
    }

    override fun companionSetupDidRemoveAccessory(deviceId: UUID) {
        delegate?.devicePairingDidRemoveDevice(this, deviceId)
    }

    override fun companionSetupDidFailPairing(deviceId: UUID) {
        delegate?.devicePairingDidFailPairing(this, deviceId)
    }
}

/**
 * Selects the pairing implementation exactly once. Companion association is used when the
 * device declares `FEATURE_COMPANION_DEVICE_SETUP`; otherwise the in-app scan picker runs
 * (which additionally requires the `BLUETOOTH_SCAN` runtime permission).
 */
object DevicePairingFactory {
    enum class Kind { Companion, ScanFallback }

    fun select(companionDeviceSetupSupported: Boolean): Kind =
        if (companionDeviceSetupSupported) Kind.Companion else Kind.ScanFallback

    fun make(
        companionDeviceSetupSupported: Boolean,
        companion: () -> CompanionSetupServicing,
        diagnostics: ConnectivityDiagnostics = ConnectivityDiagnostics.NONE,
    ): DevicePairingService = when (select(companionDeviceSetupSupported)) {
        Kind.Companion -> CompanionPairingService(companion(), diagnostics)
        Kind.ScanFallback -> BluetoothScanPairingService()
    }
}
