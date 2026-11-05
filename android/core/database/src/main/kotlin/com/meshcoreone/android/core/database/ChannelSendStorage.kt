// AndroidOnly: WP-202 Incarnation-bound channel failure and exact pending cleanup journal.
package com.meshcoreone.android.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

@Entity(tableName = "channel_send_attempts", primaryKeys = ["radioId", "captureRequestId"])
data class ChannelSendAttemptEntity(
    val radioId: UUID,
    val captureRequestId: UUID,
    val leaseId: UUID,
    val messageId: UUID,
    val origin: String,
    val creationDigest: String?,
    val resendTimestamp: Long?,
    val messageIncarnation: String,
    val messageRevision: Long,
    val pendingId: UUID?,
    val pendingIncarnation: String?,
    val pendingRevision: Long?,
    val version: Long,
    val failed: Boolean,
    val pendingDeleted: Boolean,
    val current: Boolean,
)

@Entity(tableName = "channel_send_operations", primaryKeys = ["radioId", "captureRequestId", "requestId"])
data class ChannelSendOperationEntity(
    val radioId: UUID,
    val captureRequestId: UUID,
    val requestId: UUID,
    val kind: String,
    val inputVersion: Long,
    val outputVersion: Long,
)

@Dao
interface ChannelSendDao {
    @Query("SELECT * FROM pending_sends WHERE radioId = :radio AND id = :id")
    suspend fun pending(radio: UUID, id: UUID): PendingSendEntity?

    @Query("SELECT * FROM channel_send_attempts WHERE radioId = :radio AND captureRequestId = :request")
    suspend fun attempt(radio: UUID, request: UUID): ChannelSendAttemptEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: ChannelSendAttemptEntity)

    @Update
    suspend fun update(row: ChannelSendAttemptEntity): Int

    @Query("SELECT * FROM channel_send_operations WHERE radioId = :radio AND captureRequestId = :capture AND requestId = :request")
    suspend fun operation(radio: UUID, capture: UUID, request: UUID): ChannelSendOperationEntity?

    @Query("SELECT * FROM channel_send_operations WHERE radioId = :radio AND captureRequestId = :capture ORDER BY requestId")
    suspend fun operations(radio: UUID, capture: UUID): List<ChannelSendOperationEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: ChannelSendOperationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessage(row: MessageEntity)

    @Query("""
        UPDATE messages SET timestamp = :timestamp
        WHERE radioId = :radio AND id = :message
          AND channelIndex BETWEEN 0 AND 255 AND contactID IS NULL AND directionRawValue = 1
          AND statusRawValue != 3
          AND EXISTS(SELECT 1 FROM direct_ack_incarnations i
                     WHERE i.radioId = :radio AND i.id = :message AND i.kind = 'message'
                       AND i.incarnation = :incarnation AND i.revision = :revision)
    """)
    suspend fun timestamp(radio: UUID, message: UUID, incarnation: String, revision: Long, timestamp: Long): Int

    @Query("""
        UPDATE messages SET
          failureSeen = CASE WHEN :status = 4 AND statusRawValue != 4 THEN 0 ELSE failureSeen END,
          statusRawValue = :status
        WHERE radioId = :radio AND id = :message
          AND channelIndex BETWEEN 0 AND 255 AND contactID IS NULL AND directionRawValue = 1
          AND statusRawValue != 3
          AND EXISTS(SELECT 1 FROM direct_ack_incarnations i
                     WHERE i.radioId = :radio AND i.id = :message AND i.kind = 'message'
                       AND i.incarnation = :incarnation AND i.revision = :revision)
    """)
    suspend fun status(radio: UUID, message: UUID, incarnation: String, revision: Long, status: Long): Int

    @Query("""
        DELETE FROM pending_sends
        WHERE radioId = :radio AND id = :pending AND messageID = :message AND kindRawValue = 1
          AND EXISTS(SELECT 1 FROM direct_ack_incarnations i
                     WHERE i.radioId = :radio AND i.id = :pending AND i.kind = 'pending'
                       AND i.incarnation = :incarnation AND i.revision = :revision)
    """)
    suspend fun deletePending(radio: UUID, message: UUID, pending: UUID, incarnation: String, revision: Long): Int
}

/**
 * The sole version/migration producer calls createTables from the admitted v4 migration.
 * These tables contain no copied message/pending payloads and grant nothing to historical rows.
 */
object ChannelSendStorage {
    fun createTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS channel_send_attempts (" +
                "radioId TEXT NOT NULL,captureRequestId TEXT NOT NULL,leaseId TEXT NOT NULL,messageId TEXT NOT NULL," +
                "origin TEXT NOT NULL,creationDigest TEXT,resendTimestamp INTEGER," +
                "messageIncarnation TEXT NOT NULL,messageRevision INTEGER NOT NULL," +
                "pendingId TEXT,pendingIncarnation TEXT,pendingRevision INTEGER," +
                "version INTEGER NOT NULL,failed INTEGER NOT NULL,pendingDeleted INTEGER NOT NULL,current INTEGER NOT NULL," +
                "PRIMARY KEY(radioId,captureRequestId))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS channel_send_operations (" +
                "radioId TEXT NOT NULL,captureRequestId TEXT NOT NULL,requestId TEXT NOT NULL,kind TEXT NOT NULL," +
                "inputVersion INTEGER NOT NULL,outputVersion INTEGER NOT NULL," +
                "PRIMARY KEY(radioId,captureRequestId,requestId))",
        )
    }

    fun invalidateRestore(db: SupportSQLiteDatabase) {
        check(db.inTransaction()) { "Channel send restore invalidation requires the restore transaction" }
        db.execSQL("UPDATE channel_send_attempts SET current = 0")
    }
}
