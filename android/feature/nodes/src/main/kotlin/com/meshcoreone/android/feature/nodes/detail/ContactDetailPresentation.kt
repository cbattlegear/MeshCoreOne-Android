// PortedFrom: MC1/Views/Contacts/ContactDetailView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.nodes.detail

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import kotlin.math.roundToInt

/** Quick actions shown for the contact's role (`ContactActionsSection`/`NodeActionRows`). */
enum class DetailAction { JOIN_ROOM, SEND_MESSAGE, TELEMETRY, SAVED_HISTORY, MANAGEMENT, RADIO_CLI, PING, SHARE_QR, SHARE_VIA_ADVERT, FAVORITE }

/** Non-visual text and availability for the contact detail sections. */
object ContactDetailPresentation {
    private const val ARROW = " \u2192 "

    /** Role-specific quick actions in display order. */
    fun actions(contact: ContactDTO, showFromDirectChat: Boolean, supportsRadioCLI: Boolean = false): List<DetailAction> = buildList {
        when (contact.type) {
            ContactType.ROOM -> {
                add(DetailAction.JOIN_ROOM)
                addAll(nodeActions)
            }
            ContactType.REPEATER -> addAll(nodeActions)
            ContactType.SENSOR -> addAll(listOf(DetailAction.TELEMETRY, DetailAction.SAVED_HISTORY, DetailAction.MANAGEMENT))
            ContactType.UNKNOWN -> Unit
            ContactType.CHAT -> {
                // Send message only when not opened from that chat and not blocked.
                if (!showFromDirectChat && !contact.isBlocked) add(DetailAction.SEND_MESSAGE)
                if (contact.showsRemoteCLI(supportsRadioCLI)) add(DetailAction.RADIO_CLI)
                add(DetailAction.TELEMETRY)
                add(DetailAction.SAVED_HISTORY)
            }
        }
        if (contact.type != ContactType.UNKNOWN) add(DetailAction.SHARE_QR)
        add(DetailAction.SHARE_VIA_ADVERT)
        add(DetailAction.FAVORITE)
    }

    private val nodeActions = listOf(DetailAction.TELEMETRY, DetailAction.SAVED_HISTORY, DetailAction.MANAGEMENT, DetailAction.PING)

    /** `radioDisabled(for:or:)`: radio actions need a ready connection, plus any extra condition. */
    fun radioEnabled(state: DeviceConnectionState, otherwiseDisabled: Boolean = false): Boolean =
        state == DeviceConnectionState.READY && !otherwiseDisabled

    /** Hops joined by arrows, each a resolved name or uppercase hex; "Direct" for an empty path. */
    fun pathDisplayWithNames(contact: ContactDTO, resolveName: (Bytes) -> String?): NodesMessage {
        val byteLength = contact.pathByteLength.toInt()
        val hashSize = contact.pathHashSize.toInt()
        if (byteLength <= 0) return NodesMessage.res(R.string.l10n_app_contacts_contacts_route_direct)
        val path = contact.outPath.prefix(byteLength)
        val hops = (0 until path.size step hashSize).map { start ->
            val hop = path.slice(start, minOf(start + hashSize, path.size))
            resolveName(hop) ?: hop.uppercaseHexString()
        }
        return NodesMessage.Text(hops.joinToString(ARROW))
    }

    fun routeDisplayText(contact: ContactDTO, pathDisplay: NodesMessage): NodesMessage = when {
        contact.isFloodRouted -> NodesMessage.res(R.string.l10n_app_contacts_contacts_route_flood)
        contact.pathHopCount == 0L -> NodesMessage.res(R.string.l10n_app_contacts_contacts_route_direct)
        else -> pathDisplay
    }

    fun networkPathFooter(contact: ContactDTO): NodesMessage = NodesMessage.res(
        if (contact.isFloodRouted) R.string.l10n_app_contacts_contacts_detail_floodfooter else R.string.l10n_app_contacts_contacts_detail_pathfooter,
    )

    fun pathAccessibilityLabel(contact: ContactDTO, pathDisplay: NodesMessage): NodesMessage = when {
        contact.isFloodRouted -> NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_routeflood)
        contact.pathHopCount == 0L -> NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_routedirect)
        else -> NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_routeprefix, pathDisplay)
    }

    /** Copy-route is offered only for a populated (non-flood, non-direct) route. */
    fun isRoutePopulated(contact: ContactDTO): Boolean = !contact.isFloodRouted && contact.pathHopCount > 0

    /** Comma-joined hop prefixes for the copy-route action. */
    fun routeIdPrefixes(contact: ContactDTO): String = contact.pathNodesHex.joinToString(",")

    /** "Hops away" only for a deliberate or discovered out-path, never the passively heard inbound hops. */
    fun showsHopsAway(contact: ContactDTO): Boolean = !contact.isFloodRouted

    /** Reset is disabled while a path command runs or when already flood-routed. */
    fun resetPathDisabled(contact: ContactDTO, isSettingPath: Boolean): Boolean = isSettingPath || contact.isFloodRouted

    /** Info rows shown only when meaningful. */
    fun showsLastHeard(contact: ContactDTO): Boolean = (contact.lastHeardTimestamp ?: 0u) > 0u
    fun showsLastAdvert(contact: ContactDTO): Boolean = contact.lastAdvertTimestamp > 0u
    fun showsUnreadCount(contact: ContactDTO): Boolean = contact.unreadCount > 0

    /** Danger-zone rows: block and clear messages for chats; delete unless it is the V-contact. */
    fun showsBlockAndClear(contact: ContactDTO): Boolean = contact.type == ContactType.CHAT
    fun showsDelete(isVContact: Boolean): Boolean = !isVContact
}

/** Avatar re-encoding bounds (`processAvatarImage`): longest side 512, JPEG quality 0.8. */
object AvatarProcessing {
    const val MAX_DIMENSION = 512.0
    const val JPEG_QUALITY = 0.8

    /** Target pixel size, or null for an empty image; images within bounds keep their size. */
    fun targetSize(width: Double, height: Double): Pair<Int, Int>? {
        val longest = maxOf(width, height)
        if (longest <= 0) return null
        if (longest <= MAX_DIMENSION) return width.roundToInt() to height.roundToInt()
        val scale = MAX_DIMENSION / longest
        return (width * scale).roundToInt() to (height * scale).roundToInt()
    }
}
