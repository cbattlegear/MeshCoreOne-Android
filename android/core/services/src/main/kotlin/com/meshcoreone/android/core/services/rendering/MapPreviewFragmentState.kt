// PortedFrom: MC1Services/Sources/MC1Services/Models/Rendering/MapPreviewFragmentState.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.model.Coordinate

/**
 * Display state for a chat map-location thumbnail. Latitude/longitude are plain doubles (the Swift
 * type avoids `CLLocationCoordinate2D` for the same value-semantics reason). [isDark] and [isOffline]
 * are carried from the build so the view's snapshot key matches the one used to compute [isReady].
 */
class MapPreviewFragmentState(
    val latitude: Double,
    val longitude: Double,
    val isDark: Boolean,
    val isOffline: Boolean,
    val isReady: Boolean,
) {
    val coordinate: Coordinate get() = Coordinate(latitude, longitude)

    private val fields: Array<Any?> get() = arrayOf(latitude, longitude, isDark, isOffline, isReady)
    override fun equals(other: Any?): Boolean = other is MapPreviewFragmentState && swiftFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = swiftFieldsHash(fields)
    override fun toString(): String =
        "MapPreviewFragmentState(latitude=$latitude, longitude=$longitude, isDark=$isDark, isOffline=$isOffline, isReady=$isReady)"
}
