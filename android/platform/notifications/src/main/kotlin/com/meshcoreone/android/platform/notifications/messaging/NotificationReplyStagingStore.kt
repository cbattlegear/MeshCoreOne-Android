// AndroidOnly: WP-401 Unactivated reply intake and conservative durable custody; never dispatch authority.
package com.meshcoreone.android.platform.notifications.messaging

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.model.RadioId
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Structural post identity, NOT authentication or RF-origin proof. The future ingress owner must
 * bind the private PendingIntent to durable issuance before staging. Display targets are not send authority.
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
    private val afterIssuanceCommit: (() -> Unit)? = null,
    private val afterAdmissionCommit: (() -> Unit)? = null,
    private val afterLifecycleCommit: (() -> Unit)? = null,
) {
    constructor(context: Context, limits: NotificationReplyLimits) : this(
        File(context.applicationContext.noBackupFilesDir, "notification-reply-staging.db"), limits,
    )

    data class RecordIdentity(val storeId: UUID, val recordId: UUID, val sequence: Long)

    data class IssuedAction(
        val token: UUID,
        val post: NotificationReplyPost,
        val logicalNotificationId: NotificationId,
        val payload: NotificationPayload,
    )

    data class IssuedRecord(val storeId: UUID, val sequence: Long, val action: IssuedAction)

    /** Separate immutable caller-selected policy, with no expiry, eviction or production defaults. */
    data class IssuanceLimits(val maxRecords: Int, val maxRecordBytes: Long, val maxTotalRecordBytes: Long) {
        internal fun isValid(): Boolean =
            maxRecords > 0 && maxRecordBytes > 0 && maxTotalRecordBytes >= maxRecordBytes
    }

    sealed interface IssuanceResult {
        data class Reserved(val record: IssuedRecord) : IssuanceResult
        data class AlreadyReserved(val record: IssuedRecord) : IssuanceResult
        /** No reservation was retained by this call. */
        data class Rejected(val reason: Failure) : IssuanceResult
        /** Reservation may exist; do not expose a PendingIntent or allocate another token. */
        data class Indeterminate(val token: UUID) : IssuanceResult
    }

    enum class Custody { UNCLAIMED, UNBOUND, CLAIMED_POSSIBLE_SEND, INDETERMINATE }
    enum class Uncertainty { OWNER_REPORTED, RECOVERY, CANCELLED, OUTCOME_UNKNOWN }

    /**
     * A durable custody receipt, not a radio receipt. Revision and both caller-chosen UUIDs fence
     * journal mutations only. Even a fresh claim supplies no target authority or dispatch permission.
     */
    data class CustodyReceipt(
        val identity: RecordIdentity,
        val revision: Long,
        val state: Custody,
        val claimId: UUID?,
        val ownerId: UUID?,
        val uncertainty: Uncertainty?,
    )

    sealed interface CustodyResult {
        data class NewlyClaimed(val receipt: CustodyReceipt) : CustodyResult
        data class Observed(val receipt: CustodyReceipt) : CustodyResult
        data class SettledIndeterminate(val receipt: CustodyReceipt) : CustodyResult
        data class Fenced(val current: CustodyReceipt) : CustodyResult
        data class Failed(val reason: Failure) : CustodyResult
        /** The operation may have committed; never mint a replacement claim or infer unsent. */
        data class Indeterminate(val identity: RecordIdentity) : CustodyResult
    }

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
        val issuedToken: UUID?
        val targetAuthority: TargetAuthority get() = TargetAuthority.UNPROVED

        class StagedReply internal constructor(
            override val identity: RecordIdentity,
            override val post: NotificationReplyPost,
            override val exactText: String,
            val payload: NotificationPayload,
            val outgoingMessageId: UUID,
            val custody: CustodyReceipt?,
            override val issuedToken: UUID?,
        ) : RecoveryEntry

        class UnboundConflict internal constructor(
            override val identity: RecordIdentity,
            override val post: NotificationReplyPost,
            override val exactText: String,
            val custody: CustodyReceipt?,
            override val issuedToken: UUID?,
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

    /**
     * Absence, including absence of an external expectation, is never fresh-install proof.
     * Expected store IDs must come from independently retained provisioning/issuance state,
     * not from inspecting this file and adopting whatever identity happens to be present.
     */
    sealed interface InstallationResult {
        data object UnprovisionedOrLost : InstallationResult
        data class MissingInitializedStorage(val expectedStoreId: UUID) : InstallationResult
        data class UnboundExisting(val observedStoreId: UUID) : InstallationResult
        data class IdentityMismatch(val expectedStoreId: UUID, val observedStoreId: UUID) : InstallationResult
        data class Ready(val storeId: UUID, val schemaVersion: Int, val retainedRecords: Long) : InstallationResult
        data class Failed(val reason: Failure) : InstallationResult
    }

    enum class Failure {
        INVALID_IDENTITY, INVALID_PAYLOAD, INVALID_TEXT, INVALID_LIMITS, LIMITS_MISMATCH,
        RECORD_CAPACITY, PRIVATE_DATA_CAPACITY, MISSING_STORAGE, STORAGE_UNAVAILABLE, CORRUPT_STORAGE,
        LIFECYCLE_NOT_INITIALIZED, LIFECYCLE_CAPACITY, INVALID_LIFECYCLE_BUDGET, RECORD_NOT_FOUND,
        IDENTITY_MISMATCH,
        ISSUANCE_NOT_INITIALIZED, INVALID_ISSUANCE_LIMITS, ISSUANCE_RECORD_CAPACITY,
        ISSUANCE_PRIVATE_DATA_CAPACITY, ISSUANCE_CONFLICT, ISSUED_TOKEN_NOT_FOUND,
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
    ): StageResult = stageBound(null, post, payload, exactText)

    /**
     * Legacy storage-only admission, NOT authenticated ingress or dispatch permission. It does not
     * resolve issuance; the issued-action adapter must use admitIssued instead of lookup + admit.
     * Externally retained store identity is checked in the same transaction as byte retention.
     * Rejected means not retained; Indeterminate never authorizes queue retirement or retransmission.
     */
    suspend fun admit(
        expectedStoreId: UUID,
        post: NotificationReplyPost,
        issuedPayload: NotificationPayload,
        exactText: String,
    ): StageResult = stageBound(expectedStoreId, post, issuedPayload, exactText)

    /** Resolve immutable issuance inside the reply transaction; incoming extras supply no payload. */
    suspend fun admitIssued(expectedStoreId: UUID, issuedToken: UUID, exactText: String): StageResult =
        stageBound(expectedStoreId, null, null, exactText, issuedToken)

    suspend fun inspectInstallation(expectedStoreId: UUID?): InstallationResult = withContext(Dispatchers.IO) {
        if (!limits.isValid()) return@withContext InstallationResult.Failed(Failure.INVALID_LIMITS)
        try {
            database { db ->
                transaction(db) {
                    val state = load(db)
                    when {
                        expectedStoreId == null -> InstallationResult.UnboundExisting(state.storeId)
                        expectedStoreId != state.storeId ->
                            InstallationResult.IdentityMismatch(expectedStoreId, state.storeId)
                        else -> InstallationResult.Ready(state.storeId, db.version, state.count)
                    }
                }
            }
        } catch (error: StoreFailure) {
            if (error.reason == Failure.MISSING_STORAGE) {
                if (expectedStoreId == null) InstallationResult.UnprovisionedOrLost
                else InstallationResult.MissingInitializedStorage(expectedStoreId)
            } else InstallationResult.Failed(error.reason)
        } catch (_: SQLiteDatabaseCorruptException) {
            InstallationResult.Failed(Failure.CORRUPT_STORAGE)
        } catch (_: SQLiteException) {
            InstallationResult.Failed(Failure.STORAGE_UNAVAILABLE)
        } catch (_: SecurityException) {
            InstallationResult.Failed(Failure.STORAGE_UNAVAILABLE)
        }
    }

    private suspend fun stageBound(
        expectedStoreId: UUID?,
        suppliedPost: NotificationReplyPost?,
        suppliedPayload: NotificationPayload?,
        exactText: String,
        issuedToken: UUID? = null,
    ): StageResult = withContext(Dispatchers.IO) {
        if (!limits.isValid()) return@withContext StageResult.Rejected(Failure.INVALID_LIMITS)
        if (issuedToken == null && suppliedPost?.isValid() != true) {
            return@withContext StageResult.Rejected(Failure.INVALID_IDENTITY)
        }
        if (exactText.isEmpty()) return@withContext StageResult.Rejected(Failure.INVALID_TEXT)
        if (issuedToken == null) {
            val encoded = suppliedPayload?.let(ReplyRoutingCodec::encode)
                ?: return@withContext StageResult.Rejected(Failure.INVALID_PAYLOAD)
            if (ReplyRoutingCodec.decode(encoded) != suppliedPayload) {
                return@withContext StageResult.Rejected(Failure.INVALID_PAYLOAD)
            }
            if (encoded.length.toLong() * Char.SIZE_BYTES + exactText.length.toLong() * Char.SIZE_BYTES >
                limits.maxRecordBytes || encoded.length > Int.MAX_VALUE / Char.SIZE_BYTES
            ) return@withContext StageResult.Rejected(Failure.PRIVATE_DATA_CAPACITY)
        }
        if (exactText.length.toLong() * Char.SIZE_BYTES > limits.maxRecordBytes ||
            exactText.length > Int.MAX_VALUE / Char.SIZE_BYTES
        ) return@withContext StageResult.Rejected(Failure.PRIVATE_DATA_CAPACITY)
        val replyBytes = textBytes(exactText)
        val proposedId = UUID.randomUUID()
        var resolvedPost: NotificationReplyPost? = null
        var resolvedPayload: ByteArray? = null
        var commitAttempted = false
        fun reconcileCommitted(): StageResult = reconcile(
            expectedStoreId, requireNotNull(resolvedPost), requireNotNull(resolvedPayload),
            replyBytes, proposedId, issuedToken,
        )
        try {
            val result = database { db ->
                transaction(db) {
                    val state = load(db)
                    if (expectedStoreId != null && state.storeId != expectedStoreId) {
                        return@transaction StageResult.Rejected(Failure.IDENTITY_MISMATCH)
                    }
                    val issued = if (issuedToken == null) null else {
                        if (db.version < 3) {
                            return@transaction StageResult.Rejected(Failure.ISSUANCE_NOT_INITIALIZED)
                        }
                        readIssuance(db, state.storeId).records.firstOrNull { it.action.token == issuedToken }
                            ?: return@transaction StageResult.Rejected(Failure.ISSUED_TOKEN_NOT_FOUND)
                    }
                    val post = issued?.action?.post ?: suppliedPost
                        ?: return@transaction StageResult.Rejected(Failure.INVALID_IDENTITY)
                    val payload = issued?.action?.payload ?: suppliedPayload
                        ?: return@transaction StageResult.Rejected(Failure.INVALID_PAYLOAD)
                    val encoded = ReplyRoutingCodec.encode(payload)
                        ?: return@transaction StageResult.Rejected(Failure.INVALID_PAYLOAD)
                    if (ReplyRoutingCodec.decode(encoded) != payload) {
                        return@transaction StageResult.Rejected(Failure.INVALID_PAYLOAD)
                    }
                    val payloadBytes = textBytes(encoded)
                    resolvedPost = post
                    resolvedPayload = payloadBytes
                    val prior = state.rows.filter { it.post == post && it.issuedToken == issuedToken }
                    val identical = prior.firstOrNull {
                        it.payload.contentEquals(payloadBytes) && it.text.contentEquals(replyBytes)
                    }
                    if (identical != null) return@transaction result(state.storeId, identical, replay = true)
                    val first = prior.firstOrNull { it.parentId == null }
                    val row = Row(
                        state.rows.size.toLong() + 1, proposedId, post, first?.recordId,
                        if (first == null) UUID.randomUUID() else null, payloadBytes, replyBytes, issuedToken,
                    )
                    if (row.contentBytes > limits.maxRecordBytes ||
                        row.contentBytes > limits.maxTotalRecordBytes - state.contentBytes
                    ) return@transaction StageResult.Rejected(Failure.PRIVATE_DATA_CAPACITY)
                    if (state.rows.size >= limits.maxRecords) {
                        return@transaction StageResult.Rejected(Failure.RECORD_CAPACITY)
                    }
                    val values = arrayOf<Any?>(
                        row.sequence, row.recordId.toString(), post.postId.toString(), post.version,
                        post.action, row.parentId?.toString(), row.outgoingId?.toString(),
                        row.payload, row.text, row.contentBytes, row.digest(state.storeId),
                    )
                    db.execSQL(
                        if (db.version >= 3) "INSERT INTO reply_records VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        else "INSERT INTO reply_records VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        if (db.version >= 3) values + issuedToken?.toString() else values,
                    )
                    db.execSQL(
                        "UPDATE reply_metadata SET record_count=?, content_bytes=? WHERE singleton=1",
                        arrayOf<Any>(state.rows.size + 1, state.contentBytes + row.contentBytes),
                    )
                    if (db.version >= 2) {
                        val receipt = initialCustody(state.storeId, row)
                        val before = readCustody(db, state)
                        writeCustody(db, receipt, insert = true)
                        checkCustodyBudget(db, before.values + receipt)
                    }
                    val retained = load(db)
                    val actual = retained.rows.lastOrNull() ?: corrupt()
                    if (retained.storeId != state.storeId || retained.rows.size != state.rows.size + 1 ||
                        actual.sequence != row.sequence || actual.recordId != row.recordId ||
                        actual.post != row.post || actual.parentId != row.parentId ||
                        actual.issuedToken != row.issuedToken ||
                        actual.outgoingId != row.outgoingId || !actual.payload.contentEquals(row.payload) ||
                        !actual.text.contentEquals(row.text)
                    ) corrupt()
                    commitAttempted = true
                    result(state.storeId, row, replay = false)
                }
            }
            if (commitAttempted) afterAdmissionCommit?.invoke()
            result
        } catch (error: StoreFailure) {
            if (commitAttempted) reconcileCommitted()
            else StageResult.Rejected(error.reason)
        } catch (_: SQLiteDatabaseCorruptException) {
            if (commitAttempted) reconcileCommitted()
            else StageResult.Rejected(Failure.CORRUPT_STORAGE)
        } catch (_: SQLiteException) {
            if (commitAttempted) reconcileCommitted()
            else StageResult.Rejected(Failure.STORAGE_UNAVAILABLE)
        } catch (_: SecurityException) {
            if (commitAttempted) reconcileCommitted()
            else StageResult.Rejected(Failure.STORAGE_UNAVAILABLE)
        }
    }

    /** Single read transaction: never a mixed or silently truncated inventory. */
    suspend fun read(): ReadResult = readBound(null)

    /** Read-only recovery view bound to externally retained installation identity; never adopts a replacement. */
    suspend fun read(expectedStoreId: UUID): ReadResult = readBound(expectedStoreId)

    private suspend fun readBound(expectedStoreId: UUID?): ReadResult = withContext(Dispatchers.IO) {
        if (!limits.isValid()) return@withContext ReadResult.Failed(Failure.INVALID_LIMITS)
        try {
            database { db ->
                transaction(db) {
                    val state = load(db)
                    if (expectedStoreId != null && expectedStoreId != state.storeId) {
                        return@transaction ReadResult.Failed(Failure.IDENTITY_MISMATCH)
                    }
                    val custody = if (db.version >= 2) readCustody(db, state) else emptyMap()
                    ReadResult.Inventory(state.rows.map { row ->
                        val identity = RecordIdentity(state.storeId, row.recordId, row.sequence)
                        if (row.parentId == null) {
                            RecoveryEntry.StagedReply(
                                identity, row.post, decodeText(row.text),
                                decodePayload(row.payload), requireNotNull(row.outgoingId),
                                custody[row.recordId], row.issuedToken,
                            )
                        } else {
                            RecoveryEntry.UnboundConflict(
                                identity, row.post, decodeText(row.text), custody[row.recordId], row.issuedToken,
                            )
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
        expectedStoreId: UUID?,
        post: NotificationReplyPost,
        payload: ByteArray,
        text: ByteArray,
        proposedId: UUID,
        issuedToken: UUID? = null,
    ): StageResult = try {
        database { db ->
            transaction(db) {
                val state = load(db)
                if (expectedStoreId != null && state.storeId != expectedStoreId) {
                    return@transaction StageResult.Indeterminate(post, proposedId)
                }
                val exact = state.rows.firstOrNull {
                    it.post == post && it.issuedToken == issuedToken &&
                        it.payload.contentEquals(payload) && it.text.contentEquals(text)
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
        val expected = when (db.version) {
            1 -> setOf("reply_metadata", "reply_records")
            2 -> setOf("reply_metadata", "reply_records", "reply_custody", "reply_custody_policy")
            3 -> setOf("reply_metadata", "reply_records", "reply_custody", "reply_custody_policy",
                "reply_issued_actions", "reply_issuance_policy")
            else -> corrupt()
        }
        if (userTables(db) != expected) corrupt()
        checkColumns(db, "reply_metadata", listOf(
            "singleton", "store_id", "record_count", "content_bytes", "max_records",
            "max_record_bytes", "max_total_bytes",
        ))
        checkColumns(db, "reply_records", listOf(
            "sequence", "record_id", "post_id", "version", "action", "parent_id", "outgoing_id",
            "payload", "reply_text", "content_bytes", "digest",
        ) + if (db.version >= 3) listOf("issued_token") else emptyList())
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
        val roots = mutableMapOf<Pair<NotificationReplyPost, UUID?>, Row>()
        val recordIds = mutableSetOf<UUID>()
        val outgoingIds = mutableSetOf<UUID>()
        val issuance = if (db.version >= 3) readIssuance(db, state.storeId).records.associateBy { it.action.token }
        else emptyMap()
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
                    if (db.version < 3 || cursor.isNull(11)) null else {
                        if (cursor.getType(11) != Cursor.FIELD_TYPE_STRING) corrupt()
                        uuid(cursor.getString(11))
                    },
                )
                if (row.sequence != state.rows.size.toLong() + 1 || !row.post.isValid() ||
                    !recordIds.add(row.recordId) || row.text.isEmpty() || row.text.size % 2 != 0 ||
                    row.contentBytes != cursor.getLong(9) || row.contentBytes > limits.maxRecordBytes ||
                    row.contentBytes > limits.maxTotalRecordBytes - totalBytes ||
                    !row.digest(state.storeId).contentEquals(cursor.getBlob(10))
                ) corrupt()
                decodePayload(row.payload)
                if (row.issuedToken != null) {
                    val issued = issuance[row.issuedToken]?.action ?: corrupt()
                    if (issued.post != row.post || issued.payload != decodePayload(row.payload)) corrupt()
                }
                val key = row.post to row.issuedToken
                val root = roots[key]
                if (row.parentId == null) {
                    if (root != null || row.outgoingId == null || !outgoingIds.add(row.outgoingId)) corrupt()
                    roots[key] = row
                } else {
                    if (root == null || row.parentId != root.recordId || row.outgoingId != null ||
                        state.rows.any { it.post == row.post && it.issuedToken == row.issuedToken &&
                            it.payload.contentEquals(row.payload) &&
                            it.text.contentEquals(row.text) }
                    ) corrupt()
                }
                state.rows.add(row)
                totalBytes += row.contentBytes
            }
        }
        if (state.count != state.rows.size.toLong() || state.contentBytes != totalBytes) corrupt()
        if (db.version >= 2) readCustody(db, state)
        return state
    }

    /**
     * Explicit v2 -> v3 migration of an existing externally bound installation. This supplies
     * durable app-issued token binding, not first-install proof, human authentication or RF proof.
     */
    suspend fun initializeIssuance(expectedStoreId: UUID, policy: IssuanceLimits): InitializeResult =
        withContext(Dispatchers.IO) {
            if (!limits.isValid()) return@withContext InitializeResult.Failed(Failure.INVALID_LIMITS)
            if (!policy.isValid()) return@withContext InitializeResult.Failed(Failure.INVALID_ISSUANCE_LIMITS)
            var commitAttempted = false
            try {
                database { db ->
                    transaction(db) {
                        val state = load(db)
                        if (state.storeId != expectedStoreId) throw StoreFailure(Failure.IDENTITY_MISMATCH)
                        if (db.version < 2) throw StoreFailure(Failure.LIFECYCLE_NOT_INITIALIZED)
                        if (db.version == 2) {
                            db.execSQL(
                                """CREATE TABLE reply_issuance_policy(
                                   singleton INTEGER PRIMARY KEY CHECK(singleton=1), record_count INTEGER NOT NULL,
                                   content_bytes INTEGER NOT NULL, max_records INTEGER NOT NULL,
                                   max_record_bytes INTEGER NOT NULL, max_total_bytes INTEGER NOT NULL,
                                   digest BLOB NOT NULL)""",
                            )
                            db.execSQL(
                                "INSERT INTO reply_issuance_policy VALUES(1,0,0,?,?,?,?)",
                                arrayOf<Any>(policy.maxRecords, policy.maxRecordBytes, policy.maxTotalRecordBytes,
                                    issuancePolicyDigest(state.storeId, policy, 0, 0)),
                            )
                            db.execSQL(
                                """CREATE TABLE reply_issued_actions(
                                   sequence INTEGER PRIMARY KEY, token TEXT UNIQUE NOT NULL,
                                   post_id TEXT NOT NULL, version INTEGER NOT NULL, action TEXT NOT NULL,
                                   logical_id BLOB NOT NULL, payload BLOB NOT NULL,
                                   content_bytes INTEGER NOT NULL, digest BLOB NOT NULL)""",
                            )
                            db.execSQL("ALTER TABLE reply_records ADD COLUMN issued_token TEXT")
                            db.version = 3
                        }
                        if (readIssuance(db, state.storeId).policy != policy) {
                            throw StoreFailure(Failure.LIMITS_MISMATCH)
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

    /**
     * Commit before exposing any PendingIntent. Exact token replay is idempotent; a differing
     * proposal never overwrites it. Issuance reserves registry bytes, not future reply capacity.
     */
    suspend fun registerIssued(expectedStoreId: UUID, proposal: IssuedAction): IssuanceResult =
        withContext(Dispatchers.IO) {
            if (!limits.isValid()) return@withContext IssuanceResult.Rejected(Failure.INVALID_LIMITS)
            if (!proposal.post.isValid() || proposal.logicalNotificationId.value.isEmpty()) {
                return@withContext IssuanceResult.Rejected(Failure.INVALID_IDENTITY)
            }
            val encoded = ReplyRoutingCodec.encode(proposal.payload)
                ?: return@withContext IssuanceResult.Rejected(Failure.INVALID_PAYLOAD)
            if (ReplyRoutingCodec.decode(encoded) != proposal.payload) {
                return@withContext IssuanceResult.Rejected(Failure.INVALID_PAYLOAD)
            }
            if (proposal.logicalNotificationId.value.length > Int.MAX_VALUE / Char.SIZE_BYTES) {
                return@withContext IssuanceResult.Rejected(Failure.ISSUANCE_PRIVATE_DATA_CAPACITY)
            }
            var commitAttempted = false
            try {
                val result = database { db ->
                    transaction(db) {
                        val state = load(db)
                        if (state.storeId != expectedStoreId) {
                            return@transaction IssuanceResult.Rejected(Failure.IDENTITY_MISMATCH)
                        }
                        if (db.version < 3) {
                            return@transaction IssuanceResult.Rejected(Failure.ISSUANCE_NOT_INITIALIZED)
                        }
                        val issued = readIssuance(db, state.storeId)
                        val prior = issued.records.firstOrNull { it.action.token == proposal.token }
                        if (prior != null) {
                            return@transaction if (prior.action == proposal) IssuanceResult.AlreadyReserved(prior)
                            else IssuanceResult.Rejected(Failure.ISSUANCE_CONFLICT)
                        }
                        val record = IssuedRecord(state.storeId, issued.records.size.toLong() + 1, proposal)
                        val bytes = issuanceContentBytes(record)
                        if (bytes > issued.policy.maxRecordBytes ||
                            bytes > issued.policy.maxTotalRecordBytes - issued.contentBytes
                        ) return@transaction IssuanceResult.Rejected(Failure.ISSUANCE_PRIVATE_DATA_CAPACITY)
                        if (issued.records.size >= issued.policy.maxRecords) {
                            return@transaction IssuanceResult.Rejected(Failure.ISSUANCE_RECORD_CAPACITY)
                        }
                        db.execSQL(
                            "INSERT INTO reply_issued_actions VALUES(?,?,?,?,?,?,?,?,?)",
                            arrayOf<Any>(record.sequence, proposal.token.toString(), proposal.post.postId.toString(),
                                proposal.post.version, proposal.post.action,
                                textBytes(proposal.logicalNotificationId.value), textBytes(encoded), bytes,
                                issuanceDigest(record)),
                        )
                        val count = issued.records.size.toLong() + 1
                        val total = issued.contentBytes + bytes
                        db.execSQL(
                            "UPDATE reply_issuance_policy SET record_count=?,content_bytes=?,digest=? WHERE singleton=1",
                            arrayOf<Any>(count, total, issuancePolicyDigest(state.storeId, issued.policy, count, total)),
                        )
                        load(db)
                        val retained = readIssuance(db, state.storeId)
                        if (retained.records.size.toLong() != count || retained.records.lastOrNull() != record) corrupt()
                        commitAttempted = true
                        IssuanceResult.Reserved(record)
                    }
                }
                if (commitAttempted) afterIssuanceCommit?.invoke()
                result
            } catch (error: StoreFailure) {
                if (commitAttempted) reconcileIssuance(expectedStoreId, proposal)
                else IssuanceResult.Rejected(error.reason)
            } catch (_: SQLiteDatabaseCorruptException) {
                if (commitAttempted) reconcileIssuance(expectedStoreId, proposal)
                else IssuanceResult.Rejected(Failure.CORRUPT_STORAGE)
            } catch (_: SQLiteException) {
                if (commitAttempted) reconcileIssuance(expectedStoreId, proposal)
                else IssuanceResult.Rejected(Failure.STORAGE_UNAVAILABLE)
            } catch (_: SecurityException) {
                if (commitAttempted) reconcileIssuance(expectedStoreId, proposal)
                else IssuanceResult.Rejected(Failure.STORAGE_UNAVAILABLE)
            }
        }

    private fun reconcileIssuance(expectedStoreId: UUID, proposal: IssuedAction): IssuanceResult = try {
        database { db ->
            transaction(db) {
                val state = load(db)
                val record = if (state.storeId != expectedStoreId || db.version < 3) null
                else readIssuance(db, state.storeId).records.firstOrNull { it.action == proposal }
                if (record == null) IssuanceResult.Indeterminate(proposal.token)
                else IssuanceResult.AlreadyReserved(record)
            }
        }
    } catch (_: StoreFailure) {
        IssuanceResult.Indeterminate(proposal.token)
    } catch (_: SQLiteException) {
        IssuanceResult.Indeterminate(proposal.token)
    } catch (_: SecurityException) {
        IssuanceResult.Indeterminate(proposal.token)
    }

    private class IssuanceState(
        val policy: IssuanceLimits, val contentBytes: Long, val records: List<IssuedRecord>,
    )

    private fun readIssuance(db: SQLiteDatabase, storeId: UUID): IssuanceState {
        checkColumns(db, "reply_issuance_policy", listOf(
            "singleton", "record_count", "content_bytes", "max_records", "max_record_bytes", "max_total_bytes", "digest",
        ))
        checkColumns(db, "reply_issued_actions", listOf(
            "sequence", "token", "post_id", "version", "action", "logical_id", "payload", "content_bytes", "digest",
        ))
        val state = db.rawQuery("SELECT * FROM reply_issuance_policy", null).use { cursor ->
            if (!cursor.moveToFirst() || (0..5).any { cursor.getType(it) != Cursor.FIELD_TYPE_INTEGER } ||
                cursor.getType(6) != Cursor.FIELD_TYPE_BLOB || cursor.getLong(0) != 1L
            ) corrupt()
            val count = cursor.getLong(1)
            val bytes = cursor.getLong(2)
            val policy = IssuanceLimits(exactInt(cursor, 3), cursor.getLong(4), cursor.getLong(5))
            if (!policy.isValid() || count !in 0L..policy.maxRecords.toLong() ||
                bytes !in 0L..policy.maxTotalRecordBytes ||
                !issuancePolicyDigest(storeId, policy, count, bytes).contentEquals(cursor.getBlob(6)) ||
                cursor.moveToNext()
            ) corrupt()
            Triple(policy, count, bytes)
        }
        val records = mutableListOf<IssuedRecord>()
        val tokens = mutableSetOf<UUID>()
        var bytes = 0L
        db.rawQuery("SELECT * FROM reply_issued_actions ORDER BY sequence", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (records.size >= state.first.maxRecords ||
                    listOf(0, 3, 7).any { cursor.getType(it) != Cursor.FIELD_TYPE_INTEGER } ||
                    listOf(1, 2, 4).any { cursor.getType(it) != Cursor.FIELD_TYPE_STRING } ||
                    listOf(5, 6, 8).any { cursor.getType(it) != Cursor.FIELD_TYPE_BLOB }
                ) corrupt()
                val action = IssuedAction(
                    uuid(cursor.getString(1)),
                    NotificationReplyPost(exactInt(cursor, 3), uuid(cursor.getString(2)), cursor.getString(4)),
                    NotificationId(decodeText(cursor.getBlob(5))), decodePayload(cursor.getBlob(6)),
                )
                val record = IssuedRecord(storeId, cursor.getLong(0), action)
                val size = issuanceContentBytes(record)
                if (record.sequence != records.size.toLong() + 1 || !tokens.add(action.token) ||
                    !action.post.isValid() || action.logicalNotificationId.value.isEmpty() ||
                    size != cursor.getLong(7) || size > state.first.maxRecordBytes ||
                    size > state.first.maxTotalRecordBytes - bytes ||
                    !issuanceDigest(record).contentEquals(cursor.getBlob(8))
                ) corrupt()
                bytes += size
                records.add(record)
            }
        }
        if (records.size.toLong() != state.second || bytes != state.third) corrupt()
        return IssuanceState(state.first, bytes, records)
    }

    private fun issuanceHeader(record: IssuedRecord): String =
        "issued-v1|${record.storeId}|${record.sequence}|${record.action.token}|" +
            "${record.action.post.version}|${record.action.post.postId}|${record.action.post.action}"

    private fun issuanceContentBytes(record: IssuedRecord): Long =
        (issuanceHeader(record).length.toLong() + record.action.logicalNotificationId.value.length +
            requireNotNull(ReplyRoutingCodec.encode(record.action.payload)).length) * Char.SIZE_BYTES

    private fun issuanceDigest(record: IssuedRecord): ByteArray = MessageDigest.getInstance("SHA-256").run {
        listOf(issuanceHeader(record), record.action.logicalNotificationId.value,
            requireNotNull(ReplyRoutingCodec.encode(record.action.payload))).forEach { value ->
            update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value.length.toLong() * Char.SIZE_BYTES).array())
            update(textBytes(value))
        }
        digest()
    }

    private fun issuancePolicyDigest(storeId: UUID, policy: IssuanceLimits, count: Long, bytes: Long): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(textBytes(
            "issued-policy-v1|$storeId|$count|$bytes|${policy.maxRecords}|${policy.maxRecordBytes}|${policy.maxTotalRecordBytes}",
        ))

    /**
     * Explicit transactional v1 -> v2 migration. The separate caller-selected byte budget covers
     * serialized custody receipts, not SQLite overhead. Existing intake limits/text never change.
     * No dispatch, accepted or definitely-unsent evidence exists in this schema.
     */
    suspend fun initializeLifecycle(maxTotalReceiptBytes: Long): InitializeResult = withContext(Dispatchers.IO) {
        if (!limits.isValid()) return@withContext InitializeResult.Failed(Failure.INVALID_LIMITS)
        if (maxTotalReceiptBytes <= 0) {
            return@withContext InitializeResult.Failed(Failure.INVALID_LIFECYCLE_BUDGET)
        }
        var commitAttempted = false
        try {
            database { db ->
                transaction(db) {
                    val state = load(db)
                    if (db.version == 1) {
                        db.execSQL(
                            """CREATE TABLE reply_custody_policy(
                               singleton INTEGER PRIMARY KEY CHECK(singleton=1), max_bytes INTEGER NOT NULL,
                               digest BLOB NOT NULL)""",
                        )
                        db.execSQL(
                            "INSERT INTO reply_custody_policy VALUES(1, ?, ?)",
                            arrayOf<Any>(maxTotalReceiptBytes, policyDigest(state.storeId, maxTotalReceiptBytes)),
                        )
                        db.execSQL(
                            """CREATE TABLE reply_custody(
                               record_id TEXT PRIMARY KEY NOT NULL, revision INTEGER NOT NULL,
                               state TEXT NOT NULL, claim_id TEXT UNIQUE, owner_id TEXT,
                               uncertainty TEXT, digest BLOB NOT NULL)""",
                        )
                        state.rows.forEach { writeCustody(db, initialCustody(state.storeId, it), insert = true) }
                        checkCustodyBudget(db, state.rows.map { initialCustody(state.storeId, it) })
                        db.version = 2
                    }
                    if (custodyBudget(db) != maxTotalReceiptBytes) throw StoreFailure(Failure.LIMITS_MISMATCH)
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

    /**
     * Caller must retain claimId/ownerId before calling. Only the first atomic UNCLAIMED transition
     * returns NewlyClaimed. Replays, restarts and other handles only observe/fence the existing claim;
     * there is no timeout, takeover, release or transition back to UNCLAIMED.
     */
    suspend fun claim(
        identity: RecordIdentity,
        claimId: UUID,
        ownerId: UUID,
    ): CustodyResult = mutateCustody(identity) { current, all ->
        if (current.state == Custody.UNCLAIMED) {
            if (all.any { it.claimId == claimId }) return@mutateCustody CustodyResult.Failed(Failure.IDENTITY_MISMATCH)
            CustodyResult.NewlyClaimed(current.copy(
                revision = 1, state = Custody.CLAIMED_POSSIBLE_SEND, claimId = claimId, ownerId = ownerId,
            ))
        } else if (current.claimId == claimId && current.ownerId == ownerId) {
            CustodyResult.Observed(current)
        } else {
            CustodyResult.Fenced(current)
        }
    }

    /**
     * The sole available settlement is conservative uncertainty. Exact revision/claim/owner must
     * match; recovery may use the retained receipt to quarantine an abandoned claim. This cannot
     * cancel an actual in-flight dispatch or justify a retry. Definitive settlement is unavailable.
     */
    suspend fun settleIndeterminate(expected: CustodyReceipt, reason: Uncertainty): CustodyResult =
        mutateCustody(expected.identity) { current, _ ->
            when {
                current == expected && current.state == Custody.CLAIMED_POSSIBLE_SEND -> {
                    CustodyResult.SettledIndeterminate(current.copy(
                        revision = current.revision + 1, state = Custody.INDETERMINATE, uncertainty = reason,
                    ))
                }
                current.state == Custody.INDETERMINATE &&
                    current.claimId == expected.claimId && current.ownerId == expected.ownerId &&
                    expected.state == Custody.CLAIMED_POSSIBLE_SEND &&
                    expected.uncertainty == null &&
                    expected.revision == 1L && current.revision == 2L &&
                    current.uncertainty == reason -> CustodyResult.Observed(current)
                else -> CustodyResult.Fenced(current)
            }
        }

    private suspend fun mutateCustody(
        identity: RecordIdentity,
        operation: (CustodyReceipt, Collection<CustodyReceipt>) -> CustodyResult,
    ): CustodyResult = withContext(Dispatchers.IO) {
        if (!limits.isValid()) return@withContext CustodyResult.Failed(Failure.INVALID_LIMITS)
        var commitAttempted = false
        try {
            val result = database { db ->
                transaction(db) {
                    val state = load(db)
                    if (db.version < 2) return@transaction CustodyResult.Failed(Failure.LIFECYCLE_NOT_INITIALIZED)
                    if (state.storeId != identity.storeId) {
                        return@transaction CustodyResult.Failed(Failure.IDENTITY_MISMATCH)
                    }
                    val all = readCustody(db, state)
                    val current = all[identity.recordId]
                        ?: return@transaction CustodyResult.Failed(Failure.RECORD_NOT_FOUND)
                    if (current.identity != identity) {
                        return@transaction CustodyResult.Failed(Failure.IDENTITY_MISMATCH)
                    }
                    val proposed = operation(current, all.values)
                    val next = when (proposed) {
                        is CustodyResult.NewlyClaimed -> proposed.receipt
                        is CustodyResult.SettledIndeterminate -> proposed.receipt
                        else -> return@transaction proposed
                    }
                    checkCustodyBudget(db, all.values.filter { it.identity != identity } + next)
                    writeCustody(db, next, insert = false)
                    val retained = load(db)
                    if (readCustody(db, retained)[identity.recordId] != next) corrupt()
                    commitAttempted = true
                    proposed
                }
            }
            if (commitAttempted) afterLifecycleCommit?.invoke()
            result
        } catch (error: StoreFailure) {
            if (commitAttempted) CustodyResult.Indeterminate(identity) else CustodyResult.Failed(error.reason)
        } catch (_: SQLiteDatabaseCorruptException) {
            if (commitAttempted) CustodyResult.Indeterminate(identity)
            else CustodyResult.Failed(Failure.CORRUPT_STORAGE)
        } catch (_: SQLiteException) {
            if (commitAttempted) CustodyResult.Indeterminate(identity)
            else CustodyResult.Failed(Failure.STORAGE_UNAVAILABLE)
        } catch (_: SecurityException) {
            if (commitAttempted) CustodyResult.Indeterminate(identity)
            else CustodyResult.Failed(Failure.STORAGE_UNAVAILABLE)
        }
    }

    private fun initialCustody(storeId: UUID, row: Row) = CustodyReceipt(
        RecordIdentity(storeId, row.recordId, row.sequence), 0,
        if (row.parentId == null) Custody.UNCLAIMED else Custody.UNBOUND, null, null, null,
    )

    private fun readCustody(db: SQLiteDatabase, state: State): Map<UUID, CustodyReceipt> {
        checkColumns(db, "reply_custody", listOf(
            "record_id", "revision", "state", "claim_id", "owner_id", "uncertainty", "digest",
        ))
        val rows = state.rows.associateBy { it.recordId }
        val receipts = mutableMapOf<UUID, CustodyReceipt>()
        val claims = mutableSetOf<UUID>()
        db.rawQuery("SELECT * FROM reply_custody", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (receipts.size >= state.rows.size ||
                    listOf(0, 2).any { cursor.getType(it) != Cursor.FIELD_TYPE_STRING } ||
                    cursor.getType(1) != Cursor.FIELD_TYPE_INTEGER ||
                    cursor.getType(6) != Cursor.FIELD_TYPE_BLOB ||
                    listOf(3, 4, 5).any { !cursor.isNull(it) && cursor.getType(it) != Cursor.FIELD_TYPE_STRING }
                ) corrupt()
                val row = rows[uuid(cursor.getString(0))] ?: corrupt()
                val disposition = Custody.entries.firstOrNull { it.name == cursor.getString(2) } ?: corrupt()
                val uncertainty = if (cursor.isNull(5)) null
                else Uncertainty.entries.firstOrNull { it.name == cursor.getString(5) } ?: corrupt()
                val receipt = CustodyReceipt(
                    RecordIdentity(state.storeId, row.recordId, row.sequence), cursor.getLong(1), disposition,
                    if (cursor.isNull(3)) null else uuid(cursor.getString(3)),
                    if (cursor.isNull(4)) null else uuid(cursor.getString(4)), uncertainty,
                )
                val initial = initialCustody(state.storeId, row)
                if (receipt.state == Custody.UNCLAIMED || receipt.state == Custody.UNBOUND) {
                    if (receipt != initial) corrupt()
                } else {
                    if (row.parentId != null || receipt.claimId == null || receipt.ownerId == null ||
                        !claims.add(receipt.claimId) ||
                        receipt.state == Custody.CLAIMED_POSSIBLE_SEND &&
                        (receipt.revision != 1L || receipt.uncertainty != null) ||
                        receipt.state == Custody.INDETERMINATE &&
                        (receipt.revision != 2L || receipt.uncertainty == null)
                    ) corrupt()
                }
                if (!custodyDigest(receipt).contentEquals(cursor.getBlob(6)) ||
                    receipts.put(row.recordId, receipt) != null
                ) corrupt()
            }
        }
        if (receipts.size != state.rows.size) corrupt()
        checkCustodyBudget(db, receipts.values, persisted = true)
        return receipts
    }

    private fun custodyBudget(db: SQLiteDatabase): Long {
        checkColumns(db, "reply_custody_policy", listOf("singleton", "max_bytes", "digest"))
        return db.rawQuery("SELECT * FROM reply_custody_policy", null).use {
            if (!it.moveToFirst() || it.getType(0) != Cursor.FIELD_TYPE_INTEGER ||
                it.getLong(0) != 1L || it.getType(1) != Cursor.FIELD_TYPE_INTEGER ||
                it.getType(2) != Cursor.FIELD_TYPE_BLOB
            ) corrupt()
            val budget = it.getLong(1)
            val storeId = db.rawQuery("SELECT store_id FROM reply_metadata WHERE singleton=1", null).use { metadata ->
                if (!metadata.moveToFirst()) corrupt()
                uuid(metadata.getString(0))
            }
            if (budget <= 0 || !policyDigest(storeId, budget).contentEquals(it.getBlob(2)) || it.moveToNext()) corrupt()
            budget
        }
    }

    private fun checkCustodyBudget(
        db: SQLiteDatabase,
        receipts: Collection<CustodyReceipt>,
        persisted: Boolean = false,
    ) {
        var remaining = custodyBudget(db)
        receipts.forEach {
            val bytes = custodyBytes(it).size.toLong() + 32L
            if (bytes > remaining) {
                if (persisted) corrupt() else throw StoreFailure(Failure.LIFECYCLE_CAPACITY)
            }
            remaining -= bytes
        }
    }

    private fun writeCustody(db: SQLiteDatabase, receipt: CustodyReceipt, insert: Boolean) {
        val values = arrayOf<Any?>(
            receipt.revision, receipt.state.name, receipt.claimId?.toString(),
            receipt.ownerId?.toString(), receipt.uncertainty?.name, custodyDigest(receipt),
            receipt.identity.recordId.toString(),
        )
        if (insert) {
            db.execSQL(
                """INSERT INTO reply_custody(revision,state,claim_id,owner_id,uncertainty,digest,record_id)
                   VALUES(?,?,?,?,?,?,?)""", values,
            )
        } else {
            db.execSQL(
                """UPDATE reply_custody SET revision=?,state=?,claim_id=?,owner_id=?,uncertainty=?,digest=?
                   WHERE record_id=?""", values,
            )
        }
    }

    private fun custodyBytes(receipt: CustodyReceipt): ByteArray = textBytes(
        "${receipt.identity.storeId}|${receipt.identity.recordId}|${receipt.identity.sequence}|" +
            "${receipt.revision}|${receipt.state.name}|${receipt.claimId}|${receipt.ownerId}|${receipt.uncertainty}",
    )

    private fun custodyDigest(receipt: CustodyReceipt): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(custodyBytes(receipt))

    private fun policyDigest(storeId: UUID, budget: Long): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(textBytes("$storeId|$budget"))

    private fun decodePayload(bytes: ByteArray): NotificationPayload {
        val payload = ReplyRoutingCodec.decode(decodeText(bytes)) ?: corrupt()
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
            """SELECT name FROM sqlite_master WHERE type='table' AND name NOT GLOB 'sqlite_*'
               AND name != 'android_metadata'""", null,
        ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun hasUserSchema(db: SQLiteDatabase): Boolean =
        db.rawQuery(
            """SELECT 1 FROM sqlite_master WHERE name NOT GLOB 'sqlite_*'
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
        val issuedToken: UUID? = null,
    ) {
        private fun header(): ByteArray = textBytes(
            "$sequence|$recordId|${post.version}|${post.postId}|${post.action}|$parentId|$outgoingId" +
                (issuedToken?.let { "|issued:$it" } ?: ""),
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

    /**
     * Journal-only format, independent of the platform Intent codec and held OPEN persistence work.
     * Version 1 retains exactly the direct/channel identifiers available on current main. It supplies
     * no original-key, channel-incarnation or RF-origin witness. Unsupported/new payloads fail closed.
     */
    private object ReplyRoutingCodec {
        fun encode(payload: NotificationPayload): String? = when (payload) {
            is NotificationPayload.DirectMessage ->
                "1|direct|${payload.contact.radioId.value}|${payload.contact.id}|${payload.messageID}"
            is NotificationPayload.ChannelMessage ->
                "1|channel|${payload.radioId.value}|${payload.channelIndex.toInt()}|${payload.messageID}"
            else -> null
        }

        fun decode(encoded: String): NotificationPayload? {
            val fields = encoded.split('|')
            if (fields.size != 5 || fields[0] != "1") return null
            val radio = canonicalUuid(fields[2]) ?: return null
            val message = canonicalUuid(fields[4]) ?: return null
            return when (fields[1]) {
                "direct" -> {
                    val contact = canonicalUuid(fields[3]) ?: return null
                    NotificationPayload.DirectMessage(EntityKey(RadioId(radio), contact), message)
                }
                "channel" -> {
                    val channel = fields[3].toIntOrNull() ?: return null
                    if (channel !in 0..255 || channel.toString() != fields[3]) return null
                    NotificationPayload.ChannelMessage(RadioId(radio), channel.toUByte(), message)
                }
                else -> null
            }
        }

        private fun canonicalUuid(value: String): UUID? = try {
            UUID.fromString(value).takeIf { it.toString() == value }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
