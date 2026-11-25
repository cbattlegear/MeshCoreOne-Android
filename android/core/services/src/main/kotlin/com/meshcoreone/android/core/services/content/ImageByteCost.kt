// PortedFrom: MC1/Utilities/ImageByteCost.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Generalized to plain pixel dimensions - Android's `graphics.Bitmap` is a platform (not pure-JVM)
// type, so the Bitmap-reading half of the original (the `cgImage`/bytesPerRow branch) is a
// narrow native-adapter responsibility deferred to a follow-on WP (see
// docs/android/deviations/WP-218.md). This module owns only the pure byte-cost arithmetic that
// every caller (decoded preview cache, inline image cache) needs regardless of platform.
package com.meshcoreone.android.core.services.content

/**
 * Single source of truth for the pixel-byte cost of a decoded image, used by the image caches to
 * feed a cost-bounded budget (NSCache.totalCostLimit's Android analogue).
 *
 * Mirrors the Swift `ImageByteCost.bytes(for:)` fallback arithmetic (width * height *
 * bytesPerPixel) exactly. The original's `cgImage.bytesPerRow * cgImage.height` branch, which
 * reads a real backing store's row-alignment padding, requires a `Bitmap`/`CGImage`-level adapter
 * and is not reproduced here; callers that have exact row-stride information may pass it directly
 * via [bytes].
 */
object ImageByteCost {
    private const val BYTES_PER_PIXEL_RGBA = 4

    /** Approximates decoded cost as `width * height * bytesPerPixel`, matching the Swift fallback. */
    fun bytes(width: Int, height: Int, bytesPerPixel: Int = BYTES_PER_PIXEL_RGBA): Int {
        if (width <= 0 || height <= 0) return 0
        return width * height * bytesPerPixel
    }

    /** Cost for a possibly-absent image: zero when `width`/`height` are null, matching `bytes(for: UIImage?)`. */
    fun bytes(width: Int?, height: Int?, bytesPerPixel: Int = BYTES_PER_PIXEL_RGBA): Int {
        if (width == null || height == null) return 0
        return bytes(width, height, bytesPerPixel)
    }

    /** Cost computed from an exact row stride (e.g. a real decoded bitmap's `rowBytes`), matching the `cgImage` branch exactly. */
    fun bytes(rowBytes: Int, height: Int): Int {
        if (rowBytes <= 0 || height <= 0) return 0
        return rowBytes * height
    }
}
