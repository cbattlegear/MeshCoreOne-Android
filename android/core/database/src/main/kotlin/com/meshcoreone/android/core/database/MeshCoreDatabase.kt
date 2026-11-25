// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Android schema; no SwiftData files/numbering or destructive fallback.
package com.meshcoreone.android.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DeviceEntity::class, ContactEntity::class, ChannelEntity::class, MessageEntity::class,
        MessageRepeatEntity::class, ReactionEntity::class, RemoteNodeSessionEntity::class, RoomMessageEntity::class,
        SavedTracePathEntity::class, TracePathRunEntity::class, RxLogEntryEntity::class, DebugLogEntryEntity::class,
        LinkPreviewEntity::class, DiscoveredNodeEntity::class, NodeStatusSnapshotEntity::class,
        BlockedChannelSenderEntity::class, PendingSendEntity::class, ChannelOccupantEntity::class, QueuedChannelAttemptEntity::class,
        ChannelSubmissionEntity::class,
        DirectAckIncarnationEntity::class, DirectAckLeaseEntity::class, DirectAckClaimEntity::class,
        DirectAckDeliveryEntity::class, DirectAckWireEntity::class, DirectAckOperationEntity::class,
        DirectPendingCompletionEntity::class,
        DirectAckIntentEntity::class,
        ReactionSummaryReceiptEntity::class,
        ReactionQueueStateEntity::class, ClassifiedReactionEntity::class,
        TextRulePreferenceEntity::class, TextRuleConversationEntity::class, RecentlyBlockedTextEntity::class,
        OutgoingReactionIntentEntity::class,
    ],
    version = 14,
    exportSchema = true,
)
@TypeConverters(ValueConverters::class)
abstract class MeshCoreDatabase : RoomDatabase() {
    abstract fun devices(): DeviceDao
    abstract fun contacts(): ContactDao
    abstract fun channels(): ChannelDao
    abstract fun messages(): MessageDao
    abstract fun repeats(): MessageRepeatDao
    abstract fun reactions(): ReactionDao
    abstract fun pendingSends(): PendingSendDao
    abstract fun sessions(): RemoteNodeSessionDao
    abstract fun roomMessages(): RoomMessageDao
    abstract fun tracePaths(): SavedTracePathDao
    abstract fun traceRuns(): TracePathRunDao
    abstract fun rxLogs(): RxLogDao
    abstract fun debugLogs(): DebugLogDao
    abstract fun linkPreviews(): LinkPreviewDao
    abstract fun discoveredNodes(): DiscoveredNodeDao
    abstract fun nodeSnapshots(): NodeSnapshotDao
    abstract fun blockedSenders(): BlockedChannelSenderDao
    abstract fun directAcks(): DirectAckDao
    abstract fun channelOccupants(): ChannelOccupantDao
    abstract fun queuedChannelAttempts(): QueuedChannelAttemptDao
    abstract fun channelSubmissions(): ChannelSubmissionDao
    abstract fun reactionSummaryReceipts(): ReactionSummaryReceiptDao
    abstract fun classifiedReactionQueue(): ClassifiedReactionQueueDao
    abstract fun textRules(): TextRuleDao
    abstract fun outgoingReactionIntents(): OutgoingReactionIntentDao

    companion object {
        const val SCHEMA_VERSION = 14
        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE node_status_snapshots ADD COLUMN selfRadioID TEXT")
            }
        }
        val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS outgoing_reaction_intents (radioId TEXT NOT NULL, messageID TEXT NOT NULL, pendingID TEXT NOT NULL, messageIncarnation TEXT NOT NULL, pendingIncarnation TEXT NOT NULL, targetID TEXT NOT NULL, targetIncarnation TEXT NOT NULL, targetFingerprint TEXT NOT NULL, senderName TEXT NOT NULL, emoji TEXT NOT NULL, senderKey TEXT NOT NULL, emojiKey TEXT NOT NULL, messageHash TEXT NOT NULL, rawText TEXT NOT NULL, channelIndex INTEGER, contactID TEXT, contactPublicKey BLOB, completed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(radioId, messageID))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_outgoing_reaction_intents_radioId_targetID_targetIncarnation_targetFingerprint_senderKey_emojiKey ON outgoing_reaction_intents (radioId, targetID, targetIncarnation, targetFingerprint, senderKey, emojiKey)")
            }
        }
        val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS text_rule_preferences (singleton INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(singleton))")
                db.execSQL("CREATE TABLE IF NOT EXISTS text_rule_conversations (radioId TEXT NOT NULL, family TEXT NOT NULL, parentID TEXT NOT NULL, fingerprint TEXT NOT NULL, identity TEXT NOT NULL, override TEXT NOT NULL, PRIMARY KEY(radioId, family, parentID))")
                db.execSQL("CREATE TABLE IF NOT EXISTS recently_blocked_texts (id TEXT NOT NULL, radioId TEXT NOT NULL, conversationID TEXT, contactID TEXT, senderPrefix BLOB, arrivedSeconds INTEGER NOT NULL, arrivedNanos INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_direct_wire_timestamp ON messages (radioId, channelIndex, senderKeyPrefix, senderTimestamp, timestampCorrected, timestamp)")
                TextRuleStorage.install(db, roomArchiveOwners = false)
            }
        }
        val MIGRATION_13_14: Migration = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recently_blocked_texts ADD COLUMN roomSessionID TEXT")
                db.execSQL("""
                    UPDATE recently_blocked_texts SET roomSessionID = (
                        SELECT CASE WHEN COUNT(*) = 1 AND MAX(family) = 'room' THEN MAX(parentID) END
                        FROM text_rule_conversations
                        WHERE radioId = recently_blocked_texts.radioId COLLATE NOCASE
                          AND identity = recently_blocked_texts.conversationID COLLATE NOCASE
                    ) WHERE conversationID IS NOT NULL
                """.trimIndent())
                TextRuleStorage.install(db)
            }
        }
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reactions ADD COLUMN notificationToken TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reactions_radioId_notificationToken ON reactions (radioId, notificationToken)")
            }
        }
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN channelOccupantID TEXT")
                db.execSQL("ALTER TABLE pending_sends ADD COLUMN channelOccupantID TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS channel_occupants (radioId TEXT NOT NULL, `index` INTEGER NOT NULL, occupantID TEXT NOT NULL, unresolved INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(radioId, `index`))")
            }
        }
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_sends ADD COLUMN channelDispatchStateRawValue INTEGER")
                db.execSQL("ALTER TABLE pending_sends ADD COLUMN channelAttemptID TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS queued_channel_attempts (radioId TEXT NOT NULL, attemptID TEXT NOT NULL, pendingID TEXT NOT NULL, messageID TEXT NOT NULL, channelIndex INTEGER NOT NULL, channelOccupantID TEXT NOT NULL, wireTimestamp INTEGER NOT NULL, isResend INTEGER NOT NULL, phaseRawValue INTEGER NOT NULL, isCompleted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(radioId, attemptID))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_queued_channel_attempts_radioId_pendingID ON queued_channel_attempts (radioId, pendingID)")
            }
        }
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS channel_submissions (pendingID TEXT NOT NULL, radioId TEXT NOT NULL, messageID TEXT NOT NULL, channelIndex INTEGER NOT NULL, channelOccupantID TEXT NOT NULL, messageText TEXT, messageTimestamp INTEGER NOT NULL, localNodeName TEXT, isResend INTEGER NOT NULL, enqueuedSeconds INTEGER NOT NULL, enqueuedNanos INTEGER NOT NULL, sequence INTEGER NOT NULL, phaseRawValue INTEGER NOT NULL, PRIMARY KEY(pendingID))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_channel_submissions_radioId_messageID ON channel_submissions (radioId, messageID)")
            }
        }
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reactions ADD COLUMN targetIncarnation TEXT")
                db.execSQL("ALTER TABLE reactions ADD COLUMN notificationEligible INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE queued_channel_attempts ADD COLUMN messageIncarnation TEXT")
            }
        }
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS reaction_summary_receipts (radioId TEXT NOT NULL, operationID TEXT NOT NULL, messageID TEXT NOT NULL, targetIncarnation TEXT NOT NULL, summary TEXT, PRIMARY KEY(radioId, operationID), FOREIGN KEY(radioId, messageID) REFERENCES messages(radioId, id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reaction_summary_receipts_radioId_messageID ON reaction_summary_receipts (radioId, messageID)")
            }
        }
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS reaction_queue_state (singleton INTEGER NOT NULL, version TEXT NOT NULL, PRIMARY KEY(singleton))")
                db.execSQL("CREATE TABLE IF NOT EXISTS classified_channel_reactions (radioId TEXT NOT NULL, occurrenceID TEXT NOT NULL, emoji TEXT NOT NULL, targetSender TEXT NOT NULL, messageHash TEXT NOT NULL, channelIndex INTEGER NOT NULL, senderName TEXT NOT NULL, rawText TEXT NOT NULL, receivedSeconds INTEGER NOT NULL, receivedNanos INTEGER NOT NULL, localNodeName TEXT, deliveryKind INTEGER NOT NULL, anchorSeconds INTEGER, anchorNanos INTEGER, keyOrder INTEGER NOT NULL, entryOrder INTEGER NOT NULL, queued INTEGER NOT NULL, targetID TEXT, targetIncarnation TEXT, notificationPending INTEGER NOT NULL, mutation INTEGER NOT NULL, PRIMARY KEY(radioId, occurrenceID))")
            }
        }
        fun open(context: Context, name: String = "meshcoreone.db"): MeshCoreDatabase =
            Room.databaseBuilder(context.applicationContext, MeshCoreDatabase::class.java, name)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                    DirectAckStorage.migration4To5, DirectAckStorage.migration5To6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                    MIGRATION_12_13, MIGRATION_13_14,
                )
                .addCallback(DirectAckStorage.callback)
                .addCallback(TextRuleStorage.callback)
                .build()
    }
}
