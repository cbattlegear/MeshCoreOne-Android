// PortedFrom: MC1/Views/Components/AvatarCropGeometry.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.ui

import kotlin.math.max
import kotlin.math.min

data class CropSize(val width: Double, val height: Double) {
    init { require(width.isFinite() && height.isFinite()) { "Crop dimensions must be finite" } }
    companion object { val ZERO = CropSize(0.0, 0.0) }
}

data class CropOffset(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite()) { "Crop offsets must be finite" } }
    companion object { val ZERO = CropOffset(0.0, 0.0) }
}

data class CropRect(val x: Double, val y: Double, val width: Double, val height: Double)
data class CropTransform(val scale: Double, val offset: CropOffset)

data class AvatarCropGeometry(
    val cropSize: Double,
    val imageSize: CropSize,
    val scale: Double = MIN_SCALE,
    val offset: CropOffset = CropOffset.ZERO,
) {
    init {
        require(cropSize.isFinite() && cropSize > 0) { "Crop size must be finite and positive" }
        require(scale.isFinite() && scale in MIN_SCALE..MAX_SCALE) { "Crop scale must be in 1...4" }
    }

    val baseDisplaySize: CropSize
        get() {
            if (imageSize.width <= 0 || imageSize.height <= 0) return CropSize(cropSize, cropSize)
            val fill = max(cropSize / imageSize.width, cropSize / imageSize.height)
            return CropSize(imageSize.width * fill, imageSize.height * fill)
        }

    fun clampedScale(proposed: Double): Double {
        require(proposed.isFinite()) { "Proposed scale must be finite" }
        return proposed.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    fun clampedOffset(proposed: CropOffset, atScale: Double = scale): CropOffset {
        require(atScale.isFinite() && atScale in MIN_SCALE..MAX_SCALE)
        val base = baseDisplaySize
        val maxX = max(0.0, (base.width * atScale - cropSize) / 2)
        val maxY = max(0.0, (base.height * atScale - cropSize) / 2)
        return CropOffset(proposed.x.coerceIn(-maxX, maxX), proposed.y.coerceIn(-maxY, maxY))
    }

    fun liveTransform(pinchDelta: Double, dragTranslation: CropOffset): CropTransform {
        val liveScale = clampedScale(scale * pinchDelta)
        return CropTransform(liveScale, clampedOffset(
            CropOffset(offset.x + dragTranslation.x, offset.y + dragTranslation.y), liveScale,
        ))
    }

    fun imageDrawRect(outputSide: Double = OUTPUT_SIDE): CropRect {
        require(outputSide.isFinite() && outputSide > 0)
        val base = baseDisplaySize
        val renderScale = outputSide / cropSize
        val width = base.width * scale
        val height = base.height * scale
        return CropRect(
            ((cropSize - width) / 2 + offset.x) * renderScale,
            ((cropSize - height) / 2 + offset.y) * renderScale,
            width * renderScale, height * renderScale,
        )
    }

    fun zoomBy(delta: Double): AvatarCropGeometry {
        val newScale = clampedScale(scale + delta)
        return copy(scale = newScale, offset = clampedOffset(offset, newScale))
    }

    fun panBy(delta: CropOffset): AvatarCropGeometry =
        copy(offset = clampedOffset(CropOffset(offset.x + delta.x, offset.y + delta.y)))

    fun resized(size: Double): AvatarCropGeometry {
        val resized = copy(cropSize = size)
        val ratio = size / cropSize
        return resized.copy(offset = resized.clampedOffset(CropOffset(offset.x * ratio, offset.y * ratio)))
    }

    companion object {
        const val MIN_SCALE = 1.0
        const val MAX_SCALE = 4.0
        const val COMPACT_MAX_CROP_SIZE = 300.0
        const val REGULAR_MAX_CROP_SIZE = 480.0
        const val CROP_MARGIN = 32.0
        const val OUTPUT_SIDE = 512.0
        const val DECODE_MAX_PIXEL_SIZE = 1024
        const val ZOOM_STEP = 0.25
        const val PAN_STEP_FRACTION = 0.125

        fun cropSize(available: CropSize, regularWidth: Boolean): Double =
            max(min(if (regularWidth) REGULAR_MAX_CROP_SIZE else COMPACT_MAX_CROP_SIZE,
                min(available.width, available.height) - CROP_MARGIN * 2), 1.0)
    }
}
