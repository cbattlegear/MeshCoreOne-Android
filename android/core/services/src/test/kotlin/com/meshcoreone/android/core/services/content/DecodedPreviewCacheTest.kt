// PortedFrom: MC1Tests/Services/DecodedPreviewCacheTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Payload identity and DTO/FIFO behavior are tested independently of the platform image type.
// BitmapImageDecoderTest exercises the original decoded-pixel cost with a real Bitmap handle.
package com.meshcoreone.android.core.services.content

import com.meshcoreone.android.core.model.LinkPreviewDataDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class DecodedPreviewCacheTest {
    private val stringCost: (String) -> Long = { it.length.toLong() }

    private fun dto(url: String) = LinkPreviewDataDTO(
        url = url,
        title = "Example",
        imageData = Bytes(byteArrayOf(1, 2, 3)),
        iconData = Bytes(byteArrayOf(4, 5)),
        imageWidth = 100,
        imageHeight = 50,
        fetchedAt = Instant.EPOCH,
    )

    @Test
    fun `Round-trips a stored entry with DTO, hero, and icon`() {
        val cache = DecodedPreviewCache<Any, Any>()
        val hero = Any()
        val icon = Any()
        val original = dto("https://example.invalid/round-trip")
        cache.store(CachedDecodedPreview(original, hero, icon, { 4L }, { 4L }), original.url)

        val result = assertNotNull(cache.decoded(original.url))
        assertEquals(original.url, result.dto.url)
        assertEquals(original.title, result.dto.title)
        assertSame(hero, result.hero)
        assertSame(icon, result.icon)
    }

    @Test
    fun `Tolerates a nil hero or icon`() {
        val cache = DecodedPreviewCache<Any, Any>()
        val icon = Any()
        val original = dto("https://example.invalid/nil-hero")
        cache.store(CachedDecodedPreview(original, null, icon, { 4L }, { 4L }), original.url)

        val result = assertNotNull(cache.decoded(original.url))
        assertNull(result.hero)
        assertSame(icon, result.icon)
    }

    @Test
    fun `Re-storing the same key replaces the entry`() {
        val cache = DecodedPreviewCache<Any, Any>()
        val first = dto("https://example.invalid/replace").copy(title = "first")
        val second = first.copy(title = "second")
        val secondHero = Any()
        cache.store(CachedDecodedPreview(first, Any(), null, { 4L }, { 4L }), first.url)
        cache.store(CachedDecodedPreview(second, secondHero, null, { 4L }, { 4L }), second.url)

        val result = assertNotNull(cache.decoded(first.url))
        assertSame(secondHero, result.hero)
        assertEquals("second", result.dto.title)
    }

    @Test
    fun `FIFO eviction drops the oldest entry past the count cap`() {
        val cache = DecodedPreviewCache<Any, Any>()
        val urls = (0 until 60).map { "https://example.invalid/$it" }
        for (url in urls) {
            cache.store(CachedDecodedPreview(dto(url), Any(), null, { 4L }, { 4L }), url)
        }

        assertNull(cache.decoded(urls.first()))
        assertNotNull(cache.decoded(urls.last()))
    }

    @Test
    fun `Stores raw source bytes but strips them from the retained dto`() {
        val entry = CachedDecodedPreview(
            dto = dto("https://example.com"),
            hero = "hero-pixels",
            icon = "icon-pixels",
            heroCost = stringCost,
            iconCost = stringCost,
        )

        assertNull(entry.dto.imageData)
        assertNull(entry.dto.iconData)
        assertEquals("https://example.com", entry.dto.url)
        assertEquals("Example", entry.dto.title)
        assertEquals(100L, entry.dto.imageWidth)
        assertEquals(50L, entry.dto.imageHeight)
        assertEquals("hero-pixels".length.toLong() + "icon-pixels".length.toLong(), entry.cost)
    }

    @Test
    fun `A nil hero or icon contributes zero cost`() {
        val entry = CachedDecodedPreview<String, String>(
            dto = dto("https://example.com"),
            hero = null,
            icon = null,
            heroCost = stringCost,
            iconCost = stringCost,
        )
        assertEquals(0L, entry.cost)
    }

    @Test
    fun `Round-trips a stored entry by URL`() {
        val cache = DecodedPreviewCache<String, String>(maxEntryCount = 50, maxTotalCostBytes = 1000)
        val entry = CachedDecodedPreview<String, String>(dto("https://a.example"), "hero", null, stringCost, stringCost)

        cache.store(entry, "https://a.example")

        assertEquals(entry, cache.decoded("https://a.example"))
    }

    @Test
    fun `Returns null for a URL never stored`() {
        val cache = DecodedPreviewCache<String, String>(maxEntryCount = 50, maxTotalCostBytes = 1000)
        assertNull(cache.decoded("https://missing.example"))
    }

    @Test
    fun `Evicts the oldest entry once the entry-count bound is exceeded`() {
        val cache = DecodedPreviewCache<String, String>(maxEntryCount = 2, maxTotalCostBytes = 1000)
        cache.store(CachedDecodedPreview<String, String>(dto("a"), "1", null, stringCost, stringCost), "a")
        cache.store(CachedDecodedPreview<String, String>(dto("b"), "2", null, stringCost, stringCost), "b")
        cache.store(CachedDecodedPreview<String, String>(dto("c"), "3", null, stringCost, stringCost), "c")

        assertNull(cache.decoded("a"))
        assertEquals("2", cache.decoded("b")?.hero)
        assertEquals("3", cache.decoded("c")?.hero)
    }

    @Test
    fun `Keeps at least the just-inserted entry even when it alone exceeds the cost budget`() {
        val cache = DecodedPreviewCache<String, String>(maxEntryCount = 50, maxTotalCostBytes = 3)
        val huge = CachedDecodedPreview<String, String>(dto("huge"), "0123456789", null, stringCost, stringCost)

        cache.store(huge, "huge")

        assertEquals(huge, cache.decoded("huge"))
    }

    @Test
    fun `clear empties the cache`() {
        val cache = DecodedPreviewCache<String, String>(maxEntryCount = 50, maxTotalCostBytes = 1000)
        cache.store(CachedDecodedPreview<String, String>(dto("a"), "1", null, stringCost, stringCost), "a")
        assertNotNull(cache.decoded("a"))

        cache.clear()

        assertNull(cache.decoded("a"))
    }

    @Test
    fun `Default bounds match the Swift original, 50 entries and 50MB`() {
        assertEquals(50, DecodedPreviewCache.DEFAULT_MAX_ENTRY_COUNT)
        assertEquals(50L * 1024 * 1024, DecodedPreviewCache.DEFAULT_MAX_TOTAL_COST_BYTES)
    }
}
