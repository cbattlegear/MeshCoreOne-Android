// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.location

/** Where a fix came from. [wire] is the spec's `provider` value. */
enum class FixProvider(val wire: String) { GPS("gps"), FUSED("fused"), NETWORK("network") }

/**
 * One reading from the phone's location stack, as plain data (no Android types; the adapter converts `Location`).
 * [fixElapsedMs] is on the same monotonic clock as the rx times handed to [PositionResolver] (the adapter uses
 * `elapsedRealtime`, so wall-clock changes cannot reorder fixes).
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val hAccuracyMeters: Double,
    val altitudeMeters: Double? = null,
    val vAccuracyMeters: Double? = null,
    val speedMps: Double? = null,
    val bearingDegrees: Double? = null,
    val fixElapsedMs: Long,
    val provider: FixProvider,
) {
    /** True when coordinates are real numbers inside the valid range and the accuracy is finite and non-negative. */
    val isValid: Boolean
        get() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            hAccuracyMeters.isFinite() && hAccuracyMeters >= 0.0
}
