// PortedFrom: MC1/Views/RemoteNodes/Location/LocationHistorySection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.map

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import java.util.UUID

/**
 * The inline preview's built content (Swift `LocationHistorySection`'s `path` + `cameraVersion`
 * state). [coordinateKey] is the rebuild identity: only a change in the plotted coordinates rebuilds,
 * so a theme or connectivity re-render never re-mints pin ids.
 */
data class LocationPreviewState(
    val coordinateKey: List<Coordinate>? = null,
    val path: LocationPathMapBuilder.PlottedPath? = null,
    val cameraVersion: Int = 0,
)

/** Which report the pushed full-screen map should auto-select (null: open with nothing selected). */
data class LocationMapLaunch(val initialSelection: UUID?)

/**
 * Non-layout logic of the "Location" history section: what the preview plots in full-path versus
 * latest-fix mode, its camera region, the newest-first report list, and the map push requests.
 */
object LocationHistoryPreview {
    /** Span for the single-fix preview, tighter than the full-screen map's fit. */
    const val PREVIEW_SPAN_DELTA = 0.02

    /** Padding around the multi-fix bounding region (the preview carries no floating controls). */
    const val PREVIEW_PADDING_MULTIPLIER = 1.3

    /** Camera placeholder before the first fit; never applied. */
    val FALLBACK_REGION = CoordinateRegion.around(Coordinate(0.0, 0.0), PREVIEW_SPAN_DELTA)

    /** Section header and the "not captured" row argument. */
    val SECTION_TITLE_RESOURCE: Int get() = AppRemoteNodesStrings.remoteNodesHistoryLocationSection

    /** Header of the newest-first report list. */
    val REPORTS_HEADER_RESOURCE: Int get() = AppRemoteNodesStrings.remoteNodesHistoryLocationReportsHeader

    /** The whole path, or just the latest fix; empty when nothing is plottable ("not captured"). */
    fun plottedCoordinates(snapshots: List<NodeStatusSnapshotDTO>, showsFullPath: Boolean): List<Coordinate> =
        if (showsFullPath) {
            snapshots.mapNotNull { it.validCoordinate }
        } else {
            listOfNotNull(LocationPathMapBuilder.latestFix(snapshots))
        }

    /** Single fix: a fixed 0.02° span; several: the bounding region padded by 1.3. */
    fun region(coordinates: List<Coordinate>): CoordinateRegion? {
        val first = coordinates.firstOrNull() ?: return null
        if (coordinates.size == 1) return CoordinateRegion.around(first, PREVIEW_SPAN_DELTA)
        return coordinates.boundingRegion(PREVIEW_PADDING_MULTIPLIER)
    }

    fun currentRegion(snapshots: List<NodeStatusSnapshotDTO>, showsFullPath: Boolean): CoordinateRegion =
        region(plottedCoordinates(snapshots, showsFullPath)) ?: FALLBACK_REGION

    /** Every snapshot with a valid fix, newest first (undecimated: the list is the full record). */
    fun locationReports(snapshots: List<NodeStatusSnapshotDTO>): List<NodeStatusSnapshotDTO> =
        snapshots.filter { it.validCoordinate != null }.asReversed().toList()

    /** Whether [report] is the newest row (the first of [reports]). */
    fun isLatest(report: NodeStatusSnapshotDTO, reports: List<NodeStatusSnapshotDTO>): Boolean =
        report.id == reports.firstOrNull()?.id

    /**
     * Swift `rebuild()` behind `onChange(of: coordinateKey, initial: true)`: an unchanged key keeps
     * [previous]; no plottable fix clears the path without a camera bump; otherwise the full path (or
     * a lone hero pin for latest-fix mode) is built and the camera version bumps.
     */
    fun rebuild(
        previous: LocationPreviewState,
        snapshots: List<NodeStatusSnapshotDTO>,
        showsFullPath: Boolean,
        idFactory: () -> UUID = UUID::randomUUID,
    ): LocationPreviewState {
        val coordinates = plottedCoordinates(snapshots, showsFullPath)
        if (coordinates == previous.coordinateKey) return previous
        val first = coordinates.firstOrNull() ?: return previous.copy(coordinateKey = coordinates, path = null)
        val path = if (showsFullPath) {
            LocationPathMapBuilder.build(snapshots, decimatePins = true, idFactory = idFactory)
        } else {
            singleFixPath(first, idFactory)
        }
        return LocationPreviewState(coordinates, path, previous.cameraVersion + 1)
    }

    /** A row tap opens the map with that report selected. */
    fun rowTap(report: NodeStatusSnapshotDTO): LocationMapLaunch = LocationMapLaunch(report.id)

    /** The expand button opens the map with nothing selected. */
    fun expandTap(): LocationMapLaunch = LocationMapLaunch(null)

    /** Latest-fix preview: one hero pin with no report (the preview is not tappable). */
    private fun singleFixPath(coordinate: Coordinate, idFactory: () -> UUID): LocationPathMapBuilder.PlottedPath =
        LocationPathMapBuilder.PlottedPath(
            points = listOf(
                MapPoint(
                    id = idFactory(),
                    coordinate = coordinate,
                    pinStyle = PinStyle.LOCATION_FIX_LATEST,
                    label = null,
                    isClusterable = false,
                    hopIndex = null,
                    badge = null,
                ),
            ),
            lines = emptyList(),
            reports = emptyMap(),
        )
}
