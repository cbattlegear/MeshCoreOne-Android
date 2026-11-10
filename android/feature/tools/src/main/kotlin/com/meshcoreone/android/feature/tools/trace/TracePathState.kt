// PortedFrom: MC1/Views/Tools/TracePath/TracePathViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.SavedTracePathDTO
import com.meshcoreone.android.core.model.TracePathRunDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID

/** AndroidOnly: WP-314 Bind comparison to response acceptance, not asynchronous storage completion. */
data class AcceptedTraceComparison(
    val resultId: UUID,
    val pathId: UUID,
    val radioId: RadioId,
    val run: TracePathRunDTO,
    val previous: TracePathRunDTO?,
)

/**
 * Immutable snapshot of the trace path builder. Values the source derived from the connected
 * device (effective hash mode/size) live on [TracePathStateHolder]; everything here derives from
 * the snapshot alone.
 */
data class TracePathState(
    val outboundPath: List<TracePathHop> = emptyList(),
    val availableRepeaters: List<ContactDTO> = emptyList(),
    val availableRooms: List<ContactDTO> = emptyList(),
    val discoveredRepeaters: List<DiscoveredNodeDTO> = emptyList(),
    val autoReturnPath: Boolean = true,
    /** Recently added hop keys, newest first, for the picker's "Recent" section. */
    val recentPublicKeys: List<Bytes> = emptyList(),
    val isRunning: Boolean = false,
    val result: TraceResult? = null,
    /** A new value only on a successful (non-batch-continuation) trace; drives result presentation. */
    val resultID: UUID? = null,
    val errorMessage: String? = null,
    /** Incremented on each error, for haptics. */
    val errorHapticTrigger: Long = 0,
    val batchEnabled: Boolean = false,
    val batchSize: Int = 3,
    val currentTraceIndex: Int = 0,
    val completedResults: List<TraceResult> = emptyList(),
    /** AndroidOnly: WP-314 Invalidate presentation on path edits without deleting batch history. */
    val batchPresentationActive: Boolean = false,
    /** Retained history cannot authorize saving after its connection is retired. */
    val batchResultsRetired: Boolean = false,
    val activeSavedPath: SavedTracePathDTO? = null,
    val acceptedComparison: AcceptedTraceComparison? = null,
    /** Per-trace hash size override (path_sz 0/1/2); `null` follows the radio's `pathHashMode`. */
    val traceHashMode: UByte? = null,
) {
    /** Repeaters and rooms combined, for hex-code and pin resolution. */
    val availableNodes: List<ContactDTO> get() = availableRepeaters + availableRooms

    val isBatchInProgress: Boolean get() = batchEnabled && currentTraceIndex > 0 && currentTraceIndex <= batchSize
    val isBatchComplete: Boolean get() = batchEnabled && completedResults.size == batchSize
    val successfulResults: List<TraceResult> get() = completedResults.filter { it.success }
    val successCount: Int get() = successfulResults.size

    val averageRTT: Long?
        get() {
            val rtts = successfulResults.map { it.durationMs }
            if (rtts.isEmpty()) return null
            return rtts.fold(0L) { total, value -> Math.addExact(total, value) } / rtts.size
        }
    val minRTT: Long? get() = successfulResults.minOfOrNull { it.durationMs }
    val maxRTT: Long? get() = successfulResults.maxOfOrNull { it.durationMs }

    /** SNR aggregate across successful results for hop [index]; the start node (0) has none. */
    fun hopStats(index: Int): HopStats? {
        if (index <= 0) return null
        val values = successfulResults.mapNotNull { result ->
            result.hops.getOrNull(index)?.takeUnless { it.isStartNode }?.snr
        }
        if (values.isEmpty()) return null
        return HopStats(values.sum() / values.size, values.min(), values.max())
    }

    /** SNR of hop [index] from the most recent successful result. */
    fun latestHopSNR(index: Int): Double? = successfulResults.lastOrNull()?.hops?.getOrNull(index)?.snr

    val isRunningSavedPath: Boolean get() = activeSavedPath != null

    /** The second most recent successful run of the active saved path, for comparison. */
    val previousRun: TracePathRunDTO?
        get() {
            acceptedComparison?.takeIf {
                it.resultId == result?.id && it.pathId == activeSavedPath?.id && it.radioId == activeSavedPath?.radioId
            }?.let { return it.previous }
            val successful = activeSavedPath?.runs.orEmpty().filter { it.success }.sortedByDescending { it.date }
            return if (successful.size >= 2) successful[1] else null
        }

    /** Outbound hops plus the optional mirrored return (without repeating the far hop). */
    val fullPathData: Bytes get() = SavedPathCodec.wirePath(outboundPath.map { it.hashBytes }, autoReturnPath)

    /** Comma-separated hex chunked by the hops' own width (a saved path keeps its saved width). */
    val fullPathString: String
        get() {
            val size = outboundPath.firstOrNull()?.hashBytes?.size ?: 1
            return TracePathHex.chunks(fullPathData, size).joinToString(",")
        }

    val canRunTraceWhenConnected: Boolean get() = outboundPath.isNotEmpty() && !isRunning

    /** A save needs a successful result for exactly the current path. */
    val canSavePath: Boolean
        get() {
            if (batchEnabled) {
                if (batchResultsRetired) return false
                if (completedResults.isEmpty()) return false
                val firstSuccess = successfulResults.firstOrNull() ?: return false
                return fullPathData == firstSuccess.tracedPathBytes
            }
            val current = result ?: return false
            return current.success && fullPathData == current.tracedPathBytes
        }

    /**
     * Metres along the result: the full loop when the device has a location, otherwise the
     * intermediate repeaters only; `null` when a needed hop has no location.
     */
    val totalPathDistance: Double?
        get() {
            val current = result ?: return null
            if (!current.success || current.hops.size < 2) return null
            return calculateDistance(current.hops)
                ?: calculateDistance(current.hops.filter { !it.isStartNode && !it.isEndNode })
        }

    /** Names (or hash hex, or "Unknown") of intermediate hops without a location. */
    val repeatersWithoutLocation: List<String>
        get() = result?.hops.orEmpty()
            .filter { !it.isStartNode && !it.isEndNode && !it.hasLocation }
            .map { it.resolvedName ?: it.hashDisplayString ?: "Unknown" }

    /** Whether [totalPathDistance] fell back to intermediate hops only. */
    val isDistanceUsingFallback: Boolean
        get() {
            val current = result ?: return false
            if (!current.success || totalPathDistance == null) return false
            val start = current.hops.firstOrNull() ?: return false
            val end = current.hops.lastOrNull() ?: return false
            return !start.hasLocation || !end.hasLocation
        }

    private fun calculateDistance(hops: List<TraceHop>): Double? {
        if (hops.size < 2) return null
        var total = 0.0
        for (index in 0 until hops.size - 1) {
            val current = hops[index]
            val next = hops[index + 1]
            if (!current.hasLocation || !next.hasLocation) return null
            val curLat = current.latitude ?: return null
            val curLon = current.longitude ?: return null
            val nextLat = next.latitude ?: return null
            val nextLon = next.longitude ?: return null
            total += GeoDistance.meters(curLat, curLon, nextLat, nextLon)
        }
        return total
    }
}
