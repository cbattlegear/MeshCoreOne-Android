// PortedFrom: MC1/Views/Tools/TracePath/Map/TracePathMapViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// Engine-free part only: path membership, pin visibility and pin taps. Lines, SNR badges and the
// camera wait for the WP-312 map engine and MapLine/MapPoint types.
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.UUID

/**
 * Feature-local subset of WP-312 `MapFilterState` for the trace host: Favorites and Discovered.
 * The trace host seeds Discovered on; the Discovered toggle freezes while Favorites is on.
 */
data class TraceMapPinFilter(val favoritesOnly: Boolean = false, val showDiscovered: Boolean = true) {
    val effectiveShowDiscovered: Boolean get() = !favoritesOnly && showDiscovered
}

/** Path membership of a pin; [hopIndex] is 1-based. */
data class RepeaterPathInfo(val inPath: Boolean, val hopIndex: Int?, val isLastHop: Boolean)

/** An engine-neutral pin: the map layer chooses styles from [inPath]. */
data class TraceMapPin(val id: UUID, val coordinate: Coordinate, val inPath: Boolean, val hopIndex: Int?, val label: String?)

enum class PathPinTapResult { ADDED, REMOVED, REJECTED_MIDDLE_HOP, IGNORED }

// PortedFrom: MC1/Views/Map/MapLine+SNR.swift@db14559b39d32322b06477c6ae676112f583db50
enum class TraceLineQuality { GOOD, MEDIUM, WEAK, UNTRACED }

fun traceLineQuality(snr: Double?): TraceLineQuality = when {
    snr == null -> TraceLineQuality.UNTRACED
    snr > 0.0 -> TraceLineQuality.GOOD
    snr > -6.0 -> TraceLineQuality.MEDIUM
    else -> TraceLineQuality.WEAK
}

/** Map-side view of a [TracePathStateHolder] (source `TracePathMapViewModel`). Call from the main thread. */
class TraceMapPathLogic(private val trace: TracePathStateHolder) {
    var userLocation: Coordinate? = null
        private set
    var showLabels: Boolean = true
        private set
    var filter: TraceMapPinFilter = TraceMapPinFilter()
        private set
    var pathState: Map<UUID, RepeaterPathInfo> = emptyMap()
        private set
    var visibleDiscovered: List<DiscoveredNodeDTO> = emptyList()
        private set
    var pins: List<TraceMapPin> = emptyList()
        private set

    /** Repeaters and rooms with a location. */
    val repeatersWithLocation: List<ContactDTO> get() = trace.current.availableNodes.filter { it.hasLocation }
    val hasPath: Boolean get() = trace.current.outboundPath.isNotEmpty()
    val canRunTrace: Boolean get() = trace.current.canRunTraceWhenConnected
    val isRunning: Boolean get() = trace.current.isRunning
    val canSave: Boolean get() = trace.current.canSavePath
    val result: TraceResult? get() = trace.current.result

    private var lastRebuildLocation: Coordinate? = null

    /** Sets the location without rebuilding (the source's `configure(traceViewModel:userLocation:)`). */
    fun configure(location: Coordinate?) {
        userLocation = location
    }

    /** Rebuilds only with a path and after moving at least 10 m since the last rebuild. */
    fun updateUserLocation(location: Coordinate?) {
        userLocation = location
        if (trace.current.outboundPath.isEmpty()) return
        val last = lastRebuildLocation
        if (location != null && last != null && GeoDistance.meters(location, last) < MIN_REBUILD_METERS) return
        lastRebuildLocation = location
        rebuildPathState()
    }

    fun setShowLabels(enabled: Boolean) {
        showLabels = enabled
        rebuildPins()
    }

    fun applyFilter(newFilter: TraceMapPinFilter) {
        filter = newFilter
        rebuildPins()
    }

    /** Rebuilds membership and pins; call when the path, node tables or location change. */
    fun rebuildPathState() {
        val repeaters = repeatersWithLocation
        val discovered = trace.current.discoveredRepeaters
        val path = trace.current.outboundPath
        val lookup = HashMap<UUID, RepeaterPathInfo>()
        path.forEachIndexed { index, hop ->
            locatedPathNodeId(hop)?.let { lookup[it] = RepeaterPathInfo(true, index + 1, index == path.size - 1) }
        }
        val state = LinkedHashMap<UUID, RepeaterPathInfo>()
        for (repeater in repeaters) state[repeater.id] = lookup[repeater.id] ?: RepeaterPathInfo(false, null, false)
        for (node in discovered) lookup[node.id]?.let { state[node.id] = it }
        pathState = state
        rebuildPins()
    }

    fun handleRepeaterTap(repeater: ContactDTO): PathPinTapResult = handlePathPinTap(repeater.id) { trace.addNode(repeater) }

    /** Routes a pin tap to a contact or a visible discovered node. */
    fun handleMapPointTap(pointId: UUID): PathPinTapResult {
        visibleContactPins(repeatersWithLocation, pathMemberIds(), filter).firstOrNull { it.id == pointId }
            ?.let { return handleRepeaterTap(it) }
        visibleDiscovered.firstOrNull { it.id == pointId }
            ?.let { node -> return handlePathPinTap(node.id) { trace.addNode(node) } }
        return PathPinTapResult.IGNORED
    }

    fun clearPath() {
        trace.clearPath()
        rebuildPathState()
    }

    /** Map traces are always single traces. */
    suspend fun runTrace() {
        trace.setBatchEnabled(false)
        trace.runTrace()
    }

    suspend fun savePath(name: String): Boolean = trace.savePath(name)

    fun generatePathName(): String = trace.generatePathName()

    /** The last hop toggles off, a middle hop is rejected, anything else is appended. */
    private fun handlePathPinTap(pointId: UUID, add: () -> Unit): PathPinTapResult {
        val info = pathState[pointId]
        val result = when {
            info?.isLastHop == true -> {
                val lastIndex = trace.current.outboundPath.lastIndex
                if (lastIndex >= 0) trace.removeRepeater(lastIndex)
                PathPinTapResult.REMOVED
            }
            info?.inPath != true -> {
                add()
                PathPinTapResult.ADDED
            }
            else -> PathPinTapResult.REJECTED_MIDDLE_HOP
        }
        rebuildPathState()
        return result
    }

    private fun pathMemberIds(): Set<UUID> = pathState.filterValues { it.inPath }.keys

    private fun rebuildPins() {
        val members = pathMemberIds()
        val contactPins = visibleContactPins(repeatersWithLocation, members, filter).map { repeater ->
            val info = pathState[repeater.id]
            TraceMapPin(repeater.id, Coordinate(repeater.latitude, repeater.longitude), info?.inPath ?: false,
                info?.hopIndex, if (showLabels) repeater.displayName else null)
        }
        val contactKeys = trace.current.availableNodes.map { it.publicKey }.toSet()
        val discovered = visibleDiscoveredPins(trace.current.discoveredRepeaters, contactKeys, members, filter)
        visibleDiscovered = discovered
        val discoveredPins = discovered.map { node ->
            val info = pathState[node.id]
            TraceMapPin(node.id, Coordinate(node.latitude, node.longitude), info?.inPath ?: false,
                info?.hopIndex, if (showLabels) node.name else null)
        }
        pins = contactPins + discoveredPins
    }

    /**
     * A full key that matches a known contact or discovered node never falls back to prefix
     * matching (that would attach the hop to a different node on collision); contacts win over
     * discovered nodes on prefix-only matches.
     */
    private fun locatedPathNodeId(hop: TracePathHop): UUID? {
        val contacts = trace.current.availableNodes
        val discovered = trace.current.discoveredRepeaters
        val key = hop.publicKey
        if (key != null) {
            val exactContact = contacts.firstOrNull { it.publicKey == key }
            val exactDiscovered = discovered.firstOrNull { it.publicKey == key }
            if (exactContact != null || exactDiscovered != null) {
                if (exactContact != null && exactContact.hasLocation) return exactContact.id
                if (exactDiscovered != null && exactDiscovered.isValidFix()) return exactDiscovered.id
                return null
            }
        }
        RepeaterResolver.bestMatch(hop.hashBytes, contacts, userLocation)?.takeIf { it.hasLocation }?.let { return it.id }
        return RepeaterResolver.bestMatch(hop.hashBytes, discovered, userLocation)?.takeIf { it.isValidFix() }?.id
    }

    companion object {
        private const val MIN_REBUILD_METERS = 10.0

        /** Contact candidates after the Favorites rule; path members always stay. */
        fun visibleContactPins(candidates: List<ContactDTO>, pathMemberIds: Set<UUID>, filter: TraceMapPinFilter): List<ContactDTO> =
            if (filter.favoritesOnly) candidates.filter { it.isFavorite || it.id in pathMemberIds } else candidates

        /** Located discovered repeaters that are not contacts, plus located path-member hops. */
        fun visibleDiscoveredPins(
            discovered: List<DiscoveredNodeDTO>,
            contactKeys: Set<Bytes>,
            pathMemberIds: Set<UUID>,
            filter: TraceMapPinFilter,
        ): List<DiscoveredNodeDTO> {
            val pathMembers = discovered.filter { it.id in pathMemberIds && it.isValidFix() }
            if (filter.favoritesOnly || !filter.effectiveShowDiscovered) return pathMembers
            val byId = LinkedHashMap<UUID, DiscoveredNodeDTO>()
            for (node in discovered) {
                if (node.nodeType == ContactType.REPEATER && node.isValidFix() && node.publicKey !in contactKeys) byId[node.id] = node
            }
            for (member in pathMembers) byId[member.id] = member
            return byId.values.toList()
        }

        private fun DiscoveredNodeDTO.isValidFix(): Boolean = Coordinate(latitude, longitude).isValidFix
    }
}
