// PortedFrom: MC1/Services/InlineImageCache.swift@db14559b39d32322b06477c6ae676112f583db50
// (the `UIImage(data:)` / `CGImageSourceCreateWithData` decode half) and
// MC1/Services/ImageURLDetector.swift@db14559b39d32322b06477c6ae676112f583db50 (downsample half).
// Android's `graphics.BitmapFactory` is not a pure-JVM API, so core:services owns only this typed
// contract; the real decode/downsample/allocation producer is a native adapter in
// android/app/src/main/kotlin/.../app/content (see docs/android/deviations/WP-218.md). This role
// supplements -- does not replace -- ImageHeaderDecoder's pure hand-parsed header dimensions:
// ImageHeaderDecoder answers "what size is this, without touching the full bitmap" for the probe
// path, while ImageDecoding answers "produce the actual decoded/downsampled pixels" for the
// render path, matching the Swift original's two distinct call sites.
package com.meshcoreone.android.core.services.content

/**
 * Opaque handle to a platform-decoded bitmap. `core:services` never constructs or inspects one
 * directly -- it is produced and owned by the native [ImageDecoding] adapter (a real Android
 * `graphics.Bitmap`-backed implementation lives in `app/content`) -- but every consumer
 * needs these three fields to drive cache eviction and layout without a native import leaking into
 * this module. Deliberately not `Any`: a typed handle keeps the contract checkable at compile time
 * and keeps ambiguous untyped payloads out of `core:services`.
 */
interface DecodedImageHandle {
    /** Decoded pixel width. Always positive for a [ImageDecodeOutcome.Decoded] result. */
    val width: Int

    /** Decoded pixel height. Always positive for a [ImageDecodeOutcome.Decoded] result. */
    val height: Int

    /**
     * Retained decoded pixel byte cost. Native adapters report allocation capacity when
     * available (summed across animation frames); [ImageByteCost] provides a row-stride or
     * width/height estimate for handles without a platform allocation measurement.
     */
    val costBytes: Int
}

/**
 * Outcome of an [ImageDecoding.decode] call. Mirrors the Swift original's real branches
 * (`UIImage(data:)` success, the `CGImageSourceCreateWithData` "not a valid image" probe-path
 * early return, and task cancellation) as an explicit, typed result instead of a nullable
 * best-effort value -- callers must handle every branch, so a decode failure can never be
 * silently treated as a successful empty result.
 */
sealed interface ImageDecodeOutcome {
    /** The payload decoded to a real platform bitmap. */
    data class Decoded(val handle: DecodedImageHandle) : ImageDecodeOutcome

    /** The payload parsed as a recognized container but this adapter cannot decode its format. */
    data class Unsupported(val reason: String) : ImageDecodeOutcome

    /** The payload is not a valid/decodable image (corrupt bytes, truncated stream, etc). */
    data class Failed(val reason: String) : ImageDecodeOutcome

    /** The calling coroutine was cancelled mid-decode; no partial handle is produced. */
    data object Cancelled : ImageDecodeOutcome
}

/**
 * Native producer role for turning encoded image bytes into a real decoded bitmap, optionally
 * downsampled. Implemented by a `BitmapFactory`-backed adapter in `app/content`; `core:services`
 * depends only on this interface so its own algorithms (prefetch coalescing, cache eviction,
 * cost accounting) stay pure-JVM and independently testable with an injected fake.
 */
interface ImageDecoding {
    /**
     * Decodes [data] to a bitmap. When [maxDimension] is set and the source is larger, the
     * adapter should downsample during decode (e.g. `BitmapFactory.Options.inSampleSize`) rather
     * than decoding at full resolution and scaling afterward, matching the original's
     * `CGImageSourceCreateThumbnailAtIndex` downsample-during-decode intent for inline images.
     */
    suspend fun decode(data: ByteArray, maxDimension: Int? = null): ImageDecodeOutcome
}
