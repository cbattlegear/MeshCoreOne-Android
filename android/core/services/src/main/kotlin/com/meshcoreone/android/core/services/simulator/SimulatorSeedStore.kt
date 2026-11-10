// AndroidOnly: WP-217 narrow persistence port for the simulator seed; WP-303 adapts core:data's store to it.
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.contracts.domain.EntityKey
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
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant

/**
 * Narrow persistence surface for the simulator seed and its native safety adaptations.
 *
 * The ordinary persistence members mirror the corresponding `core:contracts` interfaces. `seedContact`
 * and `seedConversations` are simulator-specific hooks, while the snapshot pair mirrors Swift backup-import helpers
 * (`existingNodeStatusSnapshotKeys`, `batchInsertNodeStatusSnapshots`) with no general contract yet.
 *
 * The Swift store upserts contacts, channels, messages, reactions and repeats by row ID, and `saveContact`
 * overwrites `avatarImageData` (null included). The production Room seed adapter deliberately preserves
 * compatible existing conversations instead. The reference replay saves parents and messages separately,
 * then reapplies preview and sidecar fields; a durable adapter can instead insert each new parent and its
 * complete fixture children atomically. `saveMessageRepeat` requires the parent message.
 */
interface SimulatorSeedStore {
    suspend fun saveDevice(dto: DeviceDTO)
    suspend fun fetchContact(key: EntityKey): ContactDTO?
    suspend fun saveContact(dto: ContactDTO)

    /**
     * The default retains Swift's avatar-only replay for reference-compatible stores. A durable adapter can
     * override this with one atomic seed operation so a stale avatar read cannot race a delete or replacement.
     */
    suspend fun seedContact(dto: ContactDTO) {
        val avatar = fetchContact(EntityKey(dto.radioId, dto.id))?.avatarImageData
        saveContact(if (avatar != null) dto.withAvatar(avatar) else dto)
    }

    /**
     * The default executes the reference replay unchanged without building the durable fixtures first.
     * A durable adapter may validate the declared identities and build each absent parent's children inside
     * its transaction. Existing conversations are preserved without constructing their fixture children.
     */
    suspend fun seedConversations(
        buildPlan: () -> SimulatorDeferredFixturePlan,
        replayReference: suspend () -> Unit,
    ): SimulatorFixtureSeedResult {
        replayReference()
        return SimulatorFixtureSeedResult.ReferenceReplay
    }

    suspend fun saveChannel(dto: ChannelDTO)
    suspend fun saveMessage(dto: MessageDTO)
    suspend fun updateMessageLinkPreview(
        key: EntityKey, url: String?, title: String?, imageData: Bytes?, iconData: Bytes?, fetched: Boolean,
    )
    suspend fun saveReaction(dto: ReactionDTO)
    suspend fun updateMessageReactionSummary(message: EntityKey, summary: String?)
    suspend fun saveMessageRepeat(radioId: RadioId, dto: MessageRepeatDTO)
    suspend fun fetchRxLogEntries(radioId: RadioId, limit: Long = 500): SnapshotList<RxLogEntryDTO>
    suspend fun saveRxLogEntry(dto: RxLogEntryDTO)
    suspend fun fetchLatestNodeStatusSnapshot(nodePublicKey: Bytes): NodeStatusSnapshotDTO?
    suspend fun existingNodeStatusSnapshotKeys(): SnapshotSet<NodeStatusSnapshotKey>
    suspend fun batchInsertNodeStatusSnapshots(
        dtos: SnapshotList<NodeStatusSnapshotDTO>, existingKeys: SnapshotSet<NodeStatusSnapshotKey>,
    ): NodeStatusSnapshotInsertResult
}

sealed interface SimulatorFixtureParent {
    data class Direct(val contact: ContactDTO) : SimulatorFixtureParent
    data class Channel(val channel: ChannelDTO) : SimulatorFixtureParent
}

data class SimulatorFixtureConversation(
    val parent: SimulatorFixtureParent,
    val messages: SnapshotList<MessageDTO>,
    val reactions: SnapshotList<ReactionDTO>,
    val repeats: SnapshotList<MessageRepeatDTO>,
)

sealed interface SimulatorFixtureSeedResult {
    data object ReferenceReplay : SimulatorFixtureSeedResult
    data class Durable(val created: Int, val preservedExisting: Int) : SimulatorFixtureSeedResult
}

/**
 * Swift `nodeStatusSnapshotKey(nodePublicKey:timestamp:)`: the node key plus the timestamp truncated to whole
 * milliseconds (`Int(timeIntervalSince1970 * 1000)`), deliberately excluding the row id so re-import is
 * idempotent. `core:data` keys its backup restore the same way (`snapshotBackupKey`).
 */
data class NodeStatusSnapshotKey(val nodePublicKey: Bytes, val milliseconds: Long) {
    companion object {
        fun of(nodePublicKey: Bytes, timestamp: Instant): NodeStatusSnapshotKey =
            NodeStatusSnapshotKey(nodePublicKey, swiftMilliseconds(timestamp))

        fun of(dto: NodeStatusSnapshotDTO): NodeStatusSnapshotKey = of(dto.nodePublicKey, dto.timestamp)

        /** Truncation toward zero, as Swift's `Int(Double)` conversion does. */
        private fun swiftMilliseconds(timestamp: Instant): Long {
            val floorMillis = Math.addExact(Math.multiplyExact(timestamp.epochSecond, 1000L), timestamp.nano / 1_000_000L)
            val hasSubMillis = timestamp.nano % 1_000_000 != 0
            return if (timestamp.epochSecond < 0 && hasSubMillis) floorMillis + 1 else floorMillis
        }
    }
}

/** Swift `batchInsertNodeStatusSnapshots` result tuple. */
data class NodeStatusSnapshotInsertResult(val inserted: Long, val skipped: Long)
