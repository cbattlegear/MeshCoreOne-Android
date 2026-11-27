// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import com.meshcoreone.android.core.observer.ObserverRecord
import java.time.Instant

/**
 * Where the phone was when the packet was heard (spec keys lat, lon, h_acc, alt, v_acc, speed, bearing, fix_age_ms,
 * provider, interpolated). Produced by `PositionResolver` and then passed through `LocationRedactor`, which drops it for
 * privacy zones and when location sharing is off; a record without a location simply has `location = null`.
 */
data class RecordLocation(
    val latitude: Double,
    val longitude: Double,
    val hAccuracyMeters: Double,
    val altitudeMeters: Double? = null,
    val vAccuracyMeters: Double? = null,
    val speedMps: Double? = null,
    val bearingDegrees: Double? = null,
    /** Null for interpolated positions (they are exact for the rx instant). */
    val fixAgeMs: Long? = null,
    val provider: String? = null,
    val interpolated: Boolean = false,
)

/** A filtered record plus the time it was received. [rxTime] is fixed at capture and never rewritten on resend. */
class TimedRecord(val record: ObserverRecord, val rxTime: Instant, val location: RecordLocation? = null) {
    override fun toString(): String = "TimedRecord($record, rxTime=$rxTime)"
}
