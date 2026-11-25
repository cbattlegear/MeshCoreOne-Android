// AndroidOnly: WP-309 Issue #729; explicit newer upstream TextRulesPreference.swift at 5e980a53e077c45a1270170612fdf5dae97792ff, not a reference-pin change.
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.UUID
import java.util.regex.Pattern

enum class TextRuleKind { PUBLIC_CHANNEL, HASHTAG_CHANNEL, PRIVATE_CHANNEL, DIRECT_MESSAGE, ROOM }
enum class TextRuleField { MESSAGE, SENDER, MESSAGE_OR_SENDER }
enum class TextRuleMatch { CONTAINS, WHOLE_FIELD }
enum class TextRuleOverride { INHERIT, ON, OFF }
enum class TextRuleSelectionMode { OFF, ALL, FIXED }

data class TextRuleKey(val radioId: RadioId, val conversationID: UUID)
data class TextRuleSelection(
    val mode: TextRuleSelectionMode,
    val keys: Set<TextRuleKey> = emptySet(),
)
data class TextRuleHighlight(val location: Int, val length: Int)
data class TextRuleHit(
    val rule: TextRule,
    val bodyHighlights: List<TextRuleHighlight>,
    val senderHighlights: List<TextRuleHighlight>,
)
data class TextRule(
    val id: UUID = UUID.randomUUID(),
    val phrase: String,
    val field: TextRuleField = TextRuleField.MESSAGE,
    val match: TextRuleMatch = TextRuleMatch.CONTAINS,
) {
    companion object {
        // Foundation's whitespaceAndNewlines includes NEL and the legacy zero-width space.
        fun isValidPhrase(phrase: String): Boolean = phrase.any {
            it !in '\t'..'\r' && it != '\u0085' && it != '\u200B' && !Character.isSpaceChar(it)
        }
    }

    val isValid: Boolean get() = isValidPhrase(phrase)

    internal fun highlights(text: String?): List<TextRuleHighlight> {
        if (text.isNullOrEmpty() || !isValid) return emptyList()
        val matcher = Pattern.compile(Pattern.quote(phrase), Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE).matcher(text)
        if (match == TextRuleMatch.WHOLE_FIELD) {
            return if (matcher.matches()) listOf(TextRuleHighlight(0, text.length)) else emptyList()
        }
        val characters = Pattern.compile("\\X").matcher(text)
        val ranges = buildList {
            while (characters.find()) add(characters.start() until characters.end())
        }
        return buildList {
            while (matcher.find()) {
                val start = ranges.first { matcher.start() in it }.first
                val end = ranges.first { matcher.end() - 1 in it }.last + 1
                val highlight = TextRuleHighlight(start, end - start)
                if (lastOrNull() != highlight) add(highlight)
            }
        }
    }
}

data class TextRulesPreference(
    val rules: List<TextRule> = emptyList(),
    val selections: Map<TextRuleKind, TextRuleSelection> = TextRuleKind.entries.associateWith {
        TextRuleSelection(if (it == TextRuleKind.PUBLIC_CHANNEL || it == TextRuleKind.HASHTAG_CHANNEL)
            TextRuleSelectionMode.ALL else TextRuleSelectionMode.OFF)
    },
) {
    fun validate() {
        require(selections.keys == TextRuleKind.entries.toSet()) { "Missing text-rule type selection" }
        require(rules.all { it.isValid } && rules.map { it.id }.distinct().size == rules.size) { "Invalid text rules" }
    }

    fun isEnabled(kind: TextRuleKind, key: TextRuleKey?, override: TextRuleOverride): Boolean = when (override) {
        TextRuleOverride.ON -> true
        TextRuleOverride.OFF -> false
        TextRuleOverride.INHERIT -> when (selections.getValue(kind).mode) {
            TextRuleSelectionMode.OFF -> false
            TextRuleSelectionMode.ALL -> true
            TextRuleSelectionMode.FIXED -> key in selections.getValue(kind).keys
        }
    }

    fun firstMatch(body: String?, sender: String?): TextRuleHit? {
        for (rule in rules) {
            val bodies = if (rule.field == TextRuleField.SENDER) emptyList() else rule.highlights(body)
            val senders = if (rule.field == TextRuleField.MESSAGE) emptyList() else rule.highlights(sender)
            if (bodies.isNotEmpty() || senders.isNotEmpty()) return TextRuleHit(rule, bodies, senders)
        }
        return null
    }
}

data class TextRuleConversation(
    val key: TextRuleKey,
    val kind: TextRuleKind,
    val name: String,
    val radioName: String,
    val override: TextRuleOverride,
)

data class BlockedTextIdentity(
    val radioId: RadioId,
    val conversationID: UUID?,
    val senderPrefix: Bytes?,
    val senderTimestamp: UInt,
    val textType: UByte,
    val wireText: String,
)

data class RecentlyBlockedText(
    val id: UUID = UUID.randomUUID(),
    val identity: BlockedTextIdentity,
    val arrivedAt: Instant,
    val body: String,
    val sender: String?,
    val conversationName: String?,
    val radioName: String?,
    val hit: TextRuleHit,
    val contactID: UUID? = null,
)
