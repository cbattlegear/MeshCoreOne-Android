// PortedFrom: MC1/Views/Chats/Linkify/MessageLinkTokenizer.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.feature.chats.list.ChatMailtoPolicy
import java.util.Locale
import java.util.regex.Pattern

/**
 * Detects every link kind in one pass over the normalized string and merges the results into one
 * sorted, non-overlapping token list; overlaps resolve by [LinkKind] priority, not by start offset.
 */
object MessageLinkTokenizer {
    data class StyleContext(val isOutgoing: Boolean)
    data class Result(val tokens: List<LinkToken>, val mapCoordinate: Coordinate?)

    private val meshCoreLink: Pattern = Pattern.compile(
        """meshcore://[^${ChatCoordinateDetector.WHITE_SPACE.removeSurrounding("[", "]")}<>"]+""",
    )
    private const val MESHCORE_TRAILING = ".,;:!?)"
    private val hostOfLink = Regex("""^meshcore://([^/?#]*)""")
    private val mailtoLink = Regex("""(?i)\bmailto:[^${ChatCoordinateDetector.WHITE_SPACE.removeSurrounding("[", "]")}<>"]+""")

    fun tokenize(normalized: String, preSpans: List<LinkToken>, context: StyleContext): Result {
        val urls = (LinkDetection.detectUrls(normalized) + mailtoLink.findAll(normalized).mapNotNull { match ->
            var url = match.value
            var closingQuote = match.range.first > 0 && normalized[match.range.first - 1] == '\''
            while (url.isNotEmpty()) {
                val last = url.last()
                val unbalanced = last == ')' && url.count { it == '(' } < url.count { it == ')' }
                if (last in ".,;:!" || unbalanced) url = url.dropLast(1)
                else if (last == '\'' && closingQuote) {
                    url = url.dropLast(1)
                    closingQuote = false
                } else break
            }
            if (ChatMailtoPolicy.isOpenable(url)) DetectedUrl(match.range.first, match.range.first + url.length, url) else null
        }).sortedBy { it.start }
        val coordinates = ChatCoordinateDetector.matches(normalized)
        val hits = ArrayList<LinkToken>(preSpans)
        urls.mapTo(hits) { LinkToken(it.start, it.end, LinkKind.URL, it.url) }
        hits += meshCoreTokens(normalized)
        hits += hashtagTokens(normalized, urls.map { it.start until it.end }, context)
        val coordinateTokens = coordinates.map {
            LinkToken(it.start, it.end, LinkKind.COORDINATE, mapUrl(it.coordinate))
        }
        hits += coordinateTokens

        val merged = resolveOverlaps(hits)
        // The map preview follows the first coordinate that survived overlap resolution.
        val surviving = merged.filter { it.kind == LinkKind.COORDINATE }.map { it.start to it.end }.toSet()
        val mapCoordinate = coordinates.filter { (it.start to it.end) in surviving }.minByOrNull { it.start }?.coordinate
        return Result(merged, mapCoordinate)
    }

    /** Accepts highest priority first, drops overlaps with an accepted token, returns survivors in document order. */
    private fun resolveOverlaps(tokens: List<LinkToken>): List<LinkToken> {
        val byPriority = tokens.sortedWith(compareBy({ it.kind.ordinal }, { it.start }))
        val accepted = ArrayList<LinkToken>()
        for (token in byPriority) if (accepted.none { it.overlaps(token) }) accepted += token
        return accepted.sortedBy { it.start }
    }

    private fun meshCoreTokens(text: String): List<LinkToken> {
        if (!text.contains("meshcore://")) return emptyList()
        val matcher = meshCoreLink.matcher(text)
        val tokens = ArrayList<LinkToken>()
        while (matcher.find()) {
            val start = matcher.start()
            var end = matcher.end()
            // Trim over-captured trailing punctuation per Character (a mark fused to it blocks the trim).
            while (end > start) {
                val last = ComposerText.graphemes(text.substring(start, end)).last()
                if (last.length == 1 && last[0] in MESHCORE_TRAILING) end -= 1 else break
            }
            if (end <= start) continue
            val link = text.substring(start, end)
            val host = hostOfLink.find(link)?.groupValues?.get(1)
            if (host == "contact" || host == "channel") tokens += LinkToken(start, end, LinkKind.MESHCORE_LINK, link)
        }
        return tokens
    }

    private fun hashtagTokens(text: String, urlRanges: List<IntRange>, context: StyleContext): List<LinkToken> =
        Hashtags.extract(text, urlRanges).map {
            LinkToken(
                it.start, it.end, LinkKind.HASHTAG, "meshcoreone://hashtag/${Hashtags.normalizeName(it.name)}",
                color = if (context.isOutgoing) LinkColor.OUTGOING_TEXT else LinkColor.HASHTAG,
                underline = false, bold = true,
            )
        }

    /** `meshcore://map?lat=&lon=` with locale-independent `%.6f`, so it round-trips through the link parser. */
    fun mapUrl(coordinate: Coordinate): String =
        String.format(Locale.ROOT, "meshcore://map?lat=%.6f&lon=%.6f", coordinate.latitude, coordinate.longitude)
}
