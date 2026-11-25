// PortedFrom: MC1/Views/Chats/Mentions/MentionDeeplinkSupport.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.composer

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * The `meshcoreone://mention/<name>` link that body text produces and the conversation consumes.
 * The allowed set is Foundation's `urlPathAllowed` minus `/`, so a slash is encoded into one path
 * component (verified against the swiftc oracle, e.g. `A/B` -> `A%2FB`, `100%` -> `100%25`).
 */
object MentionDeeplink {
    const val SCHEME = "meshcoreone"
    const val HOST = "mention"
    private const val ALLOWED = "-._~!$&'()*+,;=:@"
    private const val HEX = "0123456789ABCDEF"
    private val shape = Regex("""^$SCHEME://$HOST(/[^?#]*)?(?:[?#].*)?$""")

    /** Null when the name encodes to nothing. */
    fun url(name: String): String? {
        val encoded = encode(name)
        return if (encoded.isEmpty()) null else "$SCHEME://$HOST/$encoded"
    }

    /** The decoded mention name when [url] is a mention link, else null. The path is decoded exactly once. */
    fun name(url: String): String? {
        val path = shape.matchEntire(url)?.groupValues?.get(1) ?: return null
        val encoded = path.removePrefix("/")
        if (encoded.isEmpty()) return null
        return percentDecode(encoded)?.takeIf { it.isNotEmpty() }
    }

    internal fun encode(text: String): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val unsigned = byte.toInt() and 0xff
            val char = unsigned.toChar()
            val plain = unsigned < 0x80 && (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in ALLOWED)
            if (plain) append(char) else append('%').append(HEX[unsigned ushr 4]).append(HEX[unsigned and 0x0f])
        }
    }

    /** `removingPercentEncoding`: null on a malformed escape or on bytes that are not valid UTF-8. */
    internal fun percentDecode(text: String): String? {
        val bytes = java.io.ByteArrayOutputStream()
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '%') {
                val value = text.substring(index + 1, minOf(index + 3, text.length)).takeIf { it.length == 2 }
                    ?.takeIf { pair -> pair.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } }?.toInt(16) ?: return null
                bytes.write(value)
                index += 3
            } else {
                val codePoint = text.codePointAt(index)
                bytes.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
                index += Character.charCount(codePoint)
            }
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }
}
