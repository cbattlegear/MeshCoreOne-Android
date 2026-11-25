// PortedFrom: MC1/Services/ImageURLDetector.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.content

import com.meshcoreone.android.core.services.content.ImageDecoding
import com.meshcoreone.android.core.services.content.ImageDecodeOutcome
import com.meshcoreone.android.core.services.content.ImageHeaderDecoder

class InlineImageDecoder(
    private val bitmaps: BitmapImageDecoder = BitmapImageDecoder(),
    private val gifs: AnimatedGifImageDecoder = AnimatedGifImageDecoder(),
) : ImageDecoding {
    override suspend fun decode(data: ByteArray, maxDimension: Int?): ImageDecodeOutcome =
        if (ImageHeaderDecoder.isGifData(data)) gifs.decode(data)
        else bitmaps.decode(data, maxDimension ?: 900)
}
