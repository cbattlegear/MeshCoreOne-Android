// PortedFrom: MC1/Views/Map/MC1MapView+Layers.swift@db14559b39d32322b06477c6ae676112f583db50
// AndroidOnly: WP-312 Reconciled from the preserved 3ca57f86 advisory trail draft; prior execution unverified.
package com.meshcoreone.android.core.maps

import com.google.gson.JsonObject
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

internal data class LineLayerSpec(
    val layerId: String,
    val styles: Set<MapLineStyle>,
    val width: Float,
    val dash: List<Float>? = null,
    val casing: Boolean = false,
    val roundJoinAndCap: Boolean = false,
)

internal object MapLineLayerSpecs {
    const val LINES_LAYER = "mesh-lines-layer"
    const val UNTRACED_LAYER = "mesh-untraced-lines"
    const val LOCATION_TRAIL_CASING_LAYER = "mesh-location-trail-casing"
    const val LOCATION_TRAIL_LAYER = "mesh-location-trail"
    const val CASING_OPACITY = 0.8f

    private val untraced = LineLayerSpec(UNTRACED_LAYER, setOf(MapLineStyle.TRACE_UNTRACED), 2f, listOf(1.75f, 3.25f))
    private val trailCasing = LineLayerSpec(
        LOCATION_TRAIL_CASING_LAYER, setOf(MapLineStyle.LOCATION_TRAIL), 5f,
        listOf(0.8f, 1.4f), casing = true, roundJoinAndCap = true,
    )
    private val trail = LineLayerSpec(
        LOCATION_TRAIL_LAYER, setOf(MapLineStyle.LOCATION_TRAIL), 2.5f,
        listOf(1.6f, 2.8f), roundJoinAndCap = true,
    )
    private val generic = LineLayerSpec(
        LINES_LAYER, MapLineStyle.entries.toSet() - untraced.styles - trail.styles, 3f,
    )
    val ordered: List<LineLayerSpec> = listOf(generic, untraced, trailCasing, trail)
    fun layersFor(lineStyleName: String): List<LineLayerSpec> =
        ordered.filter { spec -> spec.styles.any { it.name == lineStyleName } }
}

internal fun mapLineLayer(spec: LineLayerSpec, sourceId: String): LineLayer {
    val filter = Expression.any(*spec.styles.map {
        Expression.eq(Expression.get("lineStyle"), Expression.literal(it.name))
    }.toTypedArray())
    return LineLayer(spec.layerId, sourceId).withFilter(filter).apply {
        setProperties(
            if (spec.casing) lineColor("#FFFFFF") else lineColor(Expression.get("color")),
            lineWidth(spec.width),
            if (spec.casing) lineOpacity(MapLineLayerSpecs.CASING_OPACITY) else lineOpacity(Expression.get("opacity")),
        )
        spec.dash?.let { setProperties(lineDasharray(it.toTypedArray())) }
        if (spec.roundJoinAndCap) setProperties(lineJoin(Property.LINE_JOIN_ROUND), lineCap(Property.LINE_CAP_ROUND))
    }
}

fun mapLineFeature(line: MapLine, color: String): Feature {
    val properties = JsonObject().apply {
        addProperty("color", color)
        addProperty("opacity", line.opacity)
        addProperty("lineStyle", line.style.name)
    }
    return Feature.fromGeometry(
        LineString.fromLngLats(line.points.map { Point.fromLngLat(it.longitude, it.latitude) }),
        properties, line.id,
    )
}
