// PortedFrom: MC1Services/Sources/MC1Services/Utilities/HashtagUtilities.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Linkify/MessageLinkTokenizer.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.composer

import java.util.Locale

/** A detected web link: UTF-16 [start]..[end) and the openable [url] (`www.` hosts resolve to `https://`). */
data class DetectedUrl(val start: Int, val end: Int, val url: String)

/**
 * Link scan feeding both URL tokens and the ranges hashtags must skip. The JVM has no `NSDataDetector`,
 * so this matches explicit `http(s)://` links and `www.` hosts and trims trailing sentence punctuation
 * and unbalanced `)` as the detector does. Bare domains (`example.com`) and emails are NOT linked
 * by this UI tokenizer; its broader detector adaptation remains tracked by #403. WP-218's
 * independent preview-content extractor recognizes a bounded ASCII bare-domain subset.
 */
object LinkDetection {
    private val candidate = Regex("""(?i)\b(?:https?://|www\.)[^${ChatCoordinateDetector.WHITE_SPACE.removeSurrounding("[", "]")}<>"]+""")
    private const val TRAILING = ".,;:!?'\""

    fun detectUrls(text: String): List<DetectedUrl> = candidate.findAll(text).mapNotNull { match ->
        var end = match.range.last
        while (end >= match.range.first) {
            val char = text[end]
            val span = text.substring(match.range.first, end + 1)
            val unbalanced = char == ')' && span.count { it == '(' } < span.count { it == ')' }
            if (char in TRAILING || unbalanced) end-- else break
        }
        if (end < match.range.first) return@mapNotNull null
        val raw = text.substring(match.range.first, end + 1)
        val prefixLength = if (raw.startsWith("www.", ignoreCase = true)) 4 else raw.indexOf("://") + 3
        if (raw.length <= prefixLength) return@mapNotNull null // scheme or `www.` with no host after trimming
        val url = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
        DetectedUrl(match.range.first, end + 1, url)
    }.toList()
}

/** `HashtagUtilities` (`#[A-Za-z0-9][A-Za-z0-9-]*`) with URL-range exclusion. */
object Hashtags {
    data class Detected(val name: String, val start: Int, val end: Int)

    private val regex = Regex("#[A-Za-z0-9][A-Za-z0-9-]*")

    fun extract(text: String, urlRanges: List<IntRange>): List<Detected> {
        if (text.isEmpty()) return emptyList()
        return regex.findAll(text).mapNotNull { match ->
            val inside = urlRanges.any { match.range.first >= it.first && match.range.last <= it.last }
            if (inside) null else Detected(match.value, match.range.first, match.range.last + 1)
        }.toList()
    }

    /** Lowercases and removes one leading `#` (compared per grapheme, as Swift `hasPrefix` does). */
    fun normalizeName(name: String): String {
        val lowered = name.lowercase(Locale.ROOT)
        return if (ComposerText.graphemes(lowered).firstOrNull() == "#") lowered.substring(1) else lowered
    }
}
