// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.location

/** A user-defined circle (home, work) inside which no position may leave the phone. */
data class PrivacyZone(val latitude: Double, val longitude: Double, val radiusMeters: Double = DEFAULT_RADIUS_METERS) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "invalid latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "invalid longitude" }
        require(radiusMeters.isFinite() && radiusMeters > 0.0) { "radius must be positive" }
    }

    companion object { const val DEFAULT_RADIUS_METERS = 300.0 }
}

class PrivacyZones(private val zones: List<PrivacyZone>) {
    /**
     * True when the point, widened by its accuracy circle ([accuracyMeters]), touches any zone. Using the accuracy means a
     * poor fix just outside a zone is withheld rather than risk revealing a position that may really be inside it.
     */
    fun covers(latitude: Double, longitude: Double, accuracyMeters: Double = 0.0): Boolean {
        val slack = if (accuracyMeters.isFinite() && accuracyMeters > 0.0) accuracyMeters else 0.0
        return zones.any { GeoMath.haversineMeters(latitude, longitude, it.latitude, it.longitude) <= it.radiusMeters + slack }
    }
}

/** Where the current zones come from (settings storage). Read on every record so edits apply at once. */
fun interface PrivacyZoneSource { fun zones(): List<PrivacyZone> }
