// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/DiscoveredNodePersisting.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID

internal class DiscoveredNodeRepository(private val context: RoomRepositoryContext) : DiscoveredNodePersisting {
    private data class HopKey(val radioId: RadioId, val publicKey: Bytes)
    private val pendingHops = LinkedHashMap<HopKey, InboundHop>()

    override suspend fun upsertDiscoveredNode(radioId: RadioId, frame: ContactFrame): DiscoveredNodeSaveResult =
        context.write("upsertDiscoveredNode") { upsertInTransaction(this, radioId, frame) }

    suspend fun upsertInTransaction(
        transaction: RepositoryTransaction, radioId: RadioId, frame: ContactFrame,
    ): DiscoveredNodeSaveResult = with(transaction) {
        val existing = database.discoveredNodes().forPublicKey(radioId.value, frame.publicKey).firstOrNull()
        var row = existing?.copy(
            name = frame.name, typeRawValue = frame.type.rawValue.toLong(), lastHeard = StoredInstant.from(clock.instant()),
            lastAdvertTimestamp = frame.lastAdvertTimestamp.toLong(), latitude = frame.latitude, longitude = frame.longitude,
            outPathLength = frame.outPathLength.toLong(), outPath = frame.outPath,
        ) ?: DiscoveredNodeDTO(
            UUID.randomUUID(), radioId, frame.publicKey, frame.name, frame.type.rawValue, clock.instant(),
            frame.lastAdvertTimestamp, frame.latitude, frame.longitude, frame.outPathLength, frame.outPath, null, null,
        ).toEntity()
        database.discoveredNodes().upsert(row)
        if (existing == null) {
            val excess = database.discoveredNodes().count(radioId.value) - MAX_DISCOVERED_NODES
            if (excess > 0) {
                for (oldest in database.discoveredNodes().oldest(radioId.value, excess)) {
                    database.discoveredNodes().delete(radioId.value, oldest.id)
                }
            }
        }
        val key = HopKey(radioId, frame.publicKey)
        val buffered = pendingHops[key]
        if (buffered != null) {
            val stored = row.toDTO()
            val adopted = adoptInboundHop(stored.inboundHopCount, stored.inboundHopAdvertTimestamp, buffered.count, buffered.timestamp)
            if (adopted != null) {
                row = row.copy(inboundHopCount = adopted.count, inboundHopAdvertTimestamp = adopted.timestamp?.toLong())
                if (database.discoveredNodes().byId(radioId.value, row.id) != null) {
                    database.discoveredNodes().upsert(row)
                }
            }
            afterCommit { pendingHops.remove(key) }
        }
        save()
        DiscoveredNodeSaveResult(row.toDTO(), existing == null)
    }

    override suspend fun setInboundHopCount(
        radioId: RadioId, publicKey: Bytes, hopCount: Long, advertTimestamp: UInt?,
    ) = context.write("setInboundHopCount") {
        val row = database.discoveredNodes().forPublicKey(radioId.value, publicKey).firstOrNull()
        if (row == null) {
            val key = HopKey(radioId, publicKey)
            val previous = pendingHops[key]
            val adopted = adoptInboundHop(previous?.count, previous?.timestamp, hopCount, advertTimestamp)
                ?: return@write
            afterCommit {
                if (previous == null && pendingHops.size >= MAX_PENDING_HOPS) pendingHops.remove(pendingHops.keys.first())
                pendingHops[key] = adopted
            }
        } else {
            val dto = row.toDTO()
            val adopted = adoptInboundHop(dto.inboundHopCount, dto.inboundHopAdvertTimestamp, hopCount, advertTimestamp)
                ?: return@write
            database.discoveredNodes().upsert(row.copy(
                inboundHopCount = adopted.count, inboundHopAdvertTimestamp = adopted.timestamp?.toLong(),
            ))
            save()
        }
    }

    override suspend fun fetchDiscoveredNodes(radioId: RadioId): SnapshotList<DiscoveredNodeDTO> =
        context.read("fetchDiscoveredNodes") {
            database.discoveredNodes().forRadio(radioId.value).map { it.toDTO() }.snapshot()
        }

    override suspend fun deleteDiscoveredNode(key: EntityKey) = context.write("deleteDiscoveredNode") {
        if (database.discoveredNodes().delete(key.radioId.value, key.id) > 0) save()
    }

    override suspend fun clearDiscoveredNodes(radioId: RadioId) = context.write("clearDiscoveredNodes") {
        database.discoveredNodes().clearRadio(radioId.value)
        save()
    }

    override suspend fun fetchContactPublicKeys(radioId: RadioId): SnapshotSet<Bytes> =
        context.read("fetchContactPublicKeys") {
            database.contacts().forRadio(radioId.value).map { it.publicKey }.snapshotSet()
        }

    companion object {
        const val MAX_DISCOVERED_NODES = 1000L
        const val MAX_PENDING_HOPS = 256
    }
}
