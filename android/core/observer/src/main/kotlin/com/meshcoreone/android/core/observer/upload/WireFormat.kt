// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import com.meshcoreone.android.core.observer.json.JsonObjectBuilder
import com.meshcoreone.android.core.observer.json.jsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Wire pieces shared by packet and rf messages (Prizm server contract). */
internal object WireFormat {
    const val COORDINATE_DECIMALS = 6

    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** UTC RFC 3339 with exactly three fraction digits, e.g. `2026-11-27T18:04:05.123Z`. */
    fun timestamp(instant: Instant): String = TIMESTAMP.format(instant)

    /** Packet `gps` object: lat, lon, acc_m, alt_m, v_acc_m, speed_mps, bearing_deg, fix_age_ms, provider, interpolated. */
    fun packetGps(location: RecordLocation): String = jsonObject {
        coordinates(location)
        optDouble("alt_m", location.altitudeMeters)
        optDouble("v_acc_m", location.vAccuracyMeters)
        optDouble("speed_mps", location.speedMps)
        optDouble("bearing_deg", location.bearingDegrees)
        optLong("fix_age_ms", location.fixAgeMs)
        optString("provider", location.provider)
        bool("interpolated", location.interpolated)
    }

    /** rf `gps` object: lat, lon, acc_m only. */
    fun rfGps(location: RecordLocation): String = jsonObject { coordinates(location) }

    private fun JsonObjectBuilder.coordinates(location: RecordLocation) {
        fixed("lat", location.latitude, COORDINATE_DECIMALS)
        fixed("lon", location.longitude, COORDINATE_DECIMALS)
        double("acc_m", location.hAccuracyMeters)
    }
}
