// PortedFrom: MC1/Views/Chats/Linkify/MessageTextNormalizer.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.feature.chats.list.ChatTextMatching

/**
 * The string-shrinking pre-pass: `@[name]` becomes `@name` and a `<pubkey:type:name>` contact share
 * becomes its sanitized name, each as a linkable span. After this every other detector runs on one
 * stable string, so no later pass can desynchronize offsets.
 */
object MessageTextNormalizer {
    data class StyleContext(val isOutgoing: Boolean, val currentUserName: String?)
    data class Result(val text: String, val spans: List<LinkToken>)

    private class Replacement(val start: Int, val end: Int, val text: String, val token: (Int, Int) -> LinkToken)

    fun normalize(text: String, context: StyleContext): Result {
        val contactRanges = contactTokenRanges(text)
        val replacements = (mentionReplacements(text, context, contactRanges) + contactReplacements(text))
            .sortedBy { it.start }
        if (replacements.isEmpty()) return Result(text, emptyList())

        val out = StringBuilder()
        val spans = ArrayList<LinkToken>()
        var cursor = 0
        for (replacement in replacements) {
            out.append(text, cursor, replacement.start)
            val spanStart = out.length
            out.append(replacement.text)
            spans += replacement.token(spanStart, out.length)
            cursor = replacement.end
        }
        out.append(text, cursor, text.length)
        return Result(out.toString(), spans)
    }

    private fun contactTokenRanges(text: String): List<IntRange> {
        if (!text.contains('<')) return emptyList()
        val matcher = ContactShare.tokenRegex.matcher(text)
        val ranges = ArrayList<IntRange>()
        while (matcher.find()) ranges += matcher.start() until matcher.end()
        return ranges
    }

    private fun mentionReplacements(text: String, context: StyleContext, excluded: List<IntRange>): List<Replacement> {
        val matcher = MentionUtilities.mentionRegex.matcher(text)
        val result = ArrayList<Replacement>()
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            // A contact token's name is attacker-controlled; mentions inside it belong to the chip.
            if (excluded.any { it.first < end && start <= it.last }) continue
            val name = matcher.group(1)
            val self = context.currentUserName?.let { ChatTextMatching.caseInsensitiveEquals(name, it) } == true
            val url = MentionDeeplink.url(name)
            result += Replacement(start, end, "@$name") { s, e ->
                LinkToken(
                    s, e, LinkKind.MENTION, url,
                    color = if (context.isOutgoing) LinkColor.BASE else LinkColor.MENTION_IDENTITY,
                    colorKey = name.takeUnless { context.isOutgoing }, selfMention = self,
                )
            }
        }
        return result
    }

    private fun contactReplacements(text: String): List<Replacement> {
        if (!text.contains('<')) return emptyList()
        val matcher = ContactShare.tokenRegex.matcher(text)
        val result = ArrayList<Replacement>()
        while (matcher.find()) {
            val shared = ContactShare.parseShare(matcher.group()) ?: continue
            // Sanitize once; the same clean name feeds the chip and the add-contact URL. Nothing left keeps the literal token.
            val clean = displayName(shared.name)
            if (clean.isEmpty()) continue
            val url = ContactShare.exportContactUri(clean, shared.publicKey, shared.type)
            result += Replacement(matcher.start(), matcher.end(), clean) { s, e -> LinkToken(s, e, LinkKind.CONTACT_SHARE, url) }
        }
        return result
    }

    /** Removes bidi controls, default-ignorable code points and control/format/separator scalars. */
    fun displayName(name: String): String {
        val out = StringBuilder(name.length)
        var index = 0
        while (index < name.length) {
            val codePoint = name.codePointAt(index)
            if (!isStrippable(codePoint)) out.appendCodePoint(codePoint)
            index += Character.charCount(codePoint)
        }
        return out.toString()
    }

    private fun isStrippable(codePoint: Int): Boolean {
        if (isDefaultIgnorable(codePoint)) return true
        return when (Character.getType(codePoint).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
            else -> false
        }
    }

    /** Unicode `Default_Ignorable_Code_Point` (includes every bidi control; checked against the oracle). */
    private fun isDefaultIgnorable(codePoint: Int): Boolean = when (codePoint) {
        0x00AD, 0x034F, 0x061C, in 0x115F..0x1160, in 0x17B4..0x17B5, in 0x180B..0x180F, in 0x200B..0x200F,
        in 0x202A..0x202E, in 0x2060..0x206F, 0x3164, in 0xFE00..0xFE0F, 0xFEFF, 0xFFA0, in 0xFFF0..0xFFF8,
        in 0x1BCA0..0x1BCA3, in 0x1D173..0x1D17A, in 0xE0000..0xE0FFF -> true
        else -> false
    }
}
