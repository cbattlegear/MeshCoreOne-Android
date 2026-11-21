// PortedFrom: MC1Services/Sources/MC1Services/Utilities/MentionUtilities.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel+Channels.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.stableUUID
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.chats.list.ChatTextMatching
import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

/**
 * Feature-local mirror of `MentionUtilities` (type-placement: the WP-213 original is in core:services,
 * which features may not import; the app layer may bind the original through [MentionResolver]-style
 * seams, the composer itself uses this mirror). Character logic follows Swift `Character` semantics;
 * folding and case-insensitive comparison reuse the WP-306 [ChatTextMatching] oracle-pinned helpers.
 */
object MentionUtilities {
    /** `@[name]`. */
    const val MENTION_PATTERN: String = """@\[([^\]]+)\]"""
    val mentionRegex: Pattern = Pattern.compile(MENTION_PATTERN)

    private const val ICU_WHITE_SPACE = """[\x{9}-\x{D}\x{20}\x{85}\x{A0}\x{1680}\x{2000}-\x{200A}\x{2028}\x{2029}\x{202F}\x{205F}\x{3000}]"""
    private val leadingMentionRegex: Pattern = Pattern.compile("""^@\[[^\]]+\]$ICU_WHITE_SPACE*""")
    private const val REPLY_PREVIEW_LENGTH = 10

    fun createMention(name: String): String = "@[$name]"

    /** Appends a mention to a draft with exactly one separating space and a trailing space. */
    fun appendMention(name: String, draft: String): String {
        val mention = createMention(name)
        if (draft.isEmpty()) return "$mention "
        val endsInWhitespace = ComposerText.graphemes(draft).lastOrNull()?.let(ComposerText::isWhitespaceCharacter) == true
        return draft + (if (endsInWhitespace) "" else " ") + mention + " "
    }

    fun extractMentions(text: String): List<String> {
        val matcher = mentionRegex.matcher(text)
        val result = ArrayList<String>()
        while (matcher.find()) result.add(matcher.group(1))
        return result
    }

    /**
     * The `@query` being typed, or null. `@` must start the text or follow whitespace; a bare `@` yields
     * an empty query; completed or unclosed bracket mentions yield null (a mid-word `@` retries earlier ones).
     */
    fun detectActiveMention(text: String): String? {
        if (text.isEmpty()) return null
        val characters = ComposerText.graphemes(text)
        var searchEnd = characters.size
        while (true) {
            val atIndex = (searchEnd - 1 downTo 0).firstOrNull { sameCharacter(characters[it], "@") } ?: return null
            val afterWhitespace = atIndex > 0 && ComposerText.isWhitespaceCharacter(characters[atIndex - 1])
            if (atIndex != 0 && !afterWhitespace) {
                searchEnd = atIndex
                continue
            }
            val afterAt = characters.subList(atIndex + 1, characters.size)
            if (afterAt.isEmpty()) return ""
            val first = afterAt.first()
            if (ComposerText.isWhitespaceCharacter(first) || sameCharacter(first, "@")) return null
            if (sameCharacter(first, "[")) {
                val close = afterAt.indexOfFirst { sameCharacter(it, "]") }
                if (close < 0 || close == afterAt.size - 1) return null
                searchEnd = atIndex
                continue
            }
            return afterAt.takeWhile { !ComposerText.isWhitespaceCharacter(it) }.joinToString("")
        }
    }

    /**
     * Replaces the last literal `@query` in [text] with `@[name] ` (the mesh [name], never a nickname).
     * Mirrors the iOS `range(of:options: .backwards)` + `replaceSubrange`; returns [text] unchanged when
     * there is no active mention.
     */
    fun insertMention(text: String, name: String): String {
        val query = detectActiveMention(text) ?: return text
        val pattern = "@$query"
        val start = text.lastIndexOf(pattern)
        if (start < 0) return text
        return text.substring(0, start) + createMention(name) + " " + text.substring(start + pattern.length)
    }

    /** Add channel senders that are not saved contacts, without exposing them as saved contacts elsewhere. */
    fun channelCandidates(
        contacts: List<ContactDTO>,
        senderOrder: Map<String, UInt>,
        radioId: RadioId,
    ): Pair<List<ContactDTO>, Map<String, UInt>> {
        val knownNames = contacts.mapTo(mutableSetOf()) { Normalizer.normalize(it.name, Normalizer.Form.NFC) }
        val order = linkedMapOf<String, UInt>()
        val canonicalNames = mutableMapOf<String, String>()
        val synthetic = mutableListOf<ContactDTO>()
        for ((rawName, timestamp) in senderOrder) {
            val name = rawName.trim { it == '\u200B' || (ComposerText.isWhiteSpaceScalar(it.code) && it !in '\n'..'\r' && it != '\u0085' && it != '\u2028' && it != '\u2029') }
            if (name.isEmpty() || ComposerText.graphemes(name).size > 128) continue
            val canonical = Normalizer.normalize(name, Normalizer.Form.NFC)
            val key = canonicalNames.getOrPut(canonical) { name }
            order[key] = maxOf(timestamp, order[key] ?: 0u)
            if (knownNames.add(canonical)) {
                synthetic += ContactDTO(
                    id = name.stableUUID,
                    radioId = radioId,
                    publicKey = Bytes.EMPTY,
                    name = name,
                    typeRawValue = ContactType.CHAT.rawValue,
                    lastHeardTimestamp = null,
                )
            }
        }
        return (contacts + synthetic) to order
    }

    /**
     * Chat contacts whose display name contains [query] (`localizedStandardContains`), ordered by
     * [senderOrder] recency (keyed by mesh name, canonical equality) then case-insensitively by name.
     */
    fun filterContacts(
        contacts: List<ContactDTO>,
        query: String,
        senderOrder: Map<String, UInt>? = null,
        locale: Locale = Locale.getDefault(),
    ): List<ContactDTO> {
        val filtered = contacts
            .filter { it.type == ContactType.CHAT }
            .filter { query.isEmpty() || ChatTextMatching.standardContains(it.displayName, query) }
        val collator = Collator.getInstance(locale).apply {
            strength = Collator.SECONDARY
            decomposition = Collator.CANONICAL_DECOMPOSITION
        }
        val alphabetical = Comparator<ContactDTO> { a, b -> collator.compare(a.displayName, b.displayName) }
        if (senderOrder.isNullOrEmpty()) return filtered.sortedWith(alphabetical)
        return filtered.sortedWith { a, b ->
            val aStamp = lookup(senderOrder, a.name)
            val bStamp = lookup(senderOrder, b.name)
            when {
                aStamp != null && bStamp != null -> bStamp.compareTo(aStamp)
                aStamp != null -> -1
                bStamp != null -> 1
                else -> alphabetical.compare(a, b)
            }
        }
    }

    fun containsSelfMention(text: String, selfName: String): Boolean =
        extractMentions(text).any { ChatTextMatching.caseInsensitiveEquals(it, selfName) }

    /** Reply text: mention + quoted 10-grapheme preview (leading mention stripped). */
    fun buildReplyText(mentionName: String, messageText: String): String {
        val matcher = leadingMentionRegex.matcher(messageText)
        val source = if (matcher.find()) messageText.substring(matcher.end()) else messageText
        val characters = ComposerText.graphemes(source)
        val suffix = if (characters.size > REPLY_PREVIEW_LENGTH) ".." else ""
        return "${createMention(mentionName)}\n>${characters.take(REPLY_PREVIEW_LENGTH).joinToString("")}$suffix\n"
    }

    private fun sameCharacter(character: String, other: String): Boolean =
        Normalizer.normalize(character, Normalizer.Form.NFC) == Normalizer.normalize(other, Normalizer.Form.NFC)

    private fun <V> lookup(table: Map<String, V>, key: String): V? {
        table[key]?.let { return it }
        val canonical = Normalizer.normalize(key, Normalizer.Form.NFC)
        return table.entries.firstOrNull { Normalizer.normalize(it.key, Normalizer.Form.NFC) == canonical }?.value
    }
}
