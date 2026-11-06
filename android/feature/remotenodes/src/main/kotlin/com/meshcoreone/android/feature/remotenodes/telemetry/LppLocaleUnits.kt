// PortedFrom: MC1Services/Sources/MC1Services/Extensions/LPPSensorType+LocaleUnits.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.telemetry

import android.icu.util.LocaleData
import android.icu.util.ULocale
import com.meshcoreone.android.core.protocol.lpp.LPPSensorType
import java.util.Locale

/** Swift `Locale.MeasurementSystem`; injected instead of reading `Locale.current` so tests pin it. */
enum class MeasurementSystem {
    METRIC, US, UK;

    companion object {
        /** Explicit Unicode measurement preference, otherwise ICU's regional measurement system. */
        fun of(locale: Locale): MeasurementSystem = when (locale.getUnicodeLocaleType("ms")) {
            "metric" -> METRIC
            "ussystem" -> US
            "uksystem" -> UK
            // LocaleData uses the supplemental region (including "rg"), not the "ms" preference.
            else -> when (LocaleData.getMeasurementSystem(ULocale.forLocale(locale))) {
                LocaleData.MeasurementSystem.US -> US
                LocaleData.MeasurementSystem.UK -> UK
                else -> METRIC
            }
        }
    }
}

/**
 * Foundation `Measurement` conversions with Foundation's own linear coefficients, so converted values
 * keep the same floating-point error as iOS (25.5 °C is 77.89999999999563 °F there; verified with
 * swiftc, evidence oracle `numbers.swift.txt`).
 */
private object FoundationUnits {
    // UnitTemperature: kelvin base; celsius (1.0, 273.15), fahrenheit (0.55555555555556, 255.37222222222427).
    private const val CELSIUS_CONSTANT = 273.15
    private const val FAHRENHEIT_COEFFICIENT = 0.55555555555556
    private const val FAHRENHEIT_CONSTANT = 255.37222222222427

    // UnitPressure: newtons per square metre base; hectopascals 100.0, inchesOfMercury 3386.39.
    private const val HECTOPASCAL_COEFFICIENT = 100.0
    private const val INCH_OF_MERCURY_COEFFICIENT = 3386.39

    // UnitLength: metres base; feet 0.3048.
    private const val FOOT_COEFFICIENT = 0.3048

    fun celsiusToFahrenheit(celsius: Double): Double =
        ((celsius + CELSIUS_CONSTANT) - FAHRENHEIT_CONSTANT) / FAHRENHEIT_COEFFICIENT

    fun hectopascalsToInchesOfMercury(hectopascals: Double): Double =
        hectopascals * HECTOPASCAL_COEFFICIENT / INCH_OF_MERCURY_COEFFICIENT

    fun metersToFeet(meters: Double): Double = meters / FOOT_COEFFICIENT
}

private val LPPSensorType.hasSiDimension: Boolean
    get() = this == LPPSensorType.TEMPERATURE || this == LPPSensorType.BAROMETER ||
        this == LPPSensorType.ALTITUDE || this == LPPSensorType.DISTANCE

/** Whether this type's value is converted away from SI under [system]. */
fun LPPSensorType.isConverted(system: MeasurementSystem): Boolean = hasSiDimension && system != MeasurementSystem.METRIC

/** Converts an SI telemetry value to the [system]'s preferred unit; other types are unchanged. */
fun LPPSensorType.convertedValue(siValue: Double, system: MeasurementSystem): Double {
    if (!isConverted(system)) return siValue
    return when (this) {
        LPPSensorType.TEMPERATURE -> FoundationUnits.celsiusToFahrenheit(siValue)
        LPPSensorType.BAROMETER -> FoundationUnits.hectopascalsToInchesOfMercury(siValue)
        LPPSensorType.ALTITUDE, LPPSensorType.DISTANCE -> FoundationUnits.metersToFeet(siValue)
        else -> siValue
    }
}

/** The [system]-appropriate unit symbol ("°F", "inHg", "ft"), else the SI `unit`. */
fun LPPSensorType.localizedUnitSymbol(system: MeasurementSystem): String {
    if (!isConverted(system)) return unit
    return when (this) {
        LPPSensorType.TEMPERATURE -> "°F"
        LPPSensorType.BAROMETER -> "inHg"
        LPPSensorType.ALTITUDE, LPPSensorType.DISTANCE -> "ft"
        else -> unit
    }
}

/** Fraction digits for the converted value. */
fun LPPSensorType.convertedFractionLength(system: MeasurementSystem): Int = if (!isConverted(system)) {
    when (this) {
        LPPSensorType.DISTANCE -> 3
        else -> 1
    }
} else {
    when (this) {
        LPPSensorType.BAROMETER -> 2
        LPPSensorType.ALTITUDE -> 0
        else -> 1
    }
}
