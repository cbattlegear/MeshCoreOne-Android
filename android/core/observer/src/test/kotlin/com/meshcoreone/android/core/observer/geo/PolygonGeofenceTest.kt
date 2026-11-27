// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.geo

import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One degree of latitude in metres (spherical earth, same radius as GeoMath). */
private const val M_PER_DEG = 111_195.0

private fun degLonFor(meters: Double, atLat: Double) = meters / (M_PER_DEG * cos(Math.toRadians(atLat)))

/** "nd" = lon -98..-97, "mn" = lon -97..-96 (they share the edge at lon -97), both lat 46..47. */
private fun twoStates(buffer: Double = 2_000.0) = PolygonGeofence(
    mapOf("nd" to listOf(square(46.0, -98.0, 47.0, -97.0)), "mn" to listOf(square(46.0, -97.0, 47.0, -96.0))),
    buffer,
)

class PolygonGeofenceTest {
    @Test fun pointsInsideAreContained() {
        val fence = twoStates()
        assertTrue(fence.contains(46.5, -97.5)); assertTrue(fence.contains(46.5, -96.5))
    }

    @Test fun pointsWellOutsideAreNot() {
        val fence = twoStates()
        assertFalse(fence.contains(46.5, -99.0)); assertFalse(fence.contains(48.0, -97.0)); assertFalse(fence.contains(45.0, -96.5))
    }

    @Test fun outsideByLessThanTheBufferIsContainedAndByMoreIsNot() {
        val fence = twoStates()
        assertTrue(fence.contains(46.5, -98.0 - degLonFor(1_900.0, 46.5)))
        assertFalse(fence.contains(46.5, -98.0 - degLonFor(2_100.0, 46.5)))
        assertTrue(fence.contains(47.0 + 1_900.0 / M_PER_DEG, -97.5))
        assertFalse(fence.contains(47.0 + 2_100.0 / M_PER_DEG, -97.5))
    }

    @Test fun theBufferAlsoAppliesAtCorners() {
        val fence = twoStates()
        // 1.4 km west and 1.4 km south of the SW corner is 1.98 km diagonally; 1.6 and 1.6 is 2.26 km.
        val lat0 = 46.0
        assertTrue(fence.contains(lat0 - 1_400.0 / M_PER_DEG, -98.0 - degLonFor(1_400.0, lat0)))
        assertFalse(fence.contains(lat0 - 1_600.0 / M_PER_DEG, -98.0 - degLonFor(1_600.0, lat0)))
    }

    @Test fun aPointOnAnEdgeIsContained() {
        assertTrue(twoStates(0.0).contains(46.5, -98.0))
    }

    @Test fun zeroBufferMeansExactlyThePolygon() {
        val fence = twoStates(0.0)
        assertFalse(fence.contains(46.5, -98.0 - degLonFor(100.0, 46.5)))
        assertTrue(fence.contains(46.5, -97.5))
    }

    @Test fun aConcavePolygonDoesNotContainItsNotch() {
        // "U" opening north: the notch (lon -97.6..-97.4, lat 46.5..47) is outside by 0.1 deg = ~8 km.
        val u = GeoPolygon(listOf(
            LatLon(46.0, -98.0), LatLon(46.0, -97.0), LatLon(47.0, -97.0), LatLon(47.0, -97.4),
            LatLon(46.5, -97.4), LatLon(46.5, -97.6), LatLon(47.0, -97.6), LatLon(47.0, -98.0),
        ))
        val fence = PolygonGeofence(mapOf("nd" to listOf(u)), 2_000.0)
        assertTrue(fence.contains(46.2, -97.5)); assertTrue(fence.contains(46.9, -97.9))
        assertFalse(fence.contains(46.9, -97.5))
    }

    @Test fun aRegionMayHaveSeveralPolygons() {
        val fence = PolygonGeofence(mapOf("mn" to listOf(square(0.0, 0.0, 1.0, 1.0), square(10.0, 10.0, 11.0, 11.0))), 0.0)
        assertTrue(fence.contains(10.5, 10.5)); assertTrue(fence.contains(0.5, 0.5)); assertFalse(fence.contains(5.0, 5.0))
    }

    @Test fun signedDistanceIsNegativeInsideAndPositiveOutside() {
        val fence = twoStates()
        val inside = fence.signedDistanceMeters("nd", 46.5, -97.5)!!
        assertTrue(inside < 0 && Math.abs(inside + 0.5 * M_PER_DEG * cos(Math.toRadians(46.5))) < 800, "inside=$inside")
        val outside = fence.signedDistanceMeters("nd", 46.5, -98.0 - degLonFor(1_000.0, 46.5))!!
        assertEquals(1_000.0, outside, 15.0)
        assertNull(fence.signedDistanceMeters("zz", 46.5, -97.5))
    }

    @Test fun invalidPolygonsAndPointsAreRejected() {
        assertFailsWith<IllegalArgumentException> { GeoPolygon(listOf(LatLon(0.0, 0.0), LatLon(1.0, 1.0))) }
        assertFailsWith<IllegalArgumentException> { LatLon(91.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { PolygonGeofence(emptyMap(), -1.0) }
        assertFalse(twoStates().contains(Double.NaN, -97.5))
    }
}
