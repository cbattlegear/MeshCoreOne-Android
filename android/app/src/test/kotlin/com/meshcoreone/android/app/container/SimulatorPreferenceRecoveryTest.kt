// AndroidOnly: WP-217 Exercise simulator recovery choice, stale data and cancellation.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.data.repository.SimulatorDevicePreferenceConflict
import com.meshcoreone.android.core.data.repository.SimulatorPreferenceChoice
import com.meshcoreone.android.core.services.simulator.MockDataProvider
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorPreferenceRecoveryTest {
    private val device = MockDataProvider.simulatorDevice(Instant.EPOCH)
    private val original = SimulatorDevicePreferenceConflict(
        canonical = device.copy(defaultFloodScopeName = null),
        imported = device.copy(id = UUID.randomUUID(), isActive = false, defaultFloodScopeName = "US915"),
    )
    private fun invalid() = PersistenceStoreException(PersistenceStoreError.InvalidData)

    @Test fun preferenceDisplayDistinguishesNullEmptyAndInvisibleCharacters() {
        assertEquals("not set", preferenceText(null, "not set", "empty"))
        assertEquals("empty (\"\")", preferenceText("", "not set", "empty"))
        assertEquals("\"a\\n\\u0000\\u200e\\\\\\\"\"", preferenceText("a\n\u0000\u200e\\\"", "not set", "empty"))
        assertEquals("\"\\udb40\\udc01\uD83D\uDE00\"", preferenceText("\uDB40\uDC01\uD83D\uDE00", "not set", "empty"))
        assertEquals("\"\\ud800\"", preferenceText("\uD800", "not set", "empty"))
    }

    @Test fun eitherChoiceResolvesBeforeRetryingTheRealAction() = runTest {
        for (choice in SimulatorPreferenceChoice.entries) {
            var conflict: SimulatorDevicePreferenceConflict? = original
            val writes = mutableListOf<SimulatorPreferenceChoice>()
            val recovery = SimulatorPreferenceRecovery(
                load = { conflict },
                resolve = { _, expected, selected ->
                    assertEquals(original, expected)
                    writes += selected
                    conflict = null
                },
            )
            var attempts = 0
            val result = async { recovery.seed(device) { attempts++; if (conflict != null) throw invalid() } }
            runCurrent()
            val prompt = assertNotNull(recovery.prompt.value)
            assertFalse(result.isCompleted)
            assertTrue(recovery.choose(prompt.id, choice))
            assertFalse(recovery.cancel(prompt.id))
            runCurrent()
            result.await()
            assertEquals(2, attempts)
            assertEquals(listOf(choice), writes)
            assertNull(recovery.prompt.value)
        }
    }

    @Test fun staleChoiceRefreshesThePromptAndRequiresASecondChoice() = runTest {
        var conflict: SimulatorDevicePreferenceConflict? = original
        val refreshed = original.copy(canonical = original.canonical.copy(defaultFloodScopeName = "EU868"))
        var writes = 0
        val recovery = SimulatorPreferenceRecovery(
            load = { conflict },
            resolve = { _, expected, _ ->
                if (expected == original) {
                    conflict = refreshed
                    throw invalid()
                }
                assertEquals(refreshed, expected)
                writes++
                conflict = null
            },
        )
        val result = async { recovery.seed(device) { if (conflict != null) throw invalid() } }
        runCurrent()
        val first = assertNotNull(recovery.prompt.value)
        assertTrue(recovery.choose(first.id, SimulatorPreferenceChoice.KEEP_IMPORTED))
        runCurrent()
        val second = assertNotNull(recovery.prompt.value)
        assertEquals(refreshed, second.conflict)
        assertNotNull(second.failure)
        assertFalse(recovery.choose(first.id, SimulatorPreferenceChoice.KEEP_CANONICAL))
        assertEquals(0, writes)
        assertTrue(recovery.choose(second.id, SimulatorPreferenceChoice.KEEP_CANONICAL))
        runCurrent()
        result.await()
        assertEquals(1, writes)
    }

    @Test fun cancellationAndCloseLeaveTheConflictUnresolved() = runTest {
        for (close in listOf(false, true)) {
            var writes = 0
            val recovery = SimulatorPreferenceRecovery(
                load = { original },
                resolve = { _, _, _ -> writes++ },
            )
            val attempt = async { recovery.seed(device) { throw invalid() } }
            runCurrent()
            val prompt = assertNotNull(recovery.prompt.value)
            if (close) recovery.close() else assertTrue(recovery.cancel(prompt.id))
            runCurrent()
            assertTrue(attempt.isCancelled)
            assertNull(recovery.prompt.value)
            assertEquals(0, writes)
        }
    }

    @Test fun cancellingTheCallerWithdrawsThePromptAndAllowsRetry() = runTest {
        val recovery = SimulatorPreferenceRecovery(load = { original }, resolve = { _, _, _ -> })
        val first = async { recovery.seed(device) { throw invalid() } }
        runCurrent()
        val old = assertNotNull(recovery.prompt.value)
        first.cancelAndJoin()
        assertNull(recovery.prompt.value)
        val second = async { recovery.seed(device) { throw invalid() } }
        runCurrent()
        val next = assertNotNull(recovery.prompt.value)
        assertFalse(recovery.choose(old.id, SimulatorPreferenceChoice.KEEP_IMPORTED))
        assertTrue(recovery.cancel(next.id))
        second.join()
        assertTrue(second.isCancelled)
    }

    @Test fun unrelatedInvalidDataAndWriteFailuresAreNotConvertedToSuccess() = runTest {
        val missing = invalid()
        val recovery = SimulatorPreferenceRecovery(load = { null }, resolve = { _, _, _ -> })
        assertSame(missing, assertFailsWith<PersistenceStoreException> { recovery.seed(device) { throw missing } })
        val write = PersistenceStoreException(PersistenceStoreError.SaveFailed("device"))
        assertSame(write, assertFailsWith<PersistenceStoreException> { recovery.seed(device) { throw write } })
        assertNull(recovery.prompt.value)
    }

    @Test fun concurrentAttemptsShareOneResolutionAndRetryIndependently() = runTest {
        var conflict: SimulatorDevicePreferenceConflict? = original
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val recovery = SimulatorPreferenceRecovery(
            load = { conflict },
            resolve = { _, _, _ -> release.await(); writes++; conflict = null },
        )
        val first = async { recovery.seed(device) { if (conflict != null) throw invalid() } }
        val second = async { recovery.seed(device) { if (conflict != null) throw invalid() } }
        runCurrent()
        val prompt = assertNotNull(recovery.prompt.value)
        assertTrue(recovery.choose(prompt.id, SimulatorPreferenceChoice.KEEP_IMPORTED))
        runCurrent()
        assertTrue(assertNotNull(recovery.prompt.value).isApplying)
        assertFalse(recovery.cancel(prompt.id))
        release.complete(Unit)
        runCurrent()
        first.await()
        second.await()
        assertEquals(1, writes)
        assertNull(recovery.prompt.value)
    }
}
