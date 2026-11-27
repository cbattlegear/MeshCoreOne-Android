// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.health

import com.meshcoreone.android.core.observer.upload.UploadClock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * Pure scheduler for health polls: due immediately the first time, then [interval] (5 minutes) after each attempt. A long gap
 * (phone asleep) yields one poll, not one per missed slot. A poll that throws still counts as an attempt, so a broken node
 * link is retried at the normal pace rather than in a tight loop.
 * Driving it: call [runIfDue] from the service timer; use [nextDueAt] to schedule the next wake-up.
 */
class HealthPoller(private val clock: UploadClock, private val interval: Duration = DEFAULT_INTERVAL) {
    private var nextDue: Instant? = null

    init { require(!interval.isZero && !interval.isNegative) { "interval must be positive" } }

    @Synchronized fun isDue(): Boolean = nextDue.let { it == null || !clock.now().isBefore(it) }

    /** When the next poll is due; "now" if none has run yet. */
    @Synchronized fun nextDueAt(): Instant = nextDue ?: clock.now()

    /** Forget the schedule (for example after a reconnect) so the next [runIfDue] polls at once. */
    @Synchronized fun reset() { nextDue = null }

    /** Runs [poll] when due. Returns true only if it ran and completed; failures are absorbed (cancellation is not). */
    suspend fun runIfDue(poll: suspend () -> Unit): Boolean {
        if (!isDue()) return false
        markAttempted()
        return try {
            poll(); true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized private fun markAttempted() { nextDue = clock.now().plus(interval) }

    companion object { val DEFAULT_INTERVAL: Duration = Duration.ofMinutes(5) }
}
