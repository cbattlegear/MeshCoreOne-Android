// PortedFrom: MC1Services/Sources/MC1Services/Services/NodeSnapshotService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.NeighborBaseline
import com.meshcoreone.android.core.contracts.domain.NodeSnapshotPersisting
import com.meshcoreone.android.core.contracts.domain.fetchNeighborBaseline
import com.meshcoreone.android.core.contracts.domain.fetchPreviousStatusSnapshot
import com.meshcoreone.android.core.model.NeighborSnapshotEntry
import com.meshcoreone.android.core.model.NodeLocationFix
import com.meshcoreone.android.core.model.NodeStatusMetrics
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.SnapshotSet
import com.meshcoreone.android.core.model.TelemetrySnapshotEntry
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Node status snapshots with throttled capture. Stateless apart from its collaborators: the
 * throttle check and the write are atomic inside the store, so concurrent captures never duplicate
 * an in-window row. Persistence failures are logged and mapped to empty results (as in Swift);
 * cancellation is always rethrown.
 *
 * @param clock the "now" for the neighbor baseline's in-window cutoff (Swift `Date.now`).
 */
class NodeSnapshotService(
    private val dataStore: NodeSnapshotPersisting,
    private val clock: Clock = Clock.systemUTC(),
    private val logger: NodeConfigLogger = NodeConfigLogger.system("NodeSnapshotService"),
) {
    /**
     * Captures a status, telemetry, neighbor and/or location reading, enriching the latest in-window
     * snapshot or inserting a new one. Returns the snapshot id, or null on persistence failure.
     */
    suspend fun recordSnapshot(
        nodePublicKey: Bytes,
        status: NodeStatusMetrics? = null,
        telemetry: SnapshotList<TelemetrySnapshotEntry>? = null,
        neighbors: SnapshotList<NeighborSnapshotEntry>? = null,
        location: NodeLocationFix? = null,
    ): UUID? = recovering("Failed to record snapshot", null) {
        dataStore.recordNodeStatusSnapshot(nodePublicKey, status, telemetry, neighbors, location)
    }

    /**
     * The previous neighbor-bearing snapshot (for the SNR delta) plus every neighbor prefix seen across
     * history (for the "New" badge), excluding the current in-window capture.
     */
    suspend fun neighborBaseline(nodePublicKey: Bytes): NeighborBaseline =
        recovering("Failed to fetch neighbor baseline", NeighborBaseline(null, SnapshotSet.empty())) {
            dataStore.fetchNeighborBaseline(nodePublicKey, clock)
        }

    /** The most recent status-bearing snapshot before [before], skipping neighbor/telemetry-only rows. */
    suspend fun previousStatusSnapshot(nodePublicKey: Bytes, before: Instant): NodeStatusSnapshotDTO? =
        recovering("Failed to fetch previous status snapshot", null) {
            dataStore.fetchPreviousStatusSnapshot(nodePublicKey, before)
        }

    /** All snapshots for a node in ascending timestamp order, optionally since a date. */
    suspend fun fetchSnapshots(nodePublicKey: Bytes, since: Instant? = null): SnapshotList<NodeStatusSnapshotDTO> =
        recovering("Failed to fetch snapshots", SnapshotList.empty()) {
            dataStore.fetchNodeStatusSnapshots(nodePublicKey, since)
        }

    /** Deletes snapshots older than [date]. */
    suspend fun pruneOldSnapshots(date: Instant) {
        recovering("Failed to prune old snapshots", Unit) {
            dataStore.deleteOldNodeStatusSnapshots(date)
            logger.info("Pruned snapshots older than $date")
        }
    }

    /**
     * Swift logs every store error and falls back. The caller's own cancellation still propagates; a
     * CancellationException the store raised itself (an internal timeout) while the caller is active is
     * an ordinary failure.
     */
    private suspend inline fun <T> recovering(failure: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        currentCoroutineContext().ensureActive()
        logger.error("$failure: $error")
        fallback
    } catch (error: Exception) {
        logger.error("$failure: $error")
        fallback
    }
}
