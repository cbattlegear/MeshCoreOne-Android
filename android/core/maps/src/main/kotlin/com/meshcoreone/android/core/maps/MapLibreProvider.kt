// AndroidOnly: WP-312 Exact user-approved MapLibre Native and OpenFreeMap provider contract.
package com.meshcoreone.android.core.maps

import android.os.Process

object MapLibreRuntime {
    val isSupported: Boolean
        get() = Process.is64Bit()
}

object MapLibreOpenFreeMap {
    const val ENGINE_ID = "maplibre-native-13.6.1"
    const val STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"

    val attribution = listOf(
        MapProviderAttribution("OpenFreeMap", "https://openfreemap.org/"),
        MapProviderAttribution("© OpenMapTiles", "https://openmaptiles.org/"),
        MapProviderAttribution("© OpenStreetMap contributors", "https://www.openstreetmap.org/copyright"),
    )

    val catalog = MapProviderCatalog(
        engineId = ENGINE_ID,
        layers = mapOf(
            MapStyle.STANDARD to MapLayerDescriptor(
                style = MapStyle.STANDARD,
                attribution = attribution,
                supportsSnapshots = true,
                supportsOfflineRegions = false,
            ),
            MapStyle.SATELLITE to MapLayerDescriptor(
                style = MapStyle.SATELLITE,
                attribution = attribution,
                supportsSnapshots = false,
                supportsOfflineRegions = false,
                availability = MapLayerAvailability.Unavailable("OpenFreeMap does not provide satellite imagery."),
            ),
            MapStyle.TOPO to MapLayerDescriptor(
                style = MapStyle.TOPO,
                attribution = attribution,
                supportsSnapshots = false,
                supportsOfflineRegions = false,
                availability = MapLayerAvailability.Unavailable("No approved OpenFreeMap topographic style is configured."),
            ),
        ),
    )
}
