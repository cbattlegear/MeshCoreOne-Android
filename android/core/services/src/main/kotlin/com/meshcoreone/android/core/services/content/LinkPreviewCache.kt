// PortedFrom: MC1/Services/LinkPreviewCache.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.content

import com.meshcoreone.android.core.contracts.domain.LinkPreviewPersisting
import com.meshcoreone.android.core.model.LinkPreviewDataDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import java.util.logging.Logger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private object CacheConfig {
    const val MAX_ENTRY_COUNT = 512
    const val MAX_TOTAL_COST_BYTES = 50L * 1024 * 1024 // 50MB
    const val MAX_CONCURRENT_FETCHES = 3
    const val PREVIEW_AVAILABILITY_BUFFER = 32
    const val MAX_PERSISTED_LOOKUPS = 10_000
    const val MAX_PERSISTED_LOOKUP_COST_BYTES = 4L * 1024 * 1024
    const val PERSISTED_LOOKUP_TTL_NANOS = 60_000_000_000L
    const val ENTRY_OVERHEAD_BYTES = 64L
}

/**
 * Two-tier cache for link previews with URL-based deduplication.
 *
 * Mirrors the Swift `actor LinkPreviewCache`'s thread-safety shape using a [Mutex]-guarded
 * critical section instead of actor isolation, and a dedicated [scope] (not the caller's own
 * coroutine) to run in-flight fetches instead of the Swift source's detached `Task`: the shared
 * fetch must complete and warm the cache even if the requesting caller is cancelled (e.g. a
 * scrolled-away list cell), matching the source's "independent of any caller's cancellation"
 * comment. [fetchSemaphore] limits concurrent fetches exactly like the source's
 * `AsyncSemaphore`-backed limiter on concurrent `LPMetadataProvider`/WKWebView instances.
 */
class LinkPreviewCache(
    private val service: LinkMetadataFetching,
    private val preferences: LinkPreviewPreferences,
    scope: CoroutineScope? = null,
    private val monotonicNowNanos: () -> Long = { System.nanoTime() },
) : LinkPreviewCaching, AutoCloseable {
    private val workers = CoroutineScope(
        (scope?.coroutineContext ?: Dispatchers.Default) + SupervisorJob(scope?.coroutineContext?.get(Job)),
    )
    private val memoryCache = ThreadSafeLruCostBoundedCache<String, LinkPreviewDataDTO>(
        maxEntryCount = CacheConfig.MAX_ENTRY_COUNT,
        maxTotalCostBytes = CacheConfig.MAX_TOTAL_COST_BYTES,
        costOf = { it.costOf() },
    )
    private val fetchSemaphore = Semaphore(CacheConfig.MAX_CONCURRENT_FETCHES)
    private val mutex = Mutex()
    private val persistedLookupMutex = Mutex()
    private val memoryClearLock = Any()
    private data class PersistedLookup(
        val hasPreview: Boolean,
        val createdAtNanos: Long,
        val costBytes: Long,
        val memoryClearRevision: Long,
    )
    private val persistedLookups = ThreadSafeLruCostBoundedCache<String, PersistedLookup>(
        maxEntryCount = CacheConfig.MAX_PERSISTED_LOOKUPS,
        maxTotalCostBytes = CacheConfig.MAX_PERSISTED_LOOKUP_COST_BYTES,
        costOf = { it.costBytes },
    )
    private val memoryClearRevision = AtomicLong()
    private val mutablePreviewAvailability =
        MutableSharedFlow<String>(extraBufferCapacity = CacheConfig.PREVIEW_AVAILABILITY_BUFFER)
    val previewAvailability: SharedFlow<String> = mutablePreviewAvailability.asSharedFlow()

    /**
     * Shared fetch deferred per in-flight URL. Concurrent requests for the same URL await the
     * same deferred and receive the resolved result, instead of a [LinkPreviewResult.Loading]
     * placeholder that would strand a follower's preview state with no path back to
     * [LinkPreviewResult.Loaded].
     */
    private val inFlightTasks = ConcurrentHashMap<String, Deferred<LinkPreviewResult>>()

    /** URLs that have been fetched but have no preview available. */
    private val noPreviewAvailable = mutableSetOf<String>()

    override suspend fun preview(
        url: String,
        dataStore: LinkPreviewPersisting,
        isChannelMessage: Boolean,
    ): LinkPreviewResult {
        // Check negative cache first.
        val isNegativelyCached = mutex.withLock { url in noPreviewAvailable }
        if (isNegativelyCached) return LinkPreviewResult.NoPreviewAvailable

        // Check memory and database caches.
        checkCaches(url, dataStore)?.let { return LinkPreviewResult.Loaded(it) }

        // Check preferences before network fetch.
        if (!preferences.shouldAutoResolve(isChannelMessage)) return LinkPreviewResult.Disabled

        // Network fetch, coalescing concurrent requests for the same URL.
        return fetchFromNetwork(url, dataStore)
    }

    override suspend fun manualFetch(url: String, dataStore: LinkPreviewPersisting): LinkPreviewResult {
        // Check memory and database caches (skip negative cache for manual retry).
        checkCaches(url, dataStore)?.let { return LinkPreviewResult.Loaded(it) }

        // Clear from negative cache on manual retry.
        mutex.withLock { noPreviewAvailable.remove(url) }

        return fetchFromNetwork(url, dataStore)
    }

    override suspend fun isFetching(url: String): Boolean = inFlightTasks.containsKey(url)

    override suspend fun cachedPreview(url: String): LinkPreviewDataDTO? =
        synchronized(memoryClearLock) { memoryCache.get(url) }

    fun hasCachedPreview(url: String): Boolean {
        return synchronized(memoryClearLock) {
            if (memoryCache.get(url) != null) return@synchronized true
            val lookup = persistedLookups.get(url) ?: return@synchronized false
            lookup.memoryClearRevision == memoryClearRevision.get() &&
                lookup.hasPreview &&
                monotonicNowNanos() - lookup.createdAtNanos < CacheConfig.PERSISTED_LOOKUP_TTL_NANOS
        }
    }

    suspend fun cachedPreview(url: String, dataStore: LinkPreviewPersisting): LinkPreviewDataDTO? =
        checkCaches(url, dataStore)

    /**
     * Checks memory cache and database for existing preview data. Returns the DTO if found, and
     * caches it in memory if it was loaded from the database.
     */
    private suspend fun checkCaches(url: String, dataStore: LinkPreviewPersisting): LinkPreviewDataDTO? {
        // Tier 1: Memory cache (instant).
        synchronized(memoryClearLock) { memoryCache.get(url) }?.let { return it }

        // Tier 2: Database lookup.
        return persistedLookupMutex.withLock {
            synchronized(memoryClearLock) { memoryCache.get(url) }?.let { return@withLock it }
            val now = monotonicNowNanos()
            val (clearRevisionAtLookup, cachedLookup) = synchronized(memoryClearLock) {
                memoryClearRevision.get() to persistedLookups.get(url)
            }
            val lookupIsFresh = cachedLookup != null &&
                now - cachedLookup.createdAtNanos < CacheConfig.PERSISTED_LOOKUP_TTL_NANOS
            if (lookupIsFresh && cachedLookup?.hasPreview == false) return@withLock null
            val expiredLookup = cachedLookup != null && !lookupIsFresh
            val lookupCostBytes = url.persistedLookupCostBytes()
            val lookupCanBeCached = lookupCostBytes <= CacheConfig.MAX_PERSISTED_LOOKUP_COST_BYTES
            try {
                val persisted = dataStore.fetchLinkPreview(url)
                val currentMemoryClearRevision = synchronized(memoryClearLock) {
                    val currentRevision = memoryClearRevision.get()
                    if (currentRevision == clearRevisionAtLookup && lookupCanBeCached) {
                        persistedLookups.put(
                            url,
                            PersistedLookup(
                                hasPreview = persisted != null,
                                createdAtNanos = monotonicNowNanos(),
                                costBytes = lookupCostBytes,
                                memoryClearRevision = currentRevision,
                            ),
                        )
                        persisted?.let { memoryCache.put(url, it) }
                    }
                    currentRevision
                }
                val clearedDuringLookup = currentMemoryClearRevision != clearRevisionAtLookup
                val recheckedAfterTrim = lookupCanBeCached && currentMemoryClearRevision > 0 &&
                    cachedLookup?.memoryClearRevision != currentMemoryClearRevision
                if (persisted != null && (expiredLookup || clearedDuringLookup || recheckedAfterTrim)) {
                    publishPreviewAvailability(url)
                }
                persisted
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Matches the Swift original's log-and-continue behavior: a database read failure is
                // treated as a cache miss, not a fetch failure - the caller still falls through to a
                // genuine network fetch attempt, it is not a fabricated success.
                Logger.getLogger("MeshCore.LinkPreviewCache").fine("Preview database read failed: ${error.javaClass.simpleName}")
                null
            }
        }
    }

    private suspend fun publishPreviewAvailability(url: String) {
        mutablePreviewAvailability.emit(url)
    }

    /**
     * Coalesces concurrent fetches for the same URL onto a single shared deferred. Followers
     * await that deferred and receive its resolved result rather than a
     * [LinkPreviewResult.Loading] placeholder.
     */
    private suspend fun fetchFromNetwork(url: String, dataStore: LinkPreviewPersisting): LinkPreviewResult {
        // A single locked check-and-create avoids the TOCTOU race a separate check then store
        // would introduce under concurrent callers: only the caller that actually creates the
        // entry ("owner") is responsible for removing it once the fetch resolves.
        val (deferred, created) = mutex.withLock {
            val existing = inFlightTasks[url]
            if (existing != null) {
                existing to false
            } else {
                val created = workers.async(start = CoroutineStart.LAZY) { performNetworkFetch(url, dataStore) }
                inFlightTasks[url] = created
                created to true
            }
        }
        if (created) deferred.invokeOnCompletion { inFlightTasks.remove(url, deferred) }
        deferred.start()
        return deferred.await()
    }

    private suspend fun performNetworkFetch(url: String, dataStore: LinkPreviewPersisting): LinkPreviewResult =
        // Limits concurrent LPMetadataProvider-equivalent fetches (and, per fetch, the
        // scrape/image-GET fallback) exactly like the source's `fetchSemaphore.wait()`/
        // `defer { signal() }` pair; `withPermit` releases on both normal return and
        // cancellation, matching the source's defer-guaranteed release.
        fetchSemaphore.withPermit {
            val clearRevisionAtFetch = memoryClearRevision.get()
            val metadata = service.fetchMetadata(url)
            if (metadata == null) {
                // Cache negative result to avoid repeated fetch attempts.
                mutex.withLock { noPreviewAvailable.add(url) }
                return@withPermit LinkPreviewResult.NoPreviewAvailable
            }

            val heroDimensions = metadata.imageData?.let { ImageHeaderDecoder.decodeDimensions(it) }
            val dto = LinkPreviewDataDTO(
                url = url,
                title = metadata.title,
                imageData = metadata.imageData?.let { Bytes(it) },
                iconData = metadata.iconData?.let { Bytes(it) },
                imageWidth = heroDimensions?.first?.toLong(),
                imageHeight = heroDimensions?.second?.toLong(),
            )

            synchronized(memoryClearLock) {
                if (memoryClearRevision.get() == clearRevisionAtFetch) memoryCache.put(url, dto)
            }

            // Persist to database.
            try {
                dataStore.saveLinkPreview(dto)
                persistedLookupMutex.withLock {
                    synchronized(memoryClearLock) {
                        val lookupCostBytes = url.persistedLookupCostBytes()
                        if (
                            memoryClearRevision.get() == clearRevisionAtFetch &&
                            lookupCostBytes <= CacheConfig.MAX_PERSISTED_LOOKUP_COST_BYTES
                        ) {
                            persistedLookups.put(
                                url,
                                PersistedLookup(
                                    hasPreview = true,
                                    createdAtNanos = monotonicNowNanos(),
                                    costBytes = lookupCostBytes,
                                    memoryClearRevision = clearRevisionAtFetch,
                                ),
                            )
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Matches the Swift original's log-and-continue behavior: a persistence failure
                // does not invalidate the freshly fetched (and already memory-cached) result.
                Logger.getLogger("MeshCore.LinkPreviewCache").fine("Preview database write failed: ${error.javaClass.simpleName}")
            }

            publishPreviewAvailability(url)
            LinkPreviewResult.Loaded(dto)
        }

    private fun Bytes?.costOf(): Long = this?.size?.toLong() ?: 0L

    private fun LinkPreviewDataDTO.costOf(): Long =
        imageData.costOf() + iconData.costOf() +
            url.storageCostBytes() + (title?.storageCostBytes() ?: 0L) +
            CacheConfig.ENTRY_OVERHEAD_BYTES

    private fun String.storageCostBytes(): Long = length.toLong() * 2L

    private fun String.persistedLookupCostBytes(): Long =
        storageCostBytes() + CacheConfig.ENTRY_OVERHEAD_BYTES

    fun clearMemory() {
        synchronized(memoryClearLock) {
            memoryClearRevision.incrementAndGet()
            memoryCache.clear()
            persistedLookups.clear()
        }
    }

    override fun close() {
        workers.cancel()
    }
}
