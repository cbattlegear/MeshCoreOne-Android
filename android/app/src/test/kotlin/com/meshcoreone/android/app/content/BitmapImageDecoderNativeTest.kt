// AndroidOnly: WP-218 exercise BitmapFactory with Robolectric native graphics, not legacy shadows.
package com.meshcoreone.android.app.content

import com.meshcoreone.android.core.services.content.BoundedHttpFetching
import com.meshcoreone.android.core.services.content.CachedDecodedImage
import com.meshcoreone.android.core.services.content.HttpFetchAttempt
import com.meshcoreone.android.core.services.content.ImageDecodeOutcome
import com.meshcoreone.android.core.services.content.InlineImageCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitmapImageDecoderNativeTest {
    @Test
    fun `native BitmapFactory decodes complete PNG and JPEG inputs and supplies actual cache cost`() = runTest {
        val decoder = BitmapImageDecoder(Dispatchers.Unconfined)
        for ((bytes, expectedWidth, expectedHeight) in listOf(
            Triple(NativeImageFixtures.png(64, 32), 64, 32),
            Triple(NativeImageFixtures.jpeg(80, 40), 80, 40),
        )) {
            val handle = assertIs<BitmapDecodedImageHandle>(
                assertIs<ImageDecodeOutcome.Decoded>(decoder.decode(bytes)).handle,
            )
            try {
                assertEquals(expectedWidth, handle.width)
                assertEquals(expectedHeight, handle.height)
                assertEquals(handle.bitmap.rowBytes * handle.height, handle.costBytes)
            } finally {
                handle.bitmap.recycle()
            }
        }

        val encoded = NativeImageFixtures.png(100, 50)
        val handle = assertIs<BitmapDecodedImageHandle>(
            assertIs<ImageDecodeOutcome.Decoded>(decoder.decode(encoded)).handle,
        )
        val fetching = object : BoundedHttpFetching {
            override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?) =
                HttpFetchAttempt.Failed("unused")
        }
        val cache = InlineImageCache(fetching)
        val entry = CachedDecodedImage(handle, isGif = false, data = encoded)
        val url = "https://example.com/bitmap-cost.png"
        try {
            cache.storeDecoded(entry, url)
            assertEquals(handle.costBytes.toLong() + encoded.size, entry.cost)
            assertEquals(entry.cost, cache.decoded(url)?.cost)
        } finally {
            cache.clearDecodedMirror()
            handle.bitmap.recycle()
        }
    }
}
