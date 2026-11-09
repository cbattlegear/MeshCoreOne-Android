// AndroidOnly: WP-211 Deterministic actual MeshCoreSession/MockTransport driver with independently encoded frozen wire layouts.
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.DeviceServiceException
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.event.MeshTransportError
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.session.SessionCorrelationException
import com.meshcoreone.android.core.protocol.session.SessionDiagnostic
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

internal val RADIO = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
internal val OTHER_RADIO = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
internal val EPOCH = ProcessEpoch(UUID.fromString("00000000-0000-0000-0000-000000000003"))
internal val NOW: Instant = Instant.ofEpochSecond(1_700_000_000)
internal fun token(generation: Long = 1, radio: RadioId = RADIO) = SessionToken(EPOCH, Generation(generation), radio)
internal fun filled(value: Int, size: Int = 32) = Bytes(ByteArray(size) { value.toByte() })
internal fun little32(value: Long) =
    Bytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array())
internal fun little16(value: Int) =
    Bytes(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
internal fun packet(code: ResponseCode, payload: Bytes = Bytes.EMPTY) = Bytes.of(code.rawValue.toInt()) + payload
internal fun hex(value: String) = Bytes.fromHex(value.replace(" ", ""))

internal fun selfPacket(
    name: String = "Test", frequency: UInt = 915_000u, bandwidth: UInt = 125_000u,
    sf: UByte = 7u, cr: UByte = 5u, latitudeScaled: Int = 0, longitudeScaled: Int = 0,
    txPower: Byte = 22, manual: Boolean = false, policy: UByte = 0u, telemetry: UByte = 0u, multiAcks: UByte = 0u,
): Bytes = packet(
    ResponseCode.SELF_INFO, Bytes.of(1, txPower.toInt() and 255, 22) + filled(1) +
        little32(latitudeScaled.toLong()) + little32(longitudeScaled.toLong()) +
        Bytes.of(multiAcks.toInt(), policy.toInt(), telemetry.toInt(), if (manual) 1 else 0) +
        little32(frequency.toLong()) + little32(bandwidth.toLong()) + Bytes.of(sf.toInt(), cr.toInt()) + Bytes.utf8(name),
)

internal fun capabilitiesPacket(
    version: UByte = 13u, repeat: Boolean = false, hash: UByte = 0u, blePin: UInt = 0u,
): Bytes = packet(
    ResponseCode.DEVICE_INFO,
    Bytes.of(version.toInt(), 10, 8) + little32(blePin.toLong()) +
        Bytes.utf8("2026-10-05").paddedOrTruncated(12) + Bytes.utf8("Test radio").paddedOrTruncated(40) +
        Bytes.utf8("v1.16.0").paddedOrTruncated(20) + Bytes.of(if (repeat) 1 else 0, hash.toInt()),
)

internal fun customVarsPacket(text: String = "") = packet(ResponseCode.CUSTOM_VARS, Bytes.utf8(text))
internal fun floodScopePacket(name: String?) =
    packet(ResponseCode.DEFAULT_FLOOD_SCOPE, name?.let { Bytes.utf8(it).paddedOrTruncated(31) + filled(0, 16) } ?: Bytes.EMPTY)

internal class TestSignals(initial: SessionToken = token()) : ConnectionSignals {
    override val snapshot = MutableStateFlow(
        ConnectionSnapshot(DeviceConnectionState.READY, ConnectionState.Connected, null,
            ConnectionIntent.WantsConnection(), initial, null),
    )
    override suspend fun subscribeTransitions(): ConnectionSubscription =
        throw UnsupportedOperationException("This fixture never substitutes a transition subscription")
    fun replace(next: SessionToken) { snapshot.value = snapshot.value.copy(token = next) }
    fun disconnect() {
        snapshot.value = ConnectionSnapshot(
            DeviceConnectionState.DISCONNECTED, ConnectionState.Disconnected, null, ConnectionIntent.None, null, null,
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class SchedulerClock(private val scheduler: TestCoroutineScheduler) : SessionClock {
    override val now: Duration get() = scheduler.currentTime.milliseconds
    override val wallClock: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    override suspend fun sleepFor(duration: Duration) = delay(duration)
}

internal class TestRadio : MeshTransport {
    val mock = MockTransport()
    val sent: List<Bytes> get() = mock.sentData
    var connects = 0
    var disconnects = 0
    var collectors = 0
    var maximumCollectors = 0
    var sendFailure: MeshTransportError? = null
    var onSend: suspend (Bytes) -> Unit = {}
    override suspend fun connect() { connects++; mock.connect() }
    override suspend fun disconnect() { disconnects++; mock.disconnect() }
    override suspend fun isConnected() = mock.isConnected()
    override suspend fun send(data: Bytes) {
        sendFailure?.let { throw it }
        mock.send(data)
        onSend(data)
    }
    override suspend fun receivedData(): Flow<Bytes> {
        val incoming = mock.receivedData()
        return flow {
            collectors++
            maximumCollectors = maxOf(maximumCollectors, collectors)
            try { incoming.collect { emit(it) } } finally { collectors-- }
        }
    }
    suspend fun receive(bytes: Bytes) = mock.simulateReceive(bytes)
    suspend fun ok() = mock.simulateOK()
    suspend fun error(code: UByte) = mock.simulateError(code)
}

internal class SettingsFixture(
    val radio: TestRadio,
    val session: MeshCoreSession,
    val signals: TestSignals,
    val context: DeviceSettingsContext,
    val settings: SettingsService,
    val clock: SchedulerClock,
    val diagnostics: MutableList<SessionDiagnostic>,
) {
    suspend fun close() {
        context.close()
        session.stop()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.settingsFixture(initial: Bytes = selfPacket(), generation: Long = 1): SettingsFixture {
    val radio = TestRadio()
    val clock = SchedulerClock(testScheduler)
    val diagnostics = mutableListOf<SessionDiagnostic>()
    val session = MeshCoreSession(
        radio, SessionConfiguration(
            defaultTimeout = 1.0, clientIdentifier = "MCore", binaryRequestOverallTimeout = 2.0,
            binaryRequestRetransmitInterval = null,
        ),
        clock, backgroundScope.coroutineContext, randomTag = { 0x12345678u }, onDiagnostic = diagnostics::add,
    )
    val start = backgroundScope.async { session.start() }
    runCurrent()
    assertEquals(hex("01032020202020204d436f7265"), radio.sent.single())
    radio.receive(initial)
    runCurrent()
    start.await()
    val signals = TestSignals(token(generation))
    val context = DeviceSettingsContext(token(generation), signals, backgroundScope, StandardTestDispatcher(testScheduler))
    return SettingsFixture(radio, session, signals, context, SettingsService(session, context), clock, diagnostics)
}

internal class CheckedRequest<T>(private val request: Deferred<Result<T>>) {
    val isCompleted: Boolean get() = request.isCompleted
    suspend fun await(): T = request.await().getOrThrow()
    fun cancel() = request.cancel()
}

internal fun <T> TestScope.request(action: suspend () -> T): CheckedRequest<T> =
    CheckedRequest(backgroundScope.async {
        try {
            Result.success(action())
        } catch (failure: SettingsServiceException) {
            Result.failure(failure)
        } catch (failure: DeviceServiceException) {
            Result.failure(failure)
        } catch (failure: MeshCoreException) {
            Result.failure(failure)
        } catch (failure: MeshTransportError) {
            Result.failure(failure)
        } catch (failure: SessionCorrelationException) {
            Result.failure(failure)
        }
    })

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.reply(fixture: SettingsFixture, count: Int, expected: Bytes, response: Bytes) {
    runCurrent()
    assertEquals(count, fixture.radio.sent.size)
    assertEquals(expected, fixture.radio.sent.last())
    fixture.radio.receive(response)
    runCurrent()
}

internal fun okPacket() = packet(ResponseCode.OK)

internal class DeviceRows : DevicePersisting, DeviceEditPersisting {
    val rows = mutableMapOf<UUID, DeviceDTO>()
    var fetchFailure: PersistenceStoreException? = null
    var saveFailure: PersistenceStoreException? = null
    var beforeSave: suspend () -> Unit = {}
    private var revision = 0L
    override suspend fun insertDeviceIfAbsent(dto: DeviceDTO): Boolean {
        if (dto.id in rows) return false
        saveDevice(dto.copy(isActive = false))
        return true
    }
    override suspend fun activateDeviceIfCurrent(id: UUID, isCurrent: () -> Boolean): Boolean = unsupported()
    override suspend fun fetchDeviceWriteSnapshot(id: UUID): DeviceWriteSnapshot? =
        fetchDevice(id)?.let { DeviceWriteSnapshot(it, revision) }
    override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
        return saveDeviceIfUnchanged(dto, expected) { true }
    }
    override suspend fun saveDeviceIfUnchanged(
        dto: DeviceDTO,
        expected: DeviceWriteSnapshot,
        isCurrent: () -> Boolean,
    ): Boolean {
        beforeSave()
        saveFailure?.let { throw it }
        if (!isCurrent() || revision != expected.revision || rows[dto.id] != expected.device) return false
        rows[dto.id] = dto
        revision++
        return true
    }
    override suspend fun fetchDevice(id: UUID): DeviceDTO? { fetchFailure?.let { throw it }; return rows[id] }
    override suspend fun fetchDevice(radioId: RadioId): DeviceDTO? = rows.values.firstOrNull { it.radioId == radioId }
    override suspend fun fetchDevice(publicKey: Bytes): DeviceDTO? = rows.values.firstOrNull { it.publicKey == publicKey }
    override suspend fun fetchDevices(): SnapshotList<DeviceDTO> = rows.values.snapshot()
    override suspend fun fetchActiveDevice(): DeviceDTO? = rows.values.firstOrNull { it.isActive }
    override suspend fun saveDevice(dto: DeviceDTO) { beforeSave(); saveFailure?.let { throw it }; rows[dto.id] = dto; revision++ }
    override suspend fun setActiveDevice(id: UUID): Unit = unsupported()
    override suspend fun updateDeviceLastContactSync(radioId: RadioId, timestamp: UInt): Unit = unsupported()
    override suspend fun addDeviceKnownRegion(radioId: RadioId, region: String): Unit = unsupported()
    override suspend fun removeDeviceKnownRegion(radioId: RadioId, region: String): Unit = unsupported()
    override suspend fun deleteDeviceData(id: UUID): Unit = unsupported()
    override suspend fun deleteDevice(id: UUID): Unit = unsupported()
    override suspend fun demoteDeviceToGhost(id: UUID): Unit = unsupported()
    override suspend fun deleteDeviceAndData(id: UUID): Unit = unsupported()
    override suspend fun reconcileGhostIdentity(currentDeviceID: UUID, newPublicKey: Bytes): RadioId? = unsupported()
    private fun unsupported(): Nothing = throw UnsupportedOperationException("Untested persistence operation is not a success-shaped fake")
}

internal fun testDevice(id: UUID = UUID.fromString("00000000-0000-0000-0000-000000000004")) =
    DeviceDTO(id, RADIO, filled(0x42), "Test", lastConnected = NOW)
