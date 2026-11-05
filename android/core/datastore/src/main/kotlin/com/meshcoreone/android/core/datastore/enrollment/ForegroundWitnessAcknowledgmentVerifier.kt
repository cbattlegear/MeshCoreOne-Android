// AndroidOnly: WP-204 Durable, single-use foreground acknowledgment for nonactivated enrollment.
package com.meshcoreone.android.core.datastore.enrollment

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.meshcoreone.android.core.datastore.AndroidStorageAccess
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
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.ProviderException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ForegroundAcknowledgmentWarning private constructor(
    val id: String,
    val version: Int,
    val renderedCopySha256: String,
) {
    companion object {
        fun fromRenderedCopy(id: String, version: Int, renderedCopy: String): ForegroundAcknowledgmentWarning {
            require(id.isNotBlank() && id.length <= 128)
            require(version > 0)
            require(renderedCopy.isNotBlank())
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(renderedCopy.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
            return ForegroundAcknowledgmentWarning(id, version, digest)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ForegroundAcknowledgmentWarning &&
            id == other.id && version == other.version && renderedCopySha256 == other.renderedCopySha256

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + version) + renderedCopySha256.hashCode()
}

class ForegroundAcknowledgmentPresentation internal constructor(
    val request: WitnessAuthorizationRequest,
    val warning: ForegroundAcknowledgmentWarning,
    internal val presentationId: UUID,
    internal val processId: UUID,
) {
    internal val lifecycleLock = Any()
    internal val invalidated = AtomicBoolean(false)
    internal val actionAttempted = AtomicBoolean(false)
    internal val staged = AtomicBoolean(false)
    internal val consumeAttempted = AtomicBoolean(false)
}

sealed interface ForegroundAcknowledgmentStaging {
    data object Staged : ForegroundAcknowledgmentStaging
    data object AlreadyConsumed : ForegroundAcknowledgmentStaging
    data object Indeterminate : ForegroundAcknowledgmentStaging
    data object Required : ForegroundAcknowledgmentStaging
    data object Rejected : ForegroundAcknowledgmentStaging
}

/**
 * The UI derives the warning descriptor from the exact localized warning copy it renders, then
 * calls [recordExplicitAction] only from its explicit create-generation action. The digest binds
 * that copy identity but does not prove rendering or consent by itself. The action is staged against
 * the complete request and consumed durably by [verify] before the witness can receive NewlyVerified.
 * A non-suspending lifecycle callback must call [retire] before scheduling [invalidate].
 */
class ForegroundWitnessAcknowledgmentVerifier internal constructor(
    file: File,
    private val warning: ForegroundAcknowledgmentWarning,
    private val authentication: AcknowledgmentAuthentication,
    private val reporter: StorageIssueReporter = AndroidStorageIssueReporter,
    private val processId: UUID = UUID.randomUUID(),
    private val fault: ((WritePoint) -> Unit)? = null,
    private val synchronize: (File) -> Unit = AndroidWitnessDurability::synchronize,
    private val beforeAdmission: (AdmissionPoint) -> Unit = {},
) : ForegroundWitnessAcknowledgment {
    private val requestedFile = file.absoluteFile
    private val file: File by lazy {
        File(requireNotNull(requestedFile.parentFile).canonicalFile, requestedFile.name)
    }
    private val presentations = ConcurrentHashMap<UUID, ForegroundAcknowledgmentPresentation>()

    constructor(context: Context, warning: ForegroundAcknowledgmentWarning) : this(
        File(context.applicationContext.noBackupFilesDir, "notification-enrollment-ack-v1.db"),
        warning,
        AndroidAcknowledgmentAuthentication(context.applicationContext),
    )

    internal enum class WritePoint { ALLOCATED, KEY_CREATED, ROW_WRITTEN, COMMITTED }
    internal enum class AdmissionPoint { EXPLICIT_ACTION, WITNESS_CONSUMPTION }

    private enum class Admission { ADMITTED, RETIRED, ALREADY_ATTEMPTED }

    fun present(request: WitnessAuthorizationRequest): ForegroundAcknowledgmentPresentation {
        require(request.expectedCurrent == null || request.expectedCurrent.valid())
        val presentation = ForegroundAcknowledgmentPresentation(
            request = request,
            warning = warning,
            presentationId = UUID.randomUUID(),
            processId = processId,
        )
        check(presentations.putIfAbsent(request.acknowledgmentId, presentation) == null) {
            "Acknowledgment identity is already active"
        }
        return presentation
    }

    fun retire(presentation: ForegroundAcknowledgmentPresentation) {
        synchronized(presentation.lifecycleLock) {
            presentation.invalidated.set(true)
            presentations.remove(presentation.request.acknowledgmentId, presentation)
        }
    }

    suspend fun recordExplicitAction(
        presentation: ForegroundAcknowledgmentPresentation,
    ): ForegroundAcknowledgmentStaging = withContext(Dispatchers.IO) {
        beforeAdmission(AdmissionPoint.EXPLICIT_ACTION)
        val admission = synchronized(presentation.lifecycleLock) {
            when {
                !isActive(presentation) || presentation.warning != warning -> Admission.RETIRED
                !presentation.actionAttempted.compareAndSet(false, true) -> Admission.ALREADY_ATTEMPTED
                else -> Admission.ADMITTED
            }
        }
        when (admission) {
            Admission.ADMITTED -> Unit
            Admission.RETIRED -> return@withContext ForegroundAcknowledgmentStaging.Rejected
            Admission.ALREADY_ATTEMPTED -> return@withContext ForegroundAcknowledgmentStaging.Indeterminate
        }
        val evidence = Evidence(
            request = requestBytes(presentation.request),
            warning = warningBytes(presentation.warning),
            presentationId = presentation.presentationId,
            processId = presentation.processId,
            acknowledgmentId = presentation.request.acknowledgmentId,
        )
        try {
            val result = persist(evidence, Status.STAGED)
            if (result == ForegroundAcknowledgmentStaging.Staged) {
                val stillActive = synchronized(presentation.lifecycleLock) {
                    if (isActive(presentation)) presentation.staged.set(true)
                    isActive(presentation)
                }
                if (stillActive) ForegroundAcknowledgmentStaging.Staged
                else ForegroundAcknowledgmentStaging.Rejected
            } else {
                presentation.invalidated.set(true)
                result
            }
        } catch (failure: Invalid) {
            report(failure.problem)
            presentation.invalidated.set(true)
            ForegroundAcknowledgmentStaging.Indeterminate
        } catch (failure: StorageFailure) {
            reporter.report(failure)
            presentation.invalidated.set(true)
            ForegroundAcknowledgmentStaging.Indeterminate
        } catch (failure: SQLiteDatabaseCorruptException) {
            report(WitnessProblem.CORRUPT_STORAGE)
            presentation.invalidated.set(true)
            ForegroundAcknowledgmentStaging.Indeterminate
        } catch (failure: SQLiteException) {
            report(WitnessProblem.STORAGE_UNAVAILABLE)
            presentation.invalidated.set(true)
            ForegroundAcknowledgmentStaging.Indeterminate
        } catch (failure: IOException) {
            report(WitnessProblem.STORAGE_UNAVAILABLE)
            presentation.invalidated.set(true)
            ForegroundAcknowledgmentStaging.Indeterminate
        } catch (failure: SecurityException) {
            report(WitnessProblem.STORAGE_UNAVAILABLE)
            presentation.invalidated.set(true)
            ForegroundAcknowledgmentStaging.Indeterminate
        }
    }

    suspend fun invalidate(presentation: ForegroundAcknowledgmentPresentation) {
        retire(presentation)
        val evidence = Evidence(
            request = requestBytes(presentation.request),
            warning = warningBytes(presentation.warning),
            presentationId = presentation.presentationId,
            processId = presentation.processId,
            acknowledgmentId = presentation.request.acknowledgmentId,
        )
        withContext(Dispatchers.IO) {
            try {
                transition(evidence, Status.INVALIDATED)
            } catch (failure: Invalid) {
                report(failure.problem)
            } catch (failure: StorageFailure) {
                reporter.report(failure)
            } catch (failure: SQLiteDatabaseCorruptException) {
                report(WitnessProblem.CORRUPT_STORAGE)
            } catch (failure: SQLiteException) {
                report(WitnessProblem.STORAGE_UNAVAILABLE)
            } catch (failure: IOException) {
                report(WitnessProblem.STORAGE_UNAVAILABLE)
            } catch (failure: SecurityException) {
                report(WitnessProblem.STORAGE_UNAVAILABLE)
            }
        }
    }

    override suspend fun verify(request: WitnessAuthorizationRequest): ForegroundAcknowledgmentResult {
        val presentation = presentations[request.acknowledgmentId]
        val expected = presentation?.takeIf {
            isActive(it) && it.request == request && it.warning == warning
        }?.let {
            Evidence(
                request = requestBytes(request),
                warning = warningBytes(warning),
                presentationId = it.presentationId,
                processId = it.processId,
                acknowledgmentId = request.acknowledgmentId,
            )
        }
        return withContext(Dispatchers.IO) {
            try {
                val current = read(request.acknowledgmentId)
                if (current == null) {
                    if (presentation?.staged?.get() == true) ForegroundAcknowledgmentResult.Indeterminate
                    else ForegroundAcknowledgmentResult.Required
                } else {
                    if (current.evidence.request.contentEquals(requestBytes(request)).not()) {
                        return@withContext ForegroundAcknowledgmentResult.Rejected
                    }
                    if (current.status == Status.CONSUMED) {
                        return@withContext ForegroundAcknowledgmentResult.AlreadyConsumed
                    }
                    if (current.status == Status.INVALIDATED) {
                        return@withContext ForegroundAcknowledgmentResult.Rejected
                    }
                    if (expected == null || !current.evidence.sameIdentity(expected)) {
                        return@withContext ForegroundAcknowledgmentResult.Rejected
                    }
                    val handle = presentation ?: return@withContext ForegroundAcknowledgmentResult.Required
                    beforeAdmission(AdmissionPoint.WITNESS_CONSUMPTION)
                    val admission = synchronized(handle.lifecycleLock) {
                        when {
                            !isActive(handle) -> Admission.RETIRED
                            !handle.consumeAttempted.compareAndSet(false, true) -> Admission.ALREADY_ATTEMPTED
                            else -> Admission.ADMITTED
                        }
                    }
                    when (admission) {
                        Admission.ADMITTED -> Unit
                        Admission.RETIRED -> return@withContext ForegroundAcknowledgmentResult.Rejected
                        Admission.ALREADY_ATTEMPTED -> return@withContext ForegroundAcknowledgmentResult.Indeterminate
                    }
                    val consumed = transition(expected, Status.CONSUMED)
                    if (consumed == Status.CONSUMED) {
                        synchronized(handle.lifecycleLock) {
                            if (isActive(handle)) {
                                presentations.remove(request.acknowledgmentId, handle)
                                ForegroundAcknowledgmentResult.NewlyVerified(request)
                            } else ForegroundAcknowledgmentResult.Indeterminate
                        }
                    } else {
                        ForegroundAcknowledgmentResult.Indeterminate
                    }
                }
            } catch (failure: Invalid) {
                report(failure.problem)
                ForegroundAcknowledgmentResult.Indeterminate
            } catch (failure: StorageFailure) {
                reporter.report(failure)
                ForegroundAcknowledgmentResult.Indeterminate
            } catch (failure: SQLiteDatabaseCorruptException) {
                report(WitnessProblem.CORRUPT_STORAGE)
                ForegroundAcknowledgmentResult.Indeterminate
            } catch (failure: SQLiteException) {
                report(WitnessProblem.STORAGE_UNAVAILABLE)
                ForegroundAcknowledgmentResult.Indeterminate
            } catch (failure: IOException) {
                report(WitnessProblem.STORAGE_UNAVAILABLE)
                ForegroundAcknowledgmentResult.Indeterminate
            } catch (failure: SecurityException) {
                report(WitnessProblem.STORAGE_UNAVAILABLE)
                ForegroundAcknowledgmentResult.Indeterminate
            }
        }
    }

    private fun isActive(presentation: ForegroundAcknowledgmentPresentation): Boolean =
        presentation.processId == processId &&
            presentations[presentation.request.acknowledgmentId] === presentation &&
            !presentation.invalidated.get()

    private suspend fun persist(evidence: Evidence, status: Status): ForegroundAcknowledgmentStaging {
        authentication.requireAccessible()
        checkPath()
        val allocated = initializeIfNeeded()
        val staged = open().use { db ->
            configure(db)
            transaction(db) {
                if (allocated) createSchema(db)
                val history = readAll(db, allowEmpty = allocated)
                val current = history.lastByAcknowledgment[evidence.acknowledgmentId]
                if (current != null) {
                    if (!current.evidence.sameIdentity(evidence)) {
                        ForegroundAcknowledgmentStaging.Rejected
                    } else when (current.status) {
                        Status.STAGED -> ForegroundAcknowledgmentStaging.Staged
                        Status.CONSUMED -> ForegroundAcknowledgmentStaging.AlreadyConsumed
                        Status.INVALIDATED -> ForegroundAcknowledgmentStaging.Rejected
                    }
                } else {
                    if (containsOperation(history.lastByAcknowledgment.values, evidence)) {
                        ForegroundAcknowledgmentStaging.Rejected
                    } else {
                        append(db, evidence, status)
                        ForegroundAcknowledgmentStaging.Staged
                    }
                }
            }
        }
        return if (staged == ForegroundAcknowledgmentStaging.Staged) {
            synchronize(file)
            fault?.invoke(WritePoint.COMMITTED)
            val confirmed = read(acknowledgmentId(evidence.request))
            if (confirmed?.status == Status.STAGED && confirmed.evidence.sameIdentity(evidence)) staged
            else ForegroundAcknowledgmentStaging.Indeterminate
        } else staged
    }

    private fun initializeIfNeeded(): Boolean {
        if (file.exists()) {
            if (!authentication.hasKey()) throw Invalid(WitnessProblem.MISSING_AUTHENTICATOR)
            return false
        }
        if (authentication.hasKey()) throw Invalid(WitnessProblem.CORRUPT_STORAGE)
        authentication.createKey()
        fault?.invoke(WritePoint.KEY_CREATED)
        if (!file.createNewFile()) throw Invalid(WitnessProblem.STORAGE_UNAVAILABLE)
        fault?.invoke(WritePoint.ALLOCATED)
        return true
    }

    private suspend fun transition(evidence: Evidence, next: Status): Status? {
        authentication.requireAccessible()
        checkPath()
        if (!file.exists()) return null
        if (!authentication.hasKey()) throw Invalid(WitnessProblem.MISSING_AUTHENTICATOR)
        val result = open().use { db ->
            configure(db)
            transaction(db) {
                val current = readAll(db).lastByAcknowledgment[evidence.acknowledgmentId] ?: return@transaction null
                if (!current.evidence.sameIdentity(evidence)) return@transaction current.status
                if (current.status == next) return@transaction next
                if (current.status != Status.STAGED || next == Status.STAGED) return@transaction current.status
                append(db, evidence, next)
                next
            }
        } ?: return null
        synchronize(file)
        fault?.invoke(WritePoint.COMMITTED)
        val confirmed = read(evidence.let { acknowledgmentId(it.request) })
        return confirmed?.takeIf { it.evidence.sameIdentity(evidence) }?.status ?: null
    }

    private suspend fun read(acknowledgmentId: UUID): Event? {
        authentication.requireAccessible()
        checkPath()
        if (!file.exists()) return null
        if (!authentication.hasKey()) throw Invalid(WitnessProblem.MISSING_AUTHENTICATOR)
        return open().use { db ->
            configure(db)
            transaction(db) {
                readAll(db).lastByAcknowledgment[acknowledgmentId]
            }
        }
    }

    private fun createSchema(db: SQLiteDatabase) {
        if (db.version != 0 || db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name != 'android_metadata'",
                null,
            ).use { it.moveToFirst() }
        ) throw Invalid(WitnessProblem.CORRUPT_STORAGE)
        db.execSQL("CREATE TABLE acknowledgment_history(sequence INTEGER PRIMARY KEY, payload BLOB NOT NULL, mac BLOB NOT NULL)")
        db.execSQL("CREATE TABLE acknowledgment_head(singleton INTEGER PRIMARY KEY CHECK(singleton=1), sequence INTEGER NOT NULL, mac BLOB NOT NULL)")
        db.version = DATABASE_VERSION
    }

    private fun append(db: SQLiteDatabase, evidence: Evidence, status: Status) {
        val history = readAll(db, allowEmpty = true)
        if (history.sequence == Long.MAX_VALUE) throw Invalid(WitnessProblem.CORRUPT_STORAGE)
        val previous = history.lastByAcknowledgment[evidence.let { acknowledgmentId(it.request) }]
        if (previous == null && status != Status.STAGED ||
            previous != null && (previous.status != Status.STAGED || status == Status.STAGED)
        ) throw Invalid(WitnessProblem.INVALID_RECEIPT)
        if (previous != null && !previous.evidence.sameIdentity(evidence)) {
            throw Invalid(WitnessProblem.ACKNOWLEDGMENT_MISMATCH)
        }
        val row = Event(history.sequence + 1, evidence, status, history.lastMac)
        val payload = encode(row)
        val mac = authentication.authenticate(payload)
        db.execSQL("INSERT INTO acknowledgment_history VALUES(?,?,?)", arrayOf(row.sequence, payload, mac))
        db.execSQL(
            "INSERT OR REPLACE INTO acknowledgment_head VALUES(1,?,?)",
            arrayOf(row.sequence, authentication.authenticate(headBytes(row.sequence, mac))),
        )
        fault?.invoke(WritePoint.ROW_WRITTEN)
    }

    private fun readAll(db: SQLiteDatabase, allowEmpty: Boolean = false): History {
        if (db.version != DATABASE_VERSION ||
            db.rawQuery("PRAGMA quick_check", null).use { !it.moveToFirst() || it.getString(0) != "ok" }
        ) throw Invalid(WitnessProblem.CORRUPT_STORAGE)
        val latest = LinkedHashMap<UUID, Event>()
        val presentations = HashSet<UUID>()
        val operationIds = HashSet<UUID>()
        val storeIds = HashSet<UUID>()
        val creationNonces = HashSet<UUID>()
        var sequence = 0L
        var previousMac = ZERO_MAC
        db.rawQuery("SELECT sequence,payload,mac FROM acknowledgment_history ORDER BY sequence", null).use { rows ->
            while (rows.moveToNext()) {
                if (sequence == Long.MAX_VALUE) throw Invalid(WitnessProblem.CORRUPT_STORAGE)
                val payload = rows.getBlob(1)
                val event = decode(payload).copy(mac = rows.getBlob(2))
                val id = acknowledgmentId(event.evidence.request)
                if (event.sequence != rows.getLong(0) || event.sequence != sequence + 1 ||
                    !MessageDigest.isEqual(event.previousMac, previousMac) ||
                    !MessageDigest.isEqual(payload, encode(event)) ||
                    !MessageDigest.isEqual(event.mac, authentication.authenticate(payload))
                ) throw Invalid(WitnessProblem.AUTHENTICATION_FAILED)
                val old = latest[id]
                if (old == null) {
                    val operation = operationIdentity(event.evidence.request)
                    if (event.status != Status.STAGED ||
                        !presentations.add(event.evidence.presentationId) ||
                        !operationIds.add(operation.first) ||
                        !storeIds.add(operation.second) ||
                        !creationNonces.add(operation.third)
                    ) {
                        throw Invalid(WitnessProblem.CORRUPT_STORAGE)
                    }
                } else if (old.status != Status.STAGED || event.status == Status.STAGED ||
                    !old.evidence.sameIdentity(event.evidence)
                ) throw Invalid(WitnessProblem.CORRUPT_STORAGE)
                latest[id] = event
                sequence = event.sequence
                previousMac = event.mac
            }
        }
        if (sequence == 0L) {
            if (!allowEmpty || db.rawQuery("SELECT 1 FROM acknowledgment_head", null).use { it.moveToFirst() }) {
                throw Invalid(WitnessProblem.CORRUPT_STORAGE)
            }
            return History(0, ZERO_MAC, latest)
        }
        db.rawQuery("SELECT singleton,sequence,mac FROM acknowledgment_head", null).use { head ->
            if (!head.moveToFirst() || head.getInt(0) != 1 || head.getLong(1) != sequence ||
                !MessageDigest.isEqual(
                    head.getBlob(2),
                    authentication.authenticate(headBytes(sequence, previousMac)),
                ) || head.moveToNext()
            ) throw Invalid(WitnessProblem.AUTHENTICATION_FAILED)
        }
        return History(sequence, previousMac, latest)
    }

    private fun configure(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA journal_mode=DELETE", null).use {
            if (!it.moveToFirst() || it.getString(0).lowercase() != "delete") {
                throw Invalid(WitnessProblem.STORAGE_UNAVAILABLE)
            }
        }
        db.execSQL("PRAGMA synchronous=FULL")
    }

    private fun checkPath() {
        if (requestedFile.parentFile.canonicalFile != file.parentFile ||
            Files.isSymbolicLink(file.toPath()) || file.canonicalFile != file.absoluteFile ||
            file.exists() && !file.isFile
        ) throw Invalid(WitnessProblem.CORRUPT_STORAGE)
    }

    private fun open(): SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.absolutePath,
        null,
        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        DatabaseErrorHandler { throw Invalid(WitnessProblem.CORRUPT_STORAGE) },
    )

    private inline fun <T> transaction(db: SQLiteDatabase, action: () -> T): T {
        db.beginTransaction()
        return try {
            action().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    private fun report(problem: WitnessProblem) {
        val storageProblem = when (problem) {
            WitnessProblem.LOCKED -> StorageProblem.DeviceLocked
            WitnessProblem.MISSING_AUTHENTICATOR -> StorageProblem.FormerKeyMissing
            WitnessProblem.AUTHENTICATION_FAILED -> StorageProblem.AuthenticationFailed
            WitnessProblem.CORRUPT_STORAGE, WitnessProblem.INVALID_RECEIPT -> StorageProblem.CorruptSecretState
            else -> StorageProblem.IoFailure
        }
        reporter.report(
            StorageFailure(
                storageProblem,
                StorageOperation.WRITE,
            ),
        )
    }

    private fun requestBytes(request: WitnessAuthorizationRequest): ByteArray = bytes {
        writeUTF("MeshCoreOne.notification-ack.request.v1")
        uuid(request.operation.operationId)
        uuid(request.operation.storeId)
        uuid(request.operation.creationNonce)
        uuid(request.acknowledgmentId)
        writeBoolean(request.expectedCurrent != null)
        request.expectedCurrent?.let { record(it) }
    }

    private fun warningBytes(value: ForegroundAcknowledgmentWarning): ByteArray = bytes {
        writeUTF("MeshCoreOne.notification-ack.warning.v1")
        writeUTF(value.id)
        writeInt(value.version)
        writeUTF(value.renderedCopySha256)
    }

    private fun encode(event: Event): ByteArray = bytes {
        writeUTF("MeshCoreOne.notification-ack.history.v1")
        writeLong(event.sequence)
        writeLengthPrefixed(event.evidence.request)
        writeLengthPrefixed(event.evidence.warning)
        uuid(event.evidence.presentationId)
        uuid(event.evidence.processId)
        writeUTF(event.status.name)
        write(event.previousMac)
    }

    private fun decode(payload: ByteArray): Event = try {
        if (payload.size > MAX_RECORD_BYTES) throw Invalid(WitnessProblem.CORRUPT_STORAGE)
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            if (input.readUTF() != "MeshCoreOne.notification-ack.history.v1") {
                throw Invalid(WitnessProblem.CORRUPT_STORAGE)
            }
            val sequence = input.readLong()
            val request = input.readBoundedBytes()
            val warning = input.readBoundedBytes()
            val presentation = input.uuid()
            val process = input.uuid()
            val statusName = input.readUTF()
            val status = Status.entries.firstOrNull { it.name == statusName }
                ?: throw Invalid(WitnessProblem.CORRUPT_STORAGE)
            val previousMac = ByteArray(MAC_BYTES).also { input.readFully(it) }
            if (input.available() != 0) throw Invalid(WitnessProblem.CORRUPT_STORAGE)
            Event(
                sequence,
                Evidence(request, warning, presentation, process, acknowledgmentId(request)),
                status,
                previousMac,
            )
        }
    } catch (failure: IOException) {
        throw Invalid(WitnessProblem.CORRUPT_STORAGE)
    }

    private fun DataOutputStream.record(value: EnrollmentWitnessRecord) {
        uuid(value.operation.operationId)
        uuid(value.operation.storeId)
        uuid(value.operation.creationNonce)
        uuid(value.witnessId)
        writeLong(value.revision)
        writeUTF(value.phase.name)
        writeBoolean(value.receipt != null)
        value.receipt?.let { receipt ->
            uuid(receipt.operation.operationId)
            uuid(receipt.operation.storeId)
            uuid(receipt.operation.creationNonce)
            writeLong(receipt.revision)
            writeUTF(receipt.phase.name)
            uuid(receipt.creationNonce)
            writeBoolean(receipt.witnessId != null)
            receipt.witnessId?.let { id -> uuid(id) }
        }
    }

    private fun Evidence.sameIdentity(other: Evidence): Boolean =
        request.contentEquals(other.request) && warning.contentEquals(other.warning) &&
            presentationId == other.presentationId && processId == other.processId &&
            acknowledgmentId == other.acknowledgmentId

    private fun containsOperation(events: Collection<Event>, evidence: Evidence): Boolean {
        val operation = operationIdentity(evidence.request)
        return events.any { event ->
            val existing = operationIdentity(event.evidence.request)
            existing.first == operation.first ||
                existing.second == operation.second ||
                existing.third == operation.third
        }
    }

    private fun DataInputStream.readBoundedBytes(): ByteArray {
        val length = readInt()
        if (length !in 0..MAX_RECORD_BYTES || length > available()) throw Invalid(WitnessProblem.CORRUPT_STORAGE)
        return ByteArray(length).also { readFully(it) }
    }

    private fun DataOutputStream.uuid(id: UUID) {
        writeLong(id.mostSignificantBits)
        writeLong(id.leastSignificantBits)
    }

    private fun DataInputStream.uuid() = UUID(readLong(), readLong())

    private fun DataOutputStream.writeLengthPrefixed(value: ByteArray) {
        writeInt(value.size)
        write(value, 0, value.size)
    }

    private fun headBytes(sequence: Long, mac: ByteArray): ByteArray = bytes {
        writeUTF("MeshCoreOne.notification-ack.head.v1")
        writeLong(sequence)
        write(mac)
    }

    private fun bytes(write: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { stream -> write(stream) }
            output.toByteArray()
        }

    private fun acknowledgmentId(request: ByteArray): UUID = try {
        DataInputStream(ByteArrayInputStream(request)).use { input ->
            if (input.readUTF() != "MeshCoreOne.notification-ack.request.v1") {
                throw Invalid(WitnessProblem.CORRUPT_STORAGE)
            }
            input.readUuid()
            input.readUuid()
            input.readUuid()
            input.readUuid()
        }
    } catch (failure: IOException) {
        throw Invalid(WitnessProblem.CORRUPT_STORAGE)
    }

    private fun operationIdentity(request: ByteArray): Triple<UUID, UUID, UUID> = try {
        DataInputStream(ByteArrayInputStream(request)).use { input ->
            if (input.readUTF() != "MeshCoreOne.notification-ack.request.v1") {
                throw Invalid(WitnessProblem.CORRUPT_STORAGE)
            }
            Triple(input.readUuid(), input.readUuid(), input.readUuid())
        }
    } catch (failure: IOException) {
        throw Invalid(WitnessProblem.CORRUPT_STORAGE)
    }

    private fun DataInputStream.readUuid() = UUID(readLong(), readLong())

    private data class Evidence(
        val request: ByteArray,
        val warning: ByteArray,
        val presentationId: UUID,
        val processId: UUID,
        val acknowledgmentId: UUID,
    )

    private data class Event(
        val sequence: Long,
        val evidence: Evidence,
        val status: Status,
        val previousMac: ByteArray,
        val mac: ByteArray = ZERO_MAC,
    )

    private data class History(
        val sequence: Long,
        val lastMac: ByteArray,
        val lastByAcknowledgment: Map<UUID, Event>,
    )

    private enum class Status { STAGED, CONSUMED, INVALIDATED }

    private class Invalid(val problem: WitnessProblem) : Exception()

    private companion object {
        const val DATABASE_VERSION = 1
        const val MAX_RECORD_BYTES = 16 * 1024
        const val MAC_BYTES = 32
        val ZERO_MAC = ByteArray(MAC_BYTES)
    }
}

internal interface AcknowledgmentAuthentication {
    fun requireAccessible()
    fun hasKey(): Boolean
    fun createKey()
    fun authenticate(bytes: ByteArray): ByteArray
}

private class AndroidAcknowledgmentAuthentication(context: Context) : AcknowledgmentAuthentication {
    private val access = AndroidStorageAccess(context, secrets = true)

    override fun requireAccessible() = access.requireAccessible(StorageOperation.READ)

    override fun hasKey(): Boolean {
        requireAccessible()
        return keyStore().containsAlias(KEY_ALIAS)
    }

    override fun createKey() {
        platform(StorageOperation.WRITE) {
            access.requireAccessible(StorageOperation.WRITE)
            if (keyStore().containsAlias(KEY_ALIAS)) {
                throw StorageFailure(StorageProblem.SecretAlreadyExists, StorageOperation.WRITE)
            }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    ).setKeySize(256)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .setUnlockedDeviceRequired(Build.VERSION.SDK_INT >= 35)
                        .build(),
                )
                generateKey()
            }
        }
    }

    override fun authenticate(bytes: ByteArray): ByteArray = platform(StorageOperation.READ) {
        requireAccessible()
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw StorageFailure(StorageProblem.FormerKeyMissing, StorageOperation.READ)
        Mac.getInstance("HmacSHA256").run {
            init(key)
            doFinal(bytes)
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private inline fun <T> platform(operation: StorageOperation, action: () -> T): T = try {
        action()
    } catch (failure: android.security.keystore.UserNotAuthenticatedException) {
        throw StorageFailure(StorageProblem.DeviceLocked, operation, failure)
    } catch (failure: android.security.keystore.KeyPermanentlyInvalidatedException) {
        throw StorageFailure(StorageProblem.KeyPermanentlyInvalidated, operation, failure)
    } catch (failure: GeneralSecurityException) {
        throw StorageFailure(StorageProblem.ProviderFailure, operation, failure)
    } catch (failure: ProviderException) {
        throw StorageFailure(StorageProblem.ProviderFailure, operation, failure)
    } catch (failure: IOException) {
        throw StorageFailure(StorageProblem.IoFailure, operation, failure)
    }

    private companion object {
        const val KEY_ALIAS = "meshcoreone.notification-enrollment-ack.v1"
    }
}
