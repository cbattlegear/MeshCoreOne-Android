// PortedFrom: MC1/Services/Geocoder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// No dedicated Swift test file exists for `Geocoder` (independently authored). Covers the
// `GeocodeResult` value-equality semantics the source relies on via Swift's `Equatable`
// conformance, and that a fake `Geocoder` role honors the suspend/cancel contract shape.
package com.meshcoreone.android.core.services.content

import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class GeocoderTest {

    @Test
    fun `GeocodeResult equality mirrors Swift Equatable field-for-field`() {
        val a = GeocodeResult("US", "California", "Santa Clara")
        val b = GeocodeResult("US", "California", "Santa Clara")
        assertEquals(a, b)
    }

    @Test
    fun `GeocodeResult differs when any field differs`() {
        val base = GeocodeResult("US", "California", "Santa Clara")
        assertNotEquals(base, base.copy(countryCode = "CA"))
        assertNotEquals(base, base.copy(administrativeArea = "Oregon"))
        assertNotEquals(base, base.copy(subAdministrativeArea = null))
    }

    @Test
    fun `fake Geocoder returns null for no placemark, mirroring placemarks-first unwrap`() = runTest {
        val geocoder = FakeGeocoder(result = null)
        val result = geocoder.reverseGeocode(GeoCoordinate(37.0, -122.0), preferredLocale = null)
        assertNull(result)
    }

    @Test
    fun `fake Geocoder passes preferredLocale through unchanged`() = runTest {
        var observedLocale: Locale? = Locale.CANADA // sentinel distinct from both `null` and the passed locale
        val geocoder = FakeGeocoder(result = GeocodeResult("US", null, null)) { observedLocale = it }
        geocoder.reverseGeocode(GeoCoordinate(0.0, 0.0), preferredLocale = Locale.US)
        assertEquals(Locale.US, observedLocale)
    }

    @Test
    fun `cancelGeocode is observable on the role`() {
        val geocoder = FakeGeocoder(result = null)
        geocoder.cancelGeocode()
        assertEquals(1, geocoder.cancelCount)
    }

    private class FakeGeocoder(
        private val result: GeocodeResult?,
        private val onReverseGeocode: (Locale?) -> Unit = {},
    ) : Geocoder {
        var cancelCount = 0
            private set

        override suspend fun reverseGeocode(coordinate: GeoCoordinate, preferredLocale: Locale?): GeocodeResult? {
            onReverseGeocode(preferredLocale)
            return result
        }

        override fun cancelGeocode() {
            cancelCount++
        }
    }
}
