// PortedFrom: MC1Tests/Services/LinkPreviewServiceTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// (scrapeHTMLMetadata/loadImageData network-leg cases only; see
// `LinkPreviewHtmlMetadataTest.kt` for the pure-parse cases.)
//
// The Swift original stubs these cases with real `URLProtocol` subclasses so the actual
// `URLSession`/`RedirectSafetyDelegate` machinery runs end-to-end. This port's `httpFetching`
// dependency is a typed role (see `LinkPreviewScraper.kt`), so these tests exercise
// `LinkPreviewScraper`'s own bounded-fetch decision logic against a fake implementation of
// that role; redirect-hop re-validation is the future HTTP adapter's own responsibility (it
// consults the already-ported `RedirectSafetyPolicy`) and is out of scope for a fake that
// never performs a real HTTP redirect.
package com.meshcoreone.android.core.services.content

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkPreviewScraperTest {
    private fun fetcherReturning(attempt: HttpFetchAttempt): BoundedHttpFetching =
        object : BoundedHttpFetching {
            override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt = attempt
        }

    /**
     * Builds a scraper with its safety gate stubbed to always-safe: these tests exercise the
     * scraper's own bounded-fetch/mime/size/parse decision logic against a domain-name test URL
     * that isn't DNS-resolvable in a network-restricted test sandbox, so they must not depend on
     * the real [UrlSafetyChecker]'s DNS resolution leg (already covered independently by
     * `UrlSafetyCheckerTest`/`RedirectSafetyPolicyTest`).
     */
    private fun scraperFor(attempt: HttpFetchAttempt): LinkPreviewScraper =
        LinkPreviewScraper(fetcherReturning(attempt), PreviewImageProcessing { it }, isUrlSafe = { true })

    private fun startedWith(
        statusCode: Int = 200,
        mimeType: String? = "text/html",
        expectedContentLength: Long? = null,
        body: ByteArray,
    ): HttpFetchAttempt.Started = HttpFetchAttempt.Started(
        statusCode = statusCode,
        mimeType = mimeType,
        expectedContentLength = expectedContentLength,
        closeResponse = {},
        chunks = { onChunk -> if (body.isNotEmpty()) onChunk(body) },
    )

    @Test
    fun `scrapeHtmlMetadata parses og tags from a stubbed html page`() = runTest {
        val html = """<meta property="og:title" content="Stubbed page"><meta property="og:image" content="https://example.com/hero.jpg">"""
        val scraper = scraperFor(startedWith(body = html.toByteArray()))

        val result = scraper.scrapeHtmlMetadata("https://example.com/page")

        assertEquals("Stubbed page", result?.title)
        assertEquals("https://example.com/hero.jpg", result?.imageUrl)
    }

    @Test
    fun `scrapeHtmlMetadata rejects a non-html mime type`() = runTest {
        val scraper = scraperFor(startedWith(mimeType = "application/json", body = "{}".toByteArray()))

        assertNull(scraper.scrapeHtmlMetadata("https://example.com/page"))
    }

    @Test
    fun `loadImageData rejects an unsafe url before any fetch`() = runTest {
        var fetchCalled = false
        val fetching = object : BoundedHttpFetching {
            override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt {
                fetchCalled = true
                return startedWith(mimeType = "image/jpeg", body = byteArrayOf(1, 2, 3))
            }
        }
        val scraper = LinkPreviewScraper(fetching, PreviewImageProcessing { it })

        val data = scraper.loadImageData("http://127.0.0.1/secret.jpg")

        assertNull(data)
        kotlin.test.assertFalse(fetchCalled, "a private-host target must be rejected before any fetch attempt")
    }

    @Test
    fun `loadImageData rejects an oversized expected content length`() = runTest {
        val scraper = scraperFor(
            startedWith(mimeType = "image/jpeg", expectedContentLength = 3 * 1024 * 1024, body = byteArrayOf(1)),
        )

        assertNull(scraper.loadImageData("https://example.com/huge.jpg"))
    }

    @Test
    fun `loadImageData rejects a non-image mime type`() = runTest {
        val scraper = scraperFor(startedWith(mimeType = "text/plain", body = byteArrayOf(1, 2, 3)))

        assertNull(scraper.loadImageData("https://example.com/not-an-image.jpg"))
    }

    @Test
    fun `loadImageData rejects a stream that exceeds the byte cap while streaming`() = runTest {
        val overCap = ByteArray(3 * 1024 * 1024) // over the 2MB image cap, with no expectedContentLength declared
        val scraper = scraperFor(startedWith(mimeType = "image/jpeg", body = overCap))

        assertNull(scraper.loadImageData("https://example.com/huge-undeclared.jpg"))
    }

    @Test
    fun `loadImageData returns the capped bytes for a valid image fetch`() = runTest {
        val bytes = byteArrayOf(0x42, 0x4d, 1, 2, 3) // arbitrary small payload; decode is a separate native adapter concern
        val scraper = scraperFor(startedWith(mimeType = "image/jpeg", body = bytes))

        val data = scraper.loadImageData("https://example.com/photo.jpg")

        assertEquals(bytes.toList(), data?.toList())
    }

    @Test
    fun `boundedFetch rejects a non-2xx status`() = runTest {
        val scraper = scraperFor(startedWith(statusCode = 404, body = byteArrayOf(1)))

        assertNull(scraper.scrapeHtmlMetadata("https://example.com/missing"))
    }

    @Test
    fun `boundedFetch rejects a failed fetch attempt`() = runTest {
        val scraper = scraperFor(HttpFetchAttempt.Failed("connection refused"))

        assertNull(scraper.scrapeHtmlMetadata("https://example.com/page"))
    }
}
