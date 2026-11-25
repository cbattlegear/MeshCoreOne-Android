// PortedFrom: MC1/Views/Chats/Mentions/MentionDeeplinkSupport.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.deeplinks

import java.net.URI

object MentionDeepLinkSupport {
    const val SCHEME = "meshcoreone"
    const val HOST = "mention"

    fun uriForName(name: String): URI? {
        if (name.isEmpty()) return null
        return runCatching { URI("$SCHEME://$HOST/${PercentEncoding.encodePathComponent(name)}") }.getOrNull()
    }

    fun nameFromUri(uri: URI): String? {
        if (!uri.scheme.equals(SCHEME, ignoreCase = true) || !uri.host.equals(HOST, ignoreCase = true)) return null
        val encoded = uri.rawPath.orEmpty().removePrefix("/")
        if (encoded.isEmpty()) return null
        return PercentEncoding.decodePathComponent(encoded)?.takeIf { it.isNotEmpty() }
    }
}
