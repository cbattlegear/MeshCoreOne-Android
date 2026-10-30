// AndroidOnly: WP-218 generate complete host-JVM image inputs for native graphics tests only.
package com.meshcoreone.android.app.content

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

internal object NativeImageFixtures {
    fun png(width: Int, height: Int): ByteArray = encode(width, height, "png", BufferedImage.TYPE_INT_ARGB)

    fun jpeg(width: Int, height: Int): ByteArray = encode(width, height, "jpeg", BufferedImage.TYPE_INT_RGB)

    private fun encode(width: Int, height: Int, format: String, imageType: Int): ByteArray {
        val image = BufferedImage(width, height, imageType)
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, format, output))
            output.toByteArray()
        }
    }
}
