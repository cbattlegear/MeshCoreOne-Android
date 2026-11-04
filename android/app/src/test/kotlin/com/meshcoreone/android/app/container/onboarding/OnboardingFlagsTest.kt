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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import kotlinx.coroutines.CoroutineScope
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class OnboardingFlagsTest {
    @get:Rule val temporary = TemporaryFolder()

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
            val flags = WriteBehindOnboardingFlags(emptyMap(), scope, persist = { _, value ->
                release.await()
                durable = value
            })
            val state = OnboardingState(flags)
            val result = async {
                runCatching { state.completeOnboarding { check(current) { "retired producer" } } }
            }
            runCurrent()
            current = false
            release.complete(Unit)
            assertEquals("retired producer", result.await().exceptionOrNull()?.message)
            assertTrue(durable, "Do not pretend a completed disk write was rolled back")
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
            val flags = WriteBehindOnboardingFlags(emptyMap(), scope, persist = { _, _ ->
                writes++
                if (writes == 1) release.await()
            })
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
            assertEquals(2, writes)
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
