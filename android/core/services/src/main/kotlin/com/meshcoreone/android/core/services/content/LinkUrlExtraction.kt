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
// receive the same inferred HTTP scheme as detector-produced web links; email addresses and
// bare IP addresses are not treated as web links.
//
// The mention-range exclusion depends only on `MentionUtilities.mentionPattern`'s literal
// regex string (`@\[([^\]]+)\]`), not the full `MentionUtilities` type (a separate,
// out-of-scope composer/contacts module not yet ported). Inlining the pattern avoids an
// undeclared cross-module dependency on code this WP does not own.
package com.meshcoreone.android.core.services.content

/** Pure text-scanning helpers extracted from `LinkPreviewService`. */
object LinkUrlExtraction {
    private val giphyShortCodePattern = Regex("^g:([A-Za-z0-9_-]+)$")
    private val mentionPattern = Regex("""@\[([^\]]+)\]""")
    private val httpUrlPattern = Regex(
        """(?i)(?<![\w.@:/-])(?:https?://\S+|(?:www\.)?(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,63}(?::\d{1,5})?(?:[/?#]\S*)?)""",
    )

    /**
     * Extracts a Giphy GIF URL from meshcore-open `g:{id}` message format. The entire
     * (whitespace-trimmed) text must match, mirroring the Swift original's `wholeMatch`.
     */
    fun extractGiphyGifUrl(text: String): String? {
        val trimmed = text.trim()
        val match = giphyShortCodePattern.matchEntire(trimmed) ?: return null
        return "https://media.giphy.com/media/${match.groupValues[1]}/giphy.gif"
    }

    /** The first HTTP(S) URL in [text], excluding URLs inside `@[mention]` ranges, or `null`. */
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

        return httpUrlPattern.findAll(text)
            .filter { match -> mentionRanges.none { it.overlaps(match.range) } }
            .map { match ->
                val value = match.value
                if (value.startsWith("http://", ignoreCase = true) ||
                    value.startsWith("https://", ignoreCase = true)) {
                    value
                } else {
                    "http://$value"
                }
            }
            .toList()
    }

    private fun IntRange.overlaps(other: IntRange): Boolean =
        first <= other.last && other.first <= last
}
