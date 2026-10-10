// AndroidOnly: WP-313 Explicit synthetic responses for native remote-node flows; no physical radio or map network.
package com.meshcoreone.android.app.remotenodes

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.*
import com.meshcoreone.android.core.protocol.lpp.LPPEncoder
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesClock
import com.meshcoreone.android.feature.remotenodes.dependencies.*
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.delay

internal class RemoteNodesFixture :
    RemoteNodesUiDependencies, RemoteNodesFeatureDependencies, RepeaterAdminPort, RoomAdminPort,
    RemoteNodeHistoryStore, RemoteNodeLoginPort, ContactOcvPort {
    val radio = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000313"))
    val publicKey = Bytes(ByteArray(32) { 0x42 })
    val contact = ContactDTO(
        radioId = radio, publicKey = publicKey, name = "Synthetic repeater",
        typeRawValue = ContactType.REPEATER.rawValue, outPathLength = 0u, lastHeardTimestamp = 0u,
    )
    var session = RemoteNodeSessionDTO(
        radioId = radio, publicKey = publicKey, name = contact.name, role = RemoteNodeRole.REPEATER,
        isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN, latitude = 37.7, longitude = -122.4,
    )
    var contacts = listOf(contact)
    var sessions = listOf(session)
    var connection = RemoteNodesConnection(radio, "fixture-generation", true)
    override val updates = MutableSharedFlow<RemoteNodesConnection>(replay = 1).apply { tryEmit(connection) }
    override val radioOptions = RemoteRadioOptions(listOf(7.8, 31.25, 125.0, 250.0, 500.0), (5L..12L).toList(), (5L..8L).toList())
    val commands = mutableListOf<String>()
    var loginStarted = 0
    var loginCancelled = 0
    var statusReads = 0
    var historyError: Exception? = null
    var snapshots = listOf(
        NodeStatusSnapshotDTO(
            nodePublicKey = publicKey, timestamp = Instant.now().minusSeconds(3600), batteryMillivolts = 3850u,
            latitude = 37.7, longitude = -122.4, altitude = 42.0,
        ),
        NodeStatusSnapshotDTO(
            nodePublicKey = publicKey, timestamp = Instant.now(), batteryMillivolts = 3900u,
            latitude = 37.8, longitude = -122.5, altitude = 43.0,
        ),
    )
    var cliHandler: (suspend (ContactMessage, ContactDTO) -> Unit)? = null
    var statusHandler: (suspend (StatusResponse) -> Unit)? = null
    var telemetryHandler: (suspend (TelemetryResponse) -> Unit)? = null
    var neighborsHandler: (suspend (NeighboursResponse) -> Unit)? = null

    fun publish() { check(updates.tryEmit(connection)) }
    override suspend fun catalog() = RemoteNodesCatalog(contacts, emptyList(), sessions)
    override fun services(): RemoteNodesFeatureDependencies = this
    override val clock = object : RemoteNodesClock {
        override val now get() = Instant.now()
        override val elapsed get() = Duration.ZERO
        override suspend fun sleep(duration: Duration) = delay(duration)
    }
    override val faults = object : RemoteNodeFaultClassifier {
        override fun isTimeout(error: Throwable) = false
        override fun isRemoteNoResponseYet(error: Throwable) = false
        override fun isBinarySessionTimeout(error: Throwable) = false
    }
    override fun repeaterAdmin(): RepeaterAdminPort = this
    override fun roomAdmin(): RoomAdminPort = this
    override fun binaryTelemetry(): BinaryTelemetryPort? = null
    override fun nodeSnapshots(): NodeSnapshotPort? = null
    override fun contactOcv(): ContactOcvPort = this
    override fun historyStore(): RemoteNodeHistoryStore = this
    override fun login(): RemoteNodeLoginPort = this
    override fun connectedRadioId() = connection.radioId.takeIf { connection.ready }
    override fun deviceHashSize() = 1
    override suspend fun sendCommand(session: EntityKey, command: String, timeout: Duration): String {
        check(session.radioId == radio)
        commands += command
        return when (command) {
            "get owner.info" -> "KD7ABC"
            "get name" -> contact.name
            "ver" -> "v1.17.1 (fixture)"
            "clock" -> "06:40 - 18/4/2025 UTC"
            "get radio" -> "915.0,250.0,10,5"
            "get lat" -> "37.7"
            "get lon" -> "-122.4"
            "get repeat" -> "on"
            "get advert.interval" -> "60"
            "get flood.advert.interval" -> "3"
            "get flood.max" -> "8"
            "get guest.password" -> "fixture"
            "get allow.read.only" -> "on"
            "region" -> "*"
            "region default" -> "default scope is <null>"
            else -> if (command.startsWith("set ") || command.startsWith("password ") ||
                command == "reboot" || command == "advert" || command.startsWith("time ") ||
                command.startsWith("region ")
            ) "OK" else error("Unexpected synthetic CLI command: $command")
        }
    }
    override suspend fun sendRawCommand(session: EntityKey, command: String, timeout: Duration) = sendCommand(session, command, timeout)
    override fun setCLIHandler(handler: suspend (ContactMessage, ContactDTO) -> Unit) { cliHandler = handler }
    override suspend fun requestStatus(session: EntityKey, timeout: Duration?): StatusResponse {
        statusReads++
        return StatusResponse(
            publicKey.prefix(6), 3850, 0, -120, -87, 1000u, 500u, 100u, 3600u, 0u, 0u, 0u, 0u,
            0, 8.5, 0, 0, 100u,
            layout = if (this.session.isRoom) StatusResponse.Layout.ROOM_SERVER else StatusResponse.Layout.REPEATER,
            roomServerPostedCount = if (this.session.isRoom) 12u else null,
            roomServerPostPushCount = if (this.session.isRoom) 24u else null,
        )
    }
    override suspend fun requestTelemetry(session: EntityKey, timeout: Duration?): TelemetryResponse =
        TelemetryResponse(publicKey.prefix(6), null, LPPEncoder().apply { addTemperature(1u, 22.5) }.encode())
    override suspend fun requestOwnerInfo(session: EntityKey, timeout: Duration?) = OwnerInfoResponse("v1.17.1", contact.name, "KD7ABC")
    override suspend fun fetchAllNeighbors(session: EntityKey, timeout: Duration?) =
        NeighboursResponse(publicKey.prefix(6), Bytes.of(0, 0, 0, 1), 1, listOf(Neighbour(Bytes.of(1, 2, 3, 4, 5, 6), 30, 5.5)))
    override fun setStatusHandler(handler: suspend (StatusResponse) -> Unit) { statusHandler = handler }
    override fun setTelemetryHandler(handler: suspend (TelemetryResponse) -> Unit) { telemetryHandler = handler }
    override fun setNeighboursHandler(handler: suspend (NeighboursResponse) -> Unit) { neighborsHandler = handler }
    override fun clearHandlers() { clearStatusHandlers(); cliHandler = null }
    override fun clearStatusHandlers() { statusHandler = null; telemetryHandler = null; neighborsHandler = null }
    override suspend fun fetchNodeStatusSnapshots(nodePublicKey: Bytes, since: Instant?): SnapshotList<NodeStatusSnapshotDTO> {
        historyError?.let { throw it }
        return snapshots.filter { it.nodePublicKey == nodePublicKey }.snapshot()
    }
    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes) = contacts.firstOrNull { it.radioId == radioId && it.publicKey == publicKey }
    override suspend fun fetchContacts(radioId: RadioId) = contacts.filter { it.radioId == radioId }.snapshot()
    override suspend fun fetchDiscoveredNodes(radioId: RadioId): SnapshotList<DiscoveredNodeDTO> = SnapshotList.empty()
    override suspend fun getContact(radioId: RadioId, publicKey: Bytes) = fetchContact(radioId, publicKey)
    override suspend fun updateContactOCVSettings(contactId: UUID, preset: String, customArray: String?) { commands += "local-ocv:$preset" }
    override suspend fun retrievePassword(contact: ContactDTO): String? = null
    override suspend fun deletePassword(contact: ContactDTO) = Unit
    override suspend fun resetPath(radioId: RadioId, publicKey: Bytes) { commands += "reset-path" }
    override suspend fun connectAsAdmin(
        radioId: RadioId, contact: ContactDTO, password: String, rememberPassword: Boolean, pathLength: UByte,
        onTimeoutKnown: suspend (Long) -> Unit,
    ): RemoteNodeSessionDTO {
        loginStarted++
        onTimeoutKnown(30)
        try { awaitCancellation() } finally { loginCancelled++ }
    }
    override suspend fun joinRoom(
        radioId: RadioId, contact: ContactDTO, password: String, rememberPassword: Boolean, pathLength: UByte,
        onTimeoutKnown: suspend (Long) -> Unit,
    ) = connectAsAdmin(radioId, contact, password, rememberPassword, pathLength, onTimeoutKnown)
}
