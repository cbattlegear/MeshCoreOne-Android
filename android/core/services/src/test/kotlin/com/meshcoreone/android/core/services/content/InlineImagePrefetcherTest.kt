// PortedFrom: MC1Tests/Services/InlineImagePrefetcherTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Each test below is tagged with its originating case name. The source's `StubDataStore` is a
// full `PersistenceStoreProtocol` stub (dozens of unrelated no-op methods); this port's
// [LinkPreviewPersisting] role is already narrow, so [FakeLinkPreviewPersisting] only needs the
// two methods that role actually declares.
package com.meshcoreone.android.core.services.content

import com.meshcoreone.android.core.contracts.domain.LinkPreviewPersisting
import com.meshcoreone.android.core.model.LinkPreviewDataDTO
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InlineImagePrefetcherTest {
    private class FakeImageProber : InlineImageDimensionProbing {
        val probedUrls = mutableListOf<String>()
        override suspend fun probeImageDimensions(url: String): Pair<Int, Int>? {
            probedUrls.add(url)
            return null
        }
    }

    private class FakeLinkPreviewCaching : LinkPreviewCaching {
        val fetchedUrls = mutableListOf<String>()
        val fetchedChannelFlags = mutableListOf<Boolean>()
        var resultToReturn: LinkPreviewResult = LinkPreviewResult.NoPreviewAvailable

        override suspend fun preview(
            url: String,
            dataStore: LinkPreviewPersisting,
            isChannelMessage: Boolean,
        ): LinkPreviewResult {
            fetchedUrls.add(url)
            fetchedChannelFlags.add(isChannelMessage)
            return resultToReturn
        }

        override suspend fun manualFetch(url: String, dataStore: LinkPreviewPersisting): LinkPreviewResult =
            LinkPreviewResult.NoPreviewAvailable

        override suspend fun isFetching(url: String): Boolean = false

        override suspend fun cachedPreview(url: String): LinkPreviewDataDTO? = null
    }

    private class FakeLinkPreviewPersisting : LinkPreviewPersisting {
        override suspend fun fetchLinkPreview(url: String): LinkPreviewDataDTO? = null
        override suspend fun saveLinkPreview(dto: LinkPreviewDataDTO) = Unit
    }

    private fun tempDimensionsFile(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "InlineImagePrefetcherTest-${UUID.randomUUID()}")
        dir.mkdirs()
        return File(dir, "dimensions.json")
    }

    private fun prefetcher(
        imageCache: FakeImageProber = FakeImageProber(),
        linkCache: FakeLinkPreviewCaching = FakeLinkPreviewCaching(),
        store: InlineImageDimensionsStore = InlineImageDimensionsStore(tempDimensionsFile()),
    ): InlineImagePrefetcher = InlineImagePrefetcher(imageCache, linkCache, store, FakeLinkPreviewPersisting())

    // PortedFrom-case: "Text with no URLs returns immediately without probes or previews"
    @Test
    fun `text with no URLs returns immediately without probes or previews`() = runTest {
        val imageCache = FakeImageProber()
        val linkCache = FakeLinkPreviewCaching()
        val prefetcher = prefetcher(imageCache, linkCache)

        prefetcher.prefetch("hello world, no links here", isChannelMessage = false, allowImageProbes = true)

        assertTrue(imageCache.probedUrls.isEmpty())
        assertTrue(linkCache.fetchedUrls.isEmpty())
    }

    // PortedFrom-case: "Direct image suffix invokes the dimension probe path"
    @Test
    fun `direct image suffix invokes the dimension probe path`() = runTest {
        val imageCache = FakeImageProber()
        val linkCache = FakeLinkPreviewCaching()
        val prefetcher = prefetcher(imageCache, linkCache)

        prefetcher.prefetch("look at https://example.com/cat.png", isChannelMessage = false, allowImageProbes = true)

        assertEquals(listOf("https://example.com/cat.png"), imageCache.probedUrls)
        assertTrue(linkCache.fetchedUrls.isEmpty())
    }

    // PortedFrom-case: "Image probes are skipped when disallowed but card URLs still resolve"
    // Receive-time leak regression: with allowImageProbes false, a direct image URL fires no
    // dimension probe (no third-party image request on receive), while a card URL still reaches
    // the link preview cache, which self-gates.
    @Test
    fun `image probes are skipped when disallowed but card URLs still resolve`() = runTest {
        val imageCache = FakeImageProber()
        val linkCache = FakeLinkPreviewCaching()
        val prefetcher = prefetcher(imageCache, linkCache)

        prefetcher.prefetch(
            "image https://example.com/cat.png and article https://example.com/article",
            isChannelMessage = false,
            allowImageProbes = false,
        )

        assertTrue(imageCache.probedUrls.isEmpty())
        assertEquals(listOf("https://example.com/article"), linkCache.fetchedUrls)
    }

    // PortedFrom-case: "Multiple URLs fan out across both classifier paths"
    @Test
    fun `multiple URLs fan out across both classifier paths`() = runTest {
        val imageCache = FakeImageProber()
        val linkCache = FakeLinkPreviewCaching()
        val prefetcher = prefetcher(imageCache, linkCache)

        prefetcher.prefetch(
            "image https://example.com/cat.png and article https://example.com/article",
            isChannelMessage = false,
            allowImageProbes = true,
        )

        assertEquals(listOf("https://example.com/cat.png"), imageCache.probedUrls)
        assertEquals(listOf("https://example.com/article"), linkCache.fetchedUrls)
    }

    // PortedFrom-case: "Direct image probe is skipped when dimensions are already cached"
    @Test
    fun `direct image probe is skipped when dimensions are already cached`() = runTest {
        val imageCache = FakeImageProber()
        val linkCache = FakeLinkPreviewCaching()
        val store = InlineImageDimensionsStore(tempDimensionsFile())
        store.save("https://example.com/cat.png", 200.0, 100.0)
        val prefetcher = prefetcher(imageCache, linkCache, store)

        prefetcher.prefetch("look at https://example.com/cat.png", isChannelMessage = false, allowImageProbes = true)

        assertTrue(imageCache.probedUrls.isEmpty())
    }

    // PortedFrom-case: "Mixed direct image and link preview URLs both invoke their paths"
    @Test
    fun `mixed direct image and link preview URLs both invoke their paths`() = runTest {
        val imageCache = FakeImageProber()
        val linkCache = FakeLinkPreviewCaching()
        val prefetcher = prefetcher(imageCache, linkCache)

        prefetcher.prefetch(
            "see https://example.com/cat.jpg then read https://news.example.com/post",
            isChannelMessage = true,
            allowImageProbes = true,
        )

        assertEquals(listOf("https://example.com/cat.jpg"), imageCache.probedUrls)
        assertEquals(listOf("https://news.example.com/post"), linkCache.fetchedUrls)
        assertEquals(listOf(true), linkCache.fetchedChannelFlags)
    }

    // PortedFrom-case: "Giphy hosting URL routes to probe path with resolved direct image URL"
    @Test
    fun `Giphy hosting URL routes to probe path with resolved direct image URL`() = runTest {
        val imageCache = FakeImageProber()
        val linkCache = FakeLinkPreviewCaching()
        val prefetcher = prefetcher(imageCache, linkCache)

        val hostingUrl = "https://giphy.com/gifs/abc123"
        val expectedProbeUrl = ImageUrlClassifier.directImageUrl(java.net.URI(hostingUrl)).toString()

        prefetcher.prefetch("look at $hostingUrl", isChannelMessage = false, allowImageProbes = true)

        assertEquals(listOf(expectedProbeUrl), imageCache.probedUrls)
        assertTrue(linkCache.fetchedUrls.isEmpty())
    }

    // WP-218-disclosed addition: the source's non-throwing [LinkPreviewResult.Loaded] path
    // persists the resolved hero aspect to the attached dimensions store under the page URL,
    // not the (absent, for a card) direct-image URL. Not independently assertable in the source
    // without a full `.loaded` stub payload; added here since it is real production behavior
    // this port's `preview`-result branch must exercise.
    @Test
    fun `loaded preview with image dimensions saves aspect under the page url`() = runTest {
        val imageCache = FakeImageProber()
        val linkCache = FakeLinkPreviewCaching().apply {
            resultToReturn = LinkPreviewResult.Loaded(
                LinkPreviewDataDTO(url = "https://news.example.com/post", imageWidth = 400, imageHeight = 200),
            )
        }
        val store = InlineImageDimensionsStore(tempDimensionsFile())
        val prefetcher = prefetcher(imageCache, linkCache, store)

        prefetcher.prefetch("read https://news.example.com/post", isChannelMessage = false, allowImageProbes = true)

        assertEquals(2.0, store.aspect("https://news.example.com/post"))
    }
}
