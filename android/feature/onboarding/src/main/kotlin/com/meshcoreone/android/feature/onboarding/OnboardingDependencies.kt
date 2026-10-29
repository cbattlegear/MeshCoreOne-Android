// AndroidOnly: WP-305 feature-owned dependency seams (features may not depend on services/runtime/connectivity).
package com.meshcoreone.android.feature.onboarding

import androidx.compose.runtime.Composable
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.ui.RSSITuning
import com.meshcoreone.android.core.ui.UiText
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class OnboardingPermissionKind { NOTIFICATIONS, LOCATION, BLUETOOTH, LOCAL_NETWORK }

/**
 * Per-permission status. `GRANTED` is also reported where the platform grants implicitly
 * (notifications below API 33, local network below API 37). `DENIED` means requested and not
 * granted, so the card offers Settings instead of another prompt.
 */
data class OnboardingPermissionSnapshot(
    val location: PermissionStatus = PermissionStatus.NOT_DETERMINED,
    val notifications: PermissionStatus = PermissionStatus.NOT_DETERMINED,
    val bluetooth: PermissionStatus = PermissionStatus.NOT_DETERMINED,
    val localNetwork: PermissionStatus = PermissionStatus.GRANTED,
    val bluetoothAdapterEnabled: Boolean = true,
) {
    fun status(kind: OnboardingPermissionKind): PermissionStatus = when (kind) {
        OnboardingPermissionKind.NOTIFICATIONS -> notifications
        OnboardingPermissionKind.LOCATION -> location
        OnboardingPermissionKind.BLUETOOTH -> bluetooth
        OnboardingPermissionKind.LOCAL_NETWORK -> localNetwork
    }
}

interface OnboardingPermissionPort {
    val snapshot: StateFlow<OnboardingPermissionSnapshot>
    /** Re-reads the platform; called on resume and after each runtime-permission result. */
    fun refresh()
}

data class OnboardingConnectionSnapshot(
    val isReady: Boolean = false,
    val isBusy: Boolean = false,
    val isDemoDevice: Boolean = false,
)

/** Outcome of a pairing or connect attempt; user cancellation is benign, never an error. */
sealed interface OnboardingConnectOutcome {
    data object Connected : OnboardingConnectOutcome
    data object Cancelled : OnboardingConnectOutcome
    /** A pairing flow is already running, or the system chooser has no host yet; nothing happened. */
    data object Ignored : OnboardingConnectOutcome
    /** `retryDeviceId` non-null means the device is held by another app and "Retry connection" applies. */
    data class Failed(
        val message: UiText,
        val retryDeviceId: UUID? = null,
        /** The app-wide typed recovery dialog owns presentation and its device-changing actions. */
        val presentedByConnectionUi: Boolean = false,
    ) : OnboardingConnectOutcome
}

data class OnboardingScanDiscovery(val id: UUID, val name: String?, val tier: RSSITuning.SignalTier)

/** In-app scanner used only when CompanionDeviceManager association is unavailable. */
interface OnboardingScanFallbackPort {
    val isRequested: StateFlow<Boolean>
    fun discoveries(): Flow<List<OnboardingScanDiscovery>>
    fun select(id: UUID)
    fun cancel()
}

interface OnboardingPairingPort {
    val connection: StateFlow<OnboardingConnectionSnapshot>
    val pairedAccessoryCount: StateFlow<Int>
    val hasLastConnectedDevice: Boolean
    val scanFallback: OnboardingScanFallbackPort?
    suspend fun pairNewDevice(): OnboardingConnectOutcome
    suspend fun retryConnection(deviceId: UUID): OnboardingConnectOutcome
    suspend fun clearStalePairings()
}

interface OnboardingWiFiPort {
    suspend fun connect(host: String, port: Int): OnboardingConnectOutcome
}

interface OnboardingDemoPort {
    val isEnabled: StateFlow<Boolean>
    fun unlock()
    suspend fun connectDemo(): OnboardingConnectOutcome
}

data class OnboardingCountry(val code: String, val displayName: String)
data class OnboardingSubdivision(val code: String, val displayName: String)
enum class AdministrativeAreaKind { PROVINCE, STATE }
/** Mirror of the `RadioPreset` fields the picker shows; the adapter applies availability filtering and name sort. */
data class OnboardingPresetOption(val id: String, val name: String, val frequencyMHz: Double)

interface OnboardingRegionCatalog {
    fun countries(): List<OnboardingCountry>
    fun showsSubdivisionPicker(countryCode: String?): Boolean
    fun subdivisions(countryCode: String?): List<OnboardingSubdivision>
    fun administrativeAreaKind(countryCode: String?): AdministrativeAreaKind
    fun displayName(region: RegionSelection): String
    fun countryDisplayName(countryCode: String): String
    fun subdivisionDisplayName(code: String): String?
    /** Selectable presets for the region (locale-sorted fallback when null/empty), sorted by name. */
    fun presets(region: RegionSelection?): List<OnboardingPresetOption>
}

interface OnboardingRegionPort {
    val catalog: OnboardingRegionCatalog
    val selection: StateFlow<RegionSelection?>
    fun setSelection(selection: RegionSelection)
    /** Location-based detection; null on denial, timeout or no network (never throws). */
    suspend fun resolveRegion(): RegionSelection?
}

sealed interface OnboardingPresetOutcome {
    data object Applied : OnboardingPresetOutcome
    /** The simulated demo radio has nothing to configure. */
    data object NoRadioToConfigure : OnboardingPresetOutcome
    data object NotConnected : OnboardingPresetOutcome
    data class Retryable(val message: UiText) : OnboardingPresetOutcome
    data class Failed(val message: UiText) : OnboardingPresetOutcome
}

interface OnboardingPresetPort {
    val canApply: StateFlow<Boolean>
    suspend fun apply(presetId: String): OnboardingPresetOutcome
}

/** Everything the app layer (WP-303) binds for the onboarding flow. */
interface OnboardingFeatureDependencies {
    val flags: OnboardingFlagStore
    val permissions: OnboardingPermissionPort
    val pairing: OnboardingPairingPort
    val wifi: OnboardingWiFiPort
    val demo: OnboardingDemoPort
    val region: OnboardingRegionPort
    val presets: OnboardingPresetPort
    fun openAppSettings()

    /**
     * Activity-result bridge: the app returns a launcher (rememberLauncherForActivityResult) that
     * requests one runtime permission just in time and calls [onResult] afterwards. Kept as a
     * composable seam because activity-compose is not on this module's compile classpath.
     */
    @Composable
    fun rememberPermissionRequester(onResult: () -> Unit): (OnboardingPermissionKind) -> Unit

    /** System-back bridge (BackHandler lives in activity-compose); steps pop the onboarding path while [enabled]. */
    @Composable
    fun InterceptBack(enabled: Boolean, onBack: () -> Unit)
}
