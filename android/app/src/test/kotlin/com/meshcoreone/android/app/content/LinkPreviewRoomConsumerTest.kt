// PortedFrom: MC1Tests/Services/LinkPreviewCacheTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/Services/DecodedPreviewCacheTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.content

import android.content.ComponentCallbacks2
import androidx.room.Room
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.LinkPreviewDataDTO
import com.meshcoreone.android.core.services.content.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class LinkPreviewRoomConsumerTest {
    private class Preferences : LinkPreviewPreferencesSource {
        override val preferences = MutableStateFlow(LinkPreviewPreferencesSnapshot(previewsEnabled = true))
        override suspend fun update(snapshot: LinkPreviewPreferencesSnapshot) { preferences.value = snapshot }
    }

    @Test
    fun `real Room persists a network preview and a fresh cache reads it without another fetch`() = runTest {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries().build()
        val store = RoomPersistenceStore(database, backgroundScope)
        var calls = 0
        val service = LinkMetadataFetching { calls++; LinkPreviewMetadata("Persisted native preview", null, null) }
        val policy = LinkPreviewPreferences(Preferences())
        val first = LinkPreviewCache(service, policy, backgroundScope)
        val second = LinkPreviewCache(service, policy, backgroundScope)
        try {
            val loaded = first.manualFetch("https://example.com/article", store)
            assertIs<LinkPreviewResult.Loaded>(loaded)
            assertEquals("Persisted native preview", store.fetchLinkPreview("https://example.com/article")?.title)
            first.close()
            val restored = second.preview("https://example.com/article", store, false)
            assertIs<LinkPreviewResult.Loaded>(restored)
            assertEquals(loaded.data, restored.data)
            assertEquals(1, calls)
        } finally {
            first.close()
            second.close()
            store.close()
            database.close()
        }
    }

    @Test
    fun `cancelled creator still warms real Room and does not retain a dead in-flight entry`() = runTest {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries().build()
        val store = RoomPersistenceStore(database, backgroundScope)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val service = LinkMetadataFetching {
            calls++
            started.complete(Unit)
            release.await()
            LinkPreviewMetadata("After cancellation", null, null)
        }
        val cache = LinkPreviewCache(service, LinkPreviewPreferences(Preferences()), backgroundScope)
        try {
            val creator = async { cache.manualFetch("https://example.com/article", store) }
            started.await()
            creator.cancel()
            val follower = async { cache.manualFetch("https://example.com/article", store) }
            release.complete(Unit)
            assertIs<LinkPreviewResult.Loaded>(follower.await())
            testScheduler.runCurrent()
            assertTrue(!cache.isFetching("https://example.com/article"))
            assertEquals("After cancellation", store.fetchLinkPreview("https://example.com/article")?.title)
            assertEquals(1, calls)
        } finally {
            cache.close()
            store.close()
            database.close()
        }
    }

    @Test
    fun `actual native memory callback clears decoded assets while the page reroute survives`() = runTest {
        val preview = LinkPreviewCache(
            LinkMetadataFetching { null }, LinkPreviewPreferences(Preferences()), backgroundScope,
        )
        val inline = InlineImageCache(object : BoundedHttpFetching {
            override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt =
                HttpFetchAttempt.Failed("fixture has no network")
        })
        val decoded = DecodedPreviewCache<String, String>()
        val entry = CachedDecodedPreview<String, String>(
            LinkPreviewDataDTO("https://example.com"), "hero", null, { 4L }, { 4L },
        )
        decoded.store(entry, "https://example.com")
        inline.markServesHtmlPage("https://example.com/landing.jpg")
        ContentMemoryPressure(RuntimeEnvironment.getApplication(), decoded, inline, preview).use {
            it.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            assertNull(decoded.decoded("https://example.com"))
            assertTrue(inline.servesHtmlPage("https://example.com/landing.jpg"))
        }
        preview.close()
    }
}
