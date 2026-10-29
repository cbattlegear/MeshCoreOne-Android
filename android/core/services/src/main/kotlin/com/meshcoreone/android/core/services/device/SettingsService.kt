// PortedFrom: MC1Services/Sources/MC1Services/Services/SettingsService.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/SettingsService+Verified.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.SessionEvent
import com.meshcoreone.android.core.contracts.domain.SessionEventSubscription
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.model.AdvertLocationPolicy
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.TelemetryModes
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.snapshotMap
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.bytes.utf8Prefix
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.CoreStats
import com.meshcoreone.android.core.protocol.event.PacketStats
import com.meshcoreone.android.core.protocol.event.RadioStats
import com.meshcoreone.android.core.protocol.model.*
import com.meshcoreone.android.core.protocol.session.ConfigurationSessionOps
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlin.math.abs
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SettingsService(
    private val session: ConfigurationSessionOps,
    private val context: DeviceSettingsContext,
) {
    private val operationLock = Mutex()
    private val eventLock = Any()
    private var subscription: Subscription? = null
    private var eventsFinished = false

    init {
        context.onClose(::finishEvents)
    }

    private inner class Subscription : SessionEventSubscription<SettingsEvent> {
        val id: UUID = UUID.randomUUID()
        val channel = Channel<SessionEvent<SettingsEvent>>(Channel.UNLIMITED)
        private val collected = AtomicBoolean(false)
        override val events: Flow<SessionEvent<SettingsEvent>> = flow {
            check(collected.compareAndSet(false, true)) { "A settings subscription permits one consumer" }
            try {
                for (event in channel) {
                    if (!context.isCurrent) break
                    emit(event)
                }
            } finally {
                close()
            }
        }
        override fun close() {
            synchronized(eventLock) {
                if (subscription?.id == id) subscription = null
                channel.close()
            }
        }
    }

    fun events(): SessionEventSubscription<SettingsEvent> = synchronized(eventLock) {
        val next = Subscription()
        if (eventsFinished || !context.isCurrent) {
            next.channel.close()
        } else {
            subscription?.channel?.close()
            if (subscription != null) Logger.getLogger("MeshCore.SettingsService").fine("Replacing settings subscriber")
            subscription = next
        }
        next
    }

    fun finishEvents() {
        synchronized(eventLock) {
            eventsFinished = true
            subscription?.channel?.close()
            subscription = null
        }
    }

    private fun publish(event: SettingsEvent) {
        context.requireCurrent()
        synchronized(eventLock) {
            subscription?.channel?.trySend(SessionEvent(context.token, event))?.getOrThrow()
        }
    }

    private suspend fun <T> operation(action: suspend () -> T): T = context.operation {
        operationLock.withLock {
            context.requireCurrent()
            action()
        }
    }

    private suspend fun <T> configuration(action: suspend ConfigurationSessionOps.() -> T): T {
        context.requireCurrent()
        val result = try {
            session.action()
        } catch (failure: MeshCoreException) {
            throw SettingsServiceException(SettingsServiceError.SessionError(failure))
        }
        context.requireCurrent()
        return result
    }

    suspend fun applyRadioPreset(preset: RadioPreset): Unit = operation {
        writeRadio(preset.frequencyKHz, preset.bandwidthHz, preset.spreadingFactor, preset.codingRate, null)
        preset.pathHashMode?.let { mode ->
            if (configuration { queryDevice() }.supportsPathHashMode) configuration { setPathHashMode(mode) }
        }
        Unit
    }

    suspend fun setRadioParams(
        frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte, codingRate: UByte,
        clientRepeat: Boolean? = null,
    ) = operation { writeRadio(frequencyKHz, bandwidthKHz, spreadingFactor, codingRate, clientRepeat) }

    private suspend fun writeRadio(
        frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte, codingRate: UByte, clientRepeat: Boolean?,
    ) = configuration {
        setRadio(frequencyKHz.toDouble() / 1000.0, bandwidthKHz.toDouble() / 1000.0, spreadingFactor, codingRate, clientRepeat)
    }

    suspend fun setTxPower(power: Byte) = operation { configuration { setTxPower(power) } }
    suspend fun getTime(): Instant = operation { configuration { getTime() } }
    suspend fun setTime(date: Instant) = operation { configuration { setTime(date) } }
    suspend fun setNodeName(name: String) = operation {
        configuration { setName(name.utf8Prefix(ProtocolLimits.MAX_USABLE_NAME_BYTES)) }
    }
    suspend fun setLocation(latitude: Double, longitude: Double) = operation {
        configuration { setCoordinates(latitude, longitude) }
    }
    suspend fun setBlePin(pin: UInt) = operation { configuration { setDevicePin(pin) } }

    suspend fun setOtherParams(
        autoAddContacts: Boolean, telemetryModes: TelemetryModes, advertLocationPolicy: AdvertLocationPolicy,
        multiAcks: UByte,
    ) = setOtherParams(autoAddContacts, telemetryModes, advertLocationPolicy.rawValue, multiAcks)

    suspend fun setOtherParams(
        autoAddContacts: Boolean, telemetryModes: TelemetryModes, advertLocationPolicyRaw: UByte, multiAcks: UByte,
    ) = operation { writeOther(autoAddContacts, telemetryModes, advertLocationPolicyRaw, multiAcks) }

    @Deprecated("Use advertLocationPolicy overload instead")
    suspend fun setOtherParams(
        autoAddContacts: Boolean, telemetryModes: TelemetryModes, shareLocationPublicly: Boolean, multiAcks: UByte,
    ) = setOtherParams(
        autoAddContacts, telemetryModes,
        if (shareLocationPublicly) AdvertLocationPolicy.PREFS else AdvertLocationPolicy.NONE, multiAcks,
    )

    private suspend fun writeOther(
        autoAddContacts: Boolean, modes: TelemetryModes, locationPolicy: UByte, multiAcks: UByte,
    ) = configuration {
        setOtherParams(!autoAddContacts, modes.environment, modes.location, modes.base, locationPolicy, multiAcks)
    }

    suspend fun factoryReset() = operation { configuration { factoryReset() } }
    suspend fun reboot() = operation { configuration { reboot() } }
    suspend fun getBattery(): BatteryInfo = operation { configuration { getBattery() } }
    suspend fun queryDevice(): DeviceCapabilities = operation { configuration { queryDevice() } }
    suspend fun getSelfInfo(): SelfInfo = operation { configuration { sendAppStart() } }
    suspend fun getAutoAddConfig(): AutoAddConfig = operation { configuration { getAutoAddConfig() } }
    suspend fun refreshAutoAddConfig() = operation {
        publish(SettingsEvent.AutoAddConfigUpdated(configuration { getAutoAddConfig() }))
    }
    suspend fun refreshRepeatFreqRanges() = operation {
        publish(SettingsEvent.AllowedRepeatFreqUpdated(configuration { getRepeatFreq() }.snapshot()))
    }
    suspend fun refreshDeviceInfo() = operation {
        publish(SettingsEvent.DeviceUpdated(configuration { sendAppStart() }, null))
    }
    suspend fun setAutoAddConfig(config: AutoAddConfig) = operation { configuration { setAutoAddConfig(config) } }
    suspend fun setAutoAddConfigVerified(config: AutoAddConfig): AutoAddConfig = operation {
        configuration { setAutoAddConfig(config) }
        val actual = configuration { getAutoAddConfig() }
        if (actual != config) verification(
            "bitmask=${config.bitmask}, maxHops=${config.maxHops}",
            "bitmask=${actual.bitmask}, maxHops=${actual.maxHops}",
        )
        publish(SettingsEvent.AutoAddConfigUpdated(actual))
        actual
    }

    suspend fun setPathHashMode(mode: UByte) = operation { configuration { setPathHashMode(mode) } }
    suspend fun setPathHashModeVerified(mode: UByte): UByte = operation { writePathHashAndVerify(mode) }

    private suspend fun writePathHashAndVerify(mode: UByte): UByte {
        configuration { setPathHashMode(mode) }
        val actual = configuration { queryDevice() }.pathHashMode
        if (actual != mode) verification("pathHashMode=$mode", "pathHashMode=$actual")
        publish(SettingsEvent.PathHashModeUpdated(mode))
        return mode
    }

    suspend fun getDefaultFloodScope(): String? = operation { readDefaultFloodScope() }

    private suspend fun readDefaultFloodScope(): String? {
        val actual = configuration { getDefaultFloodScope() }?.name
        publish(SettingsEvent.DefaultFloodScopeUpdated(actual))
        return actual
    }

    suspend fun setDefaultFloodScopeVerified(name: String?): String? = operation {
        val expected = name?.takeIf { it.isNotEmpty() }?.utf8Prefix(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES)
        configuration {
            if (expected == null) setDefaultFloodScope("", FloodScope.Disabled)
            else setDefaultFloodScope(expected, FloodScope.Region(expected))
        }
        val actual = readDefaultFloodScope()
        if (actual != expected) verification(expected ?: "(cleared)", actual ?: "(cleared)")
        actual
    }

    suspend fun getStatsCore(): CoreStats = operation { configuration { getStatsCore() } }
    suspend fun getStatsRadio(): RadioStats = operation { configuration { getStatsRadio() } }
    suspend fun getStatsPackets(): PacketStats = operation { configuration { getStatsPackets() } }
    suspend fun getCustomVars(): Map<String, String> = operation { configuration { getCustomVars() }.snapshotMap() }
    suspend fun getDeviceGPSState(): DeviceGPSState = operation { readGPSState() }
    private suspend fun readGPSState(): DeviceGPSState = deviceGPSState(configuration { getCustomVars() })
    suspend fun setCustomVar(key: String, value: String) = operation { configuration { setCustomVar(key, value) } }
    suspend fun setDeviceGPSEnabledVerified(enabled: Boolean): DeviceGPSState = operation { writeGPSAndVerify(enabled) }

    private suspend fun writeGPSAndVerify(enabled: Boolean): DeviceGPSState {
        configuration { setCustomVar("gps", if (enabled) "1" else "0") }
        val state = readGPSState()
        if (!state.isSupported || state.isEnabled != enabled) {
            throw SettingsServiceException(SettingsServiceError.DeviceGPSVerificationFailed(enabled, state.isEnabled))
        }
        publish(SettingsEvent.DeviceUpdated(configuration { sendAppStart() }, null))
        return state
    }

    suspend fun exportPrivateKey(): Bytes = operation { configuration { exportPrivateKey() } }
    suspend fun importPrivateKey(key: Bytes) = operation { configuration { importPrivateKey(key) } }
    suspend fun importPrivateKey(key: Bytes, onCommitted: () -> Unit) =
        operation { configuration { importPrivateKey(key, onCommitted) } }
    suspend fun sign(data: Bytes): Bytes = operation { configuration { sign(data) } }

    suspend fun setNodeNameVerified(name: String): SelfInfo = operation {
        val expected = name.utf8Prefix(ProtocolLimits.MAX_USABLE_NAME_BYTES)
        configuration { setName(expected) }
        val actual = configuration { sendAppStart() }
        if (actual.name != expected) verification(expected, actual.name)
        publish(SettingsEvent.DeviceUpdated(actual, null))
        actual
    }

    suspend fun setLocationVerified(latitude: Double, longitude: Double): SelfInfo =
        operation { writeLocationAndVerify(latitude, longitude) }

    private suspend fun writeLocationAndVerify(latitude: Double, longitude: Double): SelfInfo {
        val latitudeSent = scaledCoordinate(latitude)
        val longitudeSent = scaledCoordinate(longitude)
        configuration { setCoordinates(latitude, longitude) }
        val actual = configuration { sendAppStart() }
        val latitudeReceived = scaledCoordinate(actual.latitude)
        val longitudeReceived = scaledCoordinate(actual.longitude)
        if (abs(latitudeSent.toLong() - latitudeReceived.toLong()) > 2L ||
            abs(longitudeSent.toLong() - longitudeReceived.toLong()) > 2L
        ) verification(
            "(${sourceDoubleDescription(latitudeSent / 1_000_000.0)}, ${sourceDoubleDescription(longitudeSent / 1_000_000.0)})",
            "(${sourceDoubleDescription(actual.latitude)}, ${sourceDoubleDescription(actual.longitude)})",
        )
        publish(SettingsEvent.DeviceUpdated(actual, null))
        return actual
    }

    suspend fun setManualLocationVerified(latitude: Double, longitude: Double): SelfInfo = operation {
        val state = readGPSState()
        if (state.isSupported && state.isEnabled) writeGPSAndVerify(false)
        writeLocationAndVerify(latitude, longitude)
    }

    suspend fun setRadioParamsVerified(
        frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte, codingRate: UByte,
        clientRepeat: Boolean? = null, appliedRadioPresetID: String? = null,
    ): SelfInfo = operation {
        val actual = writeRadioAndVerify(frequencyKHz, bandwidthKHz, spreadingFactor, codingRate, clientRepeat)
        publish(SettingsEvent.DeviceUpdated(actual, appliedRadioPresetID))
        actual
    }

    private suspend fun writeRadioAndVerify(
        frequency: UInt, bandwidth: UInt, sf: UByte, cr: UByte, clientRepeat: Boolean?,
    ): SelfInfo {
        writeRadio(frequency, bandwidth, sf, cr, clientRepeat)
        val actual = configuration { sendAppStart() }
        if (!(abs(actual.radioFrequency - frequency.toDouble() / 1000.0) < 0.001) ||
            !(abs(actual.radioBandwidth - bandwidth.toDouble() / 1000.0) < 0.001) ||
            actual.radioSpreadingFactor != sf || actual.radioCodingRate != cr
        ) verification(
            "freq=$frequency, bw=$bandwidth, sf=$sf, cr=$cr",
            "freq=${sourceDoubleDescription(actual.radioFrequency)}, bw=${sourceDoubleDescription(actual.radioBandwidth)}, " +
                "sf=${actual.radioSpreadingFactor}, cr=${actual.radioCodingRate}",
        )
        if (clientRepeat != null) {
            val actualRepeat = configuration { queryDevice() }.clientRepeat
            if (actualRepeat != clientRepeat) verification("clientRepeat=$clientRepeat", "clientRepeat=$actualRepeat")
            publish(SettingsEvent.ClientRepeatUpdated(clientRepeat))
        }
        return actual
    }

    suspend fun applyRadioPresetVerified(preset: RadioPreset): SelfInfo = operation {
        val actual = writeRadioAndVerify(
            preset.frequencyKHz, preset.bandwidthHz, preset.spreadingFactor, preset.codingRate, null,
        )
        preset.pathHashMode?.let { mode ->
            if (configuration { queryDevice() }.supportsPathHashMode) writePathHashAndVerify(mode)
        }
        publish(SettingsEvent.DeviceUpdated(actual, preset.id))
        actual
    }

    suspend fun setTxPowerVerified(power: Byte): SelfInfo = operation {
        configuration { setTxPower(power) }
        val actual = configuration { sendAppStart() }
        if (actual.txPower != power) verification(power.toString(), actual.txPower.toString())
        publish(SettingsEvent.DeviceUpdated(actual, null))
        actual
    }

    suspend fun setOtherParamsVerified(
        autoAddContacts: Boolean, telemetryModes: TelemetryModes, advertLocationPolicy: AdvertLocationPolicy,
        multiAcks: UByte,
    ): SelfInfo = operation {
        writeOther(autoAddContacts, telemetryModes, advertLocationPolicy.rawValue, multiAcks)
        val actual = configuration { sendAppStart() }
        if (actual.manualAddContacts == autoAddContacts) {
            verification("autoAdd=$autoAddContacts", "autoAdd=${!actual.manualAddContacts}")
        }
        publish(SettingsEvent.DeviceUpdated(actual, null))
        actual
    }

    suspend fun setOtherParamsVerified(
        device: DeviceDTO, autoAddContacts: Boolean? = null, telemetryModes: TelemetryModes? = null,
        advertLocationPolicy: AdvertLocationPolicy? = null, multiAcks: UByte? = null,
    ): SelfInfo = setOtherParamsVerified(
        autoAddContacts ?: !device.manualAddContacts, telemetryModes ?: device.telemetryModes,
        advertLocationPolicy ?: device.advertLocationPolicyMode, multiAcks ?: device.multiAcks,
    )

    @Deprecated("Use advertLocationPolicy overload instead")
    suspend fun setOtherParamsVerified(
        autoAddContacts: Boolean, telemetryModes: TelemetryModes, shareLocationPublicly: Boolean, multiAcks: UByte,
    ): SelfInfo = setOtherParamsVerified(
        autoAddContacts, telemetryModes,
        if (shareLocationPublicly) AdvertLocationPolicy.PREFS else AdvertLocationPolicy.NONE, multiAcks,
    )

    private fun scaledCoordinate(value: Double): Int {
        val scaled = value * 1_000_000.0
        if (!scaled.isFinite() || scaled < Int.MIN_VALUE.toDouble() || scaled >= Int.MAX_VALUE.toDouble() + 1) {
            throw SettingsServiceException(
                SettingsServiceError.SessionError(MeshCoreException.InvalidInput("Coordinate does not fit scaled Int32")),
            )
        }
        return scaled.toInt()
    }

    private fun verification(expected: String, actual: String): Nothing =
        throw SettingsServiceException(SettingsServiceError.VerificationFailed(expected, actual))

    companion object {
        internal fun deviceGPSState(vars: Map<String, String>): DeviceGPSState {
            val value = vars["gps"] ?: return DeviceGPSState(false, false)
            return DeviceGPSState(true, value == "1")
        }
    }
}
