// PortedFrom: MC1Services/Sources/MC1Services/Services/RadioOptions.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Settings/Sections/AdvancedRadioSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/** Feature-local mirror of `RadioOptions` (core:services); a feature may not depend on core:services. */
object RadioOptions {
    val bandwidthsHz: List<UInt> = listOf(
        7_800u, 10_400u, 15_600u, 20_800u, 31_250u, 41_700u, 62_500u, 125_000u, 250_000u, 500_000u,
    )
    val spreadingFactors: IntRange = 5..12
    val codingRates: IntRange = 5..8

    fun formatBandwidth(hz: UInt): String = when (hz) {
        7_800u -> "7.8"
        10_400u -> "10.4"
        15_600u -> "15.6"
        20_800u -> "20.8"
        31_250u -> "31.25"
        41_700u -> "41.7"
        62_500u -> "62.5"
        125_000u -> "125"
        250_000u -> "250"
        500_000u -> "500"
        // NumberFormatter en_US_POSIX, 0...2 fraction digits: half-even on the exact decimal.
        else -> BigDecimal.valueOf(hz.toLong()).movePointLeft(3)
            .setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
    }

    /** Ties keep the earlier list entry, like `Sequence.min(by:)` with a strict `<`. */
    fun nearestBandwidth(hz: UInt): UInt {
        if (hz in bandwidthsHz) return hz
        return bandwidthsHz.minBy { abs(it.toLong() - hz.toLong()) }
    }
}

/**
 * The lenient parse SwiftUI's `TextField(value:format:)` applies to typed text. ICU stops at the first
 * character it cannot use and returns what it has read, so "915abc" is 915 and "9,15" (POSIX) is 9.
 * Known residue, not asserted: a stray leading "," ("`,5`" is 5 in Swift, absent here) and
 * doubled signs ("--5" is -5 in Swift, absent here).
 */
object SwiftNumberFieldParser {
    /** `FloatingPointFormatStyle<Double>.number.locale(.posix)`: no grouping separators. */
    fun parsePosixDouble(text: String): Double? = parse(text, grouping = false).toDouble()

    /** `IntegerFormatStyle<Int>.number` in en_US: grouping accepted, fraction truncated toward zero, `Int` overflow is nil. */
    fun parseInteger(text: String): Int? = parseLong(text)?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null }

    fun parseLong(text: String): Long? {
        val parsed = parse(text, grouping = true) as? Parsed.Number ?: return null
        val truncated = parsed.value.setScale(0, RoundingMode.DOWN)
        return runCatching { truncated.longValueExact() }.getOrNull()
    }

    private const val MAX_EXPONENT = 10_000L

    private sealed interface Parsed {
        class Number(val value: BigDecimal, val negativeZero: Boolean) : Parsed
        data object NaN : Parsed
        class Infinity(val negative: Boolean) : Parsed
    }

    private fun Parsed?.toDouble(): Double? = when (this) {
        null -> null
        Parsed.NaN -> Double.NaN
        is Parsed.Infinity -> if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        is Parsed.Number -> if (value.signum() == 0) (if (negativeZero) -0.0 else 0.0) else value.toDouble()
    }

    private fun parse(text: String, grouping: Boolean): Parsed? {
        var i = skipSpace(text, 0)
        var negative = false
        if (i < text.length && (text[i] == '+' || text[i] == '-' || text[i] == '−')) {
            negative = text[i] != '+'
            i = skipSpace(text, i + 1)
        }
        keywordAt(text, i, negative)?.let { return it }
        val digits = StringBuilder()
        var fraction = 0
        var sawDigit = false
        var sawDot = false
        while (i < text.length) {
            val c = text[i]
            val digit = Character.digit(c, 10).takeIf { c.code > 0x7F || c in '0'..'9' } ?: -1
            when {
                digit >= 0 -> { digits.append(digit); sawDigit = true; if (sawDot) fraction++; i++ }
                c == '.' && !sawDot -> { sawDot = true; i++ }
                grouping && !sawDot && sawDigit && (c == ',' || c == ' ') && twoDigitsFollow(text, i + 1) -> i++
                else -> break
            }
        }
        if (!sawDigit) return null
        var exponent = 0
        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) exponent = readExponent(text, i + 1)
        val magnitude = BigDecimal(java.math.BigInteger(digits.toString()), fraction).scaleByPowerOfTen(exponent)
        val signed = if (negative) magnitude.negate() else magnitude
        return Parsed.Number(signed, negative && signed.signum() == 0)
    }

    private fun readExponent(text: String, start: Int): Int {
        var i = start
        var negative = false
        if (i < text.length && (text[i] == '+' || text[i] == '-')) { negative = text[i] == '-'; i++ }
        var value = 0L
        var read = false
        while (i < text.length && text[i] in '0'..'9') { value = minOf(value * 10 + (text[i] - '0'), MAX_EXPONENT); read = true; i++ }
        return if (!read) 0 else (if (negative) -value else value).toInt()
    }

    private fun twoDigitsFollow(text: String, start: Int): Boolean =
        start + 1 < text.length && Character.isDigit(text[start]) && Character.isDigit(text[start + 1])

    private fun skipSpace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\n' || text[i] == '\r')) i++
        return i
    }

    private fun keywordAt(text: String, i: Int, negative: Boolean): Parsed? = when {
        text.regionMatches(i, "nan", 0, 3, ignoreCase = true) -> Parsed.NaN
        text.regionMatches(i, "infinity", 0, 8, ignoreCase = true) -> Parsed.Infinity(negative)
        else -> null
    }

    /** Swift `UInt32(String)`: optional "+", ASCII digits only, no spaces, within range. */
    fun parseUInt32Strict(text: String): UInt? {
        val body = if (text.startsWith("+")) text.substring(1) else text
        if (body.isEmpty() || !body.all { it in '0'..'9' }) return null
        return body.toLongOrNull()?.takeIf { it <= UInt.MAX_VALUE.toLong() }?.toUInt()
    }
}

/** `Double(device.frequency) / 1000.0` shown with `.precision(.fractionLength(3))` and the POSIX locale. */
fun formatFrequencyMHz(frequencyKHz: UInt): String =
    BigDecimal.valueOf(frequencyKHz.toDouble() / 1000.0).setScale(3, RoundingMode.HALF_EVEN).toPlainString()

/** `CharacterSet.whitespaces` and `.whitespacesAndNewlines` exactly as printed by oracle3 (U+200B is a member of both). */
internal object SwiftCharacterSets {
    val whitespaces: Set<Char> =
        (listOf(0x0009, 0x0020, 0x00A0, 0x1680, 0x202F, 0x205F, 0x3000, 0x200B) + (0x2000..0x200A)).map { it.toChar() }.toSet()
    val whitespacesAndNewlines: Set<Char> =
        whitespaces + listOf(0x000A, 0x000B, 0x000C, 0x000D, 0x0085, 0x2028, 0x2029).map { it.toChar() }

    fun trimWhitespaces(text: String): String = text.trim { it in whitespaces }
    fun trimWhitespacesAndNewlines(text: String): String = text.trim { it in whitespacesAndNewlines }
}
