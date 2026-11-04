// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/DevicePersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Devices.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.DevicePersisting
import com.meshcoreone.android.core.contracts.domain.DeviceEditPersisting
import com.meshcoreone.android.core.contracts.domain.DeviceWriteSnapshot
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID

internal class DeviceRepository(private val context: RoomRepositoryContext) : DevicePersisting, DeviceEditPersisting {
    private var revision = 0L

    private suspend fun <T> write(operation: String, action: suspend RepositoryTransaction.() -> T): T =
        context.write(operation) {
            val nextRevision = Math.incrementExact(revision)
            val result = action()
            if (saves) afterCommit { revision = nextRevision }
            result
        }

    override suspend fun fetchDeviceWriteSnapshot(id: UUID): DeviceWriteSnapshot? = context.read("fetchDeviceWriteSnapshot") {
        database.devices().byId(id)?.let { DeviceWriteSnapshot(it.toDTO(), revision) }
    }

    override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean =
        write("saveDeviceIfUnchanged") {
            val existing = database.devices().byId(dto.id) ?: return@write false
            if (revision != expected.revision || existing.toDTO() != expected.device ||
                dto.id != expected.device.id || dto.radioId != expected.device.radioId) return@write false
            database.devices().upsert(existing.applying(dto))
            save()
            true
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

    override suspend fun saveDevice(dto: DeviceDTO) = write("saveDevice") {
        val existing = database.devices().byId(dto.id)
        database.devices().upsert(existing?.applying(dto) ?: dto.toEntity())
        save()
    }

    override suspend fun setActiveDevice(id: UUID) = write("setActiveDevice") {
        for (device in database.devices().all()) {
            database.devices().upsert(device.copy(
                isActive = device.id == id,
                lastConnected = if (device.id == id) StoredInstant.from(clock.instant()) else device.lastConnected,
            ))
        }
        save()
    }

    override suspend fun updateDeviceLastContactSync(radioId: RadioId, timestamp: UInt) =
        write("updateDeviceLastContactSync") {
            val row = database.devices().forRadio(radioId.value).firstOrNull()
                ?: throw PersistenceStoreException(PersistenceStoreError.DeviceNotFound)
            database.devices().upsert(row.copy(lastContactSync = timestamp.toLong()))
            save()
        }

    override suspend fun addDeviceKnownRegion(radioId: RadioId, region: String) =
        write("addDeviceKnownRegion") {
            val row = database.devices().forRadio(radioId.value).firstOrNull()
                ?: throw PersistenceStoreException(PersistenceStoreError.DeviceNotFound)
            if (region !in row.knownRegions) {
                database.devices().upsert(row.copy(knownRegions = (row.knownRegions + region).snapshot()))
                save()
            }
        }

    override suspend fun removeDeviceKnownRegion(radioId: RadioId, region: String) =
        write("removeDeviceKnownRegion") {
            val row = database.devices().forRadio(radioId.value).firstOrNull()
                ?: throw PersistenceStoreException(PersistenceStoreError.DeviceNotFound)
            database.devices().upsert(row.copy(knownRegions = row.knownRegions.filterNot { it == region }.snapshot()))
            for (channel in database.channels().forRadio(radioId.value)) {
                if (channel.toDTO().floodScope == ChannelFloodScope.Region(region)) {
                    database.channels().setFloodScope(radioId.value, channel.id, "inherit", null)
                }
            }
            save()
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

    override suspend fun deleteDeviceData(id: UUID) = write("deleteDeviceData") {
        deleteAllDeviceData(id)
        save()
    }

    override suspend fun deleteDeviceAndData(id: UUID) = write("deleteDeviceAndData") {
        deleteAllDeviceData(id)
        database.devices().delete(id)
        save()
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
