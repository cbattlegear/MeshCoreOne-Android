// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/TracePathPersisting.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/DebugLogPersisting.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/DebugLogRetention.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/LinkPreviewPersisting.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/RxLogPersisting.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/NodeSnapshotPersisting.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Diagnostics.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

interface TracePathPersisting {
    suspend fun fetchSavedTracePaths(radioId: RadioId): SnapshotList<SavedTracePathDTO>
    suspend fun fetchSavedTracePath(key: EntityKey): SavedTracePathDTO?
    suspend fun createSavedTracePath(radioId: RadioId, name: String, pathBytes: Bytes, hashSize: Long, initialRun: TracePathRunDTO?): SavedTracePathDTO
    suspend fun updateSavedTracePathName(key: EntityKey, name: String)
    suspend fun deleteSavedTracePath(key: EntityKey)
    suspend fun appendTracePathRun(path: EntityKey, run: TracePathRunDTO)
}

interface DebugLogPersisting {
    suspend fun saveDebugLogEntries(dtos: SnapshotList<DebugLogEntryDTO>)
    suspend fun fetchDebugLogEntries(since: Instant, limit: Long = 1000): SnapshotList<DebugLogEntryDTO>
    suspend fun countDebugLogEntries(): Long
    suspend fun pruneDebugLogEntries(olderThan: Instant, keepCount: Long)
    suspend fun clearDebugLogEntries()
}

interface LinkPreviewPersisting {
    suspend fun fetchLinkPreview(url: String): LinkPreviewDataDTO?
    suspend fun saveLinkPreview(dto: LinkPreviewDataDTO)
}

interface RxLogPersisting {
    suspend fun findRxLogEntry(radioId: RadioId, channelIndex: UByte?, senderTimestamp: UInt): RxLogEntryDTO?
    suspend fun fetchRxLogEntries(radioId: RadioId, channelIndex: UByte, senderTimestamp: UInt): SnapshotList<RxLogEntryDTO>
    suspend fun findRxLogEntryBySenderPrefix(radioId: RadioId, senderPrefixByte: UByte, receivedSince: Instant): RxLogEntryDTO?
    suspend fun saveRxLogEntry(dto: RxLogEntryDTO)
    suspend fun flushPendingRxLogEntries()
    suspend fun fetchRxLogEntries(radioId: RadioId, limit: Long = 500): SnapshotList<RxLogEntryDTO>
    suspend fun countRxLogEntries(radioId: RadioId): Long
    suspend fun clearRxLogEntries(radioId: RadioId)
    suspend fun pruneRxLogEntries(radioId: RadioId, keepCount: Long = RxLogRetention.KEEP_COUNT, pruneThreshold: Long = RxLogRetention.PRUNE_THRESHOLD)
    suspend fun fetchEntriesWithTransportCode(radioId: RadioId, limit: Long): SnapshotList<RxLogEntryDTO>
    suspend fun fetchRecentEntriesByDecryptStatus(radioId: RadioId, status: DecryptStatus, since: Instant): SnapshotList<RxLogEntryDTO>
    suspend fun batchUpdateRxLogRegion(radioId: RadioId, updates: SnapshotList<RxLogRegionUpdate>)
    suspend fun batchUpdateRxLogDecryption(radioId: RadioId, updates: SnapshotList<RxLogDecryptionUpdate>)
    suspend fun batchUpdateChannelMessageRegion(radioId: RadioId, updates: SnapshotList<ChannelRegionUpdate>): SnapshotList<UUID>
    suspend fun batchUpdateDMMessageRegion(radioId: RadioId, updates: SnapshotList<DirectRegionUpdate>): SnapshotList<UUID>
}

// Source history is shared by full node public key, not by the companion that queried it.
interface NodeSnapshotPersisting {
    suspend fun saveNodeStatusSnapshot(
        nodePublicKey: Bytes, batteryMillivolts: UShort?, lastSNR: Double?, lastRSSI: Short?, noiseFloor: Short?,
        uptimeSeconds: UInt?, rxAirtimeSeconds: UInt?, packetsSent: UInt?, packetsReceived: UInt?, receiveErrors: UInt?,
        postedCount: UShort?, postPushCount: UShort?,
    ): UUID
    suspend fun saveNodeStatusSnapshot(nodePublicKey: Bytes, status: NodeStatusMetrics): UUID
    suspend fun fetchLatestNodeStatusSnapshot(nodePublicKey: Bytes): NodeStatusSnapshotDTO?
    suspend fun fetchNodeStatusSnapshots(nodePublicKey: Bytes, since: Instant?): SnapshotList<NodeStatusSnapshotDTO>
    suspend fun updateSnapshotNeighbors(id: UUID, neighbors: SnapshotList<NeighborSnapshotEntry>)
    suspend fun updateSnapshotTelemetry(id: UUID, telemetry: SnapshotList<TelemetrySnapshotEntry>)
    suspend fun saveTelemetryOnlySnapshot(nodePublicKey: Bytes, telemetryEntries: SnapshotList<TelemetrySnapshotEntry>): UUID
    suspend fun recordNodeStatusSnapshot(
        nodePublicKey: Bytes, status: NodeStatusMetrics?, telemetry: SnapshotList<TelemetrySnapshotEntry>?,
        neighbors: SnapshotList<NeighborSnapshotEntry>?, location: NodeLocationFix?,
    ): UUID
    suspend fun deleteOldNodeStatusSnapshots(olderThan: Instant)
}

suspend fun NodeSnapshotPersisting.fetchNeighborBaseline(nodePublicKey: Bytes, clock: Clock): NeighborBaseline {
    val all = fetchNodeStatusSnapshots(nodePublicKey, null)
    val now = clock.instant()
    val latest = all.lastOrNull()
    val cutoff = if (latest != null && Duration.between(latest.timestamp, now) < NodeSnapshotPolicy.minimumInterval) latest.timestamp else now
    val history = all.filter { it.timestamp < cutoff && it.neighborSnapshots != null }
    return NeighborBaseline(history.lastOrNull(), history.flatMap { it.neighborSnapshots.orEmpty() }.map { it.publicKeyPrefix }.snapshotSet())
}

suspend fun NodeSnapshotPersisting.fetchPreviousStatusSnapshot(nodePublicKey: Bytes, before: Instant): NodeStatusSnapshotDTO? =
    fetchNodeStatusSnapshots(nodePublicKey, null).lastOrNull { it.timestamp < before && it.uptimeSeconds != null }
