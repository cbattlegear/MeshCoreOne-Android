// PortedFrom: MC1/Services/LinkPreviewService.swift@db14559b39d32322b06477c6ae676112f583db50
// Only the pure text-scanning portion of `LinkPreviewService` (URL extraction, the
// meshcore-open `g:{id}` Giphy short-code, and mention-range exclusion) is ported here.
// The network-fetching portion (`fetchMetadata`, LinkPresentation) has no Android
// equivalent and is a separate, still-pending native-adapter decision; the HTML
// `og:image` scrape fallback is ported in `LinkPreviewHtmlMetadata.kt`/`LinkPreviewScraper.kt`.
//
// The Swift original detects links via `NSDataDetector(types: .link)`, filters to
// http/https, then excludes matches overlapping mentions. The JVM adaptation recognizes
// explicit HTTP(S) URLs and web-domain forms (including bare domains) so mention suppression
// does not depend on the detector happening to reject the mention text first. Bare domains
// receive HTTPS because Android does not permit cleartext network traffic by default; email
// addresses and bare IP addresses are not treated as web links.
//
// The mention-range exclusion depends only on `MentionUtilities.mentionPattern`'s literal
// regex string (`@\[([^\]]+)\]`), not the full `MentionUtilities` type (a separate,
// out-of-scope composer/contacts module not yet ported). Inlining the pattern avoids an
// undeclared cross-module dependency on code this WP does not own.
package com.meshcoreone.android.core.services.content

import java.util.Locale

/** Pure text-scanning helpers extracted from `LinkPreviewService`. */
object LinkUrlExtraction {
    private val giphyShortCodePattern = Regex("^g:([A-Za-z0-9_-]+)$")
    private val mentionPattern = Regex("""@\[([^\]]+)\]""")
    private val explicitHttpUrlPattern = Regex("""(?i)https?://\S+""")
    private val uriPattern = Regex("""(?i)\b[a-z][a-z0-9+.-]*://\S+""")
    private val emailPattern = Regex(
        """(?i)[\w.!#$%&'*+/=?^_`{|}~-]+@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+(?:[a-z]{2,63}|xn--[a-z0-9-]{2,59})""",
    )
    private val bareDomainPattern = Regex(
        """(?i)(?<![\w.@/-])(?:www\.)?(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+(?:[a-z]{2,63}|xn--[a-z0-9][a-z0-9-]{1,57}[a-z0-9])(?::\d{1,5})?(?:[/?#]\S*)?(?![\w@+%-])""",
    )
    private data class UrlMatch(val range: IntRange, val value: String, val explicitScheme: Boolean)

    /**
     * Extracts a Giphy GIF URL from meshcore-open `g:{id}` message format. The entire
     * (whitespace-trimmed) text must match, mirroring the Swift original's `wholeMatch`.
     */
    fun extractGiphyGifUrl(text: String): String? {
        val trimmed = text.trim()
        val match = giphyShortCodePattern.matchEntire(trimmed) ?: return null
        return "https://media.giphy.com/media/${match.groupValues[1]}/giphy.gif"
    }

    /** The first web URL in [text], excluding URLs inside `@[mention]` ranges, or `null`. */
    fun extractFirstUrl(text: String): String? = extractAllUrls(text).firstOrNull()

    /**
     * Every HTTP(S) URL in [text], in document order. A meshcore-open `g:{id}` short-code
     * that is the entire message is expanded and returned as the sole result, without a
     * detector pass (matching the Swift original, which checks the Giphy format first and
     * returns immediately when it matches).
     */
    fun extractAllUrls(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        extractGiphyGifUrl(text)?.let { return listOf(it) }

        val mentionRanges = mentionPattern.findAll(text).map { it.range }.toList()
        val embeddedLinkRanges = uriPattern.findAll(text).map { it.range }.toList()
        val emailRanges = emailPattern.findAll(text).map { it.range }.toList()

        val matches = buildList {
            explicitHttpUrlPattern.findAll(text).forEach { match ->
                val value = trimUrlCandidate(match.value)
                if (value.isNotEmpty()) add(UrlMatch(match.range.first..(match.range.first + value.lastIndex), value, true))
            }
            bareDomainPattern.findAll(text).forEach { match ->
                val value = trimUrlCandidate(match.value)
                if (value.isNotEmpty()) add(UrlMatch(match.range.first..(match.range.first + value.lastIndex), value, false))
            }
        }

        val accepted = mutableListOf<UrlMatch>()
        for (match in matches.sortedBy { it.range.first }) {
            if (mentionRanges.any { it.overlaps(match.range) }) continue
            if (accepted.any { it.range.contains(match.range.first) }) continue
            if (!match.explicitScheme &&
                (emailRanges.any { it.contains(match.range.first) } ||
                    embeddedLinkRanges.any { it.contains(match.range.first) })
            ) continue
            accepted.add(match)
        }

        return accepted.map { match ->
                if (match.explicitScheme) {
                    val scheme = match.value.substringBefore("://").lowercase(Locale.ROOT)
                    "$scheme://${match.value.substringAfter("://")}"
                } else {
                    "https://${match.value}"
                }
            }
    }

    private fun trimUrlCandidate(value: String): String {
        var candidate = value
        while (candidate.isNotEmpty()) {
            val withoutPunctuation = candidate.dropLastWhile { it in ".,!?;:" }
            if (withoutPunctuation != candidate) {
                candidate = withoutPunctuation
                continue
            }
            val opening = when (candidate.last()) {
                ')' -> '('
                ']' -> '['
                '}' -> '{'
                else -> return candidate
            }
            if (candidate.count { it == opening } >= candidate.count { it == candidate.last() }) return candidate
            candidate = candidate.dropLast(1)
        }
        return candidate
    }

    private fun IntRange.overlaps(other: IntRange): Boolean =
        first <= other.last && other.first <= last
}
