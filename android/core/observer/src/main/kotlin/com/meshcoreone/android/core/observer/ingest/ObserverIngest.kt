// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.ingest

import com.meshcoreone.android.core.observer.ObserverRecord
import com.meshcoreone.android.core.observer.geo.GateDecision
import com.meshcoreone.android.core.observer.geo.UploadGate
import com.meshcoreone.android.core.observer.location.LocationRedactor
import com.meshcoreone.android.core.observer.location.PositionResolver
import com.meshcoreone.android.core.observer.location.PositionResult
import com.meshcoreone.android.core.observer.upload.RegionalPipelines
import com.meshcoreone.android.core.observer.upload.TimedRecord
import java.time.Instant

sealed interface IngestOutcome {
    /** Handed to the pipeline of [region]; [withLocation] says whether a position was attached. */
    data class Uploaded(val region: String, val withLocation: Boolean) : IngestOutcome
    /** Not uploaded and not queued (outside ND/MN, or no usable fix). [notification] is the text to show, if any. */
    data class Paused(val notification: String?) : IngestOutcome
    /** The privacy filter dropped the record. */
    data object Dropped : IngestOutcome
}

/**
 * The single entry for every filtered record on its way to the broker, in this fixed order:
 *  1. resolve the position at the rx instant ([PositionResolver]);
 *  2. [UploadGate]: outside ND/MN or no acceptable fix -> the record is discarded here, never queued;
 *  3. [LocationRedactor]: sharing off or inside a privacy zone -> the location is dropped entirely;
 *  4. only then is a [TimedRecord] built and handed to the regional [RegionalPipelines].
 * The geofence always uses the real position, even when sharing is off; the position just is not uploaded.
 */
class ObserverIngest(
    private val resolver: PositionResolver,
    private val gate: UploadGate,
    private val redactor: LocationRedactor,
    private val pipelines: RegionalPipelines,
) {
    suspend fun onRecord(record: ObserverRecord, rxElapsedMs: Long, rxTime: Instant): IngestOutcome {
        if (record is ObserverRecord.Dropped) return IngestOutcome.Dropped
        val position = resolver.resolve(rxElapsedMs, rxTime.toEpochMilli())
        val region = when (val decision = gate.admit(position)) {
            is GateDecision.Accept -> decision.region
            is GateDecision.Reject -> return IngestOutcome.Paused(gate.notificationText)
        }
        val location = redactor.redact((position as? PositionResult.Resolved)?.location)
        pipelines.offer(region, TimedRecord(record, rxTime, location))
        return IngestOutcome.Uploaded(region, withLocation = location != null)
    }
}
