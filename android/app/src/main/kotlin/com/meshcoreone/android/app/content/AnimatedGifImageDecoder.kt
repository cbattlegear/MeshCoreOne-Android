// PortedFrom: MC1/Services/ImageURLDetector.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.content

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.meshcoreone.android.core.services.content.DecodedImageHandle
import com.meshcoreone.android.core.services.content.GifImageMetadata
import com.meshcoreone.android.core.services.content.ImageDecodeOutcome
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

class GifDecodedImageHandle internal constructor(
    private val frames: List<Bitmap>,
    val metadata: GifImageMetadata,
    override val width: Int,
    override val height: Int,
) : DecodedImageHandle {
    override val costBytes: Int = Math.toIntExact(frames.sumOf { it.allocationByteCount.toLong() })
    val isAnimated: Boolean get() = frames.size > 1
    val drawable: Drawable get() = newDrawable()

    fun newDrawable(): UniformGifDrawable = UniformGifDrawable(frames, metadata.durationMillis)

    internal fun newDrawable(clock: () -> Long): UniformGifDrawable =
        UniformGifDrawable(frames, metadata.durationMillis, clock)
}

class AnimatedGifImageDecoder(private val dispatcher: CoroutineDispatcher = Dispatchers.Default) {
    suspend fun decode(data: ByteArray): ImageDecodeOutcome = decodeWithBitmapOwnership(dispatcher) {
        val encoded = data.copyOf()
        val metadata = GifImageMetadata.parse(encoded)
            ?: return@decodeWithBitmapOwnership ImageDecodeOutcome.Failed("Invalid GIF container")
        val scale = (900.0 / maxOf(metadata.width, metadata.height)).coerceAtMost(1.0)
        val width = (metadata.width * scale).roundToInt().coerceAtLeast(1)
        val height = (metadata.height * scale).roundToInt().coerceAtLeast(1)
        // Retained frames plus compositor, disposal-3 restore and native frame decode.
        if (width.toLong() * height * (metadata.frameDelaysMillis.size.toLong() + 3) >
            100L * 1024 * 1024 / 4) {
            return@decodeWithBitmapOwnership ImageDecodeOutcome.Failed("GIF frame working set exceeds the decoded byte bound")
        }
        val frames = mutableListOf<Bitmap>()
        var working: Bitmap? = null
        try {
            val context = coroutineContext
            val container = GifFrameSource(encoded, metadata)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            working = bitmap
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val clearPaint = Paint().apply {
                xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.SRC)
            }
            container.forEachFrame { frame ->
                context.ensureActive()
                if (frames.isEmpty()) bitmap.eraseColor(frame.background)
                val destination = RectF(
                    frame.left.toFloat() * width / metadata.width,
                    frame.top.toFloat() * height / metadata.height,
                    (frame.left + frame.width).toFloat() * width / metadata.width,
                    (frame.top + frame.height).toFloat() * height / metadata.height,
                )
                val restore = if (frame.disposal == 3) bitmap.copy(Bitmap.Config.ARGB_8888, false) else null
                try {
                    val decoded = ImageDecoder.decodeBitmap(
                        ImageDecoder.createSource(ByteBuffer.wrap(container.singleFrame(frame))),
                    ) { decoder, _, _ ->
                        decoder.setTargetSize(
                            destination.width().roundToInt().coerceAtLeast(1),
                            destination.height().roundToInt().coerceAtLeast(1),
                        )
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    try {
                        context.ensureActive()
                        canvas.drawBitmap(decoded, null, destination, paint)
                    } finally {
                        decoded.recycle()
                    }
                    frames.add(own(bitmap.copy(Bitmap.Config.ARGB_8888, false)))
                    when (frame.disposal) {
                        2 -> {
                            clearPaint.color = frame.background
                            canvas.drawRect(destination, clearPaint)
                        }
                        3 -> {
                            bitmap.eraseColor(Color.TRANSPARENT)
                            canvas.drawBitmap(requireNotNull(restore), 0f, 0f, null)
                        }
                    }
                } finally {
                    restore?.recycle()
                }
            }
            check(frames.size == metadata.frameDelaysMillis.size)
            coroutineContext.ensureActive()
            ImageDecodeOutcome.Decoded(GifDecodedImageHandle(frames.toList(), metadata, width, height))
        } catch (error: IOException) {
            ImageDecodeOutcome.Failed("GIF platform decode failed: ${error.javaClass.simpleName}")
        } catch (_: IllegalArgumentException) {
            ImageDecodeOutcome.Failed("GIF platform decode rejected the container")
        } catch (_: OutOfMemoryError) {
            ImageDecodeOutcome.Failed("GIF decode exceeded available memory")
        } finally {
            working?.recycle()
        }
    }
}

private class GifFrameSource(private val bytes: ByteArray, private val metadata: GifImageMetadata) {
    data class Frame(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val disposal: Int,
        val background: Int,
        val control: ByteArray?,
        val start: Int,
        val end: Int,
    )

    private fun byte(index: Int): Int = bytes[index].toInt() and 255
    private fun little(index: Int): Int = byte(index) or (byte(index + 1) shl 8)
    private val headerEnd = 13 + if (byte(10) and 128 != 0) 3 * (1 shl ((byte(10) and 7) + 1)) else 0
    private val background: Int = if (headerEnd > 13) {
        val index = 13 + byte(11) * 3
        require(index + 2 < headerEnd) { "Invalid GIF background palette index" }
        Color.rgb(byte(index), byte(index + 1), byte(index + 2))
    } else Color.TRANSPARENT

    fun forEachFrame(consume: (Frame) -> Unit) {
        var cursor = headerEnd
        var control: ByteArray? = null
        fun skipBlocks() {
            while (true) {
                val size = byte(cursor++)
                if (size == 0) return
                cursor += size
            }
        }
        // GifImageMetadata has already validated the complete block structure.
        while (cursor < bytes.size) {
            when (byte(cursor++)) {
                0x3b -> return
                0x21 -> {
                    val label = byte(cursor++)
                    if (label == 0xf9) {
                        control = bytes.copyOfRange(cursor - 2, cursor + 6)
                        cursor += 6
                    } else skipBlocks()
                }
                0x2c -> {
                    val start = cursor - 1
                    val left = little(cursor)
                    val top = little(cursor + 2)
                    val width = little(cursor + 4)
                    val height = little(cursor + 6)
                    require(left + width <= metadata.width && top + height <= metadata.height) {
                        "GIF frame exceeds its logical screen"
                    }
                    val packed = byte(cursor + 8)
                    cursor += 9
                    if (packed and 128 != 0) cursor += 3 * (1 shl ((packed and 7) + 1))
                    cursor++
                    skipBlocks()
                    val flags = control?.get(3)?.toInt()?.and(255) ?: 0
                    val disposal = flags shr 2 and 7
                    require(disposal <= 3) { "Undefined GIF disposal method" }
                    consume(Frame(
                        left, top, width, height, disposal,
                        if (flags and 1 != 0) Color.TRANSPARENT else background,
                        control, start, cursor,
                    ))
                    control = null
                }
            }
        }
    }

    fun singleFrame(frame: Frame): ByteArray {
        val header = bytes.copyOfRange(0, headerEnd)
        header[6] = frame.width.toByte()
        header[7] = (frame.width shr 8).toByte()
        header[8] = frame.height.toByte()
        header[9] = (frame.height shr 8).toByte()
        val image = bytes.copyOfRange(frame.start, frame.end)
        for (index in 1..4) image[index] = 0
        return ByteArrayOutputStream(header.size + image.size + 9).apply {
            write(header)
            frame.control?.let { write(it) }
            write(image)
            write(0x3b)
        }.toByteArray()
    }
}
