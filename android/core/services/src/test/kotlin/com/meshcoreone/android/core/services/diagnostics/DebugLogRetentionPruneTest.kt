// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/DebugLogRetentionPruneTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Adaptation: the Room-backed store lives in core:data, which core:services must not depend on; these cases
// run against a reference store implementing the Swift PersistenceStore prune/fetch algorithm.
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.contracts.domain.DebugLogPersisting
import com.meshcoreone.android.core.contracts.domain.DebugLogRetention
import com.meshcoreone.android.core.model.DebugLogEntryDTO
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Time-based retention with a hard row ceiling for persisted debug logs:
 * the prune keeps entries inside the window, drops entries older than it,
 * and enforces the ceiling even when the window alone would exceed it.
 */
class DebugLogRetentionPruneTest {
    private val now: Instant = LOG_CORE_EPOCH

    @TestFactory
    fun sourceCases(): List<DynamicTest> = listOf(
        logCoreOriginal(SUITE, "prune keeps a recent entry and drops one older than the window") {
            val store = RetentionReferenceStore()
            val recent = entry(age = Duration.ofSeconds(60))
            val stale = entry(age = DebugLogRetention.window.plusSeconds(3600))
            store.saveDebugLogEntries(SnapshotList.of(recent, stale))

            store.pruneDebugLogEntries(
                olderThan = now.minus(DebugLogRetention.window),
                keepCount = DebugLogRetention.MAX_ENTRIES,
            )

            val remaining = store.fetchDebugLogEntries(since = Instant.MIN, limit = 10)
            assertEquals(listOf(recent.id), remaining.map { it.id })
        },
        logCoreOriginal(SUITE, "prune enforces the ceiling when the window alone exceeds it") {
            val store = RetentionReferenceStore()
            // All entries are inside the window; only the ceiling can drop any.
            val entries = (0 until 10).map { entry(age = Duration.ofSeconds(it * 60L)) }
            store.saveDebugLogEntries(entries.snapshot())

            store.pruneDebugLogEntries(olderThan = now.minus(DebugLogRetention.window), keepCount = 4)

            val remaining = store.fetchDebugLogEntries(since = Instant.MIN, limit = 20)
            assertEquals(4, remaining.size)
            // The newest entries survive; the oldest are the ones deleted.
            assertEquals(entries.take(4).map { it.id }.toSet(), remaining.map { it.id }.toSet())
        },
    )

    @TestFactory
    fun nativeCases(): List<DynamicTest> = listOf(
        logCoreNative("an entry exactly at the retention cutoff is kept (strictly-older-than delete)") {
            val store = RetentionReferenceStore()
            val boundary = entry(age = DebugLogRetention.window)
            val older = entry(age = DebugLogRetention.window.plusNanos(1))
            store.saveDebugLogEntries(SnapshotList.of(boundary, older))
            store.pruneDebugLogEntries(olderThan = now.minus(DebugLogRetention.window), keepCount = DebugLogRetention.MAX_ENTRIES)
            assertEquals(listOf(boundary.id), store.fetchDebugLogEntries(Instant.MIN, 10).map { it.id })
        },
        logCoreNative("the buffer's hourly prune drives the store with the 7-day window and 50K ceiling") {
            withLogCoreScope { scope ->
                val clock = LogCoreManualClock()
                val store = RetentionReferenceStore()
                val buffer = DebugLogBuffer(store, scope, clock, LogCoreRecordingSink())
                store.saveDebugLogEntries(SnapshotList.of(entry(age = DebugLogRetention.window.plusSeconds(1))))
                buffer.setLastPruneForTesting(clock.now().minus(DebugLogRetention.pruneInterval))
                val fresh = entry(age = Duration.ZERO)
                buffer.append(fresh)
                buffer.flush()
                assertEquals(listOf(fresh.id), store.fetchDebugLogEntries(Instant.MIN, 10).map { it.id })
            }
        },
    )

    private fun entry(age: Duration): DebugLogEntryDTO = DebugLogEntryDTO.create(
        level = DebugLogLevel.INFO,
        subsystem = "test",
        category = "retention",
        message = "entry aged ${age.seconds}s",
        timestamp = now.minus(age),
    )

    private companion object {
        const val SUITE = "DebugLogRetentionPruneTests"
    }
}

/**
 * Swift `PersistenceStore` debug-log algorithm: delete `timestamp < cutoff`, then delete the
 * `count - keepCount` oldest rows; fetch is `timestamp >= since`, newest first, limited.
 */
private class RetentionReferenceStore : DebugLogPersisting {
    private val lock = Any()
    private var rows: List<DebugLogEntryDTO> = emptyList()

    override suspend fun saveDebugLogEntries(dtos: SnapshotList<DebugLogEntryDTO>) {
        synchronized(lock) { rows = rows + dtos }
    }

    override suspend fun fetchDebugLogEntries(since: Instant, limit: Long): SnapshotList<DebugLogEntryDTO> =
        synchronized(lock) { rows }
            .filter { !it.timestamp.isBefore(since) }
            .sortedByDescending { it.timestamp }
            .take(limit.toInt())
            .snapshot()

    override suspend fun countDebugLogEntries(): Long = synchronized(lock) { rows.size.toLong() }

    override suspend fun pruneDebugLogEntries(olderThan: Instant, keepCount: Long) {
        synchronized(lock) {
            val inWindow = rows.filter { !it.timestamp.isBefore(olderThan) }
            val excess = inWindow.size - keepCount
            rows = if (excess > 0) {
                val oldest = inWindow.sortedBy { it.timestamp }.take(excess.toInt()).map { it.id }.toSet()
                inWindow.filter { it.id !in oldest }
            } else {
                inWindow
            }
        }
    }

    override suspend fun clearDebugLogEntries() {
        synchronized(lock) { rows = emptyList() }
    }
}
