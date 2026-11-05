// AndroidOnly: WP-303 Onboarding flag persistence and the first-run gate flow.
package com.meshcoreone.android.app.container.onboarding

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.ui.SharedTip
import com.meshcoreone.android.core.ui.ShowOnceTips
import com.meshcoreone.android.core.ui.TipHostState
import com.meshcoreone.android.feature.onboarding.OnboardingState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import kotlinx.coroutines.CoroutineScope
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class OnboardingFlagsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun storage(): MeshCoreStorage {
        val directory = temporary.newFolder()
        val application = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        return MeshCoreStorage.get(context)
    }

    @Test fun `explicit flow retirement rejects the exact attempt synchronously before its seal`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        var durable = false
        try {
            val flags = WriteBehindOnboardingFlags(emptyMap(), scope, { _, value -> durable = value })
            val state = OnboardingState(flags)
            assertFailsWith<IllegalStateException> {
                state.completeOnboardingWithSeal({}) { attempt ->
                    state.retireCompletion()
                    assertTrue(attempt.rejection() != null)
                    attempt.trySeal()
                }
            }
            assertFalse(durable)
            assertFalse(flags.hasCompleted.value)
            assertFalse(state.hasCompletedOnboarding)
            assertFailsWith<IllegalStateException> { state.completeOnboarding() }
            val fresh = OnboardingState(flags)
            fresh.completeOnboarding()
            assertTrue(fresh.hasCompletedOnboarding)
        } finally { scope.cancel() }
    }

    @Test fun `pending or rejected real writer staging never publishes a completion receipt`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val staged = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reject = true
        try {
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), scope, { _, _ -> },
                stageCompletion = {
                    if (reject) { staged.complete(Unit); release.await(); throw IOException("stage rejected") }
                },
            )
            val state = OnboardingState(flags)
            val first = async { runCatching { state.completeOnboarding() } }
            staged.await()
            assertEquals(null, state.completionReceipt)
            assertFalse(state.hasCompletedOnboarding)
            release.complete(Unit)
            assertEquals("stage rejected", first.await().exceptionOrNull()?.message)
            assertEquals(null, state.completionReceipt)
            assertFalse(state.hasCompletedOnboarding)
            reject = false
            state.completeOnboarding()
            assertEquals(
                com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt.WithoutDevice,
                state.completionReceipt,
            )
            assertTrue(state.hasCompletedOnboarding)
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test fun `reset after a valid seal preserves ordered false and prevents retired flow publication`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val finalizing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val durableWrites = mutableListOf<Boolean>()
        try {
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), scope, { _, value -> durableWrites += value },
                persistCompletion = { finalizing.complete(Unit); release.await(); durableWrites += true },
            )
            val state = OnboardingState(flags)
            val old = async { runCatching { state.completeOnboarding() } }
            finalizing.await()
            val reset = async { state.resetOnboarding() }
            runCurrent()
            assertFalse(reset.isCompleted)
            release.complete(Unit)
            assertTrue(old.await().isFailure)
            reset.await()
            assertEquals(listOf(true, false), durableWrites)
            assertFalse(flags.hasCompleted.value)
            assertFalse(state.hasCompletedOnboarding)
            assertEquals(null, state.completionReceipt)
        } finally { scope.cancel() }
    }

    @Test fun `process shutdown rejects staged attempts before parent job cancellation and drains finalizers`() = runTest {
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + UnconfinedTestDispatcher(testScheduler))
        val staged = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var durable = false
        val flags = WriteBehindOnboardingFlags(
            emptyMap(), scope, { _, value -> durable = value },
            stageCompletion = { staged.complete(Unit); release.await() },
        )
        try {
            val completion = async { runCatching { OnboardingState(flags).completeOnboarding() } }
            staged.await()
            val stopping = async { flags.shutdown() }
            runCurrent()
            assertFalse(stopping.isCompleted)
            release.complete(Unit)
            stopping.await()
            parent.cancel()
            assertTrue(completion.await().isFailure)
            assertFalse(durable)
            assertFalse(flags.hasCompleted.value)
            assertFailsWith<kotlinx.coroutines.CancellationException> { OnboardingState(flags).completeOnboarding() }
        } finally { scope.cancel() }
    }

    @Test fun `cancelled active write abandons queued waiters and releases existing and later shutdown`() = runTest {
        for ((shutdownBeforeCancellation, cancelWorker) in listOf(
            true to false, false to false, true to true, false to true,
        )) {
            val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val activeFailure = CancellationException("active write cancelled")
            val persisted = mutableListOf<String>()
            try {
                val flags = WriteBehindOnboardingFlags(
                    emptyMap(), scope, { key, _ ->
                        persisted += key
                        started.complete(Unit)
                        release.await()
                        throw activeFailure
                    },
                )
                val active = async { runCatching { flags.persistBoolean("active", true) } }
                started.await()
                val cancelledQueued = async { flags.persistBoolean("cancelled queued", true) }
                val queued = async { runCatching { flags.persistBoolean("queued", true) } }
                runCurrent()
                assertFalse(active.isCompleted)
                assertFalse(cancelledQueued.isCompleted)
                assertFalse(queued.isCompleted)
                cancelledQueued.cancel()
                cancelledQueued.join()
                assertTrue(cancelledQueued.isCancelled)
                val stopping = if (shutdownBeforeCancellation) async { flags.shutdown() } else null
                runCurrent()
                if (stopping != null) assertFalse(stopping.isCompleted)
                if (cancelWorker) scope.cancel() else release.complete(Unit)
                withTimeout(1_000) {
                    val cancelled = active.await().exceptionOrNull()
                    assertTrue(cancelled is CancellationException)
                    if (!cancelWorker) assertEquals(activeFailure.message, cancelled?.message)
                    val abandoned = queued.await().exceptionOrNull()
                    assertTrue(abandoned is CancellationException)
                    assertEquals("Onboarding preference writer stopped", abandoned?.message)
                    stopping?.await()
                    flags.shutdown()
                }
                assertEquals(listOf("active"), persisted)
                assertFalse(flags.getBoolean("active"))
                assertFalse(flags.getBoolean("queued"))
                assertFalse(flags.hasCompleted.value)
                assertFailsWith<CancellationException> { flags.persistBoolean("late", true) }
            } finally { release.complete(Unit); scope.cancel() }
        }
    }

    @Test fun `failure callback cancellation still abandons queued work and completes captured shutdown`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writeFailure = IOException("disk unavailable")
        val reported = mutableListOf<Throwable>()
        try {
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), scope, { _, _ ->
                    started.complete(Unit)
                    release.await()
                    throw writeFailure
                },
                onFailure = {
                    reported += it
                    throw CancellationException("reporting cancelled")
                },
            )
            val active = async { runCatching { flags.persistBoolean("active", true) } }
            started.await()
            val queued = async { runCatching { flags.persistBoolean("queued", true) } }
            runCurrent()
            assertFalse(queued.isCompleted)
            val stopping = async { flags.shutdown() }
            runCurrent()
            assertFalse(stopping.isCompleted)
            release.complete(Unit)
            withTimeout(1_000) {
                assertEquals(writeFailure.message, active.await().exceptionOrNull()?.message)
                assertTrue(queued.await().exceptionOrNull() is CancellationException)
                stopping.await()
                flags.shutdown()
            }
            assertEquals(listOf(writeFailure), reported)
            assertFalse(flags.hasCompleted.value)
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test fun `retirement during real staging remains incomplete on preference reload and can retry`() =
        kotlinx.coroutines.runBlocking {
            val storage = storage()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val staged = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var current = true
            try {
                val tips = ShowOnceTips(storage.preferences)
                val flags = WriteBehindOnboardingFlags(
                    emptyMap(), scope, { key, value ->
                        check(key == OnboardingState.KEY_HAS_COMPLETED)
                        storage.preferences.set(AppStorageKey.hasCompletedOnboarding, value)
                    },
                    stageCompletion = { id ->
                        tips.stageOnboardingCompletion(id)
                        staged.complete(Unit)
                        release.await()
                    },
                    persistCompletion = { tips.completeOnboarding(it) },
                    reconcileCompletion = { tips.isOnboardingCompletionFinalized(it) },
                )
                val completion = async {
                    runCatching { OnboardingState(flags).completeOnboarding { check(current) { "retired" } } }
                }
                staged.await()
                current = false
                release.complete(Unit)
                assertEquals("retired", completion.await().exceptionOrNull()?.message)
                assertFalse(storage.preferences.get(AppStorageKey.hasCompletedOnboarding))
                assertFalse(WriteBehindOnboardingFlags.load(storage.preferences, scope).hasCompleted.value)
                current = true
                OnboardingState(flags).completeOnboarding { check(current) }
                assertTrue(storage.preferences.get(AppStorageKey.hasCompletedOnboarding))
                assertTrue(WriteBehindOnboardingFlags.load(storage.preferences, scope).hasCompleted.value)
            } finally { scope.cancel(); storage.close() }
        }

    @Test fun `real committed finalization with lost acknowledgement is reconciled with its atomic tip`() =
        kotlinx.coroutines.runBlocking {
            val storage = storage()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val failures = mutableListOf<Throwable>()
            try {
                val tips = ShowOnceTips(storage.preferences)
                val flags = WriteBehindOnboardingFlags(
                    emptyMap(), scope, { _, _ -> error("Use the attempt-bound finalizer") }, failures::add,
                    stageCompletion = { tips.stageOnboardingCompletion(it) },
                    persistCompletion = { id -> tips.completeOnboarding(id); throw IOException("lost acknowledgement") },
                    reconcileCompletion = { tips.isOnboardingCompletionFinalized(it) },
                )
                OnboardingState(flags).completeOnboarding()
                assertTrue(flags.hasCompleted.value)
                assertTrue(WriteBehindOnboardingFlags.load(storage.preferences, scope).hasCompleted.value)
                assertEquals(listOf("lost acknowledgement"), failures.map { it.message })
                assertTrue(tips.claim(SharedTip.DEVICE_MENU) { TipHostState(AppTab.CHATS, true, true, false) })
            } finally { scope.cancel(); storage.close() }
        }

    @Test fun `unavailable uncertain-commit readback surfaces both errors without claiming rollback`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        var committed: String? = null
        try {
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), scope, { _, _ -> error("Use the attempt-bound finalizer") },
                persistCompletion = { id -> committed = id; throw IOException("unknown commit outcome") },
                reconcileCompletion = { throw IOException("readback unavailable") },
            )
            val failure = assertFailsWith<IOException> { OnboardingState(flags).completeOnboarding() }
            assertEquals("unknown commit outcome", failure.message)
            assertEquals(listOf("readback unavailable"), failure.suppressed.map { it.message })
            assertTrue(committed != null)
            assertFalse(flags.hasCompleted.value)
        } finally { scope.cancel() }
    }

    @Test fun `typed uncertain outcome retains both original errors across deferred delivery`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val writeFailure = IOException("unknown commit outcome")
        val readFailure = IOException("readback unavailable")
        val reported = mutableListOf<Throwable>()
        try {
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), scope, { _, _ -> error("Use the attempt-bound finalizer") }, reported::add,
                persistCompletion = { throw writeFailure },
                reconcileCompletion = { throw readFailure },
            )
            val result = async { runCatching { OnboardingState(flags).completeOnboarding() } }
            val failure = result.await().exceptionOrNull()
            assertTrue(failure is OnboardingCompletionOutcomeUnknownException)
            val unknown = failure as OnboardingCompletionOutcomeUnknownException
            assertSame(writeFailure, unknown.writeFailure)
            assertSame(readFailure, unknown.readbackFailure)
            assertSame(writeFailure, unknown.cause)
            assertEquals(listOf(readFailure), unknown.suppressed.toList())
            assertSame(unknown, reported.single())
            assertTrue(writeFailure.suppressed.isEmpty())
            assertFalse(flags.hasCompleted.value)
        } finally { scope.cancel() }
    }

    @Test fun `cancelled uncertain finalizer retains original cancellation and readback failure`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val writeFailure = CancellationException("finalizer cancelled")
        val readFailure = IOException("readback unavailable")
        val reported = mutableListOf<Throwable>()
        var readbacks = 0
        try {
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), scope, { _, _ -> error("Use the attempt-bound finalizer") }, reported::add,
                persistCompletion = { throw writeFailure },
                reconcileCompletion = { readbacks++; throw readFailure },
            )
            val result = async { runCatching { OnboardingState(flags).completeOnboarding() } }
            val failure = result.await().exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertTrue(failure is OnboardingCompletionCancellationOutcomeUnknownException)
            val unknown = failure as OnboardingCompletionCancellationOutcomeUnknownException
            assertSame(writeFailure, unknown.writeFailure)
            assertSame(readFailure, unknown.readbackFailure)
            assertSame(writeFailure, unknown.cause)
            assertEquals(listOf(readFailure), unknown.suppressed.toList())
            assertEquals(1, readbacks)
            assertTrue(reported.isEmpty())
            assertTrue(writeFailure.suppressed.isEmpty())
            assertFalse(flags.hasCompleted.value)
        } finally { scope.cancel() }
    }

    @Test fun `one shared failure is retained without mutating its suppressed errors`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val original = IOException("same underlying failure")
        try {
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), scope, { _, _ -> error("Use the attempt-bound finalizer") },
                persistCompletion = { throw original },
                reconcileCompletion = { throw original },
            )
            val failure = assertFailsWith<OnboardingCompletionOutcomeUnknownException> {
                OnboardingState(flags).completeOnboarding()
            }
            assertSame(original, failure.writeFailure)
            assertSame(original, failure.readbackFailure)
            assertSame(original, failure.cause)
            assertTrue(failure.suppressed.isEmpty())
            assertTrue(original.suppressed.isEmpty())
            assertFalse(flags.hasCompleted.value)
        } finally { scope.cancel() }
    }

    @Test fun `cancellation immediately before the seal wins without finalizing`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        var durable = false
        try {
            val flags = WriteBehindOnboardingFlags(emptyMap(), scope, { _, value -> durable = value })
            val state = OnboardingState(flags)
            lateinit var completion: Deferred<Unit>
            completion = async(start = CoroutineStart.LAZY) {
                state.completeOnboardingWithSeal({}) { attempt ->
                    completion.cancel()
                    attempt.trySeal()
                }
            }
            completion.start()
            completion.join()
            runCurrent()
            assertTrue(completion.isCancelled)
            assertFalse(durable)
            assertFalse(flags.hasCompleted.value)
            state.completeOnboarding()
            assertTrue(flags.hasCompleted.value)
        } finally { scope.cancel() }
    }

    @Test fun `seal immediately before cancellation wins and the process finalizer completes`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        var durable = false
        try {
            val flags = WriteBehindOnboardingFlags(emptyMap(), scope, { _, value -> durable = value })
            val state = OnboardingState(flags)
            lateinit var completion: Deferred<Unit>
            completion = async(start = CoroutineStart.LAZY) {
                state.completeOnboardingWithSeal({}) { attempt ->
                    attempt.trySeal().also { completion.cancel() }
                }
            }
            completion.start()
            completion.join()
            runCurrent()
            assertTrue(completion.isCancelled)
            assertTrue(durable)
            assertTrue(flags.hasCompleted.value)
        } finally { scope.cancel() }
    }

    @Test fun `reset and newer attempts supersede an unsealed stage in write order`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        var durable = false
        var stages = 0
        try {
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), scope, { _, value -> durable = value },
                stageCompletion = { if (++stages == 1) release.await() },
            )
            val old = async { runCatching { OnboardingState(flags).completeOnboarding() } }
            runCurrent()
            val reset = async { OnboardingState(flags).resetOnboarding() }
            runCurrent()
            val newer = async { OnboardingState(flags).completeOnboarding() }
            runCurrent()
            release.complete(Unit)
            assertTrue(old.await().isFailure)
            reset.await()
            newer.await()
            assertTrue(durable)
            assertTrue(flags.hasCompleted.value)
        } finally { scope.cancel() }
    }

    @Test fun `a sealed finalization remains valid after later target retirement`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        var current = true
        var durable = false
        try {
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), scope, { _, value -> release.await(); durable = value },
            )
            val state = OnboardingState(flags)
            val completion = async { state.completeOnboarding { check(current) } }
            runCurrent()
            current = false
            release.complete(Unit)
            completion.await()
            assertTrue(durable)
            assertTrue(flags.hasCompleted.value)
            assertTrue(state.hasCompletedOnboarding)
        } finally { scope.cancel() }
    }

    @Test fun `staging preserves prior true and restart honors only finalized durable completion`() =
        kotlinx.coroutines.runBlocking {
            val directory = temporary.newFolder()
            val application = ApplicationProvider.getApplicationContext<Context>()
            val context = object : ContextWrapper(application) {
                override fun getApplicationContext(): Context = this
                override fun getNoBackupFilesDir(): File = directory
            }
            val storage = MeshCoreStorage.get(context)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val tips = ShowOnceTips(storage.preferences)
                tips.stageOnboardingCompletion("unsealed")
                assertFalse(WriteBehindOnboardingFlags.load(storage.preferences, scope).hasCompleted.value)
                tips.completeOnboarding("valid")
                tips.stageOnboardingCompletion("later-unsealed")
                assertTrue(WriteBehindOnboardingFlags.load(storage.preferences, scope).hasCompleted.value)
                assertTrue(tips.isOnboardingCompletionFinalized("valid"))
                assertFalse(tips.isOnboardingCompletionFinalized("later-unsealed"))
                assertTrue(tips.claim(SharedTip.DEVICE_MENU) {
                    TipHostState(AppTab.CHATS, true, true, false)
                })
            } finally { scope.cancel(); storage.close() }
        }

    @Test fun `uncertain finalization reconciles the exact committed attempt instead of assuming rollback`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        var committed: String? = null
        val failures = mutableListOf<Throwable>()
        try {
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), scope, { _, _ -> error("Use the attempt-bound finalizer") }, failures::add,
                persistCompletion = { id -> committed = id; throw IOException("acknowledgement lost") },
                reconcileCompletion = { id -> id == committed },
            )
            val state = OnboardingState(flags)
            state.completeOnboarding()
            assertTrue(state.hasCompletedOnboarding)
            assertTrue(flags.hasCompleted.value)
            assertEquals(listOf("acknowledgement lost"), failures.map { it.message })
        } finally { scope.cancel() }
    }

    @Test fun `completion persists in order and publishes the gate immediately`() = runTest {
        val writes = mutableListOf<Pair<String, Boolean>>()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val flags = WriteBehindOnboardingFlags(emptyMap(), scope, persist = { k, v -> writes += k to v })
            val state = OnboardingState(flags)
            assertFalse(state.hasCompletedOnboarding)
            assertFalse(flags.hasCompleted.value)
            state.completeOnboarding()
            assertTrue(flags.hasCompleted.value)
            assertTrue(flags.getBoolean(OnboardingState.KEY_HAS_COMPLETED))
            state.resetOnboarding()
            assertFalse(flags.hasCompleted.value)
            assertEquals(listOf(OnboardingState.KEY_HAS_COMPLETED to true, OnboardingState.KEY_HAS_COMPLETED to false), writes)
        } finally { scope.cancel() }
    }

    @Test fun `a persisted completed flag is already true and a failing write is reported not thrown`() = runTest {
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val flags = WriteBehindOnboardingFlags(
                mapOf(OnboardingState.KEY_HAS_COMPLETED to true), scope,
                { _, _ -> error("disk full") }, failures::add,
            )
            assertTrue(flags.hasCompleted.value)
            flags.setBoolean("other", true)
            assertTrue(flags.getBoolean("other"))
            assertEquals(listOf("disk full"), failures.map { it.message })
        } finally { scope.cancel() }
    }

    @Test fun `durable completion waits for the write and leaves the gate closed on failure`() = runTest {
        val persisted = CompletableDeferred<Unit>()
        val successScope = CoroutineScope(
            SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        )
        val flags = WriteBehindOnboardingFlags(
            emptyMap(), successScope, { _, _ -> persisted.await() },
        )
        val state = OnboardingState(flags)
        val completion = async { state.completeOnboarding() }
        runCurrent()
        assertFalse(state.hasCompletedOnboarding)
        assertFalse(flags.hasCompleted.value)
        persisted.complete(Unit)
        advanceUntilIdle()
        completion.await()
        assertTrue(state.hasCompletedOnboarding)
        assertTrue(flags.hasCompleted.value)
        successScope.cancel()

        val failures = mutableListOf<Throwable>()
        val failureScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val failing = WriteBehindOnboardingFlags(
            emptyMap(), failureScope, { _, _ -> error("disk full") }, failures::add,
        )
        val failedState = OnboardingState(failing)
        assertFailsWith<IllegalStateException> { failedState.completeOnboarding() }
        assertFalse(failedState.hasCompletedOnboarding)
        assertFalse(failing.hasCompleted.value)
        assertEquals(listOf("disk full"), failures.map { it.message })
        failureScope.cancel()
    }

    @Test fun `later immediate write stays visible after an earlier awaited write finishes`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val persisted = mutableListOf<Boolean>()
        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            val flags = WriteBehindOnboardingFlags(
                emptyMap(),
                scope,
                persist = { _, value ->
                    if (value) {
                        started.complete(Unit)
                        release.await()
                    }
                    persisted += value
                },
            )
            val awaitedWrite = async {
                flags.persistBoolean(OnboardingState.KEY_HAS_COMPLETED, true)
            }
            runCurrent()
            assertTrue(started.isCompleted)
            assertFalse(flags.hasCompleted.value)

            flags.setBoolean(OnboardingState.KEY_HAS_COMPLETED, false)
            assertFalse(flags.hasCompleted.value)

            release.complete(Unit)
            advanceUntilIdle()
            awaitedWrite.await()

            assertEquals(listOf(true, false), persisted)
            assertFalse(flags.hasCompleted.value)
            assertFalse(flags.getBoolean(OnboardingState.KEY_HAS_COMPLETED))
        } finally {
            scope.cancel()
        }
    }

    @Test fun `retired producer during durable write cannot open the process gate`() = runTest {
        val release = CompletableDeferred<Unit>()
        var current = true
        var durable = false
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), scope,
                persist = { _, value -> durable = value },
                stageCompletion = { release.await() },
            )
            val state = OnboardingState(flags)
            val result = async {
                runCatching { state.completeOnboarding { check(current) { "retired producer" } } }
            }
            runCurrent()
            current = false
            release.complete(Unit)
            assertEquals("retired producer", result.await().exceptionOrNull()?.message)
            assertFalse("A staged retired completion must not survive process reload", durable)
            assertFalse(flags.hasCompleted.value)
            assertFalse(state.hasCompletedOnboarding)
            assertFalse(flags.getBoolean(OnboardingState.KEY_HAS_COMPLETED))
            current = true
            state.completeOnboarding { check(current) }
            assertTrue(flags.hasCompleted.value)
            assertTrue(state.hasCompletedOnboarding)
        } finally { scope.cancel() }
    }

    @Test fun `cancelled completion does not publish late or stop the process writer`() = runTest {
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            var stages = 0
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), scope, persist = { _, _ -> writes++ },
                stageCompletion = { if (++stages == 1) release.await() },
            )
            val state = OnboardingState(flags)
            val completion = async { state.completeOnboarding() }
            runCurrent()
            completion.cancel()
            completion.join()
            release.complete(Unit)
            runCurrent()
            assertFalse(flags.hasCompleted.value)
            assertFalse(state.hasCompletedOnboarding)
            state.completeOnboarding()
            assertEquals(1, writes)
            assertTrue(flags.hasCompleted.value)
            assertTrue(state.hasCompletedOnboarding)
        } finally { scope.cancel() }
    }

    @Test fun `production flag adapter atomically persists completion with the show-once donation`() =
        kotlinx.coroutines.runBlocking {
            val directory = temporary.newFolder()
            val application = ApplicationProvider.getApplicationContext<Context>()
            val context = object : ContextWrapper(application) {
                override fun getApplicationContext(): Context = this
                override fun getNoBackupFilesDir(): File = directory
            }
            val storage = MeshCoreStorage.get(context)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val flags = WriteBehindOnboardingFlags.load(storage.preferences, scope)
                OnboardingState(flags).completeOnboarding()
                assertTrue(storage.preferences.get(AppStorageKey.hasCompletedOnboarding))
                val tips = ShowOnceTips(storage.preferences)
                assertTrue(tips.claim(SharedTip.DEVICE_MENU) {
                    TipHostState(AppTab.CHATS, deviceMenuVisible = true, connectedDevicePresent = true, liveStatusPresented = false)
                })
            } finally {
                scope.cancel()
                storage.close()
            }
        }
}
