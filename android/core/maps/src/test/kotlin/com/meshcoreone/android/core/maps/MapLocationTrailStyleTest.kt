// AndroidOnly: WP-312 Ancillary style-table checks reconciled from the preserved 3ca57f86 draft.
// These do not substitute for real history-builder geometry or installed-layer assertions.
package com.meshcoreone.android.core.maps

import org.junit.Assert.*
import org.junit.Test

class MapLocationTrailStyleTest {
    @Test fun dashedTrailSpecPreservesOriginalParametersAndDrawOrder() {
        val casing = MapLineLayerSpecs.ordered.single { it.layerId == "mesh-location-trail-casing" }
        val main = MapLineLayerSpecs.ordered.single { it.layerId == "mesh-location-trail" }
        assertEquals(listOf(1.6f, 2.8f), main.dash)
        assertEquals(2.5f, main.width, 0f)
        assertEquals(listOf(0.8f, 1.4f), casing.dash)
        assertEquals(5f, casing.width, 0f)
        assertTrue(casing.casing && casing.roundJoinAndCap && main.roundJoinAndCap)
        assertEquals(0.8f, MapLineLayerSpecs.CASING_OPACITY, 0f)
        assertEquals(MapLineLayerSpecs.ordered.indexOf(main) - 1, MapLineLayerSpecs.ordered.indexOf(casing))
    }

    @Test fun otherStylesKeepTheirExistingLayersAndTrailNeverUsesSharedSolidLayer() {
        assertEquals(listOf("mesh-untraced-lines"), MapLineLayerSpecs.layersFor(MapLineStyle.TRACE_UNTRACED.name).map { it.layerId })
        assertEquals(listOf(1.75f, 3.25f), MapLineLayerSpecs.ordered.single { it.layerId == "mesh-untraced-lines" }.dash)
        for (style in MapLineStyle.entries - MapLineStyle.TRACE_UNTRACED - MapLineStyle.LOCATION_TRAIL) {
            assertEquals(listOf("mesh-lines-layer"), MapLineLayerSpecs.layersFor(style.name).map { it.layerId })
        }
        assertEquals(
            listOf("mesh-location-trail-casing", "mesh-location-trail"),
            MapLineLayerSpecs.layersFor(MapLineStyle.LOCATION_TRAIL.name).map { it.layerId },
        )
        assertFalse(MapLineStyle.LOCATION_TRAIL in MapLineLayerSpecs.ordered.first().styles)
    }
}
