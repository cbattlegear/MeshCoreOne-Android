// PortedFrom: MC1Services/Sources/MC1Services/Extensions/CLLocationCoordinate2D+ValidFix.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// No dedicated Swift unit test exists for this extension in the pinned source (it is exercised
// indirectly through LocationService/RegionResolver); this suite independently covers the same
// validity contract the extension documents: geographic range plus the (0,0) null-island sentinel.
package com.meshcoreone.android.core.services.content

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeoCoordinateTest {
    @Test
    fun `A typical coordinate is a valid fix`() {
        assertTrue(GeoCoordinate(37.7749, -122.4194).isValidFix)
    }

    @Test
    fun `Null island (0,0) is rejected as a sentinel, not a valid fix`() {
        assertFalse(GeoCoordinate(0.0, 0.0).isValidFix)
    }

    @Test
    fun `Zero latitude with a nonzero longitude is a valid fix`() {
        assertTrue(GeoCoordinate(0.0, 10.0).isValidFix)
    }

    @Test
    fun `Zero longitude with a nonzero latitude is a valid fix`() {
        assertTrue(GeoCoordinate(10.0, 0.0).isValidFix)
    }

    @Test
    fun `Latitude boundary values are valid`() {
        assertTrue(GeoCoordinate(90.0, 1.0).isValidFix)
        assertTrue(GeoCoordinate(-90.0, 1.0).isValidFix)
    }

    @Test
    fun `Longitude boundary values are valid`() {
        assertTrue(GeoCoordinate(1.0, 180.0).isValidFix)
        assertTrue(GeoCoordinate(1.0, -180.0).isValidFix)
    }

    @Test
    fun `Out-of-range latitude is rejected`() {
        assertFalse(GeoCoordinate(90.1, 1.0).isValidFix)
        assertFalse(GeoCoordinate(-90.1, 1.0).isValidFix)
    }

    @Test
    fun `Out-of-range longitude is rejected`() {
        assertFalse(GeoCoordinate(1.0, 180.1).isValidFix)
        assertFalse(GeoCoordinate(1.0, -180.1).isValidFix)
    }

    @Test
    fun `NaN latitude or longitude is rejected`() {
        assertFalse(GeoCoordinate(Double.NaN, 1.0).isValidFix)
        assertFalse(GeoCoordinate(1.0, Double.NaN).isValidFix)
    }
}
