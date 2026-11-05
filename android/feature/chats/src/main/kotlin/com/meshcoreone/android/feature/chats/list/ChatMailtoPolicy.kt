// AndroidOnly: WP-306 Explicit mailto tap fallback, separate from HTTP(S) preview and network policy.
package com.meshcoreone.android.feature.chats.list

import java.net.URI
import java.net.URISyntaxException

object ChatMailtoPolicy {
    fun isOpenable(url: String): Boolean = recipient(url) != null

    fun recipient(url: String): String? {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("mailto", ignoreCase = true) || !uri.isOpaque || uri.rawFragment != null) return null
        val rawRecipient = uri.rawSchemeSpecificPart.substringBefore('?')
        if (rawRecipient.isEmpty()) return null
        val recipient = URI("mailto:$rawRecipient").schemeSpecificPart
        return recipient.takeIf { it.isNotEmpty() && it.none { char -> char.isWhitespace() || char.isISOControl() } &&
            !it.startsWith("//") }
    }
}
