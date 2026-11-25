// PortedFrom: MC1/Views/Tools/NodeDiscovery/NodeDiscoveryViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.discovery

import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MeshEvent
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Scan filter and its firmware type mask. */
enum class NodeDiscoveryFilter(val filterValue: UByte) {
    REPEATERS(0x04u),
    SENSORS(0x10u),
}

enum class NodeDiscoverySortOrder { SNR, NAME }

/** One discover response, keyed by public key within the filter it was scanned under. */
data class NodeDiscoveryResult(
    val name: String,
    val publicKey: Bytes,
    val nodeType: UByte,
    val snr: Double,
    val snrIn: Double,
    val rssi: Long,
    val scanFilter: NodeDiscoveryFilter,
    val receivedAt: Instant,
    val id: UUID = UUID.randomUUID(),
)

/** Node-discover request and the session event stream (`MeshCoreSession` subset). */
interface NodeDiscoverySession {
    suspend fun sendNodeDiscoverRequest(filter: UByte, prefixOnly: Boolean): UInt
    fun events(): Flow<MeshEvent>
}

/** Name sources for discovered keys. */
interface NodeDiscoveryDirectory {
    suspend fun fetchDiscoveredNodes(radioId: RadioId): List<DiscoveredNodeDTO>
    suspend fun fetchContacts(radioId: RadioId): List<ContactDTO>
}

/** `ContactService.addOrUpdateContact` subset. */
fun interface NodeDiscoveryContactAdder {
    suspend fun addOrUpdateContact(radioId: RadioId, contact: ContactFrame)
}

/** How an add failed: WP-209 `ContactServiceError.contactTableFull`, or anything else. */
enum class AddContactFailure { CONTACT_TABLE_FULL, OTHER }

/** Live providers; `null` mirrors a disconnected state. */
interface NodeDiscoveryFeatureDependencies {
    fun session(): NodeDiscoverySession?
    fun directory(): NodeDiscoveryDirectory?
    fun radioId(): RadioId?
    fun contactAdder(): NodeDiscoveryContactAdder?
    fun maxContacts(): UShort?

    /** Classifies an add failure; WP-303 maps WP-209's `ContactServiceError`. */
    fun classifyAddFailure(error: Exception): AddContactFailure
}

/** Localized copy and error text for discovery. */
interface NodeDiscoveryStrings {
    fun filterTitle(filter: NodeDiscoveryFilter): String
    fun notConnectedDescription(filterTitle: String): String
    val unknownNode: String
    fun nodeListFull(maxContacts: Int): String
    val nodeListFullSimple: String

    /** The source's `Error.userFacingMessage`. */
    fun userFacingMessage(error: Exception): String
}
