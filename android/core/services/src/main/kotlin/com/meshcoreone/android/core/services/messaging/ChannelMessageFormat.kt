// PortedFrom: MC1Services/Sources/MC1Services/Utilities/ChannelMessageFormat.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.messaging

import java.util.regex.Pattern

object ChannelMessageFormat {
    data class Parsed(val senderName: String, val messageText: String)
    private val characters = Pattern.compile("\\X")

    fun parse(text: String): Parsed? {
        val matcher = characters.matcher(text)
        while (matcher.find()) {
            if (matcher.group() == ":") {
                if (matcher.start() == 0) return null
                return Parsed(trimSpaces(text.substring(0, matcher.start())), trimSpaces(text.substring(matcher.end())))
            }
        }
        return null
    }

    private fun trimSpaces(value: String): String =
        value.trim {
            // Foundation.whitespaces includes U+200B, unlike Unicode's Zs category.
            when (it) {
                '\t', ' ', '\u00A0', '\u1680', in '\u2000'..'\u200B', '\u202F', '\u205F', '\u3000' -> true
                else -> false
            }
        }
}
