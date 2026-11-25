// PortedFrom: MC1/Views/Tools/LineOfSight/LineOfSightViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.los

import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import java.util.UUID

/** Identifies which point is being edited or relocated. */
enum class PointID { POINT_A, POINT_B, REPEATER }

/**
 * A repeater point for relay analysis: on-path (slider, uses the cached profile) or off-path
 * (relocated, needs fresh profiles). [pathFraction] is always clamped to 0.05...0.95, matching
 * the Swift initializer and `didSet` clamp.
 */
class RepeaterPoint(
    val coordinate: Coordinate,
    /** Ground elevation at [coordinate] (fetched asynchronously for off-path points). */
    val groundElevation: Double? = null,
    /** Additional height above ground in meters. */
    val additionalHeight: Double = DEFAULT_REPEATER_HEIGHT_METERS,
    /** True when the repeater sits on the A-B path (slider), false when relocated off-path. */
    val isOnPath: Boolean = true,
    pathFraction: Double = 0.5,
) {
    /** Path fraction (meaningful only when [isOnPath]); positions the terrain profile slider. */
    val pathFraction: Double = pathFraction.swiftClamped(MIN_PATH_FRACTION, MAX_PATH_FRACTION)

    fun copy(
        coordinate: Coordinate = this.coordinate,
        groundElevation: Double? = this.groundElevation,
        additionalHeight: Double = this.additionalHeight,
        isOnPath: Boolean = this.isOnPath,
        pathFraction: Double = this.pathFraction,
    ): RepeaterPoint = RepeaterPoint(coordinate, groundElevation, additionalHeight, isOnPath, pathFraction)

    override fun equals(other: Any?): Boolean =
        other is RepeaterPoint &&
            coordinate.latitude == other.coordinate.latitude &&
            coordinate.longitude == other.coordinate.longitude &&
            optionalDoubleEquals(groundElevation, other.groundElevation) &&
            additionalHeight == other.additionalHeight &&
            isOnPath == other.isOnPath &&
            pathFraction == other.pathFraction

    override fun hashCode(): Int = listOf(
        ieeeHash(coordinate.latitude), ieeeHash(coordinate.longitude), groundElevation?.let(::ieeeHash),
        ieeeHash(additionalHeight), isOnPath, ieeeHash(pathFraction),
    ).hashCode()

    override fun toString(): String =
        "RepeaterPoint(coordinate=$coordinate, groundElevation=$groundElevation, additionalHeight=$additionalHeight, " +
            "isOnPath=$isOnPath, pathFraction=$pathFraction)"

    companion object {
        const val MIN_PATH_FRACTION: Double = 0.05
        const val MAX_PATH_FRACTION: Double = 0.95
        const val DEFAULT_REPEATER_HEIGHT_METERS: Double = 10.0
    }
}

/**
 * A selected endpoint. Equality matches the Swift custom `==`: identity, additional height and
 * ground elevation (coordinate and contact are not compared).
 */
class SelectedPoint(
    val coordinate: Coordinate,
    val contact: ContactDTO?,
    val groundElevation: Double? = null,
    val additionalHeight: Double = DEFAULT_POINT_HEIGHT_METERS,
    val id: UUID = UUID.randomUUID(),
) {
    val totalHeight: Double? get() = groundElevation?.let { it + additionalHeight }

    val isLoadingElevation: Boolean get() = groundElevation == null

    /** Contact display name, or the localized "Dropped pin" text the caller resolved from [DROPPED_PIN_LABEL]. */
    fun displayName(droppedPinLabel: String): String = contact?.displayName ?: droppedPinLabel

    fun copy(groundElevation: Double? = this.groundElevation, additionalHeight: Double = this.additionalHeight): SelectedPoint =
        SelectedPoint(coordinate, contact, groundElevation, additionalHeight, id)

    override fun equals(other: Any?): Boolean =
        other is SelectedPoint &&
            id == other.id &&
            additionalHeight == other.additionalHeight &&
            optionalDoubleEquals(groundElevation, other.groundElevation)

    override fun hashCode(): Int = listOf(id, ieeeHash(additionalHeight), groundElevation?.let(::ieeeHash)).hashCode()

    override fun toString(): String =
        "SelectedPoint(id=$id, coordinate=$coordinate, contact=${contact?.id}, groundElevation=$groundElevation, " +
            "additionalHeight=$additionalHeight)"

    companion object {
        const val DEFAULT_POINT_HEIGHT_METERS: Double = 7.0

        /** `L10n.Tools.Tools.LineOfSight.droppedPin` string resource (WP-005 converted Tools.strings). */
        val DROPPED_PIN_LABEL: Int get() = AppToolsStrings.toolsLineOfSightDroppedPin
    }
}

/** Pre-computed selection state for a repeater annotation. */
data class LOSRepeaterSelectionInfo(val selectedAs: PointID?)

/** Current status of path analysis. */
sealed interface AnalysisStatus {
    data object Idle : AnalysisStatus

    data class Result(val result: PathAnalysisResult) : AnalysisStatus

    data class RelayResult(val result: RelayPathAnalysisResult) : AnalysisStatus

    /** Swift carries `error.localizedDescription`; this carries the failure's message text. */
    data class Error(val message: String) : AnalysisStatus
}
