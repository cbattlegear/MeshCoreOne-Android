// PortedFrom: MC1/Views/Contacts/ContactRowActions.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Contacts/ContactRowView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.nodes.contacts

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.VContactIdentity
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import com.meshcoreone.android.feature.nodes.model.GeoDistance

/** Which swipe side (or the full context menu) a row action set is for. */
enum class RowActionEdge { ALL, LEADING, TRAILING }

enum class RowActionKind { SEND_MESSAGE, DELETE, BLOCK, UNBLOCK, FAVORITE, UNFAVORITE }

data class RowAction(val kind: RowActionKind, val label: NodesMessage, val enabled: Boolean, val destructive: Boolean = false)

/** Menu and swipe actions for a node row; send message is menu-only. */
object ContactRowActions {
    /** Leading swipe completes on a full swipe; trailing never does, so Delete always needs a tap. */
    fun allowsFullSwipe(edge: RowActionEdge): Boolean = edge == RowActionEdge.LEADING

    fun actions(
        contact: ContactDTO,
        edge: RowActionEdge,
        connectionState: DeviceConnectionState,
        selfPublicKey: Bytes?,
        state: ContactsState,
    ): List<RowAction> {
        val isConnected = connectionState == DeviceConnectionState.READY
        // ZephCore V-contact remove is disabled (it would turn off the firmware admin CLI).
        val isVContact = selfPublicKey != null && VContactIdentity.isVContact(contact.publicKey, selfPublicKey)
        val result = mutableListOf<RowAction>()
        if (edge == RowActionEdge.ALL && contact.type == ContactType.CHAT && !contact.isBlocked) {
            result += RowAction(RowActionKind.SEND_MESSAGE, NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_sendmessage), isConnected)
        }
        if (edge != RowActionEdge.LEADING) {
            if (!isVContact) {
                result += RowAction(
                    RowActionKind.DELETE, NodesMessage.res(R.string.l10n_app_contacts_contacts_common_delete),
                    isConnected && !state.isDeletePending(contact.id), destructive = true,
                )
            }
            if (contact.type == ContactType.CHAT) {
                result += if (contact.isBlocked) {
                    RowAction(RowActionKind.UNBLOCK, NodesMessage.res(R.string.l10n_app_contacts_contacts_action_unblock), isConnected)
                } else {
                    RowAction(RowActionKind.BLOCK, NodesMessage.res(R.string.l10n_app_contacts_contacts_action_block), isConnected)
                }
            }
        }
        if (edge != RowActionEdge.TRAILING) {
            val enabled = isConnected && contact.id !in state.togglingFavoriteIds
            result += if (contact.isFavorite) {
                RowAction(RowActionKind.UNFAVORITE, NodesMessage.res(R.string.l10n_app_contacts_contacts_action_unfavorite), enabled)
            } else {
                RowAction(RowActionKind.FAVORITE, NodesMessage.res(R.string.l10n_app_contacts_contacts_row_favorite), enabled)
            }
        }
        return result
    }
}

/** Derived row text (`ContactRowView`). */
object ContactRowPresentation {
    /** Public-key prefix shown on the row at the device's hash size. */
    fun idPrefixHex(contact: ContactDTO, deviceHashSize: Long?): String =
        contact.publicKey.prefix((deviceHashSize ?: 1).toInt()).uppercaseHexString()

    fun routeLabel(contact: ContactDTO, inboundHopCount: Long?): NodesMessage {
        if (!contact.isFloodRouted && contact.pathHopCount == 0L) return NodesMessage.res(R.string.l10n_app_contacts_contacts_route_direct)
        val hops = contact.displayedHopCount(inboundHopCount)
        return if (hops != null) NodesMessage.res(R.string.l10n_app_contacts_contacts_route_hops, hops)
        else NodesMessage.res(R.string.l10n_app_contacts_contacts_route_flood)
    }

    /** Meters to the contact, or null without a user location or contact coordinates; the UI formats it. */
    fun distanceMeters(contact: ContactDTO, userLocation: Coordinate?): Double? {
        if (userLocation == null || !contact.hasLocation) return null
        return GeoDistance.meters(userLocation, Coordinate(contact.latitude, contact.longitude))
    }
}
