// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore.swift@db14559b39d32322b06477c6ae676112f583db50
// Android schema; no SwiftData files/numbering or destructive fallback.
package com.meshcoreone.android.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

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
    ],
    version = 3,
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

    companion object {
        const val SCHEMA_VERSION = 3
        fun open(context: Context, name: String = "meshcoreone.db"): MeshCoreDatabase =
            Room.databaseBuilder(context.applicationContext, MeshCoreDatabase::class.java, name)
                .addMigrations(DirectAckStorage.migration1To2)
                .addMigrations(DirectAckExclusionStorage.migration2To3)
                .addCallback(DirectAckStorage.callback)
                .build()
    }
}
