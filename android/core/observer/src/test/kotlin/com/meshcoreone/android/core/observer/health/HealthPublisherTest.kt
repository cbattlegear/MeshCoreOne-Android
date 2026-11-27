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
import com.meshcoreone.android.core.observer.upload.RegionalPipelines
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
    private val pipelines = RegionalPipelines { region ->
        UploadPipeline(publisher, repository, ObserverTopics(region, testNodeKey), "s", clock)
    }
    private val resolver = PositionResolver()
    private var sharing = true
    private var zones = emptyList<PrivacyZone>()
    private var connected = true
    private var reads = 0
    private val source = HealthSource { reads++; HealthReading(HealthSnapshot(batteryMillivolts = 3_987, uptimeSeconds = 10), emptyList()) }
    private val health = HealthPublisher(
        source, resolver, UploadGate(testFence()),
        LocationRedactor(LocationSharing { sharing }, PrivacyZoneSource { zones }), pipelines, { connected },
    )

    private fun fix(atMs: Long, lat: Double, lon: Double) =
        resolver.add(LocationFix(lat, lon, 5.0, fixElapsedMs = atMs, provider = FixProvider.GPS))

    private fun poll(atMs: Long = 1_000): HealthOutcome = runBlocking { health.pollOnce(atMs, clock.now()) }

    @Test fun publishesOnTheHealthTopicQos1NotRetainedWithLocation() {
        fix(1_000, 46.5, -97.5)
        assertEquals(HealthOutcome.Published("nd"), poll())
        val message = publisher.acked.single()
        assertEquals("meshcore/nd/mobile/${testNodeKey.hexString}/health", message.topic)
        assertEquals(1, message.qos); assertFalse(message.retained)
        assertTrue(message.payload.startsWith("{\"ts\":\"2026-11-27T12:00:00Z\",\"batt_mv\":3987,\"uptime_s\":10,\"ble_connected\":true,\"lat\":46.500000,\"lon\":-97.500000}"), message.payload)
    }

    @Test fun usesTheMnTopicInMinnesota() {
        fix(1_000, 46.5, -96.5)
        assertEquals(HealthOutcome.Published("mn"), poll())
        assertTrue(publisher.acked.single().topic.startsWith("meshcore/mn/"))
    }

    @Test fun outsideTheGeofenceNothingIsReadOrPublished() {
        fix(1_000, 40.0, -97.5)
        assertIs<HealthOutcome.Paused>(poll())
        assertTrue(publisher.attempts.isEmpty()); assertEquals(0, reads)
    }

    @Test fun noFixAndStaleLastFixIsNotPublishedButRecentInsideLastFixIs() {
        fix(0, 46.5, -97.5)
        assertEquals(HealthOutcome.Published("nd"), poll(5 * 60_000L))
        assertFalse(publisher.acked.single().payload.contains("\"lat\""))
        assertIs<HealthOutcome.Paused>(poll(11 * 60_000L))
        assertEquals(1, publisher.attempts.size)
    }

    @Test fun insideAPrivacyZoneTheHealthRecordHasNoLatLon() {
        zones = listOf(PrivacyZone(46.5, -97.5)); fix(1_000, 46.5003, -97.5003)
        assertEquals(HealthOutcome.Published("nd"), poll())
        val payload = publisher.acked.single().payload
        for (leak in listOf("lat", "lon", "46.5", "97.5")) assertFalse(payload.contains(leak), "leaked $leak: $payload")
        assertTrue(payload.contains("batt_mv"))
    }

    @Test fun sharingOffRemovesLatLon() {
        sharing = false; fix(1_000, 46.5, -97.5)
        poll()
        assertFalse(publisher.acked.single().payload.contains("\"lat\""))
    }

    @Test fun whenTheNodeIsNotConnectedItReportsBleDisconnectedWithoutReadingTheNode() {
        connected = false; fix(1_000, 46.5, -97.5)
        assertEquals(HealthOutcome.Published("nd"), poll())
        assertEquals(0, reads)
        assertTrue(publisher.acked.single().payload.contains("\"ble_connected\":false"))
        assertFalse(publisher.acked.single().payload.contains("batt_mv"))
    }

    @Test fun aFailedPublishIsReportedAndNeverQueued() {
        fix(1_000, 46.5, -97.5); publisher.connected = false
        assertEquals(HealthOutcome.PublishFailed("nd"), poll())
        assertEquals(0, runBlocking { pipelines.queuedCount() }); assertEquals(0, runBlocking { pipelines.pendingCount() })
    }
}
