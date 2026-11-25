// PortedFrom: MC1/Services/ImageURLDetector.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Components/InlineImageView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.content

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock

class UniformGifDrawable internal constructor(
    private val frames: List<Bitmap>,
    private val durationMillis: Long,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) : Drawable(), Animatable {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var running = false
    private var startedAt = 0L
    private val advance = Runnable {
        if (running) {
            invalidateSelf()
            scheduleNext()
        }
    }

    init {
        require(frames.isNotEmpty() && durationMillis >= frames.size)
    }

    private fun boundary(index: Int): Long {
        val count = frames.size.toLong()
        return index * (durationMillis / count) +
            (index * (durationMillis % count) + count - 1) / count
    }

    private fun frameAt(position: Long): Int {
        var low = 0
        var high = frames.size
        while (low + 1 < high) {
            val middle = low + (high - low) / 2
            if (boundary(middle) <= position) low = middle else high = middle
        }
        return low
    }

    private fun scheduleNext() {
        val now = clock()
        val position = (now - startedAt).coerceAtLeast(0) % durationMillis
        scheduleSelf(advance, now + boundary(frameAt(position) + 1) - position)
    }

    override fun draw(canvas: Canvas) {
        val index = if (running) frameAt((clock() - startedAt).coerceAtLeast(0) % durationMillis) else 0
        canvas.drawBitmap(frames[index], null, bounds, paint)
    }

    override fun start() {
        if (running || frames.size == 1 || !isVisible) return
        startedAt = clock()
        running = true
        invalidateSelf()
        scheduleNext()
    }

    override fun stop() {
        running = false
        unscheduleSelf(advance)
        invalidateSelf()
    }

    override fun isRunning(): Boolean = running

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) stop()
        else if (restart && running) {
            stop()
            start()
        }
        return changed
    }

    override fun getIntrinsicWidth(): Int = frames.first().width
    override fun getIntrinsicHeight(): Int = frames.first().height
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

class GifImagePlayback(val drawable: UniformGifDrawable) : AutoCloseable {
    private var autoPlay = false
    private var reduceMotion = false

    fun appear(autoPlayGIFs: Boolean, accessibilityReduceMotion: Boolean) {
        autoPlay = autoPlayGIFs
        reduceMotion = accessibilityReduceMotion
        drawable.setVisible(true, false)
        if (autoPlay && !reduceMotion) drawable.start() else drawable.stop()
    }

    fun setAutoPlay(enabled: Boolean) {
        if (autoPlay == enabled) return
        autoPlay = enabled
        if (autoPlay && !reduceMotion) drawable.start() else drawable.stop()
    }

    fun setReduceMotion(enabled: Boolean) {
        if (reduceMotion == enabled) return
        reduceMotion = enabled
        if (enabled) drawable.stop()
    }

    fun toggle() {
        if (drawable.isRunning) drawable.stop() else drawable.start()
    }

    override fun close() {
        drawable.setVisible(false, false)
    }
}
