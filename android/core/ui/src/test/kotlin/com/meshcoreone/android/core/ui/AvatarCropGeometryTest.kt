// PortedFrom: MC1Tests/Views/Components/AvatarCropGeometryTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.ui

import kotlin.test.*
import org.junit.Test

class AvatarCropGeometryTest : SourceCaseProof() {
    @OriginalCase("AvatarCropGeometryTests::fill size covers the crop square for a landscape image()")
    @Test fun landscape() = prove {
        assertEquals(CropSize(200.0, 100.0), AvatarCropGeometry(100.0, CropSize(200.0, 100.0)).baseDisplaySize)
    }
    @OriginalCase("AvatarCropGeometryTests::fill size covers the crop square for a portrait image()")
    @Test fun portrait() = prove {
        assertEquals(CropSize(100.0, 200.0), AvatarCropGeometry(100.0, CropSize(100.0, 200.0)).baseDisplaySize)
    }
    @OriginalCase("AvatarCropGeometryTests::zero image size falls back to the crop square()")
    @Test fun zeroImage() = prove {
        assertEquals(CropSize(100.0, 100.0), AvatarCropGeometry(100.0, CropSize.ZERO).baseDisplaySize)
    }
    @OriginalCase("AvatarCropGeometryTests::scale clamps to 1...4()")
    @Test fun scaleBounds() = prove {
        val geometry = AvatarCropGeometry(100.0, CropSize(100.0, 100.0))
        assertEquals(listOf(1.0, 1.0, 2.0, 4.0), listOf(0.5, 1.0, 2.0, 8.0).map(geometry::clampedScale))
    }
    @OriginalCase("AvatarCropGeometryTests::offset clamps so the crop square stays covered()")
    @Test fun covered() = prove {
        val geometry = AvatarCropGeometry(100.0, CropSize(100.0, 100.0), 2.0)
        assertEquals(CropOffset(50.0, -50.0), geometry.clampedOffset(CropOffset(1000.0, -1000.0)))
    }
    @OriginalCase("AvatarCropGeometryTests::live pinch reclamps offset to the live scale()")
    @Test fun livePinch() = prove {
        val geometry = AvatarCropGeometry(100.0, CropSize(100.0, 100.0), 4.0, CropOffset(150.0, 0.0))
        assertEquals(CropTransform(2.0, CropOffset(50.0, 0.0)), geometry.liveTransform(0.5, CropOffset.ZERO))
    }
    @OriginalCase("AvatarCropGeometryTests::draw rect at scale 1 offset 0 matches the centered fill()")
    @Test fun renderedRect() = prove {
        val rect = AvatarCropGeometry(100.0, CropSize(200.0, 100.0)).imageDrawRect()
        assertEquals(-256.0, rect.x, 0.001); assertEquals(0.0, rect.y, 0.001)
        assertEquals(1024.0, rect.width, 0.001); assertEquals(512.0, rect.height, 0.001)
    }
    @OriginalCase("AvatarCropGeometryTests::crop size shrinks on a short landscape canvas()")
    @Test fun shortWindow() = prove {
        assertEquals(216.0, AvatarCropGeometry.cropSize(CropSize(667.0, 280.0), false))
    }
    @OriginalCase("AvatarCropGeometryTests::crop size uses the regular cap on iPad()")
    @Test fun expandedWindow() = prove {
        assertEquals(480.0, AvatarCropGeometry.cropSize(CropSize(1024.0, 768.0), true))
    }

    @Test fun resizePanZoomAndInvalidGeometryDoNotCreateUncoveredOrNonfinitePixels() {
        val original = AvatarCropGeometry(100.0, CropSize(300.0, 100.0), 4.0, CropOffset(550.0, 150.0))
        assertEquals(4.0, original.resized(50.0).scale)
        assertEquals(CropOffset(275.0, 75.0), original.resized(50.0).offset)
        assertEquals(1.0, original.zoomBy(-100.0).scale)
        assertFailsWith<IllegalArgumentException> { original.clampedScale(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { AvatarCropGeometry(0.0, CropSize.ZERO) }
        assertEquals(1.0, AvatarCropGeometry.cropSize(CropSize(20.0, 20.0), false))
    }

    @Test fun viewportResizePreservesSelectedSourceRectAtEveryZoomAndPanIncludingWindowRotation() {
        for (image in listOf(CropSize(100.0, 100.0), CropSize(400.0, 100.0), CropSize(100.0, 400.0))) {
            for (scale in listOf(1.0, 2.0, 4.0)) {
                for (pan in listOf(CropOffset(75.0, -30.0), CropOffset(-45.0, 60.0),
                    CropOffset(10000.0, -10000.0))) {
                    val original = AvatarCropGeometry(300.0, image, scale).panBy(pan)
                    val expected = original.imageDrawRect()
                    var resized = original
                    for (size in listOf(150.0, 480.0, 128.0, 300.0)) {
                        resized = resized.resized(size)
                        val actual = resized.imageDrawRect()
                        assertEquals(expected.x, actual.x, 0.000001)
                        assertEquals(expected.y, actual.y, 0.000001)
                        assertEquals(expected.width, actual.width, 0.000001)
                        assertEquals(expected.height, actual.height, 0.000001)
                        assertEquals(original.scale, resized.scale)
                        assertEquals(resized.offset, resized.clampedOffset(resized.offset))
                    }
                    assertEquals(original.offset.x, resized.offset.x, 0.000001)
                    assertEquals(original.offset.y, resized.offset.y, 0.000001)
                }
            }
        }
        val selected = AvatarCropGeometry(300.0, CropSize(100.0, 100.0), 2.0, CropOffset(75.0, 0.0))
        assertEquals(CropOffset(37.5, 0.0), selected.resized(150.0).offset)
        assertFailsWith<IllegalArgumentException> { selected.resized(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { selected.resized(0.0) }
    }

    @Test fun nativePointerDeltasAccumulateBeforeRecompositionAndExternalResizeRemainsAuthoritative() {
        val initial = AvatarCropGeometry(100.0, CropSize(100.0, 100.0), 2.0)
        val gestures = CropGestureAccumulator(initial)
        val first = gestures.transform(initial, 1.5, CropOffset(10.0, 5.0))
        val second = gestures.transform(initial, 1.0, CropOffset(10.0, 5.0))
        assertEquals(3.0, first.scale)
        assertEquals(CropOffset(10.0, 5.0), first.offset)
        assertEquals(3.0, second.scale)
        assertEquals(CropOffset(20.0, 10.0), second.offset)
        val resized = second.resized(50.0)
        assertEquals(resized.panBy(CropOffset(1.0, 2.0)),
            gestures.transform(resized, 1.0, CropOffset(1.0, 2.0)))
    }
}
