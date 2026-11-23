// AndroidOnly: WP-303 Onboarding permission port over the connectivity permission snapshot and runtime requests.
package com.meshcoreone.android.app.container.onboarding

import android.content.Context
import android.content.SharedPreferences
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
    fun notificationsEnabled(): Boolean
}

/** Completed requests and observed grants, not cached permission grants. */
class RequestedPermissions(private val preferences: SharedPreferences? = null) {
    private val lock = Any()
    private var names: Set<String> = emptySet()

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("platform_permission_requests", Context.MODE_PRIVATE),
    )

    private fun key(name: String): String = when (name) {
        OnboardingPermissionMapping.COARSE_LOCATION -> "location"
        else -> "onboarding.permission.$name"
    }

    fun record(permissions: Collection<String>) = synchronized(lock) {
        val missing = permissions.filterNot { contains(it) }
        if (missing.isNotEmpty()) {
            preferences?.edit()?.let { editor ->
                missing.forEach { editor.putBoolean(key(it), true) }
                check(editor.commit()) { "Unable to persist onboarding permission history" }
            }
            names = names + missing
        }
    }

    fun contains(name: String): Boolean = synchronized(lock) {
        name in names || preferences?.getBoolean(key(name), false) == true
    }

    /** Kept for callers that model an entire completed request scope. */
    fun mark(kind: OnboardingPermissionKind, bluetoothConnectionOnly: Boolean = false) = synchronized(lock) {
        record(OnboardingPermissionMapping.permissionsFor(kind, 37, !bluetoothConnectionOnly, bluetoothConnectionOnly))
    }
}

object OnboardingPermissionMapping {
    const val COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"

    fun map(
        facts: PermissionSnapshot, locationGranted: Boolean, notificationsEnabled: Boolean,
        requested: RequestedPermissions, scanFallback: Boolean,
        bluetoothConnectionOnly: Boolean = false,
    ): OnboardingPermissionSnapshot {
        val mode = if (scanFallback) PairingMode.ScanFallback else PairingMode.Companion
        fun status(granted: Boolean, missing: Collection<String>) = when {
            granted -> PermissionStatus.GRANTED
            missing.any(requested::contains) -> PermissionStatus.DENIED
            else -> PermissionStatus.NOT_DETERMINED
        }
        fun missing(feature: ConnectivityFeature) =
            ConnectivityPermissionPolicy.required(feature, facts.sdkInt, mode) - facts.granted
        fun featureStatus(feature: ConnectivityFeature): PermissionStatus {
            val missing = missing(feature)
            return status(missing.isEmpty(), missing.map(ConnectivityPermission::manifestName))
        }
        return OnboardingPermissionSnapshot(
            location = status(locationGranted, listOf(COARSE_LOCATION)),
            notifications = if (missing(ConnectivityFeature.ConnectionNotification).isEmpty()) {
                if (notificationsEnabled) PermissionStatus.GRANTED else PermissionStatus.DENIED
            } else {
                featureStatus(ConnectivityFeature.ConnectionNotification)
            },
            bluetooth = featureStatus(
                if (bluetoothConnectionOnly) ConnectivityFeature.BleConnection else ConnectivityFeature.BlePairing,
            ),
            localNetwork = featureStatus(ConnectivityFeature.LanConnection),
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

    private fun read(): OnboardingPermissionSnapshot {
        val snapshot = facts.snapshot()
        val locationGranted = facts.locationGranted()
        requests.record(snapshot.granted.map(ConnectivityPermission::manifestName) +
            if (locationGranted) listOf(OnboardingPermissionMapping.COARSE_LOCATION) else emptyList())
        return OnboardingPermissionMapping.map(
            snapshot, locationGranted, facts.notificationsEnabled(), requests, scanFallback, bluetoothConnectionOnly,
        )
    }

    override fun refresh() {
        onLocationChanged()
        mutable.value = read()
    }

    /** Records one finished runtime request (any grant outcome) and republishes the snapshot. */
    fun onRequestFinished(kind: OnboardingPermissionKind, answered: Collection<String> = permissionsFor(kind, facts.snapshot().sdkInt)) {
        requests.record(answered.intersect(permissionsFor(kind, facts.snapshot().sdkInt).toSet()))
        refresh()
    }

    fun permissionsFor(kind: OnboardingPermissionKind, sdkInt: Int): List<String> =
        OnboardingPermissionMapping.permissionsFor(kind, sdkInt, scanFallback, bluetoothConnectionOnly)
}
