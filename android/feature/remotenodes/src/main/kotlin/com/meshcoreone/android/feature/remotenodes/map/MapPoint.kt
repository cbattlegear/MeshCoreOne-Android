// PortedFrom: MC1/Views/Map/MapPoint.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Feature-local mirror pending WP-312's core:maps MapPoint (core:maps is still an empty shell).
package com.meshcoreone.android.feature.remotenodes.map

import com.meshcoreone.android.core.model.Coordinate
import java.util.UUID

/** Pin sprite family (Swift `MapPoint.PinStyle`). */
enum class PinStyle {
    CONTACT_CHAT,
    CONTACT_REPEATER,
    CONTACT_ROOM,
    REPEATER,
    REPEATER_RING_BLUE,
    REPEATER_RING_GREEN,
    REPEATER_RING_WHITE,
    REPEATER_HOP,
    POINT_A,
    POINT_B,
    CROSSHAIR,
    OBSTRUCTION,
    BADGE,
    DROPPED_PIN,

    /** A sampled node location report: a small neutral dot threaded onto the history trail. */
    LOCATION_FIX,

    /** The node's most recent location report: the emphasized hero teardrop capping the trail. */
    LOCATION_FIX_LATEST,
}

/**
 * Engine-free map pin (Swift `MapPoint`). Equality compares every field, as the Swift `==` does.
 *
 * Deviation: Swift stores the midpoint badge as a formatted `badgeText: String`. Logic here cannot
 * resolve the localized `dB` unit or read the device locale, so [badge] carries the badge inputs and
 * the renderer formats them with [SnrBadge.text]; equal inputs still mean equal points.
 *
 * [hopIndex] is the point's generic integer channel: repeater-hop styles carry their hop there and
 * location-fix dots their recency bucket.
 */
data class MapPoint(
    val id: UUID,
    val coordinate: Coordinate,
    val pinStyle: PinStyle,
    val label: String?,
    val isClusterable: Boolean,
    val hopIndex: Int?,
    val badge: SnrBadge?,
)
