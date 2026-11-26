// PortedFrom: MC1/Services/LinkPreviewCache.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.content

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe wrapper around [LruCostBoundedCache] using a [ReentrantLock]. `NSCache`'s own
 * methods are synchronous and safe to call from any thread (including, in the Swift original,
 * from inside an actor-isolated method without an additional `await`); this wrapper reproduces
 * that synchronous, non-suspending contract.
 */
class ThreadSafeLruCostBoundedCache<K, V>(
    maxEntryCount: Int,
    maxTotalCostBytes: Long,
    costOf: (V) -> Long,
) {
    private val lock = ReentrantLock()
    private val cache = LruCostBoundedCache<K, V>(maxEntryCount, maxTotalCostBytes, costOf)

    /** Synchronous lookup; promotes [key] to most-recently-used on a hit. */
    fun get(key: K): V? = lock.withLock { cache.get(key) }

    /** Stores [value] under [key] and runs the LRU eviction sweep, all under the lock. */
    fun put(key: K, value: V) = lock.withLock { cache.put(key, value) }

    /** Empties the cache, e.g. in response to a memory-pressure signal from a native adapter. */
    fun clear() = lock.withLock { cache.clear() }

    /** Current entry count. */
    val size: Int
        get() = lock.withLock { cache.size }

    /** Current total cost of all retained entries. */
    val totalCost: Long
        get() = lock.withLock { cache.totalCost }
}
