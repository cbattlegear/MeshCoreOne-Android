// PortedFrom: MC1/Services/LinkPreviewCaching.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.content

import com.meshcoreone.android.core.contracts.domain.LinkPreviewPersisting
import com.meshcoreone.android.core.model.LinkPreviewDataDTO

/** Result of a link preview fetch operation. Mirrors the Swift `LinkPreviewResult` enum. */
sealed interface LinkPreviewResult {
    data class Loaded(val data: LinkPreviewDataDTO) : LinkPreviewResult
    object Loading : LinkPreviewResult
    object NoPreviewAvailable : LinkPreviewResult
    object Disabled : LinkPreviewResult
    object Failed : LinkPreviewResult
}

/**
 * Narrow producer-role port for link preview caching, enabling dependency injection and
 * testing exactly as the Swift `LinkPreviewCaching` protocol does.
 */
interface LinkPreviewCaching {
    /** Gets the preview for [url], fetching if needed. */
    suspend fun preview(url: String, dataStore: LinkPreviewPersisting, isChannelMessage: Boolean): LinkPreviewResult

    /** Manual fetch bypassing the preference check (for tap-to-load). */
    suspend fun manualFetch(url: String, dataStore: LinkPreviewPersisting): LinkPreviewResult

    /** Whether a fetch is currently in progress for [url]. */
    suspend fun isFetching(url: String): Boolean

    /** Gets the cached preview for [url] without triggering a fetch. */
    suspend fun cachedPreview(url: String): LinkPreviewDataDTO?
}

/**
 * A single resolved link-preview fetch result. Mirrors the Swift `LinkPreviewMetadata` struct
 * returned by `LinkMetadataFetching.fetchMetadata`. [imageData]/[iconData] are raw, still-encoded
 * image bytes (e.g. from the scraped `og:image`); decoding them to pixel dimensions is
 * [ImageHeaderDecoder]'s job, performed by [LinkPreviewCache] itself, not the fetcher.
 */
data class LinkPreviewMetadata(
    val title: String?,
    val imageData: ByteArray?,
    val iconData: ByteArray?,
) {
    override fun equals(other: Any?): Boolean =
        other is LinkPreviewMetadata &&
            title == other.title &&
            (imageData?.contentEquals(other.imageData) ?: (other.imageData == null)) &&
            (iconData?.contentEquals(other.iconData) ?: (other.iconData == null))

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (imageData?.contentHashCode() ?: 0)
        result = 31 * result + (iconData?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Narrow producer-role port the Swift original calls `LinkMetadataFetching`: the primary
 * metadata fetch (LinkPresentation's `LPMetadataProvider` in the Swift original, which spawns a
 * `WKWebView` and has no Android equivalent) composed with the already-ported scrape fallback
 * (see [LinkPreviewScraper]). This module only depends on the shape of the port; a future native
 * adapter supplies the concrete implementation (see docs/android/deviations/WP-218.md).
 */
fun interface LinkMetadataFetching {
    suspend fun fetchMetadata(url: String): LinkPreviewMetadata?
}
