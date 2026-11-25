// PortedFrom: MC1/Services/DecodedPreviewCache.swift@5e980a53e077c45a1270170612fdf5dae97792ff
//             MC1/Services/InlineImageCache.swift (decoded-mirror and servesPage-mirror eviction
//             policy, both of which use the exact same hand-rolled insertion-order structure as
//             DecodedPreviewCache)@db14559b39d32322b06477c6ae676112f583db50
// Generic extraction of the FIFO, count-and-cost-bounded eviction policy shared by
// DecodedPreviewCache's `mirror` and InlineImageCache's `decodedMirror`/`servesPageMirror` - all
// three are hand-rolled `[String: V]` dictionaries plus an explicit `insertionOrder` array under
// a lock, evicting oldest-first while keeping the just-inserted entry. This is NOT the same
// policy as LinkPreviewCache's `NSCache` memory tier: NSCache's internal eviction order is
// OS-managed/undocumented (Apple docs: "not a strict LRU"), not insertion-order FIFO, so it is
// deliberately NOT modeled by this type - LinkPreviewCache's own memory-tier port (not yet
// implemented) will need its own policy, most likely a `LinkedHashMap(accessOrder = true)`-backed
// LRU, not this FIFO primitive. This module owns only the pure, synchronous eviction algorithm;
// thread-safety is layered by [ThreadSafeFifoCostBoundedCache] and concrete payload types
// (android Bitmap-equivalent, network bytes, etc.) are narrow native-adapter responsibilities
// layered on top (see docs/android/deviations/WP-218.md).
package com.meshcoreone.android.core.services.content

/**
 * A FIFO cache bounded by both entry count and total cost, mirroring the hand-rolled
 * insertion-order eviction policy shared by `DecodedPreviewCache.mirror`,
 * `InlineImageCache.decodedMirror`, and `InlineImageCache.servesPageMirror`.
 *
 * Eviction is insertion-order FIFO (not true LRU - the originals never promote on read either):
 * entries are evicted oldest-first while there is more than one entry and either bound is
 * exceeded. The just-inserted entry is always kept even if it alone exceeds [maxTotalCostBytes],
 * matching the Swift comment "so a large hero is still served once."
 *
 * Not thread-safe by itself - the original types each wrap an equivalent single-threaded
 * structure under their own lock/actor (`OSAllocatedUnfairLock`). Use
 * [ThreadSafeFifoCostBoundedCache] to reproduce that wait-free-read, lock-guarded-write shape.
 */
class FifoCostBoundedCache<K, V>(
    private val maxEntryCount: Int,
    private val maxTotalCostBytes: Long,
    private val costOf: (V) -> Long,
) {
    private val entries = LinkedHashMap<K, V>()
    private val insertionOrder = ArrayDeque<K>()
    private var totalCostBytes = 0L

    /** Current total cost of all retained entries. */
    val totalCost: Long
        get() = totalCostBytes

    /** Current entry count. */
    val size: Int
        get() = entries.size

    /** Returns the cached value for [key], or `null` if absent. */
    fun get(key: K): V? = entries[key]

    /**
     * Stores [value] under [key], replacing any existing entry for that key (and moving it to the
     * back of the insertion order, matching the Swift `store(_:for:)` replace-and-reorder
     * behavior), then runs the FIFO eviction sweep.
     */
    fun put(key: K, value: V) {
        val existing = entries[key]
        if (existing != null) {
            totalCostBytes -= costOf(existing)
            insertionOrder.remove(key)
        }
        entries[key] = value
        insertionOrder.addLast(key)
        totalCostBytes += costOf(value)
        evictIfNeeded()
    }

    /**
     * Inserts [value] under [key] only if [key] is not already present, without reordering an
     * existing entry. Mirrors `InlineImageCache.servesPageMirror`'s `Set<String>.insert(_:)`
     * contract exactly: re-marking an already-present key is a silent no-op (no reorder, no cost
     * update), unlike [put]'s always-replace-and-reorder semantics used by `decodedMirror`/
     * `DecodedPreviewCache`. Returns `true` when [key] was newly inserted.
     */
    fun putIfAbsent(key: K, value: V): Boolean {
        if (entries.containsKey(key)) return false
        entries[key] = value
        insertionOrder.addLast(key)
        totalCostBytes += costOf(value)
        evictIfNeeded()
        return true
    }

    /** Empties the cache, e.g. in response to a memory-pressure signal from a native adapter. */
    fun clear() {
        entries.clear()
        insertionOrder.clear()
        totalCostBytes = 0L
    }

    private fun evictIfNeeded() {
        while (insertionOrder.size > 1 &&
            (insertionOrder.size > maxEntryCount || totalCostBytes > maxTotalCostBytes)
        ) {
            val oldestKey = insertionOrder.removeFirst()
            val evicted = entries.remove(oldestKey)
            if (evicted != null) {
                totalCostBytes -= costOf(evicted)
            }
        }
    }
}
