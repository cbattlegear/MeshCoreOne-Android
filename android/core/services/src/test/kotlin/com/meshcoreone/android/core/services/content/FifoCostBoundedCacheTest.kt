// PortedFrom: MC1Tests/Services/DecodedPreviewCacheTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Exercises the generic eviction policy extracted into FifoCostBoundedCache using plain String
// payloads with an explicit cost function, in place of the Swift test's UIImage-backed
// CachedDecodedPreview - the behavior under test is the eviction policy, not image decoding.
package com.meshcoreone.android.core.services.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FifoCostBoundedCacheTest {
    private fun stringCost(value: String): Long = value.length.toLong()

    @Test
    fun `Returns null for an unseen key`() {
        val cache = FifoCostBoundedCache<String, String>(maxEntryCount = 50, maxTotalCostBytes = 1000, costOf = ::stringCost)
        assertNull(cache.get("missing"))
    }

    @Test
    fun `Round-trips a stored entry`() {
        val cache = FifoCostBoundedCache<String, String>(maxEntryCount = 50, maxTotalCostBytes = 1000, costOf = ::stringCost)
        cache.put("a", "hero")
        assertEquals("hero", cache.get("a"))
    }

    @Test
    fun `Re-storing the same key replaces the entry and its cost`() {
        val cache = FifoCostBoundedCache<String, String>(maxEntryCount = 50, maxTotalCostBytes = 1000, costOf = ::stringCost)
        cache.put("a", "first")
        cache.put("a", "second-value")

        assertEquals("second-value", cache.get("a"))
        assertEquals(1, cache.size)
        assertEquals("second-value".length.toLong(), cache.totalCost)
    }

    @Test
    fun `Evicts the oldest entry once the entry-count bound is exceeded`() {
        val cache = FifoCostBoundedCache<String, String>(maxEntryCount = 2, maxTotalCostBytes = 1000, costOf = ::stringCost)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")

        assertNull(cache.get("a"))
        assertEquals("2", cache.get("b"))
        assertEquals("3", cache.get("c"))
        assertEquals(2, cache.size)
    }

    @Test
    fun `Evicts oldest entries once the total-cost bound is exceeded`() {
        val cache = FifoCostBoundedCache<String, String>(maxEntryCount = 50, maxTotalCostBytes = 5, costOf = ::stringCost)
        cache.put("a", "ab") // cost 2, total 2
        cache.put("b", "cd") // cost 2, total 4
        cache.put("c", "ef") // cost 2, total 6 > 5 -> evict "a"

        assertNull(cache.get("a"))
        assertEquals("cd", cache.get("b"))
        assertEquals("ef", cache.get("c"))
        assertEquals(4, cache.totalCost)
    }

    @Test
    fun `Keeps at least the just-inserted entry even when it alone exceeds the cost budget`() {
        val cache = FifoCostBoundedCache<String, String>(maxEntryCount = 50, maxTotalCostBytes = 3, costOf = ::stringCost)
        cache.put("huge", "0123456789")

        assertEquals("0123456789", cache.get("huge"))
        assertEquals(1, cache.size)
    }

    @Test
    fun `clear empties the cache`() {
        val cache = FifoCostBoundedCache<String, String>(maxEntryCount = 50, maxTotalCostBytes = 1000, costOf = ::stringCost)
        cache.put("a", "1")
        cache.put("b", "2")

        cache.clear()

        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals(0, cache.size)
        assertEquals(0L, cache.totalCost)
    }

    @Test
    fun `Replacing a key moves it to the back of the insertion order`() {
        val cache = FifoCostBoundedCache<String, String>(maxEntryCount = 2, maxTotalCostBytes = 1000, costOf = ::stringCost)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("a", "1-again") // "a" re-inserted; "b" is now oldest
        cache.put("c", "3") // exceeds count bound -> evicts "b", not "a"

        assertEquals("1-again", cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("3", cache.get("c"))
    }
}
