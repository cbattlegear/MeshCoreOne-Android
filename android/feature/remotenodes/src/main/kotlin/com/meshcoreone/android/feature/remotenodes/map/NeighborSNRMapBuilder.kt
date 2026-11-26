// PortedFrom: MC1/Views/RemoteNodes/Repeaters/NeighborSNRMapBuilder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.map

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.Neighbour
import com.meshcoreone.android.core.protocol.model.sha256
import com.meshcoreone.android.feature.remotenodes.resolver.NeighborNameResolver
import com.meshcoreone.android.feature.remotenodes.resolver.NodeNameMatchKind
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID

/**
 * Builds the pins, lines and camera region for the neighbour SNR map from data already in hand
 * (Swift `NeighborSNRMapBuilder`). Pure: a function of the session, its neighbours and the resolver
 * inputs.
 */
object NeighborSNRMapBuilder {
    private const val LINE_OPACITY = 1.0
    private const val LINE_ID_PREFIX = "neighbor-"
    private const val UUID_BYTE_COUNT = 16

    data class PlottedNeighbors(
        val points: List<MapPoint>,
        val lines: List<MapLine>,
        val region: CoordinateRegion?,
        val unplottable: List<UnplottableNeighbor>,
    )

    /**
     * A neighbour that could not be placed reliably (ambiguous match, no location, an invalid
     * coordinate, or unresolved), carried with its resolved name and confidence for the list card.
     */
    data class UnplottableNeighbor(
        val neighbor: Neighbour,
        val displayName: String,
        val matchKind: NodeNameMatchKind,
    )

    /** Role namespaces so center / neighbour / badge pins keep stable ids across rebuilds. */
    enum class PinRole(val rawValue: Int) { CENTER(0), NEIGHBOR(1), BADGE(2) }

    /** Swift passes a `CLLocation?` for [userLocation]; [locale] drives the resolver's name tie-break. */
    fun build(
        session: RemoteNodeSessionDTO,
        neighbors: List<Neighbour>,
        contacts: List<ContactDTO>,
        discoveredNodes: List<DiscoveredNodeDTO>,
        userLocation: Coordinate?,
        filter: MapFilterState,
        keyDisplayByteCount: Int,
        locale: Locale = Locale.getDefault(),
    ): PlottedNeighbors {
        val effectiveFilter = filter.sanitized(MapFilterHost.NEIGHBOR_SNR)
        val effectiveContacts = if (effectiveFilter.favoritesOnly) contacts.filter { it.isFavorite } else contacts
        val effectiveDiscovered = if (effectiveFilter.effectiveShowDiscovered) discoveredNodes else emptyList()

        val points = mutableListOf<MapPoint>()
        val lines = mutableListOf<MapLine>()
        val unplottable = mutableListOf<UnplottableNeighbor>()
        val plottedCoordinates = mutableListOf<Coordinate>()

        val centerCoordinate = session.coordinate
        if (centerCoordinate != null) {
            points += pin(stableId(PinRole.CENTER, session.publicKey), centerCoordinate, PinStyle.REPEATER_RING_WHITE, session.name)
            plottedCoordinates += centerCoordinate
        }

        for (neighbor in neighbors) {
            val resolved = NeighborNameResolver.resolveLocated(
                neighbor.publicKeyPrefix, effectiveContacts, effectiveDiscovered, userLocation, locale,
            )
            if (resolved == null) {
                val fallback = NeighborNameResolver.fallbackName(neighbor.publicKeyPrefix, keyDisplayByteCount)
                unplottable += UnplottableNeighbor(neighbor, fallback, NodeNameMatchKind.UNRESOLVED)
                continue
            }
            // Only an exact identity match with a trustworthy coordinate is plotted; the validity
            // guard lives here because DiscoveredNodeDTO.hasLocation checks only non-(0,0).
            val coordinate = resolved.coordinate
            if (resolved.matchKind != NodeNameMatchKind.EXACT || coordinate == null || !coordinate.isValidFix) {
                unplottable += UnplottableNeighbor(neighbor, resolved.displayName, resolved.matchKind)
                continue
            }

            val identityKey = neighbor.publicKeyPrefix
            points += pin(stableId(PinRole.NEIGHBOR, identityKey), coordinate, PinStyle.REPEATER, resolved.displayName)
            plottedCoordinates += coordinate

            // A line and distance/SNR badge need the center as an anchor.
            if (centerCoordinate == null) continue
            lines += MapLine(
                id = stableLineId(identityKey),
                coordinates = listOf(centerCoordinate, coordinate),
                style = MapLineStyle.forSNR(neighbor.snr),
                opacity = LINE_OPACITY,
            )
            points += SnrBadges.snrBadge(stableId(PinRole.BADGE, identityKey), centerCoordinate, coordinate, neighbor.snr)
        }

        return PlottedNeighbors(
            points = points.toList(),
            lines = lines.toList(),
            region = plottedCoordinates.boundingRegion(),
            unplottable = disambiguatingUnresolved(unplottable),
        )
    }

    /** Deterministic UUID from role namespace + identity key bytes (SHA-256 prefix, big-endian). */
    fun stableId(role: PinRole, key: Bytes): UUID {
        val digest = sha256(Bytes.of(role.rawValue) + key).toByteArray()
        val buffer = ByteBuffer.wrap(digest, 0, UUID_BYTE_COUNT)
        return UUID(buffer.long, buffer.long)
    }

    /** Line id stable across filter rebuilds: `neighbor-<uppercase neighbour-role UUID>`. */
    fun stableLineId(key: Bytes): String =
        LINE_ID_PREFIX + stableId(PinRole.NEIGHBOR, key).toString().uppercase(Locale.ROOT)

    /**
     * Distinct unresolved neighbours can share the clamped key prefix and look identical; colliding
     * titles widen to the full stored prefix.
     */
    private fun disambiguatingUnresolved(unplottable: List<UnplottableNeighbor>): List<UnplottableNeighbor> {
        val titleCounts = unplottable
            .filter { it.matchKind == NodeNameMatchKind.UNRESOLVED }
            .groupingBy { it.displayName }
            .eachCount()
        if (titleCounts.values.none { it > 1 }) return unplottable.toList()
        return unplottable.map { item ->
            if (item.matchKind != NodeNameMatchKind.UNRESOLVED || (titleCounts[item.displayName] ?: 0) <= 1) {
                item
            } else {
                item.copy(displayName = item.neighbor.publicKeyPrefix.uppercaseHexString())
            }
        }
    }

    private fun pin(id: UUID, coordinate: Coordinate, style: PinStyle, label: String): MapPoint = MapPoint(
        id = id,
        coordinate = coordinate,
        pinStyle = style,
        label = label,
        isClusterable = true,
        hopIndex = null,
        badge = null,
    )
}
