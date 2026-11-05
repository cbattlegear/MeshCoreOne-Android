// AndroidOnly: WP-303 Onboarding permission port over the connectivity permission snapshot and runtime requests.
package com.meshcoreone.android.app.container.onboarding

import com.meshcoreone.android.core.connectivity.permissions.ConnectivityFeature
import com.meshcoreone.android.core.connectivity.permissions.ConnectivityPermission
import com.meshcoreone.android.core.connectivity.permissions.ConnectivityPermissionPolicy
import com.meshcoreone.android.core.connectivity.permissions.PairingMode
import com.meshcoreone.android.core.connectivity.permissions.PermissionSnapshot
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionKind
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionPort
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionSnapshot
import com.meshcoreone.android.feature.onboarding.PermissionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Platform facts the port reads; the production source is [com.meshcoreone.android.core.connectivity.permissions.AndroidPermissionState]. */
interface OnboardingPermissionFacts {
    fun snapshot(): PermissionSnapshot
    /** Approximate location (region detection only needs coarse). */
    fun locationGranted(): Boolean
}

/** Which kinds were already asked this process: a non-granted kind that was asked is `DENIED`, not `NOT_DETERMINED`. */
class RequestedPermissions {
    private val lock = Any()
    private var kinds: Set<OnboardingPermissionKind> = emptySet()
    private var connectionBluetoothRequested = false
    fun mark(kind: OnboardingPermissionKind, bluetoothConnectionOnly: Boolean = false) = synchronized(lock) {
        if (bluetoothConnectionOnly && kind == OnboardingPermissionKind.BLUETOOTH) connectionBluetoothRequested = true
        else kinds = kinds + kind
    }
    fun asSet(bluetoothConnectionOnly: Boolean = false): Set<OnboardingPermissionKind> = synchronized(lock) {
        if (bluetoothConnectionOnly && connectionBluetoothRequested) kinds + OnboardingPermissionKind.BLUETOOTH else kinds
    }
}

object OnboardingPermissionMapping {
    const val COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"

    fun map(
        facts: PermissionSnapshot, locationGranted: Boolean, requested: Set<OnboardingPermissionKind>, scanFallback: Boolean,
        bluetoothConnectionOnly: Boolean = false,
    ): OnboardingPermissionSnapshot {
        val mode = if (scanFallback) PairingMode.ScanFallback else PairingMode.Companion
        fun status(kind: OnboardingPermissionKind, granted: Boolean) = when {
            granted -> PermissionStatus.GRANTED
            kind in requested -> PermissionStatus.DENIED
            else -> PermissionStatus.NOT_DETERMINED
        }
        fun satisfied(feature: ConnectivityFeature) =
            (ConnectivityPermissionPolicy.required(feature, facts.sdkInt, mode) - facts.granted).isEmpty()
        return OnboardingPermissionSnapshot(
            location = status(OnboardingPermissionKind.LOCATION, locationGranted),
            notifications = status(OnboardingPermissionKind.NOTIFICATIONS, satisfied(ConnectivityFeature.ConnectionNotification)),
            bluetooth = status(OnboardingPermissionKind.BLUETOOTH, satisfied(
                if (bluetoothConnectionOnly) ConnectivityFeature.BleConnection else ConnectivityFeature.BlePairing,
            )),
            localNetwork = status(OnboardingPermissionKind.LOCAL_NETWORK, satisfied(ConnectivityFeature.LanConnection)),
            bluetoothAdapterEnabled = facts.bluetoothEnabled,
        )
    }

    /** Manifest permissions to request for [kind] on [sdkInt]; empty means the platform grants it implicitly. */
    fun permissionsFor(
        kind: OnboardingPermissionKind, sdkInt: Int, scanFallback: Boolean, bluetoothConnectionOnly: Boolean = false,
    ): List<String> {
        val mode = if (scanFallback) PairingMode.ScanFallback else PairingMode.Companion
        fun names(feature: ConnectivityFeature): List<String> =
            ConnectivityPermissionPolicy.required(feature, sdkInt, mode).map(ConnectivityPermission::manifestName).sorted()
        return when (kind) {
            OnboardingPermissionKind.LOCATION -> listOf(COARSE_LOCATION)
            OnboardingPermissionKind.NOTIFICATIONS -> names(ConnectivityFeature.ConnectionNotification)
            OnboardingPermissionKind.BLUETOOTH -> names(
                if (bluetoothConnectionOnly) ConnectivityFeature.BleConnection else ConnectivityFeature.BlePairing,
            )
            OnboardingPermissionKind.LOCAL_NETWORK -> names(ConnectivityFeature.LanConnection)
        }
    }
}

class AndroidOnboardingPermissionPort(
    private val facts: OnboardingPermissionFacts,
    private val requests: RequestedPermissions,
    private val scanFallback: Boolean,
    private val onLocationChanged: () -> Unit = {},
    private val bluetoothConnectionOnly: Boolean = false,
) : OnboardingPermissionPort {
    private val mutable = MutableStateFlow(read())
    override val snapshot: StateFlow<OnboardingPermissionSnapshot> = mutable.asStateFlow()

    private fun read() = OnboardingPermissionMapping.map(
        facts.snapshot(), facts.locationGranted(), requests.asSet(bluetoothConnectionOnly), scanFallback, bluetoothConnectionOnly,
    )

    override fun refresh() {
        onLocationChanged()
        mutable.value = read()
    }

    /** Records one finished runtime request (any grant outcome) and republishes the snapshot. */
    fun onRequestFinished(kind: OnboardingPermissionKind) {
        requests.mark(kind, bluetoothConnectionOnly)
        refresh()
    }

    fun permissionsFor(kind: OnboardingPermissionKind, sdkInt: Int): List<String> =
        OnboardingPermissionMapping.permissionsFor(kind, sdkInt, scanFallback, bluetoothConnectionOnly)
}
