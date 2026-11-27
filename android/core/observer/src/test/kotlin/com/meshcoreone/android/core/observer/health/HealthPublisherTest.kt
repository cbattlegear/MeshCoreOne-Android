// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.health

import com.meshcoreone.android.core.observer.geo.UploadGate
import com.meshcoreone.android.core.observer.geo.testFence
import com.meshcoreone.android.core.observer.location.FixProvider
import com.meshcoreone.android.core.observer.location.LocationFix
import com.meshcoreone.android.core.observer.location.LocationRedactor
import com.meshcoreone.android.core.observer.location.LocationSharing
import com.meshcoreone.android.core.observer.location.PositionResolver
import com.meshcoreone.android.core.observer.location.PrivacyZone
import com.meshcoreone.android.core.observer.location.PrivacyZoneSource
import com.meshcoreone.android.core.observer.upload.FakePublisher
import com.meshcoreone.android.core.observer.upload.InMemoryUploadQueueRepository
import com.meshcoreone.android.core.observer.upload.MutableUploadClock
import com.meshcoreone.android.core.observer.upload.ObserverTopics
import com.meshcoreone.android.core.observer.upload.TEST_ORIGIN
import com.meshcoreone.android.core.observer.upload.UploadPipeline
import com.meshcoreone.android.core.observer.upload.testNodeKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class HealthPublisherTest {
    private val clock = MutableUploadClock()
    private val publisher = FakePublisher()
    private val repository = InMemoryUploadQueueRepository()
    private val pipeline = UploadPipeline(publisher, repository, ObserverTopics(testNodeKey), TEST_ORIGIN, clock)
    private val resolver = PositionResolver()
    private var sharing = true
    private var zones = emptyList<PrivacyZone>()
    private var connected = true
    private var reads = 0
    private var uptime: Long? = 10
    private val source = HealthSource { reads++; HealthReading(HealthSnapshot(batteryMillivolts = 3_987, uptimeSeconds = uptime), emptyList()) }
    private val switch = LocationSharing { sharing }
    private val health = HealthPublisher(
        source, resolver, UploadGate(testFence(), switch), LocationRedactor(switch, PrivacyZoneSource { zones }), pipeline,
        { connected }, { true },
    )

    private fun fix(atMs: Long, lat: Double, lon: Double) =
        resolver.add(LocationFix(lat, lon, 5.0, fixElapsedMs = atMs, provider = FixProvider.GPS))

    private fun poll(atMs: Long = 1_000): HealthOutcome = runBlocking { health.pollOnce(atMs, clock.now()) }

    @Test fun publishesOnTheRfTopicQos1NotRetainedWithGps() {
        fix(1_000, 46.5, -97.5)
        assertEquals(HealthOutcome.Published, poll())
        val message = publisher.acked.single()
        assertEquals("meshcore/client/${testNodeKey.hexString}/rf", message.topic)
        assertEquals(1, message.qos); assertFalse(message.retained)
        assertEquals(
            "{\"timestamp\":\"2026-11-27T12:00:00.000Z\",\"gps\":{\"lat\":46.500000,\"lon\":-97.500000,\"acc_m\":5.0}," +
                "\"stationary\":true,\"uptime_secs\":10,\"battery_mv\":3987}",
            message.payload,
        )
    }

    @Test fun outsideTheGeofenceNothingIsReadOrPublished() {
        fix(1_000, 40.0, -97.5)
        assertIs<HealthOutcome.Paused>(poll())
        assertTrue(publisher.attempts.isEmpty()); assertEquals(0, reads)
    }

    @Test fun noFixMeansNoSampleBecauseTheServerNeedsAPosition() {
        fix(0, 46.5, -97.5)
        assertIs<HealthOutcome.Paused>(poll(5 * 60_000L))
        assertTrue(publisher.attempts.isEmpty()); assertEquals(0, reads)
    }

    @Test fun insideAPrivacyZoneNothingIsSent() {
        zones = listOf(PrivacyZone(46.5, -97.5)); fix(1_000, 46.5003, -97.5003)
        assertEquals(HealthOutcome.Skipped, poll())
        assertTrue(publisher.attempts.isEmpty())
    }

    @Test fun sharingOffSendsNothingAndDoesNotReadTheNode() {
        sharing = false; fix(1_000, 46.5, -97.5)
        val outcome = assertIs<HealthOutcome.Paused>(poll())
        assertEquals("Upload paused: location is off", outcome.notification)
        assertTrue(publisher.attempts.isEmpty()); assertEquals(0, reads)
    }

    @Test fun whenTheNodeIsNotConnectedNothingIsSentBecauseThereIsNoUptime() {
        connected = false; fix(1_000, 46.5, -97.5)
        assertEquals(HealthOutcome.Skipped, poll())
        assertEquals(0, reads); assertTrue(publisher.attempts.isEmpty())
    }

    @Test fun withoutUptimeFromTheNodeNothingIsSent() {
        uptime = null; fix(1_000, 46.5, -97.5)
        assertEquals(HealthOutcome.Skipped, poll())
        assertEquals(1, reads); assertTrue(publisher.attempts.isEmpty())
    }

    @Test fun aFailedPublishIsReportedAndNeverQueued() {
        fix(1_000, 46.5, -97.5); publisher.connected = false
        assertEquals(HealthOutcome.PublishFailed, poll())
        assertEquals(0, runBlocking { pipeline.queuedCount() }); assertEquals(0, runBlocking { pipeline.pendingCount() })
    }
}
