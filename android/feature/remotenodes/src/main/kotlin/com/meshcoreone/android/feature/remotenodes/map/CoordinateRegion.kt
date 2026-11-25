// PortedFrom: MC1/Extensions/CLLocationCoordinate2D+BoundingRegion.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Feature-local mirror pending WP-312's core:maps region type (Swift MKCoordinateRegion + boundingRegion).
package com.meshcoreone.android.feature.remotenodes.map

import com.meshcoreone.android.core.model.Coordinate
import kotlin.math.abs

/** South-west / north-east corners of a region (the engine-free half of `toMLNCoordinateBounds`). */
data class CoordinateBounds(val southWest: Coordinate, val northEast: Coordinate)

/** Engine-free camera region (Swift `MKCoordinateRegion`: a center plus a degree span). */
data class CoordinateRegion(
    val center: Coordinate,
    val latitudeDelta: Double,
    val longitudeDelta: Double,
) {
    /** Whether [coordinate] lies within center ± span / 2 on both axes. */
    fun brackets(coordinate: Coordinate): Boolean =
        abs(coordinate.latitude - center.latitude) <= latitudeDelta / 2 &&
            abs(coordinate.longitude - center.longitude) <= longitudeDelta / 2

    /** Plain corner bounds, as Swift's `toMLNCoordinateBounds()` computes them (no wrapping). */
    fun bounds(): CoordinateBounds = CoordinateBounds(
        southWest = Coordinate(center.latitude - latitudeDelta / 2, center.longitude - longitudeDelta / 2),
        northEast = Coordinate(center.latitude + latitudeDelta / 2, center.longitude + longitudeDelta / 2),
    )

    companion object {
        /** Swift `MKCoordinateRegion(center:span:)` with an equal span on both axes. */
        fun around(center: Coordinate, spanDegrees: Double): CoordinateRegion =
            CoordinateRegion(center, spanDegrees, spanDegrees)
    }
}

private const val DEFAULT_PADDING_MULTIPLIER = 1.5
private const val MINIMUM_SPAN_DEGREES = 0.01
private const val MAX_LATITUDE = 90.0
private const val MAX_LONGITUDE_SPAN = 360.0

/**
 * Swift `[CLLocationCoordinate2D].boundingRegion(paddingMultiplier:)`: the box around every
 * coordinate, its spans padded by [paddingMultiplier] with a 0.01° floor, then clamped so
 * center ± span / 2 stays within valid latitude and the longitude span never exceeds 360°.
 * Null for an empty list.
 */
fun List<Coordinate>.boundingRegion(paddingMultiplier: Double = DEFAULT_PADDING_MULTIPLIER): CoordinateRegion? {
    if (isEmpty()) return null
    val minLat = minOf { it.latitude }
    val maxLat = maxOf { it.latitude }
    val minLon = minOf { it.longitude }
    val maxLon = maxOf { it.longitude }
    val center = Coordinate((minLat + maxLat) / 2, (minLon + maxLon) / 2)
    val rawLatDelta = maxOf(MINIMUM_SPAN_DEGREES, (maxLat - minLat) * paddingMultiplier)
    val rawLonDelta = maxOf(MINIMUM_SPAN_DEGREES, (maxLon - minLon) * paddingMultiplier)
    val maxLatDelta = (MAX_LATITUDE - abs(center.latitude)) * 2
    return CoordinateRegion(
        center = center,
        latitudeDelta = minOf(rawLatDelta, maxLatDelta),
        longitudeDelta = minOf(rawLonDelta, MAX_LONGITUDE_SPAN),
    )
}
