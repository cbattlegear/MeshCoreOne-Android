// PortedFrom: MC1/Services/InlineImageCache.swift@db14559b39d32322b06477c6ae676112f583db50
// (the `UIImage(data:)` / `CGImageSourceCreateWithData` decode half) and
// MC1/Services/ImageURLDetector.swift@db14559b39d32322b06477c6ae676112f583db50 (downsample half).
// Native adaptation (many-to-many port): core:services/content.ImageDecoding is the pure-JVM
// typed contract (android.graphics.BitmapFactory is not a pure-JVM API); this file is the real
// Android runtime decode/downsample/allocation producer the coordinator admitted into
// app/content. This header names the actual production Swift source this file's behavior
// derives from; it does NOT claim this file carries any of WP-218's 154 original test-assertion
// credit -- that credit is claimed by core:services' own ImageDecoding/ImageHeaderDecoder ports
// and their tests (pure-JVM, run against fakes), which this adapter supplements rather than
// duplicates. core:services' own ImageHeaderDecoder remains the independently-tested pure
// hand-parser for the probe path (cheap "what size is this" without a full decode) -- this
// adapter is not a claim that the hand parser is equivalent to BitmapFactory/ImageIO;
// production decode/render call sites use this adapter so real Android formats BitmapFactory
// supports (PNG/JPEG/GIF/WebP/HEIC/BMP on the platforms that register those formats) are
// genuinely decoded, not just header-probed.
package com.meshcoreone.android.app.content

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.meshcoreone.android.core.services.content.DecodedImageHandle
import com.meshcoreone.android.core.services.content.ImageDecodeOutcome
import com.meshcoreone.android.core.services.content.ImageDecoding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.IOException
import java.util.logging.Logger

/** Real [DecodedImageHandle] wrapping an actually-decoded [android.graphics.Bitmap]. */
class BitmapDecodedImageHandle(val bitmap: Bitmap) : DecodedImageHandle {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    /** Android allocation capacity can exceed row stride times height after bitmap reuse. */
    override val costBytes: Int get() = bitmap.allocationByteCount
}

/**
 * Real [ImageDecoding] producer using [BitmapFactory]. Runs decode on [Dispatchers.Default]
 * since `BitmapFactory.decodeByteArray` is a blocking, potentially expensive CPU call that must
 * not run on the caller's dispatcher uninterrupted.
 */
class BitmapImageDecoder(private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default) :
    ImageDecoding {
    override suspend fun decode(data: ByteArray, maxDimension: Int?): ImageDecodeOutcome =
        decodeWithBitmapOwnership(dispatcher) {
            try {
                if (data.isEmpty()) return@decodeWithBitmapOwnership ImageDecodeOutcome.Failed("empty payload")
                if (maxDimension != null && maxDimension <= 0) {
                    return@decodeWithBitmapOwnership ImageDecodeOutcome.Failed("maximum image dimension must be positive")
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                coroutineContext.ensureActive()
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return@decodeWithBitmapOwnership ImageDecodeOutcome.Failed("not a decodable image")
                }
                val sampleSize = maxDimension?.let { sampleSizeFor(bounds.outWidth, bounds.outHeight, it) } ?: 1
                val width = (bounds.outWidth.toLong() + sampleSize - 1) / sampleSize
                val height = (bounds.outHeight.toLong() + sampleSize - 1) / sampleSize
                if (width * height > MAX_DECODED_BYTES / 4) {
                    return@decodeWithBitmapOwnership ImageDecodeOutcome.Failed("decoded pixels exceed the image byte bound")
                }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, options)
                    ?: return@decodeWithBitmapOwnership ImageDecodeOutcome.Failed("BitmapFactory could not decode the payload")
                own(decoded)
                coroutineContext.ensureActive()
                if (decoded.allocationByteCount.toLong() > MAX_DECODED_BYTES) {
                    return@decodeWithBitmapOwnership ImageDecodeOutcome.Failed("decoded allocation exceeds the image byte bound")
                }
                val transformed = applyOrientation(decoded, data)
                if (transformed !== decoded) {
                    own(transformed)
                    recycle(decoded)
                }
                coroutineContext.ensureActive()
                if (transformed.allocationByteCount.toLong() > MAX_DECODED_BYTES) {
                    return@decodeWithBitmapOwnership ImageDecodeOutcome.Failed("decoded allocation exceeds the image byte bound")
                }
                ImageDecodeOutcome.Decoded(BitmapDecodedImageHandle(transformed))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: OutOfMemoryError) {
                ImageDecodeOutcome.Failed("decode exceeded available memory: ${error.message}")
            } catch (error: IllegalArgumentException) {
                ImageDecodeOutcome.Failed(error.message ?: "invalid image payload")
            } catch (_: ArithmeticException) {
                ImageDecodeOutcome.Failed("image dimensions exceed supported bounds")
            }
        }

    /** Smallest power-of-two `inSampleSize` that keeps both dimensions at or under [maxDimension]. */
    private fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        val side = maxOf(width, height).toLong()
        while ((side + sampleSize - 1) / sampleSize > maxDimension) {
            if (sampleSize > Int.MAX_VALUE / 2) throw ArithmeticException("Image sample size overflow")
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun applyOrientation(bitmap: Bitmap, data: ByteArray): Bitmap {
        val orientation = try {
            data.inputStream().use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (error: IOException) {
            Logger.getLogger("MeshCore.ImageDecoder").fine("Image orientation metadata unavailable: ${error.javaClass.simpleName}")
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private companion object {
        const val MAX_DECODED_BYTES = 100L * 1024 * 1024
    }
}
