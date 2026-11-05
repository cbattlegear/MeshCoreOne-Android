// AndroidOnly: WP-313 Feature-owned ports over the WP-209/210/211 services; app wiring adapts core:services, which features may not import.
package com.meshcoreone.android.feature.remotenodes.dependencies

import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.contracts.domain.DiscoveredNodePersisting
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NeighborBaseline
import com.meshcoreone.android.core.contracts.domain.NodeSnapshotPersisting
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.NeighborSnapshotEntry
import com.meshcoreone.android.core.model.NodeLocationFix
import com.meshcoreone.android.core.model.NodeStatusMetrics
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.TelemetrySnapshotEntry
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ContactMessage
import com.meshcoreone.android.core.protocol.event.NeighboursResponse
import com.meshcoreone.android.core.protocol.event.OwnerInfoResponse
import com.meshcoreone.android.core.protocol.event.StatusResponse
import com.meshcoreone.android.core.protocol.event.TelemetryResponse
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesClock
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration

/**
 * The service failures the Swift screens pattern-match on. `RemoteNodeError` / `BinaryProtocolError`
 * live in core:services (WP-210), so app wiring classifies them here instead of the feature
 * importing them.
 */
interface RemoteNodeFaultClassifier {
    /** `RemoteNodeError.timeout`. */
    fun isTimeout(error: Throwable): Boolean

    /**
     * `RemoteNodeError.sessionError` or `BinaryProtocolError.sessionError` wrapping
     * `MeshCoreError.deviceError(FirmwareDeviceErrorCode.remoteNodeNoResponseYet)`: the transient
     * "node has not answered yet" reply that the status screens retry.
     */
    fun isRemoteNoResponseYet(error: Throwable): Boolean

    /** `BinaryProtocolError.sessionError(MeshCoreError.timeout)` from a direct binary request. */
    fun isBinarySessionTimeout(error: Throwable): Boolean
}

/** CLI surface shared by `RepeaterAdminService` and `RoomAdminService` (WP-210). */
interface RemoteNodeCliPort {
    /** Structured CLI command; replies to structured gets are shape-checked by the service. */
    suspend fun sendCommand(session: EntityKey, command: String, timeout: Duration): String

    /** Raw CLI command; the next reply is delivered verbatim. */
    suspend fun sendRawCommand(session: EntityKey, command: String, timeout: Duration): String

    /** Replaces the service's single CLI handler slot (late, unclaimed CLI replies). */
    fun setCLIHandler(handler: suspend (ContactMessage, ContactDTO) -> Unit)
}

/** Status/telemetry slots shared by the repeater and room admin services. */
interface RemoteNodeStatusPort : RemoteNodeCliPort {
    suspend fun requestStatus(session: EntityKey, timeout: Duration?): StatusResponse
    suspend fun requestTelemetry(session: EntityKey, timeout: Duration?): TelemetryResponse
    fun setStatusHandler(handler: suspend (StatusResponse) -> Unit)
    fun setTelemetryHandler(handler: suspend (TelemetryResponse) -> Unit)

    /** Clears every handler slot, including the CLI handler (true surface teardown). */
    fun clearHandlers()

    /** Clears the status-surface slots only, leaving the CLI handler intact. */
    fun clearStatusHandlers()
}

/** `RepeaterAdminService` (WP-210). */
interface RepeaterAdminPort : RemoteNodeStatusPort {
    suspend fun requestOwnerInfo(session: EntityKey, timeout: Duration?): OwnerInfoResponse
    suspend fun fetchAllNeighbors(session: EntityKey, timeout: Duration?): NeighboursResponse
    fun setNeighboursHandler(handler: suspend (NeighboursResponse) -> Unit)
}

/** `RoomAdminService` (WP-210). */
interface RoomAdminPort : RemoteNodeStatusPort

/** `BinaryProtocolService.requestTelemetry(from:)` (WP-210) for login-free chat-node telemetry. */
fun interface BinaryTelemetryPort {
    suspend fun requestTelemetry(publicKey: Bytes): TelemetryResponse
}

/**
 * The login sheet's service calls: `RemoteNodeService` password store and `RepeaterAdminService.connectAsAdmin` /
 * `RoomServerService.joinRoom` (WP-210), plus `ContactService.resetPath` (WP-209).
 */
interface RemoteNodeLoginPort {
    suspend fun retrievePassword(contact: ContactDTO): String?
    suspend fun deletePassword(contact: ContactDTO)
    suspend fun resetPath(radioId: RadioId, publicKey: Bytes)
    suspend fun connectAsAdmin(
        radioId: RadioId, contact: ContactDTO, password: String, rememberPassword: Boolean, pathLength: UByte,
        onTimeoutKnown: suspend (seconds: Long) -> Unit,
    ): com.meshcoreone.android.core.model.RemoteNodeSessionDTO
    suspend fun joinRoom(
        radioId: RadioId, contact: ContactDTO, password: String, rememberPassword: Boolean, pathLength: UByte,
        onTimeoutKnown: suspend (seconds: Long) -> Unit,
    ): com.meshcoreone.android.core.model.RemoteNodeSessionDTO
}

/** `NodeSnapshotService` (WP-210): throttled snapshot capture plus history reads. */
interface NodeSnapshotPort {
    suspend fun recordSnapshot(
        nodePublicKey: Bytes,
        status: NodeStatusMetrics? = null,
        telemetry: SnapshotList<TelemetrySnapshotEntry>? = null,
        neighbors: SnapshotList<NeighborSnapshotEntry>? = null,
        location: NodeLocationFix? = null,
    ): UUID?

    suspend fun neighborBaseline(nodePublicKey: Bytes): NeighborBaseline
    suspend fun previousStatusSnapshot(nodePublicKey: Bytes, before: Instant): NodeStatusSnapshotDTO?
    suspend fun fetchSnapshots(nodePublicKey: Bytes, since: Instant? = null): SnapshotList<NodeStatusSnapshotDTO>
}

/** The `ContactService` (WP-209) calls behind the battery-curve (OCV) section. */
interface ContactOcvPort {
    suspend fun getContact(radioId: RadioId, publicKey: Bytes): ContactDTO?
    suspend fun updateContactOCVSettings(contactId: UUID, preset: String, customArray: String?)
}

/** The `PersistenceStore` reads behind the history overview and the login sheet's path rows. */
interface RemoteNodeHistoryStore {
    val authorityIdentity: Any get() = this

    suspend fun fetchNodeStatusSnapshots(nodePublicKey: Bytes, since: Instant?): SnapshotList<NodeStatusSnapshotDTO>
    suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO?
    suspend fun fetchContacts(radioId: RadioId): SnapshotList<ContactDTO>
    suspend fun fetchDiscoveredNodes(radioId: RadioId): SnapshotList<DiscoveredNodeDTO>
}

/** Adapts the core:contracts persistence roles (one `PersistenceStore` in production). */
private class RemoteNodeHistoryAuthority(
    private val snapshots: NodeSnapshotPersisting,
    private val contacts: ContactPersisting,
    private val discoveredNodes: DiscoveredNodePersisting,
) {
    override fun equals(other: Any?) = other is RemoteNodeHistoryAuthority &&
        snapshots === other.snapshots &&
        contacts === other.contacts &&
        discoveredNodes === other.discoveredNodes

    override fun hashCode() =
        31 * (31 * System.identityHashCode(snapshots) + System.identityHashCode(contacts)) +
            System.identityHashCode(discoveredNodes)
}

fun remoteNodeHistoryStore(
    snapshots: NodeSnapshotPersisting,
    contacts: ContactPersisting,
    discoveredNodes: DiscoveredNodePersisting,
): RemoteNodeHistoryStore = object : RemoteNodeHistoryStore {
    override val authorityIdentity = RemoteNodeHistoryAuthority(snapshots, contacts, discoveredNodes)

    override suspend fun fetchNodeStatusSnapshots(nodePublicKey: Bytes, since: Instant?) =
        snapshots.fetchNodeStatusSnapshots(nodePublicKey, since)
    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes) = contacts.fetchContact(radioId, publicKey)
    override suspend fun fetchContacts(radioId: RadioId) = contacts.fetchContacts(radioId)
    override suspend fun fetchDiscoveredNodes(radioId: RadioId) = discoveredNodes.fetchDiscoveredNodes(radioId)
}

/**
 * Typed inputs the remote-node entry resolves; app supplies them. Service accessors are providers
 * (Swift `@MainActor () -> Service?`): a reconnect mints new service instances and a null return
 * mirrors a disconnected radio, so state holders read them at call time.
 */
interface RemoteNodesFeatureDependencies {
    val clock: RemoteNodesClock
    val faults: RemoteNodeFaultClassifier
    fun repeaterAdmin(): RepeaterAdminPort?
    fun roomAdmin(): RoomAdminPort?
    fun binaryTelemetry(): BinaryTelemetryPort?
    fun nodeSnapshots(): NodeSnapshotPort?
    fun contactOcv(): ContactOcvPort?
    fun historyStore(): RemoteNodeHistoryStore?
    fun login(): RemoteNodeLoginPort?

    /** The connected companion's radio, or null while disconnected (`appState.connectedDevice?.radioID`). */
    fun connectedRadioId(): RadioId?

    /** The connected companion's `DeviceDTO.hashSize`, or null while disconnected. */
    fun deviceHashSize(): Int?
}
