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

private fun square(south: Double, west: Double, north: Double, east: Double) =
    GeoPolygon(listOf(LatLon(south, west), LatLon(south, east), LatLon(north, east), LatLon(north, west)))

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

    @Test fun regionIsTheContainingStateOnEachSideOfTheSharedBoundary() {
        val fence = twoStates()
        assertEquals("nd", fence.regionFor(46.5, -97.05)); assertEquals("mn", fence.regionFor(46.5, -96.95))
        assertEquals("nd", fence.regionFor(46.5, -97.9)); assertEquals("mn", fence.regionFor(46.5, -96.1))
    }

    @Test fun regionOutsideEverythingButInsideTheBufferIsTheNearerState() {
        val fence = twoStates()
        assertEquals("nd", fence.regionFor(46.5, -98.0 - degLonFor(1_000.0, 46.5)))
        assertEquals("mn", fence.regionFor(46.5, -96.0 + degLonFor(1_000.0, 46.5)))
        assertNull(fence.regionFor(46.5, -99.0))
    }

    @Test fun whenTwoPolygonsOverlapTheDeeperOneWins() {
        val fence = PolygonGeofence(
            mapOf("nd" to listOf(square(0.0, 0.0, 1.0, 1.0)), "mn" to listOf(square(0.0, 0.8, 1.0, 2.0))), 0.0,
        )
        assertEquals("nd", fence.regionFor(0.5, 0.85)); assertEquals("mn", fence.regionFor(0.5, 0.95))
    }

    @Test fun invalidPolygonsAndPointsAreRejected() {
        assertFailsWith<IllegalArgumentException> { GeoPolygon(listOf(LatLon(0.0, 0.0), LatLon(1.0, 1.0))) }
        assertFailsWith<IllegalArgumentException> { LatLon(91.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { PolygonGeofence(emptyMap(), -1.0) }
        assertFalse(twoStates().contains(Double.NaN, -97.5))
    }
}

class GeofenceDataTest {
    @Test fun parsesPolygonsRegionsAndIgnoresCommentsAndBlankLines() {
        val parsed = GeofenceData.parse("# c\n\npolygon nd\n46 -98\n46 -97\n47 -97\n\npolygon nd\n1 1\n1 2\n2 2\n2 1\npolygon mn\n0 0\n0 1\n1 0\n")
        assertEquals(setOf("nd", "mn"), parsed.keys)
        assertEquals(2, parsed.getValue("nd").size); assertEquals(1, parsed.getValue("mn").size)
        assertEquals(LatLon(46.0, -98.0), parsed.getValue("nd")[0].vertices[0])
    }

    @Test fun malformedInputNamesTheLine() {
        for (bad in listOf("46 -98\n", "polygon nd\n46\n", "polygon nd\nabc def\n", "polygon\n", "polygon nd\n46 -98\n")) {
            val error = assertFailsWith<IllegalArgumentException>(bad) { GeofenceData.parse(bad) }
            assertTrue(error.message!!.isNotBlank())
        }
    }
}

class PlaceholderGeofenceTest {
    private val fence = PlaceholderGeofence.load()

    @Test fun cityPointsInsideNdAndMn() {
        for ((name, lat, lon) in listOf(
            Triple("Fargo", 46.8772, -96.7898), Triple("Moorhead", 46.8738, -96.7678),
            Triple("Minneapolis", 44.9778, -93.2650), Triple("Bismarck", 46.8083, -100.7837),
        )) assertTrue(fence.contains(lat, lon), name)
    }

    @Test fun cityPointsOutside() {
        for ((name, lat, lon) in listOf(
            Triple("Sioux Falls", 43.5446, -96.7311), Triple("Billings", 45.7833, -108.5007), Triple("Winnipeg", 49.8951, -97.1384),
        )) assertFalse(fence.contains(lat, lon), name)
    }

    @Test fun regionsOfCitiesFarFromTheSharedBoundary() {
        assertEquals("nd", fence.regionFor(46.8083, -100.7837)); assertEquals("mn", fence.regionFor(44.9778, -93.2650))
        assertEquals("nd", fence.regionFor(48.2325, -101.2963)) // Minot
        assertEquals("mn", fence.regionFor(46.7867, -92.1005)) // Duluth
    }

    @Test fun theResourceIsLabelledAsAPlaceholder() {
        val text = PlaceholderGeofence::class.java.getResourceAsStream(PlaceholderGeofence.RESOURCE_PATH)!!.reader().readText()
        assertTrue(text.contains("PLACEHOLDER") && text.contains("cb_2023_us_state"))
        assertTrue(PlaceholderGeofence::class.java.getResource("/com/meshcoreone/android/core/observer/geo/README-PLACEHOLDER.md") != null)
    }
}
