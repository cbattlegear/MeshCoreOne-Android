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
    val pendingLifecycleIncarnation: String? = null,
)

@Entity(tableName = "channel_pending_lifecycles", primaryKeys = ["radioId", "pendingId"])
data class ChannelPendingLifecycleEntity(
    val radioId: UUID,
    val pendingId: UUID,
    val messageId: UUID,
    val incarnation: String,
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

@Entity(tableName = "channel_send_completions", primaryKeys = ["radioId", "captureRequestId"])
data class ChannelSendCompletionEntity(
    val radioId: UUID,
    val captureRequestId: UUID,
    val completionRequestId: UUID,
    val mode: String,
    val acceptedTimestamp: Long,
    val inputVersion: Long,
    val outputVersion: Long,
    val pendingCompletionRequestId: UUID?,
)

@Dao
interface ChannelSendDao {
    @Query("SELECT * FROM channel_pending_lifecycles WHERE radioId = :radio AND pendingId = :pending")
    suspend fun pendingLifecycle(radio: UUID, pending: UUID): ChannelPendingLifecycleEntity?

    @Query("""
        SELECT a.* FROM channel_send_attempts a
        JOIN channel_send_completions c ON c.radioId = a.radioId AND c.captureRequestId = a.captureRequestId
        WHERE a.radioId = :radio AND a.messageId = :message AND a.messageIncarnation = :incarnation
          AND a.pendingId IS NOT NULL
        ORDER BY a.captureRequestId
    """)
    suspend fun completedCaptures(radio: UUID, message: UUID, incarnation: String): List<ChannelSendAttemptEntity>

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

    @Query("SELECT * FROM channel_send_completions WHERE radioId = :radio AND captureRequestId = :capture")
    suspend fun completion(radio: UUID, capture: UUID): ChannelSendCompletionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: ChannelSendCompletionEntity)

    @Update
    suspend fun update(row: ChannelSendCompletionEntity): Int

    @Query("""
        UPDATE messages SET statusRawValue = 2, sendCount = :sendCount, heardRepeats = :heardRepeats
        WHERE radioId = :radio AND id = :message
          AND channelIndex BETWEEN 0 AND 255 AND contactID IS NULL AND directionRawValue = 1
          AND statusRawValue != 3 AND timestamp = :timestamp
          AND EXISTS(SELECT 1 FROM direct_ack_incarnations i
                     WHERE i.radioId = :radio AND i.id = :message AND i.kind = 'message'
                       AND i.incarnation = :incarnation AND i.revision = :revision)
    """)
    suspend fun complete(
        radio: UUID, message: UUID, incarnation: String, revision: Long, timestamp: Long,
        sendCount: Long, heardRepeats: Long,
    ): Int

    @Query("""
        DELETE FROM message_repeats WHERE radioId = :radio AND messageID = :message
          AND EXISTS(SELECT 1 FROM direct_ack_incarnations i
                     WHERE i.radioId = :radio AND i.id = :message AND i.kind = 'message'
                       AND i.incarnation = :incarnation AND i.revision = :revision)
    """)
    suspend fun deleteCompletionRepeats(radio: UUID, message: UUID, incarnation: String, revision: Long): Int

    @Query("""
        UPDATE pending_sends SET attemptCount = :count
        WHERE radioId = :radio AND id = :pending AND messageID = :message AND kindRawValue = 1
          AND EXISTS(SELECT 1 FROM direct_ack_incarnations i
                     WHERE i.radioId = :radio AND i.id = :pending AND i.kind = 'pending'
                       AND i.incarnation = :incarnation AND i.revision = :revision)
          AND EXISTS(SELECT 1 FROM channel_pending_lifecycles l
                     WHERE l.radioId = :radio AND l.pendingId = :pending AND l.messageId = :message
                       AND l.incarnation = :lifecycle)
    """)
    suspend fun bumpPending(
        radio: UUID, message: UUID, pending: UUID, incarnation: String, revision: Long, lifecycle: String, count: Long,
    ): Int

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
                "pendingLifecycleIncarnation TEXT," +
                "PRIMARY KEY(radioId,captureRequestId))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS channel_send_operations (" +
                "radioId TEXT NOT NULL,captureRequestId TEXT NOT NULL,requestId TEXT NOT NULL,kind TEXT NOT NULL," +
                "inputVersion INTEGER NOT NULL,outputVersion INTEGER NOT NULL," +
                "PRIMARY KEY(radioId,captureRequestId,requestId))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS channel_send_completions (" +
                "radioId TEXT NOT NULL,captureRequestId TEXT NOT NULL,completionRequestId TEXT NOT NULL," +
                "mode TEXT NOT NULL,acceptedTimestamp INTEGER NOT NULL," +
                "inputVersion INTEGER NOT NULL,outputVersion INTEGER NOT NULL,pendingCompletionRequestId TEXT," +
                "PRIMARY KEY(radioId,captureRequestId))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS channel_pending_lifecycles (" +
                "radioId TEXT NOT NULL,pendingId TEXT NOT NULL,messageId TEXT NOT NULL,incarnation TEXT NOT NULL," +
                "PRIMARY KEY(radioId,pendingId))",
        )
        installPendingLifecycles(db)
    }

    private val lifecycleTriggers: Map<String, String> = linkedMapOf(
        "channel_pending_lifecycle_insert" to
            "CREATE TRIGGER channel_pending_lifecycle_insert AFTER INSERT ON pending_sends BEGIN " +
            "INSERT OR REPLACE INTO channel_pending_lifecycles (radioId,pendingId,messageId,incarnation) " +
            "VALUES (NEW.radioId,NEW.id,NEW.messageID,lower(hex(randomblob(32)))); END",
        "channel_pending_lifecycle_delete" to
            "CREATE TRIGGER channel_pending_lifecycle_delete AFTER DELETE ON pending_sends BEGIN " +
            "DELETE FROM channel_pending_lifecycles WHERE radioId = OLD.radioId AND pendingId = OLD.id; END",
        "channel_pending_lifecycle_update" to
            "CREATE TRIGGER channel_pending_lifecycle_update AFTER UPDATE ON pending_sends BEGIN " +
            "UPDATE channel_pending_lifecycles SET radioId = NEW.radioId,pendingId = NEW.id,messageId = NEW.messageID," +
            "incarnation = CASE WHEN OLD.radioId = NEW.radioId AND OLD.id = NEW.id AND OLD.messageID = NEW.messageID " +
            "THEN incarnation ELSE lower(hex(randomblob(32))) END " +
            "WHERE radioId = OLD.radioId AND pendingId = OLD.id; END",
    )

    /** Only fresh creation / the sole migration may bootstrap live row births, never historical captures. */
    fun installPendingLifecycles(db: SupportSQLiteDatabase) {
        val existing = lifecycleTriggerMap(db)
        if (existing.isNotEmpty()) {
            verifyPendingLifecycles(db)
            return
        }
        lifecycleTriggers.values.forEach(db::execSQL)
        db.execSQL(
            "INSERT INTO channel_pending_lifecycles (radioId,pendingId,messageId,incarnation) " +
                "SELECT radioId,id,messageID,lower(hex(randomblob(32))) FROM pending_sends",
        )
        verifyPendingLifecycles(db)
    }

    fun verifyPendingLifecycles(db: SupportSQLiteDatabase) {
        check(lifecycleTriggerMap(db) == lifecycleTriggers) { "Channel pending lifecycle triggers are missing or changed" }
        db.query(
            "SELECT COUNT(*) FROM pending_sends p LEFT JOIN channel_pending_lifecycles l " +
                "ON l.radioId = p.radioId AND l.pendingId = p.id " +
                "WHERE l.incarnation IS NULL OR l.messageId != p.messageID OR length(l.incarnation) != 64 " +
                "OR l.incarnation GLOB '*[^0-9a-f]*'",
        ).use { cursor ->
            check(cursor.moveToFirst() && cursor.getLong(0) == 0L) { "Channel pending lifecycle coverage is invalid" }
        }
        db.query(
            "SELECT COUNT(*) FROM channel_pending_lifecycles l WHERE NOT EXISTS " +
                "(SELECT 1 FROM pending_sends p WHERE p.radioId = l.radioId AND p.id = l.pendingId)",
        ).use { cursor ->
            check(cursor.moveToFirst() && cursor.getLong(0) == 0L) { "Orphaned channel pending lifecycle" }
        }
    }

    private fun lifecycleTriggerMap(db: SupportSQLiteDatabase): Map<String, String> {
        val actual = linkedMapOf<String, String>()
        db.query(
            "SELECT name,sql FROM sqlite_master WHERE type = 'trigger' AND name GLOB 'channel_pending_lifecycle_*'",
        ).use { cursor -> while (cursor.moveToNext()) actual[cursor.getString(0)] = cursor.getString(1) }
        return actual
    }

    fun invalidateRestore(db: SupportSQLiteDatabase) {
        check(db.inTransaction()) { "Channel send restore invalidation requires the restore transaction" }
        db.execSQL("UPDATE channel_send_attempts SET current = 0")
    }
}
