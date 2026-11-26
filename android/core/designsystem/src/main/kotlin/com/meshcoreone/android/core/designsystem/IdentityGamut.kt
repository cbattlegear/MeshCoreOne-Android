// PortedFrom: MC1/Theme/IdentityGamut.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.designsystem

import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import kotlin.math.max
import kotlin.math.min

data class ResolvedIdentity(val hue: Double, val saturation: Double, val brightness: Double) {
    val color: ThemeColor get() = IdentityGamut.hsbToRgb(hue, saturation, brightness)
}

class IdentityGamut(
    hueAnchors: Iterable<Double>,
    val minimumSaturation: Double,
    val maximumSaturation: Double,
) {
    val hueAnchors: SnapshotList<Double> = hueAnchors.snapshot()
    val sortedAnchors: SnapshotList<Double> = this.hueAnchors.sorted().snapshot()

    init {
        require(this.hueAnchors.isNotEmpty() && this.hueAnchors.all { it.isFinite() && it >= 0 && it < 360 })
        require(this.hueAnchors.distinct().size == this.hueAnchors.size)
        require(minimumSaturation.isFinite() && maximumSaturation.isFinite())
        require(minimumSaturation >= 0 && maximumSaturation <= 1 && minimumSaturation < maximumSaturation)
    }

    fun distinctAnchorHues(names: List<String>): SnapshotList<Double> {
        require(names.size <= sortedAnchors.size) { "Distinct names cannot exceed the available anchors" }
        val taken = mutableSetOf<Int>()
        return names.map { name ->
            var index = anchorIndex(fnv1a(name))
            while (!taken.add(index)) index = (index + 1) % sortedAnchors.size
            sortedAnchors[index]
        }.snapshot()
    }

    fun color(
        name: String,
        backgroundLuminances: List<Double>,
        highContrast: Boolean,
        atHue: Double? = null,
        atVariety: Double? = null,
    ): ThemeColor = resolve(name, backgroundLuminances, highContrast, atHue, atVariety).color

    fun resolve(
        name: String,
        backgroundLuminances: List<Double>,
        highContrast: Boolean,
        atHue: Double? = null,
        atVariety: Double? = null,
    ): ResolvedIdentity {
        require(backgroundLuminances.all { it.isFinite() && it in 0.0..1.0 })
        require(atHue == null || atHue.isFinite())
        require(atVariety == null || (atVariety.isFinite() && atVariety in 0.0..1.0))
        val seed = fnv1a(name)
        val hue = atHue ?: hue(seed)
        val baseSaturation = minimumSaturation + (maximumSaturation - minimumSaturation) * fraction(seed, 32)
        val variety = atVariety ?: fraction(seed, 48)
        val floor = WCAGContrast.floor(highContrast) + 0.35
        val backgrounds = backgroundLuminances.ifEmpty { listOf(1.0) }
        if (backgrounds.min() >= 0.3) {
            val safeMaximum = max((backgrounds.min() + 0.05) / floor - 0.05, 0.0)
            val target = safeMaximum * (0.45 + 0.55 * variety)
            return ResolvedIdentity(hue, baseSaturation, brightnessFor(target, hue, baseSaturation))
        }
        val required = max(floor * (backgrounds.max() + 0.05) - 0.05, 0.0)
        var saturation = baseSaturation
        while (saturation > 0 && luminance(hue, saturation, 1.0) < required) {
            saturation = max(0.0, saturation - 0.04)
        }
        val reachable = luminance(hue, saturation, 1.0)
        val ceiling = max(required, min(reachable, 0.86))
        val target = min(required + (ceiling - required) * variety, reachable)
        return ResolvedIdentity(hue, saturation, brightnessFor(target, hue, saturation))
    }

    private fun anchorIndex(seed: Long): Int =
        java.lang.Long.remainderUnsigned(seed, sortedAnchors.size.toLong()).toInt()

    private fun hue(seed: Long): Double {
        val index = anchorIndex(seed)
        val anchor = sortedAnchors[index]
        val next = sortedAnchors[(index + 1) % sortedAnchors.size]
        val previous = sortedAnchors[(index - 1 + sortedAnchors.size) % sortedAnchors.size]
        val draw = fraction(seed, 16) * 2 - 1
        val jitter = draw * (if (draw >= 0) circularGap(anchor, next) else circularGap(previous, anchor)) / 2
        val shifted = anchor + jitter
        return shifted % 360 + if (shifted < 0) 360 else 0
    }

    private fun brightnessFor(target: Double, hue: Double, saturation: Double): Double {
        var low = 0.0
        var high = 1.0
        repeat(24) {
            val middle = (low + high) / 2
            if (luminance(hue, saturation, middle) < target) low = middle else high = middle
        }
        return (low + high) / 2
    }

    override fun equals(other: Any?): Boolean = other is IdentityGamut &&
        hueAnchors == other.hueAnchors && minimumSaturation == other.minimumSaturation &&
        maximumSaturation == other.maximumSaturation
    override fun hashCode(): Int = 31 * (31 * hueAnchors.hashCode() + minimumSaturation.hashCode()) + maximumSaturation.hashCode()

    companion object {
        fun glyphColor(fillLuminance: Double): ThemeColor {
            require(fillLuminance.isFinite() && fillLuminance in 0.0..1.0)
            val whiteContrast = 1.05 / (fillLuminance + 0.05)
            val blackContrast = (fillLuminance + 0.05) / 0.05
            return if (whiteContrast >= blackContrast) ThemeColor.WHITE else ThemeColor(0.1, 0.1, 0.1)
        }

        fun fnv1a(value: String): Long {
            var index = 0
            while (index < value.length) {
                val character = value[index]
                require(!character.isLowSurrogate()) { "Identity contains an unpaired UTF-16 surrogate" }
                if (character.isHighSurrogate()) {
                    require(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                        "Identity contains an unpaired UTF-16 surrogate"
                    }
                    index++
                }
                index++
            }
            var hash = 0xcbf29ce484222325uL.toLong()
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                hash = (hash xor (byte.toLong() and 255)) * 0x100000001b3L
            }
            return hash
        }

        private fun fraction(seed: Long, shift: Int): Double = ((seed ushr shift) and 65535).toDouble() / 65535
        private fun circularGap(from: Double, to: Double): Double = ((to - from) % 360).let {
            if (it <= 0) it + 360 else it
        }
        private fun luminance(hue: Double, saturation: Double, brightness: Double): Double =
            hsbToRgb(hue, saturation, brightness).luminance

        fun hsbToRgb(hue: Double, saturation: Double, brightness: Double): ThemeColor {
            require(hue.isFinite() && saturation.isFinite() && brightness.isFinite())
            require(saturation in 0.0..1.0 && brightness in 0.0..1.0)
            val h = ((hue % 360 + 360) % 360) / 60
            val sector = h.toInt() % 6
            val fraction = h - h.toInt()
            val p = brightness * (1 - saturation)
            val q = brightness * (1 - fraction * saturation)
            val t = brightness * (1 - (1 - fraction) * saturation)
            return when (sector) {
                0 -> ThemeColor(brightness, t, p)
                1 -> ThemeColor(q, brightness, p)
                2 -> ThemeColor(p, brightness, t)
                3 -> ThemeColor(p, q, brightness)
                4 -> ThemeColor(t, p, brightness)
                else -> ThemeColor(brightness, p, q)
            }
        }
    }
}
