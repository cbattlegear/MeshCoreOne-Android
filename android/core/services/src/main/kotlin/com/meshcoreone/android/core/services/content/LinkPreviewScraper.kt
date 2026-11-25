// PortedFrom: MC1/Services/LinkPreviewService+Scrape.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// The Swift original's network leg (`scrapeHTMLMetadata`, `loadImageData`, `boundedData`)
// used a shared `URLSession` with `RedirectSafetyDelegate`. This pure-JVM port depends on a
// typed [BoundedHttpFetching] role instead of any concrete HTTP client, per the explicit
// instruction to port the scraper's algorithm now via an injected fetch role/fakes, ahead
// of the still-pending Android-compatible HTTP client (OkHttp) amendment. `UrlSafetyChecker`
// gates both the page URL and the resolved scraped-image URL exactly as the Swift original's
// two `URLSafetyChecker.isSafe` calls do; redirect-hop re-validation is the HTTP adapter's
// responsibility (mirroring `RedirectSafetyDelegate`, already ported as `RedirectSafetyPolicy`
// for the adapter to consult per-hop).
//
// NOT YET PORTED: the Swift original's `boundedDecode`/`fitToMaxSize` re-encodes the scraped
// image to a bounded-pixel-size JPEG via ImageIO/UIKit before returning it. That recompression
// is a native image-codec concern (forbidden in this pure-JVM module) and is intentionally
// deferred to a follow-up increment that composes this scraper's validated raw bytes with the
// already-ported native `ImageDecoding`/`BitmapImageDecoder` adapter. `loadImageData` here
// returns the capped, mime-validated raw bytes; it does not claim the recompression behavior.
package com.meshcoreone.android.core.services.content

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/** A single fetch attempt's outcome, enough to apply the Swift original's bounded-GET checks. */
sealed interface HttpFetchAttempt {
    /**
     * A reachable response. [chunks] streams the body: each invocation of its callback
     * delivers one chunk and must return `true` to continue or `false` to stop (and let the
     * adapter close/cancel the underlying connection), mirroring the Swift original's
     * `for try await byte in bytes { ...; if overCap { return nil } }` early-exit streaming.
     */
    class Started(
        val statusCode: Int,
        val mimeType: String?,
        /** Server-declared body size, or `null` when not declared (never trusted alone). */
        val expectedContentLength: Long?,
        private val closeResponse: () -> Unit,
        val chunks: suspend (onChunk: suspend (ByteArray) -> Boolean) -> Unit,
    ) : HttpFetchAttempt, AutoCloseable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (closed.compareAndSet(false, true)) closeResponse()
        }

        suspend fun readBounded(byteCap: Int): ByteArray? {
            require(byteCap > 0) { "HTTP byte cap must be positive" }
            check(!closed.get()) { "HTTP response is closed" }
            if (expectedContentLength != null && expectedContentLength > byteCap) return null
            val buffer = ByteArrayOutputStream()
            var exceeded = false
            chunks { chunk ->
                if (exceeded || chunk.size > byteCap - buffer.size()) {
                    exceeded = true
                    false
                } else {
                    buffer.write(chunk)
                    true
                }
            }
            return if (exceeded) null else buffer.toByteArray()
        }
    }

    /** Any network, TLS, timeout, or DNS-safety failure. [reason] is diagnostic only. */
    data class Failed(val reason: String) : HttpFetchAttempt
}

/**
 * Narrow producer-role port the scraper depends on instead of a concrete HTTP client.
 * A future native adapter implements this against a DNS-rebinding-safe Android-compatible
 * client (see docs/android/deviations/WP-218.md for the pending OkHttp proposal); this
 * module only depends on the shape of the port.
 */
interface BoundedHttpFetching {
    /**
     * [rangeHeader], when non-null, is sent as the HTTP `Range` request header exactly as the
     * Swift original's `probeImageDimensions`'s `bytes=0-65535` header does for a dimensions-only
     * probe GET; `null` means an ordinary unranged request (the existing scrape/image-fetch
     * call sites).
     */
    suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String? = null): HttpFetchAttempt
}

/** `og:image` / `og:title` HTML scrape fallback, and the scraped-image bounded fetch. */
class LinkPreviewScraper(
    private val httpFetching: BoundedHttpFetching,
    private val imageProcessing: PreviewImageProcessing,
    /**
     * Defaults to the real [UrlSafetyChecker.isSafe] so production callers get the full
     * scheme/private-range/DNS-rebinding check; tests inject a deterministic override instead
     * of depending on real DNS resolution for a non-literal test host, mirroring the same
     * injection seam [RedirectSafetyPolicy.authorize] already establishes.
     */
    private val isUrlSafe: suspend (String) -> Boolean = { url -> UrlSafetyChecker.isSafe(url) },
) {
    /**
     * Fetches the page and scans its `<meta>` tags for an `og:image` / `twitter:image` hero
     * image and an `og:title`. Returns `null` on any safety, network, status, size, or mime
     * failure, or when the page carries neither hint.
     */
    suspend fun scrapeHtmlMetadata(url: String): ScrapedPageMetadata? {
        if (!isUrlSafe(url)) return null

        val data = boundedFetch(
            url = url,
            timeoutMs = HTML_SCRAPE_TIMEOUT_MS,
            byteCap = HTML_SCRAPE_BYTE_CAP,
            acceptsMime = { it.contains(HTML_MIME_SUBSTRING) },
        ) ?: return null

        val html = decodeUtf8OrLatin1(data)
        return LinkPreviewHtmlMetadata.parseHtmlMetadata(html, url)
    }

    suspend fun fetchPageMetadata(url: String): PagePreviewMetadata? {
        if (!isUrlSafe(url)) return null
        val data = boundedFetch(
            url, 10_000, HTML_SCRAPE_BYTE_CAP,
            acceptsMime = { it.contains(HTML_MIME_SUBSTRING) },
        ) ?: return null
        return LinkPreviewHtmlMetadata.parsePageMetadata(decodeUtf8OrLatin1(data), url)
    }

    /**
     * Fetches the scraped `og:image` bytes, bounding the download and validating its mime
     * type. Re-validates the (possibly relative-resolved) image URL's safety independently
     * of the page URL's check, exactly as the Swift original does before its image GET.
     */
    suspend fun loadImageData(url: String): ByteArray? {
        if (!isUrlSafe(url)) return null

        val data = boundedFetch(
            url = url,
            timeoutMs = IMAGE_FETCH_TIMEOUT_MS,
            byteCap = IMAGE_BYTE_CAP,
            acceptsMime = { it.startsWith(IMAGE_MIME_PREFIX) },
        ) ?: return null
        return imageProcessing.boundedPreviewImage(data)
    }

    /**
     * Bounded GET shared by the HTML scrape and the scraped-image fetch: rejects on an
     * over-cap [HttpFetchAttempt.Started.expectedContentLength], a non-2xx status, or an
     * unaccepted mime type, then enforces [byteCap] while streaming so the cap holds even
     * when the server lies about, or omits, the content length.
     */
    private suspend fun boundedFetch(
        url: String,
        timeoutMs: Long,
        byteCap: Int,
        acceptsMime: (String) -> Boolean,
    ): ByteArray? = try {
        val attempt = httpFetching.fetch(url, timeoutMs)
        if (attempt !is HttpFetchAttempt.Started) null else attempt.use {
            if (it.statusCode !in 200..299) return@use null
            val mimeType = it.mimeType ?: return@use null
            if (!acceptsMime(mimeType)) return@use null
            it.readBounded(byteCap)
        }
    } catch (error: IOException) {
        Logger.getLogger("MeshCore.LinkPreviewService").fine("Preview HTTP stream failed: ${error.javaClass.simpleName}")
        null
    }

    private fun decodeUtf8OrLatin1(data: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(data)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        String(data, Charsets.ISO_8859_1)
    }

    companion object {
        /** Streaming bound for the HTML scrape GET; `og:image`/`og:title` live in `<head>`. */
        private const val HTML_SCRAPE_BYTE_CAP = 512 * 1024

        /** Pre-decode streaming ceiling for the scraped-image GET. */
        private const val IMAGE_BYTE_CAP = 2 * 1024 * 1024

        private const val HTML_SCRAPE_TIMEOUT_MS = 5_000L
        private const val IMAGE_FETCH_TIMEOUT_MS = 5_000L

        private const val IMAGE_MIME_PREFIX = "image/"
        private const val HTML_MIME_SUBSTRING = "html"
    }
}
