// PortedFrom: MC1Tests/Services/ImageHeaderDecoderTests.swift@db14559b39d32322b06477c6ae676112f583db50
// The original test renders a real PNG via CGContext/CGImageDestination; this port builds
// the equivalent PNG signature + IHDR chunk bytes directly, since ImageHeaderDecoder only
// ever reads that header (it never decodes pixel data).
//
// The original Swift suite only covers PNG, but the real production contract
// (CGImageSourceCreateWithData) is format-agnostic. The JPEG/GIF/WebP cases below are
// independently authored - there is no corresponding original Swift test - built from each
// format's public container/bitstream header layout (same technique as the PNG case: raw
// signature/segment bytes, no real encoder/decoder library involved).
//
// PortedFrom: MC1Tests/Services/ImageURLDetectorTests.swift@db14559b39d32322b06477c6ae676112f583db50
// (the 5 "GIF Magic Byte Detection" cases only -- see `ImageHeaderDecoder.isGifData`'s own
// header comment for why this file owns that function's port).
package com.meshcoreone.android.core.services.content

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageHeaderDecoderTest {
    private fun makeCompletePng(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }

    @Test
    fun `decodeDimensions returns pixel dims for a valid PNG`() {
        val png = makeCompletePng(width = 480, height = 270)
        val dims = ImageHeaderDecoder.decodeDimensions(png)
        assertEquals(480, dims?.first)
        assertEquals(270, dims?.second)
    }

    @Test
    fun `decodeDimensions returns null for non-image bytes`() {
        val dims = ImageHeaderDecoder.decodeDimensions("not an image".toByteArray(Charsets.UTF_8))
        assertNull(dims)
    }

    @Test
    fun `decodeDimensions returns null for empty data`() {
        val dims = ImageHeaderDecoder.decodeDimensions(ByteArray(0))
        assertNull(dims)
    }

    // --- GIF: independently authored, no original Swift test coverage exists ---

    private fun makeGifHeader(width: Int, height: Int, version89a: Boolean = true): ByteArray {
        val bytes = mutableListOf<Byte>()
        bytes.addAll((if (version89a) "GIF89a" else "GIF87a").toByteArray(Charsets.US_ASCII).toList())
        bytes.addAll(uInt16LittleEndian(width))
        bytes.addAll(uInt16LittleEndian(height))
        bytes.add(0x00) // packed fields
        bytes.add(0x00) // background color index
        bytes.add(0x00) // pixel aspect ratio
        return bytes.toByteArray()
    }

    private fun uInt16LittleEndian(value: Int): List<Byte> = listOf(
        (value and 0xFF).toByte(),
        (value ushr 8 and 0xFF).toByte(),
    )

    @Test
    fun `decodeDimensions returns pixel dims for a valid GIF89a`() {
        val gif = makeGifHeader(width = 320, height = 180)
        val dims = ImageHeaderDecoder.decodeDimensions(gif)
        assertEquals(320, dims?.first)
        assertEquals(180, dims?.second)
    }

    @Test
    fun `decodeDimensions returns pixel dims for a valid GIF87a`() {
        val gif = makeGifHeader(width = 64, height = 64, version89a = false)
        val dims = ImageHeaderDecoder.decodeDimensions(gif)
        assertEquals(64, dims?.first)
        assertEquals(64, dims?.second)
    }

    @Test
    fun `decodeDimensions returns null for truncated GIF`() {
        val dims = ImageHeaderDecoder.decodeDimensions("GIF89a".toByteArray(Charsets.US_ASCII))
        assertNull(dims)
    }

    // --- JPEG: independently authored, no original Swift test coverage exists ---

    private fun uInt16BigEndian(value: Int): List<Byte> = listOf(
        (value ushr 8 and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun makeJpegBaseline(width: Int, height: Int): ByteArray {
        val bytes = mutableListOf<Byte>()
        bytes.addAll(listOf(0xFF.toByte(), 0xD8.toByte())) // SOI
        // A preceding APP0/JFIF segment, as real encoders emit, to exercise segment skipping.
        bytes.addAll(listOf(0xFF.toByte(), 0xE0.toByte()))
        bytes.addAll(uInt16BigEndian(16)) // segment length incl. itself
        bytes.addAll("JFIF".toByteArray(Charsets.US_ASCII).toList())
        bytes.add(0x00)
        bytes.addAll(listOf(0x01.toByte(), 0x01.toByte())) // version
        bytes.add(0x00) // density units
        bytes.addAll(uInt16BigEndian(1)) // x density
        bytes.addAll(uInt16BigEndian(1)) // y density
        bytes.add(0x00) // thumbnail width
        bytes.add(0x00) // thumbnail height
        // SOF0 (baseline DCT) segment.
        bytes.addAll(listOf(0xFF.toByte(), 0xC0.toByte()))
        bytes.addAll(uInt16BigEndian(17)) // segment length incl. itself: 1+2+2+1+3*3
        bytes.add(0x08) // precision
        bytes.addAll(uInt16BigEndian(height))
        bytes.addAll(uInt16BigEndian(width))
        bytes.add(0x03) // component count
        repeat(3) {
            bytes.add(0x01) // component id
            bytes.add(0x11) // sampling factors
            bytes.add(0x00) // quant table id
        }
        return bytes.toByteArray()
    }

    @Test
    fun `decodeDimensions returns pixel dims for a valid baseline JPEG`() {
        val jpeg = makeJpegBaseline(width = 1024, height = 768)
        val dims = ImageHeaderDecoder.decodeDimensions(jpeg)
        assertEquals(1024, dims?.first)
        assertEquals(768, dims?.second)
    }

    @Test
    fun `decodeDimensions returns null for JPEG missing SOI`() {
        val dims = ImageHeaderDecoder.decodeDimensions(byteArrayOf(0x00, 0x01, 0x02))
        assertNull(dims)
    }

    @Test
    fun `decodeDimensions returns null for JPEG with no SOF before SOS`() {
        val bytes = mutableListOf<Byte>()
        bytes.addAll(listOf(0xFF.toByte(), 0xD8.toByte())) // SOI
        bytes.addAll(listOf(0xFF.toByte(), 0xDA.toByte())) // SOS, no SOF seen
        val dims = ImageHeaderDecoder.decodeDimensions(bytes.toByteArray())
        assertNull(dims)
    }

    // --- WebP: independently authored, no original Swift test coverage exists ---

    private fun uInt32LittleEndian(value: Int): List<Byte> = listOf(
        (value and 0xFF).toByte(),
        (value ushr 8 and 0xFF).toByte(),
        (value ushr 16 and 0xFF).toByte(),
        (value ushr 24 and 0xFF).toByte(),
    )

    private fun makeWebpVp8xHeader(width: Int, height: Int): ByteArray {
        val bytes = mutableListOf<Byte>()
        bytes.addAll("RIFF".toByteArray(Charsets.US_ASCII).toList())
        bytes.addAll(uInt32LittleEndian(20)) // RIFF chunk size (not exercised by decoder)
        bytes.addAll("WEBP".toByteArray(Charsets.US_ASCII).toList())
        bytes.addAll("VP8X".toByteArray(Charsets.US_ASCII).toList())
        bytes.addAll(uInt32LittleEndian(10)) // chunk size
        bytes.add(0x00) // flags
        bytes.addAll(listOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte())) // reserved
        bytes.addAll(uInt24LittleEndian(width - 1))
        bytes.addAll(uInt24LittleEndian(height - 1))
        return bytes.toByteArray()
    }

    private fun uInt24LittleEndian(value: Int): List<Byte> = listOf(
        (value and 0xFF).toByte(),
        (value ushr 8 and 0xFF).toByte(),
        (value ushr 16 and 0xFF).toByte(),
    )

    @Test
    fun `decodeDimensions returns pixel dims for a valid extended VP8X WebP`() {
        val webp = makeWebpVp8xHeader(width = 400, height = 300)
        val dims = ImageHeaderDecoder.decodeDimensions(webp)
        assertEquals(400, dims?.first)
        assertEquals(300, dims?.second)
    }

    private fun makeWebpVp8LossyHeader(width: Int, height: Int): ByteArray {
        val bytes = mutableListOf<Byte>()
        bytes.addAll("RIFF".toByteArray(Charsets.US_ASCII).toList())
        bytes.addAll(uInt32LittleEndian(18))
        bytes.addAll("WEBP".toByteArray(Charsets.US_ASCII).toList())
        bytes.addAll("VP8 ".toByteArray(Charsets.US_ASCII).toList())
        bytes.addAll(uInt32LittleEndian(10))
        bytes.addAll(listOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte())) // frame tag
        bytes.addAll(listOf(0x9D.toByte(), 0x01.toByte(), 0x2A.toByte())) // start code
        bytes.addAll(uInt16LittleEndian(width and 0x3FFF))
        bytes.addAll(uInt16LittleEndian(height and 0x3FFF))
        return bytes.toByteArray()
    }

    @Test
    fun `decodeDimensions returns pixel dims for a valid lossy VP8 WebP`() {
        val webp = makeWebpVp8LossyHeader(width = 640, height = 480)
        val dims = ImageHeaderDecoder.decodeDimensions(webp)
        assertEquals(640, dims?.first)
        assertEquals(480, dims?.second)
    }

    @Test
    fun `decodeDimensions returns null for WebP with wrong lossy start code`() {
        val bytes = mutableListOf<Byte>()
        bytes.addAll("RIFF".toByteArray(Charsets.US_ASCII).toList())
        bytes.addAll(uInt32LittleEndian(18))
        bytes.addAll("WEBP".toByteArray(Charsets.US_ASCII).toList())
        bytes.addAll("VP8 ".toByteArray(Charsets.US_ASCII).toList())
        bytes.addAll(uInt32LittleEndian(10))
        bytes.addAll(listOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte()))
        bytes.addAll(listOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte())) // wrong start code
        bytes.addAll(uInt16LittleEndian(640))
        bytes.addAll(uInt16LittleEndian(480))
        val dims = ImageHeaderDecoder.decodeDimensions(bytes.toByteArray())
        assertNull(dims)
    }

    private fun makeWebpVp8LosslessHeader(width: Int, height: Int): ByteArray {
        val packed = ((width - 1) and 0x3FFF) or ((((height - 1) and 0x3FFF)) shl 14)
        val bytes = mutableListOf<Byte>()
        bytes.addAll("RIFF".toByteArray(Charsets.US_ASCII).toList())
        bytes.addAll(uInt32LittleEndian(15))
        bytes.addAll("WEBP".toByteArray(Charsets.US_ASCII).toList())
        bytes.addAll("VP8L".toByteArray(Charsets.US_ASCII).toList())
        bytes.addAll(uInt32LittleEndian(5))
        bytes.add(0x2F) // signature byte
        bytes.addAll(uInt32LittleEndian(packed))
        return bytes.toByteArray()
    }

    @Test
    fun `decodeDimensions returns pixel dims for a valid lossless VP8L WebP`() {
        val webp = makeWebpVp8LosslessHeader(width = 256, height = 128)
        val dims = ImageHeaderDecoder.decodeDimensions(webp)
        assertEquals(256, dims?.first)
        assertEquals(128, dims?.second)
    }

    @Test
    fun `decodeDimensions returns null for WebP missing WEBP fourcc`() {
        val bytes = mutableListOf<Byte>()
        bytes.addAll("RIFF".toByteArray(Charsets.US_ASCII).toList())
        bytes.addAll(uInt32LittleEndian(20))
        bytes.addAll("AVIF".toByteArray(Charsets.US_ASCII).toList())
        bytes.addAll(ByteArray(10).toList())
        val dims = ImageHeaderDecoder.decodeDimensions(bytes.toByteArray())
        assertNull(dims)
    }

    // PortedFrom-case: "Detects GIF87a magic bytes" (MC1Tests/Services/ImageURLDetectorTests.swift)
    @Test
    fun `isGifData detects GIF87a magic bytes`() {
        val data = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61)
        assertEquals(true, ImageHeaderDecoder.isGifData(data))
    }

    // PortedFrom-case: "Detects GIF89a magic bytes"
    @Test
    fun `isGifData detects GIF89a magic bytes`() {
        val data = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)
        assertEquals(true, ImageHeaderDecoder.isGifData(data))
    }

    // PortedFrom-case: "Rejects non-GIF data"
    @Test
    fun `isGifData rejects non-GIF data`() {
        val pngData = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        assertEquals(false, ImageHeaderDecoder.isGifData(pngData))
    }

    // PortedFrom-case: "Rejects data shorter than 4 bytes"
    @Test
    fun `isGifData rejects data shorter than 4 bytes`() {
        val data = byteArrayOf(0x47, 0x49, 0x46)
        assertEquals(false, ImageHeaderDecoder.isGifData(data))
    }

    // PortedFrom-case: "Rejects empty data"
    @Test
    fun `isGifData rejects empty data`() {
        assertEquals(false, ImageHeaderDecoder.isGifData(ByteArray(0)))
    }
}
