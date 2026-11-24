// AndroidOnly: WP-311 Foundation String semantics (Character, case/diacritic folding, collation) that the owned Swift relies on.
package com.meshcoreone.android.core.ui.hoppicker

import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

/**
 * Swift `String` operations used by the nodes logic, re-expressed on the JVM/Android.
 *
 * Swift iterates extended grapheme clusters (`Character`) and compares canonically equivalent
 * strings as equal; Kotlin iterates UTF-16 units. Each helper here states which Foundation call it
 * stands for. Expectations were checked against `swiftc` oracles (see the WP-311 evidence).
 */
object SwiftText {
    private val graphemes: Pattern = Pattern.compile("\\X")
    private const val DOTLESS_I = '\u0131'
    private const val FINAL_SIGMA = "\u03C2"
    private const val SIGMA = "\u03C3"

    /** Extended grapheme clusters of [text] (Swift `Array(text)` as strings). */
    fun characters(text: String): List<String> {
        val matcher = graphemes.matcher(text)
        val result = ArrayList<String>()
        while (matcher.find()) result += matcher.group()
        return result
    }

    /** `Character.isHexDigit`: a single scalar that is an ASCII or fullwidth hex digit. */
    fun isHexDigit(character: String): Boolean {
        if (character.isEmpty() || character.codePointCount(0, character.length) != 1) return false
        val scalar = character.codePointAt(0)
        return scalar in '0'.code..'9'.code || scalar in 'a'.code..'f'.code || scalar in 'A'.code..'F'.code ||
            scalar in 0xFF10..0xFF19 || scalar in 0xFF21..0xFF26 || scalar in 0xFF41..0xFF46
    }

    /** `text.filter(\.isHexDigit)`. */
    fun filterHexDigits(text: String): String = characters(text).filter(::isHexDigit).joinToString("")

    /** `!text.isEmpty && text.allSatisfy(\.isHexDigit)`. */
    fun isAllHexDigits(text: String): Boolean = text.isNotEmpty() && characters(text).all(::isHexDigit)

    /** `String.uppercased()` / `lowercased()`: locale-independent full case mapping. */
    fun uppercased(text: String): String = text.uppercase(Locale.ROOT)
    fun lowercased(text: String): String = text.lowercase(Locale.ROOT)

    /**
     * `trimmingCharacters(in: .whitespaces)`: Unicode space separators (Zs) and tab, never newlines.
     */
    fun trimmingWhitespaces(text: String): String {
        fun isWhitespace(codePoint: Int) = codePoint == '\t'.code || Character.getType(codePoint) == Character.SPACE_SEPARATOR.toInt()
        var start = 0
        var end = text.length
        while (start < end && isWhitespace(text.codePointAt(start))) start += Character.charCount(text.codePointAt(start))
        while (end > start && isWhitespace(text.codePointBefore(end))) end -= Character.charCount(text.codePointBefore(end))
        return text.substring(start, end)
    }

    /** NFC form; Swift `String ==` and `Set<String>` treat canonically equivalent strings as one. */
    fun canonical(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

    /**
     * `haystack.hasPrefix(prefix)`: canonical equivalence and whole-`Character` matching, so a
     * prefix never ends inside a grapheme of [haystack].
     */
    fun hasPrefix(haystack: String, prefix: String): Boolean {
        if (prefix.isEmpty()) return true
        val left = characters(canonical(haystack))
        val right = characters(canonical(prefix))
        return right.size <= left.size && right.indices.all { left[it] == right[it] }
    }

    /**
     * `haystack.localizedStandardContains(needle)` (current [locale]) and
     * `range(of:options:[.caseInsensitive, .diacriticInsensitive]) != nil` ([locale] = null).
     *
     * Each haystack grapheme folds independently (NFD, nonspacing marks removed, full case fold),
     * and a match must start and end on folded-grapheme boundaries, which reproduces Foundation's
     * `straße`/`stras` and ligature/Hangul partial-match refusals. Turkish/Azeri locales fold
     * `I` to dotless `ı`. Width and compatibility forms are not folded.
     */
    fun foldedContains(haystack: String, needle: String, locale: Locale?): Boolean {
        val turkic = locale?.language in setOf("tr", "az")
        val target = characters(canonical(needle)).joinToString("") { fold(it, turkic) }
        if (target.isEmpty()) return false
        val units = characters(canonical(haystack)).map { fold(it, turkic) }
        val folded = units.joinToString("")
        val starts = HashSet<Int>()
        val ends = HashSet<Int>()
        var offset = 0
        for (unit in units) {
            starts += offset
            offset += unit.length
            ends += offset
        }
        var index = folded.indexOf(target)
        while (index >= 0) {
            if (index in starts && index + target.length in ends) return true
            index = folded.indexOf(target, index + 1)
        }
        return false
    }

    private fun fold(character: String, turkic: Boolean): String {
        val stripped = buildString {
            val decomposed = Normalizer.normalize(character, Normalizer.Form.NFD)
            var index = 0
            while (index < decomposed.length) {
                val codePoint = decomposed.codePointAt(index)
                if (Character.getType(codePoint) != Character.NON_SPACING_MARK.toInt()) appendCodePoint(codePoint)
                index += Character.charCount(codePoint)
            }
        }
        return buildString {
            var index = 0
            while (index < stripped.length) {
                val codePoint = stripped.codePointAt(index)
                append(foldScalar(codePoint, turkic))
                index += Character.charCount(codePoint)
            }
        }
    }

    private fun foldScalar(codePoint: Int, turkic: Boolean): String {
        if (codePoint == DOTLESS_I.code) return DOTLESS_I.toString()
        if (turkic && codePoint == 'I'.code) return DOTLESS_I.toString()
        val scalar = String(Character.toChars(codePoint))
        return scalar.lowercase(Locale.ROOT).uppercase(Locale.ROOT).lowercase(Locale.ROOT).replace(FINAL_SIGMA, SIGMA)
    }

    /**
     * `localizedCompare` (tertiary) or `localizedCaseInsensitiveCompare` (secondary) as an ascending
     * comparator. Android's `java.text.Collator` is ICU/CLDR-backed like Foundation; the JVM test
     * runtime uses the JDK tables, which agree for letters but order punctuation and some scripts
     * differently (WP-311 deviations).
     */
    fun localizedComparator(locale: Locale, caseInsensitive: Boolean = false): Comparator<String> {
        val collator = Collator.getInstance(locale).apply {
            strength = if (caseInsensitive) Collator.SECONDARY else Collator.TERTIARY
            decomposition = Collator.CANONICAL_DECOMPOSITION
        }
        return Comparator { left, right -> synchronized(collator) { collator.compare(left, right) } }
    }
}
