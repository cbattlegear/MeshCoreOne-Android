// AndroidOnly: WP-307 Coordinated preference admission across real DataStore writes.
package com.meshcoreone.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.FileStorage
import androidx.datastore.core.Serializer
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PreferenceGuardTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = AppStorageKey.showMapPreviewThumbnails

    @Test
    fun updatesResetBackupRestoreAndFailuresInvalidateTokens() = runBlocking {
        withStorage(temporary.newFolder()) { h ->
            val preferences = h.owner.preferences
            fun PreferenceStore.GuardedPreference<Boolean>.attempt() = runIfCurrent({ it }) {
                error("A stale or disabled guard must not start")
            }

            @Test
            fun guardRevisionChangesEvenWhenPreferenceReturnsToItsOriginalValue() = runBlocking {
                withStorage(temporary.newFolder()) { h ->
                    val preferences = h.owner.preferences
                    val before = preferences.guardRevision.value
                    preferences.set(key, false)
                    preferences.set(key, true)
                    assertTrue(preferences.guardRevision.value != before)
                    var started = false
                    assertEquals(PreferenceGuardResult.STARTED, preferences.prepareGuard(key).runIfCurrent({ it }) { started = true })
                    assertTrue(started)
                }
            }

            val enabled = preferences.prepareGuard(key)
            preferences.set(key, false)
            assertEquals(PreferenceGuardResult.STALE, enabled.attempt())
            val disabled = preferences.prepareGuard(key)
            assertEquals(PreferenceGuardResult.REJECTED, disabled.attempt())

            preferences.remove(key)
            assertEquals(PreferenceGuardResult.STALE, disabled.attempt())
            val defaultEnabled = preferences.prepareGuard(key)
            h.owner.backupPreferences.restoreMissing(
                BackupPreferenceSnapshot(mapOf(key.rawValue to PreferenceValue.BooleanValue(false))),
            )
            assertEquals(PreferenceGuardResult.STALE, defaultEnabled.attempt())
            val restored = preferences.prepareGuard(key)
            assertEquals(PreferenceGuardResult.REJECTED, restored.attempt())
            preferences.resetAppPreferences()
            assertEquals(PreferenceGuardResult.STALE, restored.attempt())

            val beforeFailure = preferences.prepareGuard(key)
            assertFailsWith<IllegalStateException> {
                preferences.update { throw IllegalStateException("aborted") }
            }
            assertEquals(PreferenceGuardResult.STALE, beforeFailure.attempt())
            var started = false
            assertEquals(PreferenceGuardResult.STARTED, preferences.prepareGuard(key).runIfCurrent({ it }) { started = true })
            assertTrue(started)
        }
    }

    @Test
    fun suspendedPreferenceReadDoesNotHoldTheConsumerGate() = runTest(timeout = 10.seconds) {
        val lifetime = SupervisorJob()
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val file = File(temporary.newFolder(), "prefs.preferences_pb")
        val delegate = DataStoreFactory.create(
            storage = SerializedFileStorage(FileStorage(BoundedPreferencesSerializer()) { file }),
            scope = CoroutineScope(coroutineContext + lifetime),
        )
        val delayed = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                readStarted.complete(Unit)
                releaseRead.await()
                emitAll(delegate.data)
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                delegate.updateData(transform)
        }
        val preferences = PreferenceStore(delayed, StorageAccess {}, RecordingReporter())
        val gate = Mutex()
        try {
            val admission = async {
                val token = preferences.prepareGuard(key)
                gate.withLock { token.runIfCurrent({ it }) {} }
            }
            readStarted.await()
            assertFalse(admission.isCompleted)
            withTimeout(1_000) { gate.withLock {} }
            preferences.set(key, false)
            releaseRead.complete(Unit)
            assertEquals(PreferenceGuardResult.STALE, admission.await())
            assertEquals(PreferenceGuardResult.REJECTED, preferences.prepareGuard(key).runIfCurrent({ it }) {})
        } finally {
            releaseRead.complete(Unit)
            lifetime.cancelAndJoin()
        }
    }

    @Test
    fun cancelledCallerCannotHideAStillQueuedDataStoreCommit() = runTest(timeout = 10.seconds) {
        val lifetime = SupervisorJob()
        val writing = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val delegate = BoundedPreferencesSerializer()
        var pauseWrite = false
        val serializer = object : Serializer<Preferences> {
            override val defaultValue = delegate.defaultValue
            override suspend fun readFrom(input: InputStream) = delegate.readFrom(input)
            override suspend fun writeTo(value: Preferences, output: OutputStream) {
                if (pauseWrite) {
                    pauseWrite = false
                    writing.complete(Unit)
                    releaseWrite.await()
                }
                delegate.writeTo(value, output)
            }
        }
        val file = File(temporary.newFolder(), "prefs.preferences_pb")
        val data = DataStoreFactory.create(
            storage = SerializedFileStorage(FileStorage(serializer) { file }),
            scope = CoroutineScope(coroutineContext + lifetime),
        )
        val preferences = PreferenceStore(data, StorageAccess {}, RecordingReporter())
        try {
            preferences.set(key, true)
            val beforeWrite = preferences.prepareGuard(key)
            pauseWrite = true
            val writer = launch { preferences.set(key, false) }
            writing.await()
            assertFalse(beforeWrite.isCurrent())
            assertEquals(PreferenceGuardResult.STALE, beforeWrite.runIfCurrent({ it }) { error("started during write") })
            val fresh = async { preferences.prepareGuard(key) }
            val cancelledWaiter = launch { preferences.prepareGuard(key) }
            testScheduler.runCurrent()
            assertFalse(fresh.isCompleted, "A new guard must wait without spinning while the write is active")
            cancelledWaiter.cancelAndJoin()
            assertFalse(writer.isCompleted, "Cancelling a guard wait must not cancel the writer")
            writer.cancelAndJoin()

            testScheduler.runCurrent()
            assertFalse(fresh.isCompleted, "A fresh guard must wait for the already queued write")
            releaseWrite.complete(Unit)
            val settled = fresh.await()
            assertFalse(settled.value)
            assertEquals(PreferenceGuardResult.REJECTED, settled.runIfCurrent({ it }) { error("started after disable") })
        } finally {
            releaseWrite.complete(Unit)
            lifetime.cancelAndJoin()
        }
    }

    @Test
    fun guardedStartAndPreferenceCommitAreOrdered() = runBlocking {
        withStorage(temporary.newFolder()) { h ->
            val preferences = h.owner.preferences
            val token = preferences.prepareGuard(key)
            val started = CompletableDeferred<Unit>()
            val attemptedWrite = CompletableDeferred<Thread>()
            val releaseStart = CountDownLatch(1)
            try {
                val admission = async(Dispatchers.IO) {
                    token.runIfCurrent({ it }) {
                        started.complete(Unit)
                        check(releaseStart.await(5, TimeUnit.SECONDS))
                    }
                }
                withTimeout(3_000) { started.await() }
                val writer = async(Dispatchers.IO) {
                    attemptedWrite.complete(Thread.currentThread())
                    preferences.set(key, false)
                }
                val writerThread = withTimeout(3_000) { attemptedWrite.await() }
                withTimeout(3_000) {
                    while (writerThread.state != Thread.State.BLOCKED) {
                        check(!writer.isCompleted)
                        delay(1)
                    }
                }
                assertFalse(writer.isCompleted)
                releaseStart.countDown()
                assertEquals(PreferenceGuardResult.STARTED, admission.await())
                writer.await()
                assertEquals(PreferenceGuardResult.STALE, token.runIfCurrent({ it }) { error("stale start") })
                assertFalse(preferences.get(key))
            } finally {
                releaseStart.countDown()
            }
        }
    }
}
