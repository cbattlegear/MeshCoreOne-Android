// PortedFrom: MC1/Views/Map/MapLine+SNR.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Feature-local mirror pending WP-312's core:maps SNR line/badge helpers.
package com.meshcoreone.android.feature.remotenodes.map

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.feature.remotenodes.common.GeoDistance
import com.meshcoreone.android.feature.remotenodes.common.SwiftNumberFormat
import com.meshcoreone.android.feature.remotenodes.telemetry.MeasurementSystem
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/** Swift `MapLine.LineStyle.forSNR`: `SNRQuality` owns the thresholds. */
fun MapLineStyle.Companion.forSNR(snr: Double?): MapLineStyle = when (SNRQuality.of(snr)) {
    SNRQuality.EXCELLENT, SNRQuality.GOOD -> MapLineStyle.TRACE_GOOD
    SNRQuality.FAIR -> MapLineStyle.TRACE_MEDIUM
    SNRQuality.POOR -> MapLineStyle.TRACE_WEAK
    SNRQuality.UNKNOWN -> MapLineStyle.TRACE_UNTRACED
}

/**
 * The inputs of the midpoint distance/SNR badge (Swift `MapLine.snrBadgeText(distance:snr:)`), kept as
 * data so logic never resolves resources or reads the device locale.
 */
data class SnrBadge(val distanceMeters: Double, val snr: Double) {
    /**
     * `"<distance> · <snr> <unit>"`: the distance in road usage ([RoadDistanceFormat], an
     * approximation of Foundation's `.measurement(width: .abbreviated, usage: .road)`), the SNR with
     * one fraction digit, and [unitText] resolved by the caller from [UNIT_TEXT_RESOURCE] ("dB"). The
     * space before the unit is composed here, as in Swift.
     */
    fun text(locale: Locale, measurementSystem: MeasurementSystem, unitText: String): String {
        val distance = RoadDistanceFormat.format(distanceMeters, locale, measurementSystem)
        val snrText = SwiftNumberFormat.fixed(snr, SNR_FRACTION_DIGITS, locale)
        return "$distance · $snrText $unitText"
    }

    companion object {
        private const val SNR_FRACTION_DIGITS = 1

        /** L10n `RemoteNodes.Status.snrBadgeUnit`. */
        val UNIT_TEXT_RESOURCE: Int get() = AppRemoteNodesStrings.remoteNodesStatusSnrBadgeUnit
    }
}

/** Shared SNR badge/midpoint helpers (Swift `extension MapLine`). */
object SnrBadges {
    private const val HALF_TURN_DEGREES = 180.0
    private const val FULL_TURN_DEGREES = 360.0

    /**
     * The midpoint badge pin for the link between two coordinates. The distance is the haversine
     * stand-in for `CLLocation.distance(from:)` ([GeoDistance]; documented deviation).
     */
    fun snrBadge(id: UUID, from: Coordinate, to: Coordinate, snr: Double): MapPoint = MapPoint(
        id = id,
        coordinate = midpoint(from, to),
        pinStyle = PinStyle.BADGE,
        label = null,
        isClusterable = false,
        hopIndex = null,
        badge = SnrBadge(GeoDistance.meters(from, to), snr),
    )

    /**
     * Geographic midpoint of two coordinates, shifting one longitude by 360° before averaging when the
     * pair straddles the antimeridian so the badge lands between them.
     */
    fun midpoint(from: Coordinate, to: Coordinate): Coordinate {
        val west = from.longitude
        val east = to.longitude
        val (lon1, lon2) = when {
            abs(west - east) <= HALF_TURN_DEGREES -> west to east
            west < east -> (west + FULL_TURN_DEGREES) to east
            else -> west to (east + FULL_TURN_DEGREES)
        }
        val average = (lon1 + lon2) / 2
        val midLongitude = if (average > HALF_TURN_DEGREES) average - FULL_TURN_DEGREES else average
        return Coordinate((from.latitude + to.latitude) / 2, midLongitude)
    }
}
