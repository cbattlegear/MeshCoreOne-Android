// PortedFrom: MC1Services/Sources/MC1Services/Services/SettingsService+Verified.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: preserve Swift numeric verification metadata rather than locale/JVM scientific spelling.
package com.meshcoreone.android.core.services.device

import java.math.BigDecimal

internal fun sourceDoubleDescription(value: Double): String {
    if (value.isNaN()) return "nan"
    if (value == Double.POSITIVE_INFINITY) return "inf"
    if (value == Double.NEGATIVE_INFINITY) return "-inf"
    if (value == 0.0) return if (value.toRawBits() < 0) "-0.0" else "0.0"
    val decimal = BigDecimal.valueOf(value).stripTrailingZeros()
    val exponent = decimal.precision() - decimal.scale() - 1
    if (exponent in -4..15) {
        val plain = decimal.toPlainString()
        return if ('.' in plain) plain else "$plain.0"
    }
    val significand = decimal.movePointLeft(exponent).toPlainString()
    val sign = if (exponent < 0) "-" else "+"
    return significand + "e" + sign + kotlin.math.abs(exponent).toString().padStart(2, '0')
}
