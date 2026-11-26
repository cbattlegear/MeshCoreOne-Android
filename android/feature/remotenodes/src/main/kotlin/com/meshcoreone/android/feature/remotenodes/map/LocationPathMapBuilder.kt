// PortedFrom: MC1/Views/RemoteNodes/Location/LocationPathMapBuilder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Map/PinSpriteRenderer.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Only PinSpriteRenderer.recencyBucketCount is mirrored here; sprite drawing is map-engine glue (WP-312).
package com.meshcoreone.android.feature.remotenodes.map

import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Turns time-filtered snapshots into the pins and dashed trail for the location history map (Swift
 * `LocationPathMapBuilder`). Precondition: snapshots are in ascending timestamp order; the builder
 * never re-sorts. Swift mints each pin id with `UUID()`; [idFactory] stands in so tests are
 * deterministic.
 */
object LocationPathMapBuilder {
    /** Cap on plotted pins for the inline preview; the full-screen map opts out of decimation. */
    const val MAX_PINS = 60

    /** `PinSpriteRenderer.recencyBucketCount`: the recency ramp a trail's dots are graded into. */
    const val RECENCY_BUCKET_COUNT = 5

    /** Reports farther apart than this are not joined by a trail segment (~4x the nominal cadence). */
    val MAX_CONNECTED_INTERVAL: Duration = Duration.ofHours(1)

    private const val TRAIL_ID_PREFIX = "location-trail-"
    private const val TRAIL_OPACITY = 1.0
    private const val RESERVED_PIN_SLOTS = 2

    /** A fix's report detail for the tap callout; [id] is the source snapshot's id. */
    data class LocationReport(val id: UUID, val timestamp: Instant, val altitude: Double?)

    /**
     * [lines] is the time-ordered trail split at each long silence (empty for a single fix);
     * [reports] maps each pin id to its report, in pin order.
     */
    data class PlottedPath(
        val points: List<MapPoint>,
        val lines: List<MapLine>,
        val reports: Map<UUID, LocationReport>,
    ) {
        companion object {
            val EMPTY = PlottedPath(emptyList(), emptyList(), emptyMap())
        }
    }

    private data class Fix(val snapshot: NodeStatusSnapshotDTO, val coordinate: Coordinate)

    fun build(
        snapshots: List<NodeStatusSnapshotDTO>,
        decimatePins: Boolean = true,
        idFactory: () -> UUID = UUID::randomUUID,
    ): PlottedPath {
        val fixes = snapshots.mapNotNull { snapshot -> snapshot.validCoordinate?.let { Fix(snapshot, it) } }
        if (fixes.isEmpty()) return PlottedPath.EMPTY

        val reports = LinkedHashMap<UUID, LocationReport>()
        val makePin = { fix: Fix, style: PinStyle, recencyBucket: Int? ->
            val point = pin(idFactory(), fix.coordinate, style, recencyBucket)
            reports[point.id] = LocationReport(fix.snapshot.id, fix.snapshot.timestamp, fix.snapshot.altitude)
            point
        }

        // One fix: a lone hero pin, no degenerate one-length trail.
        if (fixes.size < 2) {
            val hero = makePin(fixes[0], PinStyle.LOCATION_FIX_LATEST, null)
            return PlottedPath(listOf(hero), emptyList(), reports.toMap())
        }
        val points = pins(fixes, decimatePins, makePin)
        return PlottedPath(points, segments(fixes), reports.toMap())
    }

    /** The most recent valid fix: snapshots are ascending, so the last valid one. */
    fun latestFix(snapshots: List<NodeStatusSnapshotDTO>): Coordinate? =
        snapshots.lastOrNull { it.validCoordinate != null }?.validCoordinate

    /**
     * Every fix but the latest is a dot graded by recency rank among the plotted dots; the latest is
     * the hero. Interior fixes are decimated by a ceiling stride when [decimate] is set, reserving two
     * slots for the first dot and the hero so the total never exceeds [MAX_PINS].
     */
    private fun pins(fixes: List<Fix>, decimate: Boolean, makePin: (Fix, PinStyle, Int?) -> MapPoint): List<MapPoint> {
        val interior = fixes.subList(1, fixes.size - 1)
        val step = if (decimate && interior.isNotEmpty()) {
            val interiorBudget = MAX_PINS - RESERVED_PIN_SLOTS
            maxOf(1, (interior.size + interiorBudget - 1) / interiorBudget)
        } else {
            1
        }
        val dotFixes = listOf(fixes[0]) + interior.indices.step(step).map { interior[it] }
        val lastDot = dotFixes.size - 1
        val dots = dotFixes.mapIndexed { position, fix -> makePin(fix, PinStyle.LOCATION_FIX, bucket(position, lastDot)) }
        return dots + makePin(fixes[fixes.size - 1], PinStyle.LOCATION_FIX_LATEST, null)
    }

    /**
     * Maps a dot's rank (0 = oldest) onto the ramp so the oldest is bucket 0 and the newest dot the
     * last bucket. Swift's `rounded()` rounds halves away from zero (`Math.round` for these
     * non-negative values), not half-even.
     */
    private fun bucket(position: Int, lastDot: Int): Int {
        if (lastDot <= 0) return 0
        val recency = position.toDouble() / lastDot.toDouble()
        return Math.round(recency * (RECENCY_BUCKET_COUNT - 1)).toInt()
    }

    /** Splits ascending fixes into trail segments at gaps over [MAX_CONNECTED_INTERVAL]. */
    private fun segments(fixes: List<Fix>): List<MapLine> {
        val runs = mutableListOf(mutableListOf(fixes[0].coordinate))
        for (index in 1 until fixes.size) {
            val gap = Duration.between(fixes[index - 1].snapshot.timestamp, fixes[index].snapshot.timestamp)
            if (gap > MAX_CONNECTED_INTERVAL) {
                runs += mutableListOf(fixes[index].coordinate)
            } else {
                runs[runs.size - 1].add(fixes[index].coordinate)
            }
        }
        // A run of a single fix contributes no segment (a one-point line is degenerate).
        return runs.filter { it.size >= 2 }.mapIndexed { index, run ->
            MapLine("$TRAIL_ID_PREFIX$index", run.toList(), MapLineStyle.LOCATION_TRAIL, TRAIL_OPACITY)
        }
    }

    /** The recency bucket rides in `hopIndex`, the point's generic integer channel. */
    private fun pin(id: UUID, coordinate: Coordinate, style: PinStyle, recencyBucket: Int?): MapPoint = MapPoint(
        id = id,
        coordinate = coordinate,
        pinStyle = style,
        label = null,
        isClusterable = false,
        hopIndex = recencyBucket,
        badge = null,
    )
}
