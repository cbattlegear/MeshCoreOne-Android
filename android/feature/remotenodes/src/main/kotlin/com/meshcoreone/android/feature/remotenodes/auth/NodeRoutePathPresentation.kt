// PortedFrom: MC1/Views/RemoteNodes/NodeRoutePathSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.auth

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.resolver.NeighborNameResolver
import com.meshcoreone.android.feature.remotenodes.resolver.NodeNameMatchKind
import com.meshcoreone.android.feature.remotenodes.resolver.ResolvedPathHop
import java.util.Locale

/** Read-only route display (Swift `NodeRoutePathSection`): "no route set", or a summary with hop names. */
sealed interface NodeRoutePath {
    /** Flood-routed contact: no stored path. */
    data object NoRoute : NodeRoutePath

    /** [summary] is the collapsed line; [hops] is empty when the host holds no names to resolve. */
    data class Route(val summary: RemoteNodesText, val accessibilityLabel: RemoteNodesText, val hops: List<HopRow>) : NodeRoutePath
}

/** One expanded hop; [possibleMatch] shows the short-prefix indicator. */
data class HopRow(val hex: String, val name: RemoteNodesText, val possibleMatch: Boolean)

object NodeRoutePathPresentation {
    fun of(
        contact: ContactDTO,
        contacts: List<ContactDTO>,
        discoveredNodes: List<DiscoveredNodeDTO>,
        userLocation: Coordinate?,
        locale: Locale = Locale.getDefault(),
    ): NodeRoutePath {
        if (contact.isFloodRouted) return NodeRoutePath.NoRoute
        val resolved = NeighborNameResolver.resolvePath(contact.pathHops, contacts, discoveredNodes, userLocation, locale)
        return NodeRoutePath.Route(summary(contact), accessibility(contact), resolved.map(::row))
    }

    /** `A3 → 7F → 42`, or "Direct" for a zero-hop path. */
    fun summary(contact: ContactDTO): RemoteNodesText = if (contact.pathHopCount == 0L) {
        RemoteNodesText.resource(AppContactsStrings.contactsRouteDirect)
    } else {
        RemoteNodesText.Verbatim(contact.pathString)
    }

    fun accessibility(contact: ContactDTO): RemoteNodesText = if (contact.pathHopCount == 0L) {
        RemoteNodesText.resource(AppContactsStrings.contactsDetailRouteDirect)
    } else {
        RemoteNodesText.resource(R.string.l10n_app_contacts_contacts_detail_routeprefix, contact.pathString)
    }

    private fun row(hop: ResolvedPathHop): HopRow {
        val resolution = hop.resolution
        val name = resolution?.let { RemoteNodesText.Verbatim(it.displayName) }
            ?: RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesAuthPathHopUnknown)
        return HopRow(hop.hex, name, resolution?.matchKind == NodeNameMatchKind.FALLBACK)
    }
}
