// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import com.meshcoreone.android.core.observer.ObserverRecord
import java.time.Instant

/**
 * Where the phone was when the packet was heard (wire keys gps.lat, lon, acc_m, alt_m, v_acc_m, speed_mps, bearing_deg,
 * fix_age_ms, provider, interpolated). Produced by `PositionResolver` and then passed through `LocationRedactor`, which
 * withholds it for privacy zones and when location sharing is off; a record without a usable location is not uploaded at all.
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

/** A filtered record plus the time it was received and where. [rxTime] is fixed at capture and never rewritten on resend. */
class TimedRecord(val record: ObserverRecord, val rxTime: Instant, val location: RecordLocation) {
    override fun toString(): String = "TimedRecord($record, rxTime=$rxTime)"
}
