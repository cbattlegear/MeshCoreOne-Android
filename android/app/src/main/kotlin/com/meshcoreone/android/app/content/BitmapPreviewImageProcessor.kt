// PortedFrom: MC1/Services/LinkPreviewService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Services/LinkPreviewService+Scrape.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.content

import android.graphics.Bitmap
import com.meshcoreone.android.core.services.content.ImageDecodeOutcome
import com.meshcoreone.android.core.services.content.PreviewImageProcessing
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

class BitmapPreviewImageProcessor(
    private val decoder: BitmapImageDecoder = BitmapImageDecoder(),
) : PreviewImageProcessing {
    override suspend fun boundedPreviewImage(data: ByteArray): ByteArray? = withContext(Dispatchers.Default) {
        val decoded = decoder.decode(data, MAX_PIXEL_SIZE)
        if (decoded !is ImageDecodeOutcome.Decoded) return@withContext null
        val handle = decoded.handle
        check(handle is BitmapDecodedImageHandle) { "Bitmap decoder returned a non-Bitmap image" }
        var image = handle.bitmap
        try {
            var quality = 90
            var encoded = jpeg(image, quality) ?: return@withContext null
            while (encoded.size > MAX_ENCODED_BYTES && quality > 10) {
                coroutineContext.ensureActive()
                quality -= 10
                encoded = jpeg(image, quality) ?: return@withContext null
            }
            while (encoded.size > MAX_ENCODED_BYTES) {
                coroutineContext.ensureActive()
                val scale = sqrt(MAX_ENCODED_BYTES.toDouble() / encoded.size) * 0.95
                val width = (image.width * scale).toInt().coerceAtLeast(1)
                val height = (image.height * scale).toInt().coerceAtLeast(1)
                if (width == image.width && height == image.height) return@withContext null
                val next = Bitmap.createScaledBitmap(image, width, height, true)
                if (next !== image) image.recycle()
                image = next
                encoded = jpeg(image, 70) ?: return@withContext null
            }
            encoded
        } finally {
            image.recycle()
        }
    }

    private fun jpeg(image: Bitmap, quality: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        return if (image.compress(Bitmap.CompressFormat.JPEG, quality, output)) output.toByteArray() else null
    }

    private companion object {
        const val MAX_PIXEL_SIZE = 2000
        const val MAX_ENCODED_BYTES = 500 * 1024
    }
}
