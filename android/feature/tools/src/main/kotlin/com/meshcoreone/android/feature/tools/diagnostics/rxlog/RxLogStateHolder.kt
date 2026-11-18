// PortedFrom: MC1/Views/Tools/RxLogViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.diagnostics.rxlog

import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.lang.ref.WeakReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The WP-212 `RxLogService` slice the RX log screen consumes (core:services stays off the feature classpath). */
interface RxLogFeed {
    suspend fun loadExistingEntries(): List<RxLogEntryDTO>

    /** Live entries; each collector gets its own subscription, as `entryStream()` does. */
    fun entryStream(): Flow<RxLogEntryDTO>
    fun packetRates(): Flow<Long>
    suspend fun clearEntries()
}

/** The contact read used for path-hop name resolution (`ContactPersisting.fetchContacts`). */
fun interface RxLogContactSource {
    suspend fun fetchContacts(radioId: RadioId): List<ContactDTO>
}

/** Live per-connection providers; a null read mirrors a disconnected state. */
data class RxLogFeatureDependencies(
    val rxLogService: () -> RxLogFeed?,
    val dataStore: () -> RxLogContactSource?,
    val radioId: () -> RadioId?,
)

enum class RxLogRouteFilter(val labelId: Int) {
    ALL(AppToolsStrings.toolsRxLogFilterAll),
    FLOOD_ONLY(AppToolsStrings.toolsRxLogFilterFloodOnly),
    DIRECT_ONLY(AppToolsStrings.toolsRxLogFilterDirectOnly),
}

enum class RxLogDecryptFilter(val labelId: Int) {
    ALL(AppToolsStrings.toolsRxLogFilterAll),
    DECRYPTED(AppToolsStrings.toolsRxLogFilterDecrypted),
    FAILED(AppToolsStrings.toolsRxLogFilterFailed),
}

/** Immutable RX log state: entries newest-first, per-hash counts and the active filters. */
data class RxLogState(
    val entries: List<RxLogEntryDTO> = emptyList(),
    val groupCounts: Map<String, Int> = emptyMap(),
    val routeFilter: RxLogRouteFilter = RxLogRouteFilter.ALL,
    val decryptFilter: RxLogDecryptFilter = RxLogDecryptFilter.ALL,
    /** Public-key prefixes (1-3 bytes) that uniquely identify one contact, to its display name. */
    val nodeNames: Map<Bytes, String> = emptyMap(),
    val packetsPerMinute: Long? = null,
) {
    val isFiltering: Boolean get() = routeFilter != RxLogRouteFilter.ALL || decryptFilter != RxLogDecryptFilter.ALL

    /** Entries passing the current route and decrypt filters. */
    val filteredEntries: List<RxLogEntryDTO>
        get() = entries.filter { passesRoute(it) && passesDecrypt(it) }

    private fun passesRoute(entry: RxLogEntryDTO): Boolean = when (routeFilter) {
        RxLogRouteFilter.ALL -> true
        RxLogRouteFilter.FLOOD_ONLY -> entry.isFlood
        RxLogRouteFilter.DIRECT_ONLY -> !entry.isFlood
    }

    private fun passesDecrypt(entry: RxLogEntryDTO): Boolean = when (decryptFilter) {
        RxLogDecryptFilter.ALL -> true
        RxLogDecryptFilter.DECRYPTED -> entry.decryptStatus == DecryptStatus.SUCCESS
        RxLogDecryptFilter.FAILED -> entry.decryptStatus in FAILED_STATUSES
    }

    private companion object {
        val FAILED_STATUSES = setOf(
            DecryptStatus.HMAC_FAILED, DecryptStatus.DECRYPT_FAILED,
            DecryptStatus.NO_MATCHING_KEY, DecryptStatus.DM_NO_MATCHING_KEY,
        )
    }
}

/**
 * Live RX log for the Tools tab (Swift `RxLogViewModel`). Confined to [scope]'s single-threaded
 * dispatcher; [subscribe] loads persisted entries and then follows the live stream.
 */
class RxLogStateHolder(private val scope: CoroutineScope) {
    companion object {
        const val MAX_ENTRIES = 1000
        private const val MAX_PREFIX_LENGTH = 3

        /** Maps 1-, 2- and 3-byte public-key prefixes to a display name when exactly one contact has it. */
        fun buildNodeNameMap(contacts: List<ContactDTO>): Map<Bytes, String> = buildMap {
            for (prefixLength in 1..MAX_PREFIX_LENGTH) {
                val byPrefix = contacts
                    .filter { it.publicKey.size >= prefixLength }
                    .groupBy { it.publicKey.prefix(prefixLength) }
                byPrefix.forEach { (prefix, matches) -> if (matches.size == 1) put(prefix, matches[0].displayName) }
            }
        }
    }

    private val mutableState = MutableStateFlow(RxLogState())
    val state: StateFlow<RxLogState> = mutableState.asStateFlow()
    private var dependencies: RxLogFeatureDependencies? = null
    private var streamJob: Job? = null
    private var rateJob: Job? = null

    /** Change detection for re-subscribes; weak, so a torn-down service reads as a change. */
    private var subscribedService: WeakReference<RxLogFeed>? = null

    fun configure(dependencies: RxLogFeatureDependencies) {
        this.dependencies = dependencies
    }

    fun setRouteFilter(filter: RxLogRouteFilter) = mutableState.update { it.copy(routeFilter = filter) }

    fun setDecryptFilter(filter: RxLogDecryptFilter) = mutableState.update { it.copy(decryptFilter = filter) }

    /** Subscribes to the live service; a re-subscribe first cancels the previous stream. */
    suspend fun subscribe() {
        unsubscribe()
        val service = dependencies?.rxLogService?.invoke() ?: return
        if (subscribedService?.get() !== service) {
            mutableState.update { it.copy(entries = emptyList(), groupCounts = emptyMap()) }
        }
        subscribedService = WeakReference(service)

        val existing = service.loadExistingEntries()
        mutableState.update { it.copy(entries = existing, groupCounts = countsOf(existing)) }

        streamJob = scope.launch {
            service.entryStream().collect(::appendEntry)
        }
        rateJob = scope.launch {
            service.packetRates().collect { rate ->
                mutableState.update { it.copy(packetsPerMinute = rate) }
            }
        }
    }

    fun unsubscribe() {
        streamJob?.cancel()
        streamJob = null
        rateJob?.cancel()
        rateJob = null
        mutableState.update { it.copy(packetsPerMinute = null) }
    }

    suspend fun clearLog() {
        dependencies?.rxLogService?.invoke()?.clearEntries()
        mutableState.update { it.copy(entries = emptyList(), groupCounts = emptyMap()) }
    }

    /** Inserts newest-first and prunes the oldest entry over the cap, keeping counts in step. */
    private fun appendEntry(entry: RxLogEntryDTO) = mutableState.update { current ->
        var entries = listOf(entry) + current.entries
        val counts = current.groupCounts.toMutableMap()
        counts[entry.packetHash] = (counts[entry.packetHash] ?: 0) + 1
        if (entries.size > MAX_ENTRIES) {
            val removed = entries.last()
            entries = entries.dropLast(1)
            val remaining = (counts[removed.packetHash] ?: 1) - 1
            if (remaining == 0) counts.remove(removed.packetHash) else counts[removed.packetHash] = remaining
        }
        current.copy(entries = entries, groupCounts = counts.toMap())
    }

    /** Loads contact names for path-hop resolution; unchanged while disconnected, empty on failure. */
    suspend fun loadNodeNames() {
        val store = dependencies?.dataStore?.invoke() ?: return
        val radio = dependencies?.radioId?.invoke() ?: return
        val names = try {
            buildNodeNameMap(store.fetchContacts(radio))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            emptyMap()
        }
        mutableState.update { it.copy(nodeNames = names) }
    }

    private fun countsOf(entries: List<RxLogEntryDTO>): Map<String, Int> =
        entries.groupingBy { it.packetHash }.eachCount()
}
