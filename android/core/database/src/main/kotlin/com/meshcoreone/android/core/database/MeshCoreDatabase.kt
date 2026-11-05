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
        BlockedChannelSenderEntity::class, PendingSendEntity::class,
        DirectAckIncarnationEntity::class, DirectAckLeaseEntity::class, DirectAckClaimEntity::class,
        DirectAckDeliveryEntity::class, DirectAckWireEntity::class, DirectAckOperationEntity::class,
        DirectPendingCompletionEntity::class,
        DirectAckIntentEntity::class,
        DirectAckEmissionEntity::class, DirectAckRadioExclusionEntity::class,
        DirectAckPrehistoryEntity::class,
        ChannelSendAttemptEntity::class, ChannelSendOperationEntity::class,
        ChannelPendingLifecycleEntity::class, ChannelMessageOriginEntity::class, ChannelSendCompletionEntity::class,
    ],
    version = 4,
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
    abstract fun directAckExclusions(): DirectAckExclusionDao
    abstract fun directAckPrehistory(): DirectAckPrehistoryDao
    abstract fun channelSends(): ChannelSendDao

    companion object {
        const val SCHEMA_VERSION = 4
        val migration3To4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                DirectAckPrehistoryStorage.createForMigration(db)
                ChannelSendStorage.createTables(db)
            }
        }
        fun open(context: Context, name: String = "meshcoreone.db"): MeshCoreDatabase =
            Room.databaseBuilder(context.applicationContext, MeshCoreDatabase::class.java, name)
                .addMigrations(DirectAckStorage.migration1To2)
                .addMigrations(DirectAckExclusionStorage.migration2To3)
                .addMigrations(migration3To4)
                .addCallback(DirectAckStorage.callback)
                .build()
    }
}
