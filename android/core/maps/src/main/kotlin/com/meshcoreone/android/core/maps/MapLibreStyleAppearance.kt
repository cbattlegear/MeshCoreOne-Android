// AndroidOnly: WP-312 Reversible dark paint for the existing OpenFreeMap Liberty style.
package com.meshcoreone.android.core.maps

import java.util.logging.Logger
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*

internal class MapLibreStyleAppearance(private val layerForId: (String) -> Layer?) {
    private val original = mutableMapOf<String, List<PropertyValue<*>>>()
    private var appliedDark: Boolean? = null

    fun apply(isDark: Boolean) {
        if (appliedDark == isDark) return
        layerIds.forEach { id ->
            val layer = layerForId(id)
            if (layer == null) {
                Logger.getLogger("com.meshcoreone.android.MapAppearance")
                    .warning("Liberty appearance layer missing: $id")
                return@forEach
            }
            val paints: List<Pair<PropertyValue<*>, PropertyValue<*>>> = when (layer) {
                is BackgroundLayer -> listOf(layer.backgroundColor to backgroundColor("#182028"))
                is RasterLayer -> listOf(
                    layer.rasterBrightnessMax to rasterBrightnessMax(0.35f),
                    layer.rasterSaturation to rasterSaturation(-0.6f),
                )
                is FillLayer -> listOf(
                    layer.fillColor to fillColor(when (id) {
                        "water" -> "#172F42"
                        "park", "landcover_wood", "landcover_grass", "landuse_cemetery" -> "#273F32"
                        "building" -> "#3D444B"
                        "landcover_ice" -> "#46545E"
                        "landcover_sand" -> "#494333"
                        else -> "#2B333B"
                    }),
                    layer.fillOutlineColor to fillOutlineColor("#46515A"),
                )
                is FillExtrusionLayer -> listOf(layer.fillExtrusionColor to fillExtrusionColor("#3D444B"))
                is LineLayer -> listOf(layer.lineColor to lineColor(when {
                    id.startsWith("waterway") -> "#325770"
                    id == "park_outline" -> "#45634B"
                    id.endsWith("casing") -> "#242C33"
                    "motorway" in id || "trunk_primary" in id || "secondary_tertiary" in id -> "#8E7653"
                    id.startsWith("boundary") -> "#88939E"
                    else -> "#66717C"
                }))
                is SymbolLayer -> listOf(
                    layer.textColor to textColor(if (id.startsWith("water")) "#92B8D3" else "#D9E1E8"),
                    layer.textHaloColor to textHaloColor("#182028"),
                )
                else -> {
                    Logger.getLogger("com.meshcoreone.android.MapAppearance")
                        .warning("Unexpected Liberty layer type for $id: ${layer.javaClass.simpleName}")
                    return@forEach
                }
            }
            val light = original.getOrPut(id) { paints.map { restoredPaint(it.first) } }
            layer.setProperties(*(if (isDark) paints.map { it.second } else light).toTypedArray())
        }
        appliedDark = isDark
    }

    companion object {
        // Basemap IDs only; retain original light expressions and leave mesh overlays, sources and fonts untouched.
        private val commonRoads = listOf(
            "motorway_link_casing", "service_track_casing", "link_casing", "secondary_tertiary_casing",
            "trunk_primary_casing", "motorway_casing", "path_pedestrian", "motorway_link",
            "service_track", "link", "secondary_tertiary", "trunk_primary", "motorway",
            "major_rail", "major_rail_hatching", "transit_rail", "transit_rail_hatching",
        )
        internal val layerIds = listOf(
            "background", "natural_earth", "park", "park_outline", "landuse_residential",
            "landcover_wood", "landcover_grass", "landcover_ice", "landuse_pitch", "landuse_track",
            "landuse_cemetery", "landuse_hospital", "landuse_school", "water", "landcover_sand",
            "aeroway_fill", "aeroway_runway", "aeroway_taxiway",
            "waterway_tunnel", "waterway_river", "waterway_other", "building", "building-3d",
            "boundary_3", "boundary_2", "boundary_disputed",
            "waterway_line_label", "water_name_point_label", "water_name_line_label",
            "poi_r20", "poi_r7", "poi_r1", "poi_transit", "airport",
            "highway-name-path", "highway-name-minor", "highway-name-major",
            "label_other", "label_village", "label_town", "label_state", "label_city",
            "label_city_capital", "label_country_3", "label_country_2", "label_country_1",
            "tunnel_street_casing", "tunnel_minor", "road_minor_casing", "road_minor",
            "bridge_street_casing", "bridge_street", "bridge_path_pedestrian_casing",
        ) + listOf("tunnel", "road", "bridge").flatMap { prefix -> commonRoads.map { "${prefix}_$it" } }

        internal fun restoredPaint(property: PropertyValue<*>): PropertyValue<*> =
            PaintPropertyValue(
                property.name,
                when {
                    property.isExpression -> checkNotNull(property.expression)
                    property.isValue -> property.value
                    else -> null
                },
            )
    }
}
