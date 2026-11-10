// PortedFrom: MC1Tests/Views/Tools/CLI/MockConfigurationSession.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.diagnostics.cli

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.BatteryInfo
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.model.SelfInfo
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Stateful fake of the `SettingsService` slice, standing in for the Swift
 * `MockConfigurationSession` + real `SettingsService` pair: setters mutate the stored
 * `SelfInfo`/`DeviceCapabilities` so verified read-backs reflect the write, [suppressWrites]
 * drives the verification-failure path, and device errors are wrapped in
 * `SettingsServiceException(SessionError)` exactly as the service does.
 */
internal class FakeSettings(
    name: String = "TestDevice",
    txPower: Byte = 20,
    publicKey: Bytes = Bytes(ByteArray(32) { 0x01 }),
    latitude: Double = 0.0,
    longitude: Double = 0.0,
    multiAcks: UByte = 0u,
    manualAddContacts: Boolean = false,
    radioFrequency: Double = 869.525,
    radioBandwidth: Double = 250.0,
    radioSpreadingFactor: UByte = 11u,
    radioCodingRate: UByte = 5u,
    version: String = "1.13.0",
    firmwareBuild: String = "testbuild",
    model: String = "TestBoard",
    pathHashMode: UByte = 0u,
    batteryMillivolts: Long = 3700,
    private var deviceTime: Instant = Instant.EPOCH,
    private var customVars: Map<String, String> = emptyMap(),
) : CliSettingsPort {
    data class RadioCall(val frequencyKHz: UInt, val bandwidthHz: UInt, val spreadingFactor: UByte, val codingRate: UByte)

    private var selfInfo = SelfInfo(
        advertisementType = 0u, txPower = txPower, maxTxPower = 30, publicKey = publicKey,
        latitude = latitude, longitude = longitude, multiAcks = multiAcks, advertisementLocationPolicy = 0u,
        telemetryModeEnvironment = 0u, telemetryModeLocation = 0u, telemetryModeBase = 0u,
        manualAddContacts = manualAddContacts, radioFrequency = radioFrequency, radioBandwidth = radioBandwidth,
        radioSpreadingFactor = radioSpreadingFactor, radioCodingRate = radioCodingRate, name = name,
    )
    private var capabilities = DeviceCapabilities(
        firmwareVersion = 9u, maxContacts = 100, maxChannels = 8, blePin = 0u,
        firmwareBuild = firmwareBuild, model = model, version = version, pathHashMode = pathHashMode,
    )
    private val battery = BatteryInfo(level = batteryMillivolts)

    var suppressWrites = false
    var nextSetCustomVarErrorCode: UByte? = null
    var nextGetCustomVarsErrorCode: UByte? = null
    var readCount = 0
        private set
    var rebootCalled = false
        private set
    val setNameCalls = mutableListOf<String>()
    val setTxPowerCalls = mutableListOf<Byte>()
    val setPathHashModeCalls = mutableListOf<UByte>()
    val setTimeCalls = mutableListOf<Instant>()
    val setRadioCalls = mutableListOf<RadioCall>()
    val setCoordinatesCalls = mutableListOf<Pair<Double, Double>>()
    val setOtherParamsCalls = mutableListOf<Pair<Boolean, UByte>>()
    val setCustomVarCalls = mutableListOf<Pair<String, String>>()

    private fun read(): SelfInfo {
        readCount++
        return selfInfo
    }

    private fun verify(expected: Any, actual: Any) {
        if (expected != actual) {
            throw SettingsServiceException(SettingsServiceError.VerificationFailed(expected.toString(), actual.toString()))
        }
    }

    private fun deviceError(code: UByte): Nothing =
        throw SettingsServiceException(SettingsServiceError.SessionError(MeshCoreException.DeviceError(code)))

    override suspend fun getTime(): Instant = deviceTime.also { readCount++ }

    override suspend fun setTime(date: Instant) {
        setTimeCalls += date
        if (!suppressWrites) deviceTime = date
    }

    override suspend fun queryDevice(): DeviceCapabilities = capabilities.also { readCount++ }

    override suspend fun getSelfInfo(): SelfInfo = read()

    override suspend fun getBattery(): BatteryInfo = battery.also { readCount++ }

    override suspend fun setNodeNameVerified(name: String): SelfInfo {
        setNameCalls += name
        if (!suppressWrites) selfInfo = selfInfo.copy(name = name)
        return read().also { verify(name, it.name) }
    }

    override suspend fun setManualLocationVerified(latitude: Double, longitude: Double): SelfInfo {
        setCoordinatesCalls += latitude to longitude
        if (!suppressWrites) selfInfo = selfInfo.copy(latitude = latitude, longitude = longitude)
        return read().also { verify(latitude to longitude, it.latitude to it.longitude) }
    }

    override suspend fun setTxPowerVerified(power: Byte): SelfInfo {
        setTxPowerCalls += power
        if (!suppressWrites) selfInfo = selfInfo.copy(txPower = power)
        return read().also { verify(power, it.txPower) }
    }

    override suspend fun setRadioParamsVerified(
        frequencyKHz: UInt,
        bandwidthKHz: UInt,
        spreadingFactor: UByte,
        codingRate: UByte,
    ): SelfInfo {
        setRadioCalls += RadioCall(frequencyKHz, bandwidthKHz, spreadingFactor, codingRate)
        if (!suppressWrites) {
            selfInfo = selfInfo.copy(
                radioFrequency = frequencyKHz.toDouble() / 1000, radioBandwidth = bandwidthKHz.toDouble() / 1000,
                radioSpreadingFactor = spreadingFactor, radioCodingRate = codingRate,
            )
        }
        return read().also { verify(spreadingFactor, it.radioSpreadingFactor) }
    }

    override suspend fun setOtherParamsVerified(device: DeviceDTO, multiAcks: UByte): SelfInfo {
        setOtherParamsCalls += device.manualAddContacts to multiAcks
        if (!suppressWrites) selfInfo = selfInfo.copy(multiAcks = multiAcks, manualAddContacts = device.manualAddContacts)
        return read().also { verify(multiAcks, it.multiAcks) }
    }

    override suspend fun setPathHashModeVerified(mode: UByte): UByte {
        setPathHashModeCalls += mode
        if (!suppressWrites) capabilities = capabilities.copy(pathHashMode = mode)
        return queryDevice().pathHashMode.also { verify(mode, it) }
    }

    override suspend fun getCustomVars(): Map<String, String> {
        readCount++
        nextGetCustomVarsErrorCode?.let {
            nextGetCustomVarsErrorCode = null
            deviceError(it)
        }
        return customVars
    }

    override suspend fun setCustomVar(key: String, value: String) {
        setCustomVarCalls += key to value
        nextSetCustomVarErrorCode?.let {
            nextSetCustomVarErrorCode = null
            deviceError(it)
        }
        if (!suppressWrites) customVars = customVars + (key to value)
    }

    override suspend fun reboot() {
        rebootCalled = true
    }
}

/** Parks every `fetchContacts` caller until released FIFO; parked callers ignore cancellation, as Swift's continuation does. */
internal class ParkingDirectory(private val contacts: List<ContactDTO> = emptyList()) : CliNodeDirectory {
    private val parked = ArrayDeque<CompletableDeferred<Unit>>()
    val parkedCount: Int get() = parked.size

    fun resumeFirst() {
        parked.removeFirstOrNull()?.complete(Unit)
    }

    override suspend fun fetchContacts(radioId: RadioId): List<ContactDTO> {
        val gate = CompletableDeferred<Unit>()
        parked.addLast(gate)
        withContext(NonCancellable) { gate.await() }
        return contacts
    }

    override suspend fun fetchChannels(radioId: RadioId): List<ChannelDTO> = emptyList()
}

/** A directory that answers immediately (or throws [failure]). */
internal class FakeDirectory(
    var contacts: List<ContactDTO> = emptyList(),
    var channels: List<ChannelDTO> = emptyList(),
    var failure: Exception? = null,
) : CliNodeDirectory {
    override suspend fun fetchContacts(radioId: RadioId): List<ContactDTO> = failure?.let { throw it } ?: contacts

    override suspend fun fetchChannels(radioId: RadioId): List<ChannelDTO> = failure?.let { throw it } ?: channels
}

/** Records remote-node calls; [loginGate] can hold `login` open to observe the countdown. */
internal class FakeRemoteNode(
    var storedPassword: String? = null,
    var loginSuccess: Boolean = true,
    var loginFailure: Exception? = null,
    var loginTimeoutSeconds: Long? = null,
) : CliRemoteNodePort {
    val loginCalls = mutableListOf<Pair<EntityKey, String>>()
    val logoutCalls = mutableListOf<EntityKey>()
    val storedPasswords = mutableListOf<String>()
    val deletedPasswords = mutableListOf<ContactDTO>()
    val timeoutCallbacks = mutableListOf<suspend (Long) -> Unit>()
    var loginGate: CompletableDeferred<Unit>? = null
    var holdLoginAfterCancellation = false

    override suspend fun createSession(radioId: RadioId, contact: ContactDTO): EntityKey = EntityKey(radioId, contact.id)

    override suspend fun login(
        session: EntityKey,
        password: String,
        pathLength: UByte,
        onTimeoutKnown: suspend (Long) -> Unit,
    ): Boolean {
        loginCalls += session to password
        timeoutCallbacks += onTimeoutKnown
        loginTimeoutSeconds?.let { onTimeoutKnown(it) }
        val gate = loginGate
        if (holdLoginAfterCancellation) {
            withContext(NonCancellable) { gate?.await() }
        } else {
            gate?.await()
        }
        loginFailure?.let { throw it }
        return loginSuccess
    }

    override suspend fun logout(session: EntityKey) {
        logoutCalls += session
    }

    override suspend fun retrievePassword(contact: ContactDTO): String? = storedPassword

    override suspend fun storePassword(password: String, publicKey: Bytes) {
        storedPasswords += password
    }

    override suspend fun deletePassword(contact: ContactDTO) {
        deletedPasswords += contact
        storedPassword = null
    }
}

/** Records raw remote commands and returns [response] or throws [failure]. */
internal class FakeRepeaterAdmin(var response: String = "OK", var failure: Exception? = null) : CliRepeaterAdminPort {
    val calls = mutableListOf<Triple<EntityKey, String, Duration>>()

    override suspend fun sendRawCommand(session: EntityKey, command: String, timeout: Duration): String {
        calls += Triple(session, command, timeout)
        failure?.let { throw it }
        return response
    }
}

/** Stand-in for core:services `RemoteNodeError.Timeout`; classified through [CliErrorPresentation]. */
internal class FakeRemoteTimeout : Exception("Request timed out")

internal class FakeLoginFailed(val reason: String) : Exception("Login failed: $reason")

internal val FAKE_ERRORS = CliErrorPresentation(
    remoteFault = { error ->
        when (error) {
            is FakeRemoteTimeout -> CliRemoteFault.Timeout
            is FakeLoginFailed -> CliRemoteFault.LoginFailed(error.reason)
            else -> null
        }
    },
)

internal val TEST_RADIO = RadioId(UUID.fromString("00000000-0000-0000-0000-0000000000AA"))

internal fun contact(
    name: String,
    type: UByte = 2u,
    outPathLength: UByte = 0u,
    publicKey: Bytes = Bytes(ByteArray(32) { name.hashCode().toByte() }),
): ContactDTO = ContactDTO(
    radioId = TEST_RADIO, publicKey = publicKey, name = name, typeRawValue = type,
    outPathLength = outPathLength, lastHeardTimestamp = null,
)

internal fun device(manualAddContacts: Boolean, multiAcks: UByte): DeviceDTO = DeviceDTO(
    radioId = TEST_RADIO, publicKey = Bytes(ByteArray(32) { 0x01 }), nodeName = "TestDevice",
    manualAddContacts = manualAddContacts, multiAcks = multiAcks,
)
