// PortedFrom: MC1Services/Sources/MC1Services/Utilities/ContactShareUtilities.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/ContactService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.regex.Pattern

/** A parsed `<publicKeyHex:type:name>` share token. */
data class SharedContact(val name: String, val publicKey: Bytes, val type: ContactType)

/**
 * Feature-local mirror of the contact share token (`<64-hex:type:name>`) and `exportContactURI`
 * (type-placement: the originals live in core:services, which features may not import; the app layer
 * keeps the two in sync through the shared Swift-derived fixtures). Field splitting is per grapheme.
 */
object ContactShare {
    private const val OPEN = "<"
    private const val CLOSE = ">"
    private const val SEPARATOR = ":"
    private const val HEX = "0123456789ABCDEF"
    private const val URI_ALLOWED = "-._~!$'()*+,;:@/?"

    /** `\d` is spelled `\p{Nd}` because ICU `\d` matches any Unicode decimal digit (no UNICODE_CHARACTER_CLASS). */
    val tokenRegex: Pattern = Pattern.compile("<[0-9a-fA-F]{64}:\\p{Nd}+:[^>]+>")

    fun formatShare(publicKey: Bytes, type: ContactType, name: String): String {
        val cleaned = ComposerText.graphemes(name).filter { it != CLOSE }.joinToString("")
        return OPEN + publicKey.uppercaseHexString() + SEPARATOR + type.rawValue.toString() + SEPARATOR + cleaned + CLOSE
    }

    /** Parses the first token in [token]; null when absent or invalid (bad key length, type, or empty name). */
    fun parseShare(token: String): SharedContact? {
        val matcher = tokenRegex.matcher(token)
        if (!matcher.find()) return null
        val interior = ComposerText.graphemes(matcher.group()).drop(1).dropLast(1)
        val fields = split(interior)
        if (fields.size != 3) return null
        val key = Bytes.parseHex(fields[0]) ?: return null
        if (key.size != com.meshcoreone.android.core.model.ProtocolLimits.PUBLIC_KEY_SIZE) return null
        val typeValue = fields[1].takeIf { text -> text.all { it in '0'..'9' } }?.toIntOrNull() ?: return null
        if (typeValue !in 0..255) return null
        val type = ContactType.fromRawValue(typeValue.toUByte()) ?: return null
        val name = fields[2]
        if (name.isEmpty()) return null
        return SharedContact(name, key, type)
    }

    private fun split(interior: List<String>): List<String> {
        val fields = ArrayList<String>()
        val current = StringBuilder()
        for (grapheme in interior) {
            if (grapheme == SEPARATOR && fields.size < 2) {
                fields += current.toString()
                current.setLength(0)
            } else {
                current.append(grapheme)
            }
        }
        fields += current.toString()
        return fields
    }

    /** `ContactService.exportContactURI`: `meshcore://contact/add?name=&public_key=&type=` with Swift query encoding. */
    fun exportContactUri(name: String, publicKey: Bytes, type: ContactType): String {
        val items = listOf(
            "name" to name, "public_key" to publicKey.uppercaseHexString(), "type" to type.rawValue.toString(),
        )
        val query = items.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return "meshcore://contact/add?$query"
    }

    private fun encode(text: String): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val unsigned = byte.toInt() and 0xff
            val char = unsigned.toChar()
            val plain = unsigned < 0x80 && (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in URI_ALLOWED)
            // A literal `+` becomes %2B so a form decoder keeps it a plus (Swift MeshCoreURIQuery).
            if (plain && char != '+') append(char) else append('%').append(HEX[unsigned ushr 4]).append(HEX[unsigned and 0x0f])
        }
    }
}
