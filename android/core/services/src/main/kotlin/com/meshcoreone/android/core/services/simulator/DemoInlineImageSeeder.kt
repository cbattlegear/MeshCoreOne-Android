// PortedFrom: MC1/Services/DemoInlineImageSeeder.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.net.URI
import java.net.URISyntaxException

/**
 * The app-layer pieces `DemoInlineImageSeeder` touches: platform image decoding (`UIImage(data:)`), the
 * URL classifier's `directImageURL(for:)`, and the process-wide inline-image cache's `storeDecoded`.
 * [Image] is the platform's decoded image type.
 */
interface DemoInlineImageCache<Image : Any> {
    /** Decodes [data] into a displayable image, or null when it is not a decodable image. */
    suspend fun decodeImage(data: Bytes): Image?

    /** `ImageURLClassifier.directImageURL(for:)`. */
    fun directImageURL(url: URI): URI

    /** `InlineImageCache.shared.storeDecoded(CachedDecodedImage(image:isGIF:data:), for:)`. */
    fun storeDecoded(image: Image, isGIF: Boolean, data: Bytes, url: URI)
}

/**
 * Pre-seeds the process-wide inline-image cache with demo mode's inline image. The render path shows
 * `.loaded` once the cache holds a decoded image for the URL, so seeding it here (the cache and decoder are
 * app-layer) lets the package-level seed reference the URL while the pixels stay embedded and render offline.
 */
object DemoInlineImageSeeder {
    /** Idempotent: re-seeding overwrites the same cache key. Does nothing if the URL or image is invalid. */
    suspend fun <Image : Any> seed(cache: DemoInlineImageCache<Image>) {
        val url = parseURL(MockDataProvider.inlineImageURL) ?: return
        val data = MockDataProvider.demoImageData
        val image = cache.decodeImage(data) ?: return
        cache.storeDecoded(image, isGIF = false, data = data, url = cache.directImageURL(url))
    }

    /** Swift `URL(string:)` returns nil for an unparseable string; `URI` reports it as an exception. */
    private fun parseURL(text: String): URI? =
        try {
            URI(text)
        } catch (invalid: URISyntaxException) {
            null
        }
}
