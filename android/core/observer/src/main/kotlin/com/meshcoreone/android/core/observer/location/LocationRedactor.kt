// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.location

import com.meshcoreone.android.core.observer.upload.RecordLocation
import kotlinx.coroutines.CancellationException

/** The user's "share my location" switch. Read per record. */
fun interface LocationSharing { fun isEnabled(): Boolean }

/**
 * The only door a position passes through before it can be attached to a record. Returns null (the record then has no
 * location field at all, including h_acc, alt, v_acc, speed, bearing) when sharing is off or the position is inside, or
 * within its accuracy of, any privacy zone. Fails closed: if reading the setting or the zones throws, nothing is shared.
 */
class LocationRedactor(private val sharing: LocationSharing, private val zoneSource: PrivacyZoneSource) {
    fun redact(location: RecordLocation?): RecordLocation? {
        if (location == null) return null
        return try {
            if (!sharing.isEnabled()) return null
            if (PrivacyZones(zoneSource.zones()).covers(location.latitude, location.longitude, location.hAccuracyMeters)) null else location
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}
