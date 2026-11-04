// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/MessagePersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/HeardRepeatPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/ReactionPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Messages.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+PendingSends.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+FailedSends.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.UUID

data class PendingDirectMessageIntent(
    val messageID: UUID,
    val radioId: RadioId,
    val contactID: UUID,
    val text: String,
    val textType: TextType,
    val replyToID: UUID?,
)

interface PendingSendPersisting {
    suspend fun upsertPendingSend(dto: PendingSendDTO)
    suspend fun insertPendingSendAssigningSequence(dto: PendingSendDTO): Long
    suspend fun replacePendingSendForRetry(messageID: UUID, dto: PendingSendDTO): Long
    suspend fun fetchPendingSends(radioId: RadioId): SnapshotList<PendingSendDTO>
    suspend fun fetchPendingSendsForMessage(key: EntityKey): SnapshotList<PendingSendDTO>
    suspend fun deletePendingSend(key: EntityKey)
    suspend fun deletePendingSendsForMessage(key: EntityKey)
    suspend fun hasPendingSend(key: EntityKey): Boolean
    suspend fun incrementPendingSendAttemptCount(key: EntityKey): Long?
    suspend fun purgeOrphanPendingSends(): Long
    suspend fun purgeLegacyAttemptCountRows(): Long
}

interface MessagePersisting : PendingSendPersisting {
    /**
     * Atomically inserts the caller's outgoing DM or returns its matching current row, without resetting
     * delivery state. Timestamp and createdAt apply only on insert; they are not replay identity fields.
     * Conflicting intent or an ID occupied on another radio throws PersistenceStoreException(InvalidData).
     * The durable action/dispatch guard must separately bind the contact's public key: MessageDTO stores
     * only contactID. This operation neither transmits nor guarantees exactly-once transmission.
     */
    suspend fun createOrReconcilePendingDirectMessage(
        intent: PendingDirectMessageIntent, timestamp: UInt, createdAt: Instant,
    ): MessageDTO
    suspend fun isDuplicateMessage(deduplicationKey: String, radioId: RadioId): Boolean
    suspend fun fetchMessage(deduplicationKey: String, radioId: RadioId): MessageDTO?
    suspend fun saveMessage(dto: MessageDTO)
    suspend fun fetchMessage(key: EntityKey): MessageDTO?
    suspend fun fetchMessages(contact: EntityKey, limit: Long = 50, offset: Long = 0): SnapshotList<MessageDTO>
    suspend fun newestUnreadIncomingMessage(contact: EntityKey): MessageDTO?
    suspend fun fetchMessages(radioId: RadioId, channelIndex: UByte, limit: Long = 50, offset: Long = 0): SnapshotList<MessageDTO>
    suspend fun fetchMessageWindow(contact: EntityKey, anchorSortDate: Instant?, floorLimit: Long): MessageWindow
    suspend fun fetchMessageWindow(radioId: RadioId, channelIndex: UByte, anchorSortDate: Instant?, floorLimit: Long): MessageWindow
    suspend fun fetchLastMessages(contacts: SnapshotList<EntityKey>, limit: Long): SnapshotMap<EntityKey, SnapshotList<MessageDTO>>
    suspend fun fetchLastChannelMessages(channels: SnapshotList<ChannelQuery>, limit: Long): SnapshotMap<EntityKey, SnapshotList<MessageDTO>>
    suspend fun findChannelMessageForReaction(
        radioId: RadioId, channelIndex: UByte, parsedReaction: ParsedReaction, localNodeName: String?,
        timestampWindow: ClosedRange<UInt>, limit: Long,
    ): MessageDTO?
    suspend fun fetchChannelMessageCandidates(
        radioId: RadioId, channelIndex: UByte, timestampWindow: ClosedRange<UInt>, limit: Long,
    ): SnapshotList<MessageDTO>
    suspend fun fetchDMMessageCandidates(contact: EntityKey, timestampWindow: ClosedRange<UInt>, limit: Long): SnapshotList<MessageDTO>
    suspend fun findDMMessageForReaction(contact: EntityKey, messageHash: String, timestampWindow: ClosedRange<UInt>, limit: Long): MessageDTO?
    suspend fun updateMessageStatus(key: EntityKey, status: MessageStatus)
    suspend fun updateMessageStatusUnlessDelivered(key: EntityKey, status: MessageStatus): Boolean
    suspend fun clearRetryingToSent(key: EntityKey): Boolean
    suspend fun hasOutgoingSentDM(radioId: RadioId, ackCode: UInt): Boolean
    suspend fun updateMessageAck(key: EntityKey, ackCode: UInt, status: MessageStatus, roundTripTime: UInt? = null)
    suspend fun updateMessageRetryStatus(key: EntityKey, status: MessageStatus, retryAttempt: Long, maxRetryAttempts: Long)
    suspend fun updateMessageTimestamp(key: EntityKey, timestamp: UInt)
    suspend fun updateMessageHeardRepeats(key: EntityKey, heardRepeats: Long)
    suspend fun markMessageAsRead(key: EntityKey)
    suspend fun updateMessageLinkPreview(key: EntityKey, url: String?, title: String?, imageData: Bytes?, iconData: Bytes?, fetched: Boolean)
    suspend fun deleteMessage(key: EntityKey)
    suspend fun countPendingMessages(radioId: RadioId): Long
}

interface HeardRepeatPersisting {
    suspend fun findSentChannelMessage(radioId: RadioId, channelIndex: UByte, timestamp: UInt, text: String): MessageDTO?
    suspend fun fetchMessage(deduplicationKey: String, radioId: RadioId): MessageDTO?
    suspend fun saveMessageRepeat(radioId: RadioId, dto: MessageRepeatDTO)
    suspend fun fetchMessageRepeats(message: EntityKey): SnapshotList<MessageRepeatDTO>
    suspend fun deleteMessageRepeats(message: EntityKey)
    suspend fun messageRepeatExists(rxLogEntry: EntityKey): Boolean
    suspend fun incrementMessageHeardRepeats(key: EntityKey): Long
    suspend fun adoptIncomingPathIfUnknown(key: EntityKey, pathNodes: Bytes, pathLength: UByte): Boolean
    suspend fun incrementMessageSendCount(key: EntityKey): Long
}

interface ReactionPersisting {
    suspend fun fetchReactions(message: EntityKey, limit: Long = 100): SnapshotList<ReactionDTO>
    suspend fun saveReaction(dto: ReactionDTO)
    suspend fun reactionExists(message: EntityKey, senderName: String, emoji: String): Boolean
    suspend fun updateMessageReactionSummary(message: EntityKey, summary: String?)
    suspend fun deleteReactionsForMessage(message: EntityKey)
}

interface FailedSendPersisting {
    suspend fun fetchFailedSendConversationKeys(radioId: RadioId): FailedSendConversationKeys
    suspend fun markFailedSendsSeen(contact: EntityKey)
    suspend fun markFailedSendsSeen(radioId: RadioId, channelIndex: UByte)
    suspend fun markRoomFailedSendsSeen(session: EntityKey)
}
