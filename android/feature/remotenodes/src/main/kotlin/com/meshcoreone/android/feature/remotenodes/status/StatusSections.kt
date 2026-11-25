// PortedFrom: MC1/Views/RemoteNodes/SharedNodeStatusViews.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/RemoteNodes/Repeaters/RepeaterStatusContent.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.status

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.NodeLocationFix
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText

/** What a disclosure section body shows (the branch order of the Swift section views). */
sealed interface SectionBody {
    data object Loading : SectionBody
    data class Error(val message: RemoteNodesText) : SectionBody
    /** A plain secondary message such as "no sensor data". */
    data class Message(val text: Int) : SectionBody
    data object Content : SectionBody
    data object Empty : SectionBody
}

/** The telemetry body's content layout once a response is in. */
sealed interface TelemetryLayout {
    data class Grouped(val groups: List<TelemetryChannelGroup>) : TelemetryLayout
    data class Flat(val rows: List<TelemetryRowModel>) : TelemetryLayout
}

object StatusSections {
    /** Swift: expanding a section loads it once, unless loaded or already loading. */
    fun shouldLoadOnExpand(isExpanded: Boolean, isLoaded: Boolean, isLoading: Boolean): Boolean =
        isExpanded && !isLoaded && !isLoading

    /** `NodeStatusSection`: spinner or error only until a first status arrives. */
    fun statusBody(state: NodeStatusState): SectionBody {
        val error = state.statusSectionError
        return when {
            state.isLoadingStatus && state.status == null -> SectionBody.Loading
            error != null && state.status == null -> SectionBody.Error(error)
            state.status != null -> SectionBody.Content
            else -> SectionBody.Empty
        }
    }

    /** `NodeTelemetryDisclosureSection` body. */
    fun telemetryBody(state: NodeStatusState): SectionBody {
        val error = state.telemetrySectionError
        return when {
            state.isLoadingTelemetry -> SectionBody.Loading
            error != null && state.telemetry == null -> SectionBody.Error(error)
            state.telemetry != null && state.cachedDataPoints.isEmpty() ->
                SectionBody.Message(AppRemoteNodesStrings.remoteNodesStatusNoSensorData)
            state.telemetry != null -> SectionBody.Content
            else -> SectionBody.Message(AppRemoteNodesStrings.remoteNodesStatusNoTelemetryData)
        }
    }

    /** Grouped per channel when several channels report, else one flat list. */
    fun telemetryLayout(state: NodeStatusState): TelemetryLayout = if (state.hasMultipleChannels) {
        TelemetryLayout.Grouped(state.groupedDataPoints)
    } else {
        TelemetryLayout.Flat(telemetryRows(state.cachedDataPoints, state.ocvValues))
    }

    /** The "View on Map" route for the live fix, labelled with the session name. */
    fun locationRoute(state: NodeStatusState): NodeStatusRoute.LocationMap? =
        state.currentLocationFix?.let { fix: NodeLocationFix -> NodeStatusRoute.LocationMap(fix, state.session?.name) }

    /** `OwnerInfoSection` body; content shows the text (or "no owner info") and the firmware row. */
    fun ownerInfoBody(state: RepeaterStatusState): SectionBody {
        val error = state.ownerInfoError
        return when {
            state.isLoadingOwnerInfo -> SectionBody.Loading
            error != null -> SectionBody.Error(error)
            else -> SectionBody.Content
        }
    }

    /** Owner text, or the "no owner info" placeholder when absent or empty. */
    fun ownerInfoText(state: RepeaterStatusState): RemoteNodesText =
        state.ownerInfo?.takeIf { it.isNotEmpty() }?.let { RemoteNodesText.Verbatim(it) }
            ?: RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesStatusNoOwnerInfo)

    /** `NeighborsSection` body; discovery suppresses the spinner, error and empty states. */
    fun neighborsBody(state: RepeaterStatusState): SectionBody {
        val error = state.neighborsSectionError
        return when {
            state.isLoadingNeighbors && !state.isDiscovering -> SectionBody.Loading
            error != null && !state.isDiscovering -> SectionBody.Error(error)
            state.neighbors.isEmpty() && !state.isDiscovering ->
                SectionBody.Message(AppRemoteNodesStrings.remoteNodesStatusNoNeighbors)
            else -> SectionBody.Content
        }
    }

    /** The neighbours reload button spins only for a manual load, not during discovery. */
    fun neighborsReloadSpinning(state: RepeaterStatusState): Boolean = state.isLoadingNeighbors && !state.isDiscovering

    /** Discover button label: countdown while discovering, else "Discover neighbors". */
    fun discoverButtonText(state: RepeaterStatusState): RemoteNodesText = if (state.isDiscovering) {
        RemoteNodesText.resource(
            com.meshcoreone.android.core.l10n.R.string.l10n_app_remotenodes_remotenodes_status_discoveringseconds,
            state.discoverySecondsRemaining,
        )
    } else {
        RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesStatusDiscoverNeighbors)
    }
}
