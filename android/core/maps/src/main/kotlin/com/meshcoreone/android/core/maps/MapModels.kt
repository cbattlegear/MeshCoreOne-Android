// PortedFrom: MC1/Views/Map/MapPoint.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Map/MapLine.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Map/MapStyleSelection.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.maps

import java.util.UUID

enum class MapPinStyle {
    CONTACT_CHAT,
    CONTACT_REPEATER,
    CONTACT_ROOM,
    REPEATER,
    REPEATER_RING_BLUE,
    REPEATER_RING_GREEN,
    REPEATER_RING_WHITE,
    REPEATER_HOP,
    POINT_A,
    POINT_B,
    CROSSHAIR,
    OBSTRUCTION,
    BADGE,
    DROPPED_PIN,
    LOCATION_FIX,
    LOCATION_FIX_LATEST,
}

data class MapMarker(
    val id: UUID,
    val position: GeoPoint,
    val style: MapPinStyle,
    val label: String? = null,
    val clusterable: Boolean = true,
    val hopIndex: Int? = null,
    val badgeText: String? = null,
    /** Spoken name independent of visual labels; null or blank retains [label]. */
    val accessibilityLabel: String? = null,
)

data class MapPointPartition(val clusterable: List<MapMarker>, val fixed: List<MapMarker>)

fun List<MapMarker>.partitionForClustering(clusteringEnabled: Boolean): MapPointPartition =
    if (!clusteringEnabled) MapPointPartition(emptyList(), toList())
    else {
        val partition = partition { it.clusterable }
        MapPointPartition(partition.first, partition.second)
    }

enum class MapLineStyle {
    LOS,
    TRACE_UNTRACED,
    TRACE_WEAK,
    TRACE_MEDIUM,
    TRACE_GOOD,
    MESSAGE_PATH,
    LOCATION_TRAIL,
}

data class MapLine(
    val id: String,
    val points: List<GeoPoint>,
    val style: MapLineStyle,
    val opacity: Float = 1f,
    val pathIndex: Int? = null,
) {
    init {
        require(points.size >= 2)
        require(opacity in 0f..1f)
    }
}

enum class MapStyle(val requiresNetwork: Boolean, val offlineLayer: OfflineLayer?) {
    STANDARD(false, OfflineLayer.BASE),
    SATELLITE(true, null),
    TOPO(false, OfflineLayer.TOPO),
}

enum class OfflineLayer(val maxZoom: Int) { BASE(14), TOPO(17) }

data class MapFocusRequest(val latitude: Double, val longitude: Double) {
    val coordinate: GeoPoint = GeoPoint(latitude, longitude)
}

data class MapProviderAttribution(
    val label: String,
    val legalUri: String,
)

data class MapLayerDescriptor(
    val style: MapStyle,
    val attribution: List<MapProviderAttribution>,
    val supportsSnapshots: Boolean,
    val supportsOfflineRegions: Boolean,
    val availability: MapLayerAvailability = MapLayerAvailability.Available,
)

sealed interface MapLayerAvailability {
    data object Available : MapLayerAvailability
    data class Unavailable(val reason: String) : MapLayerAvailability {
        init { require(reason.isNotBlank()) }
    }
}

data class MapProviderCatalog(
    val engineId: String,
    val layers: Map<MapStyle, MapLayerDescriptor>,
) {
    init {
        require(engineId.isNotBlank())
        require(layers.keys.containsAll(MapStyle.entries))
        layers.forEach { (style, layer) ->
            require(layer.style == style)
            require(layer.attribution.isNotEmpty())
            require(layer.attribution.all { it.label.isNotBlank() && it.legalUri.isNotBlank() })
            if (style.offlineLayer == null || layer.availability is MapLayerAvailability.Unavailable) {
                require(!layer.supportsOfflineRegions)
            }
        }
    }
}
