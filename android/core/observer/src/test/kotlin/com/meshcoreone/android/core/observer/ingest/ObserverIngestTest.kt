// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.ingest

import com.meshcoreone.android.core.observer.ObserverRecord
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
import com.meshcoreone.android.core.observer.upload.fullRecord
import com.meshcoreone.android.core.observer.upload.testNodeKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ObserverIngestTest {
    private val clock = MutableUploadClock()
    private val publisher = FakePublisher()
    private val repository = InMemoryUploadQueueRepository()
    private val pipelines = RegionalPipelines { region ->
        UploadPipeline(publisher, repository, ObserverTopics(region, testNodeKey), "s", clock)
    }
    private val resolver = PositionResolver()
    private var sharing = true
    private var zones = emptyList<PrivacyZone>()
    private val ingest = ObserverIngest(
        resolver, UploadGate(testFence()),
        LocationRedactor(LocationSharing { sharing }, PrivacyZoneSource { zones }), pipelines,
    )

    private fun fix(atMs: Long, lat: Double, lon: Double) =
        resolver.add(LocationFix(lat, lon, 5.0, speedMps = 3.0, fixElapsedMs = atMs, provider = FixProvider.GPS))

    private fun hear(n: Int, atMs: Long): IngestOutcome = runBlocking {
        val outcome = ingest.onRecord(fullRecord(n, clock.now()).record, atMs, clock.now())
        pipelines.flushAll()
        outcome
    }

    private fun payloads() = publisher.attempts.map { it.topic to it.payload }

    @Test fun insideNdUploadsOnTheNdTopicWithLocation() {
        fix(1_000, 46.5, -97.5)
        val outcome = assertIs<IngestOutcome.Uploaded>(hear(1, 1_000))
        assertEquals("nd", outcome.region); assertTrue(outcome.withLocation)
        val (topic, payload) = payloads().single()
        assertTrue(topic.startsWith("meshcore/nd/mobile/") && topic.endsWith("/packets"))
        assertTrue(payload.contains("\"lat\":46.500000") && payload.contains("\"lon\":-97.500000"))
    }

    @Test fun insideMnUploadsOnTheMnTopic() {
        fix(1_000, 46.5, -96.5)
        assertEquals("mn", assertIs<IngestOutcome.Uploaded>(hear(1, 1_000)).region)
        assertTrue(payloads().single().first.startsWith("meshcore/mn/"))
    }

    @Test fun aPacketHeardOutsideTheGeofenceNeverReachesThePublisherOrTheQueue() {
        fix(1_000, 40.0, -97.5) // Nebraska-ish
        publisher.connected = false // even with the broker down it must not be queued
        val outcome = assertIs<IngestOutcome.Paused>(hear(1, 1_000))
        assertEquals("Paused: outside ND and MN", outcome.notification)
        assertTrue(publisher.attempts.isEmpty(), "nothing offered to the publisher")
        assertEquals(0, runBlocking { pipelines.queuedCount() }); assertEquals(0, runBlocking { pipelines.pendingCount() })
    }

    @Test fun packetsHeardOutsideAreNotRetroactivelyUploadedWhenTheUserComesBack() {
        fix(1_000, 40.0, -97.5); hear(1, 1_000)
        fix(5_000_000, 46.5, -97.5); hear(2, 5_000_000)
        val hashes = publisher.deliveries().map { it.first }
        assertEquals(listOf(com.meshcoreone.android.core.observer.upload.hashOf(2)), hashes)
    }

    @Test fun insideAPrivacyZoneThePacketUploadsButWithoutAnyLocationField() {
        zones = listOf(PrivacyZone(46.5, -97.5))
        fix(1_000, 46.5005, -97.5005)
        val outcome = assertIs<IngestOutcome.Uploaded>(hear(1, 1_000))
        assertFalse(outcome.withLocation)
        val out = payloads().joinToString { it.second }
        assertTrue(out.contains("\"hash\""))
        for (leak in listOf("46.5", "97.5", "\"lat\"", "\"lon\"", "h_acc", "speed", "provider", "interpolated", "fix_age_ms")) {
            assertFalse(out.contains(leak), "leaked $leak: $out")
        }
    }

    @Test fun sharingOffUploadsWithoutLocationButStillEnforcesTheGeofence() {
        sharing = false
        fix(1_000, 46.5, -97.5)
        assertFalse(assertIs<IngestOutcome.Uploaded>(hear(1, 1_000)).withLocation)
        assertFalse(payloads().single().second.contains("\"lat\""))
        fix(9_000_000, 40.0, -97.5)
        assertIs<IngestOutcome.Paused>(hear(2, 9_000_000)) // still paused outside, location not shared but still used locally
    }

    @Test fun noFixButARecentInsideFixUploadsWithoutLocationUsingTheLastGoodRegion() {
        fix(0, 46.5, -96.5)
        val outcome = assertIs<IngestOutcome.Uploaded>(hear(1, 5 * 60_000L))
        assertEquals("mn", outcome.region); assertFalse(outcome.withLocation)
        assertFalse(payloads().single().second.contains("\"lat\""))
    }

    @Test fun noFixAndAStaleLastGoodFixIsNotUploaded() {
        fix(0, 46.5, -96.5)
        assertIs<IngestOutcome.Paused>(hear(1, 11 * 60_000L))
        assertTrue(publisher.attempts.isEmpty())
    }

    @Test fun noFixEverIsNotUploaded() {
        assertIs<IngestOutcome.Paused>(hear(1, 1_000))
        assertTrue(publisher.attempts.isEmpty())
    }

    @Test fun droppedRecordsAreIgnored() {
        fix(1_000, 46.5, -97.5)
        assertEquals(IngestOutcome.Dropped, runBlocking { ingest.onRecord(ObserverRecord.Dropped, 1_000, clock.now()) })
    }

    @Test fun anInterpolatedPositionIsUploadedWithTheInterpolatedFlag() {
        fix(0, 46.5, -97.5); fix(2_000, 46.5, -96.5)
        hear(1, 1_000)
        val payload = payloads().single().second
        assertTrue(payload.contains("\"interpolated\":true"), payload)
    }
}
