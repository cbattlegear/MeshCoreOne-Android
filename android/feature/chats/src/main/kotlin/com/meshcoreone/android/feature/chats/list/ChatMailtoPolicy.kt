// AndroidOnly: WP-306 Explicit mailto tap fallback, separate from HTTP(S) preview and network policy.
package com.meshcoreone.android.feature.chats.list

import java.net.URI
import java.net.URISyntaxException

object ChatMailtoPolicy {
    fun isOpenable(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return false
        }
        if (!uri.scheme.equals("mailto", ignoreCase = true) || !uri.isOpaque || uri.rawFragment != null) return false
        val rawRecipient = uri.rawSchemeSpecificPart.substringBefore('?')
        if (rawRecipient.isEmpty()) return false
        val recipient = URI("mailto:$rawRecipient").schemeSpecificPart
        return recipient.isNotEmpty() && recipient.none { it.isWhitespace() || it.isISOControl() } &&
            !recipient.startsWith("//")
    }
}
