// AndroidOnly: WP-204 One process owner for credential-protected, no-auto-backup DataStore files.
package com.meshcoreone.android.core.datastore

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.FileStorage
import androidx.datastore.core.ReadScope
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MeshCoreStorage private constructor(
    private val directory: File,
    preferenceAccess: StorageAccess,
    secretAccess: StorageAccess,
    cryptography: SecretCryptography,
    reporter: StorageIssueReporter,
    dispatcher: CoroutineDispatcher,
    aliasPrefix: String,
) {
    private val closed = AtomicBoolean(false)
    private val closeMutex = Mutex()
    private var closeCompleted = false
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + dispatcher)
    private val preferenceGuard = guarded(preferenceAccess)
    private val secretGuard = guarded(secretAccess)

    val preferences = PreferenceStore(
        PreferenceDataStoreFactory.create(
            storage = SerializedFileStorage(
                FileStorage(BoundedPreferencesSerializer()) { File(directory, PREFERENCE_FILENAME) },
            ),
            corruptionHandler = null,
            migrations = emptyList(),
            scope = scope,
        ),
        preferenceGuard, reporter,
    )
    val devicePreferences = DevicePreferenceStore(preferences)
    val scenePreferences = ScenePreferenceStore(preferences)
    val appearancePreferences = AppearancePreferenceStore(preferences)
    val backupPreferences = BackupPreferenceStore(preferences)
    val secrets = SecretStore(
        DataStoreFactory.create(
            storage = SerializedFileStorage(
                FileStorage(SecretArchiveSerializer()) { File(directory, SECRET_FILENAME) },
            ),
            scope = scope,
        ),
        cryptography, secretGuard, reporter, aliasPrefix,
    )

    private val notificationMutex = Mutex()
    private var notifications: NotificationPreferenceStore? = null

    suspend fun notificationPreferences(): NotificationPreferenceStore = notificationMutex.withLock {
        preferenceGuard.requireAccessible(StorageOperation.OPEN)
        notifications ?: NotificationPreferenceStore.create(preferences, scope).also { notifications = it }
    }

    suspend fun close() {
        withContext(NonCancellable) {
            closeMutex.withLock {
                if (!closeCompleted) {
                    closed.set(true)
                    lifetime.cancelAndJoin()
                    synchronized(owners) {
                        if (owners[directory.path] === this@MeshCoreStorage) owners.remove(directory.path)
                    }
                    closeCompleted = true
                }
            }
        }
        currentCoroutineContext().ensureActive()
    }

    private fun guarded(access: StorageAccess) = StorageAccess { operation ->
        if (closed.get()) throw StorageFailure(StorageProblem.OwnerClosed, operation)
        access.requireAccessible(operation)
    }

    companion object {
        internal const val PREFERENCE_FILENAME = "app.preferences_pb"
        internal const val SECRET_FILENAME = "secrets.bin"
        private val owners = mutableMapOf<String, MeshCoreStorage>()

        fun get(context: Context, reporter: StorageIssueReporter = AndroidStorageIssueReporter): MeshCoreStorage {
            val app = context.applicationContext
            val preferences = AndroidStorageAccess(app, secrets = false)
            val secrets = AndroidStorageAccess(app, secrets = true)
            try {
                preferences.requireAccessible(StorageOperation.OPEN)
                val directory = File(app.noBackupFilesDir, "meshcoreone-datastore").canonicalFile
                return synchronized(owners) {
                    owners[directory.path] ?: createOwned(
                        directory, AndroidKeystoreCryptography(app), reporter, preferences, secrets,
                        Dispatchers.IO, "${app.packageName}.datastore.keystore.v1",
                    )
                }
            } catch (failure: StorageFailure) {
                reporter.report(failure)
                throw failure
            } catch (failure: IOException) {
                val typed = StorageFailure(StorageProblem.IoFailure, StorageOperation.OPEN, failure)
                reporter.report(typed)
                throw typed
            } catch (failure: SecurityException) {
                val typed = StorageFailure(StorageProblem.PermissionDenied, StorageOperation.OPEN, failure)
                reporter.report(typed)
                throw typed
            }
        }

        internal fun createOwned(
            directory: File,
            cryptography: SecretCryptography,
            reporter: StorageIssueReporter,
            preferenceAccess: StorageAccess,
            secretAccess: StorageAccess,
            dispatcher: CoroutineDispatcher,
            aliasPrefix: String,
        ): MeshCoreStorage {
            val canonical = directory.canonicalFile
            return synchronized(owners) {
                if (owners.containsKey(canonical.path)) {
                    throw StorageFailure(StorageProblem.DuplicateOwner, StorageOperation.OPEN).also(reporter::report)
                }
                MeshCoreStorage(
                    canonical, preferenceAccess, secretAccess, cryptography, reporter, dispatcher,
                    aliasPrefix,
                ).also { owners[canonical.path] = it }
            }
        }
    }
}

internal class SerializedFileStorage<T>(private val storage: Storage<T>) : Storage<T> {
    override fun createConnection(): StorageConnection<T> {
        val connection = storage.createConnection()
        val transactions = Mutex()
        return object : StorageConnection<T> by connection {
            override suspend fun <R> readScope(block: suspend ReadScope<T>.(Boolean) -> R): R =
                transactions.withLock { connection.readScope(block) }

            // DataStore 1.2.1 increments its version inside this scope, before file replacement.
            // Keep a starting collector from pairing that new version with the previous file.
            override suspend fun writeScope(block: suspend WriteScope<T>.() -> Unit) {
                transactions.withLock { connection.writeScope(block) }
            }
        }
    }
}
