// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+BackupBatchInsert.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.utf8Prefix
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class BackupDiagnosticRestore(
    private val db: MeshCoreDatabase,
    private val accounting: ImportAccounting,
    private val beforeWrite: suspend (BackupModelKind) -> Unit,
) {
    suspend fun restore(
        paths: List<SavedTracePathDTO>,
        blocked: List<BlockedChannelSenderDTO>,
        snapshots: List<NodeStatusSnapshotDTO>,
        discovered: List<DiscoveredNodeDTO>,
        radioMap: Map<RadioId, RadioId>,
    ) {
        val localPaths = db.tracePaths().backupAll().associateBy {
            TraceIdentity(RadioId(it.radioId), it.pathBytes, it.hashSize)
        }.toMutableMap()
        val seenRunIds = db.traceRuns().backupAll().mapTo(hashSetOf()) { it.id }
        for (dto in paths) {
            currentCoroutineContext().ensureActive()
            val key = TraceIdentity(dto.radioId, dto.pathBytes, dto.hashSize)
            val local = localPaths[key]
            val pathId = local?.id ?: dto.id
            if (local == null) {
                val row = dto.toEntity()
                write(BackupModelKind.SAVED_TRACE_PATHS) { db.tracePaths().insert(row) }
                localPaths[key] = row
                accounting.record(BackupModelKind.SAVED_TRACE_PATHS, inserted = 1)
            } else accounting.record(BackupModelKind.SAVED_TRACE_PATHS, skipped = 1)
            var appended = false
            for (run in dto.runs) {
                if (!seenRunIds.add(run.id)) continue
                val row = run.toEntity(dto.radioId, pathId)
                write(BackupModelKind.SAVED_TRACE_PATHS) { db.traceRuns().insert(row) }
                appended = true
            }
            if (local != null && appended) accounting.record(BackupModelKind.SAVED_TRACE_PATHS, merged = 1)
        }
        val knownBlocked = db.blockedSenders().backupAll().mapTo(hashSetOf()) { RadioId(it.radioId) to sourceStringKey(it.name) }
        for (dto in blocked) {
            if (!knownBlocked.add(dto.radioId to sourceStringKey(dto.name))) accounting.record(BackupModelKind.BLOCKED_CHANNEL_SENDERS, skipped = 1)
            else {
                write(BackupModelKind.BLOCKED_CHANNEL_SENDERS) { db.blockedSenders().insert(dto.toEntity()) }
                accounting.record(BackupModelKind.BLOCKED_CHANNEL_SENDERS, inserted = 1)
            }
        }
        val persisted = db.nodeSnapshots().backupAll()
        val knownSnapshots = persisted.mapTo(hashSetOf()) { ownedSnapshotBackupKey(it.toDTO()) }
        val knownIds = persisted.mapTo(hashSetOf()) { it.id }
        val validOwners = db.devices().all().mapTo(hashSetOf()) { RadioId(it.radioId) }
        for (incoming in snapshots) {
            currentCoroutineContext().ensureActive()
            val owner = incoming.selfRadioID?.let { radioMap[it] }
            if (incoming.id in knownIds ||
                (incoming.selfRadioID != null && (owner == null || owner !in validOwners))) {
                accounting.record(BackupModelKind.NODE_STATUS_SNAPSHOTS, skipped = 1)
                continue
            }
            val dto = incoming.copy(selfRadioID = owner)
            if (!knownSnapshots.add(ownedSnapshotBackupKey(dto))) {
                accounting.record(BackupModelKind.NODE_STATUS_SNAPSHOTS, skipped = 1)
                continue
            }
            write(BackupModelKind.NODE_STATUS_SNAPSHOTS) { db.nodeSnapshots().insert(dto.toEntity()) }
            knownIds.add(dto.id)
            accounting.record(BackupModelKind.NODE_STATUS_SNAPSHOTS, inserted = 1)
        }
        insertDiscovered(discovered)
    }

    private suspend fun insertDiscovered(discovered: List<DiscoveredNodeDTO>) {
        val local = db.discoveredNodes().backupAll()
        val existing = local.mapTo(hashSetOf()) { PublicKeyIdentity(RadioId(it.radioId), it.publicKey) }
        val valid = mutableListOf<DiscoveredNodeDTO>()
        for (dto in discovered) {
            currentCoroutineContext().ensureActive()
            if (dto.publicKey.size != ProtocolLimits.PUBLIC_KEY_SIZE) {
                accounting.record(BackupModelKind.DISCOVERED_NODES, skipped = 1)
                continue
            }
            val validFix = Coordinate(dto.latitude, dto.longitude).isValidFix
            valid += dto.copy(
                name = dto.name.utf8Prefix(ProtocolLimits.MAX_USABLE_NAME_BYTES),
                outPath = dto.outPath.prefix(ProtocolLimits.MAX_PATH_SIZE),
                latitude = if (validFix) dto.latitude else 0.0, longitude = if (validFix) dto.longitude else 0.0,
            )
        }
        val dropped = hashSetOf<UUID>()
        val incoming = valid.filter { PublicKeyIdentity(it.radioId, it.publicKey) !in existing }.groupBy { it.radioId }
        for ((radioId, rows) in incoming) {
            val room = maxOf(0L, RoomPersistenceStore.MAX_DISCOVERED_NODES - local.count { it.radioId == radioId.value })
            if (rows.size > room) {
                dropped += rows.sortedByDescending { it.lastHeard }.drop(room.toInt()).map { it.id }
            }
        }
        accounting.record(BackupModelKind.DISCOVERED_NODES, dropped = dropped.size.toLong())
        for (dto in valid) {
            if (dto.id in dropped) continue
            if (!existing.add(PublicKeyIdentity(dto.radioId, dto.publicKey))) {
                accounting.record(BackupModelKind.DISCOVERED_NODES, skipped = 1)
                continue
            }
            write(BackupModelKind.DISCOVERED_NODES) { db.discoveredNodes().insert(dto.copy(id = UUID.randomUUID()).toEntity()) }
            accounting.record(BackupModelKind.DISCOVERED_NODES, inserted = 1)
        }
    }

    private suspend fun write(kind: BackupModelKind, block: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        beforeWrite(kind)
        currentCoroutineContext().ensureActive()
        block()
    }
}
