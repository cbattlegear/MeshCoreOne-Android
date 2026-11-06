// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/DevicePersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Devices.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.DevicePersisting
import com.meshcoreone.android.core.contracts.domain.DeviceEditPersisting
import com.meshcoreone.android.core.contracts.domain.DeviceWriteSnapshot
import com.meshcoreone.android.core.contracts.domain.DeviceWriteConflict
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.bytes.utf8Prefix
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

internal class DeviceRepository(private val context: RoomRepositoryContext) : DevicePersisting, DeviceEditPersisting {
    override val repeatSnapshotAuthority get() = context.repeatSnapshotAuthority
    private class RetiredWrite : RuntimeException()

    private fun RepositoryTransaction.changed(id: UUID, authority: Boolean = true) {
        val nextRevision = checkedIncrement(context.deviceRevisions[id] ?: 0L)
        val nextAuthority = if (authority) checkedIncrement(context.deviceAuthorityRevisions[id] ?: 0L) else null
        afterCommit {
            context.deviceRevisions[id] = nextRevision
            if (nextAuthority != null) context.deviceAuthorityRevisions[id] = nextAuthority
        }
    }

    private suspend fun conditionalWrite(
        operation: String,
        isCurrent: () -> Boolean,
        action: suspend RepositoryTransaction.() -> Boolean,
    ): Boolean = try {
        context.write(operation) {
            if (!isCurrent()) return@write false
            beforeCommit { if (!isCurrent()) throw RetiredWrite() }
            action()
        }
    } catch (_: RetiredWrite) {
        false
    }

    override suspend fun fetchDeviceWriteSnapshot(id: UUID): DeviceWriteSnapshot? = context.read("fetchDeviceWriteSnapshot") {
        database.devices().byId(id)?.let {
            DeviceWriteSnapshot(it.toDTO(), context.deviceRevisions[id] ?: 0L, context.deviceAuthorityRevisions[id] ?: 0L)
        }
    }

    override suspend fun insertDeviceIfAbsent(dto: DeviceDTO): Boolean = repeatSnapshotAuthority.withAdmission(dto.id) {
        context.write("insertDeviceIfAbsent") {
            if (database.devices().byId(dto.id) != null) return@write false
            database.devices().upsert(dto.copy(isActive = false).toEntity())
            changed(dto.id)
            afterCommit {
                repeatSnapshotAuthority.invalidateIdentity(dto.id)
                repeatSnapshotAuthority.observeCommittedClear(dto)
            }
            save()
            true
        }
    }

    override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
        var admissionBusy = false
        val saved = expected.withAuthority {
            repeatSnapshotAuthority.tryWithAdmission(dto.id, ifBusy = { admissionBusy = true; false }) {
                conditionalWrite("saveDeviceIfUnchanged", expected.isCurrent) {
                    val existing = database.devices().byId(dto.id) ?: return@conditionalWrite false
                    if ((context.deviceRevisions[dto.id] ?: 0L) != expected.revision ||
                        (context.deviceAuthorityRevisions[dto.id] ?: 0L) != expected.authorityRevision ||
                        existing.toDTO() != expected.device ||
                        dto.id != expected.device.id || dto.radioId != expected.device.radioId) return@conditionalWrite false
                    if (dto.isActive != expected.device.isActive || dto.lastConnected != expected.device.lastConnected) return@conditionalWrite false
                    val removed = existing.knownRegions.toSet() - dto.knownRegions.toSet()
                    val normalizedDefault = dto.defaultFloodScopeName
                        ?.utf8Prefix(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES)
                    if (normalizedDefault != null && removed.any {
                            it.utf8Prefix(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES) == normalizedDefault
                        } && dto.knownRegions.none {
                            it.utf8Prefix(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES) == normalizedDefault
                        }) {
                        invalidData("Removing the default region requires clearing its default flood scope")
                    }
                    database.devices().upsert(dto.toEntity())
                    resetRemovedRegionScopes(dto.radioId, removed)
                    changed(dto.id, authority = dto.publicKey != expected.device.publicKey)
                    afterCommit {
                        repeatSnapshotAuthority.observeIdentityChange(expected.device, dto)
                        repeatSnapshotAuthority.observeCommittedClear(dto)
                        var receiptFailure: Throwable? = null
                        try {
                            expected.onCommittedSnapshot(DeviceWriteSnapshot(
                                dto, checkNotNull(context.deviceRevisions[dto.id]),
                                context.deviceAuthorityRevisions[dto.id] ?: 0L,
                            ))
                        } catch (failure: Throwable) {
                            receiptFailure = failure
                            throw failure
                        } finally {
                            try {
                                expected.onCommitted(dto)
                            } catch (failure: Throwable) {
                                val first = receiptFailure
                                if (first == null) throw failure
                                if (first !== failure) first.addSuppressed(failure)
                            }
                        }
                    }
                    save()
                    true
                }
            }
        }
        if (admissionBusy) {
            // Never retain runtime authority while waiting for a send that retirement must cancel.
            currentCoroutineContext().ensureActive()
            repeatSnapshotAuthority.withAdmission(dto.id) {}
        }
        return saved
    }

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

    override suspend fun saveDevice(dto: DeviceDTO) = repeatSnapshotAuthority.withAdmission(dto.id) {
        context.write("saveDevice") {
            val existing = database.devices().byId(dto.id)
            val stored = existing?.applying(dto) ?: dto.toEntity()
            database.devices().upsert(stored)
            changed(dto.id)
            afterCommit {
                if (existing == null) repeatSnapshotAuthority.invalidateIdentity(dto.id)
                else repeatSnapshotAuthority.observeIdentityChange(existing.toDTO(), stored.toDTO())
                repeatSnapshotAuthority.observeCommittedClear(stored.toDTO())
            }
            save()
        }
    }

    override suspend fun setActiveDevice(id: UUID) = context.write("setActiveDevice") {
        activate(id)
        save()
    }

    override suspend fun activateDeviceIfCurrent(id: UUID, isCurrent: () -> Boolean): Boolean =
        conditionalWrite("activateDeviceIfCurrent", isCurrent) {
            if (database.devices().byId(id) == null) throw PersistenceStoreException(PersistenceStoreError.DeviceNotFound)
            activate(id)
            save()
            true
        }

    private suspend fun RepositoryTransaction.activate(id: UUID) {
        for (device in database.devices().all()) {
            if (device.id != id && !device.isActive) continue
            database.devices().upsert(device.copy(
                isActive = device.id == id,
                lastConnected = if (device.id == id) StoredInstant.from(clock.instant()) else device.lastConnected,
            ))
            changed(device.id)
        }
    }

    override suspend fun updateDeviceLastContactSync(radioId: RadioId, timestamp: UInt) =
        context.write("updateDeviceLastContactSync") {
            val row = database.devices().forRadio(radioId.value).firstOrNull()
                ?: throw PersistenceStoreException(PersistenceStoreError.DeviceNotFound)
            database.devices().upsert(row.copy(lastContactSync = timestamp.toLong()))
            changed(row.id, authority = false)
            save()
        }

    override suspend fun addDeviceKnownRegion(radioId: RadioId, region: String) =
        context.write("addDeviceKnownRegion") {
            val row = database.devices().forRadio(radioId.value).firstOrNull()
                ?: throw PersistenceStoreException(PersistenceStoreError.DeviceNotFound)
            if (region !in row.knownRegions) {
                database.devices().upsert(row.copy(knownRegions = (row.knownRegions + region).snapshot()))
                changed(row.id, authority = false)
                save()
            }
        }

    override suspend fun removeDeviceKnownRegion(radioId: RadioId, region: String) =
        context.write("removeDeviceKnownRegion") {
            val row = database.devices().forRadio(radioId.value).firstOrNull()
                ?: throw PersistenceStoreException(PersistenceStoreError.DeviceNotFound)
            val remaining = row.knownRegions.filterNot { it == region }.snapshot()
            val normalizedRegion = region.utf8Prefix(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES)
            database.devices().upsert(row.copy(
                knownRegions = remaining,
                defaultFloodScopeName = row.defaultFloodScopeName.takeUnless { selected ->
                    selected?.utf8Prefix(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES) == normalizedRegion &&
                        remaining.none {
                            it.utf8Prefix(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES) == normalizedRegion
                        }
                },
            ))
            resetRemovedRegionScopes(radioId, setOf(region))
            changed(row.id, authority = false)
            save()
        }

    private suspend fun RepositoryTransaction.resetRemovedRegionScopes(radioId: RadioId, removed: Set<String>) {
        if (removed.isEmpty()) return
        for (channel in database.channels().forRadio(radioId.value)) {
            val scope = channel.toDTO().floodScope
            if (scope is ChannelFloodScope.Region && scope.name in removed) {
                database.channels().setFloodScope(radioId.value, channel.id, "inherit", null)
            }
        }
    }

    override suspend fun deleteDevice(id: UUID) = repeatSnapshotAuthority.withAdmission(id) {
        context.write("deleteDevice") {
            database.devices().delete(id)
            changed(id)
            afterCommit { repeatSnapshotAuthority.invalidateIdentity(id) }
            save()
        }
    }

    override suspend fun demoteDeviceToGhost(id: UUID) {
        val ghostId = UUID.randomUUID()
        repeatSnapshotAuthority.withAdmissions(listOf(id, ghostId)) {
            context.write("demoteDeviceToGhost") {
                val row = database.devices().byId(id) ?: return@write
                val ghost = row.copy(id = ghostId, isActive = false, connectionMethods = SnapshotList.empty())
                database.devices().delete(id)
                database.devices().upsert(ghost)
                changed(id)
                changed(ghostId)
                afterCommit { repeatSnapshotAuthority.observeIdentityChange(row.toDTO(), ghost.toDTO()) }
                save()
            }
        }
    }

    override suspend fun reconcileGhostIdentity(currentDeviceID: UUID, newPublicKey: Bytes): RadioId? {
        repeat(8) {
            currentCoroutineContext().ensureActive()
            val selected = context.read("selectGhostIdentity") {
                database.devices().forPublicKey(newPublicKey).firstOrNull { it.id != currentDeviceID && !it.isActive }?.id
            } ?: return null
            val result = repeatSnapshotAuthority.withAdmissions(listOf(currentDeviceID, selected)) {
                context.write("reconcileGhostIdentity") {
                    val ghost = database.devices().forPublicKey(newPublicKey)
                        .firstOrNull { it.id != currentDeviceID && !it.isActive } ?: return@write true to null
                    if (ghost.id != selected) return@write false to null
                    if (ghost.connectionMethods.any { it.isBluetooth }) return@write true to null
                    val current = database.devices().byId(currentDeviceID) ?: return@write true to null
                    val methods = current.connectionMethods.toMutableList()
                    for (method in ghost.connectionMethods) {
                        if (!method.isBluetooth && methods.none { it.id == method.id }) methods += method
                    }
                    val merged = current.copy(
                        radioId = ghost.radioId, publicKey = newPublicKey, connectionMethods = methods.snapshot(),
                    )
                    database.devices().upsert(merged)
                    database.devices().delete(ghost.id)
                    changed(currentDeviceID)
                    changed(ghost.id)
                    afterCommit {
                        repeatSnapshotAuthority.observeIdentityChange(current.toDTO(), merged.toDTO())
                        repeatSnapshotAuthority.invalidateIdentity(ghost.id)
                    }
                    save()
                    true to RadioId(ghost.radioId)
                }
            }
            if (result.first) return result.second
            yield()
        }
        throw DeviceWriteConflict()
    }

    override suspend fun deleteDeviceData(id: UUID) = context.write("deleteDeviceData") {
        deleteAllDeviceData(id)
        changed(id)
        save()
    }

    override suspend fun deleteDeviceAndData(id: UUID) = repeatSnapshotAuthority.withAdmission(id) {
        context.write("deleteDeviceAndData") {
            deleteAllDeviceData(id)
            database.devices().delete(id)
            changed(id)
            afterCommit { repeatSnapshotAuthority.invalidateIdentity(id) }
            save()
        }
    }

    private suspend fun RepositoryTransaction.deleteAllDeviceData(id: UUID) {
        val device = database.devices().byId(id) ?: return
        val radioId = RadioId(device.radioId)
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
        afterCommit { context.forgetRxCount(radioId) }
        database.discoveredNodes().clearRadio(radioId.value)
        database.contacts().clearRadio(radioId.value)
        val messages = database.messages().backupPageWithoutPreviewBlobs(radioId.value, Long.MAX_VALUE, 0)
        deleteMessageDependents(radioId, messages, reactionsByMessage = false)
        database.pendingSends().clearRadio(radioId.value)
        database.messages().clearRadio(radioId.value)
        database.channels().clearRadio(radioId.value)
        database.tracePaths().clearRadio(radioId.value)
    }
}
