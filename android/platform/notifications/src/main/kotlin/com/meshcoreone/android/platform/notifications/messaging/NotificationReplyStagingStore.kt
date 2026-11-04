// AndroidOnly: WP-401 Unactivated private reply intake; no claim, dispatch, settlement or retention.
package com.meshcoreone.android.platform.notifications.messaging

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Structural post identity, NOT authentication or RF-origin proof. The future ingress owner must
 * verify the private PendingIntent provenance before staging. Display targets are not send authority.
 */
data class NotificationReplyPost(val version: Int, val postId: UUID, val action: String) {
    internal fun isValid(): Boolean = version == 1 && action == "REPLY"
}

/**
 * Required policy inputs, with no production defaults. Bytes count the complete serialized record
 * content (including IDs), not SQLite pages/journals/filesystem overhead. No eviction or expiry.
 */
data class NotificationReplyLimits(
    val maxRecords: Int,
    val maxRecordBytes: Long,
    val maxTotalRecordBytes: Long,
) {
    internal fun isValid(): Boolean =
        maxRecords > 0 && maxRecordBytes > 0 && maxTotalRecordBytes >= maxRecordBytes
}

class NotificationReplyStagingStore internal constructor(
    private val databaseFile: File,
    private val limits: NotificationReplyLimits,
) {
    constructor(context: Context, limits: NotificationReplyLimits) : this(
        File(context.applicationContext.noBackupFilesDir, "notification-reply-staging.db"), limits,
    )

    data class RecordIdentity(val storeId: UUID, val recordId: UUID, val sequence: Long)

    sealed interface StageResult {
        data class Staged(val identity: RecordIdentity, val outgoingMessageId: UUID) : StageResult
        data class AlreadyStaged(val identity: RecordIdentity, val outgoingMessageId: UUID) : StageResult
        data class ConflictRetained(val identity: RecordIdentity, val alreadyRetained: Boolean) : StageResult
        data class Rejected(val reason: Failure) : StageResult
        data class Indeterminate(val post: NotificationReplyPost, val proposedRecordId: UUID) : StageResult
    }

    /** Neither recovery variant authorizes dispatch; conflicts deliberately expose no route. */
    sealed interface RecoveryEntry {
        val identity: RecordIdentity
        val post: NotificationReplyPost
        val exactText: String
        val targetAuthority: TargetAuthority get() = TargetAuthority.UNPROVED

        class StagedReply internal constructor(
            override val identity: RecordIdentity,
            override val post: NotificationReplyPost,
            override val exactText: String,
            val payload: NotificationPayload,
            val outgoingMessageId: UUID,
        ) : RecoveryEntry

        class UnboundConflict internal constructor(
            override val identity: RecordIdentity,
            override val post: NotificationReplyPost,
            override val exactText: String,
        ) : RecoveryEntry
    }

    sealed interface ReadResult {
        class Inventory internal constructor(val entries: List<RecoveryEntry>) : ReadResult
        data class Failed(val reason: Failure) : ReadResult
    }

    sealed interface InitializeResult {
        data object Ready : InitializeResult
        data class Failed(val reason: Failure) : InitializeResult
        data object Indeterminate : InitializeResult
    }

    enum class Failure {
        INVALID_IDENTITY, INVALID_PAYLOAD, INVALID_TEXT, INVALID_LIMITS, LIMITS_MISMATCH,
        RECORD_CAPACITY, PRIVATE_DATA_CAPACITY, MISSING_STORAGE, STORAGE_UNAVAILABLE, CORRUPT_STORAGE,
    }

    // Neither the current payload nor a display-time full key proves the radio's prefix destination.
    enum class TargetAuthority { UNPROVED }

    /**
     * Only after the external owner proves a fresh install. Ordinary operations never create a
     * missing database. No known-initialized marker or data-loss recovery is provided by this slice.
     */
    suspend fun initializeNew(): InitializeResult = withContext(Dispatchers.IO) {
        if (!limits.isValid()) return@withContext InitializeResult.Failed(Failure.INVALID_LIMITS)
        var commitAttempted = false
        try {
            database(allowCreate = true) { db ->
                transaction(db) {
                    if (db.version == 0 && !hasUserSchema(db)) {
                        db.execSQL(
                            """CREATE TABLE reply_metadata(
                               singleton INTEGER PRIMARY KEY CHECK(singleton=1), store_id TEXT NOT NULL,
                               record_count INTEGER NOT NULL, content_bytes INTEGER NOT NULL,
                               max_records INTEGER NOT NULL, max_record_bytes INTEGER NOT NULL,
                               max_total_bytes INTEGER NOT NULL)""",
                        )
                        db.execSQL(
                            """CREATE TABLE reply_records(
                               sequence INTEGER PRIMARY KEY, record_id TEXT UNIQUE NOT NULL,
                               post_id TEXT NOT NULL, version INTEGER NOT NULL, action TEXT NOT NULL,
                               parent_id TEXT, outgoing_id TEXT UNIQUE, payload BLOB NOT NULL,
                               reply_text BLOB NOT NULL, content_bytes INTEGER NOT NULL, digest BLOB NOT NULL,
                               CHECK((parent_id IS NULL AND outgoing_id IS NOT NULL) OR
                                     (parent_id IS NOT NULL AND outgoing_id IS NULL)))""",
                        )
                        db.execSQL("CREATE INDEX reply_post ON reply_records(post_id)")
                        db.execSQL(
                            "INSERT INTO reply_metadata VALUES(1, ?, 0, 0, ?, ?, ?)",
                            arrayOf<Any>(UUID.randomUUID().toString(), limits.maxRecords, limits.maxRecordBytes,
                                limits.maxTotalRecordBytes),
                        )
                        db.version = 1
                    }
                    load(db)
                    commitAttempted = true
                    InitializeResult.Ready
                }
            }
        } catch (error: StoreFailure) {
            InitializeResult.Failed(error.reason)
        } catch (_: SQLiteDatabaseCorruptException) {
            InitializeResult.Failed(Failure.CORRUPT_STORAGE)
        } catch (_: SQLiteException) {
            if (commitAttempted) InitializeResult.Indeterminate
            else InitializeResult.Failed(Failure.STORAGE_UNAVAILABLE)
        } catch (_: SecurityException) {
            if (commitAttempted) InitializeResult.Indeterminate
            else InitializeResult.Failed(Failure.STORAGE_UNAVAILABLE)
        }
    }

    suspend fun stage(
        post: NotificationReplyPost,
        payload: NotificationPayload,
        exactText: String,
    ): StageResult = withContext(Dispatchers.IO) {
        if (!limits.isValid()) return@withContext StageResult.Rejected(Failure.INVALID_LIMITS)
        if (!post.isValid()) return@withContext StageResult.Rejected(Failure.INVALID_IDENTITY)
        if (exactText.isEmpty()) return@withContext StageResult.Rejected(Failure.INVALID_TEXT)
        if (payload !is NotificationPayload.DirectMessage && payload !is NotificationPayload.ChannelMessage) {
            return@withContext StageResult.Rejected(Failure.INVALID_PAYLOAD)
        }
        val encoded = NotificationPayloadCodec.encode(payload)
        if (NotificationPayloadCodec.decode(encoded) != payload) {
            return@withContext StageResult.Rejected(Failure.INVALID_PAYLOAD)
        }
        if (encoded.length.toLong() * Char.SIZE_BYTES + exactText.length.toLong() * Char.SIZE_BYTES >
            limits.maxRecordBytes ||
            encoded.length > Int.MAX_VALUE / Char.SIZE_BYTES ||
            exactText.length > Int.MAX_VALUE / Char.SIZE_BYTES
        ) return@withContext StageResult.Rejected(Failure.PRIVATE_DATA_CAPACITY)
        val payloadBytes = textBytes(encoded)
        val replyBytes = textBytes(exactText)
        val proposedId = UUID.randomUUID()
        var commitAttempted = false
        try {
            database { db ->
                transaction(db) {
                    val state = load(db)
                    val prior = state.rows.filter { it.post == post }
                    val identical = prior.firstOrNull {
                        it.payload.contentEquals(payloadBytes) && it.text.contentEquals(replyBytes)
                    }
                    if (identical != null) return@transaction result(state.storeId, identical, replay = true)
                    val first = prior.firstOrNull { it.parentId == null }
                    val row = Row(
                        state.rows.size.toLong() + 1, proposedId, post, first?.recordId,
                        if (first == null) UUID.randomUUID() else null, payloadBytes, replyBytes,
                    )
                    if (row.contentBytes > limits.maxRecordBytes ||
                        row.contentBytes > limits.maxTotalRecordBytes - state.contentBytes
                    ) return@transaction StageResult.Rejected(Failure.PRIVATE_DATA_CAPACITY)
                    if (state.rows.size >= limits.maxRecords) {
                        return@transaction StageResult.Rejected(Failure.RECORD_CAPACITY)
                    }
                    db.execSQL(
                        "INSERT INTO reply_records VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf(row.sequence, row.recordId.toString(), post.postId.toString(), post.version,
                            post.action, row.parentId?.toString(), row.outgoingId?.toString(),
                            row.payload, row.text, row.contentBytes, row.digest(state.storeId)),
                    )
                    db.execSQL(
                        "UPDATE reply_metadata SET record_count=?, content_bytes=? WHERE singleton=1",
                        arrayOf<Any>(state.rows.size + 1, state.contentBytes + row.contentBytes),
                    )
                    val retained = load(db)
                    val actual = retained.rows.lastOrNull() ?: corrupt()
                    if (retained.storeId != state.storeId || retained.rows.size != state.rows.size + 1 ||
                        actual.sequence != row.sequence || actual.recordId != row.recordId ||
                        actual.post != row.post || actual.parentId != row.parentId ||
                        actual.outgoingId != row.outgoingId || !actual.payload.contentEquals(row.payload) ||
                        !actual.text.contentEquals(row.text)
                    ) corrupt()
                    commitAttempted = true
                    result(state.storeId, row, replay = false)
                }
            }
        } catch (error: StoreFailure) {
            if (commitAttempted) reconcile(post, payloadBytes, replyBytes, proposedId)
            else StageResult.Rejected(error.reason)
        } catch (_: SQLiteDatabaseCorruptException) {
            if (commitAttempted) reconcile(post, payloadBytes, replyBytes, proposedId)
            else StageResult.Rejected(Failure.CORRUPT_STORAGE)
        } catch (_: SQLiteException) {
            if (commitAttempted) reconcile(post, payloadBytes, replyBytes, proposedId)
            else StageResult.Rejected(Failure.STORAGE_UNAVAILABLE)
        } catch (_: SecurityException) {
            if (commitAttempted) reconcile(post, payloadBytes, replyBytes, proposedId)
            else StageResult.Rejected(Failure.STORAGE_UNAVAILABLE)
        }
    }

    /** Single read transaction: never a mixed or silently truncated inventory. */
    suspend fun read(): ReadResult = withContext(Dispatchers.IO) {
        if (!limits.isValid()) return@withContext ReadResult.Failed(Failure.INVALID_LIMITS)
        try {
            database { db ->
                transaction(db) {
                    val state = load(db)
                    ReadResult.Inventory(state.rows.map { row ->
                        val identity = RecordIdentity(state.storeId, row.recordId, row.sequence)
                        if (row.parentId == null) {
                            RecoveryEntry.StagedReply(
                                identity, row.post, decodeText(row.text),
                                decodePayload(row.payload), requireNotNull(row.outgoingId),
                            )
                        } else {
                            RecoveryEntry.UnboundConflict(identity, row.post, decodeText(row.text))
                        }
                    })
                }
            }
        } catch (error: StoreFailure) {
            ReadResult.Failed(error.reason)
        } catch (_: SQLiteDatabaseCorruptException) {
            ReadResult.Failed(Failure.CORRUPT_STORAGE)
        } catch (_: SQLiteException) {
            ReadResult.Failed(Failure.STORAGE_UNAVAILABLE)
        } catch (_: SecurityException) {
            ReadResult.Failed(Failure.STORAGE_UNAVAILABLE)
        }
    }

    private fun reconcile(
        post: NotificationReplyPost,
        payload: ByteArray,
        text: ByteArray,
        proposedId: UUID,
    ): StageResult = try {
        database { db ->
            transaction(db) {
                val state = load(db)
                val exact = state.rows.firstOrNull {
                    it.post == post && it.payload.contentEquals(payload) && it.text.contentEquals(text)
                }
                if (exact == null) StageResult.Indeterminate(post, proposedId)
                else result(state.storeId, exact, replay = true)
            }
        }
    } catch (_: StoreFailure) {
        StageResult.Indeterminate(post, proposedId)
    } catch (_: SQLiteException) {
        StageResult.Indeterminate(post, proposedId)
    } catch (_: SecurityException) {
        StageResult.Indeterminate(post, proposedId)
    }

    private fun result(storeId: UUID, row: Row, replay: Boolean): StageResult {
        val identity = RecordIdentity(storeId, row.recordId, row.sequence)
        return if (row.parentId != null) StageResult.ConflictRetained(identity, replay)
        else if (replay) StageResult.AlreadyStaged(identity, requireNotNull(row.outgoingId))
        else StageResult.Staged(identity, requireNotNull(row.outgoingId))
    }

    private fun load(db: SQLiteDatabase): State {
        if (db.version != 1 || userTables(db) != setOf("reply_metadata", "reply_records")) corrupt()
        checkColumns(db, "reply_metadata", listOf(
            "singleton", "store_id", "record_count", "content_bytes", "max_records",
            "max_record_bytes", "max_total_bytes",
        ))
        checkColumns(db, "reply_records", listOf(
            "sequence", "record_id", "post_id", "version", "action", "parent_id", "outgoing_id",
            "payload", "reply_text", "content_bytes", "digest",
        ))
        val state = db.rawQuery("SELECT * FROM reply_metadata", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getLong(0) != 1L) corrupt()
            if ((0..6).any {
                cursor.getType(it) != if (it == 1) Cursor.FIELD_TYPE_STRING else Cursor.FIELD_TYPE_INTEGER
            }) corrupt()
            val storeId = uuid(cursor.getString(1))
            val count = cursor.getLong(2)
            val bytes = cursor.getLong(3)
            val storedLimits = NotificationReplyLimits(exactInt(cursor, 4), cursor.getLong(5), cursor.getLong(6))
            if (!storedLimits.isValid() || count !in 0L..storedLimits.maxRecords.toLong() ||
                bytes !in 0L..storedLimits.maxTotalRecordBytes || cursor.moveToNext()
            ) corrupt()
            if (storedLimits != limits) throw StoreFailure(Failure.LIMITS_MISMATCH)
            State(storeId, count, bytes, mutableListOf())
        }
        var totalBytes = 0L
        val roots = mutableMapOf<NotificationReplyPost, Row>()
        val recordIds = mutableSetOf<UUID>()
        val outgoingIds = mutableSetOf<UUID>()
        db.rawQuery("SELECT * FROM reply_records ORDER BY sequence", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (state.rows.size >= limits.maxRecords) corrupt()
                if ((0..10).any { it != 5 && it != 6 && cursor.isNull(it) }) corrupt()
                if (listOf(0, 3, 9).any { cursor.getType(it) != Cursor.FIELD_TYPE_INTEGER } ||
                    listOf(1, 2, 4).any { cursor.getType(it) != Cursor.FIELD_TYPE_STRING } ||
                    listOf(7, 8, 10).any { cursor.getType(it) != Cursor.FIELD_TYPE_BLOB } ||
                    listOf(5, 6).any { !cursor.isNull(it) && cursor.getType(it) != Cursor.FIELD_TYPE_STRING }
                ) corrupt()
                val row = Row(
                    cursor.getLong(0), uuid(cursor.getString(1)),
                    NotificationReplyPost(exactInt(cursor, 3), uuid(cursor.getString(2)), cursor.getString(4)),
                    if (cursor.isNull(5)) null else uuid(cursor.getString(5)),
                    if (cursor.isNull(6)) null else uuid(cursor.getString(6)),
                    cursor.getBlob(7), cursor.getBlob(8),
                )
                if (row.sequence != state.rows.size.toLong() + 1 || !row.post.isValid() ||
                    !recordIds.add(row.recordId) || row.text.isEmpty() || row.text.size % 2 != 0 ||
                    row.contentBytes != cursor.getLong(9) || row.contentBytes > limits.maxRecordBytes ||
                    row.contentBytes > limits.maxTotalRecordBytes - totalBytes ||
                    !row.digest(state.storeId).contentEquals(cursor.getBlob(10))
                ) corrupt()
                decodePayload(row.payload)
                val root = roots[row.post]
                if (row.parentId == null) {
                    if (root != null || row.outgoingId == null || !outgoingIds.add(row.outgoingId)) corrupt()
                    roots[row.post] = row
                } else {
                    if (root == null || row.parentId != root.recordId || row.outgoingId != null ||
                        state.rows.any { it.post == row.post && it.payload.contentEquals(row.payload) &&
                            it.text.contentEquals(row.text) }
                    ) corrupt()
                }
                state.rows.add(row)
                totalBytes += row.contentBytes
            }
        }
        if (state.count != state.rows.size.toLong() || state.contentBytes != totalBytes) corrupt()
        return state
    }

    private fun decodePayload(bytes: ByteArray): NotificationPayload {
        val payload = NotificationPayloadCodec.decode(decodeText(bytes)) ?: corrupt()
        if (payload !is NotificationPayload.DirectMessage && payload !is NotificationPayload.ChannelMessage) corrupt()
        return payload
    }

    private fun <T> database(allowCreate: Boolean = false, block: (SQLiteDatabase) -> T): T {
        if (!databaseFile.exists() && !allowCreate) throw StoreFailure(Failure.MISSING_STORAGE)
        return SQLiteDatabase.openDatabase(
            databaseFile.absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS or
                (if (allowCreate) SQLiteDatabase.CREATE_IF_NECESSARY else 0),
            { throw SQLiteDatabaseCorruptException("Reply staging corruption; files preserved") },
        ).use { db ->
            db.execSQL("PRAGMA synchronous=FULL")
            block(db)
        }
    }

    private fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun userTables(db: SQLiteDatabase): Set<String> =
        db.rawQuery(
            """SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'
               AND name != 'android_metadata'""", null,
        ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun hasUserSchema(db: SQLiteDatabase): Boolean =
        db.rawQuery(
            """SELECT 1 FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'
               AND NOT(type='table' AND name='android_metadata') LIMIT 1""", null,
        ).use { it.moveToFirst() }

    private fun checkColumns(db: SQLiteDatabase, table: String, expected: List<String>) {
        val actual = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        if (actual != expected) corrupt()
    }

    private class StoreFailure(val reason: Failure) : RuntimeException()
    private fun corrupt(): Nothing = throw StoreFailure(Failure.CORRUPT_STORAGE)
    private fun exactInt(cursor: Cursor, column: Int): Int {
        val value = cursor.getLong(column)
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) corrupt()
        return value.toInt()
    }

    private fun uuid(value: String): UUID = try {
        UUID.fromString(value).also { if (it.toString() != value) corrupt() }
    } catch (_: IllegalArgumentException) {
        corrupt()
    }

    private class State(val storeId: UUID, val count: Long, val contentBytes: Long, val rows: MutableList<Row>)

    private class Row(
        val sequence: Long,
        val recordId: UUID,
        val post: NotificationReplyPost,
        val parentId: UUID?,
        val outgoingId: UUID?,
        val payload: ByteArray,
        val text: ByteArray,
    ) {
        private fun header(): ByteArray = textBytes(
            "$sequence|$recordId|${post.version}|${post.postId}|${post.action}|$parentId|$outgoingId",
        )
        val contentBytes: Long get() = header().size.toLong() + payload.size + text.size
        fun digest(storeId: UUID): ByteArray = MessageDigest.getInstance("SHA-256").run {
            update(textBytes(storeId.toString()))
            update(header())
            update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(payload.size.toLong()).array())
            update(payload)
            update(text)
            digest()
        }
    }

    companion object {
        // Encode Java UTF-16 code units, including NUL and isolated surrogates, without replacement.
        private fun textBytes(value: String): ByteArray =
            ByteBuffer.allocate(Math.multiplyExact(value.length, Char.SIZE_BYTES)).apply {
                value.forEach { putChar(it) }
            }.array()

        private fun decodeText(value: ByteArray): String {
            if (value.size % Char.SIZE_BYTES != 0) throw StoreFailure(Failure.CORRUPT_STORAGE)
            val buffer = ByteBuffer.wrap(value)
            return CharArray(value.size / Char.SIZE_BYTES) { buffer.char }.concatToString()
        }
    }
}
