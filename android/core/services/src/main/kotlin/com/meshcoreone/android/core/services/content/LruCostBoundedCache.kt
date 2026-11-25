// PortedFrom: MC1/Services/LinkPreviewCache.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Swift's `NSCache`-backed memory tier is explicitly documented upstream as "not a strict LRU"
// (Apple: the OS may evict any entry at any time), but its *intended* and observed shape - most
// recently touched entries survive memory pressure longest - is a genuine least-recently-used
// policy, not the insertion-order FIFO already ported for `DecodedPreviewCache`/`InlineImageCache`
// (see `FifoCostBoundedCache`'s own doc comment flagging this exact distinction). This type is
// that separate, LRU-shaped primitive: a `LinkedHashMap(accessOrder = true)`-backed cache that
// promotes an entry to most-recently-used on both read and write, evicting the least-recently-used
// entry first once either bound is exceeded.
package com.meshcoreone.android.core.services.content

/**
 * An LRU cache bounded by both entry count and total cost, mirroring the *intended* eviction
 * shape of `LinkPreviewCache`'s Swift `NSCache` memory tier (an `NSCache` has no formal LRU
 * contract, but unlike the hand-rolled FIFO caches elsewhere in this module, it promotes
 * recently-touched entries rather than always evicting oldest-inserted-first).
 *
 * Eviction removes the least-recently-used entry (by both read and write access) while there is
 * more than one entry and either bound is exceeded. The just-written entry is always kept even if
 * it alone exceeds [maxTotalCostBytes], matching [FifoCostBoundedCache]'s "a large hero is still
 * served once" guarantee.
 *
 * Not thread-safe by itself; use [ThreadSafeLruCostBoundedCache] for the lock-guarded shape the
 * original's actor-isolated `NSCache` access provides.
 */
class LruCostBoundedCache<K, V>(
    private val maxEntryCount: Int,
    private val maxTotalCostBytes: Long,
    private val costOf: (V) -> Long,
) {
    // `accessOrder = true` reorders on every get/put so the iteration order is always
    // least-recently-used first, most-recently-used last - exactly what the eviction sweep below
    // needs to find (and remove) the LRU entry in O(1).
    private val entries = java.util.LinkedHashMap<K, V>(16, 0.75f, true)
    private var totalCostBytes = 0L

    /** Current total cost of all retained entries. */
    val totalCost: Long
        get() = totalCostBytes

    /** Current entry count. */
    val size: Int
        get() = entries.size

    /** Returns the cached value for [key], promoting it to most-recently-used, or `null`. */
    fun get(key: K): V? = entries[key]

    /**
     * Stores [value] under [key] as the most-recently-used entry, replacing any existing entry
     * for that key, then runs the LRU eviction sweep.
     */
    fun put(key: K, value: V) {
        val existing = entries[key]
        if (existing != null) totalCostBytes -= costOf(existing)
        entries[key] = value
        totalCostBytes += costOf(value)
        evictIfNeeded()
    }

    /** Empties the cache, e.g. in response to a memory-pressure signal from a native adapter. */
    fun clear() {
        entries.clear()
        totalCostBytes = 0L
    }

    private fun evictIfNeeded() {
        while (entries.size > 1 && (entries.size > maxEntryCount || totalCostBytes > maxTotalCostBytes)) {
            val leastRecentlyUsed = entries.entries.iterator()
            val oldest = leastRecentlyUsed.next()
            leastRecentlyUsed.remove()
            totalCostBytes -= costOf(oldest.value)
        }
    }
}
