// PortedFrom: MC1/Views/Contacts/ContactsViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.nodes.contacts

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.model.GeoDistance
import com.meshcoreone.android.feature.nodes.model.NodeSegment
import com.meshcoreone.android.feature.nodes.model.NodeSortOrder
import com.meshcoreone.android.core.ui.hoppicker.SwiftText
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** `ContactsViewModel.filteredContacts` as a pure function over a state snapshot. */
internal object ContactsSorting {
    fun filtered(
        contacts: List<ContactDTO>,
        pendingRemovalIds: Set<UUID>,
        inboundHopByKey: Map<Bytes, Long>,
        searchText: String,
        segment: NodeSegment,
        sortOrder: NodeSortOrder,
        userLocation: Coordinate?,
        locale: Locale,
        referenceTime: Instant,
    ): List<ContactDTO> {
        val visible = contacts.filter { it.id !in pendingRemovalIds }
        val matched = if (searchText.isEmpty()) {
            // Not searching: filter by segment.
            when (segment) {
                NodeSegment.FAVORITES -> visible.filter { it.isFavorite }
                NodeSegment.CONTACTS -> visible.filter { it.type == ContactType.CHAT || it.type == ContactType.UNKNOWN }
                NodeSegment.REPEATERS -> visible.filter { it.type == ContactType.REPEATER || it.type == ContactType.SENSOR }
                NodeSegment.ROOMS -> visible.filter { it.type == ContactType.ROOM }
            }
        } else {
            // Searching shows every type: name or public-key hex prefix.
            val upperQuery = SwiftText.uppercased(searchText)
            visible.filter { contact ->
                SwiftText.foldedContains(contact.displayName, searchText, locale) ||
                    SwiftText.hasPrefix(contact.publicKeyHex, upperQuery)
            }
        }
        return matched.sortedWith(comparator(sortOrder, userLocation, inboundHopByKey, locale, referenceTime))
    }

    private fun comparator(
        order: NodeSortOrder,
        userLocation: Coordinate?,
        inboundHopByKey: Map<Bytes, Long>,
        locale: Locale,
        referenceTime: Instant,
    ): Comparator<ContactDTO> {
        val byName = SwiftText.localizedComparator(locale)
        val distanceThenName = distanceThenName(userLocation, byName)
        return when (order) {
            NodeSortOrder.LAST_HEARD -> compareByDescending { it.nodeListTimestamp(referenceTime) }
            NodeSortOrder.NAME -> Comparator { left, right -> byName.compare(left.displayName, right.displayName) }
            NodeSortOrder.DISTANCE -> distanceThenName
            NodeSortOrder.HOPS -> Comparator { left, right ->
                val leftHops = left.displayedHopCount(inboundHopByKey[left.publicKey])
                val rightHops = right.displayedHopCount(inboundHopByKey[right.publicKey])
                when {
                    // A nil hop count (flood-routed and never heard via advert) sorts to the bottom.
                    (leftHops == null) != (rightHops == null) -> if (leftHops != null) -1 else 1
                    leftHops != null && rightHops != null && leftHops != rightHops -> leftHops.compareTo(rightHops)
                    else -> distanceThenName.compare(left, right)
                }
            }
        }
    }

    /**
     * Located nodes first, then nearest to [userLocation], then by name; name alone when there is
     * no user location, neither node has coordinates, or the distances tie.
     */
    private fun distanceThenName(userLocation: Coordinate?, byName: Comparator<String>) =
        Comparator<ContactDTO> { left, right ->
            if (userLocation != null) {
                if (left.hasLocation != right.hasLocation) return@Comparator if (left.hasLocation) -1 else 1
                if (left.hasLocation) {
                    val leftDistance = GeoDistance.meters(Coordinate(left.latitude, left.longitude), userLocation)
                    val rightDistance = GeoDistance.meters(Coordinate(right.latitude, right.longitude), userLocation)
                    if (leftDistance != rightDistance) return@Comparator leftDistance.compareTo(rightDistance)
                }
            }
            byName.compare(left.displayName, right.displayName)
        }
}
