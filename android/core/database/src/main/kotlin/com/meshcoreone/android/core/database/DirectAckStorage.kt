// AndroidOnly: WP-202 Persistent direct-message incarnation and all-writer ACK fencing.
package com.meshcoreone.android.core.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

@Entity(tableName = "direct_ack_incarnations", primaryKeys = ["radioId", "id", "kind"])
data class DirectAckIncarnationEntity(
    val radioId: UUID,
    val id: UUID,
    val kind: String,
    val incarnation: String,
    val revision: Long,
)

@Entity(tableName = "direct_ack_leases", primaryKeys = ["radioId", "requestId"])
data class DirectAckLeaseEntity(
    val radioId: UUID,
    val requestId: UUID,
    val issuer: UUID,
    val epoch: UUID,
    val generation: Long,
    val sequence: Long,
    val active: Boolean,
)

@Entity(tableName = "direct_ack_claims", primaryKeys = ["radioId", "requestId"])
data class DirectAckClaimEntity(
    val requestId: UUID,
    val leaseId: UUID,
    val firstWireId: UUID,
    val incarnation: String,
    val revision: Long,
    val phase: String,
    val mode: String,
    val initialStatus: Long,
    val current: Boolean,
    val pendingId: UUID?,
    val pendingIncarnation: String?,
    val radioId: UUID,
    val messageId: UUID,
    @Embedded(prefix = "snapshot_") val snapshot: MessageEntity?,
)

@Entity(tableName = "direct_ack_deliveries", primaryKeys = ["radioId", "claimId"])
data class DirectAckDeliveryEntity(
    val claimId: UUID,
    val ackCode: Long,
    val roundTripTime: Long?,
    val radioId: UUID,
    val messageId: UUID,
    @Embedded(prefix = "snapshot_") val snapshot: MessageEntity?,
)

@Entity(tableName = "direct_ack_wires", primaryKeys = ["radioId", "claimId", "requestId"])
data class DirectAckWireEntity(
    val radioId: UUID,
    val claimId: UUID,
    val requestId: UUID,
    val predictedCode: Long,
    val acceptedCode: Long?,
)

@Entity(tableName = "direct_ack_operations", primaryKeys = ["radioId", "claimId", "requestId"])
data class DirectAckOperationEntity(
    val radioId: UUID,
    val claimId: UUID,
    val requestId: UUID,
    val kind: String,
    val argument: Long?,
    val maximum: Long?,
    val pendingId: UUID?,
)

@Entity(tableName = "direct_pending_completions", primaryKeys = ["radioId", "captureRequestId"])
data class DirectPendingCompletionEntity(
    val captureRequestId: UUID,
    val leaseId: UUID,
    val messageIncarnation: String,
    val messageRevision: Long,
    val pendingIncarnation: String,
    val reason: String,
    val maximumAttempts: Long?,
    val current: Boolean,
    val completedRequestId: UUID?,
    val radioId: UUID,
    val messageId: UUID,
    val pendingId: UUID,
    @Embedded(prefix = "message_") val message: MessageEntity?,
    @Embedded(prefix = "pending_") val pending: PendingSendEntity?,
)

@Entity(tableName = "direct_ack_intents", primaryKeys = ["radioId", "requestId"])
data class DirectAckIntentEntity(
    val radioId: UUID,
    val requestId: UUID,
    val leaseId: UUID,
    val messageId: UUID,
    val pendingId: UUID?,
    val mode: String,
    val incarnation: String,
    val revision: Long,
    val pendingIncarnation: String?,
    val pendingRevision: Long?,
    val current: Boolean,
    @Embedded(prefix = "message_") val message: MessageEntity?,
    @Embedded(prefix = "pending_") val pending: PendingSendEntity?,
)

@Dao
interface DirectAckDao {
    @Query("UPDATE messages SET statusRawValue = 3,ackCode = :code,roundTripTime = :rtt WHERE radioId = :radio AND id = :id")
    suspend fun deliver(radio: UUID, id: UUID, code: Long, rtt: Long?): Int
    @Query("SELECT * FROM direct_ack_incarnations WHERE radioId = :radio AND id = :id AND kind = :kind")
    suspend fun incarnation(radio: UUID, id: UUID, kind: String): DirectAckIncarnationEntity?
    @Query("SELECT * FROM direct_ack_leases WHERE radioId = :radio")
    suspend fun leases(radio: UUID): List<DirectAckLeaseEntity>
    @Query("SELECT * FROM direct_ack_leases WHERE radioId = :radio AND requestId = :request")
    suspend fun lease(radio: UUID, request: UUID): DirectAckLeaseEntity?
    @Upsert suspend fun lease(row: DirectAckLeaseEntity)
    @Query("UPDATE direct_ack_leases SET active = 0 WHERE issuer = :issuer")
    suspend fun revokeIssuer(issuer: UUID): Int
    @Query("UPDATE direct_ack_leases SET active = 0 WHERE radioId = :radio")
    suspend fun revokeRadio(radio: UUID): Int
    @Query("SELECT * FROM direct_ack_claims WHERE radioId = :radio AND requestId = :request")
    suspend fun claim(radio: UUID, request: UUID): DirectAckClaimEntity?
    @Upsert suspend fun claim(row: DirectAckClaimEntity)
    @Query("UPDATE direct_ack_claims SET current = 0 WHERE radioId = :radio AND messageId = :message")
    suspend fun invalidateMessage(radio: UUID, message: UUID): Int
    @Query("UPDATE direct_ack_claims SET current = 0")
    suspend fun invalidateRestore(): Int
    @Query("SELECT * FROM direct_ack_deliveries WHERE radioId = :radio AND claimId = :claim")
    suspend fun delivery(radio: UUID, claim: UUID): DirectAckDeliveryEntity?
    @Upsert suspend fun delivery(row: DirectAckDeliveryEntity)
    @Query("SELECT * FROM direct_ack_wires WHERE radioId = :radio AND claimId = :claim ORDER BY requestId")
    suspend fun wires(radio: UUID, claim: UUID): List<DirectAckWireEntity>
    @Query("SELECT * FROM direct_ack_wires WHERE radioId = :radio AND claimId = :claim AND requestId = :request")
    suspend fun wire(radio: UUID, claim: UUID, request: UUID): DirectAckWireEntity?
    @Upsert suspend fun wire(row: DirectAckWireEntity)
    @Query("SELECT * FROM direct_ack_operations WHERE radioId = :radio AND claimId = :claim ORDER BY requestId")
    suspend fun operations(radio: UUID, claim: UUID): List<DirectAckOperationEntity>
    @Query("""
        SELECT * FROM direct_ack_operations
        WHERE radioId = :radio AND claimId = :claim
          AND kind NOT IN ('RADIO_DELIVERY','RADIO_FAILURE','EMISSION_TRACKING_REQUIRED')
          AND substr(kind,1,20) != 'EMISSION_RESOLUTION:'
          AND substr(kind,1,28) != 'ACCEPTANCE_HISTORY_REQUIRED:'
          AND substr(kind,1,24) != 'TRACKED_WIRE_ACCEPTANCE:'
        ORDER BY requestId
    """)
    suspend fun receiptOperations(radio: UUID, claim: UUID): List<DirectAckOperationEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM direct_ack_operations WHERE radioId = :radio AND claimId = :claim AND kind = 'PENDING')")
    suspend fun hasPendingCompletion(radio: UUID, claim: UUID): Boolean
    @Query("SELECT * FROM direct_ack_operations WHERE radioId = :radio AND claimId = :claim AND requestId = :request")
    suspend fun operation(radio: UUID, claim: UUID, request: UUID): DirectAckOperationEntity?
    @Query("SELECT * FROM direct_ack_operations WHERE radioId = :radio AND claimId = :claim AND kind = 'RADIO_DELIVERY' LIMIT 2")
    suspend fun radioDeliveries(radio: UUID, claim: UUID): List<DirectAckOperationEntity>
    @Query("SELECT * FROM direct_ack_operations WHERE radioId = :radio AND claimId = :claim AND kind = 'RADIO_FAILURE' LIMIT 2")
    suspend fun radioFailures(radio: UUID, claim: UUID): List<DirectAckOperationEntity>
    @Upsert suspend fun operation(row: DirectAckOperationEntity)
    @Query("SELECT * FROM direct_pending_completions WHERE radioId = :radio AND captureRequestId = :request")
    suspend fun pendingCompletion(radio: UUID, request: UUID): DirectPendingCompletionEntity?
    @Upsert suspend fun pendingCompletion(row: DirectPendingCompletionEntity)
    @Query("UPDATE direct_pending_completions SET current = 0")
    suspend fun invalidatePendingRestore(): Int
    @Query("SELECT * FROM direct_ack_intents WHERE radioId = :radio AND requestId = :request")
    suspend fun intent(radio: UUID, request: UUID): DirectAckIntentEntity?
    @Upsert suspend fun intent(row: DirectAckIntentEntity)
    @Query("UPDATE direct_ack_intents SET current = 0")
    suspend fun invalidateIntentRestore(): Int
    @Query("UPDATE direct_ack_intents SET current = 0 WHERE radioId = :radio AND messageId = :message")
    suspend fun invalidateIntents(radio: UUID, message: UUID): Int
}

object DirectAckStorage {
    private const val CLAIMS = "direct_ack_claims"
    private const val IDENTITIES = "direct_ack_incarnations"

    private fun invalidate(radio: String, message: String) =
        "UPDATE $CLAIMS SET current = 0 WHERE radioId = $radio AND messageId = $message;" +
            "UPDATE direct_ack_intents SET current = 0 WHERE radioId = $radio AND messageId = $message;"

    private fun identity(radio: String, id: String, kind: String) =
        "INSERT OR REPLACE INTO $IDENTITIES (radioId,id,kind,incarnation,revision) " +
            "VALUES ($radio,$id,'$kind',lower(hex(randomblob(32))),0);"

    private fun advanceMessageRevision(predicate: String) =
        "SELECT CASE WHEN EXISTS(SELECT 1 FROM $IDENTITIES WHERE kind = 'message' " +
            "AND ($predicate) AND revision = 9223372036854775807) " +
            "THEN RAISE(ABORT,'direct ACK revision exhausted') END;" +
            "UPDATE $IDENTITIES SET revision = revision + 1 WHERE kind = 'message' AND ($predicate);"

    private fun triggerDefinitions(db: SupportSQLiteDatabase): Map<String, String> {
        val messageColumns = columnNames(db, "messages")
        val pendingColumns = columnNames(db, "pending_sends")
        fun nulls(prefix: String, columns: List<String>) = columns.joinToString(",") { "`$prefix$it` = NULL" }
        fun eraseMessage(radio: String, id: String, condition: String = "1") =
            "UPDATE $CLAIMS SET current = 0,${nulls("snapshot_", messageColumns)} " +
                "WHERE radioId = $radio AND messageId = $id AND ($condition);" +
                "UPDATE direct_ack_deliveries SET ${nulls("snapshot_", messageColumns)} " +
                "WHERE radioId = $radio AND messageId = $id AND ($condition);" +
                "UPDATE direct_ack_intents SET current = 0,${nulls("message_", messageColumns)},${nulls("pending_", pendingColumns)} " +
                "WHERE radioId = $radio AND messageId = $id AND ($condition);" +
                "UPDATE direct_pending_completions SET current = 0,${nulls("message_", messageColumns)},${nulls("pending_", pendingColumns)} " +
                "WHERE radioId = $radio AND messageId = $id AND ($condition);"
        fun erasePending(radio: String, id: String) =
            "UPDATE direct_ack_intents SET current = 0,${nulls("pending_", pendingColumns)} " +
                "WHERE radioId = $radio AND pendingId = $id;" +
                "UPDATE direct_pending_completions SET current = 0,${nulls("pending_", pendingColumns)} " +
                "WHERE radioId = $radio AND pendingId = $id;"
        val ownFields = setOf("statusRawValue", "failureSeen", "ackCode", "roundTripTime", "retryAttempt", "maxRetryAttempts", "sendCount")
        val redacted = messageColumns.filterNot { it in ownFields }.joinToString(" OR ") { "OLD.`$it` IS NOT NEW.`$it`" }
        return buildMap {
        put("direct_ack_lease_revoke", "CREATE TRIGGER direct_ack_lease_revoke AFTER UPDATE ON direct_ack_leases " +
            "WHEN OLD.active != 0 AND NEW.active = 0 BEGIN " +
            "UPDATE direct_ack_deliveries SET ${nulls("snapshot_", messageColumns)} " +
                "WHERE radioId = OLD.radioId AND claimId IN " +
                "(SELECT requestId FROM $CLAIMS WHERE radioId = OLD.radioId AND leaseId = OLD.requestId);" +
            "UPDATE $CLAIMS SET current = 0,${nulls("snapshot_", messageColumns)} " +
                "WHERE radioId = OLD.radioId AND leaseId = OLD.requestId;" +
            "UPDATE direct_ack_intents SET current = 0,${nulls("message_", messageColumns)},${nulls("pending_", pendingColumns)} " +
                "WHERE radioId = OLD.radioId AND leaseId = OLD.requestId;" +
            "UPDATE direct_pending_completions SET current = 0,${nulls("message_", messageColumns)},${nulls("pending_", pendingColumns)} " +
                "WHERE radioId = OLD.radioId AND leaseId = OLD.requestId; END")
        put("direct_ack_message_insert", "CREATE TRIGGER direct_ack_message_insert AFTER INSERT ON messages BEGIN " +
            invalidate("NEW.radioId", "NEW.id") + eraseMessage("NEW.radioId", "NEW.id") +
            identity("NEW.radioId", "NEW.id", "message") + " END")
        put("direct_ack_message_update", "CREATE TRIGGER direct_ack_message_update AFTER UPDATE ON messages BEGIN " +
            invalidate("OLD.radioId", "OLD.id") + invalidate("NEW.radioId", "NEW.id") +
            eraseMessage("OLD.radioId", "OLD.id", redacted) + eraseMessage("NEW.radioId", "NEW.id", redacted) +
            "DELETE FROM $IDENTITIES WHERE kind = 'message' AND radioId = OLD.radioId AND id = OLD.id " +
            "AND (OLD.radioId != NEW.radioId OR OLD.id != NEW.id);" +
            "INSERT OR REPLACE INTO $IDENTITIES (radioId,id,kind,incarnation,revision) " +
            "SELECT NEW.radioId,NEW.id,'message',lower(hex(randomblob(32))),0 " +
            "WHERE OLD.radioId != NEW.radioId OR OLD.id != NEW.id;" +
            advanceMessageRevision("radioId = NEW.radioId AND id = NEW.id") + " END")
        put("direct_ack_message_delete", "CREATE TRIGGER direct_ack_message_delete AFTER DELETE ON messages BEGIN " +
            invalidate("OLD.radioId", "OLD.id") +
            eraseMessage("OLD.radioId", "OLD.id") +
            "DELETE FROM $IDENTITIES WHERE kind = 'message' AND radioId = OLD.radioId AND id = OLD.id; END")
        put("direct_ack_pending_replace", "CREATE TRIGGER direct_ack_pending_replace BEFORE INSERT ON pending_sends " +
            "WHEN EXISTS(SELECT 1 FROM pending_sends WHERE radioId = NEW.radioId AND id = NEW.id " +
            "AND messageID != NEW.messageID) BEGIN " +
            "UPDATE $CLAIMS SET current = 0 WHERE radioId = NEW.radioId AND messageId IN " +
            "(SELECT messageID FROM pending_sends WHERE radioId = NEW.radioId AND id = NEW.id);" +
            advanceMessageRevision("radioId = NEW.radioId AND id IN " +
                "(SELECT messageID FROM pending_sends WHERE radioId = NEW.radioId AND id = NEW.id)") + " END")
        put("direct_ack_pending_insert", "CREATE TRIGGER direct_ack_pending_insert AFTER INSERT ON pending_sends BEGIN " +
            invalidate("NEW.radioId", "NEW.messageID") + erasePending("NEW.radioId", "NEW.id") +
            identity("NEW.radioId", "NEW.id", "pending") +
            advanceMessageRevision("radioId = NEW.radioId AND id = NEW.messageID") + " END")
        put("direct_ack_pending_update", "CREATE TRIGGER direct_ack_pending_update AFTER UPDATE ON pending_sends BEGIN " +
            invalidate("OLD.radioId", "OLD.messageID") + invalidate("NEW.radioId", "NEW.messageID") +
            erasePending("OLD.radioId", "OLD.id") + erasePending("NEW.radioId", "NEW.id") +
            "DELETE FROM $IDENTITIES WHERE kind = 'pending' AND radioId = OLD.radioId AND id = OLD.id;" +
            identity("NEW.radioId", "NEW.id", "pending") +
            advanceMessageRevision("(radioId = OLD.radioId AND id = OLD.messageID) OR " +
                "(radioId = NEW.radioId AND id = NEW.messageID)") + " END")
        put("direct_ack_pending_delete", "CREATE TRIGGER direct_ack_pending_delete AFTER DELETE ON pending_sends BEGIN " +
            invalidate("OLD.radioId", "OLD.messageID") +
            erasePending("OLD.radioId", "OLD.id") +
            "DELETE FROM $IDENTITIES WHERE kind = 'pending' AND radioId = OLD.radioId AND id = OLD.id;" +
            advanceMessageRevision("radioId = OLD.radioId AND id = OLD.messageID") + " END")
        }
    }

    val callback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            install(db)
            DirectAckPrehistoryStorage.initialize(db)
        }
        override fun onOpen(db: SupportSQLiteDatabase) {
            verify(db)
            DirectAckPrehistoryStorage.verify(db)
        }
    }

    val migration1To2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS direct_ack_incarnations (" +
                "radioId TEXT NOT NULL,id TEXT NOT NULL,kind TEXT NOT NULL,incarnation TEXT NOT NULL,revision INTEGER NOT NULL," +
                "PRIMARY KEY(radioId,id,kind))")
            db.execSQL("CREATE TABLE IF NOT EXISTS direct_ack_leases (" +
                "radioId TEXT NOT NULL,requestId TEXT NOT NULL,issuer TEXT NOT NULL,epoch TEXT NOT NULL," +
                "generation INTEGER NOT NULL,sequence INTEGER NOT NULL,active INTEGER NOT NULL,PRIMARY KEY(radioId,requestId))")
            val snapshot = snapshotColumns(db, "messages", "snapshot_")
            db.execSQL("CREATE TABLE IF NOT EXISTS direct_ack_claims (" +
                "requestId TEXT NOT NULL,leaseId TEXT NOT NULL,firstWireId TEXT NOT NULL,incarnation TEXT NOT NULL," +
                "revision INTEGER NOT NULL,phase TEXT NOT NULL," +
                "mode TEXT NOT NULL,initialStatus INTEGER NOT NULL," +
                "current INTEGER NOT NULL,pendingId TEXT,pendingIncarnation TEXT,radioId TEXT NOT NULL,messageId TEXT NOT NULL,$snapshot," +
                "PRIMARY KEY(radioId,requestId))")
            db.execSQL("CREATE TABLE IF NOT EXISTS direct_ack_deliveries (" +
                "claimId TEXT NOT NULL,ackCode INTEGER NOT NULL,roundTripTime INTEGER,radioId TEXT NOT NULL,messageId TEXT NOT NULL,$snapshot," +
                "PRIMARY KEY(radioId,claimId))")
            db.execSQL("CREATE TABLE IF NOT EXISTS direct_ack_wires (" +
                "radioId TEXT NOT NULL,claimId TEXT NOT NULL,requestId TEXT NOT NULL,predictedCode INTEGER NOT NULL," +
                "acceptedCode INTEGER,PRIMARY KEY(radioId,claimId,requestId))")
            db.execSQL("CREATE TABLE IF NOT EXISTS direct_ack_operations (" +
                "radioId TEXT NOT NULL,claimId TEXT NOT NULL,requestId TEXT NOT NULL,kind TEXT NOT NULL," +
                "argument INTEGER,maximum INTEGER,pendingId TEXT,PRIMARY KEY(radioId,claimId,requestId))")
            db.execSQL("CREATE TABLE IF NOT EXISTS direct_pending_completions (" +
                "captureRequestId TEXT NOT NULL,leaseId TEXT NOT NULL,messageIncarnation TEXT NOT NULL," +
                "messageRevision INTEGER NOT NULL,pendingIncarnation TEXT NOT NULL,reason TEXT NOT NULL," +
                "maximumAttempts INTEGER,current INTEGER NOT NULL,completedRequestId TEXT," +
                "radioId TEXT NOT NULL,messageId TEXT NOT NULL,pendingId TEXT NOT NULL," +
                snapshotColumns(db, "messages", "message_") + "," +
                snapshotColumns(db, "pending_sends", "pending_") + ",PRIMARY KEY(radioId,captureRequestId))")
            db.execSQL("CREATE TABLE IF NOT EXISTS direct_ack_intents (" +
                "radioId TEXT NOT NULL,requestId TEXT NOT NULL,leaseId TEXT NOT NULL,messageId TEXT NOT NULL,pendingId TEXT," +
                "mode TEXT NOT NULL,incarnation TEXT NOT NULL,revision INTEGER NOT NULL,pendingIncarnation TEXT,pendingRevision INTEGER,current INTEGER NOT NULL," +
                snapshotColumns(db, "messages", "message_") + "," +
                snapshotColumns(db, "pending_sends", "pending_") + ",PRIMARY KEY(radioId,requestId))")
            install(db)
        }
    }

    fun verify(db: SupportSQLiteDatabase) {
        val actual = mutableMapOf<String, String>()
        db.query("SELECT name,sql FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'direct_ack_%'").use {
            while (it.moveToNext()) actual[it.getString(0)] = it.getString(1)
        }
        check(actual == triggerDefinitions(db)) { "Direct ACK all-writer fencing triggers are missing or changed" }
    }

    fun eraseRestorePayloads(db: SupportSQLiteDatabase) {
        check(db.inTransaction()) { "Direct ACK restore erasure requires the restore transaction" }
        verify(db)
        val messageColumns = columnNames(db, "messages")
        val pendingColumns = columnNames(db, "pending_sends")
        fun nulls(prefix: String, columns: List<String>) =
            columns.joinToString(",") { "`$prefix$it` = NULL" }
        db.execSQL("UPDATE direct_ack_claims SET current = 0,${nulls("snapshot_", messageColumns)}")
        db.execSQL("UPDATE direct_ack_deliveries SET ${nulls("snapshot_", messageColumns)}")
        db.execSQL("UPDATE direct_ack_intents SET current = 0," +
            "${nulls("message_", messageColumns)},${nulls("pending_", pendingColumns)}")
        db.execSQL("UPDATE direct_pending_completions SET current = 0," +
            "${nulls("message_", messageColumns)},${nulls("pending_", pendingColumns)}")
    }

    private fun install(db: SupportSQLiteDatabase) {
        triggerDefinitions(db).values.forEach(db::execSQL)
        db.execSQL("INSERT INTO $IDENTITIES (radioId,id,kind,incarnation,revision) " +
            "SELECT radioId,id,'message',lower(hex(randomblob(32))),0 FROM messages")
        db.execSQL("INSERT INTO $IDENTITIES (radioId,id,kind,incarnation,revision) " +
            "SELECT radioId,id,'pending',lower(hex(randomblob(32))),0 FROM pending_sends")
        verify(db)
    }

    private fun snapshotColumns(db: SupportSQLiteDatabase, table: String, prefix: String): String {
        val columns = mutableListOf<String>()
        db.query("PRAGMA table_info($table)").use {
            while (it.moveToNext()) {
                val name = it.getString(1)
                check(name.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))) { "Invalid message column" }
                val type = it.getString(2)
                check(type in setOf("TEXT", "INTEGER", "REAL", "BLOB")) { "Invalid message column type" }
                columns += "`$prefix$name` $type"
            }
        }
        check(columns.isNotEmpty()) { "Missing v1 $table table" }
        return columns.joinToString(",")
    }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info($table)").use {
            buildList {
                while (it.moveToNext()) {
                    val name = it.getString(1)
                    check(name.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))) { "Invalid snapshot column" }
                    add(name)
                }
            }.also { columns -> check(columns.isNotEmpty()) { "Missing $table columns" } }
        }
}
