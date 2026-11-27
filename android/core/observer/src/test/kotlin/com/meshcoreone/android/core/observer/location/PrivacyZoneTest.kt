// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.location

import com.meshcoreone.android.core.observer.geo.UploadGate
import com.meshcoreone.android.core.observer.geo.testFence
import com.meshcoreone.android.core.observer.ingest.IngestOutcome
import com.meshcoreone.android.core.observer.ingest.ObserverIngest
import com.meshcoreone.android.core.observer.upload.FakePublisher
import com.meshcoreone.android.core.observer.upload.InMemoryUploadQueueRepository
import com.meshcoreone.android.core.observer.upload.MutableUploadClock
import com.meshcoreone.android.core.observer.upload.RecordLocation
import com.meshcoreone.android.core.observer.upload.TEST_ORIGIN
import com.meshcoreone.android.core.observer.upload.UploadPipeline
import com.meshcoreone.android.core.observer.upload.fullRecord
import com.meshcoreone.android.core.observer.upload.testTopics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class GeoMathTest {
    @Test fun oneDegreeOfLatitudeIsAbout111Kilometres() {
        assertEquals(111_195.0, GeoMath.haversineMeters(46.0, -97.0, 47.0, -97.0), 50.0)
    }

    @Test fun longitudeDegreesShrinkWithLatitude() {
        assertEquals(111_195.0 * Math.cos(Math.toRadians(46.0)), GeoMath.haversineMeters(46.0, -97.0, 46.0, -96.0), 100.0)
    }

    @Test fun distanceIsSymmetricZeroForSamePointAndWrapsTheAntimeridian() {
        assertEquals(0.0, GeoMath.haversineMeters(46.0, -97.0, 46.0, -97.0))
        assertEquals(GeoMath.haversineMeters(10.0, 20.0, 11.0, 22.0), GeoMath.haversineMeters(11.0, 22.0, 10.0, 20.0), 1e-6)
        assertEquals(GeoMath.haversineMeters(0.0, 179.5, 0.0, 180.0), GeoMath.haversineMeters(0.0, 179.5, 0.0, -180.0), 1e-6)
        assertTrue(GeoMath.haversineMeters(0.0, 179.9, 0.0, -179.9) < 25_000)
    }
}

class PrivacyZoneTest {
    private val home = PrivacyZone(46.8772, -96.7898)

    @Test fun defaultRadiusIs300Metres() = assertEquals(300.0, home.radiusMeters)

    @Test fun insideAndOutsideByHaversine() {
        assertTrue(PrivacyZones(listOf(home)).covers(46.8772, -96.7898))
        // 0.002 deg lat is about 222 m, 0.004 about 445 m.
        assertTrue(PrivacyZones(listOf(home)).covers(46.8792, -96.7898))
        assertFalse(PrivacyZones(listOf(home)).covers(46.8812, -96.7898))
    }

    @Test fun anyZoneOfSeveralMatches() {
        val zones = PrivacyZones(listOf(home, PrivacyZone(44.98, -93.27, 1_000.0)))
        assertTrue(zones.covers(44.985, -93.27))
        assertFalse(zones.covers(45.5, -93.27))
    }

    @Test fun aPositionWhoseAccuracyCircleTouchesAZoneIsTreatedAsInside() {
        val zones = PrivacyZones(listOf(home))
        // About 445 m away: outside on its own, but a 200 m accuracy circle widens the 300 m zone to 500 m.
        assertFalse(zones.covers(46.8812, -96.7898, 0.0))
        assertTrue(zones.covers(46.8812, -96.7898, 200.0))
    }

    @Test fun anEmptyZoneListCoversNothing() = assertFalse(PrivacyZones(emptyList()).covers(46.0, -97.0))

    @Test fun invalidZonesAreRejected() {
        assertFailsWith<IllegalArgumentException> { PrivacyZone(91.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { PrivacyZone(0.0, 181.0) }
        assertFailsWith<IllegalArgumentException> { PrivacyZone(0.0, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { PrivacyZone(Double.NaN, 0.0) }
    }
}

class LocationRedactorTest {
    private val home = PrivacyZone(46.8772, -96.7898)
    private val location = RecordLocation(
        46.9, -96.8, 4.0, altitudeMeters = 270.0, vAccuracyMeters = 3.0, speedMps = 12.0, bearingDegrees = 80.0,
        fixAgeMs = 100, provider = "gps", interpolated = false,
    )

    private fun redactor(sharing: Boolean = true, zones: List<PrivacyZone> = listOf(home)) =
        LocationRedactor(LocationSharing { sharing }, PrivacyZoneSource { zones })

    @Test fun anOutsideFixPassesThroughUnchangedWithEveryField() = assertEquals(location, redactor().redact(location))

    @Test fun sharingOffDropsEverything() = assertNull(redactor(sharing = false).redact(location))

    @Test fun insideAZoneDropsEverythingNotJustLatLon() {
        val inside = location.copy(latitude = 46.8775, longitude = -96.7899)
        assertNull(redactor().redact(inside))
    }

    @Test fun nullStaysNull() = assertNull(redactor().redact(null))

    @Test fun aBrokenZoneSourceFailsClosed() {
        val broken = LocationRedactor(LocationSharing { true }, PrivacyZoneSource { error("db closed") })
        assertNull(broken.redact(location))
    }

    @Test fun aBrokenSharingSettingFailsClosed() {
        val broken = LocationRedactor(LocationSharing { error("prefs") }, PrivacyZoneSource { emptyList() })
        assertNull(broken.redact(location))
    }

    @Test fun zonesChangeTakesEffectImmediately() {
        var zones = emptyList<PrivacyZone>()
        val r = LocationRedactor(LocationSharing { true }, PrivacyZoneSource { zones })
        val inside = location.copy(latitude = 46.8775, longitude = -96.7899)
        assertNotNull(r.redact(inside))
        zones = listOf(home)
        assertNull(r.redact(inside))
    }
}

/** The server only records packets with a position, so a private place leaves nothing at all: no packet, no queue row, no rf sample. */
class PrivacyZonePipelineTest {
    private val clock = MutableUploadClock()
    private val publisher = FakePublisher()
    private val repository = InMemoryUploadQueueRepository()
    private val pipeline = UploadPipeline(publisher, repository, testTopics(), TEST_ORIGIN, clock)
    private val resolver = PositionResolver()
    private val home = PrivacyZone(46.8772, -96.7898)

    private fun ingest(sharing: Boolean, zones: List<PrivacyZone>): ObserverIngest {
        val switch = LocationSharing { sharing }
        return ObserverIngest(resolver, UploadGate(testFence(), switch), LocationRedactor(switch, PrivacyZoneSource { zones }), pipeline)
    }

    private fun hearAtHome(ingest: ObserverIngest): IngestOutcome = runBlocking {
        resolver.add(LocationFix(46.877345, -96.789712, 3.5, 273.25, 2.5, 0.5, 211.0, 1_000, FixProvider.GPS))
        val outcome = ingest.onRecord(fullRecord(1, clock.now()).record, 1_000, clock.now())
        pipeline.flush()
        outcome
    }

    @Test fun nothingInsideAZoneReachesThePublisherOrTheQueue() {
        publisher.connected = false
        assertEquals(IngestOutcome.Withheld, hearAtHome(ingest(true, listOf(home))))
        assertTrue(publisher.attempts.isEmpty())
        assertEquals(0, runBlocking { pipeline.queuedCount() }); assertEquals(0, runBlocking { pipeline.pendingCount() })
    }

    @Test fun nothingReachesThePublisherWhileSharingIsOff() {
        assertTrue(hearAtHome(ingest(false, emptyList())) is IngestOutcome.Paused)
        assertTrue(publisher.attempts.isEmpty())
    }

    @Test fun outsideZonesWithSharingOnThePositionIsUploaded() {
        assertEquals(IngestOutcome.Uploaded, hearAtHome(ingest(true, emptyList())))
        assertTrue(publisher.attempts.single().payload.contains("\"lat\":46.877345"))
    }
}
