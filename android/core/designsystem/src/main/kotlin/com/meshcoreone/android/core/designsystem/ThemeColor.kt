// PortedFrom: MC1/Extensions/Color+Hex.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Theme/WCAGContrast.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.designsystem

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class ColorScheme { LIGHT, DARK }
enum class ThemeColorSpace { SRGB, DISPLAY_P3 }

data class ThemeColor(
    val red: Double,
    val green: Double,
    val blue: Double,
    val alpha: Double = 1.0,
    val colorSpace: ThemeColorSpace = ThemeColorSpace.SRGB,
) {
    init {
        require(listOf(red, green, blue, alpha).all { it.isFinite() && it in 0.0..1.0 }) {
            "Color components must be finite and within 0...1"
        }
    }

    val luminance: Double get() = toSrgb().let { WCAGContrast.relativeLuminance(it.red, it.green, it.blue) }

    fun toSrgb(): ThemeColor {
        if (colorSpace == ThemeColorSpace.SRGB) return this
        val r = linearize(red)
        val g = linearize(green)
        val b = linearize(blue)
        return ThemeColor(
            encode(1.2249401763 * r - 0.2249401763 * g),
            encode(-0.0420569547 * r + 1.0420569547 * g),
            encode(-0.0196375546 * r - 0.0786360456 * g + 1.0982736001 * b),
            alpha,
        )
    }

    fun compositeOver(background: ThemeColor): ThemeColor {
        val foreground = toSrgb()
        val behind = background.toSrgb()
        val a = foreground.alpha + behind.alpha * (1.0 - foreground.alpha)
        if (a == 0.0) return TRANSPARENT
        fun component(front: Double, back: Double) =
            (front * foreground.alpha + back * behind.alpha * (1.0 - foreground.alpha)) / a
        return ThemeColor(
            component(foreground.red, behind.red), component(foreground.green, behind.green),
            component(foreground.blue, behind.blue), a,
        )
    }

    fun mix(other: ThemeColor, fraction: Double): ThemeColor {
        require(fraction.isFinite() && fraction in 0.0..1.0)
        val a = toSrgb()
        val b = other.toSrgb()
        fun component(first: Double, second: Double) = first + (second - first) * fraction
        return ThemeColor(
            component(a.red, b.red), component(a.green, b.green), component(a.blue, b.blue),
            component(a.alpha, b.alpha),
        )
    }

    companion object {
        val WHITE = ThemeColor(1.0, 1.0, 1.0)
        val BLACK = ThemeColor(0.0, 0.0, 0.0)
        val TRANSPARENT = ThemeColor(0.0, 0.0, 0.0, 0.0)

        fun hex(value: Long): ThemeColor = ThemeColor(
            ((value shr 16) and 255).toDouble() / 255,
            ((value shr 8) and 255).toDouble() / 255,
            (value and 255).toDouble() / 255,
        )

        private fun linearize(value: Double): Double =
            if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        private fun encode(value: Double): Double =
            (if (value <= 0.0031308) 12.92 * value else 1.055 * value.pow(1.0 / 2.4) - 0.055).coerceIn(0.0, 1.0)
    }
}

data class AdaptiveColor(
    val light: ThemeColor,
    val dark: ThemeColor = light,
    val highContrastLight: ThemeColor = light,
    val highContrastDark: ThemeColor = dark,
) {
    fun resolve(scheme: ColorScheme, highContrast: Boolean): ThemeColor = when (scheme) {
        ColorScheme.LIGHT -> if (highContrast) highContrastLight else light
        ColorScheme.DARK -> if (highContrast) highContrastDark else dark
    }
}

object WCAGContrast {
    const val AA_FLOOR = 4.5
    const val INCREASED_CONTRAST_FLOOR = 7.0

    fun relativeLuminance(red: Double, green: Double, blue: Double): Double {
        require(red.isFinite() && green.isFinite() && blue.isFinite())
        fun linearize(component: Double): Double {
            val c = component.coerceIn(0.0, 1.0)
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linearize(red) + 0.7152 * linearize(green) + 0.0722 * linearize(blue)
    }

    fun contrastRatio(lhs: Double, rhs: Double): Double {
        require(lhs.isFinite() && rhs.isFinite() && lhs in 0.0..1.0 && rhs in 0.0..1.0)
        return (max(lhs, rhs) + 0.05) / (min(lhs, rhs) + 0.05)
    }

    fun contrastRatio(foreground: ThemeColor, background: ThemeColor): Double =
        contrastRatio(foreground.compositeOver(background).luminance, background.luminance)

    fun floor(highContrast: Boolean): Double = if (highContrast) INCREASED_CONTRAST_FLOOR else AA_FLOOR
}
