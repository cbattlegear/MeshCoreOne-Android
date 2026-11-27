// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.location

import com.meshcoreone.android.core.observer.ObserverRecord
import com.meshcoreone.android.core.observer.upload.FakePublisher
import com.meshcoreone.android.core.observer.upload.InMemoryUploadQueueRepository
import com.meshcoreone.android.core.observer.upload.MutableUploadClock
import com.meshcoreone.android.core.observer.upload.RecordLocation
import com.meshcoreone.android.core.observer.upload.TimedRecord
import com.meshcoreone.android.core.observer.upload.UploadPipeline
import com.meshcoreone.android.core.observer.upload.testTopics
import com.meshcoreone.android.core.protocol.bytes.Bytes
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
        // About 445 m away: outside on its own, but a 200 m accuracy circle reaches 245 m... inside the 300 m zone.
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

/** Mirrors the broker-side check: whatever the publisher saw must not contain anything from a private place. */
class PrivacyZonePipelineTest {
    private val clock = MutableUploadClock()
    private val publisher = FakePublisher()
    private val pipeline = UploadPipeline(publisher, InMemoryUploadQueueRepository(), testTopics(), "s", clock)
    private val home = PrivacyZone(46.8772, -96.7898)
    private val inHome = RecordLocation(
        46.877345, -96.789712, 3.5, altitudeMeters = 273.25, vAccuracyMeters = 2.5, speedMps = 0.5, bearingDegrees = 211.0,
        fixAgeMs = 40, provider = "gps", interpolated = true,
    )

    private fun record(location: RecordLocation?, redactor: LocationRedactor): TimedRecord {
        val r = ObserverRecord.Full(Bytes.fromHex("aabb"), Bytes.fromHex("0102030405060708"), 1.0, -90, null)
        return TimedRecord(r, clock.now(), redactor.redact(location))
    }

    private fun everythingPublished(): String = runBlocking {
        pipeline.flush(); publisher.attempts.joinToString("\n") { it.topic + " " + it.payload }
    }

    @Test fun nothingInsideAZoneReachesThePublisher() {
        val redactor = LocationRedactor(LocationSharing { true }, PrivacyZoneSource { listOf(home) })
        runBlocking { pipeline.offer(record(inHome, redactor)) }
        val out = everythingPublished()
        assertTrue(out.contains("\"hash\""), "the packet itself is still uploaded")
        for (leak in listOf("46.87", "96.789", "273.25", "3.5", "2.5", "211", "\"lat\"", "\"lon\"", "h_acc", "alt", "v_acc", "speed", "bearing", "fix_age_ms", "provider", "interpolated")) {
            assertFalse(out.contains(leak), "leaked $leak in $out")
        }
    }

    @Test fun nothingReachesThePublisherWhileSharingIsOff() {
        val redactor = LocationRedactor(LocationSharing { false }, PrivacyZoneSource { emptyList() })
        runBlocking { pipeline.offer(record(inHome, redactor)) }
        val out = everythingPublished()
        assertTrue(out.contains("\"hash\""))
        for (leak in listOf("\"lat\"", "\"lon\"", "h_acc", "46.87")) assertFalse(out.contains(leak), leak)
    }

    @Test fun outsideZonesWithSharingOnTheLocationIsUploaded() {
        val redactor = LocationRedactor(LocationSharing { true }, PrivacyZoneSource { emptyList() })
        runBlocking { pipeline.offer(record(inHome, redactor)) }
        assertTrue(everythingPublished().contains("\"lat\":46.877345"))
    }
}
