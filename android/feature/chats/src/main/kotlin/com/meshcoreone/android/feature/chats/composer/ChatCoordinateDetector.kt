// PortedFrom: MC1/Views/Chats/ChatCoordinateDetector.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.core.model.Coordinate
import java.util.regex.Pattern

/**
 * Single source of truth for decimal-degree pairs in message text: the linkifier and the map preview
 * both route through it. The range clamp is the only validity gate for the preview path.
 *
 * ICU `\w` is spelled out as L/Nl/M/Nd/Pc plus the joiners (it differs from ICU only for Other_Alphabetic symbols such as circled letters); `\d`, `\s` likewise (`UNICODE_CHARACTER_CLASS` is not portable to Android's regex):
 * the oracle shows ICU accepts fullwidth digits and NBSP, which [Double] parsing then rejects/accepts
 * exactly as Swift's `Double(String)` does.
 */
object ChatCoordinateDetector {
    data class Match(val start: Int, val end: Int, val coordinate: Coordinate)

    private const val WORD = """[\p{L}\p{Nl}\p{M}\p{Nd}\p{Pc}\x{200C}\x{200D}]"""
    private const val DIGIT = """\p{Nd}"""
    internal const val WHITE_SPACE = """[\x{9}-\x{D}\x{20}\x{85}\x{A0}\x{1680}\x{2000}-\x{200A}\x{2028}\x{2029}\x{202F}\x{205F}\x{3000}]"""

    private val regex: Pattern = Pattern.compile(
        """(?<![$WORD.])(-?$DIGIT{1,3}\.$DIGIT+)$WHITE_SPACE*,$WHITE_SPACE*(-?$DIGIT{1,3}\.$DIGIT+)(?!\.$DIGIT)(?!$WORD)""",
    )
    private val listTail: Pattern = Pattern.compile("""$WHITE_SPACE*,$WHITE_SPACE*-?$DIGIT""")
    private val latitudes = -90.0..90.0
    private val longitudes = -180.0..180.0

    /** All valid coordinates in document order (regex, range clamp, then the decimal-list guard). */
    fun matches(text: String): List<Match> {
        if (!text.contains(',')) return emptyList()
        val matcher = regex.matcher(text)
        val results = ArrayList<Match>()
        while (matcher.find()) {
            val latitude = matcher.group(1).toDoubleOrNull() ?: continue
            val longitude = matcher.group(2).toDoubleOrNull() ?: continue
            if (latitude !in latitudes || longitude !in longitudes) continue
            val tail = listTail.matcher(text).region(matcher.end(), text.length)
            if (tail.lookingAt()) continue // `1.0, 2.0, 3.0` is a list, not a coordinate
            results += Match(matcher.start(), matcher.end(), Coordinate(latitude, longitude))
        }
        return results
    }

    fun firstCoordinate(text: String): Coordinate? = matches(text).firstOrNull()?.coordinate
}
