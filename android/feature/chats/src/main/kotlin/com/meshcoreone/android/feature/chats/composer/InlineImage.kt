// PortedFrom: MC1/Views/Chats/Components/InlineImageView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Components/Fragments/InlineImageFragmentView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.composer

/** Where an inline image is in its lifecycle (the bubble reserves the final footprint while loading). */
sealed interface InlineImageState {
    data class Loading(val cachedAspect: Double? = null) : InlineImageState
    data class Loaded(val widthPx: Int, val heightPx: Int, val isAnimated: Boolean = false) : InlineImageState
    data object Failed : InlineImageState
}

data class InlineImageSize(val width: Double, val height: Double)

/** Display sizing of loaded inline images (iOS `InlineImageView.displaySize`, 280 x 300 box). */
object InlineImageMetrics {
    const val MAX_WIDTH_DP = 280.0
    const val MAX_HEIGHT_DP = 300.0

    fun displaySize(widthPx: Int, heightPx: Int): InlineImageSize {
        if (widthPx <= 0 || heightPx <= 0) return InlineImageSize(MAX_WIDTH_DP, MAX_HEIGHT_DP)
        val aspect = widthPx.toDouble() / heightPx
        var width = minOf(MAX_WIDTH_DP, widthPx.toDouble())
        var height = width / aspect
        if (height > MAX_HEIGHT_DP) {
            height = MAX_HEIGHT_DP
            width = height * aspect
        }
        return InlineImageSize(width, height)
    }

    /** The skeleton's reserved aspect: the remembered one or the 16:9 fallback. */
    fun reservedAspect(state: InlineImageState): Double =
        (state as? InlineImageState.Loading)?.cachedAspect ?: LinkPreviewMetrics.FALLBACK_ASPECT
}

/**
 * Seam for decoding/fetching an image. The app binds it to the WP-218 content services (decoded cache,
 * SSRF-guarded fetch); the composer module deliberately has no image-loading library (no Coil).
 */
fun interface InlineImageSource {
    suspend fun load(url: String): androidx.compose.ui.graphics.ImageBitmap?
}
