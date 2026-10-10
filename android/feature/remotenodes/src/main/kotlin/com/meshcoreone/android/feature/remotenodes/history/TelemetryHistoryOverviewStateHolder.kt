// PortedFrom: MC1/Views/RemoteNodes/TelemetryHistoryOverviewViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.history

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.OCVPreset
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesClock
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeHistoryStore
import com.meshcoreone.android.feature.remotenodes.resolver.NeighborNameResolver
import com.meshcoreone.android.feature.remotenodes.telemetry.MeasurementSystem
import java.time.ZoneId
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Swift `TelemetryHistoryOverviewViewModel`'s stored state. */
data class TelemetryHistoryOverviewState(
    val snapshots: List<NodeStatusSnapshotDTO> = emptyList(),
    val ocvArray: List<Long> = OCVPreset.LI_ION.ocvArray,
    val contacts: List<ContactDTO> = emptyList(),
    val discoveredNodes: List<DiscoveredNodeDTO> = emptyList(),
    val timeRange: HistoryTimeRange = HistoryTimeRange.DEFAULT,
    val isLoading: Boolean = false,
    val error: RemoteNodesText? = null,
) {
    val hasSnapshots: Boolean get() = snapshots.isNotEmpty()
}

/**
 * Offline history overview for one repeater (Swift `TelemetryHistoryOverviewViewModel`). The computed
 * Swift properties read `.now` on every access; here they read [clock] the same way. [system], [locale]
 * and [titleText] stand in for `Locale.current` and `L10n` when grouping and sorting sensor charts.
 */
class TelemetryHistoryOverviewStateHolder(
    private val clock: RemoteNodesClock,
    private val zone: ZoneId,
    private val locale: Locale,
    private val system: MeasurementSystem,
    private val titleText: TitleTextResolver,
) {
    private val _state = MutableStateFlow(TelemetryHistoryOverviewState())
    val state: StateFlow<TelemetryHistoryOverviewState> = _state.asStateFlow()

    fun setTimeRange(range: HistoryTimeRange) {
        _state.update { it.copy(timeRange = range) }
    }

    val filteredSnapshots: List<NodeStatusSnapshotDTO>
        get() = _state.value.let { it.snapshots.filtered(it.timeRange, clock.now, zone) }

    val hasSnapshots: Boolean get() = _state.value.hasSnapshots

    val hasNeighborData: Boolean get() = hasNeighborData(filteredSnapshots)

    val hasTelemetryData: Boolean get() = hasTelemetryData(filteredSnapshots)

    val channelGroups: List<ChannelGroup> get() = channelGroups(filteredSnapshots)

    fun channelGroups(snapshots: List<NodeStatusSnapshotDTO>): List<ChannelGroup> =
        ChannelGroups.groups(snapshots, system, locale, titleText)

    fun hasNeighborData(snapshots: List<NodeStatusSnapshotDTO>): Boolean =
        snapshots.any { it.neighborSnapshots?.isEmpty() == false }

    fun hasTelemetryData(snapshots: List<NodeStatusSnapshotDTO>): Boolean =
        snapshots.any { it.telemetryEntries?.isEmpty() == false }

    fun hasRadioData(snapshots: List<NodeStatusSnapshotDTO>): Boolean = snapshots.any { it.hasRadioMetrics }

    /**
     * Swift `loadData(dataStore:publicKey:radioID:)`: a snapshot failure leaves no snapshots, a contact
     * failure keeps the previous OCV curve, and contact or discovered-node failures (`try?`) leave empty
     * lists. Cancellation always propagates.
     */
    suspend fun loadData(store: RemoteNodeHistoryStore, publicKey: Bytes, radioId: RadioId) {
        _state.update { it.copy(isLoading = true, error = null) }
        try {
            val snapshots = attempt { store.fetchNodeStatusSnapshots(publicKey, null).toList() }
            if (snapshots != null) _state.update { it.copy(snapshots = snapshots) }

            val contact = attempt { store.fetchContact(radioId, publicKey) }
            if (contact != null) _state.update { it.copy(ocvArray = contact.activeOCVArray) }

            val contacts = attempt { store.fetchContacts(radioId).toList() }
            if (contacts != null) _state.update { it.copy(contacts = contacts) }
            val discovered = attempt { store.fetchDiscoveredNodes(radioId).toList() }
            if (discovered != null) _state.update { it.copy(discoveredNodes = discovered) }
        } finally {
            _state.update { it.copy(isLoading = false) }
        }
    }

    /** Swift `resolveNeighborName(prefix:)`: the resolver policy without a user location. */
    fun resolveNeighborName(prefix: Bytes): String? = _state.value.let {
        NeighborNameResolver.resolveName(prefix, it.contacts, it.discoveredNodes, userLocation = null, locale = locale)
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _state.update { it.copy(error = RemoteNodesText.Failure(e)) }
        null
    }
}

/** Swift `hasRadioData(in:)`'s per-snapshot test: any battery, signal, packet or post counter. */
internal val NodeStatusSnapshotDTO.hasRadioMetrics: Boolean
    get() = batteryMillivolts != null || lastSNR != null || lastRSSI != null || noiseFloor != null ||
        packetsSent != null || packetsReceived != null || receiveErrors != null ||
        sentDirect != null || sentFlood != null || receivedDirect != null || receivedFlood != null ||
        directDuplicates != null || floodDuplicates != null || postedCount != null || postPushCount != null
