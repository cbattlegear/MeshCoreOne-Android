// PortedFrom: MC1/Views/PathEditing/HopNodeMatching.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/PathEditing/PickerNode.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.ui.hoppicker

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.RepeaterResolvable
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.UUID

/** Unified node type for the repeater picker list. */
sealed interface PickerNode {
    data class Contact(val contact: ContactDTO) : PickerNode
    data class Discovered(val node: DiscoveredNodeDTO) : PickerNode

    val id: UUID
        get() = when (this) {
            is Contact -> contact.id
            is Discovered -> node.id
        }

    val displayName: String
        get() = when (this) {
            is Contact -> contact.displayName
            is Discovered -> node.name
        }

    val publicKeyHex: String
        get() = when (this) {
            is Contact -> contact.publicKey.uppercaseHexString()
            is Discovered -> node.publicKey.uppercaseHexString()
        }

    val isRoom: Boolean get() = this is Contact && contact.type == ContactType.ROOM

    val isDiscovered: Boolean get() = this is Discovered

    /** A contact flagged as a user favorite; discovered nodes never are. */
    val isFavorite: Boolean get() = this is Contact && contact.isFavorite

    /** The underlying DTO passed to view-model methods. */
    val underlying: RepeaterResolvable
        get() = when (this) {
            is Contact -> contact
            is Discovered -> node
        }
}

/** Query matching for the shared Add-Hop picker. */
object HopNodeMatching {
    /** Non-empty and every character a hex digit (all-digit names match both branches). */
    fun isHexQuery(query: String): Boolean = SwiftText.isAllHexDigits(query)

    /**
     * Empty query, a case- and diacritic-insensitive name substring (independent of the runtime
     * locale), or for a hex query a public-key hex prefix.
     */
    fun matches(node: PickerNode, query: String): Boolean {
        if (query.isEmpty()) return true
        val nameHit = SwiftText.foldedContains(node.displayName, query, locale = null)
        if (isHexQuery(query)) {
            return nameHit || SwiftText.hasPrefix(SwiftText.lowercased(node.publicKeyHex), SwiftText.lowercased(query))
        }
        return nameHit
    }

    /** Nodes matching [query], preserving order. */
    fun filtered(nodes: List<PickerNode>, query: String): List<PickerNode> =
        if (query.isEmpty()) nodes else nodes.filter { matches(it, query) }
}
