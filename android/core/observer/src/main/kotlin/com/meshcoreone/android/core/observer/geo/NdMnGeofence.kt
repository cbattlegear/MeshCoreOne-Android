// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.geo

import com.meshcoreone.android.core.observer.json.MiniJson

/**
 * The upload geofence: North Dakota and Minnesota. The data is the Prizm server's own `cmd/ingestor/fences/nd-mn.json`
 * (US Census `cb_2023_us_state_5m`, simplified to 0.5 km), copied unchanged so the app and the server make the same call;
 * a point inside either polygon or within 2 km of an edge passes. See `nd-mn.PROVENANCE.md` beside the resource.
 */
object NdMnGeofence {
    const val RESOURCE_PATH = "/com/meshcoreone/android/core/observer/geo/nd-mn.json"

    fun load(bufferMeters: Double = PolygonGeofence.DEFAULT_BUFFER_METERS): PolygonGeofence {
        val stream = NdMnGeofence::class.java.getResourceAsStream(RESOURCE_PATH) ?: error("missing geofence resource $RESOURCE_PATH")
        return PolygonGeofence(GeofenceJson.parse(stream.use { it.reader(Charsets.UTF_8).readText() }), bufferMeters)
    }
}

/** Reads `{"source": ..., "polygons": {"<name>": [[lat, lon], ...], ...}}`. One ring per name; the closing edge is implied. */
object GeofenceJson {
    fun parse(text: String): Map<String, List<GeoPolygon>> {
        val root = MiniJson.parse(text) as? Map<*, *> ?: throw IllegalArgumentException("geofence root must be an object")
        val polygons = root["polygons"] as? Map<*, *> ?: throw IllegalArgumentException("geofence has no 'polygons' object")
        require(polygons.isNotEmpty()) { "geofence has no polygons" }
        return polygons.entries.associate { (name, ring) ->
            val label = name as? String ?: throw IllegalArgumentException("polygon name must be a string")
            label to listOf(ring(label, ring))
        }
    }

    private fun ring(name: String, value: Any?): GeoPolygon {
        val points = value as? List<*> ?: throw IllegalArgumentException("polygon '$name' must be an array of [lat, lon]")
        val vertices = points.mapIndexed { index, point ->
            val pair = point as? List<*>
            val lat = pair?.getOrNull(0) as? Double
            val lon = pair?.getOrNull(1) as? Double
            require(pair != null && pair.size == 2 && lat != null && lon != null) { "polygon '$name' vertex $index is not [lat, lon]" }
            try {
                LatLon(lat, lon)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("polygon '$name' vertex $index: ${e.message}")
            }
        }
        return try { GeoPolygon(vertices) } catch (e: IllegalArgumentException) { throw IllegalArgumentException("polygon '$name': ${e.message}") }
    }
}
