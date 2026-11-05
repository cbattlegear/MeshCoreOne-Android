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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val defaultScopeQueueLock = Any()
    private var defaultScopeTail = CompletableDeferred(Unit)
    private var subscription: Subscription? = null
    private var subscriptionRevision = 0L
    private var eventsFinished = false
    private var pathHashRevision = 0L
    private var persistedPathHashRevision = 0L
    val pathHashModeNeedsRecovery: Boolean
        get() = synchronized(eventLock) { pathHashRevision != persistedPathHashRevision }

    init {
        context.onClose(::finishEvents)
    }

    private inner class Subscription : SessionEventSubscription<SettingsEvent> {
        val channel = Channel<SessionEvent<SettingsEvent>>(Channel.UNLIMITED)
        val pendingPersistence = mutableSetOf<CompletableDeferred<Unit>>()
        var revision = 0L
        private val collected = AtomicBoolean(false)
        override val events: Flow<SessionEvent<SettingsEvent>> = flow {
            check(collected.compareAndSet(false, true)) { "A settings subscription permits one consumer" }
            try {
                for (event in channel) {
                    if (!context.isCurrent) break
                    val persistence = when (val update = event.event) {
                        is SettingsEvent.PathHashModeUpdated -> update.persistence
                        is SettingsEvent.DefaultFloodScopeUpdated -> update.persistence
                        else -> null
                    }
                    if (event.event is SettingsEvent.DefaultFloodScopeUpdated &&
                        synchronized(eventLock) { revision != subscriptionRevision }
                    ) continue
                    if (persistence != null && synchronized(eventLock) {
                            subscription !== this@Subscription || persistence.isCancelled
                        }) continue
                    emit(event)
                }
            } finally {
                close()
            }
        }
        override fun close() {
            val pending = synchronized(eventLock) { detachLocked() }
            finishClose(pending)
        }
        fun detachLocked(): List<CompletableDeferred<Unit>> {
            if (subscription === this) subscription = null
            return pendingPersistence.toList().also { pendingPersistence.clear() }
        }
        fun finishClose(pending: List<CompletableDeferred<Unit>>) {
            channel.close()
            pending.forEach {
                it.completeExceptionally(SettingsServiceException(SettingsServiceError.NotConnected))
            }
        }
    }

    fun events(): SessionEventSubscription<SettingsEvent> {
        val next = Subscription()
        var previous: Subscription? = null
        var pending = emptyList<CompletableDeferred<Unit>>()
        val accepted = synchronized(eventLock) {
            if (eventsFinished || !context.isCurrent) false else {
                previous = subscription
                pending = previous?.detachLocked().orEmpty()
                next.revision = ++subscriptionRevision
                subscription = next
                true
            }
        }
        // Closing a channel or completion can resume an unconfined caller that subscribes again.
        if (accepted) {
            previous?.finishClose(pending)
            if (previous != null) Logger.getLogger("MeshCore.SettingsService").fine("Replacing settings subscriber")
        } else next.close()
        return next
    }

    fun finishEvents() {
        val (previous, pending) = synchronized(eventLock) {
            eventsFinished = true
            val previous = subscription
            previous to previous?.detachLocked().orEmpty()
        }
        previous?.finishClose(pending)
    }

    private fun publish(
        event: SettingsEvent,
        completion: CompletableDeferred<Unit>? = null,
        owner: Subscription? = null,
    ) {
        context.requireCurrent()
        synchronized(eventLock) {
            if (completion == null) {
                subscription?.channel?.trySend(SessionEvent(context.token, event))?.getOrThrow()
            } else {
                if (eventsFinished || subscription !== owner) {
                    throw SettingsServiceException(SettingsServiceError.NotConnected)
                }
                val target = checkNotNull(owner)
                target.pendingPersistence.add(completion)
                target.channel.trySend(SessionEvent(context.token, event)).getOrThrow()
            }
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

    /** Normal Settings awaits the event consumer's durable write, outside the radio-operation lock. */
    suspend fun setPathHashModePersisted(mode: UByte): UByte = context.operation {
        val revision = acknowledged { completion, owner ->
            writePathHashAndVerify(mode, completion, owner)
            synchronized(eventLock) { pathHashRevision }
        }
        synchronized(eventLock) {
            persistedPathHashRevision = maxOf(persistedPathHashRevision, revision)
        }
        mode
    }

    private suspend fun <T> acknowledged(action: suspend (CompletableDeferred<Unit>, Subscription) -> T): T {
        val completion = CompletableDeferred<Unit>()
        val owner = synchronized(eventLock) {
            subscription?.takeUnless { eventsFinished }
                ?: throw SettingsServiceException(SettingsServiceError.NotConnected)
        }
        try {
            val result = operation {
                synchronized(eventLock) {
                    if (eventsFinished || subscription !== owner) {
                        throw SettingsServiceException(SettingsServiceError.NotConnected)
                    }
                }
                action(completion, owner)
            }
            completion.await()
            context.requireCurrent()
            return result
        } finally {
            synchronized(eventLock) { owner.pendingPersistence.remove(completion) }
            completion.cancel()
        }
    }

    private suspend fun writePathHashAndVerify(
        mode: UByte,
        completion: CompletableDeferred<Unit>? = null,
        owner: Subscription? = null,
    ): UByte {
        synchronized(eventLock) { pathHashRevision = Math.incrementExact(pathHashRevision) }
        configuration { setPathHashMode(mode) }
        val actual = configuration { queryDevice() }.pathHashMode
        if (actual != mode) verification("pathHashMode=$mode", "pathHashMode=$actual")
        publish(SettingsEvent.PathHashModeUpdated(mode, completion), completion, owner)
        return mode
    }

    suspend fun getDefaultFloodScope(): String? = defaultScopeOperation { operation { readDefaultFloodScope() } }

    private suspend fun readDefaultFloodScope(): String? {
        val actual = configuration { getDefaultFloodScope() }?.name
        publish(SettingsEvent.DefaultFloodScopeUpdated(actual))
        return actual
    }

    suspend fun setDefaultFloodScopeVerified(name: String?): String? = defaultScopeOperation {
        operation { writeDefaultFloodScopeAndVerify(name) }
    }

    suspend fun setDefaultFloodScopePersisted(name: String?, currentDevice: () -> DeviceDTO?): String? = defaultScopeOperation {
        val device = currentDevice() ?: throw SettingsServiceException(SettingsServiceError.NotConnected)
        if (!name.isNullOrEmpty() && name !in device.knownRegions) {
            verification("knownRegion=$name", "knownRegions=${device.knownRegions.joinToString()}")
        }
        persistDefaultFloodScope(name)
    }

    /** Queue before sampling live/radio state; never enter the editor until the radio clear is acknowledged. */
    suspend fun removeKnownRegionPersisted(
        region: String,
        currentDevice: () -> DeviceDTO?,
        remove: suspend () -> Unit,
    ) = defaultScopeOperation {
        val device = currentDevice() ?: throw SettingsServiceException(SettingsServiceError.NotConnected)
        if (device.supportsDefaultFloodScope) {
            // This acknowledged baseline fences older unacknowledged reads even if no clear is needed.
            val actual = acknowledged { completion, owner ->
                val value = configuration { getDefaultFloodScope() }
                publish(SettingsEvent.DefaultFloodScopeUpdated(value?.name, completion), completion, owner)
                value
            }
            val radioRegion = defaultFloodScopeName(region)
            val latest = currentDevice() ?: throw SettingsServiceException(SettingsServiceError.NotConnected)
            val anotherAlias = latest.knownRegions.any { it != region && defaultFloodScopeName(it) == radioRegion }
            val matchesRadio = radioRegion != null && actual?.name == radioRegion
            if (matchesRadio && actual?.scopeKey != FloodScope.Region(checkNotNull(radioRegion)).scopeKey()) {
                verification("scopeKey(region=$radioRegion)", "different scope key")
            }
            val retryClear = actual == null &&
                (device.defaultFloodScopeName == region || (radioRegion != null && device.defaultFloodScopeName == radioRegion))
            if (!anotherAlias && (matchesRadio || retryClear)) {
                persistDefaultFloodScope(null)
            }
        }
        context.requireCurrent()
        remove()
        context.requireCurrent()
    }

    private suspend fun <T> defaultScopeOperation(action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val admission = AtomicInteger(0)
        val task = context.submit {
            val finished = CompletableDeferred<Unit>()
            val previous = synchronized(defaultScopeQueueLock) {
                defaultScopeTail.also { defaultScopeTail = finished }
            }
            var turnAcquired = false
            try {
                previous.await()
                turnAcquired = true
                if (!admission.compareAndSet(0, 1)) throw CancellationException("Queued scope action withdrawn")
                context.requireCurrent()
                action()
            } finally {
                if (turnAcquired || previous.isCompleted) finished.complete(Unit)
                else previous.invokeOnCompletion { finished.complete(Unit) }
            }
        }
        try {
            return context.await(task)
        } catch (cancelled: CancellationException) {
            if (admission.compareAndSet(0, 2)) task.cancel()
            else task.invokeOnCompletion { failure ->
                if (failure != null && failure !is CancellationException) {
                    Logger.getLogger("MeshCore.SettingsService").log(Level.WARNING, "Detached scope action failed", failure)
                }
            }
            throw cancelled
        }
    }

    private suspend fun persistDefaultFloodScope(name: String?): String? {
        val expected = defaultFloodScopeName(name)
        val actual = acknowledged { completion, owner ->
            configuration {
                if (expected == null) setDefaultFloodScope("", FloodScope.Disabled)
                else setDefaultFloodScope(expected, FloodScope.Region(expected))
            }
            val value = configuration { getDefaultFloodScope() }
            publish(SettingsEvent.DefaultFloodScopeUpdated(value?.name, completion), completion, owner)
            value
        }
        if (actual?.name != expected) verification(expected ?: "(cleared)", actual?.name ?: "(cleared)")
        if (expected != null && actual?.scopeKey != FloodScope.Region(expected).scopeKey()) {
            verification("scopeKey(region=$expected)", "different scope key")
        }
        return actual?.name
    }

    private suspend fun writeDefaultFloodScopeAndVerify(name: String?): String? {
        val expected = defaultFloodScopeName(name)
        configuration {
            if (expected == null) setDefaultFloodScope("", FloodScope.Disabled)
            else setDefaultFloodScope(expected, FloodScope.Region(expected))
        }
        val actual = readDefaultFloodScope()
        if (actual != expected) verification(expected ?: "(cleared)", actual ?: "(cleared)")
        return actual
    }

    private fun defaultFloodScopeName(name: String?): String? =
        name?.takeIf { it.isNotEmpty() }?.utf8Prefix(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES)

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
