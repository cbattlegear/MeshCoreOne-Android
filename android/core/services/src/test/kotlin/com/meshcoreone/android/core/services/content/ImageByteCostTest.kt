// PortedFrom: MC1/Utilities/ImageByteCost.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// No dedicated Swift unit test exists for this utility in the pinned source; this suite
// independently covers the arithmetic the type documents.
package com.meshcoreone.android.core.services.content

import kotlin.test.Test
import kotlin.test.assertEquals

class ImageByteCostTest {
    @Test
    fun `bytes for width and height uses 4 bytes per pixel by default`() {
        assertEquals(100 * 50 * 4, ImageByteCost.bytes(width = 100, height = 50))
    }

    @Test
    fun `bytes for width and height honors a custom bytesPerPixel`() {
        assertEquals(100 * 50 * 3, ImageByteCost.bytes(width = 100, height = 50, bytesPerPixel = 3))
    }

    @Test
    fun `bytes for zero or negative width or height is zero`() {
        assertEquals(0, ImageByteCost.bytes(width = 0, height = 50))
        assertEquals(0, ImageByteCost.bytes(width = 100, height = 0))
        assertEquals(0, ImageByteCost.bytes(width = -1, height = 50))
    }

    @Test
    fun `bytes for nullable width or height is zero when either is null`() {
        assertEquals(0, ImageByteCost.bytes(width = null, height = 50))
        assertEquals(0, ImageByteCost.bytes(width = 100, height = null))
        assertEquals(0, ImageByteCost.bytes(width = null, height = null))
    }

    @Test
    fun `bytes for nullable width and height matches the non-nullable overload when both present`() {
        assertEquals(ImageByteCost.bytes(width = 200, height = 80), ImageByteCost.bytes(width = 200 as Int?, height = 80 as Int?))
    }

    @Test
    fun `bytes for rowBytes and height multiplies the exact stride`() {
        assertEquals(400 * 120, ImageByteCost.bytes(rowBytes = 400, height = 120))
    }

    @Test
    fun `bytes for rowBytes and height is zero when either is non-positive`() {
        assertEquals(0, ImageByteCost.bytes(rowBytes = 0, height = 120))
        assertEquals(0, ImageByteCost.bytes(rowBytes = 400, height = 0))
    }
}
