// PortedFrom: MC1/Views/PathEditing/AddHopPickerView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.ui.hoppicker

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.Locale

enum class HopPickerFilter { ALL, FAVORITES, RECENT, DISCOVERED }

data class HopPickerResults(
    val recent: List<PickerNode> = emptyList(),
    val favorites: List<PickerNode> = emptyList(),
    val contacts: List<PickerNode> = emptyList(),
    val discovered: List<PickerNode> = emptyList(),
    val rooms: List<PickerNode> = emptyList(),
) {
    val isEmpty: Boolean get() = recent.isEmpty() && favorites.isEmpty() && contacts.isEmpty() && discovered.isEmpty() && rooms.isEmpty()
}

fun buildHopPickerResults(
    repeaters: List<ContactDTO>,
    rooms: List<ContactDTO>,
    discovered: List<DiscoveredNodeDTO>,
    recent: List<Bytes>,
    filter: HopPickerFilter,
    query: String,
    locale: Locale,
): HopPickerResults {
    val all = filter == HopPickerFilter.ALL
    val recentKeys = if (all) recent.toSet() else emptySet()
    val contactKeys = if (all) (repeaters + rooms).mapTo(HashSet()) { it.publicKey } else emptySet()
    val byName = SwiftText.localizedComparator(locale, caseInsensitive = true)
    fun contacts(nodes: List<ContactDTO>) = HopNodeMatching.filtered(
        nodes.sortedWith { a, b -> byName.compare(a.displayName, b.displayName) }.map { PickerNode.Contact(it) },
        query,
    )
    return HopPickerResults(
        recent = if (all || filter == HopPickerFilter.RECENT) HopNodeMatching.filtered(
            recent.mapNotNull { key ->
                repeaters.firstOrNull { it.publicKey == key }?.let { PickerNode.Contact(it) }
                    ?: rooms.firstOrNull { it.publicKey == key }?.let { PickerNode.Contact(it) }
                    ?: discovered.firstOrNull { it.publicKey == key }?.let { PickerNode.Discovered(it) }
            }, query,
        ) else emptyList(),
        favorites = if (all || filter == HopPickerFilter.FAVORITES) contacts(repeaters.filter { it.isFavorite && it.publicKey !in recentKeys }) else emptyList(),
        contacts = if (all) contacts(repeaters.filter { !it.isFavorite && it.publicKey !in recentKeys }) else emptyList(),
        discovered = if (all || filter == HopPickerFilter.DISCOVERED) HopNodeMatching.filtered(
            discovered.filter { it.publicKey !in contactKeys && it.publicKey !in recentKeys }
                .sortedWith { a, b -> byName.compare(a.resolvableName, b.resolvableName) }
                .map { PickerNode.Discovered(it) }, query,
        ) else emptyList(),
        rooms = if (all) contacts(rooms.filter { it.publicKey !in recentKeys }) else emptyList(),
    )
}
