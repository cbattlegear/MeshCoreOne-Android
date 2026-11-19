// PortedFrom: MC1Tests/Views/Map/MapAppearanceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Map/MapCameraStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Map/MapFilterStateTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Map/MapPointClusteringTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MapDomainTest {
    @Test
    fun appearanceMatchesSystemAndOverrides() {
        assertTrue(resolvedMapIsDark(MapColorPreference.SYSTEM, true))
        assertFalse(resolvedMapIsDark(MapColorPreference.SYSTEM, false))
        assertFalse(resolvedMapIsDark(MapColorPreference.LIGHT, true))
        assertFalse(resolvedMapIsDark(MapColorPreference.LIGHT, false))
        assertTrue(resolvedMapIsDark(MapColorPreference.DARK, false))
        assertTrue(resolvedMapIsDark(MapColorPreference.DARK, true))
    }

    @Test
    fun cameraRoundTripsAndRejectsMalformedValues() {
        val camera = MapCamera(GeoPoint(37.7749, -122.4194), 0.25, 0.5)
        assertEquals(camera, MapCamera.decode(camera.encode()))
        listOf("", "1,2,3", "1,2,3,4,5", "a,b,c,d", "10,20,nan,1", "10,20,NaN,1", "91,20,1,1", "10,20,0,1", "10,20,1,-1")
            .forEach { assertNull(MapCamera.decode(it)) }
    }

    @Test
    fun invalidCameraIsRejectedAtConstructionAndDecoderBoundaries() {
        for (span in listOf(Double.NaN, 0.0)) {
            val error = runCatching { MapCamera(GeoPoint(10.0, 20.0), span, 1.0) }.exceptionOrNull()
            assertTrue("Invalid constructor span $span", error is IllegalArgumentException)
            // Swift permits constructing/encoding invalid regions; Android rejects them earlier.
            assertNull(MapCamera.decode("10.0,20.0,$span,1.0"))
        }
    }

    @Test
    fun filterDefaultsAndFavoritesFreezeAllOriginalAxesUntilFavoritesClears() {
        val defaults = MapFilterState()
        assertFalse(defaults.favoritesOnly)
        assertFalse(defaults.showDiscovered)
        assertTrue(defaults.showChat && defaults.showRepeater && defaults.showRoom)
        val original = MapFilterState(showDiscovered = true, showChat = true, showRepeater = false, showRoom = true)
        val favorites = original.setFavoritesOnly(true)
        assertTrue(favorites.favoritesOnly)
        assertTrue(favorites.showDiscovered)
        assertFalse(favorites.showRepeater)
        assertEquals(favorites, favorites.setShowDiscovered(false))
        assertEquals(favorites, favorites.setContactType(ContactMapType.CHAT, false, MapFilterHost.MAIN_MAP))
        val restored = favorites.setFavoritesOnly(false)
        assertFalse(restored.favoritesOnly)
        assertTrue(restored.showDiscovered)
        assertFalse(restored.showRepeater)
        assertEquals(original, restored)
        assertFalse(favorites.effectiveShowDiscovered)
        assertTrue(favorites.showDiscovered)
    }

    @Test
    fun everyLastContactTypeAndHostWithoutTypeCapabilitiesPreservesSourceState() {
        for (type in ContactMapType.entries) {
            val only = MapFilterState(
                showChat = type == ContactMapType.CHAT,
                showRepeater = type == ContactMapType.REPEATER,
                showRoom = type == ContactMapType.ROOM,
            )
            assertEquals(type.name, only, only.setContactType(type, false, MapFilterHost.MAIN_MAP))
        }
        for (host in listOf(MapFilterHost.TRACE_PATH, MapFilterHost.NEIGHBOR_SNR)) {
            val defaults = MapFilterState.seed(host)
            for (type in ContactMapType.entries) {
                assertEquals("$host/$type", defaults, defaults.setContactType(type, false, host))
            }
            val allOff = MapFilterState(showChat = false, showRepeater = false, showRoom = false)
            assertEquals(allOff, allOff.sanitized(host))
        }
        val raw = MapFilterState(showDiscovered = true, showChat = false, showRepeater = false, showRoom = false)
        val repaired = raw.sanitized(MapFilterHost.MAIN_MAP)
        assertTrue(repaired.showChat && repaired.showRepeater && repaired.showRoom)
        assertFalse(repaired.favoritesOnly)
        assertTrue(repaired.showDiscovered)
    }

    @Test
    fun filterRoundTripAndEverySeedDifferenceRetainsTheOriginalVector() {
        val original = MapFilterState(
            favoritesOnly = true, showDiscovered = true, showChat = false, showRepeater = true, showRoom = true,
        )
        assertEquals(original, MapFilterState.decode(original.encode()))
        assertNull(MapFilterState.decode(""))
        for (host in MapFilterHost.entries) {
            val defaults = MapFilterState.seed(host)
            assertFalse(defaults.differsFromSeed(host))
            assertTrue(defaults.setFavoritesOnly(true).differsFromSeed(host))
            assertTrue(defaults.setShowDiscovered(!defaults.showDiscovered).differsFromSeed(host))
        }
        for (type in ContactMapType.entries) {
            val defaults = MapFilterState.seed(MapFilterHost.MAIN_MAP)
            assertTrue(defaults.setContactType(type, false, MapFilterHost.MAIN_MAP).differsFromSeed(MapFilterHost.MAIN_MAP))
        }
        val noChat = MapFilterState(showChat = false)
        assertFalse(noChat.allows(ContactMapType.CHAT))
        assertTrue(noChat.allows(ContactMapType.REPEATER))
        assertTrue(noChat.setFavoritesOnly(true).allows(ContactMapType.CHAT))
    }

    @Test
    fun boundingCameraAndDistancePreserveCoordinateSemantics() {
        val points = listOf(GeoPoint(37.0, -123.0), GeoPoint(38.0, -122.0))
        assertEquals(MapCamera(GeoPoint(37.5, -122.5), 1.5, 1.5), points.boundingCamera())
        assertTrue(points.totalDistanceMeters()!! > 140_000.0)
        assertNull(listOf(GeoPoint(1.0, 2.0)).totalDistanceMeters())
    }

    @Test
    fun antimeridianBoundsOverlapAndMidpointRemainValid() {
        val midpoint = GeoPoint(10.0, 179.0).midpoint(GeoPoint(10.0, -179.0))
        assertEquals(180.0, midpoint.longitude, 0.0)
        val crossing = GeoBounds(GeoPoint(-5.0, 170.0), GeoPoint(5.0, -170.0))
        assertTrue(crossing.overlaps(GeoBounds(GeoPoint(-1.0, 175.0), GeoPoint(1.0, 179.0))))
        assertFalse(crossing.overlaps(GeoBounds(GeoPoint(-1.0, -10.0), GeoPoint(1.0, 10.0))))
    }

    @Test
    fun filtersFreezeDisabledAxesAndKeepOneContactType() {
        var state = MapFilterState(showDiscovered = true, showRepeater = false, showRoom = false)
        state = state.setContactType(ContactMapType.CHAT, false, MapFilterHost.MAIN_MAP)
        assertTrue(state.showChat)
        state = state.setFavoritesOnly(true)
        assertEquals(state, state.setShowDiscovered(false))
        assertTrue(state.allows(ContactMapType.ROOM))
        assertFalse(state.effectiveShowDiscovered)
    }

    @Test
    fun filtersSeedEncodeDecodeAndSanitizeByHost() {
        assertTrue(MapFilterState.seed(MapFilterHost.TRACE_PATH).showDiscovered)
        assertFalse(MapFilterState.seed(MapFilterHost.NEIGHBOR_SNR).showDiscovered)
        val corrupt = MapFilterState(showChat = false, showRepeater = false, showRoom = false)
        val encoded = MapFilterPreferences.encode(corrupt, MapFilterHost.MAIN_MAP)
        val decoded = MapFilterState.decode(encoded)!!
        assertTrue(decoded.showChat && decoded.showRepeater && decoded.showRoom)
        assertNull(MapFilterState.decode("{not-json"))
    }

    @Test
    fun filterMigrationOnlyConsumesLegacyForMainMap() {
        val main = MapFilterPreferences.migrate(null, true, MapFilterHost.MAIN_MAP)
        assertTrue(main.state.showDiscovered)
        assertTrue(main.replacement != null)
        val trace = MapFilterPreferences.migrate(null, false, MapFilterHost.TRACE_PATH)
        assertTrue(trace.state.showDiscovered)
        assertNull(trace.replacement)
        val stored = MapFilterPreferences.migrate(MapFilterState(showDiscovered = false).encode(), true, MapFilterHost.MAIN_MAP)
        assertFalse(stored.state.showDiscovered)
        assertNull(stored.replacement)
    }

    @Test
    fun filterDecodeRequiresACompleteJsonObjectWithActualBooleanValues() {
        val valid = MapFilterState().encode()
        assertEquals(MapFilterState(), MapFilterState.decode(valid))
        for (invalid in listOf(
            valid.dropLast(1), valid + " trailing", "[$valid]",
            valid.replace("\"showChat\":true", "\"showChat\":truegarbage"),
            valid.replace("\"showChat\":true", "\"showChat\":\"true\""),
            valid.replace("\"showChat\":true,", ""),
            valid.replace("\"showChat\":true", "\"showChat\":null"),
        )) {
            assertNull(invalid, MapFilterState.decode(invalid))
        }
        assertEquals(MapFilterState(), MapFilterState.decode(valid.dropLast(1) + ""","future":{"flag":true}}"""))
    }

    @Test
    fun clusteringKeepsStableOrder() {
        val contact = marker(clusterable = true)
        val dropped = marker(clusterable = false)
        val enabled = listOf(contact, dropped).partitionForClustering(true)
        assertEquals(listOf(contact), enabled.clusterable)
        assertEquals(listOf(dropped), enabled.fixed)
        val disabled = listOf(contact, dropped).partitionForClustering(false)
        assertTrue(disabled.clusterable.isEmpty())
        assertEquals(listOf(contact, dropped), disabled.fixed)
    }

    @Test
    fun providerCatalogRejectsUnattributedOrOfflineSatelliteLayers() {
        val attribution = listOf(MapProviderAttribution("approved", "https://example.invalid/legal"))
        val error = runCatching {
            MapProviderCatalog(
                "engine",
                mapOf(
                    MapStyle.STANDARD to MapLayerDescriptor(MapStyle.STANDARD, attribution, true, true),
                    MapStyle.SATELLITE to MapLayerDescriptor(MapStyle.SATELLITE, attribution, true, true),
                    MapStyle.TOPO to MapLayerDescriptor(MapStyle.TOPO, attribution, true, true),
                ),
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun approvedProviderExposesExactAttributionAndUnavailableStylesTruthfully() {
        val catalog = MapLibreOpenFreeMap.catalog
        assertEquals("maplibre-native-13.6.1", catalog.engineId)
        assertEquals(
            listOf("OpenFreeMap", "© OpenMapTiles", "© OpenStreetMap contributors"),
            catalog.layers.getValue(MapStyle.STANDARD).attribution.map { it.label },
        )
        assertTrue(catalog.layers.getValue(MapStyle.STANDARD).supportsSnapshots)
        assertTrue(catalog.layers.getValue(MapStyle.STANDARD).supportsOfflineRegions)
        assertTrue(catalog.layers.getValue(MapStyle.SATELLITE).availability is MapLayerAvailability.Unavailable)
        assertTrue(catalog.layers.getValue(MapStyle.TOPO).availability is MapLayerAvailability.Unavailable)
    }

    @Test
    fun unavailableLayerCannotClaimOfflineRegions() {
        val attribution = listOf(MapProviderAttribution("provider", "https://example.invalid/legal"))
        val error = runCatching {
            MapProviderCatalog(
                "engine",
                mapOf(
                    MapStyle.STANDARD to MapLayerDescriptor(MapStyle.STANDARD, attribution, true, false),
                    MapStyle.SATELLITE to MapLayerDescriptor(
                        MapStyle.SATELLITE,
                        attribution,
                        false,
                        true,
                        MapLayerAvailability.Unavailable("not supplied"),
                    ),
                    MapStyle.TOPO to MapLayerDescriptor(MapStyle.TOPO, attribution, false, false),
                ),
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    private fun marker(clusterable: Boolean) = MapMarker(
        id = UUID.randomUUID(),
        position = GeoPoint(1.0, 2.0),
        style = if (clusterable) MapPinStyle.CONTACT_CHAT else MapPinStyle.DROPPED_PIN,
        clusterable = clusterable,
    )
}
