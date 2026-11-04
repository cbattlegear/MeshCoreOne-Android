// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Messages.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+PendingSends.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+FailedSends.swift@db14559b39d32322b06477c6ae676112f583db50
// Partition-safe SQL ordering, conditional writes and failure-key projections.
package com.meshcoreone.android.core.database

import androidx.room.Dao
import androidx.room.Query
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import kotlinx.coroutines.flow.Flow

private const val MESSAGE_ORDER = "sortDate_seconds DESC, sortDate_nanos DESC, timestamp DESC, createdAt_seconds DESC, createdAt_nanos DESC"
private const val MESSAGE_WITHOUT_BLOBS = """
SELECT radioId, id, contactID, channelIndex, text, timestamp, createdAt_seconds, createdAt_nanos,
sortDate_seconds, sortDate_nanos, directionRawValue, statusRawValue, textTypeRawValue, ackCode,
pathLength, snr, pathNodes, senderKeyPrefix, senderNodeName, isRead, replyToID, roundTripTime,
heardRepeats, sendCount, retryAttempt, maxRetryAttempts, deduplicationKey, linkPreviewURL, linkPreviewTitle,
NULL AS linkPreviewImageData, NULL AS linkPreviewIconData, 0 AS linkPreviewFetched,
containsSelfMention, mentionSeen, failureSeen, timestampCorrected, senderTimestamp, reactionSummary,
routeTypeRawValue, regionScope, regionScopeMatches FROM messages
"""

@Dao
interface MessageDao : RowWriter<MessageEntity> {
    // Reject caller-ID reuse on a different radio without exposing that radio's DTO.
    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun forIdentity(id: UUID): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE radioId = :radioId AND id = :id") suspend fun byId(radioId: UUID, id: UUID): MessageEntity?
    @Query("SELECT * FROM messages WHERE radioId = :radioId AND deduplicationKey = :key")
    suspend fun forDeduplicationKey(radioId: UUID, key: String): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE radioId = :radioId AND contactID = :contactID ORDER BY " + MESSAGE_ORDER + " LIMIT :limit OFFSET :offset")
    suspend fun newestForContact(radioId: UUID, contactID: UUID, limit: Long, offset: Long = 0): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE radioId = :radioId AND channelIndex = :index ORDER BY " + MESSAGE_ORDER + " LIMIT :limit OFFSET :offset")
    suspend fun newestForChannel(radioId: UUID, index: Long, limit: Long, offset: Long = 0): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE radioId = :radioId AND channelIndex = :index AND channelOccupantID = :occupantID ORDER BY " + MESSAGE_ORDER + " LIMIT :limit OFFSET :offset")
    suspend fun newestForChannelOccupant(radioId: UUID, index: Long, occupantID: UUID, limit: Long, offset: Long = 0): List<MessageEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE radioId = :radioId AND channelIndex = :index AND channelOccupantID IS NULL)")
    suspend fun hasUnboundChannelHistory(radioId: UUID, index: Long): Boolean
    @Query("SELECT COUNT(*) FROM messages WHERE radioId = :radioId AND channelIndex = :index AND channelOccupantID = :occupantID")
    suspend fun countForChannelOccupant(radioId: UUID, index: Long, occupantID: UUID): Long
    @Query("SELECT COUNT(*) FROM messages WHERE radioId = :radioId AND channelIndex = :index AND channelOccupantID = :occupantID AND (sortDate_seconds > :seconds OR (sortDate_seconds = :seconds AND sortDate_nanos >= :nanos))")
    suspend fun countChannelOccupantAtOrAfter(radioId: UUID, index: Long, occupantID: UUID, seconds: Long, nanos: Int): Long
    @Query("SELECT * FROM messages WHERE radioId = :radioId AND channelIndex = :index AND channelOccupantID = :occupantID AND timestamp BETWEEN :fromTimestamp AND :toTimestamp ORDER BY createdAt_seconds DESC, createdAt_nanos DESC, timestamp DESC LIMIT :limit")
    suspend fun channelOccupantCandidates(radioId: UUID, index: Long, occupantID: UUID, fromTimestamp: Long, toTimestamp: Long, limit: Long): List<MessageEntity>
    @Query("SELECT COUNT(*) FROM messages WHERE radioId = :radioId AND channelIndex = :index AND channelOccupantID = :occupantID AND statusRawValue = 4 AND directionRawValue = 1 AND failureSeen = 0")
    suspend fun countUnseenChannelOccupantFailures(radioId: UUID, index: Long, occupantID: UUID): Long
    @Query("SELECT * FROM messages WHERE radioId = :radioId AND contactID = :contactID ORDER BY " + MESSAGE_ORDER)
    fun observeContact(radioId: UUID, contactID: UUID): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE radioId = :radioId AND contactID IS NULL AND channelIndex IS NULL")
    suspend fun orphanedDirectMessages(radioId: UUID): List<MessageEntity>
    @Query("SELECT COUNT(*) FROM messages WHERE radioId = :radioId AND contactID = :contactID")
    suspend fun countForContact(radioId: UUID, contactID: UUID): Long
    @Query("SELECT COUNT(*) FROM messages WHERE radioId = :radioId AND channelIndex = :index")
    suspend fun countForChannel(radioId: UUID, index: Long): Long
    @Query("SELECT COUNT(*) FROM messages WHERE radioId = :radioId AND contactID = :contactID AND (sortDate_seconds > :seconds OR (sortDate_seconds = :seconds AND sortDate_nanos >= :nanos))")
    suspend fun countContactAtOrAfter(radioId: UUID, contactID: UUID, seconds: Long, nanos: Int): Long
    @Query("SELECT COUNT(*) FROM messages WHERE radioId = :radioId AND channelIndex = :index AND (sortDate_seconds > :seconds OR (sortDate_seconds = :seconds AND sortDate_nanos >= :nanos))")
    suspend fun countChannelAtOrAfter(radioId: UUID, index: Long, seconds: Long, nanos: Int): Long
    @Query("SELECT * FROM messages WHERE radioId = :radioId AND contactID = :contactID AND directionRawValue = 0 AND isRead = 0 ORDER BY " + MESSAGE_ORDER + " LIMIT 1")
    suspend fun newestUnreadIncoming(radioId: UUID, contactID: UUID): MessageEntity?
    @Query("SELECT * FROM messages WHERE radioId = :radioId AND channelIndex = :index AND timestamp BETWEEN :fromTimestamp AND :toTimestamp ORDER BY createdAt_seconds DESC, createdAt_nanos DESC, timestamp DESC LIMIT :limit")
    suspend fun channelCandidates(radioId: UUID, index: Long, fromTimestamp: Long, toTimestamp: Long, limit: Long): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE radioId = :radioId AND contactID = :contactID AND timestamp BETWEEN :fromTimestamp AND :toTimestamp ORDER BY createdAt_seconds DESC, createdAt_nanos DESC, timestamp DESC LIMIT :limit")
    suspend fun directCandidates(radioId: UUID, contactID: UUID, fromTimestamp: Long, toTimestamp: Long, limit: Long): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE radioId = :radioId AND channelIndex = :index AND timestamp = :timestamp AND text = :text AND directionRawValue = 1 ORDER BY createdAt_seconds DESC, createdAt_nanos DESC LIMIT 1")
    suspend fun sentChannelMessage(radioId: UUID, index: Long, timestamp: Long, text: String): MessageEntity?
    @Query("UPDATE messages SET failureSeen = CASE WHEN :status = 4 AND statusRawValue != 4 THEN 0 ELSE failureSeen END, statusRawValue = :status WHERE radioId = :radioId AND id = :id")
    suspend fun setStatus(radioId: UUID, id: UUID, status: Long): Int
    @Query("UPDATE messages SET failureSeen = CASE WHEN :status = 4 AND statusRawValue != 4 THEN 0 ELSE failureSeen END, statusRawValue = :status WHERE radioId = :radioId AND id = :id AND statusRawValue != 3")
    suspend fun setStatusUnlessDelivered(radioId: UUID, id: UUID, status: Long): Int
    @Query("UPDATE messages SET statusRawValue = 2 WHERE radioId = :radioId AND id = :id AND statusRawValue NOT IN (3, 4)")
    suspend fun clearRetryingToSent(radioId: UUID, id: UUID): Int
    @Query("UPDATE messages SET statusRawValue = :status, retryAttempt = :attempt, maxRetryAttempts = :maximum WHERE radioId = :radioId AND id = :id AND statusRawValue NOT IN (3, 4)")
    suspend fun setRetryStatus(radioId: UUID, id: UUID, status: Long, attempt: Long, maximum: Long): Int
    @Query("UPDATE messages SET ackCode = :ackCode, statusRawValue = :status, roundTripTime = :roundTripTime WHERE radioId = :radioId AND id = :id AND (statusRawValue NOT IN (3, 4) OR statusRawValue = :status)")
    suspend fun setAck(radioId: UUID, id: UUID, ackCode: Long, status: Long, roundTripTime: Long?): Int
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE radioId = :radioId AND ackCode = :ackCode AND statusRawValue = 2 AND directionRawValue = 1 AND channelIndex IS NULL)")
    suspend fun hasOutgoingSentDM(radioId: UUID, ackCode: Long): Boolean
    @Query("UPDATE messages SET pathNodes = :nodes, pathLength = :length WHERE radioId = :radioId AND id = :id AND pathNodes IS NULL")
    suspend fun adoptPathIfUnknown(radioId: UUID, id: UUID, nodes: Bytes, length: Long): Int
    @Query("SELECT id FROM messages WHERE radioId = :radioId AND contactID = :contactID AND containsSelfMention = 1 AND mentionSeen = 0 ORDER BY timestamp")
    suspend fun unseenContactMentionIDs(radioId: UUID, contactID: UUID): List<UUID>
    @Query("SELECT id FROM messages WHERE radioId = :radioId AND channelIndex = :index AND containsSelfMention = 1 AND mentionSeen = 0 ORDER BY timestamp")
    suspend fun unseenChannelMentionIDs(radioId: UUID, index: Long): List<UUID>
    @Query("SELECT DISTINCT contactID FROM messages WHERE radioId = :radioId AND contactID IS NOT NULL AND statusRawValue = 4 AND directionRawValue = 1 AND failureSeen = 0")
    suspend fun failedContactIDs(radioId: UUID): List<UUID>
    @Query("SELECT DISTINCT c.id FROM channels c JOIN messages m ON c.radioId = m.radioId AND c.`index` = m.channelIndex WHERE m.radioId = :radioId AND m.statusRawValue = 4 AND m.directionRawValue = 1 AND m.failureSeen = 0")
    suspend fun failedChannelIDs(radioId: UUID): List<UUID>
    @Query("UPDATE messages SET failureSeen = 1 WHERE radioId = :radioId AND contactID = :contactID AND statusRawValue = 4 AND directionRawValue = 1 AND failureSeen = 0")
    suspend fun markContactFailuresSeen(radioId: UUID, contactID: UUID): Int
    @Query("UPDATE messages SET failureSeen = 1 WHERE radioId = :radioId AND channelIndex = :index AND statusRawValue = 4 AND directionRawValue = 1 AND failureSeen = 0")
    suspend fun markChannelFailuresSeen(radioId: UUID, index: Long): Int
    @Query("SELECT COUNT(*) FROM messages WHERE radioId = :radioId AND statusRawValue IN (0, 1)") suspend fun countPending(radioId: UUID): Long
    @Query("DELETE FROM messages WHERE radioId = :radioId AND id = :id") suspend fun delete(radioId: UUID, id: UUID): Int
    @Query("DELETE FROM messages WHERE radioId = :radioId AND contactID = :contactID") suspend fun deleteContactMessages(radioId: UUID, contactID: UUID): Int
    @Query("DELETE FROM messages WHERE radioId = :radioId AND channelIndex = :index") suspend fun deleteChannelMessages(radioId: UUID, index: Long): Int
    @Query("DELETE FROM messages WHERE radioId = :radioId") suspend fun clearRadio(radioId: UUID): Int
    @Query(MESSAGE_WITHOUT_BLOBS + " WHERE radioId = :radioId ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun backupPageWithoutPreviewBlobs(radioId: UUID, limit: Long, offset: Long): List<MessageEntity>
    @Query("SELECT DISTINCT radioId FROM messages ORDER BY radioId") suspend fun backupRadioIds(): List<UUID>
}

@Dao
interface MessageRepeatDao : RowWriter<MessageRepeatEntity> {
    @Query("SELECT * FROM message_repeats") suspend fun backupAll(): List<MessageRepeatEntity>
    @Query("SELECT * FROM message_repeats WHERE radioId = :radioId AND messageID = :messageID ORDER BY receivedAt_seconds, receivedAt_nanos")
    suspend fun forMessage(radioId: UUID, messageID: UUID): List<MessageRepeatEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM message_repeats WHERE radioId = :radioId AND rxLogEntryID = :rxID)")
    suspend fun existsForRxLog(radioId: UUID, rxID: UUID): Boolean
    @Query("DELETE FROM message_repeats WHERE radioId = :radioId AND messageID = :messageID")
    suspend fun deleteForMessage(radioId: UUID, messageID: UUID): Int
    @Query("DELETE FROM message_repeats WHERE radioId = :radioId") suspend fun clearRadio(radioId: UUID): Int
}

@Dao
interface ReactionDao : RowWriter<ReactionEntity> {
    @Query("SELECT * FROM reactions") suspend fun backupAll(): List<ReactionEntity>
    @Query("SELECT * FROM reactions WHERE radioId = :radioId AND messageID = :messageID ORDER BY receivedAt_seconds DESC, receivedAt_nanos DESC LIMIT :limit")
    suspend fun forMessage(radioId: UUID, messageID: UUID, limit: Long): List<ReactionEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM reactions WHERE radioId = :radioId AND messageID = :messageID AND senderName = :name AND emoji = :emoji)")
    suspend fun exists(radioId: UUID, messageID: UUID, name: String, emoji: String): Boolean
    @Query("DELETE FROM reactions WHERE radioId = :radioId AND messageID = :messageID") suspend fun deleteForMessage(radioId: UUID, messageID: UUID): Int
    @Query("DELETE FROM reactions WHERE radioId = :radioId AND contactID = :contactID") suspend fun deleteForContact(radioId: UUID, contactID: UUID): Int
    @Query("DELETE FROM reactions WHERE radioId = :radioId") suspend fun clearRadio(radioId: UUID): Int
}

@Dao
interface PendingSendDao : RowWriter<PendingSendEntity> {
    @Query("SELECT * FROM pending_sends WHERE radioId = :radioId AND id = :id")
    suspend fun byId(radioId: UUID, id: UUID): PendingSendEntity?
    @Query("UPDATE pending_sends SET channelDispatchStateRawValue = 1, channelAttemptID = :attemptID WHERE radioId = :radioId AND id = :id AND messageID = :messageID AND kindRawValue = 1 AND channelOccupantID = :occupantID AND channelDispatchStateRawValue = 0 AND channelAttemptID IS NULL")
    suspend fun beginChannelAttempt(radioId: UUID, id: UUID, messageID: UUID, occupantID: UUID, attemptID: UUID): Int
    @Query("UPDATE pending_sends SET channelDispatchStateRawValue = :next, channelAttemptID = :nextAttemptID WHERE radioId = :radioId AND id = :id AND messageID = :messageID AND kindRawValue = 1 AND channelOccupantID = :occupantID AND channelDispatchStateRawValue = 1 AND channelAttemptID = :attemptID")
    suspend fun settleChannelAttempt(radioId: UUID, id: UUID, messageID: UUID, occupantID: UUID, attemptID: UUID, next: Long, nextAttemptID: UUID?): Int
    @Query("DELETE FROM pending_sends WHERE radioId = :radioId AND id = :id AND messageID = :messageID AND kindRawValue = 1 AND channelDispatchStateRawValue = 2 AND channelAttemptID = :attemptID")
    suspend fun deleteAcceptedChannelAttempt(radioId: UUID, id: UUID, messageID: UUID, attemptID: UUID): Int
    @Query("SELECT * FROM pending_sends WHERE radioId = :radioId ORDER BY sequence") suspend fun forRadio(radioId: UUID): List<PendingSendEntity>
    @Query("SELECT * FROM pending_sends WHERE radioId = :radioId AND messageID = :messageID ORDER BY sequence")
    suspend fun forMessage(radioId: UUID, messageID: UUID): List<PendingSendEntity>
    @Query("SELECT MAX(sequence) FROM pending_sends WHERE radioId = :radioId") suspend fun maximumSequence(radioId: UUID): Long?
    @Query("UPDATE pending_sends SET attemptCount = :count WHERE radioId = :radioId AND id = :id")
    suspend fun setAttemptCount(radioId: UUID, id: UUID, count: Long): Int
    @Query("DELETE FROM pending_sends WHERE radioId = :radioId AND id = :id") suspend fun delete(radioId: UUID, id: UUID): Int
    @Query("DELETE FROM pending_sends WHERE radioId = :radioId AND messageID = :messageID") suspend fun deleteForMessage(radioId: UUID, messageID: UUID): Int
    @Query("DELETE FROM pending_sends WHERE radioId = :radioId") suspend fun clearRadio(radioId: UUID): Int
    @Query("SELECT * FROM pending_sends p WHERE NOT EXISTS(SELECT 1 FROM devices d WHERE d.radioId = p.radioId)")
    suspend fun orphanedRadioRows(): List<PendingSendEntity>
    @Query("SELECT * FROM pending_sends WHERE attemptCount IS NULL") suspend fun legacyAttemptRows(): List<PendingSendEntity>
}
