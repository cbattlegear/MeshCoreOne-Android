// PortedFrom: MC1Services/Tests/MC1ServicesTests/Mocks/MockPersistenceStore.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: debug-log slice of the mock store plus deterministic clock/sink/runner helpers for WP-212 logging tests.
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.contracts.domain.DebugLogPersisting
import com.meshcoreone.android.core.model.DebugLogEntryDTO
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.test.fail
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.DynamicTest

internal val LOG_CORE_EPOCH: Instant = Instant.ofEpochSecond(1_704_067_200)
private val LOG_CORE_TEST_TIMEOUT: Duration = 20.seconds
private val LOG_CORE_POLL_INTERVAL: Duration = 5.milliseconds
private const val LOG_CORE_POLL_ATTEMPTS = 2_000

/** Source test: display name `Suite::original name()`, run under a hard timeout so a hang fails fast. */
internal fun logCoreOriginal(suite: String, name: String, body: suspend CoroutineScope.() -> Unit): DynamicTest =
    DynamicTest.dynamicTest("$suite::$name()") { logCoreRun(body) }

/** Android-specific regression: display name `WP-212::description`. */
internal fun logCoreNative(name: String, body: suspend CoroutineScope.() -> Unit): DynamicTest =
    DynamicTest.dynamicTest("WP-212::$name") { logCoreRun(body) }

private fun logCoreRun(body: suspend CoroutineScope.() -> Unit) {
    runBlocking { withTimeout(LOG_CORE_TEST_TIMEOUT) { body() } }
}

/** Polls [condition] (bounded); returns whether it became true. Absorbs hops onto launched flush coroutines. */
internal suspend fun logCorePoll(condition: suspend () -> Boolean): Boolean {
    repeat(LOG_CORE_POLL_ATTEMPTS) {
        if (condition()) return true
        delay(LOG_CORE_POLL_INTERVAL)
    }
    return condition()
}

/** Like [logCorePoll] but fails the test when [condition] never holds (Swift `waitUntil`). */
internal suspend fun logCoreAwait(description: String, condition: suspend () -> Boolean) {
    if (!logCorePoll(condition)) fail("Timed out waiting: $description")
}

/**
 * Owning scope for a buffer under test; always cancelled afterwards. Serial (parallelism 1) like
 * the Swift actor's executor, so launched flushes start in launch order; suspended flushes still
 * interleave, exactly as actor reentrancy allows.
 */
internal suspend fun <T> withLogCoreScope(body: suspend (CoroutineScope) -> T): T {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    try {
        return body(scope)
    } finally {
        scope.cancel()
    }
}

internal fun logCoreEntry(category: String, message: String, level: DebugLogLevel = DebugLogLevel.INFO, subsystem: String = "test") =
    DebugLogEntryDTO.create(level = level, subsystem = subsystem, category = category, message = message, timestamp = LOG_CORE_EPOCH)

/** Manual wall clock: time only moves through [advance]; sleepers resume once their deadline is reached. */
internal class LogCoreManualClock(start: Instant = LOG_CORE_EPOCH) : DebugLogClock {
    private class Sleeper(val deadline: Instant, val continuation: CancellableContinuation<Unit>)

    private val lock = Any()
    private var current: Instant = start
    private var sleepers: List<Sleeper> = emptyList()

    val sleeperCount: Int get() = synchronized(lock) { sleepers.size }

    override fun now(): Instant = synchronized(lock) { current }

    override suspend fun sleep(duration: Duration) = suspendCancellableCoroutine { continuation ->
        val sleeper = synchronized(lock) {
            Sleeper(current.plusNanos(duration.inWholeNanoseconds), continuation).also { sleepers = sleepers + it }
        }
        continuation.invokeOnCancellation { synchronized(lock) { sleepers = sleepers - sleeper } }
    }

    fun advance(by: java.time.Duration) {
        val due = synchronized(lock) {
            current = current.plus(by)
            val (ready, waiting) = sleepers.partition { !it.deadline.isAfter(current) }
            sleepers = waiting
            ready
        }
        due.forEach { it.continuation.resume(Unit) }
    }
}

internal data class LogCoreSinkLine(val level: DebugLogLevel, val subsystem: String, val category: String, val message: String)

internal class LogCoreRecordingSink : DebugLogPlatformSink {
    private val lock = Any()
    private var recorded: List<LogCoreSinkLine> = emptyList()
    val lines: List<LogCoreSinkLine> get() = synchronized(lock) { recorded }

    override fun write(level: DebugLogLevel, subsystem: String, category: String, message: String) {
        synchronized(lock) { recorded = recorded + LogCoreSinkLine(level, subsystem, category, message) }
    }
}

internal class LogCoreStoreFailure(message: String) : Exception(message)

/** Debug-log slice of Swift `MockPersistenceStore`: saves append; `saveError` fails every save. */
internal class LogCoreMemoryStore : DebugLogPersisting {
    private val lock = Any()
    private var entries: List<DebugLogEntryDTO> = emptyList()
    private var failures = 0
    @Volatile var saveError: Exception? = null

    val debugLogEntries: List<DebugLogEntryDTO> get() = synchronized(lock) { entries }
    val saveFailureCount: Int get() = synchronized(lock) { failures }

    override suspend fun saveDebugLogEntries(dtos: SnapshotList<DebugLogEntryDTO>) {
        synchronized(lock) {
            saveError?.let { error ->
                failures += 1
                throw error
            }
            entries = entries + dtos
        }
    }

    override suspend fun fetchDebugLogEntries(since: Instant, limit: Long): SnapshotList<DebugLogEntryDTO> =
        debugLogEntries.filter { !it.timestamp.isBefore(since) }.sortedBy { it.timestamp }.take(limit.toInt()).snapshot()

    override suspend fun countDebugLogEntries(): Long = debugLogEntries.size.toLong()

    override suspend fun pruneDebugLogEntries(olderThan: Instant, keepCount: Long) = Unit

    override suspend fun clearDebugLogEntries() {
        synchronized(lock) { entries = emptyList() }
    }
}
