// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore.swift@db14559b39d32322b06477c6ae676112f583db50
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
    ],
    version = 6,
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

    companion object {
        const val SCHEMA_VERSION = 6
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
        fun open(context: Context, name: String = "meshcoreone.db"): MeshCoreDatabase =
            Room.databaseBuilder(context.applicationContext, MeshCoreDatabase::class.java, name)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                    DirectAckStorage.migration4To5, DirectAckStorage.migration5To6,
                )
                .addCallback(DirectAckStorage.callback)
                .build()
    }
}
