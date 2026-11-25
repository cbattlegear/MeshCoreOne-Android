// PortedFrom: MC1Services/Sources/MC1Services/Simulator/MockDataProvider+NodeStatus.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.UUID

/**
 * The node whose seeded location history the demo showcases: Hannah Lee, a direct chat contact, reached via
 * Contacts, Hannah Lee, then Saved History, which pushes the offline telemetry overview with the location
 * list and map.
 */
val MockDataProvider.locationHistoryNodePublicKey: Bytes get() = mockPublicKey(80u)

private data class TrackFix(val minutesAgo: Double, val latitude: Double, val longitude: Double, val altitude: Double?)

private const val MINUTES_PER_DAY = 1440.0
private const val START_MILLIVOLTS = 4050
private const val END_MILLIVOLTS = 3860

// (minutesAgo, latitude, longitude, altitudeMeters), oldest first. Four outings dated so the time filter has
// content to add and drop. A minute is 1440 per day, so the leading values place each outing a set number of
// days back.
private val track = listOf(
    // ~180 days ago: reachable only under the All filter.
    TrackFix(180 * MINUTES_PER_DAY + 30, 37.7690, -122.4830, 30.0),
    TrackFix(180 * MINUTES_PER_DAY + 15, 37.7695, -122.4800, 35.0),
    TrackFix(180 * MINUTES_PER_DAY, 37.7700, -122.4770, 40.0),
    // ~55 days ago: reachable under 3 Months and All.
    TrackFix(55 * MINUTES_PER_DAY + 30, 37.7585, -122.4270, 25.0),
    TrackFix(55 * MINUTES_PER_DAY + 15, 37.7600, -122.4255, 28.0),
    TrackFix(55 * MINUTES_PER_DAY, 37.7615, -122.4240, 32.0),
    // ~14 days ago: reachable under Month, 3 Months, and All.
    TrackFix(14 * MINUTES_PER_DAY + 30, 37.7980, -122.4650, 50.0),
    TrackFix(14 * MINUTES_PER_DAY + 15, 37.7995, -122.4620, 58.0),
    TrackFix(14 * MINUTES_PER_DAY, 37.8010, -122.4590, 64.0),
    // Today: a morning loop, then a 2.5 h pause, then an afternoon walk that ends at Hannah's advertised spot.
    TrackFix(300.0, 37.7350, -122.4770, 55.0),
    TrackFix(285.0, 37.7340, -122.4780, 58.0),
    TrackFix(270.0, 37.7330, -122.4785, 60.0),
    TrackFix(255.0, 37.7325, -122.4790, 57.0),
    TrackFix(240.0, 37.7320, -122.4795, 54.0),
    // The pause exceeds the connected interval, so the trail breaks here.
    TrackFix(90.0, 37.7260, -122.4800, 48.0),
    TrackFix(72.0, 37.7230, -122.4798, 50.0),
    TrackFix(54.0, 37.7200, -122.4796, 46.0),
    TrackFix(36.0, 37.7180, -122.4795, null), // a fix the node reported without altitude
    TrackFix(18.0, 37.7160, -122.4794, 41.0),
    TrackFix(5.0, 37.7149, -122.4794, 42.0), // newest: Hannah Lee's advertised coordinate
)

/**
 * A seeded run of node status snapshots carrying a plausible GPS track for [locationHistoryNodePublicKey], so
 * the location History list and map render with real content in the simulator and demo mode.
 *
 * The track is shaped to exercise the map and the History time filter:
 * - Four separate outings spread across the past six months, so switching the time range visibly adds and
 *   drops rows and pins: Week shows only today's outing, Month adds the 14-day one, 3 Months adds the 55-day
 *   one, and All shows the 180-day one too.
 * - Each outing's fixes sit within the connected interval, but the weeks-long gaps between outings (and a
 *   2.5 h pause inside today's) exceed it, so the trail draws as separate dashed segments with no phantom
 *   line bridging them.
 * - Altitudes on every fix but one, so the row/callout renders both the present and absent altitude paths.
 * - The newest fix sits at Hannah Lee's advertised coordinate.
 *
 * Timestamps are relative to [now]; [SimulatorConnectionMode] seeds this once per install (skipped when the
 * node already has snapshots) so the track isn't duplicated on every reconnect. Swift mints a random `UUID()`
 * per row; here each id is a name-based UUID of the row's store key (node key plus millisecond, see
 * [NodeStatusSnapshotKey]), so the seed stays deterministic for a fixed clock while rows seeded at different
 * instants never share an id (a backup restore inserting by key can therefore never hit a primary-key clash).
 */
fun MockDataProvider.nodeStatusSnapshots(now: Instant): SnapshotList<NodeStatusSnapshotDTO> {
    val key = locationHistoryNodePublicKey
    return track.mapIndexed { index, fix ->
        // Battery drains gently across the outing so the diagnostics rows read as real.
        val progress = index.toDouble() / maxOf(track.size - 1, 1).toDouble()
        val millivolts = START_MILLIVOLTS - ((START_MILLIVOLTS - END_MILLIVOLTS).toDouble() * progress).toInt()
        val timestamp = now.addingInterval(-fix.minutesAgo * 60)
        NodeStatusSnapshotDTO(
            id = nodeStatusSnapshotID(NodeStatusSnapshotKey.of(key, timestamp)),
            timestamp = timestamp,
            nodePublicKey = key,
            batteryMillivolts = millivolts.toUShortExact(),
            lastSNR = 8.5 - progress * 3,
            lastRSSI = -72,
            noiseFloor = -108,
            uptimeSeconds = (6 * 3600 + index * 1800).toUInt(),
            latitude = fix.latitude,
            longitude = fix.longitude,
            altitude = fix.altitude,
        )
    }.snapshot()
}

/** Number of seeded GPS fixes. */
internal val MockDataProvider.nodeStatusSnapshotCount: Int get() = track.size

/** Name-based (v3) UUID of the snapshot's store key; stable for a key, distinct across keys. */
internal fun nodeStatusSnapshotID(key: NodeStatusSnapshotKey): UUID =
    UUID.nameUUIDFromBytes(Bytes.utf8("mc1-simulator-node-status|${key.milliseconds}|").toByteArray() + key.nodePublicKey.toByteArray())

/** Swift `UInt16(Int)`, which traps outside `0...65535`. */
private fun Int.toUShortExact(): UShort {
    require(this in 0..UShort.MAX_VALUE.toInt()) { "Value $this does not fit UInt16" }
    return toUShort()
}
