// PortedFrom: MC1/Views/Tools/LineOfSight/LineOfSightViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/LineOfSight/LineOfSightView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.los

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.maps.MapAvailability
import com.meshcoreone.android.core.maps.MapCamera
import com.meshcoreone.android.core.maps.MapLibreOpenFreeMap
import com.meshcoreone.android.core.maps.MapLine
import com.meshcoreone.android.core.maps.MapLineStyle
import com.meshcoreone.android.core.maps.MapMarker
import com.meshcoreone.android.core.maps.MapPinStyle
import com.meshcoreone.android.core.maps.MapPresentationState
import com.meshcoreone.android.core.maps.MapStyle
import com.meshcoreone.android.core.model.Coordinate
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Stable map identities and source-equivalent line/marker rebuilding. Camera is deliberately an
 * input so incoming elevation and analysis state cannot reset an operator-selected viewport.
 */
class LineOfSightMapPresentation(
    private val pointAMapId: UUID = UUID.randomUUID(),
    private val pointBMapId: UUID = UUID.randomUUID(),
    private val repeaterTargetMapId: UUID = UUID.randomUUID(),
) {
    fun build(state: LineOfSightState, camera: MapCamera?, labelsEnabled: Boolean): MapPresentationState {
        val markers = buildList {
            state.repeatersWithLocation.forEach { repeater ->
                val selectedAs = state.selectionState[repeater.id]?.selectedAs
                add(
                    MapMarker(
                        id = repeater.id,
                        position = GeoPoint(repeater.latitude, repeater.longitude),
                        style = when (selectedAs) {
                            PointID.POINT_A -> MapPinStyle.REPEATER_RING_BLUE
                            PointID.POINT_B -> MapPinStyle.REPEATER_RING_GREEN
                            PointID.REPEATER, null -> MapPinStyle.REPEATER
                        },
                        label = repeater.displayName.takeIf { labelsEnabled },
                        clusterable = selectedAs == null,
                    ),
                )
            }
            state.pointA?.takeIf { it.contact == null }?.let {
                add(MapMarker(pointAMapId, it.coordinate.toGeoPoint(), MapPinStyle.POINT_A, clusterable = false))
            }
            state.pointB?.takeIf { it.contact == null }?.let {
                add(MapMarker(pointBMapId, it.coordinate.toGeoPoint(), MapPinStyle.POINT_B, clusterable = false))
            }
            state.repeaterPoint?.let {
                add(MapMarker(repeaterTargetMapId, it.coordinate.toGeoPoint(), MapPinStyle.CROSSHAIR, clusterable = false))
            }
            val direct = (state.analysisStatus as? AnalysisStatus.Result)?.result
            if (state.repeaterPoint == null && direct?.clearanceStatus != null &&
                direct.clearanceStatus != ClearanceStatus.CLEAR
            ) {
                direct.obstructionPoints.forEach { obstruction ->
                    val fraction = obstruction.distanceFromAMeters / direct.distanceMeters
                    state.coordinateAt(fraction)?.let { coordinate ->
                        add(
                            MapMarker(
                                obstruction.id,
                                coordinate.toGeoPoint(),
                                MapPinStyle.OBSTRUCTION,
                                clusterable = false,
                            ),
                        )
                    }
                }
            }
        }
        val lines = buildLines(state)
        return MapPresentationState(
            availability = MapAvailability.Available(MapLibreOpenFreeMap.catalog),
            style = MapStyle.STANDARD,
            camera = camera,
            points = markers,
            lines = lines,
            isInteractive = true,
        )
    }

    private fun buildLines(state: LineOfSightState): List<MapLine> {
        val a = state.pointA?.coordinate?.toGeoPoint() ?: return emptyList()
        val b = state.pointB?.coordinate?.toGeoPoint() ?: return emptyList()
        val dim = 0.3f
        val active = 0.7f
        val repeater = state.repeaterPoint?.coordinate?.toGeoPoint()
        if (repeater == null) {
            return listOf(MapLine("los-ab", listOf(a, b), MapLineStyle.LOS, if (state.isRelocating) dim else active))
        }
        val relocating = state.relocatingPoint
        val ar = if (relocating == PointID.REPEATER || relocating == PointID.POINT_A) dim else active
        val rb = if (relocating == PointID.REPEATER || relocating == PointID.POINT_B) dim else active
        return listOf(
            MapLine("los-ar", listOf(a, repeater), MapLineStyle.LOS, ar),
            MapLine("los-rb", listOf(repeater, b), MapLineStyle.LOS, rb),
        )
    }

    companion object {
        val WORLD_CAMERA = MapCamera(GeoPoint(0.0, 0.0), latitudeSpan = 170.0, longitudeSpan = 350.0)

        fun fit(coordinates: List<Coordinate>): MapCamera? {
            if (coordinates.isEmpty()) return null
            val minLat = coordinates.minOf { it.latitude }
            val maxLat = coordinates.maxOf { it.latitude }
            val minLon = coordinates.minOf { it.longitude }
            val maxLon = coordinates.maxOf { it.longitude }
            val center = GeoPoint((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0)
            val rawLatSpan = max(MIN_SPAN, (maxLat - minLat) * FIT_PADDING)
            val rawLonSpan = max(MIN_SPAN, (maxLon - minLon) * FIT_PADDING)
            return runCatching {
                MapCamera(
                    center = center,
                    latitudeSpan = min(rawLatSpan, (90.0 - abs(center.latitude)) * 2.0),
                    longitudeSpan = min(360.0, rawLonSpan),
                )
            }.getOrNull()
        }

        /**
         * Converts an unconsumed Compose pointer into the north-up, untilted MapLibre viewport.
         * Latitude interpolation is Web Mercator rather than linear degrees.
         */
        fun coordinateAt(offset: Offset, size: IntSize, camera: MapCamera): Coordinate? {
            if (size.width <= 0 || size.height <= 0) return null
            val x = (offset.x / size.width).coerceIn(0f, 1f).toDouble()
            val y = (offset.y / size.height).coerceIn(0f, 1f).toDouble()
            val west = camera.center.longitude - camera.longitudeSpan / 2.0
            val longitude = normalizeLongitude(west + camera.longitudeSpan * x)
            val north = min(MAX_MERCATOR_LATITUDE, camera.center.latitude + camera.latitudeSpan / 2.0)
            val south = max(-MAX_MERCATOR_LATITUDE, camera.center.latitude - camera.latitudeSpan / 2.0)
            val mercatorY = mercator(north) + (mercator(south) - mercator(north)) * y
            val latitude = inverseMercator(mercatorY)
            return runCatching { Coordinate(latitude, longitude) }.getOrNull()
        }

        private fun Coordinate.toGeoPoint() = GeoPoint(latitude, longitude)

        private fun mercator(latitude: Double): Double {
            val radians = latitude * PI / 180.0
            return ln(tan(PI / 4.0 + radians / 2.0))
        }

        private fun inverseMercator(value: Double): Double = (2.0 * atan(exp(value)) - PI / 2.0) * 180.0 / PI

        private fun normalizeLongitude(value: Double): Double {
            var normalized = value
            while (normalized > 180.0) normalized -= 360.0
            while (normalized < -180.0) normalized += 360.0
            return normalized
        }

        private const val FIT_PADDING = 1.5
        private const val MIN_SPAN = 0.01
        private const val MAX_MERCATOR_LATITUDE = 85.05112878
    }
}
