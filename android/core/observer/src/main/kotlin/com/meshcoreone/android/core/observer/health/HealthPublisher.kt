// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.health

import com.meshcoreone.android.core.observer.geo.GateDecision
import com.meshcoreone.android.core.observer.geo.UploadGate
import com.meshcoreone.android.core.observer.location.LocationRedactor
import com.meshcoreone.android.core.observer.location.PositionResolver
import com.meshcoreone.android.core.observer.upload.UploadPipeline
import java.time.Instant

sealed interface HealthOutcome {
    data object Published : HealthOutcome
    /** Broker did not ack; health is not queued. */
    data object PublishFailed : HealthOutcome
    /** Gate said no (sharing off, no usable fix, outside ND/MN): the node was not even read. */
    data class Paused(val notification: String?) : HealthOutcome
    /** Nothing sent: the position is in a privacy zone, or the node gave no `uptime_secs` (the server would drop the sample). */
    data object Skipped : HealthOutcome
}

/**
 * One health poll, with the same rules as packets: the [UploadGate] decides first (nothing is read or sent when paused), then
 * the position passes the [LocationRedactor] (privacy zones). The server drops a sample without a position or `uptime_secs`, so a
 * sample without either is not sent: with the node link down, or on firmware that cannot report core stats, nothing goes to `/rf`.
 * Use with [HealthPoller.runIfDue].
 */
class HealthPublisher(
    private val source: HealthSource,
    private val resolver: PositionResolver,
    private val gate: UploadGate,
    private val redactor: LocationRedactor,
    private val pipeline: UploadPipeline,
    private val bleConnected: () -> Boolean,
    private val stationary: () -> Boolean? = { null },
) {
    suspend fun pollOnce(nowElapsedMs: Long, now: Instant): HealthOutcome {
        val position = resolver.resolve(nowElapsedMs, now.toEpochMilli())
        val accepted = when (val decision = gate.admit(position)) {
            is GateDecision.Accept -> decision.location
            is GateDecision.Reject -> return HealthOutcome.Paused(gate.notificationText)
        }
        val location = redactor.redact(accepted) ?: return HealthOutcome.Skipped
        if (!bleConnected()) return HealthOutcome.Skipped
        val record = HealthRecord.create(now, source.read().snapshot, location, stationary()) ?: return HealthOutcome.Skipped
        return if (pipeline.publishRf(HealthRecordSerializer.toJson(record))) HealthOutcome.Published else HealthOutcome.PublishFailed
    }
}
