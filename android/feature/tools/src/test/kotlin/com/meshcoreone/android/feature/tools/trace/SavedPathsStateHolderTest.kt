// AndroidOnly: WP-314 Saved-path list/detail and presentation rules (no source unit tests); fixture store, no radio.
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.contracts.domain.TracePathPersisting
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SavedTracePathDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
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
    fun `accepted rename refreshes the same connection after a newer load without restoring older snapshots`() {
        for (holdSnapshot in listOf(false, true)) {
            val path = savedPath(Bytes.of(1), radioId = RADIO)
            store.insert(path)
            val writeGate = CompletableDeferred<Unit>()
            val readGate = CompletableDeferred<Unit>()
            var reads = 0
            holder.configure(object : SavedPathsDependencies {
                override fun connectedDevice() = connected
                override fun savedPaths() = object : TracePathPersisting by store {
                    override suspend fun updateSavedTracePathName(key: EntityKey, name: String) {
                        writeGate.await()
                        store.updateSavedTracePathName(key, name)
                    }
                    override suspend fun fetchSavedTracePaths(radioId: RadioId) =
                        store.fetchSavedTracePaths(radioId).also {
                            if (++reads == 2 && holdSnapshot) readGate.await()
                        }
                }
            })
            harness.complete { holder.loadSavedPaths() }
            val rename = harness.start { holder.renamePath(path, "Accepted new name") }
            val newerLoad = harness.start { holder.loadSavedPaths() }
            assertEquals(path.name, store.paths.getValue(path.id).name)
            writeGate.complete(Unit)
            harness.main.runCurrent()
            assertTrue(rename.isCompleted)
            assertEquals("Accepted new name", store.paths.getValue(path.id).name)
            assertEquals("Accepted new name", holder.state.value.savedPaths.single { it.id == path.id }.name)
            val accepted = holder.state.value
            readGate.complete(Unit)
            harness.main.runCurrent()
            assertTrue(newerLoad.isCompleted)
            assertEquals(accepted, holder.state.value)
            assertFalse(holder.state.value.isLoading)
        }
    }

    @Test
    fun `held mutation failures survive intervening loads but never overwrite a newer mutation outcome`() {
        for (delete in listOf(false, true)) for (newer in listOf("none", "success", "failure")) {
            val path = savedPath(Bytes.of(1), radioId = RADIO)
            store.insert(path)
            val gate = CompletableDeferred<Unit>()
            var mutations = 0
            holder.configure(object : SavedPathsDependencies {
                override fun connectedDevice() = connected
                override fun savedPaths() = object : TracePathPersisting by store {
                    private suspend fun beforeMutation() {
                        if (++mutations == 1) {
                            gate.await()
                            throw IOException("older held mutation")
                        }
                        if (newer == "failure") throw IOException("newer mutation")
                    }
                    override suspend fun updateSavedTracePathName(key: EntityKey, name: String) {
                        beforeMutation()
                        store.updateSavedTracePathName(key, name)
                    }
                    override suspend fun deleteSavedTracePath(key: EntityKey) {
                        beforeMutation()
                        store.deleteSavedTracePath(key)
                    }
                }
            })
            harness.complete { holder.loadSavedPaths() }
            val pending = harness.start {
                if (delete) holder.deletePath(path) else holder.renamePath(path, "Old request")
            }
            harness.complete { holder.loadSavedPaths() }
            if (newer != "none") {
                harness.complete {
                    if (delete) holder.renamePath(path, "New accepted name") else holder.deletePath(path)
                }
            }
            val newerState = holder.state.value
            gate.complete(Unit)
            harness.main.runCurrent()
            assertTrue(pending.isCompleted)
            val expected = when {
                newer == "success" -> null
                newer == "failure" -> if (delete) Strings.renameFailed else Strings.deleteFailed
                delete -> Strings.deleteFailed
                else -> Strings.renameFailed
            }
            assertEquals(expected, holder.state.value.errorMessage)
            assertEquals(newerState.savedPaths, holder.state.value.savedPaths)
            if (newer == "none") assertEquals(path, store.paths[path.id])
        }
    }

    @Test
    fun `older successful rename refresh cannot erase a newer mutation failure`() {
        val path = savedPath(Bytes.of(1), radioId = RADIO)
        store.insert(path)
        val gate = CompletableDeferred<Unit>()
        holder.configure(object : SavedPathsDependencies {
            override fun connectedDevice() = connected
            override fun savedPaths() = object : TracePathPersisting by store {
                override suspend fun updateSavedTracePathName(key: EntityKey, name: String) {
                    gate.await()
                    store.updateSavedTracePathName(key, name)
                }
                override suspend fun deleteSavedTracePath(key: EntityKey) {
                    throw IOException("newer failed delete")
                }
            }
        })
        harness.complete { holder.loadSavedPaths() }
        val rename = harness.start { holder.renamePath(path, "Accepted name") }
        assertFalse(harness.complete { holder.deletePath(path) })
        assertEquals(Strings.deleteFailed, holder.state.value.errorMessage)
        gate.complete(Unit)
        harness.main.runCurrent()
        assertTrue(rename.isCompleted)
        assertEquals("Accepted name", holder.state.value.savedPaths.single { it.id == path.id }.name)
        assertEquals(Strings.deleteFailed, holder.state.value.errorMessage)
    }

    @Test
    fun `delete preserves overlapping rename reload before and after the actual store read`() {
        for (holdBeforeRead in listOf(false, true)) {
            val a = savedPath(Bytes.of(1), radioId = RADIO)
            val b = savedPath(Bytes.of(2), radioId = RADIO)
            store.insert(a)
            store.insert(b)
            val gate = CompletableDeferred<Unit>()
            var reads = 0
            val current = SavedPathsStateHolder(Strings, diagnostics).apply {
                configure(object : SavedPathsDependencies {
                    override fun connectedDevice() = connected
                    override fun savedPaths() = object : TracePathPersisting by store {
                        override suspend fun fetchSavedTracePaths(radioId: RadioId): SnapshotList<SavedTracePathDTO> {
                            val held = ++reads == 2
                            if (held && holdBeforeRead) gate.await()
                            val snapshot = store.fetchSavedTracePaths(radioId)
                            if (held && !holdBeforeRead) gate.await()
                            return snapshot
                        }
                    }
                })
            }
            harness.complete { current.loadSavedPaths() }
            val rename = harness.start { current.renamePath(a, "Renamed before delete") }
            assertEquals("Renamed before delete", store.paths.getValue(a.id).name)
            assertTrue(current.state.value.isLoading)
            assertTrue(harness.complete { current.deletePath(b) })
            assertFalse(current.state.value.savedPaths.any { it.id == b.id })
            gate.complete(Unit)
            harness.main.runCurrent()
            assertTrue(rename.isCompleted)
            assertEquals("Renamed before delete", current.state.value.savedPaths.single { it.id == a.id }.name)
            assertFalse(current.state.value.savedPaths.any { it.id == b.id }, "A held pre-delete snapshot cannot resurrect B")
            assertFalse(store.paths.containsKey(b.id))
            assertNull(current.state.value.errorMessage)
            assertFalse(current.state.value.isLoading)
            harness.complete { store.deleteSavedTracePath(EntityKey(a.radioId, a.id)) }
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
        val detail = SavedPathDetailState(savedPath(Bytes.of(1, 2), hashSize = 2, runs = runs), isVerifiedFromStore = true)
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
        assertEquals(path, detail.state.value.savedPath)
        assertFalse(detail.state.value.isVerifiedFromStore)
        assertFalse(detail.state.value.isAvailable)
        detail.configure { store }
        harness.complete { store.appendTracePathRun(com.meshcoreone.android.core.contracts.domain.EntityKey(RADIO, path.id), run(T0)) }
        harness.complete { detail.refresh() }
        assertEquals(1, assertNotNull(detail.state.value.savedPath).runs.size)
        assertTrue(detail.state.value.isVerifiedFromStore)
        assertFalse(detail.state.value.isLoading)
    }

    @Test fun `detail chart threshold ignores failed runs and empty history has no RTT`() {
        val empty = SavedPathDetailState(savedPath(Bytes.of(1)), isVerifiedFromStore = true)
        assertFalse(empty.showsChart)
        assertNull(empty.averageRoundTrip)
        assertNull(empty.bestRoundTrip)
        assertEquals("100%", empty.successRateText)
        val failures = empty.copy(savedPath = assertNotNull(empty.savedPath).copy(
            runs = com.meshcoreone.android.core.model.SnapshotList.of(run(T0, success = false)),
        ))
        assertFalse(failures.showsChart)
        assertNull(failures.averageRoundTrip)
        assertEquals("0%", failures.successRateText)
        val one = failures.copy(savedPath = assertNotNull(failures.savedPath).copy(
            runs = com.meshcoreone.android.core.model.SnapshotList.of(run(T0, success = false), run(T0.minusSeconds(1), 200)),
        ))
        assertFalse(one.showsChart)
        assertEquals(200L, one.averageRoundTrip)
        assertEquals("50%", one.successRateText)
        assertTrue(one.copy(savedPath = assertNotNull(one.savedPath).copy(
            runs = com.meshcoreone.android.core.model.SnapshotList.of(run(T0, 100), run(T0.minusSeconds(1), 200)),
        )).showsChart)
    }

    @Test fun `detail refresh exposes failure keeps cached runs and recovers or reports deletion`() {
        val path = savedPath(Bytes.of(1), radioId = RADIO, runs = listOf(run(T0)))
        store.insert(path)
        val detail = SavedPathDetailStateHolder(path, diagnostics)
        detail.configure { store }
        store.failure = IOException("read failed")
        harness.complete { detail.refresh() }
        assertEquals(SavedPathDetailError.LOAD, detail.state.value.error)
        assertEquals(path, detail.state.value.savedPath)
        assertFalse(detail.state.value.isLoading)
        assertEquals("refreshSavedPath", diagnostics.failures.single().first)
        store.failure = null
        harness.complete { detail.refresh() }
        assertNull(detail.state.value.error)
        store.paths.clear()
        harness.complete { detail.refresh() }
        assertEquals(SavedPathDetailError.NOT_FOUND, detail.state.value.error)
        assertFalse(detail.state.value.isVerifiedFromStore)
    }

    @Test fun `deleted detail stays unavailable through suspended retry failure and cancellation until a matching read succeeds`() {
        val path = savedPath(Bytes.of(1), radioId = RADIO, runs = listOf(run(T0)))
        val detail = SavedPathDetailStateHolder(path, diagnostics)
        var gate: CompletableDeferred<Unit>? = null
        detail.configure {
            object : TracePathPersisting by store {
                override suspend fun fetchSavedTracePath(key: EntityKey): SavedTracePathDTO? {
                    gate?.await()
                    return store.fetchSavedTracePath(key)
                }
            }
        }
        harness.complete { detail.refresh() }
        assertEquals(SavedPathDetailError.NOT_FOUND, detail.state.value.error)
        assertFalse(detail.state.value.isAvailable)
        assertTrue(detail.state.value.sortedRuns.isEmpty())
        val failedRead = CompletableDeferred<Unit>()
        gate = failedRead
        store.failure = IOException("retry failed")
        harness.start { detail.refresh() }
        assertTrue(detail.state.value.isLoading)
        assertFalse(detail.state.value.isAvailable)
        assertTrue(detail.state.value.sortedRuns.isEmpty())
        failedRead.complete(Unit)
        harness.main.runCurrent()
        assertEquals(SavedPathDetailError.LOAD, detail.state.value.error)
        assertFalse(detail.state.value.isAvailable)
        assertTrue(detail.state.value.sortedRuns.isEmpty())
        val reopened = SavedPathDetailStateHolder(path, diagnostics)
        reopened.configure { store }
        harness.complete { reopened.refresh() }
        assertFalse(reopened.state.value.isAvailable, "Re-entering from a cached path must also await a matching successful read")
        assertTrue(reopened.state.value.sortedRuns.isEmpty())

        gate = CompletableDeferred()
        val cancelled = harness.start { detail.refresh() }
        cancelled.cancel()
        harness.main.runCurrent()
        assertFalse(detail.state.value.isAvailable)
        assertTrue(detail.state.value.sortedRuns.isEmpty())
        gate = null
        store.failure = null
        store.insert(path)
        harness.complete { detail.refresh() }
        assertTrue(detail.state.value.isAvailable)
        assertTrue(detail.state.value.isVerifiedFromStore)
        assertEquals(path.runs.toList(), detail.state.value.sortedRuns)
    }

    @Test fun `identity-only detail resolves from the store without manufacturing a saved path`() {
        val path = savedPath(Bytes.of(0xAA, 0xBB), hashSize = 2, radioId = RADIO, runs = listOf(run(T0)))
        val key = EntityKey(RADIO, path.id)
        val detail = SavedPathDetailStateHolder(key, diagnostics)
        assertNull(detail.state.value.savedPath)
        assertFalse(detail.state.value.isAvailable)
        var readKey: EntityKey? = null
        detail.configure {
            object : TracePathPersisting by store {
                override suspend fun fetchSavedTracePath(key: EntityKey): SavedTracePathDTO? {
                    readKey = key
                    return store.fetchSavedTracePath(key)
                }
            }
        }
        store.insert(path)
        harness.complete { detail.refresh() }
        assertEquals(key, readKey)
        assertEquals(path, detail.state.value.savedPath)
        assertTrue(detail.state.value.isAvailable)
    }

    @Test fun `detail rejects stale session and retired completions including same radio reconnect`() {
        for (retire in listOf(false, true)) {
            val path = savedPath(Bytes.of(1), radioId = RADIO)
            store.insert(path)
            var generation = 1
            val gate = CompletableDeferred<Unit>()
            val detail = SavedPathDetailStateHolder(path, diagnostics)
            detail.configure(isCurrent = { generation == 1 }) {
                object : TracePathPersisting by store {
                    override suspend fun fetchSavedTracePath(key: EntityKey): SavedTracePathDTO? {
                        gate.await()
                        return path.copy(name = "Late snapshot")
                    }
                }
            }
            val pending = harness.start { detail.refresh() }
            assertTrue(detail.state.value.isLoading)
            if (retire) detail.retireConnection() else generation = 2
            assertFalse(detail.isCurrentConnection())
            gate.complete(Unit)
            harness.main.runCurrent()
            assertTrue(pending.isCompleted)
            assertEquals(path, detail.state.value.savedPath)
            assertEquals(SavedPathDetailError.STALE_SESSION, detail.state.value.error)
            assertFalse(detail.state.value.isLoading)
        }
    }

    @Test fun `detail overlapping refresh cannot replace newer snapshot and cancellation propagates`() {
        val path = savedPath(Bytes.of(1), radioId = RADIO)
        store.insert(path)
        val gate = CompletableDeferred<Unit>()
        var hold = true
        val detail = SavedPathDetailStateHolder(path, diagnostics)
        detail.configure {
            object : TracePathPersisting by store {
                override suspend fun fetchSavedTracePath(key: EntityKey): SavedTracePathDTO? {
                    val snapshot = store.fetchSavedTracePath(key)
                    if (hold) gate.await()
                    return snapshot
                }
            }
        }
        harness.start { detail.refresh() }
        hold = false
        val updated = path.copy(name = "Latest", runs = com.meshcoreone.android.core.model.SnapshotList.of(run(T0)))
        store.insert(updated)
        harness.complete { detail.refresh() }
        gate.complete(Unit)
        harness.main.runCurrent()
        assertEquals(updated, detail.state.value.savedPath)

        val cancelGate = CompletableDeferred<Unit>()
        detail.configure {
            object : TracePathPersisting by store {
                override suspend fun fetchSavedTracePath(key: EntityKey): SavedTracePathDTO? {
                    cancelGate.await()
                    return path
                }
            }
        }
        val cancelled = harness.start { detail.refresh() }
        cancelled.cancel()
        harness.main.runCurrent()
        assertTrue(cancelled.isCancelled)
        assertFalse(detail.state.value.isLoading)
        assertNull(detail.state.value.error)
        assertTrue(diagnostics.failures.isEmpty())
    }

    @Test fun `detail missing store and wrong identity are visible load failures`() {
        val path = savedPath(Bytes.of(1), radioId = RADIO)
        val detail = SavedPathDetailStateHolder(path, diagnostics)
        detail.configure { null }
        harness.complete { detail.refresh() }
        assertEquals(SavedPathDetailError.LOAD, detail.state.value.error)
        detail.configure {
            object : TracePathPersisting by store {
                override suspend fun fetchSavedTracePath(key: EntityKey) = path.copy(radioId = RadioId(UUID.randomUUID()))
            }
        }
        harness.complete { detail.refresh() }
        assertEquals(SavedPathDetailError.LOAD, detail.state.value.error)
        assertEquals(path, detail.state.value.savedPath)
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
