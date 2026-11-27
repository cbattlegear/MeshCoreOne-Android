// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import com.meshcoreone.android.core.observer.ObserverRecord
import java.time.Instant

/**
 * Where the phone was when the packet was heard. HOOK: GPS and privacy zones produce this later (a record inside a
 * privacy zone simply gets `location = null`); until then every record has none and the fields are omitted.
 */
data class RecordLocation(val latitude: Double, val longitude: Double, val accuracyMeters: Double? = null)

/** A filtered record plus the time it was received. [rxTime] is fixed at capture and never rewritten on resend. */
class TimedRecord(val record: ObserverRecord, val rxTime: Instant, val location: RecordLocation? = null) {
    override fun toString(): String = "TimedRecord($record, rxTime=$rxTime)"
}
