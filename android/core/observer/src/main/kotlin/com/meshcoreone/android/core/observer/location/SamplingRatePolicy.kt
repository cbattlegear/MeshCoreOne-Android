// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.location

/**
 * How often the adapter should ask for a fix: 1 s while moving, 10 s once the phone has been still (speed under 1 m/s)
 * for 30 s. Pure function of recent fixes; the adapter re-evaluates it after every fix and re-requests updates when the
 * answer changes. An unknown speed counts as moving, so a missing speed never reduces sampling.
 */
object SamplingRatePolicy {
    const val MOVING_INTERVAL_MS = 1_000L
    const val STILL_INTERVAL_MS = 10_000L
    const val STILL_WINDOW_MS = 30_000L
    const val STILL_SPEED_MPS = 1.0

    fun intervalMs(recent: List<LocationFix>, nowElapsedMs: Long): Long {
        val windowStart = nowElapsedMs - STILL_WINDOW_MS
        val ordered = recent.filter { it.fixElapsedMs <= nowElapsedMs }.sortedBy { it.fixElapsedMs }
        // The anchor proves the phone was already still when the window opened; without one we cannot say 30 s passed.
        val anchorIndex = ordered.indexOfLast { it.fixElapsedMs <= windowStart }
        if (anchorIndex < 0) return MOVING_INTERVAL_MS
        val stillThroughout = ordered.drop(anchorIndex).all { (it.speedMps ?: return MOVING_INTERVAL_MS) < STILL_SPEED_MPS }
        return if (stillThroughout) STILL_INTERVAL_MS else MOVING_INTERVAL_MS
    }
}
