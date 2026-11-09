// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/TracePathPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/DebugLogPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/DebugLogRetention.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/LinkPreviewPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/RxLogPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/NodeSnapshotPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Diagnostics.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Metadata.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PayloadType
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class DiagnosticRepository(private val context: RoomRepositoryContext) :
    TracePathPersisting, DebugLogPersisting, LinkPreviewPersisting, RxLogPersisting, NodeSnapshotPersisting {
    override suspend fun fetchSavedTracePaths(radioId: RadioId): SnapshotList<SavedTracePathDTO> =
        context.read("fetchSavedTracePaths") {
            database.tracePaths().forRadio(radioId.value).map { path ->
                path.toDTO(database.traceRuns().forPath(radioId.value, path.id).map { it.toDTO() }.snapshot())
            }.snapshot()
        }

    override suspend fun fetchSavedTracePath(key: EntityKey): SavedTracePathDTO? =
        context.read("fetchSavedTracePath") {
            database.tracePaths().byId(key.radioId.value, key.id)?.toDTO(
                database.traceRuns().forPath(key.radioId.value, key.id).map { it.toDTO() }.snapshot(),
            )
        }

    override suspend fun createSavedTracePath(
        radioId: RadioId, name: String, pathBytes: Bytes, hashSize: Long, initialRun: TracePathRunDTO?,
    ): SavedTracePathDTO = context.write("createSavedTracePath") {
        val dto = SavedTracePathDTO(UUID.randomUUID(), radioId, name, pathBytes, hashSize, clock.instant(),
            initialRun?.let { SnapshotList.of(it) } ?: SnapshotList.empty())
        database.tracePaths().upsert(dto.toEntity())
        if (initialRun != null) database.traceRuns().upsert(initialRun.toEntity(radioId, dto.id))
        save()
        dto
    }

    override suspend fun updateSavedTracePathName(key: EntityKey, name: String) =
        context.write("updateSavedTracePathName") {
            val row = database.tracePaths().byId(key.radioId.value, key.id)
                ?: throw PersistenceStoreException(PersistenceStoreError.FetchFailed("SavedTracePath not found"))
            database.tracePaths().upsert(row.copy(name = name))
            save()
        }

    override suspend fun deleteSavedTracePath(key: EntityKey) = context.write("deleteSavedTracePath") {
        if (database.tracePaths().delete(key.radioId.value, key.id) > 0) save()
    }

    override suspend fun appendTracePathRun(path: EntityKey, run: TracePathRunDTO) =
        context.write("appendTracePathRun") {
            if (database.tracePaths().byId(path.radioId.value, path.id) == null) {
                throw PersistenceStoreException(PersistenceStoreError.FetchFailed("SavedTracePath not found"))
            }
            database.traceRuns().upsert(run.toEntity(path.radioId, path.id))
            save()
        }

    override suspend fun saveDebugLogEntries(dtos: SnapshotList<DebugLogEntryDTO>) =
        context.write("saveDebugLogEntries") {
            database.debugLogs().upsert(dtos.map { it.toEntity() })
            save()
        }

    override suspend fun fetchDebugLogEntries(since: Instant, limit: Long): SnapshotList<DebugLogEntryDTO> =
        context.read("fetchDebugLogEntries") {
            queryBounds(limit)
            database.debugLogs().since(since.epochSecond, since.nano, limit).map { it.toDTO() }.snapshot()
        }

    override suspend fun countDebugLogEntries(): Long = context.read("countDebugLogEntries") { database.debugLogs().count() }

    override suspend fun pruneDebugLogEntries(olderThan: Instant, keepCount: Long) =
        context.write("pruneDebugLogEntries") {
            queryBounds(keepCount)
            database.debugLogs().deleteOlderThan(olderThan.epochSecond, olderThan.nano)
            val excess = database.debugLogs().count() - keepCount
            if (excess > 0) for (row in database.debugLogs().oldest(excess)) database.debugLogs().delete(row.id)
            save()
        }

    override suspend fun clearDebugLogEntries() = context.write("clearDebugLogEntries") {
        database.debugLogs().clear()
        save()
    }

    override suspend fun fetchLinkPreview(url: String): LinkPreviewDataDTO? = context.read("fetchLinkPreview") {
        database.linkPreviews().byURL(url)?.toDTO()
    }

    override suspend fun saveLinkPreview(dto: LinkPreviewDataDTO) = context.write("saveLinkPreview") {
        database.linkPreviews().upsert(dto.toEntity())
        save()
    }

    override suspend fun saveRxLogEntry(dto: RxLogEntryDTO) { context.saveRx(dto.toEntity()) }
    override suspend fun flushPendingRxLogEntries() { context.flushRx() }

    override suspend fun fetchRxLogEntries(radioId: RadioId, limit: Long): SnapshotList<RxLogEntryDTO> =
        context.read("fetchRxLogEntries") {
            queryBounds(limit)
            rxRows(radioId).sortedByDescending { it.receivedAt.toInstant() }
                .take(minOf(limit, Int.MAX_VALUE.toLong()).toInt()).map { it.toDTO() }.snapshot()
        }

    override suspend fun countRxLogEntries(radioId: RadioId): Long =
        context.read("countRxLogEntries") { rxRows(radioId).size.toLong() }

    override suspend fun findRxLogEntry(radioId: RadioId, channelIndex: UByte?, senderTimestamp: UInt): RxLogEntryDTO? =
        context.read("findRxLogEntry") {
            rxRows(radioId).filter {
                it.senderTimestamp == senderTimestamp.toLong() &&
                    if (channelIndex != null) it.channelIndex == channelIndex.toLong()
                    else it.channelIndex == null && it.payloadType == PayloadType.TEXT_MESSAGE.rawValue.toLong()
            }.maxByOrNull { it.receivedAt.toInstant() }?.toDTO()
        }

    override suspend fun fetchRxLogEntries(
        radioId: RadioId, channelIndex: UByte, senderTimestamp: UInt,
    ): SnapshotList<RxLogEntryDTO> = context.read("fetchCorrelatedRxLogEntries") {
        rxRows(radioId).filter { it.channelIndex == channelIndex.toLong() && it.senderTimestamp == senderTimestamp.toLong() }
            .sortedBy { it.receivedAt.toInstant() }.map { it.toDTO() }.snapshot()
    }

    override suspend fun findRxLogEntryBySenderPrefix(
        radioId: RadioId, senderPrefixByte: UByte, receivedSince: Instant,
    ): RxLogEntryDTO? = context.read("findRxLogEntryBySenderPrefix") {
        rxRows(radioId).filter {
            it.channelIndex == null && it.payloadType == PayloadType.TEXT_MESSAGE.rawValue.toLong() &&
                it.receivedAt.toInstant() >= receivedSince
        }.sortedByDescending { it.receivedAt.toInstant() }.take(20)
            .firstOrNull { it.packetPayload.size >= 2 && it.packetPayload[1] == senderPrefixByte }?.toDTO()
    }

    override suspend fun clearRxLogEntries(radioId: RadioId) = context.write("clearRxLogEntries") {
        requireCanonicalRxKeys(radioId)
        database.rxLogs().clearRadio(radioId.value)
        pendingRx.entries.removeAll { it.key.radioId == radioId }
        afterCommit { context.setRxCount(radioId, 0) }
        save()
    }

    override suspend fun pruneRxLogEntries(radioId: RadioId, keepCount: Long, pruneThreshold: Long) =
        context.write("pruneRxLogEntries") {
            queryBounds(keepCount)
            queryBounds(pruneThreshold)
            val rows = rxRows(radioId)
            if (rows.size.toLong() <= checkedAdd(keepCount, pruneThreshold)) return@write
            val excess = rows.size.toLong() - keepCount
            for (row in rows.sortedBy { it.receivedAt.toInstant() }.take(excess.toInt())) {
                pendingRx.remove(EntityKey(radioId, row.id))
                database.rxLogs().delete(radioId.value, row.id)
            }
            afterCommit { context.setRxCount(radioId, keepCount) }
            save()
        }

    override suspend fun fetchEntriesWithTransportCode(radioId: RadioId, limit: Long): SnapshotList<RxLogEntryDTO> =
        context.read("fetchEntriesWithTransportCode") {
            queryBounds(limit)
            rxRows(radioId).filter { it.transportCode != null }.sortedByDescending { it.receivedAt.toInstant() }
                .take(minOf(limit, Int.MAX_VALUE.toLong()).toInt()).map { it.toDTO() }.snapshot()
        }

    override suspend fun fetchRecentEntriesByDecryptStatus(
        radioId: RadioId, status: DecryptStatus, since: Instant,
    ): SnapshotList<RxLogEntryDTO> = context.read("fetchRecentEntriesByDecryptStatus") {
        rxRows(radioId).filter { it.decryptStatus == status.rawValue && it.receivedAt.toInstant() >= since }
            .sortedBy { it.receivedAt.toInstant() }.map { it.toDTO() }.snapshot()
    }

    override suspend fun batchUpdateRxLogRegion(radioId: RadioId, updates: SnapshotList<RxLogRegionUpdate>) {
        if (updates.isEmpty()) return
        context.write("batchUpdateRxLogRegion") {
            if (updates.map { it.id }.toSet().size != updates.size) invalidData("Duplicate RX region update ID")
            val byId = rxRows(radioId).associateBy { it.id }
            for (update in updates) {
                val row = byId[update.id] ?: continue
                updateRx(row.copy(regionScope = update.regionScope, regionScopeMatches = update.regionScopeMatches))
            }
            save()
        }
    }

    override suspend fun batchUpdateRxLogDecryption(radioId: RadioId, updates: SnapshotList<RxLogDecryptionUpdate>) =
        context.write("batchUpdateRxLogDecryption") {
            val byId = rxRows(radioId).associateByTo(LinkedHashMap()) { it.id }
            for (update in updates) {
                val row = byId[update.id] ?: continue
                val changed = row.copy(channelIndex = update.channelIndex?.toLong(), channelName = update.channelName,
                    decryptStatus = DecryptStatus.SUCCESS.rawValue, senderTimestamp = update.senderTimestamp?.toLong())
                updateRx(changed)
                byId[update.id] = changed
            }
            save()
        }

    override suspend fun batchUpdateChannelMessageRegion(
        radioId: RadioId, updates: SnapshotList<ChannelRegionUpdate>,
    ): SnapshotList<UUID> = context.write("batchUpdateChannelMessageRegion") {
        val touched = mutableListOf<UUID>()
        for (update in updates) {
            for (row in database.messages().newestForChannel(radioId.value, update.channelIndex.toLong(), -1)) {
                if (row.directionRawValue != MessageDirection.INCOMING.rawValue ||
                    !(row.senderTimestamp == update.senderTimestamp.toLong() ||
                        (row.senderTimestamp == null && row.timestamp == update.senderTimestamp.toLong()))) continue
                database.messages().upsert(row.copy(regionScope = update.regionScope, regionScopeMatches = update.regionScopeMatches))
                touched += row.id
            }
        }
        save()
        touched.snapshot()
    }

    override suspend fun batchUpdateDMMessageRegion(
        radioId: RadioId, updates: SnapshotList<DirectRegionUpdate>,
    ): SnapshotList<UUID> = context.write("batchUpdateDMMessageRegion") {
        val touched = mutableListOf<UUID>()
        for (update in updates) {
            for (candidate in database.messages().backupPageWithoutPreviewBlobs(radioId.value, Long.MAX_VALUE, 0)) {
                if (candidate.channelIndex != null || candidate.directionRawValue != MessageDirection.INCOMING.rawValue ||
                    candidate.senderKeyPrefix?.let { if (it.isEmpty) null else it[0] } != update.senderPrefixByte ||
                    !(candidate.senderTimestamp == update.senderTimestamp.toLong() ||
                        (candidate.senderTimestamp == null && candidate.timestamp == update.senderTimestamp.toLong()))) continue
                val row = requireNotNull(database.messages().byId(radioId.value, candidate.id))
                database.messages().upsert(row.copy(regionScope = update.regionScope, regionScopeMatches = update.regionScopeMatches))
                touched += row.id
            }
        }
        save()
        touched.snapshot()
    }

    override suspend fun saveNodeStatusSnapshot(
        nodePublicKey: Bytes, batteryMillivolts: UShort?, lastSNR: Double?, lastRSSI: Short?, noiseFloor: Short?,
        uptimeSeconds: UInt?, rxAirtimeSeconds: UInt?, packetsSent: UInt?, packetsReceived: UInt?, receiveErrors: UInt?,
        postedCount: UShort?, postPushCount: UShort?,
    ): UUID = saveNodeStatusSnapshot(nodePublicKey, NodeStatusMetrics(
        batteryMillivolts, lastSNR, lastRSSI, noiseFloor, uptimeSeconds, rxAirtimeSeconds,
        packetsSent, packetsReceived, receiveErrors, postedCount = postedCount, postPushCount = postPushCount,
    ))

    override suspend fun saveNodeStatusSnapshot(nodePublicKey: Bytes, status: NodeStatusMetrics): UUID =
        context.write("saveNodeStatusSnapshot") {
            val dto = NodeStatusSnapshotDTO(timestamp = clock.instant(), nodePublicKey = nodePublicKey).applying(status)
            database.nodeSnapshots().upsert(dto.toEntity())
            save()
            dto.id
        }

    override suspend fun fetchLatestNodeStatusSnapshot(nodePublicKey: Bytes): NodeStatusSnapshotDTO? =
        context.read("fetchLatestNodeStatusSnapshot") { database.nodeSnapshots().latest(nodePublicKey)?.toDTO() }

    override suspend fun fetchNodeStatusSnapshots(nodePublicKey: Bytes, since: Instant?): SnapshotList<NodeStatusSnapshotDTO> =
        context.read("fetchNodeStatusSnapshots") {
            val rows = if (since == null) database.nodeSnapshots().history(nodePublicKey)
                else database.nodeSnapshots().historySince(nodePublicKey, since.epochSecond, since.nano)
            rows.map { it.toDTO() }.snapshot()
        }

    override suspend fun updateSnapshotNeighbors(id: UUID, neighbors: SnapshotList<NeighborSnapshotEntry>) =
        context.write("updateSnapshotNeighbors") {
            val row = database.nodeSnapshots().byId(id) ?: return@write
            database.nodeSnapshots().upsert(row.copy(neighborSnapshots = neighbors))
            save()
        }

    override suspend fun updateSnapshotTelemetry(id: UUID, telemetry: SnapshotList<TelemetrySnapshotEntry>) =
        context.write("updateSnapshotTelemetry") {
            val row = database.nodeSnapshots().byId(id) ?: return@write
            database.nodeSnapshots().upsert(row.copy(telemetryEntries = telemetry))
            save()
        }

    override suspend fun saveTelemetryOnlySnapshot(
        nodePublicKey: Bytes, telemetryEntries: SnapshotList<TelemetrySnapshotEntry>,
    ): UUID = context.write("saveTelemetryOnlySnapshot") {
        val dto = NodeStatusSnapshotDTO(timestamp = clock.instant(), nodePublicKey = nodePublicKey, telemetryEntries = telemetryEntries)
        database.nodeSnapshots().upsert(dto.toEntity())
        save()
        dto.id
    }

    override suspend fun recordNodeStatusSnapshot(
        nodePublicKey: Bytes, status: NodeStatusMetrics?, telemetry: SnapshotList<TelemetrySnapshotEntry>?,
        neighbors: SnapshotList<NeighborSnapshotEntry>?, location: NodeLocationFix?,
    ): UUID = context.write("recordNodeStatusSnapshot") {
        val latest = database.nodeSnapshots().latest(nodePublicKey)?.toDTO()
        val now = clock.instant()
        var dto = if (latest != null && Duration.between(latest.timestamp, now) < NodeSnapshotPolicy.minimumInterval) latest
            else NodeStatusSnapshotDTO(timestamp = now, nodePublicKey = nodePublicKey)
        if (status != null && dto.uptimeSeconds == null) dto = dto.applying(status)
        if (telemetry != null) dto = dto.copy(telemetryEntries = telemetry)
        if (neighbors != null) dto = dto.copy(neighborSnapshots = neighbors)
        if (location != null && dto.latitude == null) {
            dto = dto.copy(latitude = location.latitude, longitude = location.longitude, altitude = location.altitude)
        }
        database.nodeSnapshots().upsert(dto.toEntity())
        save()
        dto.id
    }

    override suspend fun deleteOldNodeStatusSnapshots(olderThan: Instant) =
        context.write("deleteOldNodeStatusSnapshots") {
            database.nodeSnapshots().deleteOlderThan(olderThan.epochSecond, olderThan.nano)
            save()
        }
}
