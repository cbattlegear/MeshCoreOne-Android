// PortedFrom: MC1/Views/Tools/LineOfSight/TerrainProfileCanvas.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.los

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Pure geometry of the Swift `TerrainProfileCanvas`: ranges, axis ticks/labels, Fresnel and
 * terrain polygons, obstruction bands, markers and drag mapping, in canvas pixels. A Compose
 * `Canvas` draws [TerrainProfileChartLayout] in the source order: grid, Fresnel fills,
 * Fresnel boundaries, terrain, obstructions, LOS lines, endpoint markers, junction separator,
 * repeater marker. Colours, fonts, legend, empty state and animation timing stay in the UI layer.
 */
data class TerrainProfileChartInput(
    val elevationProfile: List<ElevationSample>,
    /** Primary segment (A->B, or A->R when a repeater is active). */
    val profileSamples: List<ProfileSample>,
    /** R->B segment (empty without a repeater). */
    val profileSamplesRB: List<ProfileSample> = emptyList(),
    val repeaterPathFraction: Double? = null,
    val repeaterHeight: Double? = null,
    /** Off-path segment distances (null when on-path or no repeater). */
    val segmentARDistanceMeters: Double? = null,
    val segmentRBDistanceMeters: Double? = null,
) {
    /** Whether the repeater is off the direct A->B path. */
    val isOffPath: Boolean get() = segmentARDistanceMeters != null && segmentRBDistanceMeters != null

    /** The source shows the "no data" empty state instead of the chart when this is true. */
    val showsEmptyState: Boolean get() = elevationProfile.isEmpty()

    /** Legend gains the repeater entry when a repeater fraction is present. */
    val showsRepeaterLegend: Boolean get() = repeaterPathFraction != null

    /** "Indirect route" caption below the chart. */
    val showsIndirectRouteLabel: Boolean get() = !showsEmptyState && isOffPath

    val xRange: ClosedFloatingPointRange<Double> get() = TerrainProfileChart.xRange(elevationProfile)

    val yRange: ClosedFloatingPointRange<Double> get() = TerrainProfileChart.yRange(profileSamples, profileSamplesRB)

    fun coordinateSpace(canvasWidth: Double, canvasHeight: Double): ChartCoordinateSpace =
        ChartCoordinateSpace(canvasWidth, canvasHeight, TerrainProfileChart.PADDING, xRange, yRange)

    /** Repeater marker centre for tooltip positioning (`calculatedMarkerCenter`). */
    fun repeaterMarkerCenter(canvasWidth: Double, canvasHeight: Double, nudgeOffset: Double = 0.0): ChartPoint? {
        if (canvasWidth == 0.0 && canvasHeight == 0.0) return null
        val junction = profileSamples.lastOrNull() ?: return null
        if (repeaterPathFraction == null) return null
        val base = coordinateSpace(canvasWidth, canvasHeight).point(junction.x, junction.yLOS)
        return ChartPoint(base.x + nudgeOffset, base.y)
    }

    /** Full chart geometry for a canvas of the given pixel size. */
    fun layout(canvasWidth: Double, canvasHeight: Double, locale: Locale, nudgeOffset: Double = 0.0): TerrainProfileChartLayout =
        TerrainProfileChart.layout(this, coordinateSpace(canvasWidth, canvasHeight), locale, nudgeOffset)
}

data class ChartSegment(val start: ChartPoint, val end: ChartPoint)

data class ChartRect(val left: Double, val top: Double, val right: Double, val bottom: Double)

/** Axis label text anchored at [anchor] (y labels: trailing edge; x labels: top centre). */
data class AxisLabel(val text: String, val anchor: ChartPoint)

/** Closed fill polygons and open boundary polylines for one Fresnel segment. */
data class FresnelSegmentGeometry(
    val outerFill: List<ChartPoint>,
    val innerFill: List<ChartPoint>,
    val topBoundary: List<ChartPoint>,
    val bottomBoundary: List<ChartPoint>,
)

/** Vertical stem from ground to the LOS junction plus the marker centre (radius [TerrainProfileChart.REPEATER_MARKER_RADIUS]). */
data class RepeaterMarkerGeometry(val stem: ChartSegment, val center: ChartPoint)

data class TerrainProfileChartLayout(
    val coordinateSpace: ChartCoordinateSpace,
    /** Dashed horizontal grid lines at each y tick. */
    val gridLines: List<ChartSegment>,
    val yLabels: List<AxisLabel>,
    val xLabels: List<AxisLabel>,
    val fresnelSegments: List<FresnelSegmentGeometry>,
    /** Closed terrain fill polygon (empty when fewer than two samples). */
    val terrainFill: List<ChartPoint>,
    val terrainStroke: List<ChartPoint>,
    val obstructionBands: List<ChartRect>,
    val losLines: List<ChartSegment>,
    /** Blue "A" and green "B" endpoint marker centres (radius [TerrainProfileChart.ENDPOINT_MARKER_RADIUS]). */
    val endpointA: ChartPoint?,
    val endpointB: ChartPoint?,
    /** Dashed separator at the R junction for off-path repeaters. */
    val junctionSeparator: ChartSegment?,
    val repeaterMarker: RepeaterMarkerGeometry?,
)

object TerrainProfileChart {
    val PADDING: ChartInsets = ChartInsets(top = 24.0, leading = 45.0, bottom = 28.0, trailing = 16.0)
    const val CHART_HEIGHT: Double = 200.0
    const val ENDPOINT_MARKER_RADIUS: Double = 6.0
    const val REPEATER_MARKER_RADIUS: Double = 16.0
    const val MIN_OBSTRUCTION_WIDTH: Double = 4.0
    /** One-time draggability hint: shift right this far, then back. */
    const val NUDGE_OFFSET: Double = 4.0
    const val Y_TARGET_DIVISIONS: Int = 4
    const val X_TARGET_DIVISIONS: Int = 5
    const val Y_LABEL_GAP: Double = 8.0
    const val X_LABEL_GAP: Double = 10.0

    private const val Y_PADDING_BELOW = 0.1
    private const val Y_PADDING_ABOVE = 0.2
    private val DEFAULT_Y_RANGE = 0.0..100.0
    private const val METERS_PER_KM = 1000.0

    fun xRange(elevationProfile: List<ElevationSample>): ClosedFloatingPointRange<Double> {
        val last = elevationProfile.lastOrNull() ?: return 0.0..1.0
        return 0.0..swiftMax(1.0, last.distanceFromAMeters)
    }

    /** Terrain minimum to Fresnel-top maximum, padded 10% below and 20% above. */
    fun yRange(profileSamples: List<ProfileSample>, profileSamplesRB: List<ProfileSample>): ClosedFloatingPointRange<Double> {
        val allSamples = profileSamples + profileSamplesRB
        if (allSamples.isEmpty()) return DEFAULT_Y_RANGE
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (sample in allSamples) {
            minY = swiftMin(minY, sample.yTerrain)
            maxY = swiftMax(maxY, sample.yTop)
        }
        if (!(minY.isFinite() && maxY.isFinite() && maxY > minY)) return DEFAULT_Y_RANGE
        val range = maxY - minY
        return (minY - range * Y_PADDING_BELOW)..(maxY + range * Y_PADDING_ABOVE)
    }

    /** A "nice" axis step snapping to 1, 2, 2.5, 5 or 10 times a power of ten. */
    fun niceStep(range: Double, targetDivisions: Int): Double {
        if (!(range > 0 && targetDivisions > 0)) return 1.0
        val roughStep = range / targetDivisions.toDouble()
        val magnitude = 10.0.pow(floor(log10(roughStep)))
        val normalized = roughStep / magnitude
        val niceNormalized = when {
            normalized <= 1 -> 1.0
            normalized <= 2 -> 2.0
            normalized <= 2.5 -> 2.5
            normalized <= 5 -> 5.0
            else -> 10.0
        }
        return niceNormalized * magnitude
    }

    /** Ticks from the first multiple of [step] at or above the lower bound, accumulated by addition. */
    fun tickValues(range: ClosedFloatingPointRange<Double>, step: Double): List<Double> {
        if (!(step > 0)) return emptyList()
        val ticks = mutableListOf<Double>()
        var current = ceil(range.start / step) * step
        while (current <= range.endInclusive) {
            ticks.add(current)
            current += step
        }
        return ticks
    }

    /** Elevation tick text; the last tick carries the unit. Swift `Int(y)` truncates toward zero. */
    fun yAxisLabel(y: Double, isLast: Boolean): String = if (isLast) "${y.toLong()} m" else "${y.toLong()}"

    /** Distance tick text in km: integers when the step is whole km, else one decimal; last carries the unit. */
    fun xAxisLabel(x: Double, xStep: Double, isLast: Boolean, locale: Locale): String {
        val kmValue = x / METERS_PER_KM
        val text = if (xStep >= METERS_PER_KM && kmValue % 1.0 == 0.0) {
            "${kmValue.toLong()}"
        } else {
            formatFractionLength(kmValue, 1, locale)
        }
        return if (isLast) "$text km" else text
    }

    /** Maps a drag x location to an on-path repeater fraction clamped to 0.05...0.95. */
    fun dragPathFraction(locationX: Double, canvasWidth: Double): Double {
        val chartWidth = canvasWidth - PADDING.leading - PADDING.trailing
        val relativeX = (locationX - PADDING.leading) / chartWidth
        return swiftMax(RepeaterPoint.MIN_PATH_FRACTION, swiftMin(RepeaterPoint.MAX_PATH_FRACTION, relativeX))
    }

    /** The one-time nudge fires when an on-path repeater first appears and has not animated yet. */
    fun shouldNudge(oldFraction: Double?, newFraction: Double?, hasAnimatedNudge: Boolean, isOffPath: Boolean): Boolean =
        oldFraction == null && newFraction != null && !hasAnimatedNudge && !isOffPath

    internal fun layout(
        input: TerrainProfileChartInput,
        coords: ChartCoordinateSpace,
        locale: Locale,
        nudgeOffset: Double,
    ): TerrainProfileChartLayout {
        val segments = listOf(input.profileSamples, input.profileSamplesRB).filter { it.isNotEmpty() }
        val (gridLines, yLabels, xLabels) = grid(coords, locale)
        return TerrainProfileChartLayout(
            coordinateSpace = coords,
            gridLines = gridLines,
            yLabels = yLabels,
            xLabels = xLabels,
            fresnelSegments = segments.filter { it.size >= 2 }.map { fresnelSegment(coords, it) },
            terrainFill = terrainFill(coords, input),
            terrainStroke = terrainSamples(input).takeIf { it.size >= 2 }.orEmpty().map { coords.point(it.x, it.yTerrain) },
            obstructionBands = segments.flatMap { obstructionBands(coords, it) },
            losLines = segments.map {
                ChartSegment(coords.point(it.first().x, it.first().yLOS), coords.point(it.last().x, it.last().yLOS))
            },
            endpointA = input.profileSamples.firstOrNull()?.let { coords.point(it.x, it.yLOS) },
            endpointB = input.profileSamples.firstOrNull()?.let {
                (input.profileSamplesRB.lastOrNull() ?: input.profileSamples.last()).let { b -> coords.point(b.x, b.yLOS) }
            },
            junctionSeparator = input.segmentARDistanceMeters?.takeIf { input.isOffPath }?.let {
                ChartSegment(coords.point(it, coords.yRange.endInclusive), coords.point(it, coords.yRange.start))
            },
            repeaterMarker = repeaterMarker(coords, input, nudgeOffset),
        )
    }

    private fun grid(coords: ChartCoordinateSpace, locale: Locale): Triple<List<ChartSegment>, List<AxisLabel>, List<AxisLabel>> {
        val xRange = coords.xRange
        val yRange = coords.yRange
        val yStep = niceStep(yRange.endInclusive - yRange.start, Y_TARGET_DIVISIONS)
        val xStep = niceStep(xRange.endInclusive - xRange.start, X_TARGET_DIVISIONS)
        val yTicks = tickValues(yRange, yStep)
        val xTicks = tickValues(xRange, xStep)
        val lines = yTicks.map { ChartSegment(coords.point(xRange.start, it), coords.point(xRange.endInclusive, it)) }
        val yLabels = yTicks.mapIndexed { index, y ->
            val point = coords.point(xRange.start, y)
            AxisLabel(yAxisLabel(y, index == yTicks.lastIndex), ChartPoint(point.x - Y_LABEL_GAP, point.y))
        }
        val xLabels = xTicks.mapIndexed { index, x ->
            val point = coords.point(x, yRange.start)
            AxisLabel(xAxisLabel(x, xStep, index == xTicks.lastIndex, locale), ChartPoint(point.x, point.y + X_LABEL_GAP))
        }
        return Triple(lines, yLabels, xLabels)
    }

    private fun fresnelSegment(coords: ChartCoordinateSpace, samples: List<ProfileSample>): FresnelSegmentGeometry {
        val top = samples.map { coords.point(it.x, it.yTop) }
        val top60 = samples.map { coords.point(it.x, it.yTop60) }
        return FresnelSegmentGeometry(
            outerFill = top + samples.asReversed().map { coords.point(it.x, it.yVisibleBottom) },
            innerFill = top60 + samples.asReversed().map { coords.point(it.x, it.yVisibleBottom60) },
            topBoundary = top,
            bottomBoundary = samples.map { coords.point(it.x, it.yBottom) },
        )
    }

    /** Full terrain: primary samples plus R->B without its duplicate junction sample. */
    private fun terrainSamples(input: TerrainProfileChartInput): List<ProfileSample> =
        if (input.profileSamplesRB.isEmpty()) input.profileSamples else input.profileSamples + input.profileSamplesRB.drop(1)

    private fun terrainFill(coords: ChartCoordinateSpace, input: TerrainProfileChartInput): List<ChartPoint> {
        val samples = terrainSamples(input)
        if (samples.size < 2) return emptyList()
        return listOf(coords.point(coords.xRange.start, coords.yRange.start)) +
            samples.map { coords.point(it.x, it.yTerrain) } +
            coords.point(coords.xRange.endInclusive, coords.yRange.start)
    }

    /** Full-height bands over each contiguous run of obstructed samples, at least [MIN_OBSTRUCTION_WIDTH] wide. */
    private fun obstructionBands(coords: ChartCoordinateSpace, samples: List<ProfileSample>): List<ChartRect> {
        val runs = mutableListOf<IntRange>()
        var regionStart = -1
        samples.forEachIndexed { index, sample ->
            if (sample.isObstructed && regionStart < 0) {
                regionStart = index
            } else if (!sample.isObstructed && regionStart >= 0) {
                runs.add(regionStart until index)
                regionStart = -1
            }
        }
        if (regionStart >= 0) runs.add(regionStart until samples.size)
        return runs.map { run ->
            var leftX = coords.xPixel(samples[run.first].x)
            var rightX = coords.xPixel(samples[run.last].x)
            if (rightX - leftX < MIN_OBSTRUCTION_WIDTH) {
                val center = (leftX + rightX) / 2
                leftX = center - MIN_OBSTRUCTION_WIDTH / 2
                rightX = center + MIN_OBSTRUCTION_WIDTH / 2
            }
            ChartRect(leftX, coords.yPixel(coords.yRange.endInclusive), rightX, coords.yPixel(coords.yRange.start))
        }
    }

    /** Marker at the A->R junction on the LOS line (not ground level), shifted by the nudge offset. */
    private fun repeaterMarker(coords: ChartCoordinateSpace, input: TerrainProfileChartInput, nudgeOffset: Double): RepeaterMarkerGeometry? {
        if (input.repeaterPathFraction == null || input.repeaterHeight == null || input.elevationProfile.size < 2) return null
        val junction = input.profileSamples.lastOrNull() ?: return null
        val ground = coords.point(junction.x, junction.yTerrain)
        val los = coords.point(junction.x, junction.yLOS)
        val nudgedLos = ChartPoint(los.x + nudgeOffset, los.y)
        return RepeaterMarkerGeometry(ChartSegment(ChartPoint(ground.x + nudgeOffset, ground.y), nudgedLos), nudgedLos)
    }
}
