// PortedFrom: MC1/Views/Tools/LineOfSight/LineOfSightViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Tools/LineOfSight/TerrainProfileSectionView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Tools/LineOfSight/PointsSummarySectionView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Tools/LineOfSight/PointRowButtonsView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.los

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.UUID

/**
 * Immutable snapshot of the line-of-sight screen state (the Swift view model's stored
 * properties) plus the derived values its views read. Results are modelled terrain clearance
 * from elevation data, never measured reception.
 */
data class LineOfSightState(
    val pointA: SelectedPoint? = null,
    val pointB: SelectedPoint? = null,
    val relocatingPoint: PointID? = null,
    val shouldAutoZoomOnNextResult: Boolean = false,
    /** Operating frequency in MHz; commit edits with `commitFrequencyChange()`. */
    val frequencyMHz: Double = DEFAULT_FREQUENCY_MHZ,
    /** Refraction k-factor; changing it re-analyzes with the cached profile. */
    val refractionK: Double = DEFAULT_REFRACTION_K,
    val repeatersWithLocation: List<ContactDTO> = emptyList(),
    /** The active repeater (null when not in use). */
    val repeaterPoint: RepeaterPoint? = null,
    val analysisStatus: AnalysisStatus = AnalysisStatus.Idle,
    val isAnalyzing: Boolean = false,
    val elevationProfile: List<ElevationSample> = emptyList(),
    /** Samples for the primary segment (A->B, or A->R with a repeater). */
    val profileSamples: List<ProfileSample> = emptyList(),
    /** Samples for R->B (empty without a repeater). */
    val profileSamplesRB: List<ProfileSample> = emptyList(),
    /** A->R profile for an off-path repeater. */
    val elevationProfileAR: List<ElevationSample> = emptyList(),
    /** R->B profile for an off-path repeater, distances offset to continue from A->R. */
    val elevationProfileRB: List<ElevationSample> = emptyList(),
    /** Whether any point elevation fetch failed (sea-level fallback in use). */
    val elevationFetchFailed: Boolean = false,
) {
    val canAnalyze: Boolean get() = pointA?.groundElevation != null && pointB?.groundElevation != null

    /** Selection role of each located repeater (rebuilt whenever points or repeaters change). */
    val selectionState: Map<UUID, LOSRepeaterSelectionInfo>
        get() {
            val pointAContactId = pointA?.contact?.id
            val pointBContactId = pointB?.contact?.id
            return repeatersWithLocation.associate { contact ->
                val selectedAs = when (contact.id) {
                    pointAContactId -> PointID.POINT_A
                    pointBContactId -> PointID.POINT_B
                    else -> null
                }
                contact.id to LOSRepeaterSelectionInfo(selectedAs)
            }
        }

    /** Repeater row visible: a repeater exists, or the add-repeater placeholder applies. */
    val shouldShowRepeaterRow: Boolean get() = repeaterPoint != null || shouldShowRepeaterPlaceholder

    /** Direct result is marginal or worse and has obstruction points for `addRepeater()` to use. */
    val shouldShowRepeaterPlaceholder: Boolean
        get() {
            val result = (analysisStatus as? AnalysisStatus.Result)?.result ?: return false
            return result.clearanceStatus != ClearanceStatus.CLEAR && result.obstructionPoints.isNotEmpty()
        }

    /** On-path: interpolated from the cached A->B profile; off-path: the fetched elevation. */
    val repeaterGroundElevation: Double?
        get() {
            val repeater = repeaterPoint ?: return null
            return if (repeater.isOnPath) elevationAt(repeater.pathFraction) else repeater.groundElevation
        }

    /** Terrain-chart fraction: on-path uses pathFraction; off-path uses A->R over A->R->B distance. */
    val repeaterVisualizationPathFraction: Double?
        get() {
            val repeater = repeaterPoint ?: return null
            if (repeater.isOnPath) return repeater.pathFraction
            val arLast = elevationProfileAR.lastOrNull() ?: return null
            val rbLast = elevationProfileRB.lastOrNull() ?: return null
            if (!(rbLast.distanceFromAMeters > 0)) return null
            return arLast.distanceFromAMeters / rbLast.distanceFromAMeters
        }

    /** A->R distance for an off-path relay result (null on-path or without one). */
    val segmentARDistanceMeters: Double? get() = offPathRelayResult?.segmentAR?.distanceMeters

    /** R->B distance for an off-path relay result (null on-path or without one). */
    val segmentRBDistanceMeters: Double? get() = offPathRelayResult?.segmentRB?.distanceMeters

    private val offPathRelayResult: RelayPathAnalysisResult?
        get() {
            val repeater = repeaterPoint ?: return null
            if (repeater.isOnPath) return null
            return (analysisStatus as? AnalysisStatus.RelayResult)?.result
        }

    /** Cached A->B profile, or A->R + R->B (junction sample once) for a loaded off-path repeater. */
    val terrainElevationProfile: List<ElevationSample>
        get() {
            val repeater = repeaterPoint
            if (repeater == null || repeater.isOnPath) return elevationProfile
            if (elevationProfileAR.isEmpty() || elevationProfileRB.isEmpty()) return elevationProfile
            return elevationProfileAR + elevationProfileRB.drop(1)
        }

    val hasAnalysisResult: Boolean
        get() = analysisStatus is AnalysisStatus.Result || analysisStatus is AnalysisStatus.RelayResult

    val isRelocating: Boolean get() = relocatingPoint != null

    /** Analyze button and RF settings appear before any result once both elevations are known. */
    val showsPreAnalysisControls: Boolean get() = canAnalyze && !hasAnalysisResult

    /** "Long-press to choose points" hint while either endpoint is missing. */
    val showsLongPressHint: Boolean get() = pointA == null || pointB == null

    /** Only an on-path repeater can be dragged along the terrain profile. */
    val isRepeaterDragEnabled: Boolean get() = repeaterPoint?.isOnPath == true

    /** Inputs for the terrain chart exactly as `TerrainProfileSectionView` wires them. */
    val terrainChartInput: TerrainProfileChartInput
        get() = TerrainProfileChartInput(
            elevationProfile = terrainElevationProfile,
            profileSamples = profileSamples,
            profileSamplesRB = profileSamplesRB,
            repeaterPathFraction = repeaterVisualizationPathFraction,
            repeaterHeight = repeaterPoint?.additionalHeight,
            segmentARDistanceMeters = segmentARDistanceMeters,
            segmentRBDistanceMeters = segmentRBDistanceMeters,
        )

    /** Coordinates the map camera fits for `centerOnAllRepeaters()`. */
    val repeaterCoordinates: List<Coordinate> get() = repeatersWithLocation.map { Coordinate(it.latitude, it.longitude) }

    /** Coordinate of a point row (share/copy/open actions). */
    fun coordinateOf(point: PointID): Coordinate? = when (point) {
        PointID.POINT_A -> pointA?.coordinate
        PointID.POINT_B -> pointB?.coordinate
        PointID.REPEATER -> repeaterPoint?.coordinate
    }

    /** The relocate button is disabled while a different point is relocating. */
    fun isRelocateEnabled(point: PointID): Boolean = relocatingPoint == null || relocatingPoint == point

    /** Interpolated ground elevation at [pathFraction] (clamped to 0...1); null with fewer than two samples. */
    fun elevationAt(pathFraction: Double): Double? {
        val (lower, upper, t) = interpolationIndices(pathFraction) ?: return null
        val lowerElevation = elevationProfile[lower].elevation
        val upperElevation = elevationProfile[upper].elevation
        return lowerElevation + t * (upperElevation - lowerElevation)
    }

    /** Interpolated coordinate at [pathFraction] (clamped to 0...1); null with fewer than two samples. */
    fun coordinateAt(pathFraction: Double): Coordinate? {
        val (lowerIndex, upperIndex, t) = interpolationIndices(pathFraction) ?: return null
        val lower = elevationProfile[lowerIndex].coordinate
        val upper = elevationProfile[upperIndex].coordinate
        return Coordinate(
            latitude = lower.latitude + t * (upper.latitude - lower.latitude),
            longitude = lower.longitude + t * (upper.longitude - lower.longitude),
        )
    }

    private fun interpolationIndices(pathFraction: Double): Triple<Int, Int, Double>? {
        if (elevationProfile.size < 2) return null
        val clamped = pathFraction.swiftClamped(0.0, 1.0)
        val index = clamped * (elevationProfile.size - 1).toDouble()
        val lowerIndex = index.toInt()
        val upperIndex = minOf(lowerIndex + 1, elevationProfile.size - 1)
        return Triple(lowerIndex, upperIndex, index - lowerIndex.toDouble())
    }

    companion object {
        const val DEFAULT_FREQUENCY_MHZ: Double = 906.0
        const val DEFAULT_REFRACTION_K: Double = 1.0
        private const val KHZ_PER_MHZ: Double = 1000.0

        /** Device frequency is stored in kHz; analysis works in MHz. */
        fun frequencyMHzFromDeviceKHz(deviceFrequencyKHz: UInt): Double = deviceFrequencyKHz.toDouble() / KHZ_PER_MHZ

        /** `loadRepeaters()` keeps repeaters that report a valid location fix. */
        fun isLocatedRepeater(contact: ContactDTO): Boolean = contact.hasLocation && contact.type == ContactType.REPEATER
    }
}
