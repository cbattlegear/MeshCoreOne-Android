// AndroidOnly: WP-311 QR share output round-trips through the standards decoder used by scanning.
package com.meshcoreone.android.feature.nodes.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QrUiTest {
    @Test
    fun `contact URI QR round trips without changing identity bytes`() {
        val uri = "meshcore://contact/add?name=Alice+Repeater&public_key=${"A5".repeat(32)}&type=2"
        val matrix = contactQrMatrix(uri)
        val scale = 8
        val width = matrix.width * scale
        val pixels = IntArray(width * width) { 0xFFFFFFFF.toInt() }
        for (row in 0 until matrix.height) {
            for (column in 0 until matrix.width) {
                if (matrix[column, row]) {
                    for (y in row * scale until (row + 1) * scale) {
                        for (x in column * scale until (column + 1) * scale) pixels[y * width + x] = 0xFF000000.toInt()
                    }
                }
            }
        }

        val decoded = QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, width, pixels))),
        )

        assertEquals(uri, decoded.text)
    }

    @Test
    fun `QR rendering rejects blank values`() {
        assertFailsWith<IllegalArgumentException> { contactQrMatrix("  ") }
    }
}
