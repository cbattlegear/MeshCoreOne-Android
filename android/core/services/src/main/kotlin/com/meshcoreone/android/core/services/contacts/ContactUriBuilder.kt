// PortedFrom: MC1Services/Sources/MC1Services/Services/ContactService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Utilities/MeshCoreURIQuery.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.contacts

/**
 * Builds `meshcore://contact/add?...` URIs exactly as Swift's `URLComponents` +
 * `MeshCoreURIQuery.percentEncodedQueryItems` do: each query item name/value is UTF-8
 * percent-encoded with uppercase hex, leaving the RFC 3986 query characters except `&` and `=`
 * literal, and a literal `+` is then rewritten to `%2B` so a form decoder keeps it as a plus.
 *
 * Only the encode half used by `ContactService.exportContactURI` lives here; the deep-link parse half
 * of `MeshCoreURIQuery` belongs to its own work package.
 */
internal object ContactUriBuilder {
    private const val SCHEME = "meshcore"
    private const val HOST = "contact"
    private const val PATH = "/add"
    private const val HEX = "0123456789ABCDEF"

    /** RFC 3986 `query` characters (unreserved, sub-delims, `:`, `@`, `/`, `?`) minus `&` and `=`. */
    private const val QUERY_ITEM_ALLOWED_PUNCTUATION = "-._~!$'()*+,;:@/?"

    fun build(items: List<Pair<String, String>>): String {
        val query = items.joinToString("&") { (name, value) -> "${encodeItem(name)}=${encodeItem(value)}" }
        return "$SCHEME://$HOST$PATH?$query"
    }

    private fun encodeItem(text: String): String = percentEncodeQueryItem(text).replace("+", "%2B")

    private fun percentEncodeQueryItem(text: String): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val unsigned = byte.toInt() and 0xff
            val character = unsigned.toChar()
            if (unsigned < 0x80 && (character.isAsciiAlphanumeric() || character in QUERY_ITEM_ALLOWED_PUNCTUATION)) {
                append(character)
            } else {
                append('%').append(HEX[unsigned ushr 4]).append(HEX[unsigned and 0x0f])
            }
        }
    }

    private fun Char.isAsciiAlphanumeric(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
}
