// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.location

import com.meshcoreone.android.core.observer.upload.RecordLocation
import kotlin.math.abs

sealed interface PositionResult {
    /** [location] is the record's position, [rxTimeMs] the wall-clock rx instant that was resolved. */
    data class Resolved(val location: RecordLocation, val rxTimeMs: Long) : PositionResult

    /**
     * No fix within the timeout. [lastGood] is the newest fix ever added (kept past the buffer's 2 minutes) and
     * [lastGoodAgeMs] its distance from the rx instant. `UploadGate` decides whether a packet may still be accepted
     * (last good fix at most 10 minutes old and inside ND/MN); this type only reports the facts.
     */
    data class NoFix(val lastGood: LocationFix?, val lastGoodAgeMs: Long?) : PositionResult
}

/**
 * Places a packet in space: takes the fixes just before and after the rx instant; when both exist and are less than
 * [interpolationMaxGapMs] apart it interpolates (lat/lon with antimeridian wrap, alt, speed, bearing the short way round)
 * and marks `interpolated`; otherwise it uses the nearest fix and records `fix_age_ms`. Nothing newer than
 * [noFixTimeoutMs] from the rx instant means no position.
 *
 * Hook: a packet heard just after the newest fix has no "after" fix yet; the adapter should resolve after the next fix
 * arrives (at most one sampling interval) or accept the nearest-before result.
 */
class PositionResolver(
    val buffer: FixBuffer = FixBuffer(),
    private val noFixTimeoutMs: Long = DEFAULT_NO_FIX_TIMEOUT_MS,
    private val interpolationMaxGapMs: Long = DEFAULT_INTERPOLATION_MAX_GAP_MS,
) {
    @Volatile private var lastGood: LocationFix? = null

    fun add(fix: LocationFix): Boolean {
        if (!buffer.add(fix)) return false
        synchronized(this) {
            val current = lastGood
            if (current == null || fix.fixElapsedMs >= current.fixElapsedMs) lastGood = fix
        }
        return true
    }

    fun resolve(rxElapsedMs: Long, rxTimeMs: Long): PositionResult {
        val before = buffer.atOrBefore(rxElapsedMs)
        val after = buffer.atOrAfter(rxElapsedMs)
        val location = when {
            before != null && after != null && before.fixElapsedMs == after.fixElapsedMs -> nearest(before, rxElapsedMs)
            before != null && after != null && after.fixElapsedMs - before.fixElapsedMs < interpolationMaxGapMs ->
                interpolate(before, after, rxElapsedMs)
            else -> listOfNotNull(before, after).minByOrNull { abs(it.fixElapsedMs - rxElapsedMs) }
                ?.takeIf { abs(it.fixElapsedMs - rxElapsedMs) <= noFixTimeoutMs }
                ?.let { nearest(it, rxElapsedMs) }
        }
        if (location != null) return PositionResult.Resolved(location, rxTimeMs)
        val last = lastGood
        return PositionResult.NoFix(last, last?.let { abs(rxElapsedMs - it.fixElapsedMs) })
    }

    private fun nearest(fix: LocationFix, rxElapsedMs: Long) = RecordLocation(
        fix.latitude, fix.longitude, fix.hAccuracyMeters, fix.altitudeMeters, fix.vAccuracyMeters, fix.speedMps,
        fix.bearingDegrees, abs(rxElapsedMs - fix.fixElapsedMs), fix.provider.wire, interpolated = false,
    )

    private fun interpolate(a: LocationFix, b: LocationFix, rxElapsedMs: Long): RecordLocation {
        val t = (rxElapsedMs - a.fixElapsedMs).toDouble() / (b.fixElapsedMs - a.fixElapsedMs)
        val closer = if (rxElapsedMs - a.fixElapsedMs <= b.fixElapsedMs - rxElapsedMs) a else b
        return RecordLocation(
            latitude = lerp(a.latitude, b.latitude, t),
            longitude = normalizeLongitude(a.longitude + shortestDelta(a.longitude, b.longitude, 360.0) * t),
            // Interpolating does not make a position more precise: keep the worse accuracy of the two fixes.
            hAccuracyMeters = maxOf(a.hAccuracyMeters, b.hAccuracyMeters),
            altitudeMeters = both(a.altitudeMeters, b.altitudeMeters) { x, y -> lerp(x, y, t) },
            vAccuracyMeters = both(a.vAccuracyMeters, b.vAccuracyMeters) { x, y -> maxOf(x, y) },
            speedMps = both(a.speedMps, b.speedMps) { x, y -> lerp(x, y, t) },
            bearingDegrees = both(a.bearingDegrees, b.bearingDegrees) { x, y ->
                ((x + shortestDelta(x, y, 360.0) * t) % 360.0 + 360.0) % 360.0
            },
            fixAgeMs = null,
            provider = closer.provider.wire,
            interpolated = true,
        )
    }

    private inline fun both(a: Double?, b: Double?, f: (Double, Double) -> Double): Double? =
        if (a != null && b != null) f(a, b) else null

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    /** Signed shortest difference to go from [from] to [to] on a circle of [period]. */
    private fun shortestDelta(from: Double, to: Double, period: Double): Double {
        val raw = (to - from) % period
        return when {
            raw > period / 2 -> raw - period
            raw < -period / 2 -> raw + period
            else -> raw
        }
    }

    private fun normalizeLongitude(lon: Double): Double = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    companion object {
        const val DEFAULT_NO_FIX_TIMEOUT_MS = 30_000L
        const val DEFAULT_INTERPOLATION_MAX_GAP_MS = 5_000L
    }
}
