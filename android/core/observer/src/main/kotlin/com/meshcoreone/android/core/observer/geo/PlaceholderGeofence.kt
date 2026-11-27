// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.geo

/**
 * PLACEHOLDER geofence for ND and MN built from `placeholder-nd-mn.txt`: hand-simplified, generous, NOT authoritative.
 * Replace the data with US Census cartographic boundaries (cb_2023_us_state_20m or _500k, simplified to about 100 m)
 * before release; see README-PLACEHOLDER.md beside the resource.
 */
object PlaceholderGeofence {
    const val RESOURCE_PATH = "/com/meshcoreone/android/core/observer/geo/placeholder-nd-mn.txt"

    fun load(bufferMeters: Double = PolygonGeofence.DEFAULT_BUFFER_METERS): PolygonGeofence {
        val stream = PlaceholderGeofence::class.java.getResourceAsStream(RESOURCE_PATH)
            ?: error("missing geofence resource $RESOURCE_PATH")
        val text = stream.use { it.reader(Charsets.UTF_8).readText() }
        return PolygonGeofence(GeofenceData.parse(text), bufferMeters)
    }
}
