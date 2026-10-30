// PortedFrom: MC1Services/Sources/MC1Services/Services/RxLogService+RegionResolution.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: the Swift actor extension becomes a lock-confined collaborator owned by RxLogService.
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.contracts.domain.ChannelRegionUpdate
import com.meshcoreone.android.core.contracts.domain.DirectRegionUpdate
import com.meshcoreone.android.core.contracts.domain.RxLogRegionUpdate
import com.meshcoreone.android.core.contracts.domain.RxLogRetention
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RegionScopeSemantics
import com.meshcoreone.android.core.model.RegionStorageFields
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.parser.RegionMatchResult
import com.meshcoreone.android.core.protocol.parser.RegionScopeKey
import com.meshcoreone.android.core.protocol.parser.TransportCodeRegionResolver
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield

/**
 * Region resolution and region back-fill for [RxLogService]. Each `synchronized` section is one
 * non-suspending actor segment of the source; every store call runs outside the lock, so live
 * `process` work interleaves with a reprocess drain exactly where the actor would re-enter.
 */
internal class RxLogRegionReprocessor(
    private val store: RxLogServiceStore,
    private val diagnostics: RxLogDiagnostics,
    private val clock: Clock,
    private val locale: Locale,
    private val currentRadioId: () -> RadioId?,
    private val regionUpdates: RxLogStreamBroadcaster<SnapshotList<UUID>>,
) {
    private val lock = Any()
    private var knownRegionsValue: List<String> = emptyList()
    private var scopeKeyCache: List<RegionScopeKey> = emptyList()
    private var cacheGeneration = 0L
    private var hasExplicitRegionUpdate = false
    private var lastRegionMissLogTime: Instant? = null
    private var lastRegionAmbiguousLogTime: Instant? = null
    private var isReprocessingValue = false
    private var reprocessDirty = false
    private var waiters: List<CompletableDeferred<Unit>> = emptyList()

    val knownRegions: List<String> get() = synchronized(lock) { knownRegionsValue }

    /** Whether a region reprocess drain currently owns the reprocess loop (source `isReprocessingRegions`). */
    val isReprocessing: Boolean get() = synchronized(lock) { isReprocessingValue }

    /** Bumped by region updates so a slower database load does not overwrite them. */
    val generation: Long get() = synchronized(lock) { cacheGeneration }

    /**
     * Installs regions loaded from the device row unless an explicit region update already won.
     * Returns the resolvable count, or `null` when the load was stale.
     */
    fun applyLoadedRegions(loadGeneration: Long, regions: List<String>): Int? = synchronized(lock) {
        if (hasExplicitRegionUpdate || loadGeneration != cacheGeneration) return null
        knownRegionsValue = regions.toList()
        scopeKeyCache = buildScopeKeyCache(knownRegionsValue)
        scopeKeyCache.size
    }

    /** Resolves `transport_codes[0]` of a live packet into the dual storage fields. */
    fun resolve(transportCode: Bytes?, payloadTypeBits: UByte, payload: Bytes): RegionStorageFields =
        resolveRegionStorage(transportCode, payloadTypeBits, payload, cache = null, logMisses = true)

    /** Updates known regions, rebuilds the cache and reprocesses; runs for an empty list so labels clear. */
    suspend fun updateKnownRegions(regions: List<String>) {
        val changed = synchronized(lock) {
            // An equal explicit value still supersedes any pending or future device-row load for this session.
            hasExplicitRegionUpdate = true
            cacheGeneration += 1
            if (knownRegionsValue == regions) return@synchronized false
            knownRegionsValue = regions.toList()
            scopeKeyCache = buildScopeKeyCache(knownRegionsValue)
            true
        }
        if (changed) reprocessRegionEntries()
    }

    /** Test seam: injects names that share one key without needing a real 16-bit collision. */
    suspend fun replaceScopeKeyCacheAndReprocess(cache: List<RegionScopeKey>) {
        synchronized(lock) {
            hasExplicitRegionUpdate = true
            cacheGeneration += 1
            knownRegionsValue = cache.map { it.name }
            scopeKeyCache = cache.toList()
        }
        reprocessRegionEntries()
    }

    /**
     * Re-resolves retained transport-coded entries against the current cache. Concurrent callers
     * mark dirty and park until the owner's drain finishes; overlapping callers converge on the
     * final cache.
     */
    suspend fun reprocessRegionEntries() {
        if (!currentCoroutineContext().isActive) return
        val waiter = synchronized(lock) {
            reprocessDirty = true
            if (isReprocessingValue) {
                CompletableDeferred<Unit>().also { waiters = waiters + it }
            } else {
                isReprocessingValue = true
                null
            }
        }
        if (waiter != null) {
            waiter.await()
            if (!currentCoroutineContext().isActive) return
            // Owner finished between our dirty set and its last while-check; re-enter so the mark is not lost.
            if (synchronized(lock) { reprocessDirty }) reprocessRegionEntries()
            return
        }
        try {
            drain()
        } finally {
            val parked = synchronized(lock) {
                isReprocessingValue = false
                waiters.also { waiters = emptyList() }
            }
            parked.forEach { it.complete(Unit) }
        }
    }

    private suspend fun drain() {
        val radioId = currentRadioId()
        if (radioId == null) {
            synchronized(lock) { reprocessDirty = false }
            return
        }
        while (synchronized(lock) { reprocessDirty }) {
            if (!currentCoroutineContext().isActive) return
            synchronized(lock) { reprocessDirty = false }
            runReprocessPass(radioId)
        }
    }

    private suspend fun runReprocessPass(radioId: RadioId) {
        try {
            val entries = store.fetchEntriesWithTransportCode(radioId, REGION_REPROCESS_FETCH_LIMIT)
            if (entries.isEmpty()) return
            log(DebugLogLevel.INFO, "Re-processing ${entries.size} transport-coded entries for region resolution")

            // Snapshot so a mid-pass known-regions change is applied on the dirty re-run.
            val cacheSnapshot = synchronized(lock) { scopeKeyCache }
            val rxUpdates = mutableListOf<RxLogRegionUpdate>()
            val channelMessageUpdates = mutableListOf<ChannelRegionUpdate>()
            val dmMessageUpdates = mutableListOf<DirectRegionUpdate>()

            for ((index, entry) in entries.withIndex()) {
                if (!currentCoroutineContext().isActive) break
                if (index > 0 && index % REPROCESS_YIELD_INTERVAL == 0) yield()

                val resolved = resolveRegionStorage(
                    entry.transportCode, entry.payloadTypeBits, entry.packetPayload, cacheSnapshot, logMisses = false,
                )
                val scopeChanged = entry.regionScope != resolved.regionScope
                val matchesChanged = entry.regionScopeMatches != resolved.regionScopeMatches
                if (!scopeChanged && !matchesChanged) continue

                rxUpdates += RxLogRegionUpdate(entry.id, resolved.regionScope, resolved.regionScopeMatches)

                val senderTimestamp = entry.senderTimestamp ?: continue
                val channelIndex = entry.channelIndex
                if (channelIndex != null) {
                    channelMessageUpdates += ChannelRegionUpdate(
                        channelIndex, senderTimestamp, resolved.regionScope, resolved.regionScopeMatches,
                    )
                } else if (entry.packetPayload.size >= DM_SENDER_PREFIX_BYTE_OFFSET + 1) {
                    dmMessageUpdates += DirectRegionUpdate(
                        entry.packetPayload[DM_SENDER_PREFIX_BYTE_OFFSET], senderTimestamp,
                        resolved.regionScope, resolved.regionScopeMatches,
                    )
                }
            }

            if (rxUpdates.isNotEmpty()) store.batchUpdateRxLogRegion(radioId, rxUpdates.snapshot())

            val touchedMessageIds = LinkedHashSet<UUID>()
            if (channelMessageUpdates.isNotEmpty()) {
                touchedMessageIds += store.batchUpdateChannelMessageRegion(radioId, channelMessageUpdates.snapshot())
            }
            if (dmMessageUpdates.isNotEmpty()) {
                touchedMessageIds += store.batchUpdateDMMessageRegion(radioId, dmMessageUpdates.snapshot())
            }
            if (touchedMessageIds.isNotEmpty()) regionUpdates.yield(touchedMessageIds.snapshot())

            val messageCount = channelMessageUpdates.size + dmMessageUpdates.size
            if (rxUpdates.isNotEmpty() || messageCount > 0) {
                log(
                    DebugLogLevel.INFO,
                    "Region reprocess wrote ${rxUpdates.size} RxLog entries, $messageCount message correlations " +
                        "(${touchedMessageIds.size} message IDs)",
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log(DebugLogLevel.ERROR, "Failed to re-process region entries: ${error.rxLogErrorDescription()}")
        }
    }

    private fun resolveRegionStorage(
        transportCode: Bytes?,
        payloadTypeBits: UByte,
        payload: Bytes,
        cache: List<RegionScopeKey>?,
        logMisses: Boolean,
    ): RegionStorageFields {
        if (transportCode == null || transportCode.size < 2) return NO_REGION
        val activeCache = cache ?: synchronized(lock) { scopeKeyCache }
        // Empty cache skips HMACs but still projects none so writers can clear labels.
        if (activeCache.isEmpty()) return NO_REGION
        val match = TransportCodeRegionResolver.matchRegions(
            activeCache, transportCode.readUInt16LE(0), payloadTypeBits, payload, locale,
        )
        if (logMisses) {
            when (match) {
                RegionMatchResult.None -> logRegionMissThrottled()
                is RegionMatchResult.Ambiguous -> logRegionAmbiguousThrottled(match.names)
                is RegionMatchResult.Unique -> Unit
            }
        }
        return RegionScopeSemantics.storageFields(match, locale)
    }

    private fun logRegionMissThrottled() {
        val now = clock.instant()
        val regionCount = synchronized(lock) {
            val last = lastRegionMissLogTime
            if (last != null && Duration.between(last, now) < MISS_LOG_THROTTLE) return
            lastRegionMissLogTime = now
            knownRegionsValue.size
        }
        if (regionCount == 0) {
            log(DebugLogLevel.DEBUG, "Region resolution skipped: no known regions loaded")
        } else {
            log(DebugLogLevel.DEBUG, "Region resolution miss against $regionCount known regions")
        }
    }

    private fun logRegionAmbiguousThrottled(names: List<String>) {
        val now = clock.instant()
        synchronized(lock) {
            val last = lastRegionAmbiguousLogTime
            if (last != null && Duration.between(last, now) < AMBIGUOUS_LOG_THROTTLE) return
            lastRegionAmbiguousLogTime = now
        }
        // Public region names only; never scopeKey bytes, payload, or raw codes.
        log(DebugLogLevel.DEBUG, "Region resolution ambiguous: ${names.size} matches ${names.joinToString(", ")}")
    }

    private fun log(level: DebugLogLevel, message: String) = diagnostics.log(level, LOG_CATEGORY, message)

    companion object {
        const val LOG_CATEGORY = "RxLogService.Region"
        val MISS_LOG_THROTTLE: Duration = Duration.ofSeconds(60)
        val AMBIGUOUS_LOG_THROTTLE: Duration = Duration.ofSeconds(60)

        /** Offset of the unencrypted sender prefix byte in a DM `packetPayload`. */
        const val DM_SENDER_PREFIX_BYTE_OFFSET = 1

        /** Full retention window, so rows that exist between prune passes are not skipped. */
        const val REGION_REPROCESS_FETCH_LIMIT = RxLogRetention.KEEP_COUNT + RxLogRetention.PRUNE_THRESHOLD

        /** Yield every N entries so live `process` can interleave during multi-match HMAC work. */
        const val REPROCESS_YIELD_INTERVAL = 32

        private val NO_REGION = RegionStorageFields(null, SnapshotList.empty())

        /** Skips names `deriveScopeKey` rejects (`$`-prefixed and empty/whitespace names). */
        fun buildScopeKeyCache(regions: List<String>): List<RegionScopeKey> = regions.mapNotNull { name ->
            TransportCodeRegionResolver.deriveScopeKey(name)?.let { RegionScopeKey(name, it) }
        }
    }
}
