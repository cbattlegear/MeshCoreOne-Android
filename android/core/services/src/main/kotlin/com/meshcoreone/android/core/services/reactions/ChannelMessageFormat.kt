// PortedFrom: MC1Services/Sources/MC1Services/Utilities/ChannelMessageFormat.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.services.rendering.SwiftText

/**
 * WP-208 stand-in: a package-local copy of `ChannelMessageFormat` (owned by WP-208, not on this branch) for
 * [HeardRepeatsService] and [ChannelRXCorrelation]. Its API mirrors WP-208's Kotlin
 * `messaging.ChannelMessageFormat` (`parse(text): Parsed?`) so this file can be deleted and the import
 * swapped once WP-208 lands.
 *
 * Parses the "NodeName: MessageText" format of decrypted channel messages (the firmware prepends the sender
 * name before encryption). Matches the Swift source exactly: the split is at the first `:` *Character*
 * (a colon carrying a combining mark is not a separator) and both halves are trimmed with Foundation
 * `CharacterSet.whitespaces`, which works per Unicode scalar and also contains U+200B.
 */
internal object ChannelMessageFormat {
    private const val SEPARATOR = ":"

    data class Parsed(val senderName: String, val messageText: String)

    /** Returns (senderName, messageText), or null when there is no `:` or it is the first Character. */
    fun parse(text: String): Parsed? {
        val characters = SwiftText.graphemes(text)
        val colon = characters.indexOf(SEPARATOR)
        if (colon <= 0) return null
        val senderName = trimmingWhitespaces(characters.subList(0, colon).joinToString(""))
        if (colon + 1 >= characters.size) return Parsed(senderName, "")
        return Parsed(senderName, trimmingWhitespaces(characters.subList(colon + 1, characters.size).joinToString("")))
    }

    /** `trimmingCharacters(in: .whitespaces)`: strips leading and trailing whitespace scalars. */
    private fun trimmingWhitespaces(text: String): String {
        var start = 0
        var end = text.length
        while (start < end && isWhitespaceScalar(text.codePointAt(start))) start += Character.charCount(text.codePointAt(start))
        while (end > start && isWhitespaceScalar(text.codePointBefore(end))) end -= Character.charCount(text.codePointBefore(end))
        return text.substring(start, end)
    }

    /**
     * Foundation `CharacterSet.whitespaces` as enumerated from the Swift 6.3.2 runtime over every scalar:
     * U+0009, U+0020, U+00A0, U+1680, U+2000–U+200B, U+202F, U+205F, U+3000. Unlike the JDK `Zs` category
     * it includes U+200B ZERO WIDTH SPACE.
     */
    private fun isWhitespaceScalar(codePoint: Int): Boolean = when (codePoint) {
        0x09, 0x20, 0xA0, 0x1680, in 0x2000..0x200B, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }
}
