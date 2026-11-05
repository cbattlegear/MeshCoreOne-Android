// AndroidOnly: WP-317 Scripted fakes for the remaining feature-owned ports.
package com.meshcoreone.android.feature.settings.device.support

import com.meshcoreone.android.core.contracts.domain.NotificationPreferencesPort
import com.meshcoreone.android.core.contracts.domain.PreRepeatSettingsAttempt
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.NotificationPreferences
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.feature.settings.device.ChatPreference
import com.meshcoreone.android.feature.settings.device.ChatPreferencePort
import com.meshcoreone.android.feature.settings.device.DeviceBatteryPort
import com.meshcoreone.android.feature.settings.device.DeviceBatterySnapshot
import com.meshcoreone.android.feature.settings.device.DevicePreferencePort
import com.meshcoreone.android.feature.settings.device.DeviceSettingsStorePort
import com.meshcoreone.android.feature.settings.device.DiagnosticsPort
import com.meshcoreone.android.feature.settings.device.GpsSource
import com.meshcoreone.android.feature.settings.device.LocationAuthorization
import com.meshcoreone.android.feature.settings.device.LocationPermissionPort
import com.meshcoreone.android.feature.settings.device.NodeIdentityPort
import com.meshcoreone.android.feature.settings.device.NotificationAuthorization
import com.meshcoreone.android.feature.settings.device.NotificationPermissionPort
import com.meshcoreone.android.feature.settings.device.RadioCatalogPort
import com.meshcoreone.android.feature.settings.device.RadioPresetOption
import com.meshcoreone.android.feature.settings.device.RegionDiscoveryOutcome
import com.meshcoreone.android.feature.settings.device.RegionDiscoveryPort
import com.meshcoreone.android.feature.settings.device.RegionSelectionPort
import com.meshcoreone.android.feature.settings.device.StaleNodeCleanupPort
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class FakeRegions(initial: RegionSelection? = null) : RegionSelectionPort {
    val flow = MutableStateFlow(initial)
    val sets = mutableListOf<RegionSelection?>()
    override val selection: StateFlow<RegionSelection?> get() = flow
    override fun set(selection: RegionSelection?) { sets += selection; flow.value = selection }
}

internal class FakeStore : DeviceSettingsStorePort {
    val calls = mutableListOf<String>()
    var ocvError: Throwable? = null
    override suspend fun updateOcvSettings(deviceId: UUID, preset: String, customArray: String?) {
        calls += "ocv($preset,$customArray)"
        ocvError?.let { throw it }
    }
    override fun savePreRepeatSettings() { calls += "savePreRepeat" }
    override fun clearPreRepeatSettings() { calls += "clearPreRepeat" }
    var beforeRepeatEdit: suspend () -> Unit = {}
    override suspend fun savePreRepeatSettings(expectedToken: com.meshcoreone.android.core.contracts.domain.SessionToken?) {
        beforeRepeatEdit()
        savePreRepeatSettings()
    }
    override suspend fun savePreRepeatSettings(
        expectedToken: com.meshcoreone.android.core.contracts.domain.SessionToken?, attempt: PreRepeatSettingsAttempt,
    ) = savePreRepeatSettings(expectedToken)
    override suspend fun abortPreRepeatSettings(
        expectedToken: com.meshcoreone.android.core.contracts.domain.SessionToken?, attempt: PreRepeatSettingsAttempt,
    ) {
        attempt.takeAbortSnapshot()
    }
    override suspend fun clearPreRepeatSettings(expectedToken: com.meshcoreone.android.core.contracts.domain.SessionToken?) {
        beforeRepeatEdit()
        clearPreRepeatSettings()
    }
    override fun addKnownRegion(region: String) { calls += "add($region)" }
    override fun removeKnownRegion(region: String) { calls += "remove($region)" }
}

internal val NORMAL_PRESETS = listOf(
    RadioPresetOption("us-915", "USA/Canada (915)", "North America"),
    RadioPresetOption("eu-868", "EU (868)", "Europe"),
)
internal val REPEAT_PRESETS = listOf(
    RadioPresetOption("rep-1", "Repeat 1", "", repeatSectionHeader = "A"),
    RadioPresetOption("rep-2", "Repeat 2", "", repeatSectionHeader = "B"),
)

internal class FakeCatalog : RadioCatalogPort {
    /** Frequency (kHz) to preset id for the normal presets; everything else is custom. */
    var byFrequency = mapOf(915_000u to "us-915", 868_000u to "eu-868")
    var repeatByFrequency = mapOf(910_525u to "rep-1")
    var nearestRepeat: String? = "rep-2"
    override fun visiblePresets(region: RegionSelection?, activeId: String?) = NORMAL_PRESETS
    override val repeatPresets get() = REPEAT_PRESETS
    override fun resolvedPreset(device: DeviceDTO, region: RegionSelection?) =
        byFrequency[device.frequency]?.let { id -> NORMAL_PRESETS.first { it.id == id } }
    override fun matchingPresetIds(device: DeviceDTO): Set<String> = setOfNotNull(byFrequency[device.frequency])
    override fun nearestRepeatPreset(frequencyKHz: UInt) = nearestRepeat?.let { id -> REPEAT_PRESETS.first { it.id == id } }
    override fun matchingRepeatPreset(frequencyKHz: UInt) = repeatByFrequency[frequencyKHz]?.let { id -> REPEAT_PRESETS.first { it.id == id } }
    override fun repeatFrequencyKHz(presetId: String): UInt? = mapOf("rep-1" to 910_525u, "rep-2" to 911_000u)[presetId]
    override fun regionDisplayName(region: RegionSelection) = region.countryCode
}

internal class FakeLocation(initial: LocationAuthorization = LocationAuthorization.AUTHORIZED) : LocationPermissionPort {
    val authorizationFlow = MutableStateFlow(initial)
    val requestingFlow = MutableStateFlow(false)
    var permissionRequests = 0
    var currentLocationRequests = 0
    var onRequestCurrentLocation: suspend () -> Unit = {}
    override val authorization: StateFlow<LocationAuthorization> get() = authorizationFlow
    override val isRequestingLocation: StateFlow<Boolean> get() = requestingFlow
    override fun requestPermissionIfNeeded() { permissionRequests++ }
    override suspend fun requestCurrentLocation() { currentLocationRequests++; onRequestCurrentLocation() }
}

internal class FakeDevicePreferences : DevicePreferencePort {
    val auto = mutableMapOf<UUID, Boolean>()
    val source = mutableMapOf<UUID, GpsSource>()
    override fun isAutoUpdateLocationEnabled(deviceId: UUID) = auto[deviceId] ?: false
    override fun setAutoUpdateLocationEnabled(enabled: Boolean, deviceId: UUID) { auto[deviceId] = enabled }
    override fun gpsSource(deviceId: UUID) = source[deviceId] ?: GpsSource.PHONE
    override fun hasSetGpsSource(deviceId: UUID) = deviceId in source
    override fun setGpsSource(source: GpsSource, deviceId: UUID) { this.source[deviceId] = source }
}

internal fun notificationPreferences(
    newContact: Boolean = false, contact: Boolean = false, repeater: Boolean = false, room: Boolean = false,
) = NotificationPreferences(
    contactMessagesEnabled = true, channelMessagesEnabled = true, roomMessagesEnabled = true,
    newContactDiscoveredEnabled = newContact, discoveryContactEnabled = contact, discoveryRepeaterEnabled = repeater,
    discoveryRoomEnabled = room, reactionNotificationsEnabled = true, soundEnabled = true, badgeEnabled = true, lowBatteryEnabled = true,
)

internal class FakeNotificationPreferences(initial: NotificationPreferences) : NotificationPreferencesPort {
    val flow = MutableStateFlow(initial)
    val updates = mutableListOf<NotificationPreferences>()
    override val preferences: StateFlow<NotificationPreferences> get() = flow
    override suspend fun update(preferences: NotificationPreferences) { updates += preferences; flow.value = preferences }
}

internal class FakeNotificationPermission(var status: NotificationAuthorization, var grant: Boolean = true) : NotificationPermissionPort {
    var requests = 0
    var requestError: Throwable? = null
    override suspend fun status() = status
    override suspend fun request(): Boolean { requests++; requestError?.let { throw it }; return grant }
}

internal class FakeStale : StaleNodeCleanupPort {
    val days = MutableStateFlow(0)
    val last = MutableStateFlow<Instant?>(null)
    val cleanups = mutableListOf<Boolean>()
    override val thresholdDays: StateFlow<Int> get() = days
    override val lastCleanup: StateFlow<Instant?> get() = last
    override fun setThresholdDays(days: Int) { this.days.value = days }
    override fun performCleanup(force: Boolean) { cleanups += force }
}

internal class FakeDiagnostics : DiagnosticsPort {
    var file: String? = "content://logs/1"
    var exportError: Throwable? = null
    var clearError: Throwable? = null
    var clears = 0
    override suspend fun createExportFile(): String? { exportError?.let { throw it }; return file }
    override suspend fun clearDebugLogs() { clears++; clearError?.let { throw it } }
}

internal class FakeBattery : DeviceBatteryPort {
    val flow = MutableStateFlow<DeviceBatterySnapshot?>(null)
    var fetches = 0
    var fetchError: Throwable? = null
    override val battery: StateFlow<DeviceBatterySnapshot?> get() = flow
    override suspend fun fetch() { fetches++; fetchError?.let { throw it } }
}

internal class FakeIdentity : NodeIdentityPort {
    val names = mutableListOf<String>()
    var error: Throwable? = null
    override suspend fun setNodeNameVerified(name: String): SelfInfo { names += name; error?.let { throw it }; return selfInfo() }
}

internal class FakeDiscovery(var outcome: RegionDiscoveryOutcome = RegionDiscoveryOutcome.NoRepeatersResponded) : RegionDiscoveryPort {
    val calls = mutableListOf<Pair<List<String>, Boolean>>()
    var gate: (suspend () -> Unit)? = null
    override suspend fun discover(knownRegions: List<String>, supportsAdHocRequest: Boolean): RegionDiscoveryOutcome {
        calls += knownRegions to supportsAdHocRequest
        gate?.invoke()
        return outcome
    }
}

internal class FakeChatPrefs : ChatPreferencePort {
    val flow = MutableStateFlow(ChatPreference.entries.associateWith { false })
    override val values get() = flow
    override fun set(preference: ChatPreference, value: Boolean) { flow.value = flow.value + (preference to value) }
}
