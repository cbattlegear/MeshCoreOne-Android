// PortedFrom: MC1Tests/Services/RegionResolverTests.swift@db14559b39d32322b06477c6ae676112f583db50
//
// The source's own test file notes its coverage is deliberately limited to the
// `location.isAuthorized` guard: "Success-path coverage requires injecting a stubbed
// LocationService, which is a follow-up (LocationService is not currently abstracted behind a
// protocol)." This port's `LocationService` IS already abstracted behind the `LocationProducing`
// role (see LocationServiceTest.kt), so the follow-up the source explicitly flagged as blocked
// is exercised here for real: cache reuse/expiry, the geocode timeout cancelling the geocoder,
// normalization (lowercase/trim/diacritic-fold) and the " county" suffix strip, and the
// [RegionAreaMatching] code-lookup hand-off. The two cases below marked `PortedFrom-case` are
// the source file's actual original assertions, ported 1:1; everything else is a disclosed
// WP-218 addition closing the coverage gap the source's own comment identifies.
package com.meshcoreone.android.core.services.content

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import java.util.Locale
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.services.device.RadioPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RegionResolverTest {

    private class FakeLocationProducing(
        initialStatus: LocationAuthorizationStatus = LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE,
    ) : LocationProducing {
        private val status = MutableStateFlow(initialStatus)
        override val authorizationStatus: StateFlow<LocationAuthorizationStatus> = status
        var onRequestLocation: suspend () -> GeoCoordinate = { GeoCoordinate(37.0, -122.0) }
        override fun requestPermissionIfNeeded() {}
        override suspend fun requestLocation(): GeoCoordinate = onRequestLocation()
    }

    private class FakeGeocoder(
        var stub: GeocodeResult? = null,
        private val onReverseGeocode: (suspend () -> Unit)? = null,
    ) : Geocoder {
        var cancelGeocodeCallCount = 0
            private set

        override suspend fun reverseGeocode(coordinate: GeoCoordinate, preferredLocale: Locale?): GeocodeResult? {
            onReverseGeocode?.invoke()
            return stub
        }

        override fun cancelGeocode() {
            cancelGeocodeCallCount++
        }
    }

    private class FakeAreaMatching(
        private val subdivision: (String, String?) -> String? = { _, _ -> null },
        private val county: (String, String?, String?) -> String? = { _, _, _ -> null },
    ) : RegionAreaMatching {
        override fun matchSubdivision(country: String, normalized: String?): String? =
            subdivision(country, normalized)

        override fun matchCounty(country: String, state: String?, normalized: String?): String? =
            county(country, state, normalized)
    }

    private fun resolver(
        location: LocationService,
        geocoder: Geocoder,
        areaMatching: RegionAreaMatching = FakeAreaMatching(),
        clockMs: () -> Long = { 0L },
    ) = RegionResolver(location, geocoder, areaMatching, clockMs = clockMs)

    // PortedFrom-case: "nil isoCountryCode → nil"
    @Test
    fun `nil country code resolves to null`() = runTest {
        val location = LocationService(FakeLocationProducing())
        var geocodeCalls = 0
        val geocoder = FakeGeocoder(
            stub = GeocodeResult(countryCode = null, administrativeArea = null, subAdministrativeArea = null),
            onReverseGeocode = { geocodeCalls++ },
        )
        val result = resolver(location, geocoder).resolve()
        assertNull(result)
        assertEquals(1, geocodeCalls, "the authorized path must reach the nil-country precondition")
    }

    // PortedFrom-case: "Unauthorized location → nil"
    @Test
    fun `unauthorized location resolves to null`() = runTest {
        val location = LocationService(FakeLocationProducing(LocationAuthorizationStatus.NOT_DETERMINED))
        var geocodeCalls = 0
        val geocoder = FakeGeocoder(onReverseGeocode = { geocodeCalls++ })
        val result = resolver(location, geocoder).resolve()
        assertNull(result)
        assertEquals(0, geocodeCalls, "the unauthorized path must return before geocoding")
    }

    // WP-218 addition: the source's own `#expect(result == nil)` cases never exercise a
    // successful path because its suite could not stub `LocationService`; this port can, and
    // real success-path behavior must not be left untested.
    @Test
    fun `resolve derives region selection from geocode result via area matching`() = runTest {
        val location = LocationService(FakeLocationProducing())
        val geocoder = FakeGeocoder(
            stub = GeocodeResult(
                countryCode = "US",
                administrativeArea = "  California  ",
                subAdministrativeArea = "Santa Clara County",
            ),
        )
        val areaMatching = FakeAreaMatching(
            subdivision = { country, normalized -> if (country == "US" && normalized == "california") "US-CA" else null },
            county = { country, state, normalized ->
                if (country == "US" && state == "US-CA" && normalized == "santa clara") "santa-clara" else null
            },
        )

        val result = resolver(location, geocoder, areaMatching).resolve()

        assertEquals("US", result?.countryCode)
        assertEquals("US-CA", result?.administrativeAreaCode)
        assertEquals("santa-clara", result?.countyKey)
        assertEquals(com.meshcoreone.android.core.model.RegionSelection.Source.LOCATION, result?.source)
    }

    @Test
    fun `resolve normalizes diacritics before matching`() = runTest {
        val location = LocationService(FakeLocationProducing())
        val geocoder = FakeGeocoder(
            stub = GeocodeResult(countryCode = "FR", administrativeArea = "Île-de-France", subAdministrativeArea = null),
        )
        var seenNormalized: String? = null
        val areaMatching = FakeAreaMatching(
            subdivision = { _, normalized -> seenNormalized = normalized; null },
        )

        resolver(location, geocoder, areaMatching).resolve()

        assertEquals("ile-de-france", seenNormalized)
    }

    @Test
    fun `resolve strips the county suffix after normalization`() = runTest {
        val location = LocationService(FakeLocationProducing())
        val geocoder = FakeGeocoder(
            stub = GeocodeResult(countryCode = "US", administrativeArea = null, subAdministrativeArea = "Santa Clara County"),
        )
        var seenNormalized: String? = null
        val areaMatching = FakeAreaMatching(
            county = { _, _, normalized -> seenNormalized = normalized; null },
        )

        resolver(location, geocoder, areaMatching).resolve()

        assertEquals("santa clara", seenNormalized)
    }

    @Test
    fun `resolve caches a successful result for repeated calls at the same rounded coordinate`() = runTest {
        val location = LocationService(FakeLocationProducing())
        var geocodeCallCount = 0
        val geocoder = FakeGeocoder(
            stub = GeocodeResult(countryCode = "US", administrativeArea = null, subAdministrativeArea = null),
            onReverseGeocode = { geocodeCallCount++ },
        )
        var nowMs = 0L
        val target = resolver(location, geocoder, clockMs = { nowMs })

        val first = target.resolve()
        val second = target.resolve()

        assertEquals(first, second)
        assertEquals(1, geocodeCallCount)
    }

    @Test
    fun `resolve re-geocodes once the cache entry expires`() = runTest {
        val location = LocationService(FakeLocationProducing())
        var geocodeCallCount = 0
        val geocoder = FakeGeocoder(
            stub = GeocodeResult(countryCode = "US", administrativeArea = null, subAdministrativeArea = null),
            onReverseGeocode = { geocodeCallCount++ },
        )
        var nowMs = 0L
        val target = resolver(location, geocoder, clockMs = { nowMs })

        target.resolve()
        nowMs += RegionResolver.CACHE_TTL_MS + 1
        target.resolve()

        assertEquals(2, geocodeCallCount)
    }

    @Test
    fun `resolve cancels the geocoder and returns null on geocode timeout`() = runTest {
        val location = LocationService(FakeLocationProducing())
        val geocoder = FakeGeocoder(
            onReverseGeocode = { delay(RegionResolver.GEOCODE_TIMEOUT_MS + 1_000) },
        )

        val result = resolver(location, geocoder).resolve()

        assertNull(result)
        assertEquals(1, geocoder.cancelGeocodeCallCount)
    }

    @Test
    fun `resolve returns null when the location fix itself fails`() = runTest {
        val producing = FakeLocationProducing()
        producing.onRequestLocation = { throw RuntimeException("GPS hardware unavailable") }
        val location = LocationService(producing)
        val geocoder = FakeGeocoder()

        val result = resolver(location, geocoder).resolve()

        assertNull(result)
    }

    @Test
    fun `real matcher resolves normalized SoCal county into the real county preset`() = runTest {
        val geocoder = FakeGeocoder(
            GeocodeResult("US", "  California  ", "Los \u00c1ngeles County"),
        )
        val target = RegionResolver(LocationService(FakeLocationProducing()), geocoder)

        val region = assertNotNull(target.resolve())

        assertEquals(RegionSelection("US", RegionSelection.Source.LOCATION, "US-CA", "los angeles"), region)
        val preset = RadioPresets.recommended(region)
        assertEquals("wcmesh", preset?.id)
        assertEquals(927.875, preset?.frequencyMHz)
        assertEquals(62.5, preset?.bandwidthKHz)
        assertEquals(7u.toUByte(), preset?.spreadingFactor)
        assertEquals(5u.toUByte(), preset?.codingRate)
        assertEquals(3L, preset?.pathHashSize)
    }

    @Test
    fun `real matcher leaves uncatalogued county absent and chooses the country preset`() = runTest {
        val geocoder = FakeGeocoder(GeocodeResult("US", "California", "Santa Clara County"))

        val region = assertNotNull(RegionResolver(LocationService(FakeLocationProducing()), geocoder).resolve())

        assertEquals("US-CA", region?.administrativeAreaCode)
        assertNull(region?.countyKey)
        assertEquals("us-ca", RadioPresets.recommended(region)?.id)
    }

    @Test
    fun `real matcher resolves Australian postal and long names into frozen subdivision presets`() = runTest {
        for ((name, code, preset) in listOf(
            Triple("  QLD  ", "AU-QLD", "au-qld"),
            Triple("Western Australia", "AU-WA", "au-sa-wa"),
            Triple("Victoria", "AU-VIC", "au-915"),
        )) {
            val geocoder = FakeGeocoder(GeocodeResult("AU", name, null))

            val region = assertNotNull(RegionResolver(LocationService(FakeLocationProducing()), geocoder).resolve())

            assertEquals(code, region?.administrativeAreaCode)
            assertNull(region?.countyKey)
            assertEquals(preset, RadioPresets.recommended(region)?.id)
        }
    }

    @Test
    fun `real matcher preserves case and unknown-country no-match contracts`() {
        assertEquals("US-CA", RegionalAreaMatcher.matchSubdivision("US", "california"))
        assertNull(RegionalAreaMatcher.matchSubdivision("us", "california"))
        assertNull(RegionalAreaMatcher.matchSubdivision("US", "California"))
        assertNull(RegionalAreaMatcher.matchSubdivision("US", "puerto rico"))
        assertNull(RegionalAreaMatcher.matchSubdivision("CA", "ontario"))
        assertNull(RegionalAreaMatcher.matchCounty("US", "US-PA", "philadelphia"))
        assertNull(RegionalAreaMatcher.matchCounty("AU", "US-CA", "los angeles"))
        assertNull(RegionalAreaMatcher.matchCounty("US", "US-CA", null))
    }

    @Test
    fun `real matcher and preset consumer are reused on a location cache hit`() = runTest {
        var geocodeCalls = 0
        val geocoder = FakeGeocoder(
            GeocodeResult("US", "California", "Orange County"),
            onReverseGeocode = { geocodeCalls++ },
        )
        val target = RegionResolver(
            LocationService(FakeLocationProducing()), geocoder, clockMs = { 0L },
        )

        val first = target.resolve()
        val second = assertNotNull(target.resolve())

        assertEquals(first, second)
        assertEquals(1, geocodeCalls)
        assertEquals("wcmesh", RadioPresets.recommended(second)?.id)
    }
}
