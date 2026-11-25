// PortedFrom: MC1Services/Sources/MC1Services/Utilities/ImageURLClassifier.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Pure string/URL inspection - no image decoding, no platform image APIs.
package com.meshcoreone.android.core.services.content

import java.net.URI

/**
 * URL-level classifier for image hosting. Mirrors the Swift `ImageURLClassifier`:
 * detects direct image URLs by extension and resolves a small set of known
 * gif-hosting page URLs (Giphy) to their direct media URL.
 */
object ImageUrlClassifier {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "heic")

    /** Returns `true` if the URL's path extension is a known image type. */
    fun isDirectImageUrl(url: URI): Boolean {
        val extension = pathExtension(url) ?: return false
        return imageExtensions.contains(extension.lowercase())
    }

    /**
     * Returns the direct image URL for known hosting page URLs, or `null` if
     * not resolvable.
     */
    fun resolveImageUrl(url: URI): URI? {
        val host = url.host?.lowercase() ?: return null

        if (host == "giphy.com" || host == "www.giphy.com") {
            return resolveGiphyUrl(url)
        }

        // Already a direct Giphy media URL - no resolution needed.
        if (host == "media.giphy.com" || host == "i.giphy.com") {
            return null
        }

        return null
    }

    /** Returns `true` if the URL points to a direct image or a resolvable hosting page. */
    fun isImageUrl(url: URI): Boolean = isDirectImageUrl(url) || resolveImageUrl(url) != null

    /**
     * Returns the direct image URL: the URL itself for direct images, or the
     * resolved URL for hosting pages (falling back to the original URL when
     * nothing resolves, matching the Swift non-optional return).
     */
    fun directImageUrl(forUrl: URI): URI {
        if (isDirectImageUrl(forUrl)) return forUrl
        return resolveImageUrl(forUrl) ?: forUrl
    }

    private fun resolveGiphyUrl(url: URI): URI? {
        val pathComponents = pathComponents(url)
        if (pathComponents.size < 3) return null

        val section = pathComponents[1].lowercase()
        if (section != "gifs" && section != "embed") return null

        val lastComponent = pathComponents[2]
        val giphyId = if (section == "gifs") {
            lastComponent.split("-").lastOrNull()?.takeIf { it.isNotEmpty() } ?: lastComponent
        } else {
            lastComponent
        }

        if (giphyId.isEmpty()) return null

        return runCatching { URI("https://i.giphy.com/media/$giphyId/giphy.gif") }.getOrNull()
    }

    /**
     * Swift's `URL.pathComponents` includes a leading "/" component and splits
     * on every remaining "/"; this mirrors that for the Giphy-path matching above.
     */
    private fun pathComponents(url: URI): List<String> {
        val path = url.path ?: return emptyList()
        val segments = path.split("/").filter { it.isNotEmpty() }
        return listOf("/") + segments
    }

    /** Swift's `URL.pathExtension`: the substring after the last "." in the last path segment. */
    private fun pathExtension(url: URI): String? {
        val path = url.path ?: return null
        val lastSegment = path.substringAfterLast('/')
        if (!lastSegment.contains('.')) return null
        val extension = lastSegment.substringAfterLast('.')
        return extension.ifEmpty { null }
    }
}
