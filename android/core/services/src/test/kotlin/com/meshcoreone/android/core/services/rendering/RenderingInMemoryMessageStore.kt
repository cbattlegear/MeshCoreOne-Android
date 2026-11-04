// AndroidOnly: WP-213 in-memory MessagePersisting fake standing in for the Swift in-memory PersistenceStore.
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.contracts.domain.ChannelQuery
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.MessagePersisting
import com.meshcoreone.android.core.contracts.domain.MessageWindow
import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.contracts.domain.PendingDirectMessageIntent
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.PendingSendDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.SnapshotMap
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred

/**
 * Keeps saved messages in memory and implements the reads/writes the coordinator suites exercise. The
 * window query mirrors core:data (`limit = max(floor, rows at/after anchor)`, newest `limit` rows,
 * oldest first, `hasMore` when older rows remain). Every other contract method fails loudly.
 */
internal class RenderingInMemoryMessageStore : MessagePersisting {
    override suspend fun createOrReconcilePendingDirectMessage(
        intent: PendingDirectMessageIntent, timestamp: UInt, createdAt: Instant,
    ): MessageDTO = error("Direct-message creation is not a rendering test operation")

    private val lock = Any()
    private var rows: Map<EntityKey, MessageDTO> = emptyMap()

    /** Every `fetchMessage(EntityKey)` call, in order. */
    @Volatile
    var fetchedKeys: List<EntityKey> = emptyList()
        private set

    /** Thrown by `fetchMessage(EntityKey)` for these ids (simulated store errors). */
    @Volatile
    var failingFetchIDs: Set<UUID> = emptySet()

    /** Window queries served. */
    @Volatile
    var windowQueries: Int = 0
        private set

    /** When set, `fetchMessage(EntityKey)` suspends on it (after recording the key) so a test can interleave. */
    @Volatile
    var fetchGate: CompletableDeferred<Unit>? = null

    /** Completed when a `fetchMessage(EntityKey)` call reaches [fetchGate]. */
    @Volatile
    var fetchReachedGate: CompletableDeferred<Unit> = CompletableDeferred()

    override suspend fun saveMessage(dto: MessageDTO) {
        synchronized(lock) { rows = rows + (EntityKey(dto.radioId, dto.id) to dto) }
    }

    override suspend fun fetchMessage(key: EntityKey): MessageDTO? {
        synchronized(lock) { fetchedKeys = fetchedKeys + key }
        fetchGate?.let { gate ->
            fetchReachedGate.complete(Unit)
            gate.await()
        }
        if (key.id in failingFetchIDs) throw IllegalStateException("simulated fetch failure for ${key.id}")
        return synchronized(lock) { rows[key] }
    }

    override suspend fun updateMessageStatus(key: EntityKey, status: MessageStatus) {
        synchronized(lock) { rows[key]?.let { rows = rows + (key to it.copy(status = status)) } }
    }

    override suspend fun deleteMessage(key: EntityKey) {
        synchronized(lock) { rows = rows - key }
    }

    override suspend fun fetchMessages(contact: EntityKey, limit: Long, offset: Long): SnapshotList<MessageDTO> =
        newestFirst { it.radioId == contact.radioId && it.contactID == contact.id && it.channelIndex == null }
            .drop(offset.toInt()).take(limit.toInt()).asReversed().snapshot()

    override suspend fun fetchMessages(radioId: RadioId, channelIndex: UByte, limit: Long, offset: Long): SnapshotList<MessageDTO> =
        newestFirst { it.radioId == radioId && it.channelIndex == channelIndex }
            .drop(offset.toInt()).take(limit.toInt()).asReversed().snapshot()

    override suspend fun fetchMessageWindow(contact: EntityKey, anchorSortDate: java.time.Instant?, floorLimit: Long): MessageWindow =
        window(anchorSortDate, floorLimit) { it.radioId == contact.radioId && it.contactID == contact.id && it.channelIndex == null }

    override suspend fun fetchMessageWindow(
        radioId: RadioId,
        channelIndex: UByte,
        anchorSortDate: java.time.Instant?,
        floorLimit: Long,
    ): MessageWindow = window(anchorSortDate, floorLimit) { it.radioId == radioId && it.channelIndex == channelIndex }

    private fun window(anchor: java.time.Instant?, floorLimit: Long, matches: (MessageDTO) -> Boolean): MessageWindow {
        synchronized(lock) { windowQueries += 1 }
        val newest = newestFirst(matches)
        val atAnchor = anchor?.let { a -> newest.count { it.sortDate >= a }.toLong() } ?: 0L
        val limit = maxOf(floorLimit, atAnchor).toInt()
        return MessageWindow(newest.take(limit).asReversed().snapshot(), newest.size > limit)
    }

    private fun newestFirst(matches: (MessageDTO) -> Boolean): List<MessageDTO> =
        synchronized(lock) { rows.values.filter(matches) }.sortedWith(compareByDescending<MessageDTO> { it.sortDate }.thenByDescending { it.createdAt })

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not used by WP-213 suites")

    override suspend fun isDuplicateMessage(deduplicationKey: String, radioId: RadioId): Boolean = unsupported()
    override suspend fun fetchMessage(deduplicationKey: String, radioId: RadioId): MessageDTO? = unsupported()
    override suspend fun newestUnreadIncomingMessage(contact: EntityKey): MessageDTO? = unsupported()
    override suspend fun fetchLastMessages(contacts: SnapshotList<EntityKey>, limit: Long): SnapshotMap<EntityKey, SnapshotList<MessageDTO>> = unsupported()
    override suspend fun fetchLastChannelMessages(channels: SnapshotList<ChannelQuery>, limit: Long): SnapshotMap<EntityKey, SnapshotList<MessageDTO>> = unsupported()
    override suspend fun findChannelMessageForReaction(
        radioId: RadioId, channelIndex: UByte, parsedReaction: ParsedReaction, localNodeName: String?,
        timestampWindow: ClosedRange<UInt>, limit: Long,
    ): MessageDTO? = unsupported()
    override suspend fun fetchChannelMessageCandidates(
        radioId: RadioId, channelIndex: UByte, timestampWindow: ClosedRange<UInt>, limit: Long,
    ): SnapshotList<MessageDTO> = unsupported()
    override suspend fun fetchDMMessageCandidates(contact: EntityKey, timestampWindow: ClosedRange<UInt>, limit: Long): SnapshotList<MessageDTO> = unsupported()
    override suspend fun findDMMessageForReaction(contact: EntityKey, messageHash: String, timestampWindow: ClosedRange<UInt>, limit: Long): MessageDTO? = unsupported()
    override suspend fun updateMessageStatusUnlessDelivered(key: EntityKey, status: MessageStatus): Boolean = unsupported()
    override suspend fun clearRetryingToSent(key: EntityKey): Boolean = unsupported()
    override suspend fun hasOutgoingSentDM(radioId: RadioId, ackCode: UInt): Boolean = unsupported()
    override suspend fun updateMessageAck(key: EntityKey, ackCode: UInt, status: MessageStatus, roundTripTime: UInt?) = unsupported()
    override suspend fun updateMessageRetryStatus(key: EntityKey, status: MessageStatus, retryAttempt: Long, maxRetryAttempts: Long) = unsupported()
    override suspend fun updateMessageTimestamp(key: EntityKey, timestamp: UInt) = unsupported()
    override suspend fun updateMessageHeardRepeats(key: EntityKey, heardRepeats: Long) = unsupported()
    override suspend fun markMessageAsRead(key: EntityKey) = unsupported()
    override suspend fun updateMessageLinkPreview(key: EntityKey, url: String?, title: String?, imageData: Bytes?, iconData: Bytes?, fetched: Boolean) = unsupported()
    override suspend fun countPendingMessages(radioId: RadioId): Long = unsupported()
    override suspend fun upsertPendingSend(dto: PendingSendDTO) = unsupported()
    override suspend fun insertPendingSendAssigningSequence(dto: PendingSendDTO): Long = unsupported()
    override suspend fun replacePendingSendForRetry(messageID: UUID, dto: PendingSendDTO): Long = unsupported()
    override suspend fun fetchPendingSends(radioId: RadioId): SnapshotList<PendingSendDTO> = unsupported()
    override suspend fun fetchPendingSendsForMessage(key: EntityKey): SnapshotList<PendingSendDTO> = unsupported()
    override suspend fun deletePendingSend(key: EntityKey) = unsupported()
    override suspend fun deletePendingSendsForMessage(key: EntityKey) = unsupported()
    override suspend fun hasPendingSend(key: EntityKey): Boolean = unsupported()
    override suspend fun incrementPendingSendAttemptCount(key: EntityKey): Long? = unsupported()
    override suspend fun purgeOrphanPendingSends(): Long = unsupported()
    override suspend fun purgeLegacyAttemptCountRows(): Long = unsupported()
}
