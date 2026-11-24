// AndroidOnly: WP-206 CompanionDeviceManager association, chooser result, bond removal and presence-observation adapters with API 31-37 guards.
package com.meshcoreone.android.core.connectivity.platform

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.Parcelable
import com.meshcoreone.android.core.connectivity.ConnectivityDiagnostics
import com.meshcoreone.android.core.connectivity.pairing.ChooserRequests
import com.meshcoreone.android.core.connectivity.pairing.CompanionAssociation
import com.meshcoreone.android.core.connectivity.pairing.CompanionAssociationEvents
import com.meshcoreone.android.core.connectivity.pairing.CompanionChooserHost
import com.meshcoreone.android.core.connectivity.pairing.CompanionDeviceGateway
import com.meshcoreone.android.core.connectivity.pairing.CompanionDiscoveryCriteria
import com.meshcoreone.android.core.connectivity.pairing.CompanionFailureMapping
import com.meshcoreone.android.core.connectivity.pairing.CompanionFailureOutcome
import com.meshcoreone.android.core.connectivity.pairing.CompanionSetupError
import com.meshcoreone.android.core.connectivity.pairing.DeviceEndpointIdentity
import java.util.UUID
import java.util.concurrent.Executor

/**
 * CompanionDeviceManager boundary. `associate` only records an association through the system
 * chooser: it never opens GATT, never bonds, and grants no blanket background exemption.
 */
class AndroidCompanionDeviceGateway(
    context: Context,
    private val diagnostics: ConnectivityDiagnostics = ConnectivityDiagnostics.NONE,
) : CompanionDeviceGateway {
    private val context = context.applicationContext
    private val manager: CompanionDeviceManager? = context.getSystemService(CompanionDeviceManager::class.java)
    private val mainExecutor: Executor = context.mainExecutor
    private val requests = ChooserRequests<CompanionAssociationEvents>()

    override val isSupported: Boolean
        get() = manager != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    override fun associations(): List<CompanionAssociation> {
        val cdm = manager ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.myAssociations.mapNotNull { toAssociation(it, advertisedName = null) }
        } else {
            @Suppress("DEPRECATION")
            cdm.associations.mapNotNull { address -> legacyAssociation(address, name = null) }
        }
    }

    override fun associate(events: CompanionAssociationEvents) {
        val cdm = manager ?: throw CompanionSetupError.SessionNotActive()
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(CompanionDiscoveryCriteria.bluetoothServiceUuid)).build())
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build()
        val requestId = requests.register(events)
        val callback = object : CompanionDeviceManager.Callback() {
            @Deprecated("API 31-32 chooser delivery")
            override fun onDeviceFound(intentSender: IntentSender) = events.onChooserPending(intentSender, requestId)
            override fun onAssociationPending(intentSender: IntentSender) = events.onChooserPending(intentSender, requestId)
            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                // API 33 learns the advertised name only from the activity result: keep that request open.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) requests.finish(events)
                val association = toAssociation(associationInfo, advertisedName(associationInfo))
                if (association == null) {
                    // The system created an association we cannot address: remove it rather than strand it.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) disassociateId(associationInfo.id)
                    events.onFailure(CompanionSetupError.NoBluetoothIdentifier())
                } else {
                    events.onAssociationCreated(association)
                }
            }
            /** API 36+ typed failure codes. */
            override fun onFailure(errorCode: Int, error: CharSequence?) =
                deliver(events, CompanionFailureMapping.fromCode(errorCode))
            /** API 31-35: the AOSP reason string (`user_rejected`, `discovery_timeout`, ...). */
            override fun onFailure(error: CharSequence?) = deliver(events, CompanionFailureMapping.fromReason(error))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) cdm.associate(request, mainExecutor, callback)
        else @Suppress("DEPRECATION") cdm.associate(request, callback, Handler(Looper.getMainLooper()))
    }

    /**
     * The UI host forwards the chooser's activity result here with the request id it was launched
     * with. Only a correlated request can decide whether a selection is an orphan: an unknown or
     * duplicate result does not prove ownership of the address's current association.
     */
    fun onChooserResult(requestId: UUID, resultCode: Int, data: Intent?) {
        val events = requests.take(requestId)
        if (events == null) {
            diagnostics.report("cdm.chooser.uncorrelatedResult", null)
            return
        }
        if (resultCode == CompanionChooserHost.RESULT_LAUNCH_FAILED) {
            events.onFailure(CompanionSetupError.PickerRestricted())
            return
        }
        if (resultCode != Activity.RESULT_OK) { events.onDismissed(); return }
        val device = legacyDevice(data)
        val address = addressOf(device)
        val name = (device as? ScanResult)?.scanRecord?.deviceName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // onAssociationCreated delivers the association; API 33 only learns the advertised name here.
            if (address != null && name != null) {
                runCatching { DeviceEndpointIdentity.deviceId(address) }.getOrNull()?.let { events.onAssociationNamed(it, name) }
            }
            return
        }
        val association = address?.let { legacyAssociation(it, name) }
        if (association == null) events.onFailure(CompanionSetupError.NoBluetoothIdentifier())
        else events.onAssociationCreated(association)
    }

    /** API 36+ removes the system bond through CDM first; older releases cannot (see WP-206 A-03). */
    override suspend fun disassociate(association: CompanionAssociation) {
        val cdm = manager ?: throw CompanionSetupError.SessionNotActive()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && association.associationId >= 0) {
            try {
                if (!cdm.removeBond(association.associationId)) diagnostics.report("cdm.removeBond.notBonded", null)
            } catch (denied: SecurityException) {
                // BLUETOOTH_CONNECT revoked: the association is still removed below; the bond stays.
                diagnostics.report("cdm.removeBond.denied", denied)
            } catch (failure: RuntimeException) {
                diagnostics.report("cdm.removeBond", failure)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && association.associationId >= 0) {
            cdm.disassociate(association.associationId)
        } else {
            @Suppress("DEPRECATION") cdm.disassociate(association.address)
        }
    }

    /** Presence observation binds `MeshCompanionDeviceService` while the radio is near (API 36 request form). */
    override fun observePresence(association: CompanionAssociation, observe: Boolean) {
        val cdm = manager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && association.associationId >= 0) {
                val request = ObservingDevicePresenceRequest.Builder().setAssociationId(association.associationId).build()
                if (observe) cdm.startObservingDevicePresence(request) else cdm.stopObservingDevicePresence(request)
            } else {
                @Suppress("DEPRECATION")
                if (observe) cdm.startObservingDevicePresence(association.address) else cdm.stopObservingDevicePresence(association.address)
            }
        } catch (failure: Exception) {
            diagnostics.report(if (observe) "cdm.observePresence" else "cdm.stopObservingPresence", failure)
        }
    }

    private fun deliver(events: CompanionAssociationEvents, outcome: CompanionFailureOutcome) {
        requests.finish(events)
        when (outcome) {
            CompanionFailureOutcome.Dismissed -> events.onDismissed()
            is CompanionFailureOutcome.Failed -> events.onFailure(outcome.error)
        }
    }

    private fun disassociateId(associationId: Int) {
        val cdm = manager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try { cdm.disassociate(associationId) } catch (failure: RuntimeException) { diagnostics.report("cdm.disassociate", failure) }
    }

    private fun legacyAssociation(address: String, name: String?): CompanionAssociation? =
        runCatching { DeviceEndpointIdentity.endpoint(address, null) }.getOrNull()?.let {
            CompanionAssociation(-1, it.deviceId, it.address, name?.takeIf(String::isNotBlank) ?: CompanionDiscoveryCriteria.DEFAULT_ACCESSORY_NAME)
        }

    private fun addressOf(device: Any?): String? = when (device) {
        is ScanResult -> device.device.address
        is BluetoothDevice -> device.address
        else -> null
    }

    private fun legacyDevice(data: Intent?): Any? {
        data ?: return null
        @Suppress("DEPRECATION")
        return data.getParcelableExtra<Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)
    }

    companion object {
        /** API 34+: the advertised local name of the device chosen at association time (no permission needed). */
        fun advertisedName(info: AssociationInfo): String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.associatedDevice?.bleDevice?.scanRecord?.deviceName
            else null

        fun toAssociation(info: AssociationInfo, advertisedName: String?): CompanionAssociation? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            val address = info.deviceMacAddress?.toString() ?: return null
            val endpoint = runCatching { DeviceEndpointIdentity.endpoint(address, info.id) }.getOrNull() ?: return null
            // AssociationInfo.displayName is only set for self-managed associations.
            val name = advertisedName?.takeIf { it.isNotBlank() }
                ?: info.displayName?.toString()?.takeIf { it.isNotBlank() }
                ?: CompanionDiscoveryCriteria.DEFAULT_ACCESSORY_NAME
            return CompanionAssociation(info.id, endpoint.deviceId, endpoint.address, name)
        }
    }
}
