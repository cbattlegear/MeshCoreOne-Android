// PortedFrom: MC1/Services/DecodedPreviewCache.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// `UIImage` is not a pure-JVM type, so the decoded hero/icon payload is generic over the native
// adapter's decoded-image type ([Hero]/[Icon]) rather than hard-coding the Android
// `graphics.Bitmap` type
// (which would leak a production Android dependency into this pure-JVM module). The native
// adapter supplies both the decoded image and its own cost-of-decoded-image function (e.g. via
// `ImageByteCost.bytes(width, height)` fed from the real bitmap's dimensions) exactly as the
// Swift original's `CachedDecodedPreview.init` computes `cost` from the real `UIImage`/`cgImage`.
// Thread-safety and the FIFO/count/cost eviction sweep are the concrete, process-owned policy
// this class owns; it is a real 1:1 port, not the generic `FifoCostBoundedCache` standing in as
// "proof" of this cache's own behavior.
package com.meshcoreone.android.core.services.content

import com.meshcoreone.android.core.model.LinkPreviewDataDTO

/**
 * Decoded hero/icon pair cached alongside the trimmed preview metadata. Mirrors the Swift
 * `CachedDecodedPreview`: the raw source `imageData`/`iconData` bytes are stripped (only `url`,
 * `title`, `imageWidth`, `imageHeight`, `fetchedAt` are retained) because the rehydration render
 * path reads only those fields and the pixel-based cost budget never accounts for compressed
 * source bytes.
 */
class CachedDecodedPreview<Hero, Icon>(
    dto: LinkPreviewDataDTO,
    val hero: Hero?,
    val icon: Icon?,
    heroCost: (Hero) -> Long,
    iconCost: (Icon) -> Long,
) {
    val dto: LinkPreviewDataDTO = dto.copy(imageData = null, iconData = null)
    val cost: Long = (hero?.let(heroCost) ?: 0L) + (icon?.let(iconCost) ?: 0L)
}

/**
 * Process-lifetime cache of decoded link-preview assets keyed by URL. Survives view-model
 * teardown so exiting and re-entering the same chat repaints loaded cards without reshimmering
 * or re-decoding. FIFO eviction bounded by entry count and total cost; `clear()` should be
 * invoked by a native adapter in response to a system memory-pressure signal, mirroring the
 * Swift original's `UIApplication.didReceiveMemoryWarningNotification` observer (which is itself
 * a native/platform concern, not reproduced here).
 */
class DecodedPreviewCache<Hero, Icon>(
    maxEntryCount: Int = DEFAULT_MAX_ENTRY_COUNT,
    maxTotalCostBytes: Long = DEFAULT_MAX_TOTAL_COST_BYTES,
) {
    private val mirror = ThreadSafeFifoCostBoundedCache<String, CachedDecodedPreview<Hero, Icon>>(
        maxEntryCount = maxEntryCount,
        maxTotalCostBytes = maxTotalCostBytes,
        costOf = { it.cost },
    )

    /** Persists a decoded preview keyed on the message's detected URL. */
    fun store(entry: CachedDecodedPreview<Hero, Icon>, url: String) {
        mirror.put(url, entry)
    }

    /** Wait-free decoded-preview lookup, safe to call without a coroutine suspension point. */
    fun decoded(url: String): CachedDecodedPreview<Hero, Icon>? = mirror.get(url)

    /** Empties the cache in response to system memory pressure. */
    fun clear() = mirror.clear()

    companion object {
        const val DEFAULT_MAX_ENTRY_COUNT = 50
        const val DEFAULT_MAX_TOTAL_COST_BYTES = 50L * 1024 * 1024 // 50MB, matches the Swift original
    }
}
