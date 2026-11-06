// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore.swift@db14559b39d32322b06477c6ae676112f583db50
// Android Room schema; v2 adds durable channel reaction identity through a non-destructive migration.
package com.meshcoreone.android.core.database

import android.content.Context
import android.annotation.SuppressLint
import androidx.room.Database
import androidx.room.DatabaseConfiguration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.RoomDatabase.Callback
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DeviceEntity::class, ContactEntity::class, ChannelEntity::class, MessageEntity::class,
        MessageRepeatEntity::class, ReactionEntity::class, ChannelReactionIdentityEntity::class,
        RemoteNodeSessionEntity::class, RoomMessageEntity::class,
        SavedTracePathEntity::class, TracePathRunEntity::class, RxLogEntryEntity::class, DebugLogEntryEntity::class,
        LinkPreviewEntity::class, DiscoveredNodeEntity::class, NodeStatusSnapshotEntity::class,
        BlockedChannelSenderEntity::class, PendingSendEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(ValueConverters::class)
abstract class MeshCoreDatabase : RoomDatabase() {
    // Room 2.8.5 has no public per-database callback hook; preserve all builder configuration.
    @SuppressLint("RestrictedApi")
    final override fun init(configuration: DatabaseConfiguration) {
        require(configuration.sqliteDriver == null) {
            "MeshCoreDatabase requires SupportSQLiteOpenHelper for durable identity triggers"
        }
        val existing = configuration.migrationContainer.getMigrations()[1]?.get(2)
        require(existing == null || existing === Migration1To2) {
            "MeshCoreDatabase cannot replace a different migration from version 1 to 2"
        }
        configuration.migrationContainer.addMigrations(Migration1To2)
        super.init(
            configuration.copy(
                callbacks = listOf(IdentityCreationCallback, IdentityVerificationCallback) +
                    configuration.callbacks.orEmpty() + IdentityVerificationCallback,
            ),
        )
    }

    abstract fun devices(): DeviceDao
    abstract fun contacts(): ContactDao
    abstract fun channels(): ChannelDao
    abstract fun messages(): MessageDao
    abstract fun channelReactionIdentities(): ChannelReactionIdentityDao
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

    companion object {
        const val SCHEMA_VERSION = 2
        fun open(context: Context, name: String = "meshcoreone.db"): MeshCoreDatabase =
            Room.databaseBuilder(context.applicationContext, MeshCoreDatabase::class.java, name)
                .build()
    }

    private object IdentityCreationCallback : Callback() {
        override fun onCreate(database: SupportSQLiteDatabase) {
            ChannelReactionIdentitySql.install(database)
        }
    }

    private object IdentityVerificationCallback : Callback() {
        override fun onOpen(database: SupportSQLiteDatabase) {
            // Never repair missing triggers on reopen: intervening writes may have escaped invalidation.
            ChannelReactionIdentitySql.requireInstalled(database)
        }
    }
}
