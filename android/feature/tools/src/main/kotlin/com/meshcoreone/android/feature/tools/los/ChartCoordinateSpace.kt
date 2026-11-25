// PortedFrom: MC1/Views/Tools/LineOfSight/ChartCoordinateSpace.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.los

import java.util.Locale

/** Canvas insets in pixels (SwiftUI `EdgeInsets`). */
data class ChartInsets(val top: Double, val leading: Double, val bottom: Double, val trailing: Double)

/** A canvas point in pixels (SwiftUI `CGPoint`; CGFloat is a 64-bit Double on the source platforms). */
data class ChartPoint(val x: Double, val y: Double)

/**
 * Transforms data coordinates (meters) to canvas pixel coordinates. The y axis is inverted
 * because canvas origins are top-left while data origins are bottom-left.
 */
data class ChartCoordinateSpace(
    val canvasWidth: Double,
    val canvasHeight: Double,
    val padding: ChartInsets,
    /** Meters. */
    val xRange: ClosedFloatingPointRange<Double>,
    /** Meters. */
    val yRange: ClosedFloatingPointRange<Double>,
) {
    private val plotWidth: Double get() = canvasWidth - padding.leading - padding.trailing
    private val plotHeight: Double get() = canvasHeight - padding.top - padding.bottom

    /** Convert an x data value (meters) to a pixel x coordinate. */
    fun xPixel(xMeters: Double): Double {
        val fraction = (xMeters - xRange.start) / (xRange.endInclusive - xRange.start)
        return padding.leading + fraction * plotWidth
    }

    /** Convert a y data value (meters) to a pixel y coordinate (inverted for the canvas). */
    fun yPixel(yMeters: Double): Double {
        val fraction = (yMeters - yRange.start) / (yRange.endInclusive - yRange.start)
        return canvasHeight - padding.bottom - fraction * plotHeight
    }

    /** Convert a data point (meters) to a canvas pixel point. */
    fun point(x: Double, y: Double): ChartPoint = ChartPoint(xPixel(x), yPixel(y))

    /** Format an x value (meters) as a one-decimal km string for axis labels. */
    fun xLabel(xMeters: Double, locale: Locale): String = formatFractionLength(xMeters / 1000, 1, locale)
}
