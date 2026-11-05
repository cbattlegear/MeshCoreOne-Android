// PortedFrom: MC1/Models/Conversation+Filtering.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/SenderContactMatcher.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.list

import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

/**
 * Locale-independent stand-ins for Foundation's `localizedStandardContains` and
 * `localizedCaseInsensitiveCompare == .orderedSame`. Both were checked against a swiftc oracle
 * (docs/android/evidence/WP-306/oracle); see docs/android/deviations/WP-306.md for the residual gaps.
 */
object ChatTextMatching {
    private val combiningMarks = Regex("\\p{Mn}+")
    private val graphemes = Pattern.compile("\\X")
    private const val ZERO_WIDTH_JOINER = '\u200D'
    private const val DOTLESS_I = '\u0131'

    private fun caseFold(text: String): String = text.uppercase(Locale.ROOT).lowercase(Locale.ROOT)

    private fun searchCaseFold(text: String): String = buildString {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val scalar = String(Character.toChars(codePoint))
            append(
                when (codePoint) {
                    DOTLESS_I.code -> scalar
                    0x1E9E -> "ss"
                    else -> caseFold(scalar).replace('\u03C2', '\u03C3')
                },
            )
            index += Character.charCount(codePoint)
        }
    }

    private fun graphemeClusters(text: String): List<String> {
        val matcher = graphemes.matcher(Normalizer.normalize(text, Normalizer.Form.NFC))
        val result = ArrayList<String>()
        while (matcher.find()) result += matcher.group()
        return result
    }

    /** Case-insensitive, canonical-equivalence and width-insensitive equality, diacritic-sensitive. */
    fun caseInsensitiveEquals(lhs: String, rhs: String): Boolean =
        caseFold(Normalizer.normalize(lhs, Normalizer.Form.NFKC)) ==
            caseFold(Normalizer.normalize(rhs, Normalizer.Form.NFKC))

    private fun searchFoldGrapheme(grapheme: String): String {
        val decomposed = combiningMarks.replace(Normalizer.normalize(grapheme, Normalizer.Form.NFD), "")
        val widthFoldedDigits = buildString {
            var index = 0
            while (index < decomposed.length) {
                val codePoint = decomposed.codePointAt(index)
                if (codePoint in 0xFF10..0xFF19) {
                    append(('0'.code + codePoint - 0xFF10).toChar())
                } else {
                    appendCodePoint(codePoint)
                }
                index += Character.charCount(codePoint)
            }
        }
        return searchCaseFold(widthFoldedDigits)
    }

    /** Case- and diacritic-insensitive substring match; an empty needle never matches. */
    fun standardContains(haystack: String, needle: String): Boolean {
        if (needle.isEmpty()) return false
        val target = graphemeClusters(needle).joinToString("") { searchFoldGrapheme(it) }
        if (target.isEmpty()) return false

        val haystackGraphemes = graphemeClusters(haystack)
        val foldedGraphemes = haystackGraphemes.map(::searchFoldGrapheme)
        val foldedHaystack = foldedGraphemes.joinToString("")
        val boundaries = buildSet {
            var offset = 0
            add(offset)
            for ((index, grapheme) in haystackGraphemes.withIndex()) {
                if (ZERO_WIDTH_JOINER in grapheme) {
                    var sourceOffset = 0
                    while (sourceOffset < grapheme.length) {
                        val codePoint = grapheme.codePointAt(sourceOffset)
                        offset += searchFoldGrapheme(String(Character.toChars(codePoint))).length
                        sourceOffset += Character.charCount(codePoint)
                        add(offset)
                    }
                } else {
                    offset += foldedGraphemes[index].length
                }
                add(offset)
            }
        }

        var index = foldedHaystack.indexOf(target)
        while (index >= 0) {
            if (index in boundaries && index + target.length in boundaries) return true
            index = foldedHaystack.indexOf(target, index + 1)
        }
        return false
    }
}
