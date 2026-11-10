// AndroidOnly: WP-312 Ancillary original-property preservation checks; not installed native-style evidence.
package com.meshcoreone.android.core.maps

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.style.layers.PaintPropertyValue
import org.maplibre.android.style.layers.PropertyValue

class MapLibreStyleAppearanceTest {
    @Test fun restorationRetainsZoomColorExpressionsInsteadOfSavingOnlyTheCurrentZoomValue() {
        val array = JsonParser.parseString(
            """["interpolate",["linear"],["zoom"],5,"#AABBCC",6,"#DDEEFF"]""",
        ).asJsonArray
        val original = PropertyValue<Any>("line-color", array)
        val restored = MapLibreStyleAppearance.restoredPaint(original)
        assertTrue(restored is PaintPropertyValue)
        assertTrue(restored.isExpression)
        assertEquals("line-color", restored.name)
        assertEquals(
            original.expression?.toString(),
            restored.expression?.toString(),
        )
    }

    @Test fun restorationRetainsLiteralAndUnsetPaintAndDoesNotTargetMeshSourcesOrLabels() {
        val literal = MapLibreStyleAppearance.restoredPaint(PropertyValue("text-color", "#AABBCC"))
        assertTrue(literal is PaintPropertyValue)
        assertEquals("#AABBCC", literal.value)
        val unset = MapLibreStyleAppearance.restoredPaint(PropertyValue<Any?>("fill-outline-color", null))
        assertTrue(unset is PaintPropertyValue)
        assertTrue(unset.isNull)
        val ids = MapLibreStyleAppearance.layerIds
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.containsAll(listOf("background", "water", "road_motorway", "building-3d", "label_city")))
        assertTrue(ids.none { it.startsWith("mesh-") })
        assertFalse(ids.contains("highway-shield-us-interstate"))
    }
}
