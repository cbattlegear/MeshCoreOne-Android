// PortedFrom: MC1Services/Sources/MC1Services/RF/ElevationSample.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.diagnostics.rf

import com.meshcoreone.android.core.model.Coordinate
import java.util.UUID

/**
 * Elevation sample along the path.
 *
 * `CLLocationCoordinate2D` maps to the shared immutable [Coordinate] from `core:model`.
 * Like the Swift struct (Identifiable, not Equatable), each instance gets a fresh [id] and
 * there is no value equality.
 */
class ElevationSample(
    val coordinate: Coordinate,
    /** Meters above sea level. */
    val elevation: Double,
    val distanceFromAMeters: Double,
) {
    val id: UUID = UUID.randomUUID()

    override fun toString(): String =
        "ElevationSample(coordinate=$coordinate, elevation=$elevation, distanceFromAMeters=$distanceFromAMeters)"
}
