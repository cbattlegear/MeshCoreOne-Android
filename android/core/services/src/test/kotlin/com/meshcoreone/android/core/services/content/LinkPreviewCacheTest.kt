// PortedFrom: MC1Tests/Services/LinkPreviewCacheTests.swift@db14559b39d32322b06477c6ae676112f583db50
//
// The Swift original synchronizes its coalescing test by spinning on `isFetching(url)` with
// `Task.yield()` against a real 200ms delay. This port replaces that spin/real-delay pair with a
// `CompletableDeferred` handshake (the fake fetcher signals once it has actually been invoked,
// and waits to be released) so the test is deterministic without depending on wall-clock timing,
// while still exercising the exact same coalescing/in-flight-deduplication behavior.
package com.meshcoreone.android.core.services.content

import com.meshcoreone.android.core.contracts.domain.LinkPreviewPersisting
import com.meshcoreone.android.core.model.LinkPreviewDataDTO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkPreviewCacheTest {

    /** Mirrors the Swift `MockPreviewDataStore` actor's relevant surface (fetch/save only). */
    private class FakePersisting : LinkPreviewPersisting {
        private val stored = mutableMapOf<String, LinkPreviewDataDTO>()
        var fetchCallCount = 0
            private set
        var saveCallCount = 0
            private set
        var shouldThrowOnFetch = false

        fun seed(dto: LinkPreviewDataDTO) {
            stored[dto.url] = dto
        }

        override suspend fun fetchLinkPreview(url: String): LinkPreviewDataDTO? {
            fetchCallCount++
            if (shouldThrowOnFetch) throw IllegalStateException("fetch failed")
            return stored[url]
        }

        override suspend fun saveLinkPreview(dto: LinkPreviewDataDTO) {
            saveCallCount++
            stored[dto.url] = dto
        }
    }

    /** Mirrors the Swift `FakeMetadataFetcher` actor: counts calls, returns a fixed title. */
    private class FakeMetadataFetcher(private val title: String?) : LinkMetadataFetching {
        var callCount = 0
            private set
        val invoked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun fetchMetadata(url: String): LinkPreviewMetadata? {
            callCount++
            invoked.complete(Unit)
            release.await()
            return title?.let { LinkPreviewMetadata(title = it, imageData = null, iconData = null) }
        }
    }

    private fun previewsEnabledCache(
        service: LinkMetadataFetching = FakeMetadataFetcher(title = null),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ): LinkPreviewCache {
        val source = InMemoryLinkPreviewPreferencesSource(
            LinkPreviewPreferencesSnapshot(previewsEnabled = true, autoResolveDM = true, autoResolveChannels = true),
        )
        return LinkPreviewCache(service, LinkPreviewPreferences(source), scope)
    }

    // MARK: - Memory Cache Tests

    @Test
    fun `Returns cached preview from memory on subsequent requests`() = runTest {
        val dataStore = FakePersisting()
        val url = "https://example.com/article"
        dataStore.seed(LinkPreviewDataDTO(url = url, title = "Test Article"))
        val cache = previewsEnabledCache()

        val result1 = cache.preview(url, dataStore, isChannelMessage = false)
        assertTrue(result1 is LinkPreviewResult.Loaded)
        assertEquals("Test Article", (result1 as LinkPreviewResult.Loaded).data.title)
        assertEquals(1, dataStore.fetchCallCount)

        // Second request should hit memory cache (no additional fetch).
        val result2 = cache.preview(url, dataStore, isChannelMessage = false)
        assertTrue(result2 is LinkPreviewResult.Loaded)
        assertEquals("Test Article", (result2 as LinkPreviewResult.Loaded).data.title)
        assertEquals(1, dataStore.fetchCallCount)
    }

    @Test
    fun `Memory cache returns correct preview data`() = runTest {
        val dataStore = FakePersisting()
        val url = "https://example.com/test"
        dataStore.seed(
            LinkPreviewDataDTO(
                url = url,
                title = "Memory Cache Test",
                imageData = com.meshcoreone.android.core.protocol.bytes.Bytes(byteArrayOf(1, 2, 3)),
                iconData = com.meshcoreone.android.core.protocol.bytes.Bytes(byteArrayOf(4, 5, 6)),
            ),
        )
        val cache = previewsEnabledCache()

        cache.preview(url, dataStore, isChannelMessage = false)

        val cached = cache.cachedPreview(url)
        assertEquals("Memory Cache Test", cached?.title)
        assertEquals(listOf<Byte>(1, 2, 3), cached?.imageData?.toByteArray()?.toList())
        assertEquals(listOf<Byte>(4, 5, 6), cached?.iconData?.toByteArray()?.toList())
    }

    // MARK: - In-Flight Deduplication Tests

    @Test
    fun `isFetching returns false when no fetch is in progress`() = runTest {
        val cache = previewsEnabledCache()
        assertFalse(cache.isFetching("https://example.com/inflight"))
    }

    @Test
    fun `Concurrent fetches for the same URL coalesce, every caller receives the loaded result`() = runTest {
        val fetcher = FakeMetadataFetcher(title = "Coalesced")
        val dataStore = FakePersisting()
        val url = "https://example.com/coalesce"
        // Dedicated scope backs the shared in-flight deferred, independent of this test's own
        // coroutine (matching LinkPreviewCache's real default scope shape, not the test
        // dispatcher's virtual-time scheduling).
        val cache = previewsEnabledCache(service = fetcher, scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))

        // manualFetch bypasses the auto-resolve preference gate and routes through the same
        // coalescing network-fetch path as preview().
        val first = async(Dispatchers.Default) { cache.manualFetch(url, dataStore) }
        fetcher.invoked.await() // Deterministically wait until the fetch is actually in-flight.
        assertTrue(cache.isFetching(url))

        // A follower arriving mid-flight must receive the resolved result, not a stranded
        // placeholder.
        val second = async(Dispatchers.Default) { cache.manualFetch(url, dataStore) }
        fetcher.release.complete(Unit)

        val firstResult = first.await()
        val secondResult = second.await()

        assertTrue(firstResult is LinkPreviewResult.Loaded)
        assertEquals("Coalesced", (firstResult as LinkPreviewResult.Loaded).data.title)
        assertTrue(secondResult is LinkPreviewResult.Loaded)
        assertEquals("Coalesced", (secondResult as LinkPreviewResult.Loaded).data.title)

        // Coalescing means the underlying network fetch ran exactly once.
        assertEquals(1, fetcher.callCount)
    }

    // MARK: - Database Integration Tests

    @Test
    fun `Preview is persisted to database after network fetch`() = runTest {
        val dataStore = FakePersisting()
        val url = "https://example.com/persist"
        dataStore.seed(LinkPreviewDataDTO(url = url, title = "Persisted Preview"))
        val cache = previewsEnabledCache()

        val result = cache.preview(url, dataStore, isChannelMessage = false)

        assertTrue(result is LinkPreviewResult.Loaded)
        assertEquals("Persisted Preview", (result as LinkPreviewResult.Loaded).data.title)
    }

    @Test
    fun `Database errors are handled gracefully`() = runTest {
        val dataStore = FakePersisting()
        dataStore.shouldThrowOnFetch = true
        val url = "https://example.com/error"
        // Default (previews disabled) cache, matching the Swift original's default-constructed
        // cache: a database read failure must not crash, and must fall through to a typed
        // disabled/no-preview outcome, never a fabricated success.
        val source = InMemoryLinkPreviewPreferencesSource()
        val cache = LinkPreviewCache(FakeMetadataFetcher(title = null), LinkPreviewPreferences(source))

        val result = cache.preview(url, dataStore, isChannelMessage = false)

        assertTrue(result is LinkPreviewResult.Disabled || result is LinkPreviewResult.NoPreviewAvailable)
        assertNull(cache.cachedPreview(url))
    }

    @Test
    fun `persisted misses are memoized but a preview write invalidates the miss`() = runTest {
        val dataStore = FakePersisting()
        val url = "https://example.com/after-miss"
        val service = LinkMetadataFetching {
            LinkPreviewMetadata("Written preview", null, null)
        }
        val cache = previewsEnabledCache(service)
        try {
            assertNull(cache.cachedPreview(url, dataStore))
            assertNull(cache.cachedPreview(url, dataStore))
            assertEquals(1, dataStore.fetchCallCount)

            assertTrue(cache.manualFetch(url, dataStore) is LinkPreviewResult.Loaded)
            cache.clearMemory()

            assertEquals("Written preview", cache.cachedPreview(url, dataStore)?.title)
            assertEquals(2, dataStore.fetchCallCount)
        } finally {
            cache.close()
        }
    }

    @Test
    fun `failed persisted reads are not memoized as misses`() = runTest {
        val dataStore = FakePersisting().apply { shouldThrowOnFetch = true }
        val cache = previewsEnabledCache()
        try {
            assertNull(cache.cachedPreview("https://example.com/read-failure", dataStore))
            assertNull(cache.cachedPreview("https://example.com/read-failure", dataStore))
            assertEquals(2, dataStore.fetchCallCount)
        } finally {
            cache.close()
        }
    }

    @Test
    fun `cached previews rehydrate while automatic network fetch remains gated for both conversation types`() = runTest {
        var fetchCalls = 0
        val service = LinkMetadataFetching {
            fetchCalls++
            LinkPreviewMetadata("Unexpected network result", null, null)
        }

        for (isChannel in listOf(false, true)) {
            val source = InMemoryLinkPreviewPreferencesSource(
                LinkPreviewPreferencesSnapshot(
                    previewsEnabled = true,
                    autoResolveDM = false,
                    autoResolveChannels = false,
                ),
            )
            val cache = LinkPreviewCache(service, LinkPreviewPreferences(source))
            val dataStore = FakePersisting().apply {
                seed(LinkPreviewDataDTO(url = "https://example.com/cached-$isChannel", title = "Cached"))
            }
            try {
                val cached = cache.preview("https://example.com/cached-$isChannel", dataStore, isChannel)
                assertTrue(cached is LinkPreviewResult.Loaded)
                assertEquals("Cached", (cached as LinkPreviewResult.Loaded).data.title)

                val uncached = cache.preview("https://example.com/new-$isChannel", dataStore, isChannel)
                assertEquals(LinkPreviewResult.Disabled, uncached)
                assertEquals(0, fetchCalls, "automatic network work must stay disabled")
            } finally {
                cache.close()
            }
        }
    }
}
