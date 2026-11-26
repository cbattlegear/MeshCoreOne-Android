// PortedFrom: MC1/Services/InlineImagePrefetcher.swift@5e980a53e077c45a1270170612fdf5dae97792ff
//
// The source is `@MainActor`-isolated purely because its two collaborators
// (`InlineImageCache`, `LinkPreviewCache`) were themselves `@MainActor` in the Swift app target;
// `prefetch` itself touches no main-thread-only API. This port has no actor-isolation annotation
// (core:services has no UI-thread concept); callers on Android compose their own dispatcher choice,
// matching the already-established pattern for the other orchestration types in this package.
//
// The source's `dataStore: any PersistenceStoreProtocol` is accepted at init and threaded through
// to every `linkPreviewCache.preview(for:using:isChannelMessage:)` call. This port narrows that to
// [LinkPreviewPersisting] -- the same narrow producer-role port [LinkPreviewCaching] itself already
// depends on -- rather than a full persistence-store surface, matching the narrow-adapter doctrine
// already applied throughout this WP (no second DB/PreferenceStore, no broad protocol surface).
//
// `LinkPreviewService.extractAllURLs(in:)` returns `[URL]`; this port's `LinkUrlExtraction`
// equivalent (`extractAllUrls`) returns `[String]` (see that file's own header for why). Each
// extracted string is parsed to a `URI` before classification; a string the permissive extraction
// regex matched but that is not a well-formed URI (e.g. unescaped special characters) is silently
// skipped from fan-out rather than propagating a `URISyntaxException` -- an explicit, disclosed
// hardening the source's typed `[URL]` return never needed to make.
//
// `allowImageProbes` is the receive-time privacy gate for direct-image URLs: when `false`, the
// dimension probe is skipped entirely (no third-party image request fires on receive) while the
// link-preview "card" branch stays unconditional, matching the source's own doc comment that
// `LinkPreviewCache.preview` self-gates via its own preference check and never performs a network
// fetch merely to answer a cache-hit query.
//
// Both collaborator roles ([InlineImageDimensionProbing.probeImageDimensions],
// [LinkPreviewCaching.preview]) are non-throwing by contract (`null`/[LinkPreviewResult.Failed]
// on error, matching the source's non-throwing `async` signatures), so an ordinary exception
// escaping a fanned-out `launch` here would indicate a producer-role contract violation, not an
// expected outcome this orchestrator must itself guard against; [CancellationException]
// propagates naturally through the enclosing [coroutineScope], matching every other cancellable
// operation in this package.
package com.meshcoreone.android.core.services.content

import com.meshcoreone.android.core.contracts.domain.LinkPreviewPersisting
import java.net.URI
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Probing seam for inline image dimension lookup, letting tests inject a stand-in for
 * [InlineImageCache.probeImageDimensions]. [InlineImageCache] implements this role directly.
 */
fun interface InlineImageDimensionProbing {
    suspend fun probeImageDimensions(url: String): Pair<Int, Int>?
}

/**
 * Drives receive-time prefetching of inline image dimensions and link preview metadata for every
 * URL in a new message body. Fans the work out in parallel via [coroutineScope]; callers wrap the
 * call with their own timeout (the source's doc comment specifies 3s) so a slow probe never
 * blocks message admission -- that timeout is deliberately the caller's responsibility, not this
 * orchestrator's, matching the source exactly.
 */
class InlineImagePrefetcher(
    private val imageCache: InlineImageDimensionProbing,
    private val linkPreviewCache: LinkPreviewCaching,
    private val dimensionsStore: InlineImageDimensionsStore,
    private val dataStore: LinkPreviewPersisting,
) {
    /**
     * Prefetch dimensions and link-preview metadata for every URL in [text]. Returns once all
     * probes have resolved (success or failure); never throws (see file header on the
     * non-throwing producer-role contract this relies on).
     *
     * Delegates URL extraction to [LinkUrlExtraction.extractAllUrls] so the receive-time
     * prefetcher and the per-message URL-detection writer see the same set of URLs (Giphy
     * short-codes expanded, `@[mention]` ranges skipped, HTTP/HTTPS only).
     */
    suspend fun prefetch(text: String, isChannelMessage: Boolean, allowImageProbes: Boolean) {
        val urls = LinkUrlExtraction.extractAllUrls(text)
        if (urls.isEmpty()) return

        coroutineScope {
            for (url in urls) {
                val uri = runCatching { URI(url) }.getOrNull() ?: continue
                if (ImageUrlClassifier.isImageUrl(uri)) {
                    if (!allowImageProbes) continue
                    val probeUrl = ImageUrlClassifier.directImageUrl(uri).toString()
                    if (dimensionsStore.aspect(probeUrl) != null) continue
                    launch { imageCache.probeImageDimensions(probeUrl) }
                } else {
                    launch {
                        val result = linkPreviewCache.preview(url, dataStore, isChannelMessage)
                        // Persist the hero aspect under the requested page URL (the key the
                        // build path looks up) so the loading shimmer reserves the final card
                        // footprint on the next build.
                        if (result is LinkPreviewResult.Loaded) {
                            val width = result.data.imageWidth
                            val height = result.data.imageHeight
                            if (width != null && height != null) {
                                dimensionsStore.save(url, width.toDouble(), height.toDouble())
                            }
                        }
                    }
                }
            }
        }
    }
}
