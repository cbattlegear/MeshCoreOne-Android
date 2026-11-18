// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/DevicePersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Devices.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.meshcoreone.android.core.contracts.domain.DeviceSessionPersisting
import com.meshcoreone.android.core.contracts.domain.DeviceWriteSnapshot
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID

data class SimulatorDevicePreferenceConflict(val canonical: DeviceDTO, val imported: DeviceDTO)

enum class SimulatorPreferenceChoice { KEEP_CANONICAL, KEEP_IMPORTED }

internal class DeviceRepository(private val context: RoomRepositoryContext) : DeviceSessionPersisting {
    private var revision = 0L
    private var authorityRevision = 0L

    private suspend fun <T> write(
        operation: String,
        changesAuthority: Boolean = true,
        channelIdentity: Boolean = false,
        action: suspend RepositoryTransaction.() -> T,
    ): T {
        val block: suspend RepositoryTransaction.() -> T = {
            val nextRevision = Math.incrementExact(revision)
            val nextAuthority = if (changesAuthority) Math.incrementExact(authorityRevision) else authorityRevision
            afterCommit {
                if (saves) {
                    revision = nextRevision
                    if (changesAuthority) authorityRevision = nextAuthority
                }
            }
            val result = action()
            result
        }
        return if (channelIdentity) context.channelWrite(operation, block = block)
        else context.write(operation, block = block)
    }

    /** Shares this repository's post-commit revisions with related Room mutations. */
    internal suspend fun <T> writeWithDeviceAuthority(
        operation: String,
        action: suspend RepositoryTransaction.() -> T,
    ): T = write(operation, action = action)

    internal fun stageDeviceAuthorityChange(transaction: RepositoryTransaction) {
        val nextRevision = Math.incrementExact(revision)
        val nextAuthority = Math.incrementExact(authorityRevision)
        transaction.afterCommit {
            revision = nextRevision
            authorityRevision = nextAuthority
        }
    }

    override suspend fun fetchDeviceWriteSnapshot(id: UUID): DeviceWriteSnapshot? = context.read("fetchDeviceWriteSnapshot") {
        database.devices().byId(id)?.let { DeviceWriteSnapshot(it.toDTO(), revision, authorityRevision) }
    }

    override suspend fun insertDeviceIfAbsent(dto: DeviceDTO): Boolean = write("insertDeviceIfAbsent") {
        if (database.devices().byId(dto.id) != null) return@write false
        database.devices().upsert(dto.copy(isActive = false).toEntity())
        save()
        true
    }

    private fun RepositoryTransaction.requireSimulatorIdentity(dto: DeviceDTO) {
        if (dto.id != dto.radioId.value || dto.connectionMethods.isNotEmpty()) {
            invalidData("Simulator identity is not valid")
        }
        database.query(SimpleSQLiteQuery(
            "SELECT 1 FROM devices WHERE " +
                "(id COLLATE NOCASE = ? OR radioId COLLATE NOCASE = ? OR publicKey = ?) AND " +
                "(id COLLATE BINARY != UPPER(id) OR radioId COLLATE BINARY != UPPER(radioId)) LIMIT 1",
            arrayOf(dto.id.canonicalString(), dto.radioId.canonicalString, dto.publicKey.toByteArray()),
        )).use { rows ->
            if (rows.moveToFirst()) invalidData("Simulator device identity has a noncanonical UUID")
        }
    }

    private suspend fun RepositoryTransaction.simulatorRows(dto: DeviceDTO): List<DeviceEntity> {
        requireSimulatorIdentity(dto)
        val devices = database.devices()
        val rows = buildList {
            devices.byId(dto.id)?.let(::add)
            addAll(devices.forRadio(dto.radioId.value))
            addAll(devices.forPublicKey(dto.publicKey))
        }.distinctBy { it.id }
        if (rows.any {
                it.radioId != dto.radioId.value || it.publicKey != dto.publicKey || it.connectionMethods.isNotEmpty()
            }) {
            invalidData("Simulator device identity is occupied by a different device")
        }
        return rows
    }

    private suspend fun RepositoryTransaction.expectSimulatorRow(dto: DeviceDTO, expected: DeviceEntity) {
        if (simulatorRows(dto).singleOrNull() != expected) {
            invalidData("Simulator device reconciliation did not persist the expected identity")
        }
    }

    internal suspend fun saveSimulatorDevice(dto: DeviceDTO) = write("saveSimulatorDevice") {
        val related = simulatorRows(dto)
        if (related.size > 1) {
            invalidData("Simulator device preferences require an explicit recovery choice")
        }
        val existing = related.singleOrNull()
        if (existing?.id == dto.id) return@write
        if (existing?.isActive == true) invalidData("Cannot replace an active restored simulator identity")

        val replacement = existing?.copy(id = dto.id) ?: dto.copy(isActive = false).toEntity()
        database.devices().insert(replacement)
        if (existing != null) {
            if (database.devices().byId(existing.id) != existing || database.devices().delete(existing.id) != 1) {
                invalidData("Restored simulator device changed during reconciliation")
            }
        }
        expectSimulatorRow(dto, replacement)
        beforeCommit { expectSimulatorRow(dto, replacement) }
        save()
    }

    private suspend fun RepositoryTransaction.simulatorConflict(dto: DeviceDTO): SimulatorDevicePreferenceConflict? {
        val rows = simulatorRows(dto)
        if (rows.size <= 1) return null
        if (rows.size != 2) invalidData("Simulator identity has multiple restored devices")
        val canonical = rows.singleOrNull { it.id == dto.id }
            ?: invalidData("Simulator identity has multiple restored devices")
        val imported = rows.single { it.id != dto.id }
        if (imported.isActive) invalidData("Cannot retire an active restored simulator identity")
        return SimulatorDevicePreferenceConflict(canonical.toDTO(), imported.toDTO())
    }

    internal suspend fun simulatorDevicePreferenceConflict(dto: DeviceDTO): SimulatorDevicePreferenceConflict? =
        context.read("simulatorDevicePreferenceConflict") { simulatorConflict(dto) }

    internal suspend fun resolveSimulatorDevicePreferenceConflict(
        dto: DeviceDTO,
        expected: SimulatorDevicePreferenceConflict,
        choice: SimulatorPreferenceChoice,
    ) = write("resolveSimulatorDevicePreferenceConflict") {
        val current = simulatorConflict(dto)
        if (current != expected) invalidData("Simulator preference choice is stale")
        val canonical = checkNotNull(current).canonical
        val imported = current.imported
        val selected = when (choice) {
            SimulatorPreferenceChoice.KEEP_CANONICAL -> canonical
            SimulatorPreferenceChoice.KEEP_IMPORTED -> imported
        }
        val replacement = selected.copy(
            id = canonical.id,
            isActive = canonical.isActive,
            lastConnected = canonical.lastConnected,
        ).toEntity()
        database.devices().upsert(replacement)
        if (database.devices().byId(imported.id)?.toDTO() != imported ||
            database.devices().delete(imported.id) != 1
        ) invalidData("Restored simulator device changed during recovery")
        expectSimulatorRow(dto, replacement)
        beforeCommit { expectSimulatorRow(dto, replacement) }
        save()
    }

    override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean =
        saveDeviceIfUnchanged(dto, expected) { true }

    override suspend fun saveDeviceIfUnchanged(
        dto: DeviceDTO,
        expected: DeviceWriteSnapshot,
        isCurrent: () -> Boolean,
    ): Boolean = try {
        context.guardedWrite("saveDeviceIfUnchanged", false, guard = { isCurrent() }) {
            val existing = database.devices().byId(dto.id) ?: return@guardedWrite false
            if (
                revision != expected.revision || authorityRevision != expected.authorityRevision ||
                existing.toDTO() != expected.device || dto.id != expected.device.id ||
                dto.radioId != expected.device.radioId
            ) return@guardedWrite false
            if (dto.isActive != expected.device.isActive || dto.lastConnected != expected.device.lastConnected) return@guardedWrite false
            if (!isCurrent()) throw RetiredDeviceWrite()
            database.devices().upsert(existing.applying(dto))
            if (!isCurrent()) throw RetiredDeviceWrite()
            save()
            val nextRevision = Math.incrementExact(revision)
            val nextAuthority = if (dto.publicKey != expected.device.publicKey) Math.incrementExact(authorityRevision) else authorityRevision
            afterCommit {
                revision = nextRevision
                authorityRevision = nextAuthority
            }
            true
        }
    } catch (_: RetiredDeviceWrite) {
        false
    }

    private class RetiredDeviceWrite : Exception()

    override suspend fun fetchDevice(id: UUID): DeviceDTO? = context.read("fetchDeviceById") {
        database.devices().byId(id)?.toDTO()
    }

    override suspend fun fetchDevice(radioId: RadioId): DeviceDTO? = context.read("fetchDeviceByRadio") {
        database.devices().forRadio(radioId.value).firstOrNull()?.toDTO()
    }

    override suspend fun fetchDevice(publicKey: Bytes): DeviceDTO? = context.read("fetchDeviceByPublicKey") {
        database.devices().forPublicKey(publicKey).firstOrNull()?.toDTO()
    }

    override suspend fun fetchDevices(): SnapshotList<DeviceDTO> = context.read("fetchDevices") {
        database.devices().all().map { it.toDTO() }.snapshot()
    }

    override suspend fun fetchActiveDevice(): DeviceDTO? = context.read("fetchActiveDevice") {
        database.devices().active().firstOrNull()?.toDTO()
    }

    override suspend fun saveDevice(dto: DeviceDTO) = write("saveDevice") {
        val existing = database.devices().byId(dto.id)
        database.devices().upsert(existing?.applying(dto) ?: dto.toEntity())
        save()
    }

    override suspend fun setActiveDevice(id: UUID) = write("setActiveDevice") {
        activate(id)
        save()
    }

    override suspend fun activateDeviceIfCurrent(id: UUID, isCurrent: () -> Boolean): Boolean =
        write("activateDeviceIfCurrent") {
            if (!isCurrent()) return@write false
            if (database.devices().byId(id) == null) throw PersistenceStoreException(PersistenceStoreError.DeviceNotFound)
            activate(id)
            save()
            true
        }

    private suspend fun RepositoryTransaction.activate(id: UUID) {
        for (device in database.devices().all()) {
            database.devices().upsert(device.copy(
                isActive = device.id == id,
                lastConnected = if (device.id == id) StoredInstant.from(clock.instant()) else device.lastConnected,
            ))
        }
    }

    override suspend fun updateDeviceLastContactSync(radioId: RadioId, timestamp: UInt) =
        write("updateDeviceLastContactSync", changesAuthority = false) {
            val row = database.devices().forRadio(radioId.value).firstOrNull()
                ?: throw PersistenceStoreException(PersistenceStoreError.DeviceNotFound)
            database.devices().upsert(row.copy(lastContactSync = timestamp.toLong()))
            save()
        }

    override suspend fun addDeviceKnownRegion(radioId: RadioId, region: String) =
        write("addDeviceKnownRegion", changesAuthority = false) {
            val row = database.devices().forRadio(radioId.value).firstOrNull()
                ?: throw PersistenceStoreException(PersistenceStoreError.DeviceNotFound)
            if (region !in row.knownRegions) {
                database.devices().upsert(row.copy(knownRegions = (row.knownRegions + region).snapshot()))
                save()
            }
        }

    override suspend fun addDeviceKnownRegionIfCurrent(
        id: UUID,
        radioId: RadioId,
        region: String,
        isCurrent: () -> Boolean,
    ): Boolean = changeKnownRegionIfCurrent(id, radioId, region, remove = false, isCurrent = isCurrent)

    override suspend fun removeDeviceKnownRegion(radioId: RadioId, region: String) =
        write("removeDeviceKnownRegion", changesAuthority = false) {
            val row = database.devices().forRadio(radioId.value).firstOrNull()
                ?: throw PersistenceStoreException(PersistenceStoreError.DeviceNotFound)
            database.devices().upsert(row.copy(
                knownRegions = row.knownRegions.filterNot { it == region }.snapshot(),
                defaultFloodScopeName = row.defaultFloodScopeName.takeUnless { it == region },
            ))
            for (channel in database.channels().forRadio(radioId.value)) {
                if (channel.toDTO().floodScope == ChannelFloodScope.Region(region)) {
                    database.channels().setFloodScope(radioId.value, channel.id, "inherit", null)
                }
            }
            save()
        }

    override suspend fun removeDeviceKnownRegionIfCurrent(
        id: UUID,
        radioId: RadioId,
        region: String,
        isCurrent: () -> Boolean,
    ): Boolean = changeKnownRegionIfCurrent(id, radioId, region, remove = true, isCurrent = isCurrent)

    private suspend fun changeKnownRegionIfCurrent(
        id: UUID,
        radioId: RadioId,
        region: String,
        remove: Boolean,
        isCurrent: () -> Boolean,
    ): Boolean = try {
        val operation = if (remove) "removeDeviceKnownRegionIfCurrent" else "addDeviceKnownRegionIfCurrent"
        context.guardedWrite(operation, false, guard = { isCurrent() }) {
            val row = database.devices().byId(id) ?: return@guardedWrite false
            if (row.radioId != radioId.value) return@guardedWrite false
            if (!remove && region in row.knownRegions) return@guardedWrite true
            if (remove) {
                database.devices().upsert(row.copy(
                    knownRegions = row.knownRegions.filterNot { it == region }.snapshot(),
                    defaultFloodScopeName = row.defaultFloodScopeName.takeUnless { it == region },
                ))
                for (channel in database.channels().forRadio(radioId.value)) {
                    if (channel.toDTO().floodScope == ChannelFloodScope.Region(region)) {
                        database.channels().setFloodScope(radioId.value, channel.id, "inherit", null)
                    }
                }
            } else if (region !in row.knownRegions) {
                database.devices().upsert(row.copy(knownRegions = (row.knownRegions + region).snapshot()))
            }
            if (!isCurrent()) throw RetiredDeviceWrite()
            val nextRevision = Math.incrementExact(revision)
            afterCommit { revision = nextRevision }
            save()
            true
        }
    } catch (_: RetiredDeviceWrite) {
        false
    }

    override suspend fun deleteDevice(id: UUID) = write("deleteDevice") {
        database.devices().delete(id)
        save()
    }

    override suspend fun demoteDeviceToGhost(id: UUID) = write("demoteDeviceToGhost") {
        val row = database.devices().byId(id) ?: return@write
        database.devices().delete(id)
        database.devices().upsert(row.copy(
            id = UUID.randomUUID(), isActive = false, connectionMethods = SnapshotList.empty(),
        ))
        save()
    }

    override suspend fun reconcileGhostIdentity(currentDeviceID: UUID, newPublicKey: Bytes): RadioId? =
        write("reconcileGhostIdentity") {
            val ghost = database.devices().forPublicKey(newPublicKey)
                .firstOrNull { it.id != currentDeviceID && !it.isActive } ?: return@write null
            if (ghost.connectionMethods.any { it.isBluetooth }) return@write null
            val current = database.devices().byId(currentDeviceID) ?: return@write null
            val methods = current.connectionMethods.toMutableList()
            for (method in ghost.connectionMethods) {
                if (!method.isBluetooth && methods.none { it.id == method.id }) methods += method
            }
            database.devices().upsert(current.copy(
                radioId = ghost.radioId, publicKey = newPublicKey, connectionMethods = methods.snapshot(),
            ))
            database.devices().delete(ghost.id)
            save()
            RadioId(ghost.radioId)
        }

    override suspend fun deleteDeviceData(id: UUID) = write("deleteDeviceData", channelIdentity = true) {
        deleteAllDeviceData(id)
        save()
    }

    override suspend fun deleteDeviceAndData(id: UUID) = write("deleteDeviceAndData", channelIdentity = true) {
        deleteAllDeviceData(id)
        database.devices().delete(id)
        save()
    }

    private suspend fun RepositoryTransaction.deleteAllDeviceData(id: UUID) {
        val device = database.devices().byId(id) ?: return
        val radioId = RadioId(device.radioId)
        requireCanonicalRxKeys(radioId)
        database.channelSubmissions().redactRadio(radioId.value)
        database.reactions().clearRadio(radioId.value)
        val sessions = database.sessions().forRadio(radioId.value)
        for (session in sessions) {
            database.roomMessages().deleteForSession(radioId.value, session.id)
            database.sessions().delete(radioId.value, session.id)
        }
        for (key in sessions.map { it.publicKey }) database.nodeSnapshots().deleteIfUnreferenced(key)
        database.blockedSenders().clearRadio(radioId.value)
        database.rxLogs().clearRadio(radioId.value)
        pendingRx.entries.removeAll { it.key.radioId == radioId }
        afterCommit { context.completeRxRetention(radioId) }
        database.discoveredNodes().clearRadio(radioId.value)
        database.contacts().clearRadio(radioId.value)
        val messages = database.messages().backupPageWithoutPreviewBlobs(radioId.value, Long.MAX_VALUE, 0)
        deleteMessageDependents(
            radioId, messages, reactionsByMessage = false, preserveConflictingPending = false,
        )
        database.pendingSends().clearRadio(radioId.value)
        database.queuedChannelAttempts().clearRadio(radioId.value)
        database.messages().clearRadio(radioId.value)
        database.channels().clearRadio(radioId.value)
        database.channelOccupants().clearRadio(radioId.value)
        database.tracePaths().clearRadio(radioId.value)
    }
}
