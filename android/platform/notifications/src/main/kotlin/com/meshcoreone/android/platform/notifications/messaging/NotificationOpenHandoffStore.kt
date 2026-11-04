// AndroidOnly: WP-401 Durable, indexed identity and payload handoff for private notification taps.
package com.meshcoreone.android.platform.notifications.messaging

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class NotificationOpenIdentity(
    val producerStoreId: UUID,
    val postId: String,
    val deliveryId: UUID,
    val sequence: Long,
)

data class PendingNotificationOpen(
    val identity: NotificationOpenIdentity,
    val payload: NotificationPayload,
)

data class NotificationOpenInventorySnapshot(
    val producerStoreId: UUID,
    val revision: Long,
    val highWaterSequence: Long,
)

data class NotificationOpenInventoryEntry(
    val identity: NotificationOpenIdentity,
    val consumed: Boolean,
    val payload: NotificationPayload?,
)

class NotificationOpenHandoffStore internal constructor(private val databaseFile: File) {
    constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, DATABASE_NAME),
    )

    sealed interface StageResult {
        data class Staged(val handoff: PendingNotificationOpen) : StageResult
        data class AlreadyPending(val handoff: PendingNotificationOpen) : StageResult
        data class AlreadyConsumed(val identity: NotificationOpenIdentity) : StageResult
        data class Rejected(val reason: Failure) : StageResult
        data class Indeterminate(val postId: String, val proposedDeliveryId: UUID) : StageResult
    }

    sealed interface InventoryResult {
        data class Snapshot(val value: NotificationOpenInventorySnapshot) : InventoryResult
        data class Page(
            val snapshot: NotificationOpenInventorySnapshot,
            val entries: List<NotificationOpenInventoryEntry>,
            val nextAfterSequence: Long?,
            val complete: Boolean,
        ) : InventoryResult
        data object SnapshotInvalidated : InventoryResult
        data class Failed(val reason: Failure) : InventoryResult
    }

    sealed interface AcknowledgeResult {
        data object Acknowledged : AcknowledgeResult
        data object AlreadyAcknowledged : AcknowledgeResult
        data object IdentityMismatch : AcknowledgeResult
        data object NotFound : AcknowledgeResult
        data class Failed(val reason: Failure) : AcknowledgeResult
        data class Indeterminate(val identity: NotificationOpenIdentity) : AcknowledgeResult
    }

    enum class Failure {
        INVALID_POST_ID,
        INVALID_PAYLOAD,
        PENDING_CAPACITY,
        POST_IDENTITY_CONFLICT,
        MISSING_STORAGE,
        STORAGE_UNAVAILABLE,
        CORRUPT_STORAGE,
    }

    /**
     * Only the external bootstrap owner can authorize creation after proving this is a fresh
     * install. Ordinary stage/read/ack operations never create a missing store.
     */
    suspend fun initializeNew(): InventoryResult = withContext(Dispatchers.IO) {
        try {
            withDatabase(allowCreate = true) { database ->
                val metadata = readMetadata(database)
                    ?: return@withDatabase InventoryResult.Failed(Failure.CORRUPT_STORAGE)
                InventoryResult.Snapshot(
                    NotificationOpenInventorySnapshot(
                        metadata.storeId, metadata.revision, metadata.nextSequence - 1L,
                    ),
                )
            }
        } catch (_: SQLiteDatabaseCorruptException) {
            InventoryResult.Failed(Failure.CORRUPT_STORAGE)
        } catch (_: SQLiteException) {
            InventoryResult.Failed(Failure.STORAGE_UNAVAILABLE)
        } catch (_: SecurityException) {
            InventoryResult.Failed(Failure.STORAGE_UNAVAILABLE)
        }
    }

    /**
     * Persists a tap before reporting acceptance. Replays return the original identity; pending
     * capacity is independent of the retained consumed-identity table.
     *
     * This is a producer-store operation only. It does not acknowledge navigation or prove that
     * the consumer has durably committed a route.
     */
    suspend fun stage(postId: String, payload: NotificationPayload): StageResult =
        withContext(Dispatchers.IO) {
            if (!isCanonicalPostId(postId)) return@withContext StageResult.Rejected(Failure.INVALID_POST_ID)
            val encodedPayload = NotificationPayloadCodec.encode(payload)
            if (NotificationPayloadCodec.decode(encodedPayload) != payload) {
                return@withContext StageResult.Rejected(Failure.INVALID_PAYLOAD)
            }
            val fingerprint = fingerprint(encodedPayload)
            val proposedDeliveryId = UUID.randomUUID()
            var commitAttempted = false
            try {
                withDatabase { database ->
                    database.beginTransaction()
                    try {
                        val metadata = readMetadata(database)
                            ?: return@withDatabase StageResult.Rejected(Failure.CORRUPT_STORAGE)
                        val existing = findByPostId(database, postId, metadata.storeId)
                        if (existing != null) {
                            val storedFingerprint = existing.fingerprint
                            val identity = existing.identity
                            val result = when {
                                storedFingerprint != fingerprint ->
                                    StageResult.Rejected(Failure.POST_IDENTITY_CONFLICT)
                                existing.consumed == CONSUMED ->
                                    StageResult.AlreadyConsumed(identity)
                                else -> {
                                    val storedPayload = existing.payload
                                        ?: return@withDatabase StageResult.Rejected(Failure.CORRUPT_STORAGE)
                                    val decoded = NotificationPayloadCodec.decode(storedPayload)
                                        ?: return@withDatabase StageResult.Rejected(Failure.CORRUPT_STORAGE)
                                    StageResult.AlreadyPending(PendingNotificationOpen(identity, decoded))
                                }
                            }
                            database.setTransactionSuccessful()
                            return@withDatabase result
                        }

                        val pendingCount = scalarLong(
                            database,
                            "SELECT COUNT(*) FROM $TABLE_OPENS WHERE $COLUMN_CONSUMED = ?",
                            arrayOf(PENDING.toString()),
                        )
                        if (pendingCount >= MAX_PENDING) {
                            database.setTransactionSuccessful()
                            return@withDatabase StageResult.Rejected(Failure.PENDING_CAPACITY)
                        }

                        if (metadata.nextSequence == Long.MAX_VALUE || metadata.revision == Long.MAX_VALUE) {
                            return@withDatabase StageResult.Rejected(Failure.CORRUPT_STORAGE)
                        }
                        val identity = NotificationOpenIdentity(
                            metadata.storeId,
                            postId,
                            proposedDeliveryId,
                            metadata.nextSequence,
                        )
                        database.execSQL(
                            """INSERT INTO $TABLE_OPENS
                                ($COLUMN_POST_ID, $COLUMN_DELIVERY_ID, $COLUMN_SEQUENCE, $COLUMN_CONSUMED,
                                 $COLUMN_PAYLOAD, $COLUMN_FINGERPRINT)
                                VALUES (?, ?, ?, ?, ?, ?)""",
                            arrayOf(
                                postId,
                                proposedDeliveryId.toString(),
                                metadata.nextSequence,
                                PENDING,
                                encodedPayload,
                                fingerprint,
                            ),
                        )
                        updateMetadata(
                            database,
                            metadata.copy(
                                nextSequence = Math.incrementExact(metadata.nextSequence),
                                revision = Math.incrementExact(metadata.revision),
                            ),
                        )
                        commitAttempted = true
                        database.setTransactionSuccessful()
                        StageResult.Staged(PendingNotificationOpen(identity, payload))
                    } finally {
                        database.endTransaction()
                    }
                }
            } catch (error: MissingStorageException) {
                StageResult.Rejected(Failure.MISSING_STORAGE)
            } catch (error: SQLiteDatabaseCorruptException) {
                StageResult.Rejected(Failure.CORRUPT_STORAGE)
            } catch (error: SQLiteException) {
                if (commitAttempted) resolveStageFailure(postId, proposedDeliveryId, fingerprint)
                else StageResult.Rejected(Failure.STORAGE_UNAVAILABLE)
            } catch (error: SecurityException) {
                StageResult.Rejected(Failure.STORAGE_UNAVAILABLE)
            }
        }

    suspend fun beginInventory(): InventoryResult = withContext(Dispatchers.IO) {
        try {
            withDatabase { database ->
                val metadata = readMetadata(database)
                    ?: return@withDatabase InventoryResult.Failed(Failure.CORRUPT_STORAGE)
                InventoryResult.Snapshot(
                    NotificationOpenInventorySnapshot(
                        metadata.storeId,
                        metadata.revision,
                        metadata.nextSequence - 1L,
                    ),
                )
            }
        } catch (error: MissingStorageException) {
            InventoryResult.Failed(Failure.MISSING_STORAGE)
        } catch (error: SQLiteDatabaseCorruptException) {
            InventoryResult.Failed(Failure.CORRUPT_STORAGE)
        } catch (error: SQLiteException) {
            InventoryResult.Failed(Failure.STORAGE_UNAVAILABLE)
        } catch (error: SecurityException) {
            InventoryResult.Failed(Failure.STORAGE_UNAVAILABLE)
        }
    }

    /**
     * Every committed stage/ack changes the revision, invalidating a paged inventory rather than
     * returning a mixture of states. Restart the scan from a new snapshot when invalidated.
     */
    suspend fun readInventoryPage(
        snapshot: NotificationOpenInventorySnapshot,
        afterSequence: Long?,
        limit: Int,
    ): InventoryResult = withContext(Dispatchers.IO) {
        if (limit !in 1..MAX_PAGE_SIZE || afterSequence != null && afterSequence < 0L) {
            return@withContext InventoryResult.Failed(Failure.CORRUPT_STORAGE)
        }
        try {
            withDatabase { database ->
                database.beginTransaction()
                try {
                    val metadata = readMetadata(database)
                        ?: return@withDatabase InventoryResult.Failed(Failure.CORRUPT_STORAGE)
                    if (metadata.storeId != snapshot.producerStoreId ||
                        metadata.revision != snapshot.revision ||
                        snapshot.highWaterSequence < 0L ||
                        snapshot.highWaterSequence != metadata.nextSequence - 1L ||
                        afterSequence != null && afterSequence > snapshot.highWaterSequence
                    ) {
                        database.setTransactionSuccessful()
                        return@withDatabase InventoryResult.SnapshotInvalidated
                    }
                    val entries = queryInventoryPage(database, snapshot, afterSequence, limit)
                    database.setTransactionSuccessful()
                    val last = entries.lastOrNull()?.identity?.sequence
                    InventoryResult.Page(
                        snapshot = snapshot,
                        entries = entries,
                        nextAfterSequence = last,
                        complete = entries.size < limit || last == snapshot.highWaterSequence,
                    )
                } finally {
                    database.endTransaction()
                }
            }
        } catch (error: MissingStorageException) {
            InventoryResult.Failed(Failure.MISSING_STORAGE)
        } catch (error: SQLiteDatabaseCorruptException) {
            InventoryResult.Failed(Failure.CORRUPT_STORAGE)
        } catch (error: SQLiteException) {
            InventoryResult.Failed(Failure.STORAGE_UNAVAILABLE)
        } catch (error: SecurityException) {
            InventoryResult.Failed(Failure.STORAGE_UNAVAILABLE)
        }
    }

    /**
     * Call only after a separate consumer journal has durably committed a restorable route or
     * explicit terminal outcome. The producer transaction drops payload but retains the identity.
     */
    suspend fun acknowledge(identity: NotificationOpenIdentity): AcknowledgeResult =
        withContext(Dispatchers.IO) {
            var commitAttempted = false
            try {
                withDatabase { database ->
                    database.beginTransaction()
                    try {
                        val metadata = readMetadata(database)
                            ?: return@withDatabase AcknowledgeResult.Failed(Failure.CORRUPT_STORAGE)
                        if (metadata.storeId != identity.producerStoreId) {
                            database.setTransactionSuccessful()
                            return@withDatabase AcknowledgeResult.IdentityMismatch
                        }
                        val current = findByPostId(database, identity.postId, metadata.storeId)
                            ?: return@withDatabase AcknowledgeResult.NotFound
                        if (current.identity != identity) {
                            database.setTransactionSuccessful()
                            return@withDatabase AcknowledgeResult.IdentityMismatch
                        }
                        if (current.consumed == CONSUMED) {
                            database.setTransactionSuccessful()
                            return@withDatabase AcknowledgeResult.AlreadyAcknowledged
                        }
                        if (metadata.revision == Long.MAX_VALUE) {
                            return@withDatabase AcknowledgeResult.Failed(Failure.CORRUPT_STORAGE)
                        }
                        database.execSQL(
                            """UPDATE $TABLE_OPENS
                               SET $COLUMN_CONSUMED = ?, $COLUMN_PAYLOAD = NULL
                               WHERE $COLUMN_POST_ID = ? AND $COLUMN_DELIVERY_ID = ? AND $COLUMN_SEQUENCE = ?""",
                            arrayOf(
                                CONSUMED,
                                identity.postId,
                                identity.deliveryId.toString(),
                                identity.sequence,
                            ),
                        )
                        updateMetadata(
                            database,
                            metadata.copy(revision = Math.incrementExact(metadata.revision)),
                        )
                        commitAttempted = true
                        database.setTransactionSuccessful()
                        AcknowledgeResult.Acknowledged
                    } finally {
                        database.endTransaction()
                    }
                }
            } catch (error: MissingStorageException) {
                AcknowledgeResult.Failed(Failure.MISSING_STORAGE)
            } catch (error: SQLiteDatabaseCorruptException) {
                AcknowledgeResult.Failed(Failure.CORRUPT_STORAGE)
            } catch (error: SQLiteException) {
                if (commitAttempted) resolveAckFailure(identity)
                else AcknowledgeResult.Failed(Failure.STORAGE_UNAVAILABLE)
            } catch (error: SecurityException) {
                AcknowledgeResult.Failed(Failure.STORAGE_UNAVAILABLE)
            }
        }

    private suspend fun resolveStageFailure(
        postId: String,
        proposedDeliveryId: UUID,
        expectedFingerprint: String,
    ): StageResult = try {
        withDatabase { database ->
            val metadata = readMetadata(database)
                ?: return@withDatabase StageResult.Rejected(Failure.CORRUPT_STORAGE)
            val existing = findByPostId(database, postId, metadata.storeId)
                ?: return@withDatabase StageResult.Indeterminate(postId, proposedDeliveryId)
            if (existing.fingerprint != expectedFingerprint) {
                return@withDatabase StageResult.Rejected(Failure.POST_IDENTITY_CONFLICT)
            }
            val identity = existing.identity
            if (existing.consumed == CONSUMED) {
                StageResult.AlreadyConsumed(identity)
            } else if (identity.deliveryId == proposedDeliveryId) {
                val payloadText = existing.payload
                    ?: return@withDatabase StageResult.Rejected(Failure.CORRUPT_STORAGE)
                val payload = NotificationPayloadCodec.decode(payloadText)
                    ?: return@withDatabase StageResult.Rejected(Failure.CORRUPT_STORAGE)
                StageResult.Staged(PendingNotificationOpen(identity, payload))
            } else {
                val payloadText = existing.payload
                    ?: return@withDatabase StageResult.Rejected(Failure.CORRUPT_STORAGE)
                val payload = NotificationPayloadCodec.decode(payloadText)
                    ?: return@withDatabase StageResult.Rejected(Failure.CORRUPT_STORAGE)
                StageResult.AlreadyPending(PendingNotificationOpen(identity, payload))
            }
        }
    } catch (error: MissingStorageException) {
        StageResult.Indeterminate(postId, proposedDeliveryId)
    } catch (error: SQLiteDatabaseCorruptException) {
        StageResult.Rejected(Failure.CORRUPT_STORAGE)
    } catch (error: SQLiteException) {
        StageResult.Indeterminate(postId, proposedDeliveryId)
    } catch (error: SecurityException) {
        StageResult.Rejected(Failure.STORAGE_UNAVAILABLE)
    }

    private suspend fun resolveAckFailure(identity: NotificationOpenIdentity): AcknowledgeResult =
        try {
            withDatabase { database ->
                val metadata = readMetadata(database)
                    ?: return@withDatabase AcknowledgeResult.Failed(Failure.CORRUPT_STORAGE)
                if (metadata.storeId != identity.producerStoreId) {
                    return@withDatabase AcknowledgeResult.IdentityMismatch
                }
                val current = findByPostId(database, identity.postId, metadata.storeId)
                    ?: return@withDatabase AcknowledgeResult.Indeterminate(identity)
                if (current.identity != identity) return@withDatabase AcknowledgeResult.IdentityMismatch
                if (current.consumed == CONSUMED) {
                    AcknowledgeResult.AlreadyAcknowledged
                } else {
                    AcknowledgeResult.Indeterminate(identity)
                }
            }
        } catch (error: MissingStorageException) {
            AcknowledgeResult.Indeterminate(identity)
        } catch (error: SQLiteDatabaseCorruptException) {
            AcknowledgeResult.Failed(Failure.CORRUPT_STORAGE)
        } catch (error: SQLiteException) {
            AcknowledgeResult.Indeterminate(identity)
        } catch (error: SecurityException) {
            AcknowledgeResult.Failed(Failure.STORAGE_UNAVAILABLE)
        }

    private fun <T> withDatabase(allowCreate: Boolean = false, block: (SQLiteDatabase) -> T): T {
        val existed = databaseFile.exists()
        if (!existed && !allowCreate) throw MissingStorageException()
        val database = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE or (if (allowCreate) SQLiteDatabase.CREATE_IF_NECESSARY else 0) or
                SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
            { throw SQLiteDatabaseCorruptException("Notification open store corruption; preserving files") },
        )
        try {
            database.execSQL("PRAGMA synchronous=FULL")
            synchronized(openLock) {
                ensureSchema(database, existed)
            }
            return block(database)
        } finally {
            database.close()
        }
    }

    private fun ensureSchema(database: SQLiteDatabase, existed: Boolean) {
        database.beginTransaction()
        try {
            val metadataExists = tableExists(database, TABLE_METADATA)
            val opensExists = tableExists(database, TABLE_OPENS)
            if (!metadataExists && !opensExists && !existed) {
                database.execSQL(
                    """CREATE TABLE $TABLE_METADATA (
                        singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
                        store_id TEXT NOT NULL,
                        next_sequence INTEGER NOT NULL CHECK(next_sequence > 0),
                        revision INTEGER NOT NULL CHECK(revision >= 0)
                    )""",
                )
                database.execSQL(
                    """CREATE TABLE $TABLE_OPENS (
                        post_id TEXT PRIMARY KEY NOT NULL,
                        delivery_id TEXT UNIQUE NOT NULL,
                        sequence INTEGER UNIQUE NOT NULL CHECK(sequence > 0),
                        consumed INTEGER NOT NULL CHECK(consumed IN (0, 1)),
                        payload TEXT,
                        payload_fingerprint TEXT NOT NULL,
                        CHECK((consumed = 0 AND payload IS NOT NULL) OR
                              (consumed = 1 AND payload IS NULL))
                    )""",
                )
                database.execSQL(
                    "CREATE INDEX pending_sequence ON $TABLE_OPENS(sequence) WHERE consumed = 0",
                )
                database.execSQL(
                    "INSERT INTO $TABLE_METADATA(singleton, store_id, next_sequence, revision) VALUES(1, ?, 1, 0)",
                    arrayOf(UUID.randomUUID().toString()),
                )
                database.version = DATABASE_VERSION
            } else if (!metadataExists || !opensExists || database.version != DATABASE_VERSION) {
                throw SQLiteDatabaseCorruptException("Notification open journal schema is missing or unsupported")
            } else if (readMetadata(database) == null) {
                throw SQLiteDatabaseCorruptException("Notification open journal identity is missing")
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun readMetadata(database: SQLiteDatabase): Metadata? =
        database.rawQuery(
            "SELECT store_id, next_sequence, revision FROM $TABLE_METADATA WHERE singleton = 1",
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val storeId = parseUuid(cursor.getString(0)) ?: return@use null
                val nextSequence = cursor.getLong(1)
                val revision = cursor.getLong(2)
                if (nextSequence <= 0L || revision < 0L) null
                else Metadata(storeId, nextSequence, revision)
            }
        }

    private fun updateMetadata(database: SQLiteDatabase, metadata: Metadata) {
        database.execSQL(
            "UPDATE $TABLE_METADATA SET next_sequence = ?, revision = ? WHERE singleton = 1 AND store_id = ?",
            arrayOf(metadata.nextSequence, metadata.revision, metadata.storeId.toString()),
        )
        if (database.compileStatement("SELECT changes()").use { it.simpleQueryForLong() } != 1L) {
            throw SQLiteDatabaseCorruptException("Notification open journal metadata identity changed")
        }
    }

    private fun queryInventoryPage(
        database: SQLiteDatabase,
        snapshot: NotificationOpenInventorySnapshot,
        afterSequence: Long?,
        limit: Int,
    ): List<NotificationOpenInventoryEntry> {
        val lower = afterSequence ?: 0L
        val entries = mutableListOf<NotificationOpenInventoryEntry>()
        var expectedSequence = Math.incrementExact(lower)
        database.rawQuery(
            """SELECT post_id, delivery_id, sequence, consumed, payload, payload_fingerprint
               FROM $TABLE_OPENS
               WHERE sequence > ? AND sequence <= ?
               ORDER BY sequence LIMIT ?""",
            arrayOf(lower.toString(), snapshot.highWaterSequence.toString(), limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val row = cursor.toRow(snapshot.producerStoreId)
                if (row.identity.sequence != expectedSequence) {
                    throw SQLiteDatabaseCorruptException("Notification open sequence inventory has a gap")
                }
                expectedSequence = Math.incrementExact(expectedSequence)
                val payload = row.payload?.let(NotificationPayloadCodec::decode)
                if (row.consumed == PENDING && payload == null ||
                    row.consumed == CONSUMED && row.payload != null
                ) {
                    throw SQLiteDatabaseCorruptException("Notification open row has invalid payload disposition")
                }
                entries += NotificationOpenInventoryEntry(row.identity, row.consumed == CONSUMED, payload)
            }
        }
        val lastSequence = entries.lastOrNull()?.identity?.sequence ?: lower
        if (lastSequence != snapshot.highWaterSequence && entries.size < limit) {
            throw SQLiteDatabaseCorruptException("Notification open inventory ended before its high water mark")
        }
        return entries
    }

    private fun Cursor.toRow(storeId: UUID): Row {
        val postId = getString(0)
        val deliveryId = parseUuid(getString(1))
            ?: throw SQLiteDatabaseCorruptException("Invalid notification delivery identity")
        val sequence = getLong(2)
        val consumed = getInt(3)
        val payload = if (isNull(4)) null else getString(4)
        val fingerprint = getString(5)
        if (!isCanonicalPostId(postId) || deliveryId.toString() != getString(1) || sequence <= 0L ||
            consumed !in PENDING..CONSUMED || fingerprint == null ||
            !fingerprint.matches(Regex("[0-9a-f]{64}")) ||
            consumed == PENDING && (payload == null || fingerprint(payload) != fingerprint ||
                NotificationPayloadCodec.decode(payload) == null) ||
            consumed == CONSUMED && payload != null
        ) {
            throw SQLiteDatabaseCorruptException("Invalid notification handoff row")
        }
        return Row(
            NotificationOpenIdentity(storeId, postId, deliveryId, sequence),
            consumed,
            payload,
            fingerprint,
        )
    }

    private fun tableExists(database: SQLiteDatabase, table: String): Boolean =
        database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table),
        ).use { cursor -> cursor.moveToFirst() }

    private fun scalarLong(database: SQLiteDatabase, sql: String, args: Array<String>): Long =
        database.rawQuery(sql, args).use { cursor ->
            if (!cursor.moveToFirst()) throw SQLiteDatabaseCorruptException("Missing scalar result")
            cursor.getLong(0)
        }

    private fun isCanonicalPostId(value: String): Boolean =
        parseUuid(value)?.toString() == value

    private fun parseUuid(value: String?): UUID? = try {
        value?.let(UUID::fromString)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class Metadata(val storeId: UUID, val nextSequence: Long, val revision: Long)
    private class MissingStorageException : SQLiteException("Notification open store is missing")
    private data class Row(
        val identity: NotificationOpenIdentity,
        val consumed: Int,
        val payload: String?,
        val fingerprint: String,
    )

    private fun findByPostId(database: SQLiteDatabase, postId: String, storeId: UUID): Row? =
        database.rawQuery(
            """SELECT post_id, delivery_id, sequence, consumed, payload, payload_fingerprint
               FROM $TABLE_OPENS WHERE post_id = ?""",
            arrayOf(postId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toRow(storeId) else null }

    companion object {
        private const val DATABASE_NAME = "notification-open-handoffs.db"
        private const val DATABASE_VERSION = 1
        private const val MAX_PENDING = 32L
        private const val MAX_PAGE_SIZE = 256
        private const val TABLE_METADATA = "notification_open_metadata"
        private const val TABLE_OPENS = "notification_open_records"
        private const val COLUMN_POST_ID = "post_id"
        private const val COLUMN_DELIVERY_ID = "delivery_id"
        private const val COLUMN_SEQUENCE = "sequence"
        private const val COLUMN_CONSUMED = "consumed"
        private const val COLUMN_PAYLOAD = "payload"
        private const val COLUMN_FINGERPRINT = "payload_fingerprint"
        private const val PENDING = 0
        private const val CONSUMED = 1
        private val openLock = Any()
    }
}
