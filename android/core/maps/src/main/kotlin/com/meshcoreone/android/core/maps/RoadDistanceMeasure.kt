// AndroidOnly: WP-312 Shared road-distance unit selection and rounding for map consumers.
package com.meshcoreone.android.core.maps

import android.icu.util.LocaleData
import android.icu.util.ULocale
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale

enum class MapRoadMeasurementSystem { METRIC, US, UK }

enum class MapRoadUnit { METER, KILOMETER, FOOT, YARD, MILE }

data class MapRoadMeasure(val value: Double, val unit: MapRoadUnit)

/** Foundation road-usage ladder: 0.9 km or 0.5 mi thresholds, regional small units, half-even rounding. */
object MapRoadDistanceMeasure {
    private const val METERS_PER_KILOMETER = 1_000.0
    private const val KILOMETER_FROM_KILOMETERS = 0.9
    private const val METERS_PER_MILE = 1_609.344
    private const val MILE_FROM_MILES = 0.5
    private const val METERS_PER_FOOT = 0.3048
    private const val METERS_PER_YARD = 0.9144
    private const val WHOLE_BELOW = 10.0
    private const val METRIC_COARSE_FROM = 300.0
    private const val IMPERIAL_COARSE_FROM = 100.0
    private const val FINE_INCREMENT = 10L
    private const val COARSE_INCREMENT = 50L
    private const val LARGE_SIGNIFICANT_DIGITS = 2
    private const val LARGE_INTEGER_FROM = 100.0

    fun measurementSystem(locale: Locale): MapRoadMeasurementSystem =
        when (LocaleData.getMeasurementSystem(ULocale.forLocale(locale))) {
            LocaleData.MeasurementSystem.US -> MapRoadMeasurementSystem.US
            LocaleData.MeasurementSystem.UK -> MapRoadMeasurementSystem.UK
            else -> MapRoadMeasurementSystem.METRIC
        }

    fun measure(meters: Double, system: MapRoadMeasurementSystem): MapRoadMeasure {
        if (!meters.isFinite()) return MapRoadMeasure(meters, MapRoadUnit.METER)
        return when (system) {
            MapRoadMeasurementSystem.METRIC -> {
                val kilometers = convert(meters, METERS_PER_KILOMETER)
                if (kilometers >= KILOMETER_FROM_KILOMETERS) {
                    MapRoadMeasure(large(kilometers), MapRoadUnit.KILOMETER)
                } else {
                    MapRoadMeasure(small(meters, METRIC_COARSE_FROM), MapRoadUnit.METER)
                }
            }
            MapRoadMeasurementSystem.US -> imperial(meters, METERS_PER_FOOT, MapRoadUnit.FOOT)
            MapRoadMeasurementSystem.UK -> imperial(meters, METERS_PER_YARD, MapRoadUnit.YARD)
        }
    }

    private fun imperial(meters: Double, metersPerUnit: Double, smallUnit: MapRoadUnit): MapRoadMeasure {
        val miles = convert(meters, METERS_PER_MILE)
        return if (miles >= MILE_FROM_MILES) {
            MapRoadMeasure(large(miles), MapRoadUnit.MILE)
        } else {
            MapRoadMeasure(small(convert(meters, metersPerUnit), IMPERIAL_COARSE_FROM), smallUnit)
        }
    }

    private fun convert(meters: Double, metersPerUnit: Double): Double = meters * (1.0 / metersPerUnit)

    private fun small(value: Double, coarseFrom: Double): Double {
        val increment = when {
            value < WHOLE_BELOW -> 1L
            value < coarseFrom -> FINE_INCREMENT
            else -> COARSE_INCREMENT
        }
        val step = BigDecimal.valueOf(increment)
        return shortestDecimal(value).divide(step).setScale(0, RoundingMode.HALF_EVEN).multiply(step).toDouble()
    }

    private fun large(value: Double): Double {
        val decimal = shortestDecimal(value)
        val rounded = if (value >= LARGE_INTEGER_FROM) {
            decimal.setScale(0, RoundingMode.HALF_EVEN)
        } else {
            decimal.round(MathContext(LARGE_SIGNIFICANT_DIGITS, RoundingMode.HALF_EVEN))
        }
        return rounded.toDouble()
    }

    private fun shortestDecimal(value: Double): BigDecimal = BigDecimal(value.toString())
}
