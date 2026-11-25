// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Rooms.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+FailedSends.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Scoped session/message SQL without invented authentication or cascades.
package com.meshcoreone.android.core.database

import androidx.room.Dao
import androidx.room.Query
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID

@Dao
interface RemoteNodeSessionDao : RowWriter<RemoteNodeSessionEntity> {
    @Query("SELECT * FROM remote_node_sessions") suspend fun backupAll(): List<RemoteNodeSessionEntity>
    @Query("SELECT * FROM remote_node_sessions WHERE radioId = :radioId")
    suspend fun forRadio(radioId: UUID): List<RemoteNodeSessionEntity>
    @Query("SELECT * FROM remote_node_sessions WHERE radioId = :radioId AND id = :id")
    suspend fun byId(radioId: UUID, id: UUID): RemoteNodeSessionEntity?
    @Query("SELECT * FROM remote_node_sessions WHERE radioId = :radioId AND publicKey = :publicKey")
    suspend fun forPublicKey(radioId: UUID, publicKey: Bytes): List<RemoteNodeSessionEntity>
    @Query("SELECT * FROM remote_node_sessions WHERE radioId = :radioId AND substr(publicKey, 1, :length) = :prefix")
    suspend fun forPrefix(radioId: UUID, prefix: Bytes, length: Int): List<RemoteNodeSessionEntity>
    @Query("SELECT * FROM remote_node_sessions WHERE radioId = :radioId AND isConnected = 1")
    suspend fun connected(radioId: UUID): List<RemoteNodeSessionEntity>
    @Query("UPDATE remote_node_sessions SET isConnected = 0 WHERE radioId = :radioId AND id = :id")
    suspend fun markDisconnected(radioId: UUID, id: UUID): Int
    @Query("UPDATE remote_node_sessions SET isConnected = 1 WHERE radioId = :radioId AND id = :id AND isConnected = 0")
    suspend fun markConnected(radioId: UUID, id: UUID): Int
    @Query("UPDATE remote_node_sessions SET isConnected = 0")
    suspend fun resetAllConnections(): Int
    @Query("SELECT COUNT(*) FROM remote_node_sessions WHERE publicKey = :key")
    suspend fun globalReferenceCount(key: Bytes): Long
    @Query("DELETE FROM remote_node_sessions WHERE radioId = :radioId AND id = :id")
    suspend fun delete(radioId: UUID, id: UUID): Int
    @Query("DELETE FROM remote_node_sessions WHERE radioId = :radioId")
    suspend fun clearRadio(radioId: UUID): Int
}

@Dao
interface RoomMessageDao : RowWriter<RoomMessageEntity> {
    @Query("SELECT * FROM room_messages") suspend fun backupAll(): List<RoomMessageEntity>
    @Query("SELECT * FROM room_messages WHERE radioId = :radioId AND id = :id")
    suspend fun byId(radioId: UUID, id: UUID): RoomMessageEntity?
    @Query("SELECT * FROM room_messages WHERE radioId = :radioId AND sessionID = :sessionID ORDER BY timestamp, createdAt_seconds, createdAt_nanos LIMIT :limit OFFSET :offset")
    suspend fun forSession(radioId: UUID, sessionID: UUID, limit: Long = -1, offset: Long = 0): List<RoomMessageEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM room_messages WHERE radioId = :radioId AND sessionID = :sessionID AND deduplicationKey = :key)")
    suspend fun duplicate(radioId: UUID, sessionID: UUID, key: String): Boolean
    @Query("""
        SELECT EXISTS(SELECT 1 FROM room_messages WHERE radioId = :radioId AND sessionID = :sessionID
          AND deduplicationKey = :key AND timestamp = :timestamp AND authorKeyPrefix = :author AND text = :text)
    """)
    suspend fun duplicateExact(
        radioId: UUID, sessionID: UUID, key: String, timestamp: Long, author: Bytes, text: String,
    ): Boolean
    @Query("UPDATE room_messages SET failureSeen = CASE WHEN :status = 4 AND statusRawValue != 4 THEN 0 ELSE failureSeen END, statusRawValue = :status, ackCode = COALESCE(:ackCode, ackCode), roundTripTime = COALESCE(:rtt, roundTripTime) WHERE radioId = :radioId AND id = :id")
    suspend fun setStatus(radioId: UUID, id: UUID, status: Long, ackCode: Long?, rtt: Long?): Int
    @Query("UPDATE room_messages SET failureSeen = CASE WHEN :status = 4 AND statusRawValue != 4 THEN 0 ELSE failureSeen END, statusRawValue = :status, retryAttempt = :attempt, maxRetryAttempts = :maximum WHERE radioId = :radioId AND id = :id")
    suspend fun setRetryStatus(radioId: UUID, id: UUID, status: Long, attempt: Long, maximum: Long): Int
    @Query("SELECT DISTINCT m.sessionID FROM room_messages m JOIN remote_node_sessions s ON m.radioId = s.radioId AND m.sessionID = s.id WHERE m.radioId = :radioId AND m.statusRawValue = 4 AND m.isFromSelf = 1 AND m.failureSeen = 0")
    suspend fun failedSessionIDs(radioId: UUID): List<UUID>
    @Query("UPDATE room_messages SET failureSeen = 1 WHERE radioId = :radioId AND sessionID = :sessionID AND statusRawValue = 4 AND isFromSelf = 1 AND failureSeen = 0")
    suspend fun markFailuresSeen(radioId: UUID, sessionID: UUID): Int
    @Query("DELETE FROM room_messages WHERE radioId = :radioId AND sessionID = :sessionID")
    suspend fun deleteForSession(radioId: UUID, sessionID: UUID): Int
    @Query("DELETE FROM room_messages WHERE radioId = :radioId")
    suspend fun clearRadio(radioId: UUID): Int
}
