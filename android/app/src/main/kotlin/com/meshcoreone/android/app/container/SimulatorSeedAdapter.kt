// AndroidOnly: WP-217 Adapts the real process Room store to the simulator's seed contract.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageRepeatDTO
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.SnapshotSet
import com.meshcoreone.android.core.model.snapshotSet
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.simulator.NodeStatusSnapshotInsertResult
import com.meshcoreone.android.core.services.simulator.NodeStatusSnapshotKey
import com.meshcoreone.android.core.services.simulator.SimulatorSeedStore

class RoomSimulatorSeedStore(private val store: RoomPersistenceStore) : SimulatorSeedStore {
    override suspend fun saveDevice(dto: DeviceDTO) = store.saveDevice(dto)
    override suspend fun fetchContact(key: EntityKey): ContactDTO? = store.fetchContact(key)
    override suspend fun saveContact(dto: ContactDTO) = store.saveContact(dto)
    override suspend fun saveChannel(dto: ChannelDTO) = store.saveChannel(dto)
    override suspend fun saveMessage(dto: MessageDTO) = store.saveMessage(dto)
    override suspend fun updateMessageLinkPreview(
        key: EntityKey, url: String?, title: String?, imageData: Bytes?, iconData: Bytes?, fetched: Boolean,
    ) = store.updateMessageLinkPreview(key, url, title, imageData, iconData, fetched)
    override suspend fun saveReaction(dto: ReactionDTO) = store.saveReaction(dto)
    override suspend fun updateMessageReactionSummary(message: EntityKey, summary: String?) =
        store.updateMessageReactionSummary(message, summary)
    override suspend fun saveMessageRepeat(radioId: RadioId, dto: MessageRepeatDTO) =
        store.saveMessageRepeat(radioId, dto)
    override suspend fun fetchRxLogEntries(radioId: RadioId, limit: Long): SnapshotList<RxLogEntryDTO> =
        store.fetchRxLogEntries(radioId, limit)
    override suspend fun saveRxLogEntry(dto: RxLogEntryDTO) = store.saveRxLogEntry(dto)
    override suspend fun fetchLatestNodeStatusSnapshot(nodePublicKey: Bytes): NodeStatusSnapshotDTO? =
        store.fetchLatestNodeStatusSnapshot(nodePublicKey)
    override suspend fun existingNodeStatusSnapshotKeys(): SnapshotSet<NodeStatusSnapshotKey> =
        store.existingNodeStatusSnapshotKeys().map { (key, milliseconds) ->
            NodeStatusSnapshotKey(key, milliseconds)
        }.snapshotSet()
    override suspend fun batchInsertNodeStatusSnapshots(
        dtos: SnapshotList<NodeStatusSnapshotDTO>, existingKeys: SnapshotSet<NodeStatusSnapshotKey>,
    ): NodeStatusSnapshotInsertResult {
        val (inserted, skipped) = store.batchInsertNodeStatusSnapshots(
            dtos, existingKeys.mapTo(hashSetOf()) { it.nodePublicKey to it.milliseconds }, skipNodesWithHistory = true,
        )
        return NodeStatusSnapshotInsertResult(inserted, skipped)
    }
}
