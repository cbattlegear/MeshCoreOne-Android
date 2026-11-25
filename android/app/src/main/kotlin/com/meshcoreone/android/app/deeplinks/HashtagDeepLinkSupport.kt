// PortedFrom: MC1/Views/Chats/HashtagDeeplinkSupport.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.deeplinks

import com.meshcoreone.android.core.model.ChannelDTO
import java.net.URI
import java.util.Locale

object HashtagDeepLinkSupport {
    const val SCHEME = "meshcoreone"
    const val HOST = "hashtag"

    fun channelNameFromUri(uri: URI): String? {
        if (!uri.scheme.equals(SCHEME, ignoreCase = true) || !uri.host.equals(HOST, ignoreCase = true)) return null
        val encoded = uri.rawPath.orEmpty().removePrefix("/").substringBefore('/')
        if (encoded.isEmpty()) return null
        return PercentEncoding.decodePathComponent(encoded)
    }

    fun normalizeName(name: String): String =
        name.lowercase(Locale.ROOT).removePrefix("#")

    fun isValidName(name: String): Boolean =
        name.isNotEmpty() &&
            name.first().isAsciiAlphaNumeric() &&
            name.all { it.isAsciiAlphaNumeric() || it == '-' }

    fun fullChannelName(rawName: String): String? {
        val normalized = normalizeName(rawName)
        return normalized.takeIf(::isValidName)?.let { "#$it" }
    }

    fun findChannelByName(name: String, channels: List<ChannelDTO>): ChannelDTO? =
        channels.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun Char.isAsciiAlphaNumeric(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
