// PortedFrom: MC1/Views/RemoteNodes/Repeaters/NeighborSNRMapView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.map

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.protocol.event.Neighbour
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Current inputs of the neighbour SNR map (Swift `NeighborSNRMapView` stored properties).
 * [keyDisplayByteCount] is captured with the neighbours fetch so map titles and no-location rows use
 * the same hex width.
 */
data class NeighborSNRMapInputs(
    val session: RemoteNodeSessionDTO,
    val neighbors: List<Neighbour>,
    val contacts: List<ContactDTO>,
    val discoveredNodes: List<DiscoveredNodeDTO>,
    val userLocation: Coordinate?,
    val keyDisplayByteCount: Int,
)

/**
 * Screen state. A programmatic camera move sets [cameraRegion] and bumps [cameraRegionVersion]
 * together, because the map ignores a region whose version it already applied.
 */
data class NeighborSNRMapUiState(
    val filter: MapFilterState,
    val plotted: NeighborSNRMapBuilder.PlottedNeighbors? = null,
    val cameraRegion: CoordinateRegion? = null,
    val cameraRegionVersion: Int = 0,
    val isCenteredOnUser: Boolean = false,
    val showingNoLocationList: Boolean = false,
) {
    val unplottable: List<NeighborSNRMapBuilder.UnplottableNeighbor> get() = plotted?.unplottable.orEmpty()

    /** Count for the "%d neighbors not shown" pill and the pushed list's title. */
    val notShownCount: Int get() = unplottable.size

    /** The top pill appears only when some neighbour could not be placed. */
    val showsNoLocationPill: Boolean get() = notShownCount > 0

    /** "Center all" is disabled when nothing is plottable. */
    val isCenterAllEnabled: Boolean get() = plotted?.region != null
}

/**
 * Non-layout logic of Swift `NeighborSNRMapView`: builds on appear and on input/filter changes,
 * fits the camera on appear and when the style loads, and drives the no-location list push. The
 * persisted filter preference (WP-312 `MapFilterPreferences`) is read by the caller and passed in.
 */
class NeighborSNRMapStateHolder(
    private var inputs: NeighborSNRMapInputs,
    initialFilter: MapFilterState,
    private val locale: Locale = Locale.getDefault(),
) {
    private val mutableState = MutableStateFlow(NeighborSNRMapUiState(initialFilter.sanitized(HOST)))
    val state: StateFlow<NeighborSNRMapUiState> = mutableState.asStateFlow()

    /** First appearance builds the content and fits it; later appearances keep what was built. */
    fun onAppear() = mutableState.update { current ->
        if (current.plotted != null) return@update current
        val built = build(current.filter)
        withCameraRegion(current.copy(plotted = built), built.region)
    }

    /** The map drops camera moves until its style loads, so re-issue the fit on that signal. */
    fun onStyleLoaded(loaded: Boolean) = mutableState.update { current ->
        if (loaded) withCameraRegion(current, current.plotted?.region) else current
    }

    /** A filter edit rebuilds the content without refitting the camera. */
    fun onFilterChange(filter: MapFilterState) = mutableState.update { current ->
        val sanitized = filter.sanitized(HOST)
        current.copy(filter = sanitized, plotted = build(sanitized))
    }

    /** Live catalog/telemetry changes replace content without resetting the screen's camera. */
    fun onInputsChange(inputs: NeighborSNRMapInputs, filter: MapFilterState) {
        this.inputs = inputs
        mutableState.update { current ->
            val sanitized = filter.sanitized(HOST)
            val built = build(sanitized)
            val updated = current.copy(filter = sanitized, plotted = built)
            if (current.plotted == null) withCameraRegion(updated, built.region) else updated
        }
    }

    /** Re-fits the camera to the repeater and its plotted neighbours. */
    fun centerAll() = mutableState.update { current ->
        withCameraRegion(current.copy(isCenteredOnUser = false), current.plotted?.region)
    }

    fun onCameraRegionChange(region: CoordinateRegion) = mutableState.update { it.copy(cameraRegion = region) }

    /** The user-location region delivered after a "my location" tap (app-state glue lives outside). */
    fun onUserLocationRegion(region: CoordinateRegion) = mutableState.update { withCameraRegion(it, region) }

    fun onCenteredOnUserChange(isCentered: Boolean) = mutableState.update { it.copy(isCenteredOnUser = isCentered) }

    fun showNoLocationList() = mutableState.update { it.copy(showingNoLocationList = true) }

    fun dismissNoLocationList() = mutableState.update { it.copy(showingNoLocationList = false) }

    private fun build(filter: MapFilterState): NeighborSNRMapBuilder.PlottedNeighbors = NeighborSNRMapBuilder.build(
        session = inputs.session,
        neighbors = inputs.neighbors,
        contacts = inputs.contacts,
        discoveredNodes = inputs.discoveredNodes,
        userLocation = inputs.userLocation,
        filter = filter,
        keyDisplayByteCount = inputs.keyDisplayByteCount,
        locale = locale,
    )

    companion object {
        private val HOST = MapFilterHost.NEIGHBOR_SNR

        /** Navigation title of the map. */
        val TITLE_RESOURCE: Int get() = AppRemoteNodesStrings.remoteNodesStatusNeighborsMapTitle

        /** A null region leaves the camera (and its version) untouched. */
        private fun withCameraRegion(state: NeighborSNRMapUiState, region: CoordinateRegion?): NeighborSNRMapUiState =
            if (region == null) {
                state
            } else {
                state.copy(cameraRegion = region, cameraRegionVersion = state.cameraRegionVersion + 1)
            }
    }
}
