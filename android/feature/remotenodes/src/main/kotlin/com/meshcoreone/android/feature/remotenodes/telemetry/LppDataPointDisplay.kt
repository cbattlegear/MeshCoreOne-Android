// PortedFrom: MC1Services/Sources/MC1Services/Extensions/LPPDataPoint+Display.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.telemetry

import com.meshcoreone.android.core.protocol.lpp.LPPDataPoint
import com.meshcoreone.android.core.protocol.lpp.LPPSensorType
import com.meshcoreone.android.core.protocol.lpp.LPPValue
import com.meshcoreone.android.feature.remotenodes.common.SwiftNumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.truncate

/** Wire/serialization sensor name ("Temperature"), stored in snapshot entries. */
val LPPDataPoint.typeName: String get() = type.displayName

/** Swift `Double.rounded()` (half away from zero); Kotlin's `round` is half-even. */
internal fun swiftRounded(value: Double): Double {
    val whole = truncate(value)
    return if (abs(value - whole) >= 0.5) whole + sign(value) else whole
}

/** Estimated battery percentage from voltage (3.0 V = 0 %, 4.2 V = 100 %); null unless a float voltage. */
val LPPDataPoint.batteryPercentage: Int?
    get() {
        if (type != LPPSensorType.VOLTAGE) return null
        val voltage = (value as? LPPValue.Float)?.value ?: return null
        val percentage = (voltage - MIN_BATTERY_VOLTS) / (MAX_BATTERY_VOLTS - MIN_BATTERY_VOLTS) * 100
        return swiftRounded(percentage).toInt().coerceIn(0, 100)
    }

private const val MIN_BATTERY_VOLTS = 3.0
private const val MAX_BATTERY_VOLTS = 4.2

/**
 * Formatted value with unit suffix, as Swift `formattedValue`. Numbers follow Foundation's
 * `.number.precision(.fractionLength(n))` for [locale]; locale-sensitive units follow [system].
 * Timestamps use the platform medium-date/short-time style in [zone], not Apple's
 * `.abbreviated`/`.shortened` pattern (documented deviation).
 */
fun LPPDataPoint.formattedValue(
    locale: Locale,
    system: MeasurementSystem,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    fun fixed(number: Double, digits: Int) = SwiftNumberFormat.fixed(number, digits, locale)
    fun converted(number: Double): String {
        val value = type.convertedValue(number, system)
        return "${fixed(value, type.convertedFractionLength(system))} ${type.localizedUnitSymbol(system)}"
    }
    val current = value
    val typed: String? = when (type) {
        LPPSensorType.VOLTAGE -> (current as? LPPValue.Float)?.let { "${fixed(it.value, 3)} V" }
        LPPSensorType.TEMPERATURE, LPPSensorType.BAROMETER, LPPSensorType.ALTITUDE, LPPSensorType.DISTANCE ->
            (current as? LPPValue.Float)?.let { converted(it.value) }
        LPPSensorType.HUMIDITY -> (current as? LPPValue.Float)?.let { "${fixed(it.value, 1)}%" }
        LPPSensorType.ILLUMINANCE -> (current as? LPPValue.Integer)?.let { "${it.value} lux" }
        LPPSensorType.PERCENTAGE -> (current as? LPPValue.Integer)?.let { "${it.value}%" }
        LPPSensorType.CURRENT -> (current as? LPPValue.Float)?.let { "${fixed(it.value, 3)} A" }
        LPPSensorType.POWER -> (current as? LPPValue.Float)?.let { "${fixed(it.value, 1)} W" }
        LPPSensorType.FREQUENCY -> (current as? LPPValue.Float)?.let { "${fixed(it.value, 1)} Hz" }
        LPPSensorType.ENERGY -> (current as? LPPValue.Float)?.let { "${fixed(it.value, 3)} kWh" }
        LPPSensorType.DIRECTION -> (current as? LPPValue.Float)?.let { "${fixed(it.value, 0)}°" }
        else -> null
    }
    if (typed != null) return typed
    return when (current) {
        is LPPValue.Digital -> if (current.value) "On" else "Off"
        is LPPValue.Integer -> current.value.toString()
        is LPPValue.Float -> fixed(current.value, 3)
        is LPPValue.Vector3 -> "(${fixed(current.x, 2)}, ${fixed(current.y, 2)}, ${fixed(current.z, 2)})"
        is LPPValue.Gps ->
            "${fixed(current.latitude, 5)}, ${fixed(current.longitude, 5)} @ ${fixed(current.altitude, 1)}m"
        is LPPValue.Rgb -> "RGB(${current.red}, ${current.green}, ${current.blue})"
        is LPPValue.Timestamp -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale).withZone(zone).format(current.value)
    }
}
