// AndroidOnly: WP-210 Shared node-config/snapshot test helpers: dynamic-case builders, deterministic clock, in-memory snapshot store and session/settings fakes.
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.NodeSnapshotPersisting
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.*
import com.meshcoreone.android.core.protocol.model.*
import com.meshcoreone.android.core.protocol.session.ChannelFetchResult
import com.meshcoreone.android.core.protocol.session.ContactFetchResult
import com.meshcoreone.android.core.protocol.session.MeshCoreSessionProtocol
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.DynamicTest

/** Original Swift cases: display name `Suite::original name()`, matching the frozen Swift case ids. */
internal fun nodeConfigSourceCases(suite: String, vararg cases: Pair<String, suspend () -> Unit>): List<DynamicTest> =
    cases.map { (name, body) -> DynamicTest.dynamicTest("$suite::$name()") { nodeConfigRun(body) } }

/** Android-native cases, named `WP-210::description`. */
internal fun nodeConfigNativeCases(vararg cases: Pair<String, suspend () -> Unit>): List<DynamicTest> =
    cases.map { (name, body) -> DynamicTest.dynamicTest("WP-210::$name") { nodeConfigRun(body) } }

private fun nodeConfigRun(body: suspend () -> Unit) = runBlocking { withTimeout(30.seconds) { body() } }

/** Polls a condition (the Swift tests' `waitUntil`), failing after [timeoutSeconds]; no fixed sleeps. */
internal suspend fun nodeConfigWaitUntil(label: String, timeoutSeconds: Long = 5, condition: suspend () -> Boolean) {
    val deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos()
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Timed out waiting: $label" }
        delay(1)
    }
}

/**
 * A deterministic clock that advances one millisecond per read, so "now" observed after a capture is
 * strictly later than the capture (the Swift tests rely on `Date.now` moving forward).
 */
internal class NodeConfigTestClock(start: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
    private val lock = Any()
    private var current = start
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = synchronized(lock) { current.also { current = it.plusMillis(1) } }
    fun peek(): Instant = synchronized(lock) { current }
}

/**
 * In-memory [NodeSnapshotPersisting] with the same record/throttle semantics as the production
 * repository (DiagnosticRepository): the in-window read-modify-write runs under one mutex.
 */
internal class NodeConfigSnapshotStore(private val clock: Clock) : NodeSnapshotPersisting {
    private val mutex = Mutex()
    private val rows = mutableListOf<NodeStatusSnapshotDTO>()

    private fun history(key: Bytes) = rows.filter { it.nodePublicKey == key }.sortedBy { it.timestamp }

    private fun upsert(dto: NodeStatusSnapshotDTO) {
        val index = rows.indexOfFirst { it.id == dto.id }
        if (index >= 0) rows[index] = dto else rows += dto
    }

    /** Swift `store.saveNodeStatusSnapshot(timestamp:...)`: writes a row at an explicit time, bypassing the throttle. */
    suspend fun insert(
        nodePublicKey: Bytes, timestamp: Instant? = null, batteryMillivolts: UShort? = null, lastSNR: Double? = null,
        lastRSSI: Short? = null, noiseFloor: Short? = null, uptimeSeconds: UInt? = null,
    ): UUID = mutex.withLock {
        val dto = NodeStatusSnapshotDTO(
            timestamp = timestamp ?: clock.instant(), nodePublicKey = nodePublicKey, batteryMillivolts = batteryMillivolts,
            lastSNR = lastSNR, lastRSSI = lastRSSI, noiseFloor = noiseFloor, uptimeSeconds = uptimeSeconds,
        )
        rows += dto
        dto.id
    }

    override suspend fun saveNodeStatusSnapshot(
        nodePublicKey: Bytes, batteryMillivolts: UShort?, lastSNR: Double?, lastRSSI: Short?, noiseFloor: Short?,
        uptimeSeconds: UInt?, rxAirtimeSeconds: UInt?, packetsSent: UInt?, packetsReceived: UInt?, receiveErrors: UInt?,
        postedCount: UShort?, postPushCount: UShort?,
    ): UUID = saveNodeStatusSnapshot(nodePublicKey, NodeStatusMetrics(
        batteryMillivolts, lastSNR, lastRSSI, noiseFloor, uptimeSeconds, rxAirtimeSeconds, packetsSent, packetsReceived,
        receiveErrors, postedCount = postedCount, postPushCount = postPushCount,
    ))

    override suspend fun saveNodeStatusSnapshot(nodePublicKey: Bytes, status: NodeStatusMetrics): UUID = mutex.withLock {
        val dto = NodeStatusSnapshotDTO(timestamp = clock.instant(), nodePublicKey = nodePublicKey).applying(status)
        rows += dto
        dto.id
    }

    override suspend fun fetchLatestNodeStatusSnapshot(nodePublicKey: Bytes): NodeStatusSnapshotDTO? =
        mutex.withLock { history(nodePublicKey).lastOrNull() }

    override suspend fun fetchNodeStatusSnapshots(nodePublicKey: Bytes, since: Instant?): SnapshotList<NodeStatusSnapshotDTO> =
        mutex.withLock { history(nodePublicKey).filter { since == null || it.timestamp >= since }.snapshot() }

    override suspend fun updateSnapshotNeighbors(id: UUID, neighbors: SnapshotList<NeighborSnapshotEntry>) = mutex.withLock {
        rows.firstOrNull { it.id == id }?.let { upsert(it.copy(neighborSnapshots = neighbors)) }
        Unit
    }

    override suspend fun updateSnapshotTelemetry(id: UUID, telemetry: SnapshotList<TelemetrySnapshotEntry>) = mutex.withLock {
        rows.firstOrNull { it.id == id }?.let { upsert(it.copy(telemetryEntries = telemetry)) }
        Unit
    }

    override suspend fun saveTelemetryOnlySnapshot(nodePublicKey: Bytes, telemetryEntries: SnapshotList<TelemetrySnapshotEntry>): UUID =
        mutex.withLock {
            val dto = NodeStatusSnapshotDTO(timestamp = clock.instant(), nodePublicKey = nodePublicKey, telemetryEntries = telemetryEntries)
            rows += dto
            dto.id
        }

    override suspend fun recordNodeStatusSnapshot(
        nodePublicKey: Bytes, status: NodeStatusMetrics?, telemetry: SnapshotList<TelemetrySnapshotEntry>?,
        neighbors: SnapshotList<NeighborSnapshotEntry>?, location: NodeLocationFix?,
    ): UUID = mutex.withLock {
        val latest = history(nodePublicKey).lastOrNull()
        val now = clock.instant()
        var dto = if (latest != null && Duration.between(latest.timestamp, now) < NodeSnapshotPolicy.minimumInterval) latest
            else NodeStatusSnapshotDTO(timestamp = now, nodePublicKey = nodePublicKey)
        if (status != null && dto.uptimeSeconds == null) dto = dto.applying(status)
        if (telemetry != null) dto = dto.copy(telemetryEntries = telemetry)
        if (neighbors != null) dto = dto.copy(neighborSnapshots = neighbors)
        if (location != null && dto.latitude == null) {
            dto = dto.copy(latitude = location.latitude, longitude = location.longitude, altitude = location.altitude)
        }
        upsert(dto)
        dto.id
    }

    override suspend fun deleteOldNodeStatusSnapshots(olderThan: Instant) = mutex.withLock {
        rows.removeAll { it.timestamp < olderThan }
        Unit
    }
}

/** A [NodeConfigSettingsPort] over in-memory device state that records every write by label. */
internal class NodeConfigFakeSettings(
    var selfInfo: SelfInfo,
    var capabilities: DeviceCapabilities = DeviceCapabilities(10u, 100, 8, 0u, "build", "model", "1.0"),
    var privateKey: () -> Bytes = { Bytes(ByteArray(64) { 0x11 }) },
) : NodeConfigSettingsPort {
    private val lock = Any()
    private val recorded = mutableListOf<String>()
    val calls: List<String> get() = synchronized(lock) { recorded.toList() }
    private fun record(label: String) = synchronized(lock) { recorded += label }

    override suspend fun getSelfInfo(): SelfInfo = selfInfo.also { record("getSelfInfo") }
    override suspend fun queryDevice(): DeviceCapabilities = capabilities.also { record("queryDevice") }
    override suspend fun exportPrivateKey(): Bytes = privateKey().also { record("exportPrivateKey") }
    override suspend fun importPrivateKey(key: Bytes) = record("importPrivateKey:${key.size}")
    override suspend fun setNodeName(name: String) = record("setNodeName:$name")
    override suspend fun setLocation(latitude: Double, longitude: Double) = record("setLocation:$latitude,$longitude")
    override suspend fun setRadioParams(frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte, codingRate: UByte) =
        record("setRadioParams:$frequencyKHz,$bandwidthKHz,$spreadingFactor,$codingRate")
    override suspend fun setTxPower(power: Byte) = record("setTxPower:$power")
    override suspend fun setOtherParams(autoAddContacts: Boolean, telemetryModes: TelemetryModes, advertLocationPolicyRaw: UByte, multiAcks: UByte) =
        record("setOtherParams:$autoAddContacts,${telemetryModes.packed},$advertLocationPolicyRaw,$multiAcks")
}

/** A [MeshCoreSessionProtocol] exposing only the contact/channel reads and writes the config service uses. */
internal class NodeConfigFakeSession(
    var contacts: List<MeshContact> = emptyList(),
    var channels: List<ChannelInfo> = emptyList(),
) : MeshCoreSessionProtocol {
    private val lock = Any()
    private val added = mutableListOf<MeshContact>()
    val addedContacts: List<MeshContact> get() = synchronized(lock) { added.toList() }
    var channelReads = 0
        private set

    override suspend fun getContacts(since: Instant?): List<MeshContact> = contacts
    override suspend fun addContact(contact: MeshContact) { synchronized(lock) { added += contact } }
    override suspend fun getChannel(index: UByte): ChannelInfo {
        channelReads++
        return channels.firstOrNull { it.index == index } ?: ChannelInfo(index, "", Bytes(ByteArray(16)))
    }

    private fun unused(): Nothing = error("Not used by NodeConfigService")
    override val connectionState: Flow<ConnectionState> get() = emptyFlow()
    override fun events(): Flow<MeshEvent> = emptyFlow()
    override fun events(filter: EventFilter): Flow<MeshEvent> = emptyFlow()
    override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): MeshEvent? = unused()
    override val currentSelfInfo: SelfInfo? get() = null
    override suspend fun sendMessage(destination: Bytes, text: String, timestamp: Instant, attempt: UByte): MessageSentInfo = unused()
    override suspend fun sendChannelMessage(channel: UByte, text: String, timestamp: Instant) = unused()
    override suspend fun getContactsReportingTotal(since: Instant?): ContactFetchResult = unused()
    override suspend fun getContact(publicKey: Bytes): MeshContact? = unused()
    override suspend fun removeContact(publicKey: Bytes) = unused()
    override suspend fun resetPath(publicKey: Bytes) = unused()
    override suspend fun sendPathDiscovery(destination: Bytes): MessageSentInfo = unused()
    override suspend fun shareContact(publicKey: Bytes) = unused()
    override suspend fun exportContact(publicKey: Bytes?): String = unused()
    override suspend fun importContact(cardData: Bytes) = unused()
    override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags) = unused()
    override suspend fun getChannels(indices: List<UByte>): ChannelFetchResult = unused()
    override suspend fun setChannel(index: UByte, name: String, secret: Bytes) = unused()
    override suspend fun getMessage(timeout: Double?): MessageResult = unused()
    override suspend fun startAutoMessageFetching() = unused()
    override suspend fun stopAutoMessageFetching() = unused()
}

/** Swift test fixture `SelfInfo(...)` with named arguments. */
internal fun nodeConfigSelfInfo(
    advertisementType: UByte = 0u, txPower: Byte = 0, maxTxPower: Byte = 30, publicKey: Bytes = Bytes(ByteArray(32)),
    latitude: Double = 0.0, longitude: Double = 0.0, multiAcks: UByte = 0u, advertisementLocationPolicy: UByte = 0u,
    telemetryModeEnvironment: UByte = 0u, telemetryModeLocation: UByte = 0u, telemetryModeBase: UByte = 0u,
    manualAddContacts: Boolean = false, radioFrequency: Double = 910.525, radioBandwidth: Double = 62.5,
    radioSpreadingFactor: UByte = 7u, radioCodingRate: UByte = 5u, name: String = "TestNode",
): SelfInfo = SelfInfo(
    advertisementType, txPower, maxTxPower, publicKey, latitude, longitude, multiAcks, advertisementLocationPolicy,
    telemetryModeEnvironment, telemetryModeLocation, telemetryModeBase, manualAddContacts, radioFrequency, radioBandwidth,
    radioSpreadingFactor, radioCodingRate, name,
)

/** Swift `MeshContact(...)` fixture with named arguments. */
internal fun nodeConfigMeshContact(
    publicKey: Bytes, id: String = publicKey.hexString, type: ContactType = ContactType.CHAT,
    typeRawValue: UByte = type.rawValue, flags: UByte = 0u, outPathLength: UByte = 0xFFu, outPath: Bytes = Bytes.EMPTY,
    advertisedName: String = "Contact", lastAdvertisement: Instant = Instant.EPOCH, latitude: Double = 0.0,
    longitude: Double = 0.0, lastModified: Instant = Instant.EPOCH,
): MeshContact = MeshContact(
    id, publicKey, type, ContactFlags(flags), outPathLength, outPath, advertisedName, lastAdvertisement, latitude,
    longitude, lastModified, typeRawValue,
)

internal fun nodeConfigBytes(value: Int, count: Int): Bytes = Bytes(ByteArray(count) { value.toByte() })

internal fun nodeConfigEmptySlots(count: Int): List<DeviceChannelSlot> =
    (0 until count).map { DeviceChannelSlot(it.toUByte(), "", Bytes.EMPTY, false) }

internal val nodeConfigNoSections = ConfigSections()
internal val nodeConfigAllSections = ConfigSections().selectAll()

/** `planConfigImport` with the Swift tests' default capacities. */
internal fun nodeConfigPlan(
    config: MeshCoreNodeConfig, sections: ConfigSections, maxChannels: Int = 8, maxContacts: Int = 100,
    maxTxPower: Byte = 30, existingChannels: List<DeviceChannelSlot> = nodeConfigEmptySlots(8),
    existingContacts: Map<String, MeshContact> = emptyMap(),
): ConfigImportPlan = planConfigImport(
    config, sections, maxChannels.toUByte(), maxContacts, maxTxPower, existingChannels, existingContacts,
)
