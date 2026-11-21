// PortedFrom: MC1Services/Sources/MC1Services/Services/AppStorageKey.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Models/DevicePreferenceStore.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/SceneStorageKey.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

enum class PreferenceGuardResult { STARTED, REJECTED, STALE }

class PreferenceStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val access: StorageAccess,
    private val reporter: StorageIssueReporter,
) {
    private val admissionLock = Any()
    private var writeGeneration = Any()
    private var activeWrites = 0
    private var writesSettled: CompletableDeferred<Unit>? = null
    private var cancelledWriteUnsettled = false

    val snapshots: Flow<PreferenceSnapshot> = flow {
        access.requireAccessible(StorageOperation.OBSERVE)
        dataStore.data.collect { preferences ->
            access.requireAccessible(StorageOperation.OBSERVE)
            emit(PreferenceSnapshot.from(preferences).also(::validateKnownPreferences))
        }
    }.catch { failure ->
        throw reportStorageFailure(failure, StorageOperation.OBSERVE, reporter)
    }.distinctUntilChanged()

    val states: Flow<StoreState<PreferenceSnapshot>> = flow<StoreState<PreferenceSnapshot>> {
        emit(StoreState.Loading)
        snapshots.collect { emit(StoreState.Ready(it)) }
    }.catch { failure ->
        if (failure is StorageFailure) emit(StoreState.Failed(failure)) else throw failure
    }

    suspend fun snapshot(): PreferenceSnapshot = snapshots.first()
    suspend fun <T : Any> get(key: PreferenceKey<T>): T {
        val snapshot = snapshot()
        return decode(snapshot, key)
    }

    /**
     * Read outside the consumer's operation gate, then use [GuardedPreference.runIfCurrent]
     * to make a synchronous decision and start an operation without crossing a preference write.
     * Any preference update invalidates the result, including failed or cancelled updates.
     */
    suspend fun <T : Any> prepareGuard(key: PreferenceKey<T>): GuardedPreference<T> {
        while (true) {
            val pending = synchronized(admissionLock) { writesSettled }
            if (pending == null) break
            pending.await()
        }
        val (generation, needsBarrier) = synchronized(admissionLock) { writeGeneration to cancelledWriteUnsettled }
        if (needsBarrier) {
            try {
                access.requireAccessible(StorageOperation.OBSERVE)
                // A cancelled caller can stop awaiting before DataStore's queued write finishes.
                dataStore.updateData { it }
            } catch (failure: StorageFailure) {
                throw reportStorageFailure(failure, StorageOperation.OBSERVE, reporter)
            } catch (failure: CorruptionException) {
                throw reportStorageFailure(failure, StorageOperation.OBSERVE, reporter)
            } catch (failure: IOException) {
                throw reportStorageFailure(failure, StorageOperation.OBSERVE, reporter)
            } catch (failure: SecurityException) {
                throw reportStorageFailure(failure, StorageOperation.OBSERVE, reporter)
            }
        }
        val value = get(key)
        synchronized(admissionLock) {
            if (needsBarrier && activeWrites == 0 && generation === writeGeneration) {
                cancelledWriteUnsettled = false
            }
        }
        return GuardedPreference(value, generation)
    }

    inner class GuardedPreference<T : Any> internal constructor(
        val value: T,
        private val generation: Any,
    ) {
        /** A hint only; a write may begin immediately after this returns. */
        fun isCurrent(): Boolean = synchronized(admissionLock) {
            activeWrites == 0 && !cancelledWriteUnsettled && generation === writeGeneration
        }

        /** The action must be synchronous and must not update preferences. */
        fun runIfCurrent(predicate: (T) -> Boolean, action: () -> Unit): PreferenceGuardResult =
            synchronized(admissionLock) {
                if (activeWrites != 0 || cancelledWriteUnsettled || generation !== writeGeneration) {
                    PreferenceGuardResult.STALE
                } else {
                    try {
                        access.requireAccessible(StorageOperation.OBSERVE)
                    } catch (failure: StorageFailure) {
                        reporter.report(failure)
                        throw failure
                    }
                    if (!predicate(value)) {
                        PreferenceGuardResult.REJECTED
                    } else {
                        action()
                        PreferenceGuardResult.STARTED
                    }
                }
            }
    }

    fun <T : Any> observe(key: PreferenceKey<T>): Flow<T> =
        snapshots.map { decode(it, key) }.distinctUntilChanged()

    private fun <T : Any> decode(snapshot: PreferenceSnapshot, key: PreferenceKey<T>): T = try {
        snapshot[key]
    } catch (failure: StorageFailure) {
        reporter.report(failure)
        throw failure
    }

    suspend fun update(transform: PreferenceEditor.() -> Unit): PreferenceSnapshot {
        synchronized(admissionLock) {
            if (activeWrites == 0) writesSettled = CompletableDeferred()
            activeWrites++
            writeGeneration = Any()
        }
        try {
            access.requireAccessible(StorageOperation.WRITE)
            val result = dataStore.edit { preferences ->
                access.requireAccessible(StorageOperation.WRITE)
                PreferenceEditor(preferences).transform()
            }
            return PreferenceSnapshot.from(result)
        } catch (failure: CancellationException) {
            synchronized(admissionLock) { cancelledWriteUnsettled = true }
            throw failure
        } catch (failure: StorageFailure) {
            reporter.report(failure)
            throw failure
        } catch (failure: CorruptionException) {
            throw reportStorageFailure(failure, StorageOperation.WRITE, reporter)
        } catch (failure: IOException) {
            throw reportStorageFailure(failure, StorageOperation.WRITE, reporter)
        } catch (failure: SecurityException) {
            throw reportStorageFailure(failure, StorageOperation.WRITE, reporter)
        } finally {
            val settled = synchronized(admissionLock) {
                activeWrites--
                writeGeneration = Any()
                if (activeWrites == 0) writesSettled.also { writesSettled = null } else null
            }
            settled?.complete(Unit)
        }
    }

    suspend fun <T : Any> set(key: PreferenceKey<T>, value: T) {
        update { this[key] = value }
    }

    suspend fun remove(key: PreferenceKey<*>) {
        update { remove(key) }
    }

    internal fun reject(problem: StorageProblem, operation: StorageOperation): Nothing {
        throw StorageFailure(problem, operation).also(reporter::report)
    }

    suspend fun resetAppPreferences() {
        update {
            AppStorageKey.all.forEach(::remove)
            remove(AppearanceStorageKey.selectedThemeID)
            remove(AppearanceStorageKey.appColorSchemePreference)
            remove(BackupPreferenceKeys.regionSelection)
        }
    }
}

class DevicePreferenceStore(private val store: PreferenceStore) {
    suspend fun isAutoUpdateLocationEnabled(deviceId: UUID): Boolean = store.get(autoUpdateLocationKey(deviceId))
    suspend fun setAutoUpdateLocationEnabled(enabled: Boolean, deviceId: UUID) {
        store.set(autoUpdateLocationKey(deviceId), enabled)
    }

    suspend fun gpsSource(deviceId: UUID): GPSSource {
        val raw = store.get(gpsSourceKey(deviceId))
        return GPSSource.entries.firstOrNull { it.rawValue == raw } ?: GPSSource.PHONE
    }

    suspend fun hasSetGPSSource(deviceId: UUID): Boolean = store.snapshot().contains(gpsSourceKey(deviceId))
    suspend fun setGPSSource(source: GPSSource, deviceId: UUID) {
        store.set(gpsSourceKey(deviceId), source.rawValue)
    }

    fun observe(deviceId: UUID): Flow<DevicePreferenceSnapshot> = store.snapshots.map { values ->
        val raw = values.stored(gpsSourceKey(deviceId))
        DevicePreferenceSnapshot(
            values[autoUpdateLocationKey(deviceId)], raw,
            GPSSource.entries.firstOrNull { it.rawValue == raw } ?: GPSSource.PHONE,
        )
    }.distinctUntilChanged()

    suspend fun reset(deviceId: UUID) {
        store.update {
            remove(autoUpdateLocationKey(deviceId))
            remove(gpsSourceKey(deviceId))
        }
    }
}

data class DevicePreferenceSnapshot(val autoUpdateLocation: Boolean, val gpsSourceRaw: String?, val gpsSource: GPSSource) {
    val hasSetGPSSource: Boolean get() = gpsSourceRaw != null
}

class ScenePreferenceStore(private val store: PreferenceStore) {
    suspend fun get(sceneId: UUID, key: SceneStorageKey): String = store.get(key.scoped(sceneId))
    suspend fun set(sceneId: UUID, key: SceneStorageKey, value: String) = store.set(key.scoped(sceneId), value)
    fun observe(sceneId: UUID, key: SceneStorageKey): Flow<String> = store.observe(key.scoped(sceneId))
    suspend fun reset(sceneId: UUID) {
        store.update { SceneStorageKey.entries.forEach { remove(it.scoped(sceneId)) } }
    }
}

internal fun reportStorageFailure(
    failure: Throwable,
    operation: StorageOperation,
    reporter: StorageIssueReporter,
    corrupt: StorageProblem = StorageProblem.CorruptPreferences,
): StorageFailure {
    val typed = when (failure) {
        is StorageFailure -> failure
        is CorruptionException -> StorageFailure(corrupt, operation, failure)
        is IOException -> StorageFailure(StorageProblem.IoFailure, operation, failure)
        is SecurityException -> StorageFailure(StorageProblem.PermissionDenied, operation, failure)
        else -> throw failure
    }
    reporter.report(typed)
    return typed
}
