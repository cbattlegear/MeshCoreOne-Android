// AndroidOnly: WP-305 Test doubles for the onboarding dependency ports.
package com.meshcoreone.android.feature.onboarding

import com.meshcoreone.android.core.model.RegionSelection
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakePermissions(initial: OnboardingPermissionSnapshot = OnboardingPermissionSnapshot()) : OnboardingPermissionPort {
    val mutable = MutableStateFlow(initial)
    var refreshCount = 0
    override val snapshot: StateFlow<OnboardingPermissionSnapshot> get() = mutable
    override fun refresh() { refreshCount += 1 }
}

class FakePairing(var outcome: OnboardingConnectOutcome = OnboardingConnectOutcome.Connected) : OnboardingPairingPort {
    override val connection = MutableStateFlow(OnboardingConnectionSnapshot())
    override val pairedAccessoryCount = MutableStateFlow(0)
    override var hasLastConnectedDevice = false
    override val scanFallback: OnboardingScanFallbackPort? = null
    var pairCalls = 0
    var retried: UUID? = null
    var cleared = 0
    var throwOnPair: Throwable? = null
    override suspend fun pairNewDevice(): OnboardingConnectOutcome {
        pairCalls += 1
        throwOnPair?.let { throw it }
        return outcome
    }
    override suspend fun retryConnection(deviceId: UUID): OnboardingConnectOutcome { retried = deviceId; return outcome }
    override suspend fun clearStalePairings() { cleared += 1 }
}

class FakeDemo(var outcome: OnboardingConnectOutcome = OnboardingConnectOutcome.Connected) : OnboardingDemoPort {
    override val isEnabled = MutableStateFlow(false)
    var unlocked = 0
    var beforeUnlock: suspend () -> Unit = {}
    override fun unlock() { unlocked += 1; isEnabled.value = true }
    override suspend fun unlockAndAwait() { beforeUnlock(); unlock() }
    override suspend fun connectDemo(): OnboardingConnectOutcome = outcome
}

class FakeWiFi(var outcome: OnboardingConnectOutcome = OnboardingConnectOutcome.Connected) : OnboardingWiFiPort {
    var lastHost: String? = null
    var lastPort: Int? = null
    override suspend fun connect(host: String, port: Int): OnboardingConnectOutcome { lastHost = host; lastPort = port; return outcome }
}

class FakeCatalog(private val presets: Map<String?, List<OnboardingPresetOption>> = emptyMap()) : OnboardingRegionCatalog {
    override fun countries() = listOf(OnboardingCountry("US", "United States"), OnboardingCountry("CA", "Canada"))
    override fun showsSubdivisionPicker(countryCode: String?) = countryCode == "US"
    override fun subdivisions(countryCode: String?) = if (countryCode == "US") listOf(OnboardingSubdivision("TX", "Texas")) else emptyList()
    override fun administrativeAreaKind(countryCode: String?) = if (countryCode == "CA") AdministrativeAreaKind.PROVINCE else AdministrativeAreaKind.STATE
    override fun displayName(region: RegionSelection) = region.countryCode
    override fun countryDisplayName(countryCode: String) = countryCode
    override fun subdivisionDisplayName(code: String): String? = code
    override fun presets(region: RegionSelection?) = presets[region?.countryCode] ?: presets[null].orEmpty()
}

class FakeRegion(var detected: RegionSelection? = null, presets: Map<String?, List<OnboardingPresetOption>> = emptyMap()) : OnboardingRegionPort {
    override val catalog = FakeCatalog(presets)
    override val selection = MutableStateFlow<RegionSelection?>(null)
    var resolveCalls = 0
    var throwOnResolve: Throwable? = null
    override fun setSelection(selection: RegionSelection) { this.selection.value = selection }
    override suspend fun resolveRegion(): RegionSelection? {
        resolveCalls += 1
        throwOnResolve?.let { throw it }
        return detected
    }
}

class FakePresets(var outcome: OnboardingPresetOutcome = OnboardingPresetOutcome.Applied) : OnboardingPresetPort {
    override val canApply = MutableStateFlow(true)
    val applied = mutableListOf<String>()
    override suspend fun apply(presetId: String): OnboardingPresetOutcome { applied += presetId; return outcome }
}
