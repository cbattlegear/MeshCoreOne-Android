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
import com.meshcoreone.android.core.observer.upload.TEST_ORIGIN
import com.meshcoreone.android.core.observer.upload.rawOf
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
    private val pipeline = UploadPipeline(publisher, repository, ObserverTopics(testNodeKey), TEST_ORIGIN, clock)
    private val resolver = PositionResolver()
    private var sharing = true
    private var zones = emptyList<PrivacyZone>()
    private val sharingSwitch = LocationSharing { sharing }
    private val ingest = ObserverIngest(
        resolver, UploadGate(testFence(), sharingSwitch), LocationRedactor(sharingSwitch, PrivacyZoneSource { zones }), pipeline,
    )

    private fun fix(atMs: Long, lat: Double, lon: Double, acc: Double = 5.0) =
        resolver.add(LocationFix(lat, lon, acc, speedMps = 3.0, fixElapsedMs = atMs, provider = FixProvider.GPS))

    private fun hear(n: Int, atMs: Long): IngestOutcome = runBlocking {
        val outcome = ingest.onRecord(fullRecord(n, clock.now()).record, atMs, clock.now())
        pipeline.flush()
        outcome
    }

    private fun payloads() = publisher.attempts.map { it.topic to it.payload }

    @Test fun insideUploadsOnTheClientTopicWithTheNestedGps() {
        fix(1_000, 46.5, -97.5)
        assertEquals(IngestOutcome.Uploaded, hear(1, 1_000))
        val (topic, payload) = payloads().single()
        assertEquals("meshcore/client/${testNodeKey.hexString}/packets", topic)
        assertTrue(payload.contains("\"gps\":{\"lat\":46.500000,\"lon\":-97.500000"), payload)
    }

    @Test fun theSameTopicIsUsedInMinnesotaAndNorthDakota() {
        fix(1_000, 46.5, -96.5); hear(1, 1_000)
        fix(9_000, 46.5, -97.5); hear(2, 9_000)
        assertEquals(1, payloads().map { it.first }.toSet().size)
    }

    @Test fun aPacketHeardOutsideTheGeofenceNeverReachesThePublisherOrTheQueue() {
        fix(1_000, 40.0, -97.5)
        publisher.connected = false // even with the broker down it must not be queued
        val outcome = assertIs<IngestOutcome.Paused>(hear(1, 1_000))
        assertEquals("Paused: outside ND and MN", outcome.notification)
        assertTrue(publisher.attempts.isEmpty(), "nothing offered to the publisher")
        assertEquals(0, runBlocking { pipeline.queuedCount() }); assertEquals(0, runBlocking { pipeline.pendingCount() })
    }

    @Test fun packetsHeardOutsideAreNotRetroactivelyUploadedWhenTheUserComesBack() {
        fix(1_000, 40.0, -97.5); hear(1, 1_000)
        fix(5_000_000, 46.5, -97.5); hear(2, 5_000_000)
        assertEquals(listOf(rawOf(2)), publisher.deliveries())
    }

    @Test fun insideAPrivacyZoneNothingFromThePacketIsUploadedOrQueued() {
        zones = listOf(PrivacyZone(46.5, -97.5))
        fix(1_000, 46.5005, -97.5005)
        publisher.connected = false
        assertEquals(IngestOutcome.Withheld, hear(1, 1_000))
        assertTrue(publisher.attempts.isEmpty())
        assertEquals(0, runBlocking { pipeline.queuedCount() }); assertEquals(0, runBlocking { pipeline.pendingCount() })
    }

    @Test fun aFixWhoseAccuracyCircleTouchesAZoneIsWithheldToo() {
        zones = listOf(PrivacyZone(46.5, -97.5, 300.0))
        fix(1_000, 46.5 + 0.0045, -97.5, acc = 40.0) // ~500 m away, 40 m accuracy: outside the circle
        assertEquals(IngestOutcome.Uploaded, hear(1, 1_000))
        fix(9_000, 46.5 + 0.0030, -97.5, acc = 40.0) // ~334 m away: 334 - 40 < 300
        assertEquals(IngestOutcome.Withheld, hear(2, 9_000))
        assertEquals(1, publisher.deliveries().size)
    }

    @Test fun sharingOffUploadsNothingAndSaysSo() {
        sharing = false
        fix(1_000, 46.5, -97.5)
        val outcome = assertIs<IngestOutcome.Paused>(hear(1, 1_000))
        assertEquals("Upload paused: location is off", outcome.notification)
        assertTrue(publisher.attempts.isEmpty())
        assertEquals(0, runBlocking { pipeline.queuedCount() })
        sharing = true
        fix(9_000, 46.5, -97.5)
        assertEquals(IngestOutcome.Uploaded, hear(2, 9_000))
    }

    @Test fun noFixIsNotUploadedEvenWithARecentGoodFix() {
        fix(0, 46.5, -96.5)
        val outcome = assertIs<IngestOutcome.Paused>(hear(1, 5 * 60_000L))
        assertEquals("Paused: waiting for a location fix", outcome.notification)
        assertTrue(publisher.attempts.isEmpty())
    }

    @Test fun noFixEverIsNotUploaded() {
        assertIs<IngestOutcome.Paused>(hear(1, 1_000))
        assertTrue(publisher.attempts.isEmpty())
    }

    @Test fun aPoorFixTheServerWouldDropIsNotUploaded() {
        fix(1_000, 46.5, -97.5, acc = 80.0)
        assertIs<IngestOutcome.Paused>(hear(1, 1_000))
        assertTrue(publisher.attempts.isEmpty())
    }

    @Test fun everyUploadedRecordCarriesAGpsObject() {
        fix(1_000, 46.5, -97.5); hear(1, 1_000); hear(2, 1_500)
        val records = publisher.ackedPackets.flatMap { Regex("\"mode\"").findAll(it.payload).toList() }
        assertEquals(2, records.size)
        assertEquals(2, publisher.ackedPackets.sumOf { Regex("\"gps\":\\{").findAll(it.payload).count() })
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
