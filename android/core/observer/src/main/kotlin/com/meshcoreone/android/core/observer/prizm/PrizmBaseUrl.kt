// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.prizm

import java.net.URI

/**
 * Validation of the debug "beta server" override. Plain `http://` is accepted only for a developer machine or a private network
 * (localhost, 10.x, 192.168.x, 172.16-31.x); anything else must be `https://`. No credentials, query or fragment; a path is kept
 * without its trailing slash. The result has no trailing slash.
 */
object PrizmBaseUrl {
    /** Null when [text] is not an acceptable base URL. Blank is not acceptable here: callers treat blank as "reset to default". */
    fun parse(text: String): String? {
        val trimmed = text.trim()
        val uri = try { URI(trimmed) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        if (scheme != "https" && !(scheme == "http" && isPrivateHost(host))) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "$scheme://$host$port${uri.rawPath.orEmpty().trimEnd('/')}"
    }

    fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost")) return true
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { p -> p.toIntOrNull()?.let { it in 0..255 } != true }) return false
        val a = parts[0].toInt(); val b = parts[1].toInt()
        return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
    }

    fun hostOf(baseUrl: String): String? = try { URI(baseUrl).host?.lowercase() } catch (_: Exception) { null }
}
