// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.ingest

import com.meshcoreone.android.core.observer.ObserverRecord
import com.meshcoreone.android.core.observer.geo.GateDecision
import com.meshcoreone.android.core.observer.geo.UploadGate
import com.meshcoreone.android.core.observer.location.LocationRedactor
import com.meshcoreone.android.core.observer.location.PositionResolver
import com.meshcoreone.android.core.observer.upload.TimedRecord
import com.meshcoreone.android.core.observer.upload.UploadPipeline
import java.time.Instant

sealed interface IngestOutcome {
    /** Handed to the pipeline with its position. */
    data object Uploaded : IngestOutcome
    /** Not uploaded and not queued: sharing off, no usable fix, or outside ND/MN. [notification] is the text to show, if any. */
    data class Paused(val notification: String?) : IngestOutcome
    /** Not uploaded and not queued: the position is inside (or too close to) a privacy zone. Nothing from the packet leaves. */
    data object Withheld : IngestOutcome
    /** The privacy filter dropped the record. */
    data object Dropped : IngestOutcome
}

/**
 * The single entry for every filtered record on its way to the broker, in this fixed order:
 *  1. resolve the position at the rx instant ([PositionResolver]);
 *  2. [UploadGate]: sharing off, no usable fix or outside ND/MN -> the record is discarded here, never queued;
 *  3. [LocationRedactor]: inside a privacy zone (or its accuracy circle touches one) -> discarded, nothing uploaded;
 *  4. only then is a [TimedRecord] (always with a position: the server drops records without one) handed to the [pipeline].
 */
class ObserverIngest(
    private val resolver: PositionResolver,
    private val gate: UploadGate,
    private val redactor: LocationRedactor,
    private val pipeline: UploadPipeline,
) {
    suspend fun onRecord(record: ObserverRecord, rxElapsedMs: Long, rxTime: Instant): IngestOutcome {
        if (record is ObserverRecord.Dropped) return IngestOutcome.Dropped
        val position = resolver.resolve(rxElapsedMs, rxTime.toEpochMilli())
        val accepted = when (val decision = gate.admit(position)) {
            is GateDecision.Accept -> decision.location
            is GateDecision.Reject -> return IngestOutcome.Paused(gate.notificationText)
        }
        val location = redactor.redact(accepted) ?: return IngestOutcome.Withheld
        pipeline.offer(TimedRecord(record, rxTime, location))
        return IngestOutcome.Uploaded
    }
}
