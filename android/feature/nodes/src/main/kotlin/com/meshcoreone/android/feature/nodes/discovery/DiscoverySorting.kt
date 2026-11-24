// PortedFrom: MC1/Views/Contacts/DiscoveryViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.nodes.discovery

import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.model.DiscoverSegment
import com.meshcoreone.android.feature.nodes.model.GeoDistance
import com.meshcoreone.android.feature.nodes.model.NodeSortOrder
import com.meshcoreone.android.core.ui.hoppicker.SwiftText
import java.util.Locale

/** `DiscoveryViewModel.filteredNodes` and its sorts as pure functions. */
internal object DiscoverySorting {
    fun filtered(
        nodes: List<DiscoveredNodeDTO>,
        searchText: String,
        segment: DiscoverSegment,
        sortOrder: NodeSortOrder,
        userLocation: Coordinate?,
        locale: Locale,
    ): List<DiscoveredNodeDTO> {
        val matched = if (searchText.isEmpty()) {
            when (segment) {
                DiscoverSegment.ALL -> nodes
                DiscoverSegment.CONTACTS -> nodes.filter { it.nodeType == ContactType.CHAT }
                DiscoverSegment.REPEATERS -> nodes.filter { it.nodeType == ContactType.REPEATER || it.nodeType == ContactType.SENSOR }
                DiscoverSegment.ROOMS -> nodes.filter { it.nodeType == ContactType.ROOM }
            }
        } else {
            val query = SwiftText.lowercased(searchText)
            nodes.filter { node ->
                SwiftText.foldedContains(node.name, searchText, locale) || SwiftText.hasPrefix(node.publicKey.hexString, query)
            }
        }
        val byName = SwiftText.localizedComparator(locale)
        val nameOrder = Comparator<DiscoveredNodeDTO> { left, right -> byName.compare(left.name, right.name) }
        return when (sortOrder) {
            NodeSortOrder.LAST_HEARD -> matched.sortedByDescending { it.lastHeard }
            NodeSortOrder.NAME -> matched.sortedWith(nameOrder)
            NodeSortOrder.DISTANCE -> byDistanceThenName(matched, userLocation, nameOrder)
            NodeSortOrder.HOPS -> byHopsThenDistance(matched, userLocation, nameOrder)
        }
    }

    /** Decorate-sort-undecorate: located nodes first by distance, unlocated last; ties by name. */
    private fun byDistanceThenName(
        nodes: List<DiscoveredNodeDTO>,
        userLocation: Coordinate?,
        nameOrder: Comparator<DiscoveredNodeDTO>,
    ): List<DiscoveredNodeDTO> {
        userLocation ?: return nodes.sortedWith(nameOrder)
        return nodes.map { it to distance(it, userLocation) }
            .sortedWith { left, right ->
                if (left.second != right.second) left.second.compareTo(right.second) else nameOrder.compare(left.first, right.first)
            }
            .map { it.first }
    }

    private fun byHopsThenDistance(
        nodes: List<DiscoveredNodeDTO>,
        userLocation: Coordinate?,
        nameOrder: Comparator<DiscoveredNodeDTO>,
    ): List<DiscoveredNodeDTO> {
        val decorated = nodes.map { Triple(it, it.displayedHopCount, userLocation?.let { location -> distance(it, location) }) }
        return decorated.sortedWith { left, right ->
            val (_, leftHops, leftDistance) = left
            val (_, rightHops, rightDistance) = right
            when {
                // A nil hop count (flood-routed and never heard via advert) sorts to the bottom.
                (leftHops == null) != (rightHops == null) -> if (leftHops != null) -1 else 1
                leftHops != null && rightHops != null && leftHops != rightHops -> leftHops.compareTo(rightHops)
                leftDistance != null && rightDistance != null && leftDistance != rightDistance -> leftDistance.compareTo(rightDistance)
                else -> nameOrder.compare(left.first, right.first)
            }
        }.map { it.first }
    }

    private fun distance(node: DiscoveredNodeDTO, userLocation: Coordinate): Double =
        if (node.hasLocation) GeoDistance.meters(Coordinate(node.latitude, node.longitude), userLocation) else Double.POSITIVE_INFINITY
}
