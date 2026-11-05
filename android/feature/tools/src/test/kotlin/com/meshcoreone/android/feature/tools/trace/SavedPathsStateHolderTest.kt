// AndroidOnly: WP-314 Saved-path list/detail and presentation rules (no source unit tests); fixture store, no radio.
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.contracts.domain.TracePathPersisting
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class SavedPathsStateHolderTest {
    private object Strings : SavedPathsStrings {
        override val loadFailed = "Failed to load saved paths."
        override val renameFailed = "Failed to rename path."
        override val deleteFailed = "Failed to delete path."
    }

    private val harness = TraceHarness()
    private val store = InMemoryTracePathStore { harness.time.now() }
    private val diagnostics = RecordingDiagnostics()
    private val holder = SavedPathsStateHolder(Strings, diagnostics)
    private var connected: DeviceDTO? = device()

    init {
        holder.configure(object : SavedPathsDependencies {
            override fun savedPaths(): TracePathPersisting = store
            override fun connectedDevice(): DeviceDTO? = connected
        })
    }

    @Test
    fun `load lists the connected radio's paths and a failure keeps the old list with a message`() {
        val mine = savedPath(Bytes.of(1), radioId = RADIO)
        store.insert(mine)
        store.insert(savedPath(Bytes.of(2)))
        harness.complete { holder.loadSavedPaths() }
        assertEquals(listOf(mine), holder.state.value.savedPaths)
        assertFalse(holder.state.value.isLoading)
        store.failure = IOException("db")
        harness.complete { holder.loadSavedPaths() }
        assertEquals("Failed to load saved paths.", holder.state.value.errorMessage)
        assertEquals(listOf(mine), holder.state.value.savedPaths)
        assertFalse(holder.state.value.isLoading)
    }

    @Test
    fun `without a connected radio loading is a no-op`() {
        connected = null
        harness.complete { holder.loadSavedPaths() }
        assertTrue(holder.state.value.savedPaths.isEmpty())
        assertNull(holder.state.value.errorMessage)
    }

    @Test
    fun `rename reloads and delete drops the row locally`() {
        val path = savedPath(Bytes.of(1), radioId = RADIO)
        store.insert(path)
        harness.complete { holder.loadSavedPaths() }
        harness.complete { holder.renamePath(path, "Ridge loop") }
        assertEquals("Ridge loop", holder.state.value.savedPaths.single().name)
        harness.complete { holder.deletePath(path) }
        assertTrue(holder.state.value.savedPaths.isEmpty())
        assertTrue(store.paths.isEmpty())
    }

    @Test
    fun `rename and delete failures set their messages`() {
        val path = savedPath(Bytes.of(1), radioId = RADIO)
        store.insert(path)
        harness.complete { holder.loadSavedPaths() }
        store.failure = IOException("locked")
        harness.complete { holder.renamePath(path, "x") }
        assertEquals("Failed to rename path.", holder.state.value.errorMessage)
        harness.complete { holder.deletePath(path) }
        assertEquals("Failed to delete path.", holder.state.value.errorMessage)
        assertEquals(listOf(path), holder.state.value.savedPaths)
        assertEquals(listOf("renamePath", "deletePath"), diagnostics.failures.map { it.first })
    }

    @Test
    fun `retired dialog ignores noncooperative load results errors and retained row mutations`() {
        for (sameRadio in listOf(false, true)) for (failure in listOf(false, true)) {
            connected = device()
            val oldPath = savedPath(Bytes.of(1), radioId = RADIO)
            store.insert(oldPath)
            val gate = CompletableDeferred<Unit>()
            val old = SavedPathsStateHolder(Strings, diagnostics).apply {
                configure(object : SavedPathsDependencies {
                    override fun connectedDevice() = connected
                    override fun savedPaths() = object : TracePathPersisting by store {
                        override suspend fun fetchSavedTracePaths(radioId: RadioId) =
                            store.fetchSavedTracePaths(radioId).also {
                                withContext(NonCancellable) { gate.await() }
                                if (failure) throw IOException("retired fetch")
                            }
                    }
                })
            }
            val pending = harness.start { old.loadSavedPaths() }
            old.retireConnection()
            val nextRadio = if (sameRadio) RADIO else RadioId(UUID.randomUUID())
            connected = device().copy(radioId = nextRadio)
            val fresh = SavedPathsStateHolder(Strings, diagnostics).apply {
                configure(object : SavedPathsDependencies {
                    override fun connectedDevice() = connected
                    override fun savedPaths() = store
                })
            }
            val newPath = savedPath(Bytes.of(2), radioId = nextRadio)
            store.insert(newPath)
            harness.complete { fresh.loadSavedPaths() }
            val current = fresh.state.value
            gate.complete(Unit)
            harness.main.runCurrent()
            assertTrue(pending.isCompleted)
            assertEquals(SavedPathsState(), old.state.value)
            assertEquals(current, fresh.state.value)
            assertFalse(old.isCurrentPath(oldPath))
            harness.complete { old.renamePath(oldPath, "must not rename") }
            assertFalse(harness.complete { old.deletePath(oldPath) })
            assertEquals(oldPath, store.paths[oldPath.id])
            assertTrue(fresh.isCurrentPath(newPath))
            assertTrue(harness.complete { fresh.deletePath(newPath) })
            assertFalse(store.paths.containsKey(newPath.id))
            assertTrue(store.paths.containsKey(oldPath.id))
        }
    }

    @Test
    fun `committed deletion retires an older snapshot without resurrecting its row`() {
        val path = savedPath(Bytes.of(1), radioId = RADIO)
        store.insert(path)
        harness.complete { holder.loadSavedPaths() }
        val gate = CompletableDeferred<Unit>()
        holder.configure(object : SavedPathsDependencies {
            override fun connectedDevice() = connected
            override fun savedPaths() = object : TracePathPersisting by store {
                override suspend fun fetchSavedTracePaths(radioId: RadioId) =
                    store.fetchSavedTracePaths(radioId).also { gate.await() }
            }
        })
        val load = harness.start { holder.loadSavedPaths() }
        assertTrue(harness.complete { holder.deletePath(path) })
        assertTrue(holder.state.value.savedPaths.isEmpty())
        gate.complete(Unit)
        harness.main.runCurrent()
        assertTrue(load.isCompleted)
        assertTrue(holder.state.value.savedPaths.isEmpty())
        assertFalse(holder.state.value.isLoading)
        assertFalse(store.paths.containsKey(path.id))
    }

    @Test
    fun `accepted mutation stays durable but cannot change a retired dialog`() {
        for (delete in listOf(false, true)) {
            val path = savedPath(Bytes.of(1), radioId = RADIO)
            store.insert(path)
            val gate = CompletableDeferred<Unit>()
            val old = SavedPathsStateHolder(Strings, diagnostics).apply {
                configure(object : SavedPathsDependencies {
                    override fun connectedDevice() = connected
                    override fun savedPaths() = object : TracePathPersisting by store {
                        override suspend fun deleteSavedTracePath(key: EntityKey) {
                            withContext(NonCancellable) { gate.await() }
                            store.deleteSavedTracePath(key)
                        }
                        override suspend fun updateSavedTracePathName(key: EntityKey, name: String) {
                            withContext(NonCancellable) { gate.await() }
                            store.updateSavedTracePathName(key, name)
                        }
                    }
                })
            }
            val mutation = harness.start {
                if (delete) old.deletePath(path) else old.renamePath(path, "Accepted rename")
            }
            old.retireConnection()
            gate.complete(Unit)
            harness.main.runCurrent()
            assertTrue(mutation.isCompleted)
            assertEquals(SavedPathsState(), old.state.value)
            if (delete) assertFalse(store.paths.containsKey(path.id))
            else assertEquals("Accepted rename", store.paths.getValue(path.id).name)
        }
    }

    @Test
    fun `detail sorts runs newest first and derives best, average and success rate`() {
        val runs = listOf(run(T0.minusSeconds(30), 300), run(T0, 100), run(T0.minusSeconds(60), success = false), run(T0.minusSeconds(10), 200))
        val detail = SavedPathDetailState(savedPath(Bytes.of(1, 2), hashSize = 2, runs = runs))
        assertEquals(listOf(T0, T0.minusSeconds(10), T0.minusSeconds(30), T0.minusSeconds(60)), detail.sortedRuns.map { it.date })
        assertEquals(3, detail.successfulRuns.size)
        assertEquals(100L, detail.bestRoundTrip)
        assertEquals(200L, detail.averageRoundTrip)
        assertEquals("75%", detail.successRateText)
        assertEquals(2L, detail.hashSize)
    }

    @Test
    fun `detail refresh replaces the path from the store`() {
        val path = savedPath(Bytes.of(1), radioId = RADIO)
        store.insert(path)
        val detail = SavedPathDetailStateHolder(path, diagnostics)
        detail.configure { store }
        harness.complete { store.appendTracePathRun(com.meshcoreone.android.core.contracts.domain.EntityKey(RADIO, path.id), run(T0)) }
        harness.complete { detail.refresh() }
        assertEquals(1, detail.state.value.savedPath.runs.size)
        assertFalse(detail.state.value.isLoading)
    }

    @Test
    fun `health, trend, comparison and hop display follow the row rules`() {
        assertEquals(SavedPathHealth.HEALTHY, SavedPathHealth.of(90))
        assertEquals(SavedPathHealth.DEGRADED, SavedPathHealth.of(50))
        assertEquals(SavedPathHealth.POOR, SavedPathHealth.of(49))
        assertNull(RttSummary.of(emptyList()))
        assertEquals(RttSummary(100, RttTrend.STABLE), RttSummary.of(listOf(100L)))
        assertEquals(RttSummary(150, RttTrend.INCREASING), RttSummary.of(listOf(100L, 100L, 200L, 200L)))
        assertEquals(RttTrend.DECREASING, RttSummary.of(listOf(300L, 100L))?.trend)
        assertEquals(RttTrend.STABLE, RttSummary.of(listOf(100L, 150L))?.trend)
        assertEquals(RttComparison(50, 50.0), RttComparison.of(150, 100))
        assertEquals(0.0, RttComparison.of(10, 0).percentChange)
        assertFalse(RttComparison.of(100, 100).changed)
        assertEquals(2.0, TraceHopDisplay.displaySnr(1.0, HopStats(5.0, 1.0, 9.0), 2.0, isBatchInProgress = true))
        assertEquals(5.0, TraceHopDisplay.displaySnr(1.0, HopStats(5.0, 1.0, 9.0), 2.0, isBatchInProgress = false))
        assertEquals(1.0, TraceHopDisplay.displaySnr(1.0, null, null, isBatchInProgress = false))
        assertEquals(listOf("AABB", "CC"), TraceHopDisplay.hopHexStrings(Bytes.of(0xAA, 0xBB, 0xCC), 2))
    }
}
