// PortedFrom: MC1Services/Sources/MC1Services/RF/RFCalculator.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// (`earthRadiusKm` constant and `distance(from:to:)`, lines ~160/176-190 only).
//
// WP-218 prerequisite, narrowly admitted ahead of WP-212's full RFCalculator: ElevationService's
// `fetchElevations` needs one reusable great-circle distance calculation for its per-sample
// `distanceFromAMeters` (see ElevationService.kt's own header comment, which previously left this
// injected as a placeholder pending exactly this file). RFCalculator's remaining surface - free
// -space path loss, diffraction loss, Fresnel-zone clearance, path analysis, `ElevationSample` -
// stays entirely WP-212's; this file is not a partial/fake RFCalculator stand-in and exposes
// nothing beyond the one constant and one function. A future WP-212 RFCalculator is expected to
// delegate its own `distance(from:to:)` to [GeodesicDistance.metersBetween] rather than
// re-deriving the same formula, per the explicit cross-reference admission.
//
// `GeoCoordinate` (this module's existing `content` package type, already used by
// `ElevationService`/`LocationService`) stands in for the source's `CLLocationCoordinate2D` -
// both are a bare latitude/longitude pair in degrees, so no new DTO was introduced.
package com.meshcoreone.android.core.services.rf

import com.meshcoreone.android.core.services.content.GeoCoordinate
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle (Haversine) distance between two [GeoCoordinate]s. Ported 1:1 from
 * `RFCalculator.distance(from:to:)`: identical mean-Earth-radius constant, identical
 * degrees-to-radians conversion, identical `sin²(Δ/2) + cos·cos·sin²(Δ/2)` /
 * `2·atan2(√a, √(1-a))` Haversine formula, identical meters-returning unit contract.
 */
object GeodesicDistance {
    /** Mean Earth radius in kilometers, matching `RFCalculator.earthRadiusKm` exactly. */
    const val EARTH_RADIUS_KM: Double = 6371.0

    /** Great-circle distance between [from] and [to], in meters. */
    fun metersBetween(from: GeoCoordinate, to: GeoCoordinate): Double {
        val earthRadiusMeters = EARTH_RADIUS_KM * 1000.0

        val lat1 = from.latitude * Math.PI / 180.0
        val lat2 = to.latitude * Math.PI / 180.0
        val deltaLat = (to.latitude - from.latitude) * Math.PI / 180.0
        val deltaLon = (to.longitude - from.longitude) * Math.PI / 180.0

        val haversineA = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        val angularDistance = 2 * atan2(sqrt(haversineA), sqrt(1 - haversineA))

        return earthRadiusMeters * angularDistance
    }
}
