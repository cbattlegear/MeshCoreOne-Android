// PortedFrom: MC1/Views/Contacts/ContactsViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Contacts/DiscoveryViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.nodes.model

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.protocol.model.ContactType

/** Segment for the nodes picker. */
enum class NodeSegment(val rawValue: String, val titleRes: Int) {
    FAVORITES("favorites", R.string.l10n_app_contacts_contacts_segment_favorites),
    CONTACTS("contacts", R.string.l10n_app_contacts_contacts_segment_contacts),
    REPEATERS("repeaters", R.string.l10n_app_contacts_contacts_segment_repeaters),
    ROOMS("rooms", R.string.l10n_app_contacts_contacts_segment_rooms),
}

/** Sort order for the nodes and discovery lists. */
enum class NodeSortOrder(val rawValue: String, val titleRes: Int) {
    LAST_HEARD("lastHeard", R.string.l10n_app_contacts_contacts_sort_lastheard),
    NAME("name", R.string.l10n_app_contacts_contacts_sort_name),
    DISTANCE("distance", R.string.l10n_app_contacts_contacts_sort_distance),
    HOPS("hops", R.string.l10n_app_contacts_contacts_sort_hops),
    ;

    /** Distance and hop sorts recompute when the user location sample changes (`DiscoveryView.consumesLocation`). */
    val consumesLocation: Boolean get() = this == DISTANCE || this == HOPS

    companion object {
        /** Distance falls back to last heard while no location is available (`ContactListActions`, `DiscoveryView`). */
        fun effective(order: NodeSortOrder, hasLocation: Boolean): NodeSortOrder =
            if (order == DISTANCE && !hasLocation) LAST_HEARD else order
    }
}

/** Segment for the discovery picker. */
enum class DiscoverSegment(val rawValue: String, val titleRes: Int) {
    ALL("all", R.string.l10n_app_contacts_contacts_discovery_segment_all),
    CONTACTS("contacts", R.string.l10n_app_contacts_contacts_discovery_segment_contacts),
    REPEATERS("repeaters", R.string.l10n_app_contacts_contacts_discovery_segment_repeaters),
    ROOMS("rooms", R.string.l10n_app_contacts_contacts_discovery_segment_rooms),
}

/** `ContactType.localizedName` (contact/repeater/room) used by the detail and confirmation screens. */
fun ContactType.localizedNameRes(): Int = when (this) {
    ContactType.CHAT -> R.string.l10n_app_contacts_contacts_nodekind_contact
    ContactType.REPEATER -> R.string.l10n_app_contacts_contacts_nodekind_repeater
    ContactType.ROOM -> R.string.l10n_app_contacts_contacts_nodekind_room
    ContactType.SENSOR -> R.string.l10n_app_remotenodes_remotenodes_status_sensor_genericsensor
    ContactType.UNKNOWN -> R.string.l10n_app_remotenodes_remotenodes_status_unknown
}
