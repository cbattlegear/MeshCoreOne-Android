// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.geo

import com.meshcoreone.android.core.observer.location.GeoMath
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Point-in-polygon plus distance-to-edge, on a local equirectangular projection centred on the query point (x = east,
 * y = north, in metres). Over the few hundred kilometres a state spans the projection error near the query point is far
 * below the 2 km buffer; edges far from the point are only ever used as "far", so their distortion does not matter.
 *
 * A point is [contains]ed when it is inside a polygon or within [bufferMeters] outside its edge. For each region the
 * signed distance is negative inside (depth to the nearest edge) and positive outside. Regions are only names for the
 * polygons (the server, not the app, decides ND or MN from the position, so no region is reported).
 */
class PolygonGeofence(
    regions: Map<String, List<GeoPolygon>>,
    val bufferMeters: Double = DEFAULT_BUFFER_METERS,
) : Geofence {
    private val regions: Map<String, List<GeoPolygon>> = regions.mapValues { it.value.toList() }

    init { require(bufferMeters.isFinite() && bufferMeters >= 0.0) { "buffer must be >= 0" } }

    override fun contains(latitude: Double, longitude: Double): Boolean = nearestRegion(latitude, longitude) != null

    /** Negative inside, positive outside, in metres; null for an unknown region or an invalid point. */
    fun signedDistanceMeters(region: String, latitude: Double, longitude: Double): Double? {
        if (!validPoint(latitude, longitude)) return null
        val polygons = regions[region] ?: return null
        return polygons.minOf { signedDistance(it, latitude, longitude) }
    }

    private fun nearestRegion(latitude: Double, longitude: Double): Pair<String, Double>? {
        if (!validPoint(latitude, longitude)) return null
        return regions.entries
            .map { (name, polygons) -> name to polygons.minOf { signedDistance(it, latitude, longitude) } }
            .filter { it.second <= bufferMeters }
            .minByOrNull { it.second }
    }

    private fun validPoint(lat: Double, lon: Double) =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    private fun signedDistance(polygon: GeoPolygon, lat0: Double, lon0: Double): Double {
        val kx = cos(Math.toRadians(lat0)) * GeoMath.EARTH_RADIUS_METERS * Math.PI / 180.0
        val ky = GeoMath.EARTH_RADIUS_METERS * Math.PI / 180.0
        val points = polygon.vertices.map { v -> Pair(wrap(v.longitude - lon0) * kx, (v.latitude - lat0) * ky) }
        var inside = false
        var nearest = Double.MAX_VALUE
        for (i in points.indices) {
            val (ax, ay) = points[i]
            val (bx, by) = points[(i + 1) % points.size]
            if ((ay > 0) != (by > 0) && 0.0 < (bx - ax) * (0.0 - ay) / (by - ay) + ax) inside = !inside
            nearest = minOf(nearest, distanceToSegment(ax, ay, bx, by))
        }
        return if (inside) -nearest else nearest
    }

    /** Distance from the origin to segment a-b. */
    private fun distanceToSegment(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val lengthSq = dx * dx + dy * dy
        val t = if (lengthSq == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy).div(lengthSq).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy)
    }

    private fun wrap(deltaDegrees: Double): Double = ((deltaDegrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    companion object { const val DEFAULT_BUFFER_METERS = 2_000.0 }
}
