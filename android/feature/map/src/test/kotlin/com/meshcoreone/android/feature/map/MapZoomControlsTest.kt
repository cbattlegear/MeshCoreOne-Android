// AndroidOnly: WP-312 Camera arithmetic behind the zoom buttons.
package com.meshcoreone.android.feature.map

import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.maps.MapCamera
import com.meshcoreone.android.core.maps.MapCameraPose
import org.junit.Assert.assertEquals
import org.junit.Test

class MapZoomControlsTest {
    private val center = GeoPoint(10.0, 20.0)

    @Test fun zoomInHalvesTheVisibleSpanAndRaisesThePoseZoomByOne() {
        val next = MapCamera(center, 0.4, 0.8, MapCameraPose(10.0, 30.0, 0.0)).zoomedBy(1.0)
        assertEquals(0.2, next.latitudeSpan, 1e-12)
        assertEquals(0.4, next.longitudeSpan, 1e-12)
        assertEquals(11.0, requireNotNull(next.pose).zoom, 0.0)
        assertEquals(30.0, requireNotNull(next.pose).bearing, 0.0)
        assertEquals(center, next.center)
    }

    @Test fun zoomOutDoublesTheSpanAndWorksWithoutAPose() {
        val next = MapCamera(center, 0.4, 0.8).zoomedBy(-1.0)
        assertEquals(0.8, next.latitudeSpan, 1e-12)
        assertEquals(1.6, next.longitudeSpan, 1e-12)
        assertEquals(null, next.pose)
    }

    @Test fun zoomIsClampedAtTheWorldAndStreetExtremes() {
        val deep = MapCamera(center, 1e-6, 1e-6, MapCameraPose(22.0, 0.0, 0.0)).zoomedBy(1.0)
        assertEquals(1e-6, deep.latitudeSpan, 0.0)
        assertEquals(22.0, requireNotNull(deep.pose).zoom, 0.0)
        val wide = MapCamera(center, 150.0, 170.0, MapCameraPose(0.0, 0.0, 0.0)).zoomedBy(-1.0)
        assertEquals(180.0, wide.latitudeSpan, 0.0)
        assertEquals(180.0, wide.longitudeSpan, 0.0)
        assertEquals(0.0, requireNotNull(wide.pose).zoom, 0.0)
    }
}
