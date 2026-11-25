// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/DebugLogBufferTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.contracts.domain.DebugLogPersisting
import com.meshcoreone.android.core.contracts.domain.DebugLogRetention
import com.meshcoreone.android.core.model.DebugLogEntryDTO
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.SnapshotList
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Both hub tests reassign the process-global [DebugLogBuffer.shared]; JUnit runs this module's
 * tests sequentially (the Swift suite is `.serialized`). The bounded read-back retry and fresh
 * per-attempt categories are kept from Swift so a foreign writer of the global cannot flake them.
 */
class DebugLogBufferTest {
    @TestFactory
    fun sourceCases(): List<DynamicTest> = listOf(
        logCoreOriginal(SUITE, "shared get and set round-trip through the lock") {
            withLogCoreScope { scope ->
                val buffer = DebugLogBuffer(LogCoreMemoryStore(), scope)
                assertTrue(writeAndReadBack(buffer))
                DebugLogBuffer.shared = null
            }
        },
        logCoreOriginal(SUITE, "concurrent reads and writes of shared are race-free") {
            withLogCoreScope { scope ->
                val bufferA = DebugLogBuffer(LogCoreMemoryStore(), scope)
                val bufferB = DebugLogBuffer(LogCoreMemoryStore(), scope)
                DebugLogBuffer.shared = bufferA

                coroutineScope {
                    repeat(200) { index ->
                        launch(Dispatchers.Default) {
                            if (index % 2 == 0) {
                                DebugLogBuffer.shared = if (index % 4 == 0) bufferA else bufferB
                            } else {
                                val observed = DebugLogBuffer.shared
                                assertTrue(observed == null || observed === bufferA || observed === bufferB)
                            }
                        }
                    }
                }

                assertTrue(writeAndReadBack(bufferA))
                DebugLogBuffer.shared = null
            }
        },
        logCoreOriginal(SUITE, "entries logged before shared is assigned are drained in order once a buffer is set") {
            withLogCoreScope { scope ->
                val store = LogCoreMemoryStore()
                val buffer = DebugLogBuffer(store, scope)

                var entries: List<DebugLogEntryDTO> = emptyList()
                for (attempt in 0 until MAX_READ_BACK_ATTEMPTS) {
                    val category = UUID.randomUUID().toString()
                    val logger = PersistentLogger("test.pending", category, platformSink = LogCoreRecordingSink())
                    DebugLogBuffer.shared = null
                    DebugLogBuffer.resetPendingStateForTesting()
                    logger.info("first")
                    logger.info("second")

                    if (!writeAndReadBack(buffer)) continue
                    entries = pollForDebugLogEntries(store, buffer, category, minimumCount = 2)
                    if (entries.size >= 2) break
                }
                assertEquals(listOf("first", "second"), entries.map { it.message })

                DebugLogBuffer.shared = null
            }
        },
        logCoreOriginal(SUITE, "pending queue drops oldest entries once the bound is exceeded") {
            withLogCoreScope { scope ->
                val store = LogCoreMemoryStore()
                val buffer = DebugLogBuffer(store, scope)
                val overflow = 3
                val total = DebugLogBuffer.MAX_PENDING_ENTRIES + overflow

                var entries: List<DebugLogEntryDTO> = emptyList()
                for (attempt in 0 until MAX_READ_BACK_ATTEMPTS) {
                    val category = UUID.randomUUID().toString()
                    DebugLogBuffer.shared = null
                    DebugLogBuffer.resetPendingStateForTesting()
                    for (index in 0 until total) {
                        DebugLogBuffer.record(logCoreEntry(category, "$index"))
                    }

                    if (!writeAndReadBack(buffer)) continue
                    entries = pollForDebugLogEntries(store, buffer, category, minimumCount = DebugLogBuffer.MAX_PENDING_ENTRIES)
                    if (entries.size >= DebugLogBuffer.MAX_PENDING_ENTRIES) break
                }
                assertEquals(DebugLogBuffer.MAX_PENDING_ENTRIES, entries.size)
                assertEquals("$overflow", entries.first().message)
                assertEquals("${total - 1}", entries.last().message)

                DebugLogBuffer.shared = null
            }
        },
        logCoreOriginal(SUITE, "dropped entries during save failures are reported once the store recovers") {
            withLogCoreScope { scope ->
                val store = BufferTestsGatedStore()
                val buffer = DebugLogBuffer(store, scope, LogCoreManualClock(), LogCoreRecordingSink())

                accumulateDroppedBatch(store, buffer)

                logCoreAwait("recovery flush should write a dropped-count summary") {
                    buffer.flush()
                    store.savedEntries.any { it.level == DebugLogLevel.WARNING }
                }
                val saved = store.savedEntries

                val realEntries = saved.filter { it.level != DebugLogLevel.WARNING }
                assertEquals(DebugLogBuffer.MAX_BUFFER_SIZE, realEntries.size)

                val summary = saved.firstOrNull { it.category == "DebugLogBuffer" && it.level == DebugLogLevel.WARNING }
                assertNotNull(summary)
                assertTrue(summary.message.contains("${DebugLogBuffer.MAX_BUFFER_SIZE}"))
            }
        },
        logCoreOriginal(SUITE, "a failed summary save carries the dropped count forward to the next successful save") {
            withLogCoreScope { scope ->
                val store = BufferTestsGatedStore()
                val buffer = DebugLogBuffer(store, scope, LogCoreManualClock(), LogCoreRecordingSink())

                accumulateDroppedBatch(store, buffer)
                store.setFailNextSummarySave(true)

                // First recovery flush saves the surviving batch; its summary save fails once.
                logCoreAwait("first recovery flush should persist the surviving batch") {
                    buffer.flush()
                    store.savedEntries.count { it.level != DebugLogLevel.WARNING } >= DebugLogBuffer.MAX_BUFFER_SIZE
                }
                var saved = store.savedEntries
                assertTrue(saved.none { it.level == DebugLogLevel.WARNING })

                // The next successful save must report the carried-forward count in full.
                buffer.append(logCoreEntry("c", "post-recovery"))
                logCoreAwait("next successful save should report the carried-forward drop count") {
                    buffer.flush()
                    store.savedEntries.any { it.level == DebugLogLevel.WARNING }
                }
                saved = store.savedEntries

                val summary = saved.firstOrNull { it.category == "DebugLogBuffer" && it.level == DebugLogLevel.WARNING }
                assertNotNull(summary)
                assertTrue(summary.message.contains("${DebugLogBuffer.MAX_BUFFER_SIZE}"))
            }
        },
        logCoreOriginal(SUITE, "hourly prune is skipped when last prune is within pruneInterval") {
            withLogCoreScope { scope ->
                val clock = LogCoreManualClock()
                val store = BufferTestsRecordingStore()
                val buffer = DebugLogBuffer(store, scope, clock, LogCoreRecordingSink())
                buffer.setLastPruneForTesting(clock.now())
                buffer.append(logCoreEntry("prune", "recent"))
                buffer.flush()
                assertEquals(0, store.pruneCallCount)
                assertNull(store.lastPruneCutoff)
                assertNull(store.lastPruneKeepCount)
            }
        },
        logCoreOriginal(SUITE, "hourly prune runs when last prune is older than pruneInterval") {
            withLogCoreScope { scope ->
                val clock = LogCoreManualClock()
                val store = BufferTestsRecordingStore()
                val buffer = DebugLogBuffer(store, scope, clock, LogCoreRecordingSink())
                buffer.setLastPruneForTesting(clock.now().minus(DebugLogRetention.pruneInterval))
                buffer.append(logCoreEntry("prune", "stale"))
                buffer.flush()
                assertEquals(1, store.pruneCallCount)
                expectWindowBackedPrune(store, clock)
            }
        },
        logCoreOriginal(SUITE, "hourly prune retries on the next flush after a failed prune") {
            withLogCoreScope { scope ->
                val clock = LogCoreManualClock()
                val store = BufferTestsRecordingStore()
                store.pruneShouldFail = true
                val sink = LogCoreRecordingSink()
                val buffer = DebugLogBuffer(store, scope, clock, sink)
                buffer.setLastPruneForTesting(clock.now().minus(DebugLogRetention.pruneInterval))
                buffer.append(logCoreEntry("prune", "first"))
                buffer.flush()
                assertEquals(1, store.pruneCallCount)
                expectWindowBackedPrune(store, clock)

                buffer.append(logCoreEntry("prune", "second"))
                buffer.flush()
                assertEquals(2, store.pruneCallCount)
                expectWindowBackedPrune(store, clock)
                assertEquals(
                    List(2) { LogCoreSinkLine(DebugLogLevel.ERROR, "com.mc1", "DebugLogBuffer", "Failed to prune debug logs: simulated") },
                    sink.lines,
                )
            }
        },
    )

    private fun writeAndReadBack(buffer: DebugLogBuffer, attempts: Int = MAX_READ_BACK_ATTEMPTS): Boolean {
        repeat(attempts) {
            DebugLogBuffer.shared = buffer
            if (DebugLogBuffer.shared === buffer) return true
        }
        return false
    }

    /** Repeatedly flushes [buffer] and re-checks [store] for entries in [category] (bounded, never fails itself). */
    private suspend fun pollForDebugLogEntries(
        store: LogCoreMemoryStore,
        buffer: DebugLogBuffer,
        category: String,
        minimumCount: Int,
    ): List<DebugLogEntryDTO> {
        logCorePoll {
            buffer.flush()
            store.debugLogEntries.count { it.category == category } >= minimumCount
        }
        return store.debugLogEntries.filter { it.category == category }
    }

    /**
     * Drives two size-triggered flushes into the gated store and releases them while it is still
     * failing, leaving `MAX_BUFFER_SIZE` surviving entries and a dropped count of `MAX_BUFFER_SIZE`.
     *
     * Android adaptation: Kotlin `append` is synchronous, so the test waits for the first flush to
     * reach the gate before appending the second batch (Swift's actor hop provided that ordering),
     * and waits for both failures to settle before handing control back.
     */
    private suspend fun accumulateDroppedBatch(store: BufferTestsGatedStore, buffer: DebugLogBuffer) {
        for (index in 0 until DebugLogBuffer.MAX_BUFFER_SIZE) buffer.append(logCoreEntry("a", "a$index"))
        logCoreAwait("first size-triggered flush should park at the save gate") { store.pendingSaveCount >= 1 }
        for (index in 0 until DebugLogBuffer.MAX_BUFFER_SIZE) buffer.append(logCoreEntry("b", "b$index"))

        logCoreAwait("both size-triggered flushes should park at the save gate") { store.pendingSaveCount >= 2 }
        assertEquals(2, store.pendingSaveCount)

        store.releaseWaiters()
        store.setShouldFail(false)
        logCoreAwait("both failed saves should settle") {
            buffer.droppedEntryCountForTesting == DebugLogBuffer.MAX_BUFFER_SIZE.toLong()
        }
    }

    private fun expectWindowBackedPrune(store: BufferTestsRecordingStore, clock: LogCoreManualClock) {
        assertEquals(DebugLogRetention.MAX_ENTRIES, store.lastPruneKeepCount)
        // Manual clock: the cutoff is exact rather than within Swift's 2 s slack.
        assertEquals(clock.now().minus(DebugLogRetention.window), assertNotNull(store.lastPruneCutoff))
    }

    private companion object {
        const val SUITE = "DebugLogBufferTests"
        const val MAX_READ_BACK_ATTEMPTS = 5
    }
}

/**
 * Saves block on a gate until released, so two size-triggered flushes can be held in flight at
 * once and fail together. The fail/succeed decision is captured before gating.
 */
private class BufferTestsGatedStore : DebugLogPersisting {
    private val lock = Any()
    private var saved: List<DebugLogEntryDTO> = emptyList()
    private var shouldFail = true
    private var failNextSummarySave = false
    private var waiters: List<CompletableDeferred<Unit>> = emptyList()

    val savedEntries: List<DebugLogEntryDTO> get() = synchronized(lock) { saved }
    val pendingSaveCount: Int get() = synchronized(lock) { waiters.size }

    override suspend fun saveDebugLogEntries(dtos: SnapshotList<DebugLogEntryDTO>) {
        val gate = synchronized(lock) {
            if (shouldFail) CompletableDeferred<Unit>().also { waiters = waiters + it } else null
        }
        if (gate != null) {
            gate.await()
            throw LogCoreStoreFailure("saveFailed")
        }
        synchronized(lock) {
            if (failNextSummarySave && dtos.size == 1) {
                failNextSummarySave = false
                throw LogCoreStoreFailure("saveFailed")
            }
            saved = saved + dtos
        }
    }

    override suspend fun fetchDebugLogEntries(since: Instant, limit: Long): SnapshotList<DebugLogEntryDTO> = SnapshotList.empty()
    override suspend fun countDebugLogEntries(): Long = savedEntries.size.toLong()
    override suspend fun pruneDebugLogEntries(olderThan: Instant, keepCount: Long) = Unit
    override suspend fun clearDebugLogEntries() = Unit

    /** Resumes every save currently blocked on the gate. */
    fun releaseWaiters() {
        val pending = synchronized(lock) { waiters.also { waiters = emptyList() } }
        pending.forEach { it.complete(Unit) }
    }

    fun setShouldFail(value: Boolean) = synchronized(lock) { shouldFail = value }

    /** Fails the next single-entry save (the drop summary is always saved alone). */
    fun setFailNextSummarySave(value: Boolean) = synchronized(lock) { failNextSummarySave = value }
}

private class BufferTestsRecordingStore : DebugLogPersisting {
    private val lock = Any()
    private var calls = 0
    private var cutoff: Instant? = null
    private var keepCount: Long? = null
    @Volatile var pruneShouldFail = false

    val pruneCallCount: Int get() = synchronized(lock) { calls }
    val lastPruneCutoff: Instant? get() = synchronized(lock) { cutoff }
    val lastPruneKeepCount: Long? get() = synchronized(lock) { keepCount }

    override suspend fun saveDebugLogEntries(dtos: SnapshotList<DebugLogEntryDTO>) = Unit
    override suspend fun fetchDebugLogEntries(since: Instant, limit: Long): SnapshotList<DebugLogEntryDTO> = SnapshotList.empty()
    override suspend fun countDebugLogEntries(): Long = 0

    override suspend fun pruneDebugLogEntries(olderThan: Instant, keepCount: Long) {
        synchronized(lock) {
            calls += 1
            cutoff = olderThan
            this.keepCount = keepCount
        }
        if (pruneShouldFail) throw LogCoreStoreFailure("simulated")
    }

    override suspend fun clearDebugLogEntries() = Unit
}
