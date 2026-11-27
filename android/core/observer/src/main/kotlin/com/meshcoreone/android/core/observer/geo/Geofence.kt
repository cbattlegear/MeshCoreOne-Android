// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.geo

/** Where uploading is allowed. [contains] includes the implementation's outside buffer (2 km for ND/MN, as the server applies it). */
interface Geofence {
    fun contains(latitude: Double, longitude: Double): Boolean
}

data class LatLon(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "invalid latitude $latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "invalid longitude $longitude" }
    }
}

/** A simple (non self-intersecting) ring; the closing edge is implied. */
class GeoPolygon(vertices: List<LatLon>) {
    val vertices: List<LatLon> = vertices.toList()

    init { require(this.vertices.size >= 3) { "a polygon needs at least 3 vertices" } }
}
