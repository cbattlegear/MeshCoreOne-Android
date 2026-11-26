// PortedFrom: MC1Services/Sources/MC1Services/Services/ChannelService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Utilities/MeshCoreURIQuery.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes

/**
 * `exportChannelURI` encoding. Reproduces `URLComponents.queryItems` percent-encoding (the RFC 3986
 * query set minus `&` and `=`, UTF-8, uppercase hex) followed by the source
 * `MeshCoreURIQuery.percentEncodedQueryItems` rewrite of a literal `+` to `%2B`.
 */
internal object ChannelURIEncoding {
    private const val SCHEME = "meshcore"
    private const val HOST = "channel"
    private const val PATH = "/add"
    private const val NAME_KEY = "name"
    private const val SECRET_KEY = "secret"
    private const val REGION_SCOPE_KEY = "region_scope"
    private const val HEX = "0123456789ABCDEF"

    /** Unreserved, sub-delims except `&`, `=` and `+`, plus the query extras `:@/?`. */
    private const val QUERY_ITEM_ALLOWED_PUNCTUATION = "-._~!$'()*,;:@/?"

    fun channelAddURI(name: String, secret: Bytes, floodScope: ChannelFloodScope): String {
        val items = buildList {
            add(NAME_KEY to name)
            add(SECRET_KEY to secret.uppercaseHexString())
            if (floodScope is ChannelFloodScope.Region && floodScope.name.isNotEmpty()) {
                add(REGION_SCOPE_KEY to floodScope.name)
            }
        }
        val query = items.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return "$SCHEME://$HOST$PATH?$query"
    }

    fun encode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val unsigned = byte.toInt() and 0xff
            val character = unsigned.toChar()
            if (unsigned < 0x80 && (character.isAsciiLetterOrDigit() || character in QUERY_ITEM_ALLOWED_PUNCTUATION)) {
                append(character)
            } else {
                append('%').append(HEX[unsigned ushr 4]).append(HEX[unsigned and 0x0f])
            }
        }
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
