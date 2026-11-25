// PortedFrom: MC1/Utilities/FirmwareSuggestedTimeout.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Feature-local mirror of WP-316's flood profile only; trace never uses the zero-hop or discovery helpers.
package com.meshcoreone.android.feature.tools.trace

import java.time.Instant
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** Wall clock and sleeping, injected so tests run on virtual time. */
interface TraceTimeSource {
    fun now(): Instant

    /** Suspends for [duration]; cancellation propagates. */
    suspend fun sleep(duration: Duration)
}

/** Production time source: system clock and coroutine `delay`. */
object SystemTraceTimeSource : TraceTimeSource {
    override fun now(): Instant = Instant.now()
    override suspend fun sleep(duration: Duration) = kotlinx.coroutines.delay(duration)
}

/** Firmware `suggested_timeout_ms` for a flood trace: scaled, graced and clamped. */
object FloodTraceTimeout {
    const val MULTIPLIER = 1.2
    const val MINIMUM_SECONDS = 5.0
    const val DEFAULT_SECONDS = 30.0
    const val MAXIMUM_SECONDS = 60.0
    const val GRACE_SECONDS = 8.0

    /** A missing hint (0) uses the default so the wait does not expire immediately. */
    fun sanitizedSeconds(suggestedTimeoutMs: UInt): Double {
        if (suggestedTimeoutMs == 0u) return DEFAULT_SECONDS
        val candidate = suggestedTimeoutMs.toDouble() / 1000.0 * MULTIPLIER + GRACE_SECONDS
        return minOf(maxOf(candidate, MINIMUM_SECONDS), MAXIMUM_SECONDS)
    }

    fun sanitized(suggestedTimeoutMs: UInt): Duration =
        (sanitizedSeconds(suggestedTimeoutMs) * 1_000_000_000).roundToLong().nanoseconds
}
