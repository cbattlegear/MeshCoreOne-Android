// PortedFrom: MC1/Views/Map/MC1MapView+Layers.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: text symbols retain endpoint, hop and badge meaning without restricted Apple sprites.
// PortedFrom: MC1/Views/Map/PinSpriteRenderer.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.maps

import com.google.gson.JsonObject
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

private val locationDotLight = listOf("#A19687", "#B68F68", "#D17A47", "#E76740", "#F55E47")
private val locationDotDark = listOf("#B1A695", "#C59E77", "#D8885A", "#ED7C5A", "#F87662")

fun mapMarkerFeature(marker: MapMarker, isDark: Boolean): Feature {
    val color = when (marker.style) {
        MapPinStyle.CONTACT_CHAT -> "#1677FF"
        MapPinStyle.CONTACT_REPEATER, MapPinStyle.REPEATER, MapPinStyle.REPEATER_HOP -> "#1B8A5A"
        MapPinStyle.CONTACT_ROOM -> "#8A3FFC"
        MapPinStyle.REPEATER_RING_BLUE -> "#2255A4"
        MapPinStyle.REPEATER_RING_GREEN -> "#1B8A5A"
        MapPinStyle.REPEATER_RING_WHITE -> "#6B7280"
        MapPinStyle.POINT_A, MapPinStyle.POINT_B, MapPinStyle.CROSSHAIR -> "#D97706"
        MapPinStyle.OBSTRUCTION -> "#B91C1C"
        MapPinStyle.BADGE, MapPinStyle.DROPPED_PIN -> "#C2410C"
        MapPinStyle.LOCATION_FIX -> (if (isDark) locationDotDark else locationDotLight)[
            (marker.hopIndex ?: 0).coerceIn(0, 4)
        ]
        MapPinStyle.LOCATION_FIX_LATEST -> "#FF375F"
    }
    val properties = JsonObject().apply {
        addProperty("label", marker.label.orEmpty())
        addProperty("color", color)
        addProperty("symbol", marker.symbolText)
        addProperty("badgeText", marker.badgeText.orEmpty())
        addProperty("radius", when (marker.style) {
            MapPinStyle.LOCATION_FIX -> 7f
            MapPinStyle.LOCATION_FIX_LATEST -> 11f
            else -> 9f
        })
        addProperty("casing", if (marker.style == MapPinStyle.LOCATION_FIX && isDark) "rgba(255, 255, 255, 0.85)" else "#FFFFFF")
        marker.hopIndex?.let { addProperty("hopIndex", it) }
    }
    return Feature.fromGeometry(
        Point.fromLngLat(marker.position.longitude, marker.position.latitude), properties, marker.id.toString(),
    )
}

val MapMarker.symbolText: String
    get() = when (style) {
        MapPinStyle.POINT_A -> "A"
        MapPinStyle.POINT_B -> "B"
        MapPinStyle.CROSSHAIR -> "+"
        MapPinStyle.OBSTRUCTION -> "!"
        MapPinStyle.REPEATER_HOP, MapPinStyle.REPEATER_RING_WHITE -> hopIndex?.toString() ?: "R"
        MapPinStyle.CONTACT_REPEATER, MapPinStyle.REPEATER,
        MapPinStyle.REPEATER_RING_BLUE, MapPinStyle.REPEATER_RING_GREEN -> "R"
        MapPinStyle.CONTACT_CHAT -> "C"
        MapPinStyle.CONTACT_ROOM -> "#"
        MapPinStyle.BADGE -> ""
        MapPinStyle.DROPPED_PIN -> "+"
        MapPinStyle.LOCATION_FIX -> ""
        MapPinStyle.LOCATION_FIX_LATEST -> "+"
    }

/** Name visibility never suppresses a trace badge or hop/endpoint label. */
val MapMarker.semanticText: String
    get() = listOfNotNull(
        label?.takeIf(String::isNotBlank),
        when (style) {
            MapPinStyle.POINT_A, MapPinStyle.POINT_B, MapPinStyle.REPEATER_HOP,
            MapPinStyle.REPEATER_RING_WHITE, MapPinStyle.CROSSHAIR, MapPinStyle.OBSTRUCTION -> symbolText
            else -> null
        },
        badgeText?.takeIf(String::isNotBlank),
    ).distinct().joinToString(", ")
