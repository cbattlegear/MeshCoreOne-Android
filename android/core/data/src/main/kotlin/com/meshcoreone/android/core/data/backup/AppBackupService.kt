// PortedFrom: MC1Services/Sources/MC1Services/Services/AppBackupService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.DatabaseValueException
import com.meshcoreone.android.core.datastore.BackupPreferenceStore
import com.meshcoreone.android.core.datastore.StorageFailure
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.IOException
import java.io.OutputStream
import java.time.Clock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ExportResult(val data: Bytes, val manifest: BackupManifest)

/** Rows have committed; retry only the idempotent write-if-missing preference completion. */
class CommittedBackupPreferenceException(
    val result: ImportResult,
    val preferences: BackupUserDefaults,
    val storageFailure: StorageFailure,
) : Exception("Backup rows committed; preference completion failed", storageFailure), CommittedBackupPreferenceFailure {
    override val committedReceipt: CommittedBackupReceipt = CommittedBackupReceipt(
        result.counts.entries.associate { (kind, counts) ->
            kind.arrayKey to CommittedBackupCounts(counts.inserted, counts.merged, counts.skipped, counts.dropped)
        },
        result.userDefaultsRestored,
        result.channelSlotsAffectedByImport,
    )
    override val preferenceFailure: Throwable get() = storageFailure
}

internal data class BackupImportHooks(
    val beforeWrite: suspend (BackupModelKind) -> Unit = {},
    val beforeCommit: suspend () -> Unit = {},
    val afterCommit: () -> Unit = {},
)

class AppBackupService internal constructor(
    private val codec: AppBackupCodec,
    private val preferences: BackupPreferenceStore,
    private val appVersion: String,
    private val appBuild: String,
    private val clock: Clock,
    private val hooks: BackupImportHooks,
) {
    constructor(
        preferences: BackupPreferenceStore,
        appVersion: String,
        appBuild: String,
        clock: Clock = Clock.systemUTC(),
    ) : this(AppBackupCodec(), preferences, appVersion, appBuild, clock, BackupImportHooks())

    private val mutex = Mutex()

    suspend fun exportEnvelope(store: RoomPersistenceStore): AppBackupEnvelope = mutex.withLock {
        exportOperation {
            val snapshot = store.withBackupSnapshot { database, _ -> database.backupEnvelope(appVersion, appBuild, clock.instant()) }
            snapshot.copy(userDefaults = BackupUserDefaults.snapshot(preferences))
        }
    }

    suspend fun export(store: RoomPersistenceStore): ExportResult {
        val envelope = exportEnvelope(store)
        val caller = currentCoroutineContext()
        return exportOperation {
            val output = java.io.ByteArrayOutputStream(BACKUP_STREAM_CHUNK)
            codec.encode(envelope, output, caller::ensureActive)
            ExportResult(Bytes(output.toByteArray()), envelope.manifest)
        }
    }

    suspend fun export(store: RoomPersistenceStore, output: OutputStream): BackupManifest {
        return exportOperation {
            output.use { destination ->
                val envelope = exportEnvelope(store)
                val caller = currentCoroutineContext()
                val borrowed = object : OutputStream() {
                    override fun write(value: Int) = destination.write(value)
                    override fun write(buffer: ByteArray, offset: Int, length: Int) = destination.write(buffer, offset, length)
                    override fun flush() = destination.flush()
                    override fun close() = Unit
                }
                codec.encode(envelope, borrowed, caller::ensureActive)
                envelope.manifest
            }
        }
    }

    suspend fun importBackup(envelope: AppBackupEnvelope, store: RoomPersistenceStore): ImportResult = mutex.withLock {
        envelope.validate()
        validateRelationships(envelope)
        currentCoroutineContext().ensureActive()
        val result = try {
            store.withBackupRestore(hooks.afterCommit) { database, storeClock ->
                val value = BackupDatabaseRestore(database, storeClock, hooks.beforeWrite).restore(envelope)
                hooks.beforeCommit()
                currentCoroutineContext().ensureActive()
                value
            }
        } catch (cause: PersistenceStoreException) {
            throw AppBackupException(AppBackupError.ImportFailed(cause))
        } catch (cause: BackupValueException) {
            throw AppBackupException(AppBackupError.ImportFailed(cause))
        } catch (cause: IOException) {
            throw AppBackupException(AppBackupError.ImportFailed(cause))
        }
        val snapshot = envelope.userDefaults ?: return@withLock result
        withContext(NonCancellable) {
            try {
                result.copy(userDefaultsRestored = snapshot.restore(preferences).isNotEmpty())
            } catch (cause: StorageFailure) {
                throw CommittedBackupPreferenceException(result, snapshot, cause)
            }
        }
    }

    suspend fun completePreferences(failure: CommittedBackupPreferenceException): ImportResult =
        failure.result.copy(userDefaultsRestored = failure.preferences.restore(preferences).isNotEmpty())

    private suspend fun <T> exportOperation(operation: suspend () -> T): T = try {
        operation()
    } catch (cause: PersistenceStoreException) {
        throw AppBackupException(AppBackupError.ExportFailed(cause))
    } catch (cause: StorageFailure) {
        throw AppBackupException(AppBackupError.ExportFailed(cause))
    } catch (cause: BackupValueException) {
        throw AppBackupException(AppBackupError.ExportFailed(cause))
    } catch (cause: DatabaseValueException) {
        throw AppBackupException(AppBackupError.ExportFailed(cause))
    } catch (cause: IOException) {
        throw AppBackupException(AppBackupError.ExportFailed(cause))
    }
}

internal fun validateRelationships(envelope: AppBackupEnvelope) {
    requireUnambiguousRadios(envelope.contacts.map { it.id to it.radioId }, "contacts")
    requireUnambiguousRadios(envelope.messages.map { it.id to it.radioId }, "messages")
    requireUnambiguousRadios(envelope.remoteNodeSessions.map { it.id to it.radioId }, "remoteNodeSessions")
    val radioKeys = envelope.devices.groupBy { it.radioId }
    if (radioKeys.values.any { rows -> rows.map { it.publicKey }.distinct().size > 1 }) {
        invalidValue("devices.radioID", BackupValueProblem.IDENTITY)
    }
    val messages = envelope.messages.associate { it.id to it.radioId }
    val contacts = envelope.contacts.associate { it.id to it.radioId }
    for (message in envelope.messages) {
        if (message.contactID?.let { contacts[it]?.let { parent -> parent != message.radioId } } == true ||
            message.replyToID?.let { messages[it]?.let { parent -> parent != message.radioId } } == true) {
            invalidValue("messages.parent", BackupValueProblem.RELATIONSHIP)
        }
    }
    for (reaction in envelope.reactions) {
        if (messages[reaction.messageID]?.let { it != reaction.radioId } == true) {
            invalidValue("reactions.messageID", BackupValueProblem.RELATIONSHIP)
        }
    }
}
