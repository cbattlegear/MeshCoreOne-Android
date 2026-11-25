// PortedFrom: MC1Tests/Services/InlineImageCacheTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
//
// The Swift original stubs `URLSession` with a real `URLProtocol` subclass so the actual network
// stack exercises the HTML-reroute/oversize-precedes-mime-check cases end-to-end, and uses
// `UIImage`/`CGImageSourceCreateWithData` for the decoded-cache round-trip cases. This port's
// `httpFetching` dependency is the typed [BoundedHttpFetching] role (see `LinkPreviewScraper.kt`
// and its own test's established fake-fetcher pattern), and [CachedDecodedImage] is generic over
// this module's [DecodedImageHandle] (a fake implementation stands in for the future native
// `BitmapFactory`-backed handle - see this module's `InlineImageCache.kt` header for why a
// concrete bitmap type cannot appear in pure-JVM `core:services`). Each original case below
// carries its own `PortedFrom-case` disclosure; WP-218 additions (negative-cache/in-flight/
// dedup policy coverage the original's `actor`-isolation made implicit rather than directly
// testable) are marked accordingly.
package com.meshcoreone.android.core.services.content

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InlineImageCacheTest {
    private fun fetcherReturning(attempt: HttpFetchAttempt): BoundedHttpFetching =
        object : BoundedHttpFetching {
            override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt = attempt
        }

    /**
     * Builds a cache with its safety gate stubbed to always-safe: these tests exercise the
     * cache's own fetch/negative-cache/probe decision logic against a domain-name test URL
     * that isn't DNS-resolvable in a network-restricted test sandbox, so they must not depend on
     * the real [UrlSafetyChecker]'s DNS resolution leg (already covered independently by
     * `UrlSafetyCheckerTest`/`RedirectSafetyPolicyTest`).
     */
    private fun cacheFor(fetching: BoundedHttpFetching): InlineImageCache =
        InlineImageCache(fetching, isUrlSafe = { true })

    private fun cacheFor(attempt: HttpFetchAttempt): InlineImageCache = cacheFor(fetcherReturning(attempt))

    private fun startedWith(
        statusCode: Int = 200,
        mimeType: String? = "image/png",
        expectedContentLength: Long? = null,
        body: ByteArray,
    ): HttpFetchAttempt.Started = HttpFetchAttempt.Started(
        statusCode = statusCode,
        mimeType = mimeType,
        expectedContentLength = expectedContentLength,
        closeResponse = {},
        chunks = { onChunk -> if (body.isNotEmpty()) onChunk(body) },
    )

    private fun makePngBytes(width: Int = 2, height: Int = 2): ByteArray {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val bytes = mutableListOf<Byte>()
        bytes.addAll(signature.toList())
        bytes.addAll(uInt32BigEndian(13))
        bytes.addAll("IHDR".toByteArray(Charsets.US_ASCII).toList())
        bytes.addAll(uInt32BigEndian(width))
        bytes.addAll(uInt32BigEndian(height))
        return bytes.toByteArray()
    }

    private fun uInt32BigEndian(value: Int): List<Byte> = listOf(
        (value ushr 24 and 0xFF).toByte(),
        (value ushr 16 and 0xFF).toByte(),
        (value ushr 8 and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun fakeHandle(width: Int = 1, height: Int = 1, costBytes: Int = width * height * 4): DecodedImageHandle =
        object : DecodedImageHandle {
            override val width = width
            override val height = height
            override val costBytes = costBytes
        }

    // MARK: - Safety gate

    // PortedFrom-case: `Probe returns nil for a private-IP host`
    @Test
    fun `probeImageDimensions returns null for a private-IP host`() = runTest {
        val cache = InlineImageCache(fetcherReturning(HttpFetchAttempt.Failed("unreachable")))

        assertNull(cache.probeImageDimensions("http://127.0.0.1/test.png"))
    }

    // PortedFrom-case: `Probe returns nil for a non-HTTP scheme`
    @Test
    fun `probeImageDimensions returns null for a non-HTTP scheme`() = runTest {
        val cache = InlineImageCache(fetcherReturning(HttpFetchAttempt.Failed("unreachable")))

        assertNull(cache.probeImageDimensions("ftp://example.com/test.png"))
    }

    // MARK: - HTML reroute

    // PortedFrom-case: `An image URL that serves HTML returns notImage and stays retryable`
    @Test
    fun `fetchImageData reroutes to notImage for an html-serving image-extension url, repeatably`() = runTest {
        val html = "<html><body>landing page</body></html>"
        val cache = InlineImageCache(fetcherReturning(startedWith(mimeType = "text/html", body = html.toByteArray())))
        val url = "https://media.giphy.com/example.jpg"

        val first = cache.fetchImageData(url)
        assertEquals(InlineImageResult.NotImage, first)

        // A reroute must not poison the negative cache: re-fetching runs the network path again
        // rather than short-circuiting to Failed, so a chat re-entry can still discover the page.
        val second = cache.fetchImageData(url)
        assertEquals(InlineImageResult.NotImage, second)
    }

    // PortedFrom-case: `An oversized HTML page still returns notImage instead of tripping the size guard`
    @Test
    fun `fetchImageData reroutes an oversized html page to notImage before the size guard trips`() = runTest {
        val oversizedHtml = ByteArray(11 * 1024 * 1024)
        val cache = InlineImageCache(
            fetcherReturning(startedWith(mimeType = "text/html", body = oversizedHtml)),
        )

        val result = cache.fetchImageData("https://media.giphy.com/oversized.jpg")

        assertEquals(InlineImageResult.NotImage, result)
    }

    // MARK: - Decoded cache

    // PortedFrom-case: `Decoded cache returns nil for an unseen URL`
    @Test
    fun `decoded returns null for an unseen url`() {
        val cache = InlineImageCache(fetcherReturning(HttpFetchAttempt.Failed("unused")))

        assertNull(cache.decoded("https://example.invalid/unseen.png"))
    }

    // PortedFrom-case: `Decoded cache round-trips a stored entry with raw bytes`
    @Test
    fun `decoded round-trips a stored entry with raw bytes`() {
        val cache = InlineImageCache(fetcherReturning(HttpFetchAttempt.Failed("unused")))
        val url = "https://example.invalid/round-trip.png"
        val handle = fakeHandle()
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val entry = CachedDecodedImage(handle, isGif = false, data = bytes)

        cache.storeDecoded(entry, url)
        val result = cache.decoded(url)

        assertEquals(handle, result?.handle)
        assertFalse(result!!.isGif)
        assertTrue(bytes.contentEquals(result.data))
    }

    // PortedFrom-case: `Decoded cache preserves the GIF flag and omits bytes`
    @Test
    fun `decoded preserves the gif flag and omits bytes`() {
        val cache = InlineImageCache(fetcherReturning(HttpFetchAttempt.Failed("unused")))
        val url = "https://example.invalid/gif.png"
        val entry = CachedDecodedImage(fakeHandle(), isGif = true, data = null)

        cache.storeDecoded(entry, url)
        val result = cache.decoded(url)

        assertTrue(result!!.isGif)
        assertNull(result.data)
    }

    // PortedFrom-case: `Re-storing the same key replaces the entry without growing the cache`
    @Test
    fun `re-storing the same key replaces the entry without growing the cache`() {
        val cache = InlineImageCache(fetcherReturning(HttpFetchAttempt.Failed("unused")))
        val url = "https://example.invalid/replace.png"
        val first = CachedDecodedImage(fakeHandle(), isGif = false, data = byteArrayOf(0x01))
        val second = CachedDecodedImage(fakeHandle(), isGif = true, data = null)

        cache.storeDecoded(first, url)
        cache.storeDecoded(second, url)
        val result = cache.decoded(url)

        assertEquals(second.handle, result?.handle)
        assertTrue(result!!.isGif)
        assertNull(result.data)
    }

    // PortedFrom-case: `Decoded cost reflects pixel size plus raw bytes`
    @Test
    fun `cost reflects decoded handle cost plus raw bytes`() {
        val handle = fakeHandle(width = 100, height = 50, costBytes = 100 * 50 * 4)
        val bytes = ByteArray(1000)

        val entry = CachedDecodedImage(handle, isGif = false, data = bytes)

        assertEquals(20_000L + 1_000L, entry.cost)
    }

    // MARK: - WP-218 additions: fetch/negative-cache/in-flight policy not directly exercised by
    // the original (the Swift `actor`'s isolation makes these implementation details rather
    // than independently observable behavior); this port's policy is process-owned and must be
    // verified directly per the WP-218 scope.

    @Test
    fun `fetchImageData returns Loaded for a recognized image response and serves it from memory on a repeat call`() = runTest {
        val png = makePngBytes()
        var fetchCount = 0
        val fetching = object : BoundedHttpFetching {
            override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt {
                fetchCount++
                return startedWith(body = png)
            }
        }
        val cache = cacheFor(fetching)

        val first = cache.fetchImageData("https://example.invalid/photo.png") as InlineImageResult.Loaded
        assertTrue(png.contentEquals(first.data))

        val second = cache.fetchImageData("https://example.invalid/photo.png") as InlineImageResult.Loaded
        assertTrue(png.contentEquals(second.data))
        assertEquals(1, fetchCount, "a memory-cache hit must not re-fetch")
    }

    @Test
    fun `fetchImageData rejects an unsafe url before any fetch`() = runTest {
        var fetchCalled = false
        val fetching = object : BoundedHttpFetching {
            override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt {
                fetchCalled = true
                return startedWith(body = makePngBytes())
            }
        }
        val cache = InlineImageCache(fetching)

        val result = cache.fetchImageData("http://127.0.0.1/secret.png")

        assertEquals(InlineImageResult.Failed, result)
        assertFalse(fetchCalled, "a private-host target must be rejected before any fetch attempt")
    }

    @Test
    fun `fetchImageData caches a failure and clearFailure allows a retry`() = runTest {
        var fetchCount = 0
        val fetching = object : BoundedHttpFetching {
            override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt {
                fetchCount++
                return HttpFetchAttempt.Failed("boom")
            }
        }
        val cache = cacheFor(fetching)
        val url = "https://example.invalid/broken.png"

        assertEquals(InlineImageResult.Failed, cache.fetchImageData(url))
        assertEquals(InlineImageResult.Failed, cache.fetchImageData(url))
        assertEquals(1, fetchCount, "a cached failure must not re-fetch")

        cache.clearFailure(url)
        cache.fetchImageData(url)
        assertEquals(2, fetchCount, "clearFailure must allow a retry")
    }

    @Test
    fun `fetchImageData rejects a non-2xx status without caching a recognized image`() = runTest {
        val cache = cacheFor(startedWith(statusCode = 404, body = makePngBytes()))

        assertEquals(InlineImageResult.Failed, cache.fetchImageData("https://example.invalid/missing.png"))
    }

    @Test
    fun `fetchImageData rejects an oversized image body`() = runTest {
        val cache = cacheFor(
            startedWith(expectedContentLength = 11L * 1024 * 1024, body = makePngBytes()),
        )

        assertEquals(InlineImageResult.Failed, cache.fetchImageData("https://example.invalid/huge.png"))
    }

    @Test
    fun `fetchImageData rejects bytes that are not a recognized image`() = runTest {
        val cache = cacheFor(startedWith(body = "not an image".toByteArray()))

        assertEquals(InlineImageResult.Failed, cache.fetchImageData("https://example.invalid/not-image.png"))
    }

    @Test
    fun `probeImageDimensions persists resolved dimensions to the attached store`() = runTest {
        val cache = cacheFor(startedWith(statusCode = 206, body = makePngBytes(width = 40, height = 30)))
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "InlineImageCacheTest-${java.util.UUID.randomUUID()}")
        val tempFile = java.io.File(tempDir, "dimensions.json").also { tempDir.mkdirs() }
        val store = InlineImageDimensionsStore(tempFile)
        cache.attachDimensionsStore(store)
        val url = "https://example.invalid/probe.png"

        val dims = cache.probeImageDimensions(url)

        assertEquals(40 to 30, dims)
        assertEquals(40.0 / 30.0, store.aspect(url))
    }

    @Test
    fun `probeImageDimensions does not touch the negative cache or in-flight set on failure`() = runTest {
        // Branches on the probe's range header so the same cache instance/url can fail the probe
        // leg but still succeed a later, unranged fetch - proving the probe failure never reached
        // `failedUrls` for this exact url (a shared negative cache would otherwise short-circuit
        // the later fetch to Failed without ever calling the fetcher again).
        val fetching = object : BoundedHttpFetching {
            override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt =
                if (rangeHeader != null) {
                    startedWith(statusCode = 500, body = makePngBytes())
                } else {
                    startedWith(body = makePngBytes())
                }
        }
        val cache = cacheFor(fetching)
        val url = "https://example.invalid/probe-fail.png"

        assertNull(cache.probeImageDimensions(url))
        assertTrue(cache.fetchImageData(url) is InlineImageResult.Loaded)
    }
}
