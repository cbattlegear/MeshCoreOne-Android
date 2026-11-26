// PortedFrom: MC1Tests/Services/ImageURLDetectorTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// (ImageURLClassifier-scoped cases only; GIF-magic-byte and downsample cases stay with the
// deferred UIKit-dependent ImageURLDetector - see docs/android/deviations/WP-218.md)
package com.meshcoreone.android.core.services.content

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageUrlClassifierTest {
    // MARK: - Direct Image URL Detection

    @Test
    fun `detects common image extensions`() {
        for (extension in listOf("jpg", "jpeg", "png", "gif", "webp", "heic")) {
            val url = URI("https://example.com/photo.$extension")
            assertTrue(ImageUrlClassifier.isDirectImageUrl(url), "Should detect .$extension")
        }
    }

    @Test
    fun `rejects non-image extensions`() {
        for (extension in listOf("html", "pdf", "mp4", "txt", "js", "css")) {
            val url = URI("https://example.com/file.$extension")
            assertFalse(ImageUrlClassifier.isDirectImageUrl(url), "Should reject .$extension")
        }
    }

    @Test
    fun `case insensitive extension detection`() {
        assertTrue(ImageUrlClassifier.isDirectImageUrl(URI("https://example.com/photo.JPG")))
        assertTrue(ImageUrlClassifier.isDirectImageUrl(URI("https://example.com/photo.Png")))
    }

    @Test
    fun `handles URLs with query parameters`() {
        val url = URI("https://example.com/photo.jpg?width=100&height=100")
        assertTrue(ImageUrlClassifier.isDirectImageUrl(url))
    }

    @Test
    fun `rejects URL with no extension`() {
        assertFalse(ImageUrlClassifier.isDirectImageUrl(URI("https://example.com/photo")))
    }

    @Test
    fun `rejects empty path`() {
        assertFalse(ImageUrlClassifier.isDirectImageUrl(URI("https://example.com/")))
    }

    // MARK: - Giphy URL Resolution

    @Test
    fun `resolves giphy-com-gifs-slug-text-ID`() {
        val url = URI("https://giphy.com/gifs/meme-cute-penguin-UTYwlUGi5iiRHtqEgj")
        val resolved = ImageUrlClassifier.resolveImageUrl(url)
        assertEquals("https://i.giphy.com/media/UTYwlUGi5iiRHtqEgj/giphy.gif", resolved?.toString())
    }

    @Test
    fun `resolves giphy-com-gifs-ID no slug`() {
        val url = URI("https://giphy.com/gifs/UTYwlUGi5iiRHtqEgj")
        val resolved = ImageUrlClassifier.resolveImageUrl(url)
        assertEquals("https://i.giphy.com/media/UTYwlUGi5iiRHtqEgj/giphy.gif", resolved?.toString())
    }

    @Test
    fun `resolves giphy-com-embed-ID`() {
        val url = URI("https://giphy.com/embed/UTYwlUGi5iiRHtqEgj")
        val resolved = ImageUrlClassifier.resolveImageUrl(url)
        assertEquals("https://i.giphy.com/media/UTYwlUGi5iiRHtqEgj/giphy.gif", resolved?.toString())
    }

    @Test
    fun `recognizes media-giphy-com as direct image URL`() {
        val url = URI("https://media.giphy.com/media/UTYwlUGi5iiRHtqEgj/giphy.gif")
        assertTrue(ImageUrlClassifier.isDirectImageUrl(url), "Should be detected as direct image URL via .gif extension")
    }

    @Test
    fun `recognizes i-giphy-com as direct image URL`() {
        val url = URI("https://i.giphy.com/media/UTYwlUGi5iiRHtqEgj/giphy.gif")
        assertTrue(ImageUrlClassifier.isDirectImageUrl(url), "Should be detected as direct image URL via .gif extension")
    }

    @Test
    fun `returns null for non-Giphy URLs`() {
        val url = URI("https://example.com/gifs/test-123")
        assertNull(ImageUrlClassifier.resolveImageUrl(url))
    }

    @Test
    fun `returns null for Giphy URLs without valid path`() {
        val url = URI("https://giphy.com/")
        assertNull(ImageUrlClassifier.resolveImageUrl(url))
    }

    @Test
    fun `resolves www-giphy-com URLs`() {
        val url = URI("https://www.giphy.com/gifs/test-ID123")
        val resolved = ImageUrlClassifier.resolveImageUrl(url)
        assertEquals("https://i.giphy.com/media/ID123/giphy.gif", resolved?.toString())
    }

    // MARK: - Composite Detection

    @Test
    fun `isImageUrl returns true for direct image URLs`() {
        assertTrue(ImageUrlClassifier.isImageUrl(URI("https://example.com/photo.png")))
    }

    @Test
    fun `isImageUrl returns true for resolvable Giphy URLs`() {
        assertTrue(ImageUrlClassifier.isImageUrl(URI("https://giphy.com/gifs/test-ABC123")))
    }

    @Test
    fun `isImageUrl returns false for non-image URLs`() {
        assertFalse(ImageUrlClassifier.isImageUrl(URI("https://example.com/page.html")))
    }

    @Test
    fun `directImageUrl returns self for direct images`() {
        val url = URI("https://example.com/photo.jpg")
        assertEquals(url, ImageUrlClassifier.directImageUrl(url))
    }

    @Test
    fun `directImageUrl resolves Giphy URLs`() {
        val url = URI("https://giphy.com/gifs/funny-ABC123")
        val resolved = ImageUrlClassifier.directImageUrl(url)
        assertEquals("https://i.giphy.com/media/ABC123/giphy.gif", resolved.toString())
    }
}
