// PortedFrom: MC1/Services/ImageHeaderDecoder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// JDK17 javax.imageio is NOT an Android API, so dimensions are parsed directly from each
// format's own header bytes rather than via ImageIO/CGImageSource/BitmapFactory. The Swift
// source's `CGImageSourceCreateWithData` accepts any system-registered format; its two real
// consumers (InlineImageCache, LinkPreviewCache hero images) receive arbitrary web content, so
// PNG-only coverage would misrepresent the production contract even though the original test
// suite (MC1Tests/Services/ImageHeaderDecoderTests.swift) only exercises PNG. PNG, JPEG, GIF and
// WebP (the formats that overwhelmingly dominate real link-preview/inline-image web content) are
// each parsed from their own public, openly documented container/bitstream header layout - no
// ImageIO/BitmapFactory call, no bundled/ported decoder code. Rarer formats (BMP, TIFF, HEIC) are
// out of scope for this slice; see WP-218 deviations doc.
//
// PortedFrom: MC1/Services/ImageURLDetector.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// (`isGIFData` only -- the file's one pure, platform-independent piece). `ImageURLDetector`'s
// other members (`downsampledImage`, `decodeGIFImage`) are UIKit/ImageIO-based animated-decode
// logic with no pure-JVM equivalent; they map onto this module's already-defined native-adapter
// role (`ImageDecoding.kt`, which already cross-references `ImageURLDetector` in its own header)
// rather than belonging here. [isGifData] below is the narrower 4-byte `GIF8` magic-number sniff
// the source exposes as a standalone public function (distinct from [decodeGif]'s fuller 6-byte
// `GIF87a`/`GIF89a` signature check, which parses actual dimensions and would reject a
// hypothetical future GIF variant whose magic differs only in its version suffix).
package com.meshcoreone.android.core.services.content

/**
 * Shared image header decoder. Reads pixel width and height from an image payload
 * without decoding the full bitmap.
 */
object ImageHeaderDecoder {
    private val pngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private val gifSignature87a = "GIF87a".toByteArray(Charsets.US_ASCII)
    private val gifSignature89a = "GIF89a".toByteArray(Charsets.US_ASCII)
    private val riffSignature = "RIFF".toByteArray(Charsets.US_ASCII)
    private val webpSignature = "WEBP".toByteArray(Charsets.US_ASCII)
    private val vp8xChunkId = "VP8X".toByteArray(Charsets.US_ASCII)
    private val vp8LosslessChunkId = "VP8L".toByteArray(Charsets.US_ASCII)
    private val vp8LossyChunkId = "VP8 ".toByteArray(Charsets.US_ASCII)
    private val vp8LossyStartCode = byteArrayOf(0x9D.toByte(), 0x01, 0x2A)

    /**
     * Returns pixel width and height parsed from the image header in [data].
     * Returns `null` if the bytes are not a recognized image or either dimension is
     * missing or non-positive.
     */
    fun decodeDimensions(data: ByteArray): Pair<Int, Int>? =
        decodePng(data) ?: decodeGif(data) ?: decodeJpeg(data) ?: decodeWebp(data)

    /**
     * Returns `true` if [data] begins with the GIF magic bytes (`GIF8`). PortedFrom
     * `ImageURLDetector.isGIFData` -- see this file's header for why it lives here rather than a
     * dedicated file.
     */
    fun isGifData(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return data[0] == 0x47.toByte() && // G
            data[1] == 0x49.toByte() && // I
            data[2] == 0x46.toByte() && // F
            data[3] == 0x38.toByte() // 8
    }

    private fun decodePng(data: ByteArray): Pair<Int, Int>? {
        // Signature (8) + IHDR length (4) + "IHDR" (4) + width (4) + height (4).
        val minimumLength = pngSignature.size + 4 + 4 + 4 + 4
        if (data.size < minimumLength) return null
        if (!matches(data, 0, pngSignature)) return null

        var offset = pngSignature.size
        val chunkLength = readUInt32BigEndian(data, offset)
        offset += 4
        val chunkType = String(data, offset, 4, Charsets.US_ASCII)
        offset += 4
        if (chunkType != "IHDR" || chunkLength < 8) return null

        val width = readUInt32BigEndian(data, offset).toInt()
        offset += 4
        val height = readUInt32BigEndian(data, offset).toInt()

        return positiveOrNull(width, height)
    }

    private fun decodeGif(data: ByteArray): Pair<Int, Int>? {
        // Signature (6) + Logical Screen Descriptor width (2 LE) + height (2 LE).
        if (data.size < 10) return null
        if (!matches(data, 0, gifSignature87a) && !matches(data, 0, gifSignature89a)) return null
        val width = readUInt16LittleEndian(data, 6)
        val height = readUInt16LittleEndian(data, 8)
        return positiveOrNull(width, height)
    }

    private fun decodeJpeg(data: ByteArray): Pair<Int, Int>? {
        // SOI (0xFFD8), then a marker segment stream; dimensions live in the first SOF marker.
        if (data.size < 4) return null
        if (data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte()) return null

        var offset = 2
        while (offset + 1 < data.size) {
            if (data[offset] != 0xFF.toByte()) return null
            var marker = data[offset + 1]
            offset += 2
            // Skip fill bytes (0xFF padding between markers).
            while (marker == 0xFF.toByte() && offset < data.size) {
                marker = data[offset]
                offset += 1
            }
            val markerValue = marker.toInt() and 0xFF
            // Markers with no payload: TEM (0x01) and RSTn (0xD0-0xD7).
            if (markerValue == 0x01 || markerValue in 0xD0..0xD7) continue
            // SOS: entropy-coded data follows; no SOF marker was found before it.
            if (markerValue == 0xDA) return null
            if (offset + 1 >= data.size) return null
            val segmentLength = readUInt16BigEndian(data, offset)
            if (segmentLength < 2) return null
            val isSof = markerValue in 0xC0..0xCF &&
                markerValue != 0xC4 && markerValue != 0xC8 && markerValue != 0xCC
            if (isSof) {
                // Segment payload: 1 byte precision, 2 bytes height (BE), 2 bytes width (BE).
                if (offset + 7 > data.size) return null
                val height = readUInt16BigEndian(data, offset + 3)
                val width = readUInt16BigEndian(data, offset + 5)
                return positiveOrNull(width, height)
            }
            offset += segmentLength
        }
        return null
    }

    private fun decodeWebp(data: ByteArray): Pair<Int, Int>? {
        // RIFF container: "RIFF" + size(4 LE) + "WEBP" + first chunk header(FourCC + size).
        if (data.size < 20) return null
        if (!matches(data, 0, riffSignature) || !matches(data, 8, webpSignature)) return null

        val chunkId = data.copyOfRange(12, 16)
        val chunkDataOffset = 20
        return when {
            matches(chunkId, 0, vp8xChunkId) -> {
                // Flags(1) + reserved(3) + canvas width-1(3 LE) + canvas height-1(3 LE).
                if (data.size < chunkDataOffset + 10) return null
                val width = readUInt24LittleEndian(data, chunkDataOffset + 4) + 1
                val height = readUInt24LittleEndian(data, chunkDataOffset + 7) + 1
                positiveOrNull(width, height)
            }
            matches(chunkId, 0, vp8LossyChunkId) -> {
                // Frame tag(3) + start code(3) + width(2 LE, 14 bits) + height(2 LE, 14 bits).
                if (data.size < chunkDataOffset + 10) return null
                if (!matches(data, chunkDataOffset + 3, vp8LossyStartCode)) return null
                val width = readUInt16LittleEndian(data, chunkDataOffset + 6) and 0x3FFF
                val height = readUInt16LittleEndian(data, chunkDataOffset + 8) and 0x3FFF
                positiveOrNull(width, height)
            }
            matches(chunkId, 0, vp8LosslessChunkId) -> {
                // Signature(1, 0x2F) + 4 bytes packed: 14-bit width-1, 14-bit height-1, ...
                if (data.size < chunkDataOffset + 5) return null
                if (data[chunkDataOffset] != 0x2F.toByte()) return null
                val bits = readUInt32LittleEndian(data, chunkDataOffset + 1)
                val width = ((bits and 0x3FFF) + 1).toInt()
                val height = (((bits ushr 14) and 0x3FFF) + 1).toInt()
                positiveOrNull(width, height)
            }
            else -> null
        }
    }

    private fun positiveOrNull(width: Int, height: Int): Pair<Int, Int>? =
        if (width > 0 && height > 0) width to height else null

    private fun matches(data: ByteArray, offset: Int, signature: ByteArray): Boolean {
        if (offset < 0 || offset + signature.size > data.size) return false
        for (index in signature.indices) {
            if (data[offset + index] != signature[index]) return false
        }
        return true
    }

    private fun readUInt16BigEndian(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readUInt16LittleEndian(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt24LittleEndian(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)

    private fun readUInt32LittleEndian(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)

    private fun readUInt32BigEndian(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }
}
