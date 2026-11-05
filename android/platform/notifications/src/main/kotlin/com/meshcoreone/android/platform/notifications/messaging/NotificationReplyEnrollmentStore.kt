// AndroidOnly: WP-401 Explicit loss-aware generation creation and copy-only unbound text; never freshness or send proof.
package com.meshcoreone.android.platform.notifications.messaging

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Nonactivated storage half of an external authenticated enrollment protocol. Caller must durably
 * retain actual foreground acknowledgment and PREPARING witness BEFORE prepare, then witness
 * acceptance BEFORE bind. UUIDs and local digests are not authentication or antirollback proof.
 */
class NotificationReplyEnrollmentStore internal constructor(
    private val file: File,
    private val replyLimits: NotificationReplyLimits,
    private val receiptBudget: Long,
    private val issuanceLimits: NotificationReplyStagingStore.IssuanceLimits,
    private val unboundLimits: NotificationReplyLimits,
    private val failAt: CrashPoint? = null,
    private val afterCommit: ((CommitPoint) -> Unit)? = null,
) {
    constructor(
        context: Context,
        generationId: UUID,
        replyLimits: NotificationReplyLimits,
        receiptBudget: Long,
        issuanceLimits: NotificationReplyStagingStore.IssuanceLimits,
        unboundLimits: NotificationReplyLimits,
    ) : this(
        File(context.applicationContext.noBackupFilesDir, "notification-reply-generation-$generationId.db"),
        replyLimits, receiptBudget, issuanceLimits, unboundLimits,
    )

    /** All three values must be independently retained before any prepare call. */
    data class EnrollmentOperation(val operationId: UUID, val storeId: UUID, val creationNonce: UUID)
    enum class Phase { PREPARING, BOUND }
    enum class CommitPoint { PREPARE, BIND, UNBOUND_TEXT }
    internal enum class CrashPoint { ALLOCATED, JOURNAL_CREATED, PREPARE_WRITTEN, BIND_WRITTEN, TEXT_WRITTEN }
    data class CreationReceipt(
        val operation: EnrollmentOperation,
        val revision: Long,
        val state: Phase,
        val creationNonce: UUID,
        val witnessId: UUID?,
    )
    data class WitnessAcceptance(
        val operation: EnrollmentOperation,
        val creationNonce: UUID,
        val witnessId: UUID,
    )
    enum class Failure {
        INVALID_LIMITS, MISSING_STORAGE, EXISTING_UNATTRIBUTED_STORAGE, MISMATCH,
        NOT_BOUND, INVALID_TEXT, RECORD_CAPACITY, PRIVATE_DATA_CAPACITY,
        CORRUPT_STORAGE, STORAGE_UNAVAILABLE, POLICY_MISMATCH,
    }
    sealed interface EnrollmentResult {
        data class Preparing(val receipt: CreationReceipt) : EnrollmentResult
        data class AlreadyPreparing(val receipt: CreationReceipt) : EnrollmentResult
        data class Bound(val receipt: CreationReceipt) : EnrollmentResult
        data class AlreadyBound(val receipt: CreationReceipt) : EnrollmentResult
        data class Observed(val receipt: CreationReceipt) : EnrollmentResult
        data class NotRetained(val reason: Failure) : EnrollmentResult
        data class Indeterminate(val operation: EnrollmentOperation) : EnrollmentResult
    }
    /** No destination, post, issued token, outgoing ID, claim or dispatch surface exists here. */
    data class UnboundText(
        val storeId: UUID,
        val sequence: Long,
        val recordId: UUID,
        val submissionId: UUID,
        val exactText: String,
    )
    sealed interface TextResult {
        data class Durable(val record: UnboundText) : TextResult
        data class AlreadyRetained(val record: UnboundText) : TextResult
        data class NotRetained(val reason: Failure) : TextResult
        data class Indeterminate(val submissionId: UUID, val proposedRecordId: UUID) : TextResult
    }
    sealed interface TextInventory {
        data class Retained(val records: List<UnboundText>) : TextInventory
        data class Failed(val reason: Failure) : TextInventory
    }

    private fun validLimits(): Boolean =
        replyLimits.isValid() && issuanceLimits.isValid() && unboundLimits.isValid() && receiptBudget > 0

    /**
     * Only for a previously authorized predeclared operation, never from DB/marker absence.
     * An already allocated empty/foreign/corrupt path is not recreated, even after a crash.
     */
    suspend fun prepare(operation: EnrollmentOperation): EnrollmentResult = withContext(Dispatchers.IO) {
        if (!validLimits()) return@withContext EnrollmentResult.NotRetained(Failure.INVALID_LIMITS)
        var commitAttempted = false
        var proposedReceipt: CreationReceipt? = null
        try {
            if (!file.createNewFile()) {
                return@withContext observeExisting(operation, operation.creationNonce, replayPrepare = true)
            }
            failIf(CrashPoint.ALLOCATED)
            val receipt = CreationReceipt(operation, 1, Phase.PREPARING, operation.creationNonce, null)
            proposedReceipt = receipt
            database { db ->
                transaction(db) {
                    if (db.version != 0 || db.rawQuery(
                            "SELECT 1 FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' " +
                                "AND name!='android_metadata' LIMIT 1", null,
                        ).use { it.moveToFirst() }
                    ) throw Invalid(Failure.EXISTING_UNATTRIBUTED_STORAGE)
                    journal().createGeneration(db, operation.storeId, receiptBudget, issuanceLimits)
                    failIf(CrashPoint.JOURNAL_CREATED)
                    db.execSQL(
                        """CREATE TABLE reply_enrollment(
                           singleton INTEGER PRIMARY KEY CHECK(singleton=1), operation_id TEXT NOT NULL,
                           store_id TEXT NOT NULL, revision INTEGER NOT NULL, phase TEXT NOT NULL,
                           nonce TEXT NOT NULL, witness_id TEXT, digest BLOB NOT NULL)""",
                    )
                    db.execSQL(
                        "INSERT INTO reply_enrollment VALUES(1,?,?,?,?,?,?,?)",
                        arrayOf(operation.operationId.toString(), operation.storeId.toString(), 1, Phase.PREPARING.name,
                            receipt.creationNonce.toString(), null, receiptDigest(receipt)),
                    )
                    db.execSQL(
                        """CREATE TABLE reply_unbound_policy(
                           singleton INTEGER PRIMARY KEY CHECK(singleton=1), record_count INTEGER NOT NULL,
                           content_bytes INTEGER NOT NULL, max_records INTEGER NOT NULL,
                           max_record_bytes INTEGER NOT NULL, max_total_bytes INTEGER NOT NULL, digest BLOB NOT NULL)""",
                    )
                    db.execSQL(
                        "INSERT INTO reply_unbound_policy VALUES(1,0,0,?,?,?,?)",
                        arrayOf<Any>(unboundLimits.maxRecords, unboundLimits.maxRecordBytes,
                            unboundLimits.maxTotalRecordBytes, textPolicyDigest(receipt, unboundLimits, 0, 0)),
                    )
                    db.execSQL(
                        """CREATE TABLE reply_unbound_text(
                           sequence INTEGER PRIMARY KEY, record_id TEXT UNIQUE NOT NULL,
                           submission_id TEXT NOT NULL, exact_text BLOB NOT NULL,
                           content_bytes INTEGER NOT NULL, digest BLOB NOT NULL)""",
                    )
                    db.version = 4
                    checked(db)
                    if (validate(db, operation.storeId) != receipt) throw Invalid(Failure.CORRUPT_STORAGE)
                    failIf(CrashPoint.PREPARE_WRITTEN)
                    commitAttempted = true
                }
            }
            afterCommit?.invoke(CommitPoint.PREPARE)
            EnrollmentResult.Preparing(receipt)
        } catch (error: Invalid) {
            if (commitAttempted) reconcileAmbiguous(requireNotNull(proposedReceipt))
            else EnrollmentResult.NotRetained(error.reason)
        } catch (_: SQLiteDatabaseCorruptException) {
            if (commitAttempted) reconcileAmbiguous(requireNotNull(proposedReceipt))
            else EnrollmentResult.NotRetained(Failure.CORRUPT_STORAGE)
        } catch (_: SQLiteException) {
            if (commitAttempted) reconcileAmbiguous(requireNotNull(proposedReceipt))
            else EnrollmentResult.NotRetained(Failure.STORAGE_UNAVAILABLE)
        } catch (_: IOException) {
            if (commitAttempted) reconcileAmbiguous(requireNotNull(proposedReceipt))
            else EnrollmentResult.NotRetained(Failure.STORAGE_UNAVAILABLE)
        } catch (_: SecurityException) {
            if (commitAttempted) reconcileAmbiguous(requireNotNull(proposedReceipt))
            else EnrollmentResult.NotRetained(Failure.STORAGE_UNAVAILABLE)
        }
    }

    /** Never creates or discovers an adoptable nonce: operation already requires its retained nonce. */
    suspend fun reconcile(
        operation: EnrollmentOperation,
        expectedNonce: UUID = operation.creationNonce,
    ): EnrollmentResult =
        withContext(Dispatchers.IO) {
            if (!validLimits()) EnrollmentResult.NotRetained(Failure.INVALID_LIMITS)
            else if (expectedNonce != operation.creationNonce) EnrollmentResult.NotRetained(Failure.MISMATCH)
            else observeExisting(operation, expectedNonce)
        }

    suspend fun bind(expected: CreationReceipt, acceptance: WitnessAcceptance): EnrollmentResult =
        withContext(Dispatchers.IO) {
            if (!validLimits()) return@withContext EnrollmentResult.NotRetained(Failure.INVALID_LIMITS)
            if (expected.state != Phase.PREPARING || expected.revision != 1L || expected.witnessId != null ||
                expected.creationNonce != expected.operation.creationNonce ||
                acceptance.operation != expected.operation || acceptance.creationNonce != expected.creationNonce
            ) return@withContext EnrollmentResult.NotRetained(Failure.MISMATCH)
            val bound = expected.copy(revision = 2, state = Phase.BOUND, witnessId = acceptance.witnessId)
            var commitAttempted = false
            try {
                val result = database { db ->
                    transaction(db) {
                        val current = checked(db)
                        when (current) {
                            bound -> EnrollmentResult.AlreadyBound(current)
                            expected -> {
                                db.execSQL(
                                    "UPDATE reply_enrollment SET revision=2,phase='BOUND',witness_id=?,digest=? WHERE singleton=1",
                                    arrayOf<Any>(acceptance.witnessId.toString(), receiptDigest(bound)),
                                )
                                if (checked(db) != bound) throw Invalid(Failure.CORRUPT_STORAGE)
                                failIf(CrashPoint.BIND_WRITTEN)
                                commitAttempted = true
                                EnrollmentResult.Bound(bound)
                            }
                            else -> EnrollmentResult.NotRetained(Failure.MISMATCH)
                        }
                    }
                }
                if (commitAttempted) afterCommit?.invoke(CommitPoint.BIND)
                result
            } catch (error: Invalid) {
                if (commitAttempted) reconcileBound(bound) else EnrollmentResult.NotRetained(error.reason)
            } catch (_: SQLiteDatabaseCorruptException) {
                if (commitAttempted) reconcileBound(bound)
                else EnrollmentResult.NotRetained(Failure.CORRUPT_STORAGE)
            } catch (_: SQLiteException) {
                if (commitAttempted) reconcileBound(bound)
                else EnrollmentResult.NotRetained(Failure.STORAGE_UNAVAILABLE)
            } catch (_: SecurityException) {
                if (commitAttempted) reconcileBound(bound)
                else EnrollmentResult.NotRetained(Failure.STORAGE_UNAVAILABLE)
            }
        }

    /**
     * Only returns a handle; every actual journal transaction independently validates exact BOUND
     * nonce/revision/witness. Holding this object cannot bypass replacement or PREPARING checks.
     */
    fun boundJournal(receipt: CreationReceipt): NotificationReplyStagingStore =
        NotificationReplyStagingStore(file, replyLimits, expectedEnrollment = receipt)

    /**
     * Unknown/old input is copy-only. Same submission+exact UTF-16 is idempotent; different exact
     * text under that submission gets a separate unbound row, never overwriting earlier bytes.
     */
    suspend fun retainUnbound(
        expected: CreationReceipt,
        submissionId: UUID,
        exactText: String,
    ): TextResult = withContext(Dispatchers.IO) {
        if (!validLimits()) return@withContext TextResult.NotRetained(Failure.INVALID_LIMITS)
        if (exactText.isEmpty()) return@withContext TextResult.NotRetained(Failure.INVALID_TEXT)
        if (exactText.length > Int.MAX_VALUE / Char.SIZE_BYTES ||
            exactText.length.toLong() * Char.SIZE_BYTES > unboundLimits.maxRecordBytes
        ) return@withContext TextResult.NotRetained(Failure.PRIVATE_DATA_CAPACITY)
        val proposedId = UUID.randomUUID()
        var commitAttempted = false
        try {
            val result = database { db ->
                transaction(db) {
                    val receipt = checked(db)
                    requireBound(receipt, expected)
                    val state = textState(db, receipt)
                    val prior = state.records.firstOrNull {
                        it.submissionId == submissionId && it.exactText == exactText
                    }
                    if (prior != null) return@transaction TextResult.AlreadyRetained(prior)
                    val record = UnboundText(receipt.operation.storeId, state.records.size.toLong() + 1,
                        proposedId, submissionId, exactText)
                    val bytes = recordBytes(record)
                    if (bytes > unboundLimits.maxRecordBytes ||
                        bytes > unboundLimits.maxTotalRecordBytes - state.bytes
                    ) return@transaction TextResult.NotRetained(Failure.PRIVATE_DATA_CAPACITY)
                    if (state.records.size >= unboundLimits.maxRecords) {
                        return@transaction TextResult.NotRetained(Failure.RECORD_CAPACITY)
                    }
                    db.execSQL(
                        "INSERT INTO reply_unbound_text VALUES(?,?,?,?,?,?)",
                        arrayOf<Any>(record.sequence, record.recordId.toString(), submissionId.toString(),
                            textBytes(exactText), bytes, recordDigest(receipt, record)),
                    )
                    failIf(CrashPoint.TEXT_WRITTEN)
                    val count = state.records.size.toLong() + 1
                    val total = state.bytes + bytes
                    db.execSQL(
                        "UPDATE reply_unbound_policy SET record_count=?,content_bytes=?,digest=? WHERE singleton=1",
                        arrayOf<Any>(count, total, textPolicyDigest(receipt, unboundLimits, count, total)),
                    )
                    checked(db)
                    val retained = textState(db, receipt)
                    if (retained.records.size.toLong() != count || retained.records.lastOrNull() != record) {
                        throw Invalid(Failure.CORRUPT_STORAGE)
                    }
                    commitAttempted = true
                    TextResult.Durable(record)
                }
            }
            if (commitAttempted) afterCommit?.invoke(CommitPoint.UNBOUND_TEXT)
            result
        } catch (error: Invalid) {
            if (commitAttempted) reconcileText(expected, submissionId, exactText, proposedId)
            else TextResult.NotRetained(error.reason)
        } catch (_: SQLiteDatabaseCorruptException) {
            if (commitAttempted) reconcileText(expected, submissionId, exactText, proposedId)
            else TextResult.NotRetained(Failure.CORRUPT_STORAGE)
        } catch (_: SQLiteException) {
            if (commitAttempted) reconcileText(expected, submissionId, exactText, proposedId)
            else TextResult.NotRetained(Failure.STORAGE_UNAVAILABLE)
        } catch (_: SecurityException) {
            if (commitAttempted) reconcileText(expected, submissionId, exactText, proposedId)
            else TextResult.NotRetained(Failure.STORAGE_UNAVAILABLE)
        }
    }

    suspend fun readUnbound(expected: CreationReceipt): TextInventory = withContext(Dispatchers.IO) {
        if (!validLimits()) return@withContext TextInventory.Failed(Failure.INVALID_LIMITS)
        try {
            database { db -> transaction(db) {
                val receipt = checked(db)
                requireBound(receipt, expected)
                TextInventory.Retained(textState(db, receipt).records)
            } }
        } catch (error: Invalid) {
            TextInventory.Failed(error.reason)
        } catch (_: SQLiteDatabaseCorruptException) {
            TextInventory.Failed(Failure.CORRUPT_STORAGE)
        } catch (_: SQLiteException) {
            TextInventory.Failed(Failure.STORAGE_UNAVAILABLE)
        } catch (_: SecurityException) {
            TextInventory.Failed(Failure.STORAGE_UNAVAILABLE)
        }
    }

    private fun observeExisting(
        operation: EnrollmentOperation,
        nonce: UUID,
        replayPrepare: Boolean = false,
    ): EnrollmentResult = try {
        database { db -> transaction(db) {
            val receipt = checked(db)
            if (receipt.operation != operation || receipt.creationNonce != nonce) {
                EnrollmentResult.NotRetained(Failure.MISMATCH)
            } else if (!replayPrepare) EnrollmentResult.Observed(receipt)
            else if (receipt.state == Phase.PREPARING) EnrollmentResult.AlreadyPreparing(receipt)
            else EnrollmentResult.AlreadyBound(receipt)
        } }
    } catch (error: Invalid) {
        EnrollmentResult.NotRetained(error.reason)
    } catch (_: SQLiteDatabaseCorruptException) {
        EnrollmentResult.NotRetained(Failure.CORRUPT_STORAGE)
    } catch (_: SQLiteException) {
        EnrollmentResult.NotRetained(Failure.STORAGE_UNAVAILABLE)
    } catch (_: SecurityException) {
        EnrollmentResult.NotRetained(Failure.STORAGE_UNAVAILABLE)
    }

    private fun reconcileAmbiguous(expected: CreationReceipt): EnrollmentResult =
        when (val observed = observeExisting(expected.operation, expected.creationNonce)) {
            is EnrollmentResult.Observed -> when (observed.receipt.state) {
                Phase.PREPARING -> EnrollmentResult.AlreadyPreparing(observed.receipt)
                Phase.BOUND -> EnrollmentResult.AlreadyBound(observed.receipt)
            }
            else -> EnrollmentResult.Indeterminate(expected.operation)
        }

    private fun reconcileBound(bound: CreationReceipt): EnrollmentResult =
        when (val observed = observeExisting(bound.operation, bound.creationNonce)) {
            is EnrollmentResult.Observed -> if (observed.receipt == bound) EnrollmentResult.AlreadyBound(bound)
                else EnrollmentResult.Indeterminate(bound.operation)
            else -> EnrollmentResult.Indeterminate(bound.operation)
        }

    private fun reconcileText(
        expected: CreationReceipt,
        submissionId: UUID,
        exactText: String,
        proposedId: UUID,
    ): TextResult = try {
        database { db -> transaction(db) {
            val receipt = checked(db)
            requireBound(receipt, expected)
            val exact = textState(db, receipt).records.firstOrNull {
                it.submissionId == submissionId && it.exactText == exactText
            }
            if (exact == null) TextResult.Indeterminate(submissionId, proposedId)
            else TextResult.AlreadyRetained(exact)
        } }
    } catch (_: Invalid) {
        TextResult.Indeterminate(submissionId, proposedId)
    } catch (_: SQLiteException) {
        TextResult.Indeterminate(submissionId, proposedId)
    } catch (_: SecurityException) {
        TextResult.Indeterminate(submissionId, proposedId)
    }

    private fun checked(db: SQLiteDatabase): CreationReceipt {
        if (db.version !in 4..6) throw Invalid(Failure.EXISTING_UNATTRIBUTED_STORAGE)
        val receipt = validate(db, null)
        if (textState(db, receipt).limits != unboundLimits) throw Invalid(Failure.POLICY_MISMATCH)
        // Validate journal/issuance/custody before accepting even copy-only storage as healthy.
        journal().validateGeneration(db)
        val budgets = db.rawQuery("SELECT max_bytes FROM reply_custody_policy", null).use {
            if (!it.moveToFirst()) throw Invalid(Failure.CORRUPT_STORAGE)
            it.getLong(0)
        }
        val issuance = db.rawQuery(
            "SELECT max_records,max_record_bytes,max_total_bytes FROM reply_issuance_policy", null,
        ).use {
            if (!it.moveToFirst()) throw Invalid(Failure.CORRUPT_STORAGE)
            Triple(it.getLong(0), it.getLong(1), it.getLong(2))
        }
        if (budgets != receiptBudget || issuance != Triple(issuanceLimits.maxRecords.toLong(),
                issuanceLimits.maxRecordBytes, issuanceLimits.maxTotalRecordBytes)
        ) throw Invalid(Failure.POLICY_MISMATCH)
        return receipt
    }

    private fun journal() = NotificationReplyStagingStore(file, replyLimits)

    private fun failIf(point: CrashPoint) {
        if (failAt == point) throw SQLiteException("Injected enrollment transaction interruption")
    }

    private fun <T> database(block: (SQLiteDatabase) -> T): T {
        if (!file.exists()) throw Invalid(Failure.MISSING_STORAGE)
        return SQLiteDatabase.openDatabase(
            file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            { throw SQLiteDatabaseCorruptException("Enrollment storage corrupt; files preserved") },
        ).use { db ->
            db.execSQL("PRAGMA synchronous=FULL")
            block(db)
        }
    }

    private fun <T> transaction(db: SQLiteDatabase, action: () -> T): T {
        db.beginTransaction()
        try {
            val result = action()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    internal class Invalid(val reason: Failure) : RuntimeException()

    companion object {
        internal fun copyProjection(
            db: SQLiteDatabase,
            storeId: UUID,
        ): List<NotificationReplyStagingStore.CopyText> {
            val receipt = validate(db, storeId)
            return textState(db, receipt).records.map {
                NotificationReplyStagingStore.CopyText(it.storeId, it.recordId, it.sequence, it.exactText)
            }
        }

        /** Structural validation only; the journal additionally requires exact external BOUND receipt. */
        internal fun validate(db: SQLiteDatabase, expectedStoreId: UUID?): CreationReceipt {
            columns(db, "reply_enrollment",
                listOf("singleton", "operation_id", "store_id", "revision", "phase", "nonce", "witness_id", "digest"))
            val receipt = db.rawQuery("SELECT * FROM reply_enrollment", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getType(0) != Cursor.FIELD_TYPE_INTEGER ||
                    cursor.getLong(0) != 1L || cursor.getType(3) != Cursor.FIELD_TYPE_INTEGER ||
                    listOf(1, 2, 4, 5).any { cursor.getType(it) != Cursor.FIELD_TYPE_STRING } ||
                    !cursor.isNull(6) && cursor.getType(6) != Cursor.FIELD_TYPE_STRING ||
                    cursor.getType(7) != Cursor.FIELD_TYPE_BLOB
                ) corrupt()
                val phase = Phase.entries.firstOrNull { it.name == cursor.getString(4) } ?: corrupt()
                val nonce = uuid(cursor.getString(5))
                val value = CreationReceipt(
                    EnrollmentOperation(uuid(cursor.getString(1)), uuid(cursor.getString(2)), nonce),
                    cursor.getLong(3), phase, nonce,
                    if (cursor.isNull(6)) null else uuid(cursor.getString(6)),
                )
                if (value.revision != (if (phase == Phase.PREPARING) 1L else 2L) ||
                    (phase == Phase.PREPARING) != (value.witnessId == null) ||
                    expectedStoreId != null && value.operation.storeId != expectedStoreId ||
                    !receiptDigest(value).contentEquals(cursor.getBlob(7)) || cursor.moveToNext()
                ) corrupt()
                value
            }
            textState(db, receipt)
            return receipt
        }

        private class TextState(val limits: NotificationReplyLimits, val bytes: Long, val records: List<UnboundText>)

        private fun textState(db: SQLiteDatabase, receipt: CreationReceipt): TextState {
            columns(db, "reply_unbound_policy", listOf(
                "singleton", "record_count", "content_bytes", "max_records", "max_record_bytes", "max_total_bytes", "digest"))
            columns(db, "reply_unbound_text", listOf(
                "sequence", "record_id", "submission_id", "exact_text", "content_bytes", "digest"))
            val policy = db.rawQuery("SELECT * FROM reply_unbound_policy", null).use { cursor ->
                if (!cursor.moveToFirst() || (0..5).any { cursor.getType(it) != Cursor.FIELD_TYPE_INTEGER } ||
                    cursor.getLong(0) != 1L || cursor.getType(6) != Cursor.FIELD_TYPE_BLOB
                ) corrupt()
                val count = cursor.getLong(1)
                val bytes = cursor.getLong(2)
                val max = cursor.getLong(3)
                if (max !in 1..Int.MAX_VALUE.toLong()) corrupt()
                val limits = NotificationReplyLimits(max.toInt(), cursor.getLong(4), cursor.getLong(5))
                if (!limits.isValid() || count !in 0..max || bytes !in 0..limits.maxTotalRecordBytes ||
                    !textPolicyDigest(receipt, limits, count, bytes).contentEquals(cursor.getBlob(6)) ||
                    cursor.moveToNext()
                ) corrupt()
                Triple(limits, count, bytes)
            }
            val records = mutableListOf<UnboundText>()
            val ids = mutableSetOf<UUID>()
            val exact = mutableSetOf<Pair<UUID, String>>()
            var bytes = 0L
            db.rawQuery("SELECT * FROM reply_unbound_text ORDER BY sequence", null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (records.size >= policy.first.maxRecords ||
                        listOf(0, 4).any { cursor.getType(it) != Cursor.FIELD_TYPE_INTEGER } ||
                        listOf(1, 2).any { cursor.getType(it) != Cursor.FIELD_TYPE_STRING } ||
                        listOf(3, 5).any { cursor.getType(it) != Cursor.FIELD_TYPE_BLOB }
                    ) corrupt()
                    val record = UnboundText(receipt.operation.storeId, cursor.getLong(0),
                        uuid(cursor.getString(1)), uuid(cursor.getString(2)), decodeText(cursor.getBlob(3)))
                    val size = recordBytes(record)
                    if (record.sequence != records.size.toLong() + 1 || !ids.add(record.recordId) ||
                        record.exactText.isEmpty() || !exact.add(record.submissionId to record.exactText) ||
                        size != cursor.getLong(4) || size > policy.first.maxRecordBytes ||
                        size > policy.first.maxTotalRecordBytes - bytes ||
                        !recordDigest(receipt, record).contentEquals(cursor.getBlob(5))
                    ) corrupt()
                    records.add(record)
                    bytes += size
                }
            }
            if (records.size.toLong() != policy.second || bytes != policy.third) corrupt()
            return TextState(policy.first, bytes, records)
        }

        private fun requireBound(actual: CreationReceipt, expected: CreationReceipt) {
            if (actual.state != Phase.BOUND) throw Invalid(Failure.NOT_BOUND)
            if (actual != expected) throw Invalid(Failure.MISMATCH)
        }
        private fun receiptDigest(receipt: CreationReceipt): ByteArray = digest(
            receipt.operation.operationId.toString(), receipt.operation.storeId.toString(),
            receipt.revision.toString(), receipt.state.name, receipt.creationNonce.toString(),
            receipt.witnessId?.toString() ?: "",
        )
        // Bind copy rows/policy to creation causality, not mutable PREPARING -> BOUND revision.
        private fun textPolicyDigest(
            receipt: CreationReceipt, limits: NotificationReplyLimits, count: Long, bytes: Long,
        ): ByteArray = digest(
            "copy-policy-v1", receipt.operation.operationId.toString(), receipt.operation.storeId.toString(),
            receipt.creationNonce.toString(), limits.maxRecords.toString(), limits.maxRecordBytes.toString(),
            limits.maxTotalRecordBytes.toString(), count.toString(), bytes.toString(),
        )
        private fun recordHeader(record: UnboundText): String =
            "copy-v1|${record.storeId}|${record.sequence}|${record.recordId}|${record.submissionId}"
        private fun recordBytes(record: UnboundText): Long =
            (recordHeader(record).length.toLong() + record.exactText.length) * Char.SIZE_BYTES
        private fun recordDigest(receipt: CreationReceipt, record: UnboundText): ByteArray =
            digest(receipt.operation.operationId.toString(), receipt.creationNonce.toString(),
                recordHeader(record), record.exactText)
        private fun digest(vararg values: String): ByteArray = MessageDigest.getInstance("SHA-256").run {
            values.forEach {
                update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(it.length.toLong() * Char.SIZE_BYTES).array())
                update(textBytes(it))
            }
            digest()
        }
        private fun textBytes(value: String): ByteArray =
            ByteBuffer.allocate(Math.multiplyExact(value.length, Char.SIZE_BYTES)).apply {
                value.forEach { putChar(it) }
            }.array()
        private fun decodeText(value: ByteArray): String {
            if (value.size % Char.SIZE_BYTES != 0) corrupt()
            val buffer = ByteBuffer.wrap(value)
            return CharArray(value.size / Char.SIZE_BYTES) { buffer.char }.concatToString()
        }
        private fun columns(db: SQLiteDatabase, table: String, expected: List<String>) {
            val actual = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
            }
            if (actual != expected) corrupt()
        }
        private fun uuid(value: String): UUID = try {
            UUID.fromString(value).also { if (it.toString() != value) corrupt() }
        } catch (_: IllegalArgumentException) {
            corrupt()
        }
        private fun corrupt(): Nothing = throw Invalid(Failure.CORRUPT_STORAGE)
    }
}
