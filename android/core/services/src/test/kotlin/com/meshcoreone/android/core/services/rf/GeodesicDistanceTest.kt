// PortedFrom: MC1Services/Tests/MC1ServicesTests/RFCalculatorTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// (only the "Earth radius constant is correct" case and the "Haversine Distance Tests" @Suite
// section: 6 of that frozen file's cases total). The remaining RFCalculatorTests cases
// (wavelength, Fresnel radius, earth bulge, path loss, diffraction loss, path analysis, segment
// analysis) exercise RFCalculator surface this file does NOT port (free-space path loss,
// diffraction, Fresnel-zone clearance, path analysis, ElevationSample) and remain WP-212's to
// port against its own future RFCalculator.kt; they are not ignored here, they are simply out of
// this file's actual scope. Primary ownership of RFCalculatorTests.swift itself stays WP-212;
// this is the cross-consumer slice WP-218 needs for its own prerequisite, same port-manifest
// cross-reference pattern already used for ElevationSample-adjacent cases.
package com.meshcoreone.android.core.services.rf

import com.meshcoreone.android.core.services.content.GeoCoordinate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeodesicDistanceTest {
    @Test
    fun `Earth radius constant is correct`() {
        assertEquals(6371.0, GeodesicDistance.EARTH_RADIUS_KM)
    }

    @Test
    fun `Distance between same coordinates is zero`() {
        val coord = GeoCoordinate(latitude = 37.7749, longitude = -122.4194)
        val distance = GeodesicDistance.metersBetween(coord, coord)
        assertEquals(0.0, distance)
    }

    @Test
    fun `Haversine distance calculation is accurate`() {
        // San Francisco to Los Angeles: approximately 559 km.
        val sanFrancisco = GeoCoordinate(latitude = 37.7749, longitude = -122.4194)
        val losAngeles = GeoCoordinate(latitude = 34.0522, longitude = -118.2437)

        val distance = GeodesicDistance.metersBetween(sanFrancisco, losAngeles)

        // Expected: ~559 km = 559000 meters (within 10km tolerance).
        assertTrue(abs(distance - 559_000) < 10000)
    }

    @Test
    fun `Haversine distance is symmetric`() {
        val coord1 = GeoCoordinate(latitude = 37.7749, longitude = -122.4194)
        val coord2 = GeoCoordinate(latitude = 34.0522, longitude = -118.2437)

        val distance1 = GeodesicDistance.metersBetween(coord1, coord2)
        val distance2 = GeodesicDistance.metersBetween(coord2, coord1)

        assertTrue(abs(distance1 - distance2) < 0.001)
    }

    @Test
    fun `Distance across date line is correct`() {
        // Tokyo to San Francisco across the Pacific.
        val tokyo = GeoCoordinate(latitude = 35.6762, longitude = 139.6503)
        val sanFrancisco = GeoCoordinate(latitude = 37.7749, longitude = -122.4194)

        val distance = GeodesicDistance.metersBetween(tokyo, sanFrancisco)

        // Expected: ~8,280 km = 8,280,000 meters (within 100km tolerance).
        assertTrue(abs(distance - 8_280_000) < 100_000)
    }

    @Test
    fun `Short distance calculation is accurate`() {
        // Two points approximately 1 km apart.
        val point1 = GeoCoordinate(latitude = 37.7749, longitude = -122.4194)
        // Moving ~0.009 degrees north is roughly 1 km.
        val point2 = GeoCoordinate(latitude = 37.7839, longitude = -122.4194)

        val distance = GeodesicDistance.metersBetween(point1, point2)

        // Expected: ~1 km = 1000 meters (within 50m tolerance).
        assertTrue(abs(distance - 1000) < 100)
    }
}
