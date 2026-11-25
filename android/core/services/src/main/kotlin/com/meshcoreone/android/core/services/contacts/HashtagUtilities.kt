// PortedFrom: MC1Services/Sources/MC1Services/Utilities/HashtagUtilities.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.contacts

import java.util.Locale

/** Detects and processes hashtag channel references in messages. */
object HashtagUtilities {
    internal const val HASHTAG_PATTERN = "#[A-Za-z0-9][A-Za-z0-9-]*"

    /** Pre-compiled hashtag regex (avoids recompilation per call). */
    internal val hashtagRegex = Regex(HASHTAG_PATTERN)

    /**
     * A detected hashtag and its location in the source text. [range] is an inclusive range of
     * UTF-16 offsets (the JVM string index space) where the source used `Range<String.Index>`.
     */
    data class DetectedHashtag(val name: String, val range: IntRange)

    /** Extracts all valid hashtags from [text], excluding those within http(s) URLs. */
    fun extractHashtags(text: String): List<DetectedHashtag> = extractHashtags(text, findURLRanges(text))

    /** Extracts all valid hashtags from [text], skipping any inside pre-computed [urlRanges]. */
    fun extractHashtags(text: String, urlRanges: List<IntRange>): List<DetectedHashtag> {
        if (text.isEmpty()) return emptyList()
        return hashtagRegex.findAll(text).mapNotNull { match ->
            val range = match.range
            val insideURL = urlRanges.any { range.first >= it.first && range.last <= it.last }
            if (insideURL) null else DetectedHashtag(match.value, range)
        }.toList()
    }

    /** True when [name] (without `#`) starts alphanumeric and continues with letters, digits or hyphens. */
    fun isValidHashtagName(name: String): Boolean {
        val scalars = name.codePoints().toArray()
        if (scalars.isEmpty() || !isAllowedHashtagNameScalar(scalars[0], allowsHyphen = false)) return false
        return scalars.all { isAllowedHashtagNameScalar(it, allowsHyphen = true) }
    }

    /** Lowercases, drops disallowed characters, then strips leading hyphens. */
    fun sanitizeHashtagNameInput(input: String): String {
        val kept = buildString {
            input.lowercase(Locale.ROOT).codePoints().forEach { scalar ->
                if (isAllowedHashtagNameScalar(scalar, allowsHyphen = true)) appendCodePoint(scalar)
            }
        }
        return kept.trimStart('-')
    }

    /** Lowercases and removes one leading `#` (compared per grapheme, as Swift `hasPrefix` does). */
    fun normalizeHashtagName(name: String): String {
        val lowered = name.lowercase(Locale.ROOT)
        return if (firstGrapheme.find(lowered)?.value == "#") lowered.substring(1) else lowered
    }

    private val firstGrapheme = Regex("""^\X""")

    private fun isAllowedHashtagNameScalar(scalar: Int, allowsHyphen: Boolean): Boolean = when (scalar) {
        in 48..57, in 65..90, in 97..122 -> true
        45 -> allowsHyphen
        else -> false
    }

    /**
     * JVM stand-in for the source `NSDataDetector` link scan filtered to http/https: explicit
     * `http://`/`https://` links and `www.` hosts (which the detector resolves to `http`), with
     * trailing sentence punctuation and unbalanced closing parentheses trimmed as the detector does.
     */
    private val urlCandidate = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"]+""")
    private const val TRAILING_PUNCTUATION = ".,;:!?'\""

    internal fun findURLRanges(text: String): List<IntRange> = urlCandidate.findAll(text).mapNotNull { match ->
        var end = match.range.last
        while (end >= match.range.first) {
            val character = text[end]
            val unbalancedParen = character == ')' &&
                text.substring(match.range.first, end + 1).count { it == '(' } < text.substring(match.range.first, end + 1).count { it == ')' }
            if (character in TRAILING_PUNCTUATION || unbalancedParen) end-- else break
        }
        if (end < match.range.first) null else match.range.first..end
    }.toList()
}
