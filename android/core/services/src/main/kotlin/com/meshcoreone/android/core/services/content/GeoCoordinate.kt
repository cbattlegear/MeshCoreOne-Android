// PortedFrom: MC1Services/Sources/MC1Services/Extensions/CLLocationCoordinate2D+ValidFix.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Pure coordinate validity check - no CoreLocation/LocationManager/Android location dependency.
// Kept in the `content` package per WP-218's declared write_paths (no new location/ package
// without a manifest amendment - see docs/android/deviations/WP-218.md).
package com.meshcoreone.android.core.services.content

/**
 * A plain latitude/longitude pair, decoupled from any platform location type
 * (Android's `location.Location`, `CLLocationCoordinate2D`) so this validity check stays pure JVM.
 * Callers at the native-adapter boundary (deferred - see docs/android/deviations/WP-218.md)
 * convert their platform location into this type before calling [isValidFix].
 */
data class GeoCoordinate(val latitude: Double, val longitude: Double)

/**
 * Mirrors the Swift `CLLocationCoordinate2D.isValidFix` extension: a fix worth plotting is
 * geographically valid (latitude in [-90, 90], longitude in [-180, 180], and neither NaN) and not
 * the (0, 0) "null island" sentinel that a node without a GPS lock frequently emits.
 */
val GeoCoordinate.isValidFix: Boolean
    get() {
        if (latitude.isNaN() || longitude.isNaN()) return false
        if (latitude < -90.0 || latitude > 90.0) return false
        if (longitude < -180.0 || longitude > 180.0) return false
        return !(latitude == 0.0 && longitude == 0.0)
    }
