// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.health

import com.meshcoreone.android.core.observer.geo.GateDecision
import com.meshcoreone.android.core.observer.geo.UploadGate
import com.meshcoreone.android.core.observer.location.LocationRedactor
import com.meshcoreone.android.core.observer.location.PositionResolver
import com.meshcoreone.android.core.observer.location.PositionResult
import com.meshcoreone.android.core.observer.upload.RegionalPipelines
import java.time.Instant

sealed interface HealthOutcome {
    data class Published(val region: String) : HealthOutcome
    /** Broker did not ack; health is not queued. */
    data class PublishFailed(val region: String) : HealthOutcome
    /** Gate said no (outside ND/MN or no acceptable fix): the node was not even read. */
    data class Paused(val notification: String?) : HealthOutcome
}

/**
 * One health poll, with the same rules as packets: the [UploadGate] decides first (nothing is read or sent when paused),
 * then lat/lon pass the [LocationRedactor] (sharing switch, privacy zones; the pipeline never sees a protected position).
 * When the node link is down ([bleConnected] false) a record with `ble_connected:false` and no stats is still sent, because
 * that is exactly the situation the dashboard wants to see. Use with [HealthPoller.runIfDue].
 */
class HealthPublisher(
    private val source: HealthSource,
    private val resolver: PositionResolver,
    private val gate: UploadGate,
    private val redactor: LocationRedactor,
    private val pipelines: RegionalPipelines,
    private val bleConnected: () -> Boolean,
) {
    suspend fun pollOnce(nowElapsedMs: Long, now: Instant): HealthOutcome {
        val position = resolver.resolve(nowElapsedMs, now.toEpochMilli())
        val region = when (val decision = gate.admit(position)) {
            is GateDecision.Accept -> decision.region
            is GateDecision.Reject -> return HealthOutcome.Paused(gate.notificationText)
        }
        val connected = bleConnected()
        val snapshot = if (connected) source.read().snapshot else HealthSnapshot()
        val location = redactor.redact((position as? PositionResult.Resolved)?.location)
        val record = HealthRecord(now, snapshot, connected, location?.latitude, location?.longitude)
        val acked = pipelines.publishHealth(region, HealthRecordSerializer.toJson(record))
        return if (acked) HealthOutcome.Published(region) else HealthOutcome.PublishFailed(region)
    }
}
