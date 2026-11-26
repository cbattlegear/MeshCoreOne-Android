// PortedFrom: MC1Services/Sources/MC1Services/Services/DebugLogBuffer.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.contracts.domain.DebugLogPersisting
import com.meshcoreone.android.core.contracts.domain.DebugLogRetention
import com.meshcoreone.android.core.model.DebugLogEntryDTO
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.snapshot
import java.time.Duration as JavaDuration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/**
 * Buffers debug log entries and flushes them to persistence in batches, with backpressure.
 *
 * Swift actor semantics are reproduced as a confined class: every state transition happens under
 * [lock] and no lock is held across a suspension point, so — exactly like a reentrant Swift actor —
 * two flushes may be suspended in `saveDebugLogEntries` at once. That reentrancy is why the
 * drop-summary guard and the snapshot-then-subtract drop count exist.
 *
 * Swift's unstructured `Task`s become coroutines launched in the injected [scope] (it must use a
 * real dispatcher, not `Dispatchers.Unconfined`; a serial one such as `limitedParallelism(1)`
 * mirrors the Swift actor executor so launched flushes start in launch order). Cancelling [scope]
 * cancels the pending timer and
 * in-flight flushes; a flush interrupted by cancellation puts its batch back at the front of the
 * buffer and rethrows, so a later [flush] or [shutdown] can still persist it.
 */
class DebugLogBuffer(
    private val dataStore: DebugLogPersisting,
    private val scope: CoroutineScope,
    private val clock: DebugLogClock = SystemDebugLogClock,
    private val platformSink: DebugLogPlatformSink = JavaUtilLoggingSink,
) : DebugLogFlushing {
    private val lock = Any()
    private var buffer: List<DebugLogEntryDTO> = emptyList()
    private var flushJob: Job? = null
    private var isFlushScheduled = false

    /**
     * Seeded to now so the first successful flush after connect does not
     * repeat the connect-time `pruneDebugLogEntries` on this store.
     */
    private var lastPrune: Instant = clock.now()

    /**
     * Entries lost since the last successful save, to a save failure or the requeue
     * cap. Reported as a synthesized log entry on the next successful save, since
     * those windows are otherwise invisible in the persisted log itself.
     */
    private var droppedEntryCount = 0L

    /**
     * Guards [persistDropSummaryIfNeeded] against reentrancy: [flushBuffer] runs
     * concurrently with itself via [flushNow]'s launched coroutine, and two flushes
     * succeeding together must not persist duplicate summaries.
     */
    private var isPersistingDropSummary = false

    /** Adds [entry]; a full buffer flushes immediately, otherwise a flush is scheduled after [FLUSH_INTERVAL]. */
    fun append(entry: DebugLogEntryDTO) {
        val job = synchronized(lock) {
            if (!scope.isActive) {
                // No timer or flush can run any more; only an explicit flush()/shutdown() will. Stay bounded
                // (the requeue cap) and report the overflow through the next drop summary.
                val next = buffer + entry
                val overflow = next.size - MAX_BUFFER_SIZE * 2
                buffer = if (overflow > 0) next.drop(overflow) else next
                if (overflow > 0) droppedEntryCount += overflow.toLong()
                return
            }
            buffer = buffer + entry
            if (buffer.size >= MAX_BUFFER_SIZE) flushNowLocked() else scheduleFlushLocked()
        }
        job?.start()
    }

    override suspend fun flush() {
        synchronized(lock) { cancelScheduledFlushLocked() }
        flushBuffer()
    }

    suspend fun shutdown() {
        synchronized(lock) { cancelScheduledFlushLocked() }
        flushBuffer()
    }

    internal fun setLastPruneForTesting(date: Instant) {
        synchronized(lock) { lastPrune = date }
    }

    internal val bufferedCountForTesting: Int get() = synchronized(lock) { buffer.size }

    internal val droppedEntryCountForTesting: Long get() = synchronized(lock) { droppedEntryCount }

    private fun cancelScheduledFlushLocked() {
        flushJob?.cancel()
        flushJob = null
        isFlushScheduled = false
    }

    /** Returns the newly created (lazy) timer job for the caller to start outside [lock]. */
    private fun scheduleFlushLocked(): Job? {
        if (isFlushScheduled) return null
        isFlushScheduled = true
        val job = scope.launch(start = CoroutineStart.LAZY) { runScheduledFlush() }
        flushJob = job
        return job
    }

    private suspend fun runScheduledFlush() {
        clock.sleep(FLUSH_INTERVAL)
        val self = currentCoroutineContext()[Job]
        val proceed = synchronized(lock) {
            // Swift `guard !Task.isCancelled`, checked atomically with the cancel in flush()/flushNow().
            if (self?.isActive == false) return@synchronized false
            isFlushScheduled = false
            // Past the guard Swift's cancel can no longer stop this flush; detach it so a later
            // flush()/flushNow() cancel cannot abort its in-flight save either.
            if (flushJob === self) flushJob = null
            true
        }
        if (proceed) flushBuffer()
    }

    /** Returns the (lazy) immediate-flush job for the caller to start outside [lock]. */
    private fun flushNowLocked(): Job {
        cancelScheduledFlushLocked()
        return scope.launch(start = CoroutineStart.LAZY) { flushBuffer() }
    }

    private suspend fun flushBuffer() {
        val entries = synchronized(lock) { buffer.also { buffer = emptyList() } }
        if (entries.isEmpty()) return

        try {
            dataStore.saveDebugLogEntries(entries.snapshot())
        } catch (cancellation: CancellationException) {
            synchronized(lock) { buffer = entries + buffer }
            throw cancellation
        } catch (error: Exception) {
            handleSaveFailure(entries, error)
            return
        }
        persistDropSummaryIfNeeded()
        pruneIfDue()
    }

    private fun handleSaveFailure(entries: List<DebugLogEntryDTO>, error: Exception) {
        logError("Failed to save debug logs: ${describe(error)}")

        // Backpressure: only re-queue if total won't exceed limit
        val entriesToRequeue = entries.take(MAX_BUFFER_SIZE)
        synchronized(lock) {
            if (buffer.size + entriesToRequeue.size < MAX_BUFFER_SIZE * 2) {
                buffer = entriesToRequeue + buffer
                droppedEntryCount += (entries.size - entriesToRequeue.size).toLong()
            } else {
                droppedEntryCount += entries.size.toLong()
            }
        }
    }

    private suspend fun pruneIfDue() {
        val due = synchronized(lock) {
            JavaDuration.between(lastPrune, clock.now()) >= DebugLogRetention.pruneInterval
        }
        if (!due) return
        try {
            dataStore.pruneDebugLogEntries(
                olderThan = clock.now().minus(DebugLogRetention.window),
                keepCount = DebugLogRetention.MAX_ENTRIES,
            )
            val prunedAt = clock.now()
            synchronized(lock) { lastPrune = prunedAt }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logError("Failed to prune debug logs: ${describe(error)}")
        }
    }

    /**
     * Persists a summary of entries dropped since the last successful save. Left
     * uncounted on failure so the loss carries forward to the next attempt instead
     * of being silently reset. Snapshots the count before the save because flushes
     * interleave across that suspension: drops recorded while the save is
     * suspended must survive, so only the reported amount is subtracted on success.
     */
    private suspend fun persistDropSummaryIfNeeded() {
        val reported = synchronized(lock) {
            if (droppedEntryCount <= 0 || isPersistingDropSummary) return
            isPersistingDropSummary = true
            droppedEntryCount
        }
        try {
            val summary = DebugLogEntryDTO.create(
                level = DebugLogLevel.WARNING,
                subsystem = LOG_SUBSYSTEM,
                category = LOG_CATEGORY,
                message = "Lost $reported log entries due to prior save failures",
                timestamp = clock.now(),
            )
            try {
                dataStore.saveDebugLogEntries(listOf(summary).snapshot())
                synchronized(lock) { droppedEntryCount -= reported }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                logError("Failed to persist dropped-entry summary: ${describe(error)}")
            }
        } finally {
            synchronized(lock) { isPersistingDropSummary = false }
        }
    }

    private fun logError(message: String) {
        platformSink.write(DebugLogLevel.ERROR, LOG_SUBSYSTEM, LOG_CATEGORY, message)
    }

    /** Stand-in for Swift `error.localizedDescription`. */
    private fun describe(error: Exception): String = error.localizedMessage ?: error.toString()

    companion object {
        const val MAX_BUFFER_SIZE = 50
        const val MAX_PENDING_ENTRIES = 500
        val FLUSH_INTERVAL: Duration = 5.seconds
        internal const val LOG_SUBSYSTEM = "com.mc1"
        internal const val LOG_CATEGORY = "DebugLogBuffer"

        /**
         * The current shared buffer and the entries recorded while none exists, behind
         * one lock so [record] and the [shared] setter are atomic with respect to each
         * other: an entry either reaches the current buffer or waits in `pending` for
         * the next assignment to drain; it can't fall between the two.
         */
        private val sharedLock = Any()
        private var sharedBuffer: DebugLogBuffer? = null
        private var pending: List<DebugLogEntryDTO> = emptyList()

        /**
         * Shared buffer instance for app-wide logging, reassigned on every connection while
         * [PersistentLogger] records from arbitrary threads. Assigning a buffer atomically takes
         * every entry recorded while none existed (early launch, background relaunch, between
         * connections) and delivers them to the new buffer in record order.
         *
         * Delivery happens synchronously under the hub lock (Swift hops through one `Task`), so
         * drained entries are guaranteed to precede any entry recorded after the assignment.
         */
        var shared: DebugLogBuffer?
            get() = synchronized(sharedLock) { sharedBuffer }
            set(value) {
                synchronized(sharedLock) {
                    sharedBuffer = value
                    if (value == null) return
                    val drained = pending
                    pending = emptyList()
                    drained.forEach(value::append)
                }
            }

        /**
         * Single entry point for app-wide log delivery: hands [entry] to the current
         * shared buffer, or queues it (bounded, oldest dropped first) until one is
         * assigned.
         */
        fun record(entry: DebugLogEntryDTO) {
            synchronized(sharedLock) {
                val buffer = sharedBuffer
                if (buffer != null) {
                    buffer.append(entry)
                } else {
                    pending = (pending + entry).takeLast(MAX_PENDING_ENTRIES)
                }
            }
        }

        /** Empties the pending queue so tests can exercise the no-buffer window from a known state. */
        internal fun resetPendingStateForTesting() {
            synchronized(sharedLock) { pending = emptyList() }
        }

        internal val pendingCountForTesting: Int get() = synchronized(sharedLock) { pending.size }
    }
}
