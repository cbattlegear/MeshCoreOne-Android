// AndroidOnly: WP-317 Scripted fakes for the feature-owned ports (the real core:services are not on this module's classpath).
package com.meshcoreone.android.feature.settings.device.support

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.AdvertLocationPolicy
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoveUnfavoritedResult
import com.meshcoreone.android.core.model.TelemetryModes
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.feature.settings.device.DeviceGpsState
import com.meshcoreone.android.feature.settings.device.DeviceMaintenancePort
import com.meshcoreone.android.feature.settings.device.SettingsConnection
import com.meshcoreone.android.feature.settings.device.SettingsRadioPort
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal val RADIO = RadioId(UUID(1, 2))

internal fun device(
    id: UUID = UUID.randomUUID(),
    firmwareVersion: UByte = 9u,
    firmwareVersionString: String = "v1.13.0",
    frequency: UInt = 915_000u,
    bandwidth: UInt = 250_000u,
    spreadingFactor: UByte = 10u,
    codingRate: UByte = 5u,
    txPower: Byte = 20,
    maxTxPower: Byte = 20,
    clientRepeat: Boolean = false,
    methods: List<ConnectionMethod> = emptyList(),
    manualAddContacts: Boolean = false,
    autoAddConfig: UByte = 0u,
    autoAddMaxHops: UByte = 0u,
    telemetryBase: UByte = 2u,
    telemetryLoc: UByte = 0u,
    telemetryEnv: UByte = 0u,
    advertLocationPolicy: UByte = 0u,
    blePin: UInt = 0u,
    pathHashMode: UByte = 0u,
    latitude: Double = 0.0,
    longitude: Double = 0.0,
    ocvPreset: String? = null,
    customOCVArrayString: String? = null,
    knownRegions: List<String> = emptyList(),
    defaultFloodScopeName: String? = null,
): DeviceDTO = DeviceDTO(
    id = id, radioId = RADIO, publicKey = Bytes(ByteArray(32) { 1 }), nodeName = "TestDevice",
    firmwareVersion = firmwareVersion, firmwareVersionString = firmwareVersionString, manufacturerName = "TestMfg",
    buildDate = "01 Jan 2025", maxContacts = 100u, maxChannels = 8u, frequency = frequency, bandwidth = bandwidth,
    spreadingFactor = spreadingFactor, codingRate = codingRate, txPower = txPower, maxTxPower = maxTxPower,
    latitude = latitude, longitude = longitude, blePin = blePin, clientRepeat = clientRepeat, pathHashMode = pathHashMode,
    manualAddContacts = manualAddContacts, autoAddConfig = autoAddConfig, autoAddMaxHops = autoAddMaxHops, multiAcks = 2u,
    telemetryModeBase = telemetryBase, telemetryModeLoc = telemetryLoc, telemetryModeEnv = telemetryEnv,
    advertLocationPolicy = advertLocationPolicy, ocvPreset = ocvPreset, customOCVArrayString = customOCVArrayString,
    connectionMethods = methods.snapshot(), knownRegions = knownRegions.snapshot(), defaultFloodScopeName = defaultFloodScopeName,
)

internal class FakeConnection(
    device: DeviceDTO? = null,
    state: DeviceConnectionState = DeviceConnectionState.READY,
) : SettingsConnection {
    val deviceFlow = MutableStateFlow(device)
    val stateFlow = MutableStateFlow(state)
    val startupFlow = MutableStateFlow(true)
    override val connectedDevice: StateFlow<DeviceDTO?> get() = deviceFlow
    override val connectionState: StateFlow<DeviceConnectionState> get() = stateFlow
    override val startupReadsAllowed: StateFlow<Boolean> get() = startupFlow
}

internal fun selfInfo(): SelfInfo = SelfInfo(
    0u, 20, 20, Bytes(ByteArray(32) { 1 }), 0.0, 0.0, 2u, 0u, 0u, 0u, 2u, false, 915.0, 250.0, 10u, 5u, "TestDevice",
)

/** Records every call as text; any method named in [failures] throws the queued error once per entry. */
internal class FakeSettingsPort(private val connection: FakeConnection? = null) : SettingsRadioPort {
    val calls = mutableListOf<String>()
    private val failures = mutableMapOf<String, ArrayDeque<Throwable>>()
    var gpsState = DeviceGpsState(isSupported = false, isEnabled = false)
    var floodScope: String? = null
    var onCall: suspend (String) -> Unit = {}

    fun failNext(method: String, error: Throwable) = failures.getOrPut(method) { ArrayDeque() }.addLast(error)

    private suspend fun record(method: String, detail: String = "") {
        calls += if (detail.isEmpty()) method else "$method($detail)"
        onCall(method)
        failures[method]?.removeFirstOrNull()?.let { throw it }
    }

    override suspend fun getSelfInfo(): SelfInfo { record("getSelfInfo"); return selfInfo() }
    override suspend fun setRadioParamsVerified(
        frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte, codingRate: UByte,
        clientRepeat: Boolean?, appliedRadioPresetID: String?,
    ): SelfInfo {
        record("setRadioParamsVerified", "$frequencyKHz,$bandwidthKHz,$spreadingFactor,$codingRate,$clientRepeat,$appliedRadioPresetID")
        return selfInfo()
    }
    override suspend fun setTxPowerVerified(power: Byte): SelfInfo { record("setTxPowerVerified", "$power"); return selfInfo() }
    override suspend fun applyRadioPresetVerified(presetId: String): SelfInfo {
        record("applyRadioPresetVerified", presetId); return selfInfo()
    }
    override suspend fun setOtherParamsVerified(
        device: DeviceDTO, autoAddContacts: Boolean?, telemetryModes: TelemetryModes?,
        advertLocationPolicy: AdvertLocationPolicy?, multiAcks: UByte?,
    ): SelfInfo {
        record(
            "setOtherParamsVerified",
            "auto=$autoAddContacts,tel=${telemetryModes?.let { "${it.base}/${it.location}/${it.environment}" }},policy=$advertLocationPolicy" +
                (multiAcks?.let { ",acks=$it" } ?: ""),
        )
        return selfInfo()
    }
    override suspend fun setAutoAddConfigVerified(config: AutoAddConfig): AutoAddConfig {
        record("setAutoAddConfigVerified", "${config.bitmask},${config.maxHops}"); return config
    }
    override suspend fun refreshAutoAddConfig() = record("refreshAutoAddConfig")
    override suspend fun setPathHashModeVerified(mode: UByte): UByte { record("setPathHashModeVerified", "$mode"); return mode }
    override suspend fun getDeviceGPSState(): DeviceGpsState { record("getDeviceGPSState"); return gpsState }
    override suspend fun setDeviceGPSEnabledVerified(enabled: Boolean): DeviceGpsState {
        record("setDeviceGPSEnabledVerified", "$enabled"); gpsState = gpsState.copy(isEnabled = enabled); return gpsState
    }
    override suspend fun refreshDeviceInfo() = record("refreshDeviceInfo")
    override suspend fun getDefaultFloodScope(): String? { record("getDefaultFloodScope"); return floodScope }
    override suspend fun setDefaultFloodScopeVerified(name: String?): String? { record("setDefaultFloodScopeVerified", "$name"); return name }
    override suspend fun setBlePin(pin: UInt) = record("setBlePin", "$pin")
    override suspend fun reboot() = record("reboot")
    override suspend fun factoryReset() = record("factoryReset")
    override suspend fun importPrivateKey(key: Bytes) = record("importPrivateKey", "${key.size}")
}

internal class FakeMaintenance : DeviceMaintenancePort {
    val calls = mutableListOf<String>()
    var forgetError: Throwable? = null
    var count = 0
    var count_error: Throwable? = null
    var removal = RemoveUnfavoritedResult(0, 0)
    var removalGate: (suspend () -> Unit)? = null

    override suspend fun forgetDevice(deleteData: Boolean) {
        calls += "forget($deleteData)"
        forgetError?.let { throw it }
    }
    override suspend fun forgetDevice(id: UUID) { calls += "forgetById($id)" }
    override suspend fun unfavoritedNodeCount(): Int { count_error?.let { throw it }; return count }
    override suspend fun removeUnfavoritedNodes(): RemoveUnfavoritedResult { removalGate?.invoke(); return removal }
}
