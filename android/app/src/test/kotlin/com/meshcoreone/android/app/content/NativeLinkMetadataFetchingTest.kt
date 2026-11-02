// AndroidOnly: WP-218 verify the real metadata producer and BitmapFactory image replacement.
package com.meshcoreone.android.app.content

import com.meshcoreone.android.core.services.content.BoundedHttpFetching
import com.meshcoreone.android.core.services.content.HttpFetchAttempt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeLinkMetadataFetchingTest {
    @Test
    fun `native producer scrapes actual page metadata and decodes its hero image`() = runTest {
        val pageUrl = "https://8.8.8.8/article"
        val imageUrl = "https://8.8.8.8/hero.png"
        val png = NativeImageFixtures.png(24, 12)
        val page = """<meta property="og:title" content="Native producer">
            <meta property="og:image" content="/hero.png">"""
        val requests = mutableListOf<String>()
        val http = object : BoundedHttpFetching {
            override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt {
                requests += url
                val (mime, body) = when (url) {
                    pageUrl -> "text/html" to page.toByteArray()
                    imageUrl -> "image/png" to png
                    else -> error("Unexpected metadata request: $url")
                }
                return HttpFetchAttempt.Started(
                    statusCode = 200,
                    mimeType = mime,
                    expectedContentLength = body.size.toLong(),
                    closeResponse = {},
                    chunks = { receive -> if (body.isNotEmpty()) receive(body) },
                )
            }
        }
        val producer = NativeLinkMetadataFetching(
            http,
            BitmapPreviewImageProcessor(BitmapImageDecoder(Dispatchers.Unconfined)),
        )

        val metadata = assertNotNull(producer.fetchMetadata(pageUrl))

        assertEquals("Native producer", metadata.title)
        val hero = assertNotNull(metadata.imageData)
        assertTrue(hero.size > 2)
        assertEquals(0xFF, hero[0].toInt() and 0xFF)
        assertEquals(0xD8, hero[1].toInt() and 0xFF)
        assertEquals(listOf(pageUrl, imageUrl), requests)
    }
}
