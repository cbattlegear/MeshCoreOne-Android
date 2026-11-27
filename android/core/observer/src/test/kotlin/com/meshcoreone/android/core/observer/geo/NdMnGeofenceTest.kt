// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NdMnGeofenceTest {
    private val fence = NdMnGeofence.load()

    @Test fun cityPointsInsideNdAndMn() {
        for ((name, lat, lon) in listOf(
            Triple("Fargo", 46.8772, -96.7898), Triple("Moorhead", 46.8738, -96.7678),
            Triple("Minneapolis", 44.9778, -93.2650), Triple("Bismarck", 46.8083, -100.7837),
            Triple("Minot", 48.2325, -101.2963), Triple("Duluth", 46.7867, -92.1005),
        )) assertTrue(fence.contains(lat, lon), name)
    }

    @Test fun cityPointsOutside() {
        for ((name, lat, lon) in listOf(
            Triple("Sioux Falls", 43.5446, -96.7311), Triple("Billings", 45.7833, -108.5007), Triple("Winnipeg", 49.8951, -97.1384),
            Triple("Null Island", 0.0, 0.0),
        )) assertFalse(fence.contains(lat, lon), name)
    }

    @Test fun theTwoKilometreBufferAppliesAtTheRealSouthernBorder() {
        // Minnesota's southern edge near lon -94 is the Iowa line at lat 43.5 (rounded outline): 1 km south passes, 10 km does not.
        assertTrue(fence.contains(43.5 - 1.0 / 111.2, -94.0))
        assertFalse(fence.contains(43.5 - 10.0 / 111.2, -94.0))
    }

    @Test fun bufferZeroIsStricterThanTheDefault() {
        val strict = NdMnGeofence.load(0.0)
        // 1 km south of the Minnesota-Iowa line is inside the 2 km buffer only.
        val lat = 43.5 - 1.0 / 111.2
        assertTrue(fence.contains(lat, -94.0)); assertFalse(strict.contains(lat, -94.0))
    }

    @Test fun theBundledResourceIsSmallAndHasProvenance() {
        val size = NdMnGeofence::class.java.getResourceAsStream(NdMnGeofence.RESOURCE_PATH)!!.use { it.readBytes().size }
        assertTrue(size in 1_000..50_000, "size=$size")
        val note = NdMnGeofence::class.java.getResourceAsStream("/com/meshcoreone/android/core/observer/geo/nd-mn.PROVENANCE.md")!!
            .reader().readText()
        assertTrue(note.contains("mobile-observer") && note.contains("cb_2023_us_state_5m"))
    }
}

class GeofenceJsonTest {
    @Test fun parsesNamedRingsAsLatLon() {
        val parsed = GeofenceJson.parse("""{"source":"x","polygons":{"A":[[46,-98],[46,-97],[47,-97]],"B":[[0,0],[0,1],[1,0]]}}""")
        assertEquals(setOf("A", "B"), parsed.keys)
        assertEquals(LatLon(46.0, -98.0), parsed.getValue("A").single().vertices[0])
    }

    @Test fun malformedInputIsRejectedWithAMessage() {
        for (bad in listOf(
            "[]", """{"polygons":{}}""", """{"source":"x"}""", """{"polygons":{"A":[[1,2],[3,4]]}}""",
            """{"polygons":{"A":[[1,2],[3],[5,6]]}}""", """{"polygons":{"A":[[91,2],[3,4],[5,6]]}}""", """{"polygons":{"A":"x"}}""", "{",
        )) {
            val error = assertFailsWith<IllegalArgumentException>(bad) { GeofenceJson.parse(bad) }
            assertTrue(error.message!!.isNotBlank())
        }
    }
}
