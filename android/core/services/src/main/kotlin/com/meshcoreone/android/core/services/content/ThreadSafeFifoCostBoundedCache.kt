// PortedFrom: MC1/Services/DecodedPreviewCache.swift@5e980a53e077c45a1270170612fdf5dae97792ff
//             MC1/Services/InlineImageCache.swift (decodedMirror/servesPageMirror)@db14559b39d32322b06477c6ae676112f583db50
// Swift's `OSAllocatedUnfairLock<State>` gives a synchronous, non-suspending critical section:
// callers (including a main-actor SwiftUI view body) call `withLock` directly, never `await`.
// `java.util.concurrent.locks.ReentrantLock` is the closest pure-JVM/Android-compatible
// equivalent with the same synchronous, non-suspending contract (unlike a coroutine `Mutex`,
// whose `lock()` is a suspend function). This wrapper reproduces that exact shape:
// lock-guarded writes, wait-free-callable (but still synchronously blocking, uncontended-fast)
// reads, no coroutine dispatch involved.
package com.meshcoreone.android.core.services.content

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe wrapper around [FifoCostBoundedCache] using a [ReentrantLock], mirroring the
 * `OSAllocatedUnfairLock`-guarded mirrors in `DecodedPreviewCache` and `InlineImageCache`.
 * Reads and writes are both synchronous (no suspension), matching the originals' "safe to call
 * from a view body without an actor hop" contract.
 */
class ThreadSafeFifoCostBoundedCache<K, V>(
    maxEntryCount: Int,
    maxTotalCostBytes: Long,
    costOf: (V) -> Long,
) {
    private val lock = ReentrantLock()
    private val cache = FifoCostBoundedCache<K, V>(maxEntryCount, maxTotalCostBytes, costOf)

    /** Synchronous, wait-free-callable lookup. */
    fun get(key: K): V? = lock.withLock { cache.get(key) }

    /** Stores [value] under [key] and runs the FIFO eviction sweep, all under the lock. */
    fun put(key: K, value: V) = lock.withLock { cache.put(key, value) }

    /** Inserts [value] under [key] only if absent, without reordering an existing entry; see
     * [FifoCostBoundedCache.putIfAbsent]. Returns `true` when [key] was newly inserted. */
    fun putIfAbsent(key: K, value: V): Boolean = lock.withLock { cache.putIfAbsent(key, value) }

    /** Empties the cache, e.g. in response to a memory-pressure signal from a native adapter. */
    fun clear() = lock.withLock { cache.clear() }

    /** Current entry count. */
    val size: Int
        get() = lock.withLock { cache.size }

    /** Current total cost of all retained entries. */
    val totalCost: Long
        get() = lock.withLock { cache.totalCost }
}
