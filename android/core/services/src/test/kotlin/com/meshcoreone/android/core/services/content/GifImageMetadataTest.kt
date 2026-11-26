// PortedFrom: MC1/Services/ImageURLDetector.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GifImageMetadataTest {
    private fun fixture(delay: Int = 1): ByteArray =
        "GIF89a".toByteArray(Charsets.US_ASCII) + byteArrayOf(
            1, 0, 1, 0, 0x80.toByte(), 0, 0,
            0, 0, 0, 0xff.toByte(), 0, 0,
            0x21, 0xf9.toByte(), 4, 4, delay.toByte(), 0, 0, 0,
            0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0,
            0x21, 0xf9.toByte(), 4, 4, 10, 0, 0, 0,
            0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x4c, 1, 0,
            0x3b,
        )

    @Test
    fun `GIF frame delays preserve the source minimum and total animation duration`() {
        val parsed = assertNotNull(GifImageMetadata.parse(fixture()))
        assertEquals(1, parsed.width)
        assertEquals(1, parsed.height)
        assertEquals(listOf(20L, 100L), parsed.frameDelaysMillis)
        assertEquals(120L, parsed.durationMillis)
        assertEquals(20L, GifImageMetadata.parse(fixture(0))?.frameDelaysMillis?.first())
    }

    @Test
    fun `every truncated or wrong-signature GIF metadata input fails without partial frame success`() {
        val bytes = fixture()
        for (length in bytes.indices) assertNull(GifImageMetadata.parse(bytes.copyOf(length)), "length=$length")
        assertNull(GifImageMetadata.parse(bytes.copyOf().also { it[0] = 0 }))
    }
}
