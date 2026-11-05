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

@Entity(tableName = "channel_message_origins", primaryKeys = ["radioId", "messageId"])
data class ChannelMessageOriginEntity(
    val radioId: UUID,
    val messageId: UUID,
    val channelIndex: Long,
    val occupantID: UUID,
    val messageIncarnation: String,
    val envelopeDigest: String,
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

    @Query("SELECT * FROM channel_message_origins WHERE radioId = :radio AND messageId = :message")
    suspend fun messageOrigin(radio: UUID, message: UUID): ChannelMessageOriginEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessageOrigin(row: ChannelMessageOriginEntity)

    @Query("""
        SELECT a.* FROM channel_send_attempts a
        JOIN channel_send_completions c ON c.radioId = a.radioId AND c.captureRequestId = a.captureRequestId
        WHERE a.radioId = :radio AND a.messageId = :message AND a.messageIncarnation = :incarnation
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
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS channel_message_origins (" +
                "radioId TEXT NOT NULL,messageId TEXT NOT NULL,channelIndex INTEGER NOT NULL,occupantID TEXT NOT NULL," +
                "messageIncarnation TEXT NOT NULL,envelopeDigest TEXT NOT NULL,current INTEGER NOT NULL," +
                "PRIMARY KEY(radioId,messageId))",
        )
        installPendingLifecycles(db)
        installMessageOriginProtection(db)
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

    private val messageOriginTriggers: Map<String, String> = linkedMapOf(
        "channel_message_origin_update_guard" to
            "CREATE TRIGGER channel_message_origin_update_guard BEFORE UPDATE ON channel_message_origins " +
            "WHEN NOT (OLD.current = 1 AND NEW.current = 0 AND OLD.radioId IS NEW.radioId " +
            "AND OLD.messageId IS NEW.messageId AND OLD.channelIndex IS NEW.channelIndex " +
            "AND OLD.occupantID IS NEW.occupantID AND OLD.messageIncarnation IS NEW.messageIncarnation " +
            "AND OLD.envelopeDigest IS NEW.envelopeDigest) " +
            "BEGIN SELECT RAISE(ABORT,'channel message origin is immutable'); END",
        "channel_message_origin_delete_guard" to
            "CREATE TRIGGER channel_message_origin_delete_guard BEFORE DELETE ON channel_message_origins " +
            "BEGIN SELECT RAISE(ABORT,'channel message origin is immutable'); END",
        "channel_message_origin_retire_delete" to
            "CREATE TRIGGER channel_message_origin_retire_delete AFTER DELETE ON messages BEGIN " +
            "UPDATE channel_message_origins SET current = 0 WHERE current = 1 " +
            "AND radioId = OLD.radioId AND messageId = OLD.id; END",
        "channel_message_origin_retire_update" to
            "CREATE TRIGGER channel_message_origin_retire_update AFTER UPDATE ON messages " +
            "WHEN OLD.radioId IS NOT NEW.radioId OR OLD.id IS NOT NEW.id " +
            "OR OLD.channelIndex IS NOT NEW.channelIndex OR OLD.text IS NOT NEW.text " +
            "OR OLD.timestamp IS NOT NEW.timestamp OR OLD.textTypeRawValue IS NOT NEW.textTypeRawValue " +
            "BEGIN UPDATE channel_message_origins SET current = 0 " +
            "WHERE current = 1 AND radioId = OLD.radioId AND messageId = OLD.id; END",
        "channel_message_origin_retire_channel_delete" to
            "CREATE TRIGGER channel_message_origin_retire_channel_delete AFTER DELETE ON channels BEGIN " +
            "UPDATE channel_message_origins SET current = 0 " +
            "WHERE current = 1 AND radioId = OLD.radioId AND channelIndex = OLD.`index` AND occupantID = OLD.id; END",
        "channel_message_origin_retire_channel_update" to
            "CREATE TRIGGER channel_message_origin_retire_channel_update AFTER UPDATE ON channels " +
            "WHEN OLD.radioId IS NOT NEW.radioId OR OLD.id IS NOT NEW.id " +
            "OR OLD.`index` IS NOT NEW.`index` OR OLD.secret IS NOT NEW.secret " +
            "BEGIN UPDATE channel_message_origins SET current = 0 " +
            "WHERE current = 1 AND radioId = OLD.radioId AND channelIndex = OLD.`index` AND occupantID = OLD.id; END",
        "channel_message_origin_rotate_raw_occupant" to
            "CREATE TRIGGER channel_message_origin_rotate_raw_occupant AFTER UPDATE OF secret ON channels " +
            "WHEN OLD.secret IS NOT NEW.secret BEGIN UPDATE channels SET id = lower(" +
            "substr(hex(randomblob(16)),1,8)||'-'||substr(hex(randomblob(16)),9,4)||'-'||" +
            "substr(hex(randomblob(16)),13,4)||'-'||substr(hex(randomblob(16)),17,4)||'-'||" +
            "substr(hex(randomblob(16)),21,12)) WHERE radioId = NEW.radioId AND id = NEW.id; END",
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

    fun installMessageOriginProtection(db: SupportSQLiteDatabase) {
        val existing = messageOriginTriggerMap(db)
        if (existing.isNotEmpty()) {
            verifyMessageOriginProtection(db)
            return
        }
        messageOriginTriggers.values.forEach(db::execSQL)
        verifyMessageOriginProtection(db)
    }

    fun verifyMessageOriginProtection(db: SupportSQLiteDatabase) {
        check(messageOriginTriggerMap(db) == messageOriginTriggers) {
            "Channel message origin protection triggers are missing or changed"
        }
        db.query(
            "SELECT COUNT(*) FROM channel_message_origins WHERE channelIndex NOT BETWEEN 0 AND 255 " +
                "OR current NOT IN (0,1) OR length(messageIncarnation) != 64 " +
                "OR messageIncarnation GLOB '*[^0-9a-f]*' OR length(envelopeDigest) != 64 " +
                "OR envelopeDigest GLOB '*[^0-9a-f]*'",
        ).use { cursor ->
            check(cursor.moveToFirst() && cursor.getLong(0) == 0L) { "Channel message origin data is invalid" }
        }
    }

    private fun lifecycleTriggerMap(db: SupportSQLiteDatabase): Map<String, String> {
        val actual = linkedMapOf<String, String>()
        db.query(
            "SELECT name,sql FROM sqlite_master WHERE type = 'trigger' AND name GLOB 'channel_pending_lifecycle_*'",
        ).use { cursor -> while (cursor.moveToNext()) actual[cursor.getString(0)] = cursor.getString(1) }
        return actual
    }

    private fun messageOriginTriggerMap(db: SupportSQLiteDatabase): Map<String, String> {
        val actual = linkedMapOf<String, String>()
        db.query(
            "SELECT name,sql FROM sqlite_master WHERE type = 'trigger' AND name GLOB 'channel_message_origin_*'",
        ).use { cursor -> while (cursor.moveToNext()) actual[cursor.getString(0)] = cursor.getString(1) }
        return actual
    }

    fun invalidateRestore(db: SupportSQLiteDatabase) {
        check(db.inTransaction()) { "Channel send restore invalidation requires the restore transaction" }
        db.execSQL("UPDATE channel_send_attempts SET current = 0")
        db.execSQL("UPDATE channel_message_origins SET current = 0 WHERE current = 1")
    }
}
