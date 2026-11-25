// PortedFrom: MC1Services/Sources/MC1Services/Services/DebugLogBuffer.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native regressions: flush cadence, backpressure, drop summary, hub ordering, cancellation and concurrency.
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.contracts.domain.DebugLogPersisting
import com.meshcoreone.android.core.contracts.domain.DebugLogRetention
import com.meshcoreone.android.core.model.DebugLogEntryDTO
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.SnapshotList
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class DebugLogBufferNativeTest {
    @TestFactory
    fun nativeCases(): List<DynamicTest> = listOf(
        logCoreNative("buffer constants match Swift (50 per batch, 500 pending, 5 s interval)") {
            assertEquals(50, DebugLogBuffer.MAX_BUFFER_SIZE)
            assertEquals(500, DebugLogBuffer.MAX_PENDING_ENTRIES)
            assertEquals(5_000L, DebugLogBuffer.FLUSH_INTERVAL.inWholeMilliseconds)
            assertEquals(Duration.ofDays(7), DebugLogRetention.window)
            assertEquals(50_000L, DebugLogRetention.MAX_ENTRIES)
            assertEquals(Duration.ofHours(1), DebugLogRetention.pruneInterval)
        },
        logCoreNative("a scheduled flush fires exactly at the 5 s interval and not before") {
            withLogCoreScope { scope ->
                val clock = LogCoreManualClock()
                val store = LogCoreMemoryStore()
                val buffer = DebugLogBuffer(store, scope, clock, LogCoreRecordingSink())
                buffer.append(logCoreEntry("timer", "one"))
                logCoreAwait("timer should be sleeping") { clock.sleeperCount == 1 }

                clock.advance(Duration.ofMillis(4_999))
                settle()
                assertTrue(store.debugLogEntries.isEmpty())
                assertEquals(1, clock.sleeperCount)

                clock.advance(Duration.ofMillis(1))
                logCoreAwait("timer flush should save the entry") { store.debugLogEntries.size == 1 }
                assertEquals(0, buffer.bufferedCountForTesting)
            }
        },
        logCoreNative("appends within the interval share one timer and the next append after it rearms") {
            withLogCoreScope { scope ->
                val clock = LogCoreManualClock()
                val store = LogCoreMemoryStore()
                val buffer = DebugLogBuffer(store, scope, clock, LogCoreRecordingSink())
                repeat(3) { buffer.append(logCoreEntry("timer", "e$it")) }
                logCoreAwait("one timer") { clock.sleeperCount == 1 }
                settle()
                assertEquals(1, clock.sleeperCount)

                clock.advance(DebugLogBuffer.FLUSH_INTERVAL.toJavaDuration())
                logCoreAwait("timer flush saves all three") { store.debugLogEntries.size == 3 }
                assertEquals(listOf("e0", "e1", "e2"), store.debugLogEntries.map { it.message })

                buffer.append(logCoreEntry("timer", "e3"))
                logCoreAwait("a new timer is armed") { clock.sleeperCount == 1 }
            }
        },
        logCoreNative("the 50th entry flushes immediately and cancels the pending timer") {
            withLogCoreScope { scope ->
                val clock = LogCoreManualClock()
                val store = LogCoreMemoryStore()
                val buffer = DebugLogBuffer(store, scope, clock, LogCoreRecordingSink())
                repeat(DebugLogBuffer.MAX_BUFFER_SIZE - 1) { buffer.append(logCoreEntry("size", "e$it")) }
                logCoreAwait("timer armed") { clock.sleeperCount == 1 }
                settle()
                assertTrue(store.debugLogEntries.isEmpty())

                buffer.append(logCoreEntry("size", "e49"))
                logCoreAwait("size flush saves 50") { store.debugLogEntries.size == DebugLogBuffer.MAX_BUFFER_SIZE }
                logCoreAwait("timer cancelled") { clock.sleeperCount == 0 }
                assertEquals((0 until 50).map { "e$it" }, store.debugLogEntries.map { it.message })
            }
        },
        logCoreNative("flush cancels the pending timer so the interval elapsing later saves nothing twice") {
            withLogCoreScope { scope ->
                val clock = LogCoreManualClock()
                val store = LogCoreMemoryStore()
                val buffer = DebugLogBuffer(store, scope, clock, LogCoreRecordingSink())
                buffer.append(logCoreEntry("flush", "only"))
                logCoreAwait("timer armed") { clock.sleeperCount == 1 }
                buffer.flush()
                assertEquals(1, store.debugLogEntries.size)
                logCoreAwait("timer cancelled") { clock.sleeperCount == 0 }
                clock.advance(Duration.ofSeconds(10))
                settle()
                assertEquals(1, store.debugLogEntries.size)
                buffer.shutdown()
                assertEquals(1, store.debugLogEntries.size)
            }
        },
        logCoreNative("a failed save requeues the oldest 50, drops the overflow and reports the exact Swift summary") {
            withLogCoreScope { scope ->
                val clock = LogCoreManualClock()
                val store = LogCoreMemoryStore().apply { saveError = LogCoreStoreFailure("disk full") }
                val sink = LogCoreRecordingSink()
                val buffer = DebugLogBuffer(store, scope, clock, sink)
                repeat(DebugLogBuffer.MAX_BUFFER_SIZE) { buffer.append(logCoreEntry("bp", "e$it")) }
                logCoreAwait("first failure requeues 50") {
                    store.saveFailureCount == 1 && buffer.bufferedCountForTesting == DebugLogBuffer.MAX_BUFFER_SIZE
                }
                assertEquals(0L, buffer.droppedEntryCountForTesting)

                buffer.append(logCoreEntry("bp", "e50"))
                logCoreAwait("second failure drops the newest overflow entry") {
                    store.saveFailureCount == 2 && buffer.droppedEntryCountForTesting == 1L
                }
                assertEquals(DebugLogBuffer.MAX_BUFFER_SIZE, buffer.bufferedCountForTesting)
                assertEquals(
                    List(2) { LogCoreSinkLine(DebugLogLevel.ERROR, "com.mc1", "DebugLogBuffer", "Failed to save debug logs: disk full") },
                    sink.lines,
                )

                store.saveError = null
                buffer.flush()
                val saved = store.debugLogEntries
                assertEquals((0 until 50).map { "e$it" }, saved.take(50).map { it.message })
                val summary = saved.last()
                assertEquals(51, saved.size)
                assertEquals(DebugLogLevel.WARNING, summary.level)
                assertEquals("com.mc1", summary.subsystem)
                assertEquals("DebugLogBuffer", summary.category)
                assertEquals("Lost 1 log entries due to prior save failures", summary.message)
                assertEquals(clock.now(), summary.timestamp)
                assertEquals(0L, buffer.droppedEntryCountForTesting)
            }
        },
        logCoreNative("a batch that fails again on flush is requeued without counting drops") {
            withLogCoreScope { scope ->
                val store = LogCoreMemoryStore().apply { saveError = LogCoreStoreFailure("x") }
                val buffer = DebugLogBuffer(store, scope, LogCoreManualClock(), LogCoreRecordingSink())
                repeat(DebugLogBuffer.MAX_BUFFER_SIZE) { buffer.append(logCoreEntry("cap", "a$it")) }
                logCoreAwait("first batch requeued") { store.saveFailureCount == 1 && buffer.bufferedCountForTesting == 50 }
                // flush() takes the 50 requeued entries; they fail again and fit (0 + 50 < 100).
                buffer.flush()
                assertEquals(2, store.saveFailureCount)
                assertEquals(50, buffer.bufferedCountForTesting)
                assertEquals(0L, buffer.droppedEntryCountForTesting)
            }
        },
        logCoreNative("requeue boundary: 99 buffered-plus-requeued entries fit") {
            withLogCoreScope { scope ->
                val store = NativeTestsSequencedGateStore()
                val buffer = DebugLogBuffer(store, scope, LogCoreManualClock(), LogCoreRecordingSink())
                repeat(DebugLogBuffer.MAX_BUFFER_SIZE) { buffer.append(logCoreEntry("edge", "a$it")) }
                logCoreAwait("batch a parked") { store.parkedCount == 1 }
                repeat(DebugLogBuffer.MAX_BUFFER_SIZE - 1) { buffer.append(logCoreEntry("edge", "b$it")) }
                store.releaseOldest()
                logCoreAwait("batch a requeued in front of b") { buffer.bufferedCountForTesting == 99 }
                assertEquals(0L, buffer.droppedEntryCountForTesting)
                store.failing = false
                buffer.flush()
                assertEquals(
                    (0 until 50).map { "a$it" } + (0 until 49).map { "b$it" },
                    store.saved.map { it.message },
                )
            }
        },
        logCoreNative("requeue boundary: reaching 100 drops the whole failed batch and keeps the earlier requeue") {
            withLogCoreScope { scope ->
                val store = NativeTestsSequencedGateStore()
                val buffer = DebugLogBuffer(store, scope, LogCoreManualClock(), LogCoreRecordingSink())
                repeat(DebugLogBuffer.MAX_BUFFER_SIZE) { buffer.append(logCoreEntry("edge", "a$it")) }
                logCoreAwait("batch a parked") { store.parkedCount == 1 }
                repeat(DebugLogBuffer.MAX_BUFFER_SIZE) { buffer.append(logCoreEntry("edge", "b$it")) }
                logCoreAwait("batch b parked") { store.parkedCount == 2 }

                store.releaseOldest()
                logCoreAwait("batch a requeued") { buffer.bufferedCountForTesting == 50 }
                assertEquals(0L, buffer.droppedEntryCountForTesting)
                store.releaseOldest()
                logCoreAwait("batch b dropped") { buffer.droppedEntryCountForTesting == 50L }
                assertEquals(50, buffer.bufferedCountForTesting)

                store.failing = false
                buffer.flush()
                val saved = store.saved
                assertEquals((0 until 50).map { "a$it" }, saved.take(50).map { it.message })
                assertEquals("Lost 50 log entries due to prior save failures", saved.last().message)
                assertEquals(51, saved.size)
            }
        },
        logCoreNative("concurrent successful flushes persist only one drop summary") {
            withLogCoreScope { scope ->
                val store = NativeTestsSummaryGateStore()
                val buffer = DebugLogBuffer(store, scope, LogCoreManualClock(), LogCoreRecordingSink())
                store.failBatches = true
                repeat(DebugLogBuffer.MAX_BUFFER_SIZE) { buffer.append(logCoreEntry("re", "a$it")) }
                logCoreAwait("first requeue") { buffer.bufferedCountForTesting == 50 && store.failures == 1 }
                buffer.append(logCoreEntry("re", "a50"))
                logCoreAwait("one drop recorded") { buffer.droppedEntryCountForTesting == 1L }
                store.failBatches = false
                store.holdSummaries = true

                coroutineScope {
                    val first = async { buffer.flush() }
                    logCoreAwait("first summary save parked") { store.parkedSummaries == 1 }
                    buffer.append(logCoreEntry("re", "late"))
                    buffer.flush()
                    assertEquals(1, store.parkedSummaries)
                    store.releaseSummaries()
                    first.await()
                }
                val summaries = store.saved.filter { it.level == DebugLogLevel.WARNING }
                assertEquals(listOf("Lost 1 log entries due to prior save failures"), summaries.map { it.message })
                assertEquals(0L, buffer.droppedEntryCountForTesting)
            }
        },
        logCoreNative("the first flush after construction does not prune because lastPrune is seeded to now") {
            withLogCoreScope { scope ->
                val clock = LogCoreManualClock()
                val store = NativeTestsPruneCountingStore()
                val buffer = DebugLogBuffer(store, scope, clock, LogCoreRecordingSink())
                buffer.append(logCoreEntry("seed", "x"))
                buffer.flush()
                assertEquals(0, store.prunes.size)
                clock.advance(DebugLogRetention.pruneInterval)
                buffer.append(logCoreEntry("seed", "y"))
                buffer.flush()
                assertEquals(listOf(clock.now().minus(DebugLogRetention.window) to DebugLogRetention.MAX_ENTRIES), store.prunes)
            }
        },
        logCoreNative("prune is not due one nanosecond before the interval and a success restarts the cadence") {
            withLogCoreScope { scope ->
                val clock = LogCoreManualClock()
                val store = NativeTestsPruneCountingStore()
                val buffer = DebugLogBuffer(store, scope, clock, LogCoreRecordingSink())
                buffer.setLastPruneForTesting(clock.now().minus(DebugLogRetention.pruneInterval).plusNanos(1))
                buffer.append(logCoreEntry("edge", "x"))
                buffer.flush()
                assertEquals(0, store.prunes.size)
                clock.advance(Duration.ofNanos(1))
                buffer.append(logCoreEntry("edge", "y"))
                buffer.flush()
                assertEquals(1, store.prunes.size)
                clock.advance(Duration.ofMinutes(59))
                buffer.append(logCoreEntry("edge", "z"))
                buffer.flush()
                assertEquals(1, store.prunes.size)
            }
        },
        logCoreNative("no prune and no summary run after a failed save") {
            withLogCoreScope { scope ->
                val clock = LogCoreManualClock()
                val store = NativeTestsPruneCountingStore().apply { failSaves = true }
                val buffer = DebugLogBuffer(store, scope, clock, LogCoreRecordingSink())
                buffer.setLastPruneForTesting(clock.now().minus(DebugLogRetention.pruneInterval))
                buffer.append(logCoreEntry("fail", "x"))
                buffer.flush()
                assertEquals(0, store.prunes.size)
                assertEquals(1, buffer.bufferedCountForTesting)
            }
        },
        logCoreNative("cancelling the owning scope mid-save puts the batch back and propagates cancellation") {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
            val store = NativeTestsBlockingStore()
            val buffer = DebugLogBuffer(store, scope, LogCoreManualClock(), LogCoreRecordingSink())
            repeat(DebugLogBuffer.MAX_BUFFER_SIZE) { buffer.append(logCoreEntry("cancel", "e$it")) }
            store.entered.await()
            scope.coroutineContext.job.cancelAndJoin()
            assertTrue(store.cancelled.isCompleted)
            assertEquals(DebugLogBuffer.MAX_BUFFER_SIZE, buffer.bufferedCountForTesting)

            store.block = false
            buffer.shutdown()
            assertEquals((0 until 50).map { "e$it" }, store.saved.map { it.message })
            assertEquals(0, buffer.bufferedCountForTesting)
        },
        logCoreNative("cancelling a caller of flush rethrows CancellationException and keeps the batch in order") {
            withLogCoreScope { scope ->
                val store = NativeTestsBlockingStore()
                val buffer = DebugLogBuffer(store, scope, LogCoreManualClock(), LogCoreRecordingSink())
                buffer.append(logCoreEntry("caller", "old"))
                coroutineScope {
                    val flushing = async { buffer.flush() }
                    store.entered.await()
                    buffer.append(logCoreEntry("caller", "new"))
                    flushing.cancel()
                    try {
                        flushing.await()
                        fail("a cancelled flush must not complete normally")
                    } catch (expected: CancellationException) {
                        assertTrue(flushing.isCancelled)
                    }
                }
                assertEquals(2, buffer.bufferedCountForTesting)
                store.block = false
                buffer.flush()
                assertEquals(listOf("old", "new"), store.saved.map { it.message })
            }
        },
        logCoreNative("assigning shared delivers drained entries before anything recorded afterwards") {
            withLogCoreScope { scope ->
                val store = LogCoreMemoryStore()
                val buffer = DebugLogBuffer(store, scope, LogCoreManualClock(), LogCoreRecordingSink())
                val category = UUID.randomUUID().toString()
                DebugLogBuffer.shared = null
                DebugLogBuffer.resetPendingStateForTesting()
                (1..3).forEach { DebugLogBuffer.record(logCoreEntry(category, "pending$it")) }
                assertEquals(3, DebugLogBuffer.pendingCountForTesting)
                DebugLogBuffer.shared = buffer
                assertEquals(0, DebugLogBuffer.pendingCountForTesting)
                DebugLogBuffer.record(logCoreEntry(category, "after"))
                buffer.flush()
                assertEquals(
                    listOf("pending1", "pending2", "pending3", "after"),
                    store.debugLogEntries.filter { it.category == category }.map { it.message },
                )
                DebugLogBuffer.shared = null
            }
        },
        logCoreNative("setting shared to nil keeps the pending queue for the next buffer") {
            withLogCoreScope { scope ->
                DebugLogBuffer.shared = null
                DebugLogBuffer.resetPendingStateForTesting()
                DebugLogBuffer.record(logCoreEntry("nil", "kept"))
                DebugLogBuffer.shared = null
                assertEquals(1, DebugLogBuffer.pendingCountForTesting)
                val store = LogCoreMemoryStore()
                val buffer = DebugLogBuffer(store, scope, LogCoreManualClock(), LogCoreRecordingSink())
                DebugLogBuffer.shared = buffer
                buffer.flush()
                assertEquals(listOf("kept"), store.debugLogEntries.filter { it.category == "nil" }.map { it.message })
                DebugLogBuffer.shared = null
                DebugLogBuffer.resetPendingStateForTesting()
            }
        },
        logCoreNative("records from many threads reach the shared buffer exactly once each") {
            withLogCoreScope { scope ->
                val store = LogCoreMemoryStore()
                val buffer = DebugLogBuffer(store, scope, LogCoreManualClock(), LogCoreRecordingSink())
                val category = UUID.randomUUID().toString()
                DebugLogBuffer.shared = null
                DebugLogBuffer.resetPendingStateForTesting()
                val threads = 8
                val perThread = 125
                DebugLogBuffer.shared = buffer
                coroutineScope {
                    repeat(threads) { worker ->
                        launch(Dispatchers.Default) {
                            repeat(perThread) { index ->
                                // Concurrent setter traffic on the same buffer must neither lose nor duplicate.
                                if (index % 25 == 0) DebugLogBuffer.shared = buffer
                                DebugLogBuffer.record(logCoreEntry(category, "$worker-$index"))
                            }
                        }
                    }
                }
                logCoreAwait("all ${threads * perThread} entries saved") {
                    buffer.flush()
                    store.debugLogEntries.count { it.category == category } >= threads * perThread
                }
                val messages = store.debugLogEntries.filter { it.category == category }.map { it.message }
                assertEquals(threads * perThread, messages.size)
                assertEquals(threads * perThread, messages.toSet().size)
                assertEquals(0, DebugLogBuffer.pendingCountForTesting)
                DebugLogBuffer.shared = null
            }
        },
        logCoreNative("an unrecoverable store error is reported but no exception escapes append or flush") {
            withLogCoreScope { scope ->
                val store = LogCoreMemoryStore().apply { saveError = IllegalStateException() }
                val sink = LogCoreRecordingSink()
                val buffer = DebugLogBuffer(store, scope, LogCoreManualClock(), sink)
                buffer.append(logCoreEntry("err", "x"))
                buffer.flush()
                assertEquals("Failed to save debug logs: java.lang.IllegalStateException", sink.lines.single().message)
                assertFalse(store.debugLogEntries.any())
            }
        },
    )

    /** Lets any launched work that could (wrongly) run do so before asserting a negative. */
    private suspend fun settle() {
        repeat(20) { yield() }
        kotlinx.coroutines.delay(20)
    }
}

private fun kotlin.time.Duration.toJavaDuration(): Duration = Duration.ofNanos(inWholeNanoseconds)

/** Batches can fail; single-entry summary saves can be parked on a gate. */
private class NativeTestsSummaryGateStore : DebugLogPersisting {
    private val lock = Any()
    private var entries: List<DebugLogEntryDTO> = emptyList()
    private var parked: List<CompletableDeferred<Unit>> = emptyList()
    private var failureCount = 0
    @Volatile var failBatches = false
    @Volatile var holdSummaries = false

    val saved: List<DebugLogEntryDTO> get() = synchronized(lock) { entries }
    val parkedSummaries: Int get() = synchronized(lock) { parked.size }
    val failures: Int get() = synchronized(lock) { failureCount }

    override suspend fun saveDebugLogEntries(dtos: SnapshotList<DebugLogEntryDTO>) {
        val isSummary = dtos.size == 1 && dtos[0].level == DebugLogLevel.WARNING
        if (!isSummary && failBatches) {
            synchronized(lock) { failureCount += 1 }
            throw LogCoreStoreFailure("batch")
        }
        if (isSummary && holdSummaries) {
            val gate = CompletableDeferred<Unit>()
            synchronized(lock) { parked = parked + gate }
            gate.await()
        }
        synchronized(lock) { entries = entries + dtos }
    }

    fun releaseSummaries() {
        val gates = synchronized(lock) { parked.also { parked = emptyList() } }
        gates.forEach { it.complete(Unit) }
    }

    override suspend fun fetchDebugLogEntries(since: Instant, limit: Long): SnapshotList<DebugLogEntryDTO> = SnapshotList.empty()
    override suspend fun countDebugLogEntries(): Long = saved.size.toLong()
    override suspend fun pruneDebugLogEntries(olderThan: Instant, keepCount: Long) = Unit
    override suspend fun clearDebugLogEntries() = Unit
}

private class NativeTestsPruneCountingStore : DebugLogPersisting {
    private val lock = Any()
    private var calls: List<Pair<Instant, Long>> = emptyList()
    @Volatile var failSaves = false

    val prunes: List<Pair<Instant, Long>> get() = synchronized(lock) { calls }

    override suspend fun saveDebugLogEntries(dtos: SnapshotList<DebugLogEntryDTO>) {
        if (failSaves) throw LogCoreStoreFailure("save")
    }

    override suspend fun fetchDebugLogEntries(since: Instant, limit: Long): SnapshotList<DebugLogEntryDTO> = SnapshotList.empty()
    override suspend fun countDebugLogEntries(): Long = 0
    override suspend fun pruneDebugLogEntries(olderThan: Instant, keepCount: Long) {
        synchronized(lock) { calls = calls + (olderThan to keepCount) }
    }
    override suspend fun clearDebugLogEntries() = Unit
}

/** Every save parks on its own gate while [failing]; [releaseOldest] lets the oldest one fail. */
private class NativeTestsSequencedGateStore : DebugLogPersisting {
    private val lock = Any()
    private var entries: List<DebugLogEntryDTO> = emptyList()
    private var gates: List<CompletableDeferred<Unit>> = emptyList()
    @Volatile var failing = true

    val saved: List<DebugLogEntryDTO> get() = synchronized(lock) { entries }
    val parkedCount: Int get() = synchronized(lock) { gates.size }

    override suspend fun saveDebugLogEntries(dtos: SnapshotList<DebugLogEntryDTO>) {
        if (failing) {
            val gate = CompletableDeferred<Unit>()
            synchronized(lock) { gates = gates + gate }
            gate.await()
            throw LogCoreStoreFailure("parked")
        }
        synchronized(lock) { entries = entries + dtos }
    }

    fun releaseOldest() {
        val gate = synchronized(lock) { gates.first().also { gates = gates.drop(1) } }
        gate.complete(Unit)
    }

    override suspend fun fetchDebugLogEntries(since: Instant, limit: Long): SnapshotList<DebugLogEntryDTO> = SnapshotList.empty()
    override suspend fun countDebugLogEntries(): Long = saved.size.toLong()
    override suspend fun pruneDebugLogEntries(olderThan: Instant, keepCount: Long) = Unit
    override suspend fun clearDebugLogEntries() = Unit
}

/** The first save while [block] is set suspends until cancelled. */
private class NativeTestsBlockingStore : DebugLogPersisting {
    private val lock = Any()
    private var entries: List<DebugLogEntryDTO> = emptyList()
    val entered = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    @Volatile var block = true

    val saved: List<DebugLogEntryDTO> get() = synchronized(lock) { entries }

    override suspend fun saveDebugLogEntries(dtos: SnapshotList<DebugLogEntryDTO>) {
        if (block) {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        synchronized(lock) { entries = entries + dtos }
    }

    override suspend fun fetchDebugLogEntries(since: Instant, limit: Long): SnapshotList<DebugLogEntryDTO> = SnapshotList.empty()
    override suspend fun countDebugLogEntries(): Long = saved.size.toLong()
    override suspend fun pruneDebugLogEntries(olderThan: Instant, keepCount: Long) = Unit
    override suspend fun clearDebugLogEntries() = Unit
}
