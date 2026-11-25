// PortedFrom: MC1Tests/Views/Map/MapFilterStateTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: apply the incumbent filter algebra to actual feature markers and selection lookup.
package com.meshcoreone.android.feature.map

import com.meshcoreone.android.core.maps.*
import java.util.UUID
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class MapFeatureFilterTest {
    @Test fun favoritesFreezeTypeAxesAndNeverAdmitDiscoveredNodes() {
        val favorite = marker(ContactMapType.ROOM, favorite = true)
        val ordinary = marker(ContactMapType.CHAT)
        val discovered = marker(ContactMapType.REPEATER, discovered = true)
        val content = MapFeatureContent(listOf(favorite, ordinary, discovered))
        val filter = MapFilterState(favoritesOnly = true, showChat = true, showRepeater = false, showRoom = false, showDiscovered = true)
        val state = MapFeatureState(content, filter, ordinary.marker.id)
        assertEquals(listOf(favorite), state.visibleMarkers)
        assertNull(state.selected)
    }

    @Test fun discoveredAndTypeAxesRemainIndependentAndSelectionUsesTheLatestVisibleRow() {
        val chat = marker(ContactMapType.CHAT)
        val repeater = marker(ContactMapType.REPEATER, discovered = true)
        val content = MapFeatureContent(listOf(chat, repeater))
        val hidden = MapFeatureState(content, MapFilterState(showDiscovered = false), repeater.marker.id)
        assertEquals(listOf(chat), hidden.visibleMarkers)
        assertNull(hidden.selected)
        val visible = hidden.copy(filter = MapFilterState(showDiscovered = true))
        assertEquals(repeater, visible.selected)
        val replacement = repeater.copy(marker = repeater.marker.copy(label = "Updated"))
        assertEquals(replacement, visible.copy(content = MapFeatureContent(listOf(replacement))).selected)
        assertNull(visible.copy(content = MapFeatureContent()).selected)
    }

    @Test fun advancedAxesIntersectFavoritesTypesAndDiscovered() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val favorite = marker(ContactMapType.ROOM, favorite = true).copy(heardAt = now.minusSeconds(60), displayedHops = 1)
        val unknown = marker(ContactMapType.CHAT, favorite = true).copy(heardAt = now, displayedHops = null)
        val discovered = marker(ContactMapType.REPEATER, discovered = true).copy(heardAt = now.minusSeconds(60), displayedHops = 1)
        val state = MapFeatureState(
            content = MapFeatureContent(listOf(favorite, unknown, discovered)),
            filter = MapFilterState(favoritesOnly = true, showDiscovered = true, showRoom = false,
                lastHeard = MapLastHeardRange.of(maxAge = MapDuration.of(1, MapDurationUnit.minutes)),
                hops = MapHopRange.of(max = 1)),
            now = now,
        )
        assertEquals(listOf(favorite), state.visibleMarkers)
        val nonFavorites = state.copy(filter = state.filter.copy(favoritesOnly = false))
        assertEquals(listOf(discovered), nonFavorites.visibleMarkers)
        assertTrue(nonFavorites.copy(now = now.plusSeconds(1)).visibleMarkers.isEmpty())
    }

    private fun marker(type: ContactMapType, favorite: Boolean = false, discovered: Boolean = false) =
        MapFeatureMarker(
            MapMarker(UUID.randomUUID(), GeoPoint(1.0, 2.0), MapPinStyle.CONTACT_CHAT),
            type, favorite, discovered,
        )
}
