// AndroidOnly: WP-313 Approximation of Foundation Measurement .measurement(width: .abbreviated, usage: .road) for SNR badge distances.
package com.meshcoreone.android.feature.remotenodes.map

import com.meshcoreone.android.feature.remotenodes.common.SwiftNumberFormat
import com.meshcoreone.android.feature.remotenodes.telemetry.MeasurementSystem
import com.meshcoreone.android.core.maps.MapRoadDistanceMeasure
import com.meshcoreone.android.core.maps.MapRoadMeasurementSystem
import java.util.Locale

/** A road-usage unit choice. */
enum class RoadUnit { METER, KILOMETER, FOOT, YARD, MILE }

/** A distance converted and rounded the way Foundation's road usage displays it. */
data class RoadMeasure(val value: Double, val unit: RoadUnit)

/**
 * Reproduces the unit ladder and rounding Foundation applies for `usage: .road`, derived from a
 * swiftc sweep (evidence oracle `map_road_usage.swift.txt`):
 * - metric: kilometres from 0.9 km, else metres; US: miles from 0.5 mi, else feet; UK: miles from
 *   0.5 mi, else yards (Foundation uses yards, not feet, for the UK);
 * - small units: whole numbers below 10, multiples of 10 below 300 m / 100 ft / 100 yd, multiples
 *   of 50 above;
 * - large units: two significant digits but never fewer than the integer digits ("0.95 km",
 *   "1.5 km", "16 km", "123 km");
 * - ties round half-even on the shortest decimal form of the value, as ICU does.
 *
 * Known gaps (documented in the WP-313 report): unit symbols come from a small table covering the
 * shipped app languages (no plural-dependent forms such as Ukrainian "ярди"/"ярдів"), and grouping
 * always applies, so "1235 km" in es/it/pl/pt-PT renders "1.235 km". Neither occurs at LoRa
 * neighbour distances.
 */
object RoadDistanceFormat {
    fun format(meters: Double, locale: Locale, system: MeasurementSystem): String {
        val measure = measure(meters, system)
        val number = SwiftNumberFormat.number(measure.value, locale)
        return RoadUnitSymbols.forLocale(locale).compose(number, measure.unit)
    }

    fun measure(meters: Double, system: MeasurementSystem): RoadMeasure {
        val sharedSystem = when (system) {
            MeasurementSystem.METRIC -> MapRoadMeasurementSystem.METRIC
            MeasurementSystem.US -> MapRoadMeasurementSystem.US
            MeasurementSystem.UK -> MapRoadMeasurementSystem.UK
        }
        val shared = MapRoadDistanceMeasure.measure(meters, sharedSystem)
        return RoadMeasure(shared.value, RoadUnit.valueOf(shared.unit.name))
    }
}

/** Abbreviated unit symbols and the number/unit separator per app language (Foundation output). */
private data class RoadUnitSymbols(
    val separator: String,
    val meter: String = "m",
    val kilometer: String = "km",
    val foot: String = "ft",
    val yard: String = "yd",
    val mile: String = "mi",
) {
    fun compose(number: String, unit: RoadUnit): String {
        val symbol = when (unit) {
            RoadUnit.METER -> meter
            RoadUnit.KILOMETER -> kilometer
            RoadUnit.FOOT -> foot
            RoadUnit.YARD -> yard
            RoadUnit.MILE -> mile
        }
        return number + separator + symbol
    }

    companion object {
        private const val SPACE = " "
        private const val NARROW_NO_BREAK_SPACE = " "
        private val DEFAULT = RoadUnitSymbols(SPACE)
        private val BY_LANGUAGE = mapOf(
            "fr" to RoadUnitSymbols(NARROW_NO_BREAK_SPACE, foot = "pi"),
            "ko" to RoadUnitSymbols(""),
            "zh" to RoadUnitSymbols("", "米", "公里", "英尺", "码", "英里"),
            "ru" to RoadUnitSymbols(SPACE, "м", "км", "фт", "ярд.", "ми"),
            "uk" to RoadUnitSymbols(SPACE, "м", "км", "фт", "ярди", "милі"),
            "pl" to RoadUnitSymbols(SPACE, mile = "mili"),
        )

        fun forLocale(locale: Locale): RoadUnitSymbols = BY_LANGUAGE[locale.language] ?: DEFAULT
    }
}
