// PortedFrom: MC1/Services/InlineImageCache.swift@db14559b39d32322b06477c6ae676112f583db50
//
// The Swift original is a singleton `actor` combining five concerns under one isolation domain:
// (1) a raw-bytes network fetch/negative-cache/in-flight-dedup policy backed by `URLSession`,
// (2) an `NSCache`-backed raw-bytes memory tier, (3) a hand-rolled FIFO decoded-image mirror
// (`decodedMirror`) fed by the view layer after it decodes, (4) a hand-rolled FIFO
// "serves an HTML page" mirror (`servesPageMirror`), and (5) a header-only dimensions probe.
// This port keeps all five as real, process-owned policy, but factored across this module's
// already-established primitives instead of re-deriving them: the NSCache raw-bytes tier is
// [ThreadSafeLruCostBoundedCache] (same primitive `LinkPreviewCache`'s memory tier uses, per
// that type's own doc comment explicitly anticipating this reuse); `decodedMirror`/
// `servesPageMirror` are [ThreadSafeFifoCostBoundedCache] (per that type's own doc comment,
// which already cites this exact file as a joint source). A coroutine [Semaphore] stands in for
// `AsyncSemaphore`, and a [Mutex] serializes the negative-cache/in-flight-set checks the Swift
// actor serializes implicitly by never yielding between them.
//
// Three deliberate, disclosed deviations from the literal original:
//  1. `UIImage`/`CGImageSourceCreateWithData` are not pure-JVM types. [storeDecoded]/[decoded]
//     are generic over this module's already-typed [DecodedImageHandle] (see `ImageDecoding.kt`)
//     instead of a concrete bitmap type - the actual decode happens in a future native adapter
//     (`app/content`) that calls [storeDecoded] with its own handle, exactly mirroring how the
//     Swift `ChatViewModel` decodes before calling the actor's `storeDecoded`. The pure
//     "recognized as a loadable image" validation this file performs uses the already-ported
//     [ImageHeaderDecoder] (PNG/JPEG/GIF/WebP), a narrower format set than `ImageIO`'s - see
//     that file's own disclosed scope note; it is not re-litigated here.
//  2. The Swift original's `session.data(from:)` buffers the *entire* response body before
//     checking `data.count <= maxDownloadBytes` - a server that lies about/omits its declared
//     length can force unbounded buffering before the cap is ever checked. This port instead
//     streams and enforces the cap chunk-by-chunk (the same bounded-streaming contract
//     `LinkPreviewScraper.boundedFetch` already established for the hero-image/HTML-scrape
//     fetches), per the explicit instruction that image fetches be "bounded streaming", not a
//     literal re-creation of the original's unbounded-buffer-then-check ordering.
//  3. A genuinely cancelled fetch is NOT caught and converted to `.failed` the way the Swift
//     `catch { if !Task.isCancelled { failedURLs.insert(key) }; return .failed }` does - Kotlin
//     structured concurrency expects `CancellationException` to propagate, not be swallowed into
//     a normal return value (the established precedent in this module is `RegionResolver`'s
//     `reverseGeocode`, which rethrows after its own cleanup). This port's `httpFetching`/
//     `ImageHeaderDecoder` calls simply never catch it, so cancellation propagates to the
//     caller's coroutine scope exactly as idiomatic Kotlin requires; the source's actual intent
//     - "don't poison the negative cache on cancellation" - is preserved because a propagating
//     cancellation never reaches the `markFailed` call sites at all.
package com.meshcoreone.android.core.services.content

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.logging.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Mirrors the Swift `InlineImageResult` enum's four cases exactly. */
sealed interface InlineImageResult {
    /** Raw, safety-validated, size-bounded image bytes, ready for native decode. */
    data class Loaded(val data: ByteArray) : InlineImageResult

    /** A fetch for this URL is already in flight; the caller should retry later. */
    data object Loading : InlineImageResult

    /** The URL is unsafe, unreachable, non-2xx, oversized, or not a recognized image. */
    data object Failed : InlineImageResult

    /**
     * The URL resolved to an HTML page, not image bytes (e.g. an image-host landing page served
     * at a `.jpg` path). Not a failure: the caller reroutes to the link-preview path so the
     * page's own `og:image` loads. Deliberately never inserted into the negative cache, so a
     * later re-fetch can still discover the page.
     */
    data object NotImage : InlineImageResult
}

/** Immutable owner for encoded image bytes shared by the process cache and mounted consumers. */
class EncodedImageBytes(data: ByteArray) {
    private val bytes = data.copyOf()
    val size: Int get() = bytes.size
    fun copy(): ByteArray = bytes.copyOf()
}

/**
 * Decoded hero image cached alongside its optional raw encoded bytes, generic over this module's
 * typed [DecodedImageHandle] (never a concrete bitmap type - see this file's header). Mirrors
 * the Swift `CachedDecodedImage`'s `cost` computation: decoded-pixel cost (via the handle's own
 * [DecodedImageHandle.costBytes], the native adapter's `ImageByteCost`-derived figure) plus the
 * optional retained encoded bytes the full-screen viewer/share sheet need.
 */
class CachedDecodedImage(
    val handle: DecodedImageHandle,
    val isGif: Boolean,
    data: ByteArray?,
) {
    val encodedData: EncodedImageBytes? = data?.let(::EncodedImageBytes)
    val data: ByteArray? get() = encodedData?.copy()
    val cost: Long = handle.costBytes.toLong() + (encodedData?.size?.toLong() ?: 0L)
}

/**
 * Process-lifetime cache for inline chat images: network fetch/negative-cache/in-flight-dedup
 * (this file's own policy), a bounded raw-bytes memory tier, a decoded-image mirror the native
 * adapter populates post-decode, an HTML-reroute mirror, and a header-only dimensions probe.
 * See this file's header comment for the three disclosed deviations from the literal Swift
 * original.
 */
class InlineImageCache(
    private val httpFetching: BoundedHttpFetching,
    /**
     * Defaults to the real [UrlSafetyChecker.isSafe] so production callers get the full
     * scheme/private-range/DNS-rebinding check; tests inject a deterministic override instead
     * of depending on real DNS resolution for a non-literal test host, mirroring the same
     * injection seam [RedirectSafetyPolicy.authorize] and [LinkPreviewScraper] already establish.
     */
    private val isUrlSafe: suspend (String) -> Boolean = { url -> UrlSafetyChecker.isSafe(url) },
) : InlineImageDimensionProbing {
    private val stateMutex = Mutex()
    private val failedUrls = mutableSetOf<String>()
    private val inFlightUrls = mutableSetOf<String>()
    private val fetchSemaphore = Semaphore(MAX_CONCURRENT_FETCHES)

    private val memoryCache = ThreadSafeLruCostBoundedCache<String, ByteArray>(
        maxEntryCount = MAX_ENTRY_COUNT,
        maxTotalCostBytes = MAX_TOTAL_COST_BYTES,
        costOf = { it.size.toLong() },
    )
    private val decodedMirror = ThreadSafeFifoCostBoundedCache<String, CachedDecodedImage>(
        maxEntryCount = MAX_DECODED_ENTRY_COUNT,
        maxTotalCostBytes = MAX_DECODED_TOTAL_COST_BYTES,
        costOf = { it.cost },
    )
    private val servesPageMirror = ThreadSafeFifoCostBoundedCache<String, Unit>(
        maxEntryCount = MAX_SERVES_PAGE_ENTRIES,
        maxTotalCostBytes = Long.MAX_VALUE,
        costOf = { 0L },
    )

    /** Registers the persistence sink for successful probes; later calls overwrite it. */
    @Volatile
    private var dimensionsStore: InlineImageDimensionsStore? = null

    fun attachDimensionsStore(store: InlineImageDimensionsStore) {
        dimensionsStore = store
    }

    /** Fetches image data for [url], returning cached data when available. */
    suspend fun fetchImageData(url: String): InlineImageResult {
        val precheck = stateMutex.withLock { precheckLocked(url) }
        if (precheck != null) return precheck

        try {
            return fetchSemaphore.withPermit {
                currentCoroutineContext().ensureActive()
                memoryCache.get(url)?.let { return@withPermit InlineImageResult.Loaded(it.copyOf()) }
                performFetch(url)
            }
        } catch (error: IOException) {
            Logger.getLogger("MeshCore.InlineImageCache").fine("Inline image HTTP stream failed: ${error.javaClass.simpleName}")
            markFailed(url)
            return InlineImageResult.Failed
        } finally {
            withContext(NonCancellable) {
                stateMutex.withLock { inFlightUrls.remove(url) }
            }
        }
    }

    /** Runs under [stateMutex]; returns a terminal result when no fetch is needed, else `null`. */
    private fun precheckLocked(url: String): InlineImageResult? {
        if (url in failedUrls) return InlineImageResult.Failed
        memoryCache.get(url)?.let { return InlineImageResult.Loaded(it.copyOf()) }
        if (!inFlightUrls.add(url)) return InlineImageResult.Loading
        return null
    }

    /** Removes [url] from the negative cache, allowing it to be retried. */
    suspend fun clearFailure(url: String) {
        stateMutex.withLock { failedUrls.remove(url) }
    }

    /**
     * Persists a decoded image keyed on [url] so a later chat re-entry can skip the decode step.
     * `nonisolated`-equivalent: only touches the lock-guarded [decodedMirror], safe to call
     * without a coroutine suspension point.
     */
    fun storeDecoded(entry: CachedDecodedImage, url: String) {
        decodedMirror.put(url, entry)
    }

    /** Wait-free-callable decoded-image lookup. */
    fun decoded(url: String): CachedDecodedImage? = decodedMirror.get(url)

    /** Empties the decoded-image mirror, e.g. in response to a memory-pressure signal. */
    fun clearDecodedMirror() = decodedMirror.clear()
    fun clearRawMemory() = memoryCache.clear()

    /**
     * Records that an image-extension URL served an HTML page, so later classification reroutes
     * it to the link-preview path even across process restarts of this cache. Idempotent:
     * re-marking an already-known URL is a no-op (matches the Swift `Set.insert` contract, not
     * [ThreadSafeFifoCostBoundedCache.put]'s always-reorder semantics).
     */
    fun markServesHtmlPage(url: String) {
        servesPageMirror.putIfAbsent(url, Unit)
    }

    /** `true` when [url] is a known image-extension URL that actually serves an HTML page. */
    fun servesHtmlPage(url: String): Boolean = servesPageMirror.get(url) != null

    /**
     * Probes the image header for pixel dimensions without persisting the full body. Uses a
     * small Range request and [ImageHeaderDecoder]. Persists the resolved size to the attached
     * [InlineImageDimensionsStore] on success. Failures never touch the negative cache or the
     * in-flight set - this is a side query, not a [fetchImageData] call.
     */
    override suspend fun probeImageDimensions(url: String): Pair<Int, Int>? {
        if (!isUrlSafe(url)) return null

        return try {
            fetchSemaphore.withPermit {
                val attempt = httpFetching.fetch(url, PROBE_TIMEOUT_MS, PROBE_BYTE_RANGE)
                if (attempt !is HttpFetchAttempt.Started) return@withPermit null
                attempt.use { response ->
                    if (response.statusCode != HTTP_STATUS_OK && response.statusCode != HTTP_STATUS_PARTIAL_CONTENT) {
                        return@use null
                    }
                    val data = response.readBounded(PROBE_MAX_BUFFER_BYTES) ?: return@use null
                    val dims = ImageHeaderDecoder.decodeDimensions(data) ?: return@use null
                    dimensionsStore?.save(url, dims.first.toDouble(), dims.second.toDouble())
                    dims
                }
            }
        } catch (error: IOException) {
            Logger.getLogger("MeshCore.InlineImageCache").fine("Image probe stream failed: ${error.javaClass.simpleName}")
            null
        }
    }

    /** Performs the HTTP fetch, validates the response, and caches the result. */
    private suspend fun performFetch(url: String): InlineImageResult {
        if (!isUrlSafe(url)) {
            markFailed(url)
            return InlineImageResult.Failed
        }

        val attempt = httpFetching.fetch(url, FETCH_TIMEOUT_MS)
        if (attempt !is HttpFetchAttempt.Started) {
            markFailed(url)
            return InlineImageResult.Failed
        }
        return attempt.use {
            if (it.statusCode !in HTTP_SUCCESS_RANGE) {
                markFailed(url)
                return@use InlineImageResult.Failed
            }
            if (it.mimeType == HTML_MIME_TYPE) {
                markServesHtmlPage(url)
                return@use InlineImageResult.NotImage
            }
            val data = it.readBounded(MAX_DOWNLOAD_BYTES)
            if (data == null || ImageHeaderDecoder.decodeDimensions(data) == null) {
                markFailed(url)
                return@use InlineImageResult.Failed
            }
            memoryCache.put(url, data.copyOf())
            InlineImageResult.Loaded(data)
        }
    }

    private suspend fun markFailed(url: String) {
        stateMutex.withLock { failedUrls.add(url) }
    }

    companion object {
        private const val MAX_CONCURRENT_FETCHES = 3
        private const val MAX_ENTRY_COUNT = 50
        private const val MAX_TOTAL_COST_BYTES = 50L * 1024 * 1024 // 50MB
        private const val MAX_DOWNLOAD_BYTES = 10 * 1024 * 1024 // 10MB per image
        private const val PROBE_MAX_BUFFER_BYTES = 1 * 1024 * 1024 // 1MB cap for probe bodies
        private const val PROBE_BYTE_RANGE = "bytes=0-65535"
        private const val HTTP_STATUS_OK = 200
        private const val HTTP_STATUS_PARTIAL_CONTENT = 206
        private val HTTP_SUCCESS_RANGE = 200..299
        private const val HTML_MIME_TYPE = "text/html"

        private const val MAX_DECODED_ENTRY_COUNT = 50
        private const val MAX_DECODED_TOTAL_COST_BYTES = 100L * 1024 * 1024
        private const val MAX_SERVES_PAGE_ENTRIES = 200

        private const val FETCH_TIMEOUT_MS = 15_000L
        private const val PROBE_TIMEOUT_MS = 5_000L
    }
}
