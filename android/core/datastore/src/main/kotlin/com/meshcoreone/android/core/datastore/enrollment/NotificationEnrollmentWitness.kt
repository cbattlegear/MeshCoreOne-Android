// AndroidOnly: WP-204 Nonactivated authenticated SQLite witness history and durable full-record CAS.
package com.meshcoreone.android.core.datastore.enrollment

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.meshcoreone.android.core.datastore.AndroidStorageIssueReporter
import com.meshcoreone.android.core.datastore.StorageFailure
import com.meshcoreone.android.core.datastore.StorageIssueReporter
import com.meshcoreone.android.core.datastore.StorageOperation
import com.meshcoreone.android.core.datastore.StorageProblem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * No production wiring. Requires a separately trusted foreground acknowledgment verifier.
 * The no-backup DB and nonexportable AndroidKeystore keys authenticate surviving continuity,
 * not freshness. A matching same-device snapshot of DB plus surviving keys can roll back this
 * history undetectably; this is NOT monotonic antirollback or dispatch authority.
 */
class NotificationEnrollmentWitness internal constructor(
    private val file: File,
    private val authentication: WitnessAuthentication,
    private val acknowledgment: ForegroundWitnessAcknowledgment,
    private val reporter: StorageIssueReporter = AndroidStorageIssueReporter,
    private val newWitnessId: () -> UUID = UUID::randomUUID,
    private val fault: ((WritePoint) -> Unit)? = null,
) {
    constructor(context: Context, acknowledgment: ForegroundWitnessAcknowledgment) : this(
        File(context.applicationContext.noBackupFilesDir, "notification-enrollment-witness-v1.db"),
        AndroidWitnessAuthentication(context.applicationContext), acknowledgment,
    )

    internal enum class WritePoint { ALLOCATED, KEY_CREATED, ROW_WRITTEN, COMMITTED }

    suspend fun observe(): WitnessObservation = withContext(Dispatchers.IO) {
        try {
            authentication.requireAccessible()
            checkPath()
            if (!file.exists()) WitnessObservation.UnknownOrLost
            else open().use { db ->
                transaction(db) { WitnessObservation.Current(read(db).record) }
            }
        } catch (failure: Invalid) {
            failed(failure.problem)
        } catch (failure: StorageFailure) {
            reporter.report(failure)
            WitnessObservation.Failed(problem(failure))
        } catch (failure: SQLiteDatabaseCorruptException) {
            failed(WitnessProblem.CORRUPT_STORAGE)
        } catch (failure: SQLiteException) {
            failed(WitnessProblem.STORAGE_UNAVAILABLE)
        } catch (failure: IOException) {
            failed(WitnessProblem.STORAGE_UNAVAILABLE)
        } catch (failure: SecurityException) {
            failed(WitnessProblem.STORAGE_UNAVAILABLE)
        }
    }

    suspend fun authorize(request: WitnessAuthorizationRequest): WitnessMutation {
        val before = observe()
        if (before is WitnessObservation.Failed) return WitnessMutation.NotRetained(before.problem)
        val current = (before as? WitnessObservation.Current)?.record
        if (current?.operation == request.operation) {
            return replay(request)
        }
        if (current != request.expectedCurrent) return WitnessMutation.Stale
        when (val verified = acknowledgment.verify(request)) {
            ForegroundAcknowledgmentResult.Required, ForegroundAcknowledgmentResult.AlreadyConsumed ->
                return WitnessMutation.NotRetained(WitnessProblem.ACKNOWLEDGMENT_REQUIRED)
            ForegroundAcknowledgmentResult.Indeterminate -> return WitnessMutation.Indeterminate(request.operation)
            ForegroundAcknowledgmentResult.Rejected ->
                return WitnessMutation.NotRetained(WitnessProblem.ACKNOWLEDGMENT_MISMATCH)
            is ForegroundAcknowledgmentResult.NewlyVerified -> if (verified.request != request) {
                return WitnessMutation.NotRetained(WitnessProblem.ACKNOWLEDGMENT_MISMATCH)
            }
        }
        return mutate(request.operation, allowAllocation = true) { db, previous, mutation ->
            if (previous?.record != request.expectedCurrent) return@mutate WitnessMutation.Stale
            val witness = newWitnessId()
            val next = EnrollmentWitnessRecord(request.operation, witness, 1, WitnessPhase.AUTHORIZED, null)
            if (db.rawQuery("SELECT payload FROM witness_history ORDER BY sequence", null).use { rows ->
                    var reused = false
                    while (rows.moveToNext()) {
                        val old = decode(rows.getBlob(0))
                        if (old.record.operation.operationId == request.operation.operationId ||
                            old.record.operation.storeId == request.operation.storeId ||
                            old.record.operation.creationNonce == request.operation.creationNonce ||
                            old.record.witnessId == witness || old.acknowledgmentId == request.acknowledgmentId
                        ) reused = true
                    }
                    reused
                }
            ) return@mutate WitnessMutation.NotRetained(WitnessProblem.INVALID_RECEIPT)
            mutation.attempted = true
            authentication.createNew(witness)
            fault?.invoke(WritePoint.KEY_CREATED)
            append(db, previous, next, request.acknowledgmentId, authorizationBase(request.expectedCurrent))
            WitnessMutation.Stored(next, newlyAuthorized = true)
        }
    }

    suspend fun accept(expected: EnrollmentWitnessRecord, preparing: WitnessCreationReceipt): WitnessMutation {
        if (!expected.valid() || expected.phase != WitnessPhase.AUTHORIZED ||
            !preparing.valid() || preparing.phase != JournalPhase.PREPARING ||
            preparing.operation != expected.operation
        ) return WitnessMutation.NotRetained(WitnessProblem.INVALID_RECEIPT)
        return change(expected, expected.copy(revision = 2, phase = WitnessPhase.ACCEPTED, receipt = preparing))
    }

    suspend fun complete(expected: EnrollmentWitnessRecord, bound: WitnessCreationReceipt): WitnessMutation {
        if (!expected.valid() || expected.phase != WitnessPhase.ACCEPTED || !bound.valid() ||
            bound.phase != JournalPhase.BOUND || bound.operation != expected.operation ||
            bound.witnessId != expected.witnessId || bound.creationNonce != expected.receipt?.creationNonce
        ) return WitnessMutation.NotRetained(WitnessProblem.INVALID_RECEIPT)
        return change(expected, expected.copy(revision = 3, phase = WitnessPhase.BOUND, receipt = bound))
    }

    private suspend fun replay(request: WitnessAuthorizationRequest): WitnessMutation = withContext(Dispatchers.IO) {
        guarded(request.operation) {
            authentication.requireAccessible()
            checkPath()
            if (!file.exists()) return@guarded WitnessMutation.Stale
            open().use { db ->
                transaction(db) {
                    val row = read(db)
                    if (row.record.operation != request.operation) WitnessMutation.Stale
                    else if (row.acknowledgmentId != request.acknowledgmentId ||
                        !MessageDigest.isEqual(row.authorizationBase, authorizationBase(request.expectedCurrent))
                    ) WitnessMutation.NotRetained(WitnessProblem.ACKNOWLEDGMENT_MISMATCH)
                    else WitnessMutation.Stored(row.record)
                }
            }
        }
    }

    private suspend fun change(expected: EnrollmentWitnessRecord, next: EnrollmentWitnessRecord): WitnessMutation =
        mutate(expected.operation, allowAllocation = false) { db, current, mutation ->
            if (current?.record == next) return@mutate WitnessMutation.Stored(next)
            if (current?.record != expected) return@mutate WitnessMutation.Stale
            mutation.attempted = true
            append(db, current, next, current.acknowledgmentId, current.authorizationBase)
            WitnessMutation.Stored(next)
        }

    private class Mutation(var attempted: Boolean = false)

    private suspend fun mutate(
        operation: WitnessOperation,
        allowAllocation: Boolean,
        write: (SQLiteDatabase, Row?, Mutation) -> WitnessMutation,
    ): WitnessMutation = withContext(Dispatchers.IO) {
        val mutation = Mutation()
        guarded(operation, mutation) {
            authentication.requireAccessible()
            checkPath()
            var allocated = false
            if (!file.exists()) {
                if (!allowAllocation) return@guarded WitnessMutation.NotRetained(WitnessProblem.CORRUPT_STORAGE)
                mutation.attempted = true
                allocated = file.createNewFile()
                if (!allocated) return@guarded WitnessMutation.Stale
                fault?.invoke(WritePoint.ALLOCATED)
            }
            val result = open().use { db ->
                db.rawQuery("PRAGMA journal_mode=DELETE", null).use {
                    if (!it.moveToFirst() || it.getString(0).lowercase() != "delete") {
                        throw Invalid(WitnessProblem.STORAGE_UNAVAILABLE)
                    }
                }
                db.execSQL("PRAGMA synchronous=FULL")
                transaction(db) {
                    if (allocated) {
                        if (db.version != 0 || db.rawQuery(
                                "SELECT 1 FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' " +
                                    "AND name != 'android_metadata'", null,
                            ).use { it.moveToFirst() }
                        ) throw Invalid(WitnessProblem.CORRUPT_STORAGE)
                        db.execSQL("CREATE TABLE witness_history(sequence INTEGER PRIMARY KEY, payload BLOB NOT NULL, mac BLOB NOT NULL)")
                        db.execSQL("CREATE TABLE witness_head(singleton INTEGER PRIMARY KEY CHECK(singleton=1), sequence INTEGER NOT NULL, mac BLOB NOT NULL)")
                        db.version = 1
                    }
                    write(db, if (allocated) null else read(db), mutation)
                }
            }
            if (result is WitnessMutation.Stored) {
                syncCommittedFile()
                fault?.invoke(WritePoint.COMMITTED)
                // A failed/lost readback is not a successful write acknowledgment.
                open().use { db ->
                    transaction(db) {
                        if (read(db).record == result.record) result else WitnessMutation.Indeterminate(operation)
                    }
                }
            } else result
        }
    }

    private fun append(
        db: SQLiteDatabase,
        previous: Row?,
        record: EnrollmentWitnessRecord,
        acknowledgmentId: UUID,
        authorizationBase: ByteArray,
    ) {
        if (!record.valid() || previous?.sequence == Long.MAX_VALUE) throw Invalid(WitnessProblem.INVALID_RECEIPT)
        val row = Row((previous?.sequence ?: 0) + 1, acknowledgmentId, authorizationBase, record, previous?.mac ?: ZERO_MAC)
        val payload = encode(row)
        val mac = authentication.authenticate(record.witnessId, payload)
        db.execSQL("INSERT INTO witness_history VALUES(?,?,?)", arrayOf(row.sequence, payload, mac))
        db.execSQL("INSERT OR REPLACE INTO witness_head VALUES(1,?,?)", arrayOf(
            row.sequence, authentication.authenticate(record.witnessId, headBytes(row.sequence, mac)),
        ))
        fault?.invoke(WritePoint.ROW_WRITTEN)
        if (read(db).record != record) throw Invalid(WitnessProblem.CORRUPT_STORAGE)
    }

    private data class Row(
        val sequence: Long,
        val acknowledgmentId: UUID,
        val authorizationBase: ByteArray,
        val record: EnrollmentWitnessRecord,
        val previousMac: ByteArray,
        val mac: ByteArray = ZERO_MAC,
    )

    private fun read(db: SQLiteDatabase): Row {
        if (db.version != 1 || db.rawQuery("PRAGMA quick_check", null).use {
                !it.moveToFirst() || it.getString(0) != "ok"
            }
        ) throw Invalid(WitnessProblem.CORRUPT_STORAGE)
        val operationIds = HashSet<UUID>()
        val stores = HashSet<UUID>()
        val nonces = HashSet<UUID>()
        val witnesses = HashSet<UUID>()
        val acknowledgments = HashSet<UUID>()
        var previous: Row? = null
        db.rawQuery("SELECT sequence,payload,mac FROM witness_history ORDER BY sequence", null).use { rows ->
            while (rows.moveToNext()) {
                val payload = rows.getBlob(1)
                val row = decode(payload).copy(mac = rows.getBlob(2))
                if (previous?.sequence == Long.MAX_VALUE || row.sequence != rows.getLong(0) ||
                    row.sequence != (previous?.sequence ?: 0) + 1 ||
                    !MessageDigest.isEqual(row.previousMac, previous?.mac ?: ZERO_MAC) ||
                    !MessageDigest.isEqual(payload, encode(row)) || !row.record.valid()
                ) throw Invalid(WitnessProblem.CORRUPT_STORAGE)
                if (!MessageDigest.isEqual(row.mac, authentication.authenticate(row.record.witnessId, payload))) {
                    throw Invalid(WitnessProblem.AUTHENTICATION_FAILED)
                }
                val record = row.record
                if (record.phase == WitnessPhase.AUTHORIZED) {
                    if (!operationIds.add(record.operation.operationId) || !stores.add(record.operation.storeId) ||
                        !nonces.add(record.operation.creationNonce) || !witnesses.add(record.witnessId) ||
                        !acknowledgments.add(row.acknowledgmentId) ||
                        !MessageDigest.isEqual(row.authorizationBase, authorizationBase(previous?.record))
                    ) throw Invalid(WitnessProblem.CORRUPT_STORAGE)
                } else {
                    val old = previous ?: throw Invalid(WitnessProblem.CORRUPT_STORAGE)
                    if (old.record.operation != record.operation || old.record.witnessId != record.witnessId ||
                        old.record.revision + 1 != record.revision ||
                        old.acknowledgmentId != row.acknowledgmentId ||
                        !MessageDigest.isEqual(old.authorizationBase, row.authorizationBase) ||
                        record.phase == WitnessPhase.ACCEPTED && old.record.phase != WitnessPhase.AUTHORIZED ||
                        record.phase == WitnessPhase.BOUND && (
                            old.record.phase != WitnessPhase.ACCEPTED ||
                                old.record.receipt?.creationNonce != record.receipt?.creationNonce
                            )
                    ) throw Invalid(WitnessProblem.CORRUPT_STORAGE)
                }
                previous = row
            }
        }
        val last = previous ?: throw Invalid(WitnessProblem.CORRUPT_STORAGE)
        db.rawQuery("SELECT singleton,sequence,mac FROM witness_head", null).use { head ->
            if (!head.moveToFirst() || head.getInt(0) != 1 || head.getLong(1) != last.sequence ||
                !MessageDigest.isEqual(head.getBlob(2), authentication.authenticate(
                    last.record.witnessId, headBytes(last.sequence, last.mac),
                )) || head.moveToNext()
            ) throw Invalid(WitnessProblem.AUTHENTICATION_FAILED)
        }
        return last
    }

    private fun checkPath() {
        if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile != file.absoluteFile ||
            file.exists() && !file.isFile
        ) throw Invalid(WitnessProblem.CORRUPT_STORAGE)
    }

    private fun open(): SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        DatabaseErrorHandler { throw Invalid(WitnessProblem.CORRUPT_STORAGE) },
    )

    private fun syncCommittedFile() {
        try {
            val committed = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            try { Os.fsync(committed) } finally { Os.close(committed) }
            val directory = Os.open(requireNotNull(file.parent), OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(directory).st_mode)) throw IOException("Witness parent is not a directory")
                Os.fsync(directory)
            } finally { Os.close(directory) }
        } catch (failure: ErrnoException) {
            throw IOException("Witness synchronization failed", failure)
        }
    }

    private inline fun <T> transaction(db: SQLiteDatabase, action: () -> T): T {
        // API31 has no framework read-only transaction API. Existing-file opens prohibit creation
        // and locale metadata; observation issues only SELECTs within this coherent native snapshot.
        // The corruption handler must not use Android's destructive default database recovery.
        db.beginTransaction()
        return try {
            action().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    private inline fun guarded(
        operation: WitnessOperation,
        mutation: Mutation = Mutation(),
        action: () -> WitnessMutation,
    ): WitnessMutation = try {
        action()
    } catch (failure: Invalid) {
        failed(failure.problem)
        if (mutation.attempted) WitnessMutation.Indeterminate(operation)
        else WitnessMutation.NotRetained(failure.problem)
    } catch (failure: StorageFailure) {
        reporter.report(failure)
        if (mutation.attempted) WitnessMutation.Indeterminate(operation)
        else WitnessMutation.NotRetained(problem(failure))
    } catch (failure: SQLiteDatabaseCorruptException) {
        writeFailure(operation, mutation, WitnessProblem.CORRUPT_STORAGE)
    } catch (failure: SQLiteException) {
        writeFailure(operation, mutation, WitnessProblem.STORAGE_UNAVAILABLE)
    } catch (failure: IOException) {
        writeFailure(operation, mutation, WitnessProblem.STORAGE_UNAVAILABLE)
    } catch (failure: SecurityException) {
        writeFailure(operation, mutation, WitnessProblem.STORAGE_UNAVAILABLE)
    }

    private fun writeFailure(operation: WitnessOperation, mutation: Mutation, problem: WitnessProblem): WitnessMutation {
        failed(problem)
        return if (mutation.attempted) WitnessMutation.Indeterminate(operation) else WitnessMutation.NotRetained(problem)
    }

    private fun failed(problem: WitnessProblem): WitnessObservation.Failed {
        reporter.report(StorageFailure(
            if (problem == WitnessProblem.STORAGE_UNAVAILABLE) StorageProblem.IoFailure else StorageProblem.CorruptSecretState,
            StorageOperation.OBSERVE,
        ))
        return WitnessObservation.Failed(problem)
    }

    private fun problem(failure: StorageFailure): WitnessProblem = when (failure.problem) {
        StorageProblem.DeviceLocked, StorageProblem.LockedBeforeFirstUnlock -> WitnessProblem.LOCKED
        StorageProblem.FormerKeyMissing, StorageProblem.KeyPermanentlyInvalidated -> WitnessProblem.MISSING_AUTHENTICATOR
        StorageProblem.CorruptKeystoreKey -> WitnessProblem.AUTHENTICATION_FAILED
        else -> WitnessProblem.STORAGE_UNAVAILABLE
    }

    private class Invalid(val problem: WitnessProblem) : Exception()

    private fun encode(row: Row): ByteArray = bytes {
        writeUTF("MeshCoreOne.notification-witness.history.v1")
        writeLong(row.sequence)
        uuid(row.acknowledgmentId)
        write(row.authorizationBase)
        record(row.record)
        write(row.previousMac)
    }

    private fun decode(payload: ByteArray): Row = try {
        if (payload.size > 512) throw Invalid(WitnessProblem.CORRUPT_STORAGE)
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            if (input.readUTF() != "MeshCoreOne.notification-witness.history.v1") throw Invalid(WitnessProblem.CORRUPT_STORAGE)
            val sequence = input.readLong()
            val acknowledgment = input.uuid()
            val authorizationBase = ByteArray(32).also(input::readFully)
            val operation = input.operation()
            val witness = input.uuid()
            val revision = input.readLong()
            val phase = WitnessPhase.entries.getOrNull(input.readInt()) ?: throw Invalid(WitnessProblem.CORRUPT_STORAGE)
            val receipt = if (input.readBoolean()) {
                val receiptOperation = input.operation()
                val receiptRevision = input.readLong()
                val receiptPhase = JournalPhase.entries.getOrNull(input.readInt()) ?: throw Invalid(WitnessProblem.CORRUPT_STORAGE)
                WitnessCreationReceipt(receiptOperation, receiptRevision, receiptPhase, input.uuid(),
                    if (input.readBoolean()) input.uuid() else null)
            } else null
            val previousMac = ByteArray(32).also(input::readFully)
            if (input.available() != 0) throw Invalid(WitnessProblem.CORRUPT_STORAGE)
            Row(sequence, acknowledgment, authorizationBase,
                EnrollmentWitnessRecord(operation, witness, revision, phase, receipt), previousMac)
        }
    } catch (failure: IOException) {
        throw Invalid(WitnessProblem.CORRUPT_STORAGE)
    }

    private fun authorizationBase(record: EnrollmentWitnessRecord?): ByteArray = MessageDigest.getInstance("SHA-256").digest(
        bytes {
            writeUTF("MeshCoreOne.notification-witness.authorization-base.v1")
            writeBoolean(record != null)
            if (record != null) record(record)
        },
    )

    private fun headBytes(sequence: Long, mac: ByteArray) = bytes {
        writeUTF("MeshCoreOne.notification-witness.head.v1")
        writeLong(sequence)
        write(mac)
    }

    private fun DataOutputStream.record(record: EnrollmentWitnessRecord) {
        operation(record.operation)
        uuid(record.witnessId)
        writeLong(record.revision)
        writeInt(record.phase.ordinal)
        writeBoolean(record.receipt != null)
        record.receipt?.let {
            operation(it.operation)
            writeLong(it.revision)
            writeInt(it.phase.ordinal)
            uuid(it.creationNonce)
            writeBoolean(it.witnessId != null)
            it.witnessId?.let { id -> uuid(id) }
        }
    }

    private fun DataOutputStream.operation(operation: WitnessOperation) {
        uuid(operation.operationId)
        uuid(operation.storeId)
        uuid(operation.creationNonce)
    }

    private fun DataInputStream.operation() = WitnessOperation(uuid(), uuid(), uuid())
    private fun DataInputStream.uuid() = UUID(readLong(), readLong())
    private fun DataOutputStream.uuid(id: UUID) { writeLong(id.mostSignificantBits); writeLong(id.leastSignificantBits) }
    private fun bytes(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { stream -> write(stream) }
        output.toByteArray()
    }

    private companion object {
        val ZERO_MAC = ByteArray(32)
    }
}
