// PortedFrom: MC1Services/Sources/MC1Services/Utilities/MentionUtilities.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.protocol.model.ContactType
import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

/**
 * MeshCore mention format `@[nodeContactName]`. Character-level logic follows Swift `Character`
 * semantics (extended graphemes, `White_Space`, canonical equality); see [SwiftText].
 */
object MentionUtilities {
    /** The pattern for matching mentions: `@[name]`. */
    const val MENTION_PATTERN: String = """@\[([^\]]+)\]"""

    /** Pre-compiled mention pattern. */
    val mentionRegex: Pattern = Pattern.compile(MENTION_PATTERN)

    /** ICU `\s` (the Unicode `White_Space` property), spelled out so the JDK matches the same set. */
    private const val ICU_WHITE_SPACE = """[\x{9}-\x{D}\x{20}\x{85}\x{A0}\x{1680}\x{2000}-\x{200A}\x{2028}\x{2029}\x{202F}\x{205F}\x{3000}]"""
    private val leadingMentionRegex: Pattern = Pattern.compile("""^@\[[^\]]+\]$ICU_WHITE_SPACE*""")
    private val combiningMarks = Regex("""\p{Mn}+""")
    private const val REPLY_PREVIEW_LENGTH = 10

    /** Creates a mention string from a node contact name (the mesh name, not a nickname). */
    fun createMention(name: String): String = "@[$name]"

    /**
     * Appends a mention to the composer draft, inserting a separating space only when the draft does not
     * already end in whitespace, and always leaving a trailing space.
     */
    fun appendMention(name: String, draft: String): String {
        val mention = createMention(name)
        if (draft.isEmpty()) return "$mention "
        val separator = if (SwiftText.graphemes(draft).lastOrNull()?.let(SwiftText::isWhitespaceCharacter) == true) "" else " "
        return draft + separator + mention + " "
    }

    /** All mentioned contact names (without the `@[]` wrapper), in order. */
    fun extractMentions(text: String): List<String> {
        val matcher = mentionRegex.matcher(text)
        val result = ArrayList<String>()
        while (matcher.find()) result.add(matcher.group(1))
        return result
    }

    /**
     * The active mention query being typed, or null. Triggers when `@` is at the start or after
     * whitespace; a standalone `@` yields an empty query; completed or unclosed bracketed mentions
     * never show suggestions.
     */
    fun detectActiveMention(text: String): String? {
        if (text.isEmpty()) return null
        val characters = SwiftText.graphemes(text)
        var searchEnd = characters.size
        while (true) {
            val atIndex = (searchEnd - 1 downTo 0).firstOrNull { SwiftText.characterEquals(characters[it], "@") } ?: return null
            val isAfterWhitespace = atIndex > 0 && SwiftText.isWhitespaceCharacter(characters[atIndex - 1])
            if (atIndex != 0 && !isAfterWhitespace) {
                searchEnd = atIndex // mid-word `@` (like email@): try an earlier one
                continue
            }
            val afterAt = characters.subList(atIndex + 1, characters.size)
            if (afterAt.isEmpty()) return ""
            val first = afterAt.first()
            if (SwiftText.isWhitespaceCharacter(first) || SwiftText.characterEquals(first, "@")) return null
            if (SwiftText.characterEquals(first, "[")) {
                val close = afterAt.indexOfFirst { SwiftText.characterEquals(it, "]") }
                if (close < 0) return null // unclosed bracket: a manual mention in progress
                if (close == afterAt.size - 1) return null // completed mention with nothing after it
                searchEnd = atIndex
                continue
            }
            return afterAt.takeWhile { !SwiftText.isWhitespaceCharacter(it) }.joinToString("")
        }
    }

    /**
     * Chat-type contacts whose display name contains [query] (case- and diacritic-insensitive), sorted by
     * recency from [senderOrder] (keyed by mesh name) and then alphabetically (case-insensitive, locale
     * collation). Sorting is stable, like Swift's.
     */
    fun filterContacts(
        contacts: List<ContactDTO>,
        query: String,
        senderOrder: Map<String, UInt>? = null,
        locale: Locale = Locale.getDefault(),
    ): List<ContactDTO> {
        val foldedQuery = searchFold(query)
        val filtered = contacts
            .filter { it.type == ContactType.CHAT }
            .filter { query.isEmpty() || searchFold(it.displayName).contains(foldedQuery) }
        val collator = caseInsensitiveCollator(locale)
        val alphabetical = Comparator<ContactDTO> { a, b -> collator.compare(a.displayName, b.displayName) }
        if (senderOrder.isNullOrEmpty()) return filtered.sortedWith(alphabetical)
        return filtered.sortedWith { a, b ->
            val aTimestamp = SwiftText.canonicalLookup(senderOrder, a.name)
            val bTimestamp = SwiftText.canonicalLookup(senderOrder, b.name)
            when {
                aTimestamp != null && bTimestamp != null -> bTimestamp.compareTo(aTimestamp)
                aTimestamp != null -> -1
                bTimestamp != null -> 1
                else -> alphabetical.compare(a, b)
            }
        }
    }

    /** Whether [text] mentions [selfName] (Foundation case-insensitive comparison). */
    fun containsSelfMention(text: String, selfName: String): Boolean =
        extractMentions(text).any { SwiftText.caseInsensitiveEquals(it, selfName) }

    /**
     * Reply text: a mention plus a quoted 10-character preview of the original, after stripping any
     * leading mention. Lengths count Swift `Character`s (graphemes).
     */
    fun buildReplyText(mentionName: String, messageText: String): String {
        val matcher = leadingMentionRegex.matcher(messageText)
        val previewSource = if (matcher.find()) messageText.substring(matcher.end()) else messageText
        val characters = SwiftText.graphemes(previewSource)
        val preview = characters.take(REPLY_PREVIEW_LENGTH).joinToString("")
        val suffix = if (characters.size > REPLY_PREVIEW_LENGTH) ".." else ""
        return "${createMention(mentionName)}\n>$preview$suffix\n"
    }

    /** `localizedStandardContains` folding: canonical decomposition, marks stripped, case folded. */
    private fun searchFold(text: String): String {
        val stripped = combiningMarks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")
        return stripped.uppercase(Locale.ROOT).lowercase(Locale.ROOT)
    }

    /** `localizedCaseInsensitiveCompare`: accent-sensitive, case-insensitive locale collation. */
    private fun caseInsensitiveCollator(locale: Locale): Collator = Collator.getInstance(locale).apply {
        strength = Collator.SECONDARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }
}
