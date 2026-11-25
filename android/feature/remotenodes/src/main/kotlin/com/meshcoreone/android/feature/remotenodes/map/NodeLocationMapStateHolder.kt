// PortedFrom: MC1/Views/RemoteNodes/Location/NodeLocationMapView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.map

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the full-screen location map shows (Swift `locationMapDestination` arguments). */
data class NodeLocationMapContent(
    val points: List<MapPoint>,
    val lines: List<MapLine>,
    val reports: Map<UUID, LocationPathMapBuilder.LocationReport>,
    val titleResource: Int,
    val initialSelectionId: UUID?,
) {
    companion object {
        /** The full-screen map keeps every report as a tappable pin (no decimation). */
        fun fromSnapshots(
            snapshots: List<NodeStatusSnapshotDTO>,
            initialSelection: UUID?,
            idFactory: () -> UUID = UUID::randomUUID,
        ): NodeLocationMapContent {
            val built = LocationPathMapBuilder.build(snapshots, decimatePins = false, idFactory = idFactory)
            return NodeLocationMapContent(
                points = built.points,
                lines = built.lines,
                reports = built.reports,
                titleResource = AppRemoteNodesStrings.remoteNodesStatusLocationMapTitle,
                initialSelectionId = initialSelection,
            )
        }
    }
}

/** A tapped report pin; [pointId] is the pin's id so tapping another pin re-presents the callout. */
data class SelectedLocationReport(val pointId: UUID, val report: LocationPathMapBuilder.LocationReport) {
    val callout: LocationReportCalloutContent get() = LocationReportCalloutContent.of(report)
}

/**
 * Screen state of the full-screen node location map. A programmatic camera move sets [cameraRegion]
 * and bumps [cameraRegionVersion] together; a programmatic selection likewise pairs
 * [pendingSelectionPointId] with [selectionRequestVersion].
 */
data class NodeLocationMapUiState(
    val hasSnapshotted: Boolean = false,
    val displayPoints: List<MapPoint> = emptyList(),
    val displayLines: List<MapLine> = emptyList(),
    val cameraRegion: CoordinateRegion? = null,
    val cameraRegionVersion: Int = 0,
    val isCenteredOnUser: Boolean = false,
    val hasInitiallyFit: Boolean = false,
    val selectedReport: SelectedLocationReport? = null,
    val pendingSelectionPointId: UUID? = null,
    val selectionRequestVersion: Int = 0,
) {
    /** "Center all" is disabled when nothing is plottable. */
    val isCenterAllEnabled: Boolean get() = displayPoints.isNotEmpty()
}

/** Non-layout logic of Swift `NodeLocationMapView`. */
class NodeLocationMapStateHolder(private val content: NodeLocationMapContent) {
    private val mutableState = MutableStateFlow(NodeLocationMapUiState())
    val state: StateFlow<NodeLocationMapUiState> = mutableState.asStateFlow()

    /** Snapshots the content exactly once per appearance lifecycle so re-renders keep pin ids. */
    fun onAppear() = mutableState.update { current ->
        if (current.hasSnapshotted) {
            current
        } else {
            current.copy(hasSnapshotted = true, displayPoints = content.points, displayLines = content.lines)
        }
    }

    /**
     * Fits once when the style first loads, then resolves the row-tapped snapshot id to its plotted
     * point and issues one selection request.
     */
    fun onStyleLoaded(loaded: Boolean) = mutableState.update { current ->
        if (!loaded || current.hasInitiallyFit) return@update current
        val fitted = fitCamera(current.copy(hasInitiallyFit = true))
        val pointId = initialSelectionPointId(content.reports, content.initialSelectionId) ?: return@update fitted
        fitted.copy(pendingSelectionPointId = pointId, selectionRequestVersion = fitted.selectionRequestVersion + 1)
    }

    fun onPointTap(pointId: UUID) = mutableState.update { current ->
        val report = content.reports[pointId] ?: return@update current
        current.copy(selectedReport = SelectedLocationReport(pointId, report))
    }

    fun onMapTap() = mutableState.update { it.copy(selectedReport = null) }

    /** The user moved the camera: track the region and dismiss any open callout. */
    fun onCameraRegionChange(region: CoordinateRegion) = mutableState.update {
        it.copy(cameraRegion = region, selectedReport = null)
    }

    fun centerAll() = mutableState.update { fitCamera(it.copy(isCenteredOnUser = false)) }

    fun onCenteredOnUserChange(isCentered: Boolean) = mutableState.update { it.copy(isCenteredOnUser = isCentered) }

    companion object {
        /** Span used when the path resolves to a single fix. */
        const val SINGLE_FIX_SPAN_DELTA = 0.05

        /** Small margin around the multi-fix box (the map already insets by the safe-area padding). */
        const val PATH_BOUNDING_PADDING_MULTIPLIER = 1.3

        /** The camera fit for [points]: a fixed span for one, a padded box for several, else null. */
        fun fitRegion(points: List<MapPoint>): CoordinateRegion? {
            val coordinates = points.map { it.coordinate }
            if (coordinates.size == 1) return CoordinateRegion.around(coordinates[0], SINGLE_FIX_SPAN_DELTA)
            return coordinates.boundingRegion(PATH_BOUNDING_PADDING_MULTIPLIER)
        }

        /** The plotted point whose report came from [snapshotId], or null. */
        fun initialSelectionPointId(
            reports: Map<UUID, LocationPathMapBuilder.LocationReport>,
            snapshotId: UUID?,
        ): UUID? {
            if (snapshotId == null) return null
            return reports.entries.firstOrNull { it.value.id == snapshotId }?.key
        }

        private fun fitCamera(state: NodeLocationMapUiState): NodeLocationMapUiState {
            val region = fitRegion(state.displayPoints) ?: return state
            return state.copy(cameraRegion = region, cameraRegionVersion = state.cameraRegionVersion + 1)
        }
    }
}
