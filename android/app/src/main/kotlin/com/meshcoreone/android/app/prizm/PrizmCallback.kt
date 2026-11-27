// AndroidOnly: WP-101 Prizm Mobile Observer OAuth App Link callback recognition and query parsing.
package com.meshcoreone.android.app.prizm

import java.net.URI
import java.net.URLDecoder

/**
 * `https://mqtt.nodakmesh.org/app/callback?code=...&state=...`, verified by assetlinks on the server side. [REDIRECT_URI] is
 * informational: the server fixes the redirect and the app sends no redirect URI. A beta server base URL changes only the host
 * [isCallback] accepts; Android opens the app for that host only if the manifest declares an App Link for it.
 */
object PrizmCallback {
    const val REDIRECT_URI = "https://mqtt.nodakmesh.org/app/callback"
    private const val HOST = "mqtt.nodakmesh.org"
    private const val PATH = "/app/callback"

    /** Strict: https, the exact host and the exact path. Anything else is not ours and must not reach the auth flow. */
    fun isCallback(uri: String?, allowedHost: String = HOST): Boolean {
        val parsed = parse(uri) ?: return false
        return parsed.scheme == "https" && parsed.host == allowedHost && parsed.port == -1 && parsed.rawPath == PATH
    }

    /** Query parameters, decoded; the first value wins for a repeated key. Empty when the URI has no query. */
    fun parameters(uri: String): Map<String, String> {
        val query = parse(uri)?.rawQuery ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = URLDecoder.decode(if (eq < 0) pair else pair.substring(0, eq), Charsets.UTF_8)
            val value = if (eq < 0) "" else URLDecoder.decode(pair.substring(eq + 1), Charsets.UTF_8)
            result.putIfAbsent(key, value)
        }
        return result
    }

    private fun parse(uri: String?): URI? = try { uri?.let { URI(it) } } catch (_: java.net.URISyntaxException) { null }
}
