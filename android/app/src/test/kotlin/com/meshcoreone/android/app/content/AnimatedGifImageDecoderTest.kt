// PortedFrom: MC1/Services/ImageURLDetector.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.content

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import com.meshcoreone.android.core.services.content.ImageDecodeOutcome
import com.meshcoreone.android.core.services.content.CachedDecodedImage
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimatedGifImageDecoderTest {
    @Test
    fun `animation cache charges each retained native frame allocation plus share bytes`() {
        val encoded = NativeImageFixtures.png(64, 32)
        val frames = List(2) {
            requireNotNull(BitmapFactory.decodeByteArray(
                encoded, 0, encoded.size, BitmapFactory.Options().apply { inMutable = true },
            ))
        }
        try {
            val allocation = frames.sumOf { it.allocationByteCount }
            frames.forEach { it.reconfigure(1, 1, Bitmap.Config.ARGB_8888) }
            assertTrue(allocation > frames.sumOf { it.rowBytes * it.height })
            val metadata = requireNotNull(com.meshcoreone.android.core.services.content.GifImageMetadata.parse(gif()))
            val handle = GifDecodedImageHandle(frames, metadata, 1, 1)
            assertEquals(allocation, handle.costBytes)
            val bytes = gif()
            assertEquals(allocation.toLong() + bytes.size, CachedDecodedImage(handle, true, bytes).cost)
        } finally {
            frames.forEach { it.recycle() }
        }
    }

    private fun gif(): ByteArray = "GIF89a".toByteArray(Charsets.US_ASCII) + byteArrayOf(
        1, 0, 1, 0, 0x80.toByte(), 0, 0, 0, 0, 0, 0xff.toByte(), 0, 0,
        0x21, 0xf9.toByte(), 4, 4, 1, 0, 0, 0,
        0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0,
        0x21, 0xf9.toByte(), 4, 4, 10, 0, 0, 0,
        0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x4c, 1, 0, 0x3b,
    )

    @Test
    fun `real native GIF producer returns animation rather than a static fake bitmap`() = runTest {
        val result = AnimatedGifImageDecoder().decode(gif())
        assertIs<ImageDecodeOutcome.Decoded>(result)
        val handle = result.handle
        assertIs<GifDecodedImageHandle>(handle)
        assertTrue(handle.isAnimated)
        assertEquals(listOf(20L, 100L), handle.metadata.frameDelaysMillis)
        assertEquals(120L, handle.metadata.durationMillis)
        assertEquals(8, handle.costBytes)
    }

    @Test
    fun `GIF frame decode budget is checked before native pixel allocation`() = runTest {
        val prefix = gif().copyOfRange(0, 19).also {
            it[6] = 0xff.toByte(); it[7] = 0xff.toByte()
            it[8] = 0xff.toByte(); it[9] = 0xff.toByte()
        }
        val frame = gif().copyOfRange(19, 42)
        val output = java.io.ByteArrayOutputStream()
        output.write(prefix)
        repeat(40) { output.write(frame) }
        output.write(0x3b)
        val oversized = output.toByteArray()
        val result = AnimatedGifImageDecoder().decode(oversized)
        assertIs<ImageDecodeOutcome.Failed>(result)
        assertEquals("GIF frame working set exceeds the decoded byte bound", result.reason)
    }

    private fun pixels(drawable: Drawable): List<Int> {
        assertTrue(Build.VERSION.SDK_INT in setOf(31, 37))
        println("WP218_GIF_NATIVE_PIXELS_SDK|${Build.VERSION.SDK_INT}")
        val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            return (0 until bitmap.height).flatMap { y ->
                (0 until bitmap.width).map { x -> bitmap.getPixel(x, y) }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private class Callback : Drawable.Callback {
        var scheduled: Runnable? = null
        var at = -1L
        var invalidations = 0
        override fun invalidateDrawable(who: Drawable) { invalidations++ }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            scheduled = what
            at = `when`
        }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            if (scheduled === what) scheduled = null
        }
    }

    @Test
    fun `native pixels use equal frame intervals over the summed clamped source duration`() = runTest {
        val handle = assertIs<GifDecodedImageHandle>(
            assertIs<ImageDecodeOutcome.Decoded>(AnimatedGifImageDecoder().decode(gif())).handle,
        )
        var now = 0L
        val drawable = handle.newDrawable { now }
        val callback = Callback()
        drawable.callback = callback
        drawable.start()
        assertEquals(60L, callback.at)
        assertEquals(listOf(Color.BLACK), pixels(drawable))
        now = 59
        assertEquals(listOf(Color.BLACK), pixels(drawable))
        now = 60
        requireNotNull(callback.scheduled).run()
        assertEquals(120L, callback.at)
        assertEquals(listOf(Color.RED), pixels(drawable))
        now = 119
        assertEquals(listOf(Color.RED), pixels(drawable))
        now = 120
        assertEquals(listOf(Color.BLACK), pixels(drawable))
        drawable.stop()
        assertFalse(drawable.isRunning)
        assertEquals(null, callback.scheduled)
        assertEquals(listOf(Color.BLACK), pixels(drawable))
    }

    @Test
    fun `cached GIF consumers have independent clocks and source stop motion and manual play behavior`() = runTest {
        val handle = assertIs<GifDecodedImageHandle>(
            assertIs<ImageDecodeOutcome.Decoded>(AnimatedGifImageDecoder().decode(gif())).handle,
        )
        var now = 0L
        val first = GifImagePlayback(handle.newDrawable { now })
        val second = GifImagePlayback(handle.newDrawable { now })
        assertNotSame(first.drawable, second.drawable)
        assertNotSame(handle.drawable, handle.drawable)
        val callback = Callback()
        first.drawable.callback = callback
        first.appear(autoPlayGIFs = true, accessibilityReduceMotion = false)
        second.appear(autoPlayGIFs = true, accessibilityReduceMotion = true)
        now = 60
        assertEquals(listOf(Color.RED), pixels(first.drawable))
        assertEquals(listOf(Color.BLACK), pixels(second.drawable))
        val stale = requireNotNull(callback.scheduled)
        first.setReduceMotion(true)
        assertEquals(null, callback.scheduled)
        assertEquals(listOf(Color.BLACK), pixels(first.drawable))
        first.setReduceMotion(false)
        assertFalse(first.drawable.isRunning)
        second.toggle() // Source permits explicit user playback even with reduced motion.
        now = 120
        assertEquals(listOf(Color.RED), pixels(second.drawable))
        second.setAutoPlay(true)
        second.setReduceMotion(true)
        assertTrue(second.drawable.isRunning)
        second.setAutoPlay(false)
        assertFalse(second.drawable.isRunning)
        second.toggle()
        second.close()
        assertFalse(second.drawable.isRunning)
        first.close()
        stale.run()
        assertEquals(null, callback.scheduled)
        assertEquals(listOf(Color.BLACK), pixels(second.drawable))
    }

    private data class Frame(
        val indices: List<Int>,
        val width: Int = 1,
        val height: Int = 1,
        val left: Int = 0,
        val top: Int = 0,
        val delay: Int = 2,
        val disposal: Int = 1,
        val transparent: Boolean = false,
        val interlaced: Boolean = false,
        val localPalette: ByteArray? = null,
    )

    private fun gif(width: Int, height: Int, frames: List<Frame>): ByteArray {
        val output = ByteArrayOutputStream()
        fun word(value: Int) { output.write(value and 255); output.write(value shr 8) }
        output.write("GIF89a".toByteArray(Charsets.US_ASCII))
        word(width); word(height)
        output.write(byteArrayOf(0x81.toByte(), 0, 0))
        output.write(byteArrayOf(0, 0, 0, 255.toByte(), 0, 0, 0, 255.toByte(), 0, 0, 0, 255.toByte()))
        frames.forEach { frame ->
            output.write(byteArrayOf(0x21, 0xf9.toByte(), 4))
            output.write((frame.disposal shl 2) or if (frame.transparent) 1 else 0)
            word(frame.delay)
            output.write(byteArrayOf(0, 0, 0x2c))
            word(frame.left); word(frame.top); word(frame.width); word(frame.height)
            output.write((if (frame.interlaced) 0x40 else 0) or if (frame.localPalette != null) 0x81 else 0)
            frame.localPalette?.let { output.write(it) }
            // Independent GIF89a fixture: fixed 3-bit codes, clear before each palette index.
            val codes = frame.indices.flatMap { listOf(4, it) } + 5
            val compressed = ByteArray((codes.size * 3 + 7) / 8)
            codes.forEachIndexed { index, code ->
                repeat(3) { bit ->
                    val position = index * 3 + bit
                    compressed[position / 8] = (compressed[position / 8].toInt() or
                        (((code shr bit) and 1) shl (position % 8))).toByte()
                }
            }
            output.write(2)
            compressed.toList().chunked(255).forEach { block ->
                output.write(block.size)
                output.write(block.toByteArray())
            }
            output.write(0)
        }
        output.write(0x3b)
        return output.toByteArray()
    }

    @Test
    fun `native compositor preserves frame offsets transparency and restore previous background disposals`() = runTest {
        val encoded = gif(2, 1, listOf(
            Frame(listOf(1, 1), width = 2),
            Frame(listOf(2), left = 1, disposal = 3, delay = 10),
            Frame(listOf(3)),
            Frame(listOf(0), left = 1, transparent = true, disposal = 2),
            Frame(listOf(2)),
        ))
        val handle = assertIs<GifDecodedImageHandle>(
            assertIs<ImageDecodeOutcome.Decoded>(AnimatedGifImageDecoder().decode(encoded)).handle,
        )
        var now = 0L
        val drawable = handle.newDrawable { now }
        drawable.start()
        val expected = listOf(
            listOf(Color.RED, Color.RED),
            listOf(Color.RED, Color.GREEN),
            listOf(Color.BLUE, Color.RED),
            listOf(Color.BLUE, Color.RED),
            listOf(Color.GREEN, Color.TRANSPARENT),
        )
        expected.forEachIndexed { index, colors ->
            now = index * 36L
            assertEquals(colors, pixels(drawable), "composited frame $index")
        }
        assertEquals(40, handle.costBytes)
    }

    @Test
    fun `native interlace local palettes and nonintegral uniform boundaries render actual pixels`() = runTest {
        val palette = byteArrayOf(0, 0, 0, 0, 255.toByte(), 0, 255.toByte(), 0, 0, 0, 0, 255.toByte())
        val encoded = gif(1, 4, listOf(
            Frame(listOf(1, 3, 2, 0), height = 4, interlaced = true),
            Frame(listOf(1, 1, 1, 1), height = 4, localPalette = palette, delay = 3),
            Frame(listOf(3, 3, 3, 3), height = 4, delay = 5),
        ))
        val handle = assertIs<GifDecodedImageHandle>(
            assertIs<ImageDecodeOutcome.Decoded>(AnimatedGifImageDecoder().decode(encoded)).handle,
        )
        var now = 0L
        val drawable = handle.newDrawable { now }
        val callback = Callback()
        drawable.callback = callback
        drawable.start()
        assertEquals(34L, callback.at)
        assertEquals(listOf(Color.RED, Color.GREEN, Color.BLUE, Color.BLACK), pixels(drawable))
        now = 33
        assertEquals(listOf(Color.RED, Color.GREEN, Color.BLUE, Color.BLACK), pixels(drawable))
        now = 34
        requireNotNull(callback.scheduled).run()
        assertEquals(67L, callback.at)
        assertEquals(List(4) { Color.GREEN }, pixels(drawable))
        now = 67
        requireNotNull(callback.scheduled).run()
        assertEquals(100L, callback.at)
        assertEquals(List(4) { Color.BLUE }, pixels(drawable))
        now = 100
        assertEquals(listOf(Color.RED, Color.GREEN, Color.BLUE, Color.BLACK), pixels(drawable))
    }

    @Test
    fun `opaque restore background uses the global palette without clearing neighboring pixels`() = runTest {
        val encoded = gif(2, 1, listOf(
            Frame(listOf(1, 1), width = 2),
            Frame(listOf(2), left = 1, disposal = 2),
            Frame(listOf(3)),
        ))
        val handle = assertIs<GifDecodedImageHandle>(
            assertIs<ImageDecodeOutcome.Decoded>(AnimatedGifImageDecoder().decode(encoded)).handle,
        )
        var now = 0L
        val drawable = handle.newDrawable { now }
        drawable.start()
        assertEquals(listOf(Color.RED, Color.RED), pixels(drawable))
        now = 20
        assertEquals(listOf(Color.RED, Color.GREEN), pixels(drawable))
        now = 40
        assertEquals(listOf(Color.BLUE, Color.BLACK), pixels(drawable))
    }

    @Test
    fun `single frame stays static and malformed screen rectangles fail before oversized native allocation`() = runTest {
        val static = gif(1, 1, listOf(Frame(listOf(1))))
        val handle = assertIs<GifDecodedImageHandle>(
            assertIs<ImageDecodeOutcome.Decoded>(AnimatedGifImageDecoder().decode(static)).handle,
        )
        assertFalse(handle.isAnimated)
        val drawable = handle.newDrawable()
        drawable.start()
        assertFalse(drawable.isRunning)
        assertEquals(listOf(Color.RED), pixels(drawable))
        val invalid = gif(1, 1, listOf(Frame(listOf(1), width = 65535)))
        assertIs<ImageDecodeOutcome.Failed>(AnimatedGifImageDecoder().decode(invalid))
    }

    @Test
    fun `actual native frame pixels are downsampled before the retained animation allocation`() = runTest {
        val encoded = gif(1000, 1, listOf(
            Frame(List(1000) { 1 }, width = 1000),
            Frame(List(1000) { 2 }, width = 1000),
        ))
        val handle = assertIs<GifDecodedImageHandle>(
            assertIs<ImageDecodeOutcome.Decoded>(AnimatedGifImageDecoder().decode(encoded)).handle,
        )
        assertEquals(900, handle.width)
        assertEquals(1, handle.height)
        assertEquals(7200, handle.costBytes)
        var now = 0L
        val drawable = handle.newDrawable { now }
        drawable.start()
        assertEquals(List(900) { Color.RED }, pixels(drawable))
        now = 20
        assertEquals(List(900) { Color.GREEN }, pixels(drawable))
    }

    @Test
    fun `cancelled decoder does not publish a native handle`() = runTest {
        var published = false
        val job = launch {
            AnimatedGifImageDecoder().decode(gif())
            published = true
        }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(published)
    }
}
