// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/DevicePersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/ContactPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/ChannelPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Devices.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Contacts.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Channels.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A process-owned optimistic write ticket; device writers invalidate it inside their transaction. */
data class DeviceWriteSnapshot(
    val device: DeviceDTO,
    val revision: Long,
    val authorityRevision: Long = revision,
)

class DeviceWriteConflict : IllegalStateException("Device write authority changed")

/** Overlay changes published since [before]; OCV callbacks additionally supply revision-based authority. */
fun DeviceDTO.preservingChanges(before: DeviceDTO, live: DeviceDTO, ocvChanged: Boolean = false): DeviceDTO {
    require(id == before.id && id == live.id && radioId == before.radioId && radioId == live.radioId)
    fun <T> changed(old: T, current: T, saved: T): T = if (old != current) current else saved
    return copy(
        publicKey = changed(before.publicKey, live.publicKey, publicKey),
        nodeName = changed(before.nodeName, live.nodeName, nodeName),
        firmwareVersion = changed(before.firmwareVersion, live.firmwareVersion, firmwareVersion),
        firmwareVersionString = changed(before.firmwareVersionString, live.firmwareVersionString, firmwareVersionString),
        manufacturerName = changed(before.manufacturerName, live.manufacturerName, manufacturerName),
        buildDate = changed(before.buildDate, live.buildDate, buildDate),
        maxContacts = changed(before.maxContacts, live.maxContacts, maxContacts),
        maxChannels = changed(before.maxChannels, live.maxChannels, maxChannels),
        frequency = changed(before.frequency, live.frequency, frequency),
        bandwidth = changed(before.bandwidth, live.bandwidth, bandwidth),
        spreadingFactor = changed(before.spreadingFactor, live.spreadingFactor, spreadingFactor),
        codingRate = changed(before.codingRate, live.codingRate, codingRate),
        txPower = changed(before.txPower, live.txPower, txPower),
        maxTxPower = changed(before.maxTxPower, live.maxTxPower, maxTxPower),
        latitude = changed(before.latitude, live.latitude, latitude),
        longitude = changed(before.longitude, live.longitude, longitude),
        blePin = changed(before.blePin, live.blePin, blePin),
        clientRepeat = changed(before.clientRepeat, live.clientRepeat, clientRepeat),
        pathHashMode = changed(before.pathHashMode, live.pathHashMode, pathHashMode),
        defaultFloodScopeName = changed(before.defaultFloodScopeName, live.defaultFloodScopeName, defaultFloodScopeName),
        preRepeatFrequency = changed(before.preRepeatFrequency, live.preRepeatFrequency, preRepeatFrequency),
        preRepeatBandwidth = changed(before.preRepeatBandwidth, live.preRepeatBandwidth, preRepeatBandwidth),
        preRepeatSpreadingFactor = changed(before.preRepeatSpreadingFactor, live.preRepeatSpreadingFactor, preRepeatSpreadingFactor),
        preRepeatCodingRate = changed(before.preRepeatCodingRate, live.preRepeatCodingRate, preRepeatCodingRate),
        manualAddContacts = changed(before.manualAddContacts, live.manualAddContacts, manualAddContacts),
        autoAddConfig = changed(before.autoAddConfig, live.autoAddConfig, autoAddConfig),
        autoAddMaxHops = changed(before.autoAddMaxHops, live.autoAddMaxHops, autoAddMaxHops),
        multiAcks = changed(before.multiAcks, live.multiAcks, multiAcks),
        telemetryModeBase = changed(before.telemetryModeBase, live.telemetryModeBase, telemetryModeBase),
        telemetryModeLoc = changed(before.telemetryModeLoc, live.telemetryModeLoc, telemetryModeLoc),
        telemetryModeEnv = changed(before.telemetryModeEnv, live.telemetryModeEnv, telemetryModeEnv),
        advertLocationPolicy = changed(before.advertLocationPolicy, live.advertLocationPolicy, advertLocationPolicy),
        lastConnected = changed(before.lastConnected, live.lastConnected, lastConnected),
        lastContactSync = changed(before.lastContactSync, live.lastContactSync, lastContactSync),
        isActive = changed(before.isActive, live.isActive, isActive),
        ocvPreset = if (ocvChanged) live.ocvPreset else changed(before.ocvPreset, live.ocvPreset, ocvPreset),
        appliedRadioPresetID = changed(before.appliedRadioPresetID, live.appliedRadioPresetID, appliedRadioPresetID),
        customOCVArrayString = if (ocvChanged) live.customOCVArrayString
            else changed(before.customOCVArrayString, live.customOCVArrayString, customOCVArrayString),
        connectionMethods = changed(before.connectionMethods, live.connectionMethods, connectionMethods),
        knownRegions = changed(before.knownRegions, live.knownRegions, knownRegions),
    )
}

/**
 * Rebase an intended-field edit on the matching persisted value, never on a previously read live DTO.
 * Full replacements/activation invalidate authority; targeted concurrent edits may be retried.
 */
private suspend fun editDeviceWith(
    persistence: DeviceEditPersisting,
    id: UUID,
    isCurrent: () -> Boolean = { true },
    onSaveFailure: (PersistenceStoreException) -> Nothing = { throw it },
    transform: (DeviceDTO) -> DeviceDTO,
): DeviceDTO? {
    var authority: Long? = null
    repeat(16) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return null
        val snapshot = persistence.fetchDeviceWriteSnapshot(id) ?: return null
        if (!isCurrent()) return null
        if (authority != null && snapshot.authorityRevision != authority) throw DeviceWriteConflict()
        authority = snapshot.authorityRevision
        val updated = transform(snapshot.device)
        require(updated.id == snapshot.device.id && updated.radioId == snapshot.device.radioId)
        require(updated.isActive == snapshot.device.isActive && updated.lastConnected == snapshot.device.lastConnected)
        val committed = try {
            persistence.saveDeviceIfUnchanged(updated, snapshot, isCurrent)
        } catch (failure: PersistenceStoreException) {
            onSaveFailure(failure)
        }
        if (committed) return updated
    }
    throw DeviceWriteConflict()
}

suspend fun DeviceSessionPersisting.editDevice(
    id: UUID,
    isCurrent: () -> Boolean = { true },
    onSaveFailure: (PersistenceStoreException) -> Nothing = { throw it },
    transform: (DeviceDTO) -> DeviceDTO,
): DeviceDTO? = editDeviceWith(this, id, isCurrent, onSaveFailure, transform)

suspend fun DevicePersisting.editDevice(
    id: UUID,
    isCurrent: () -> Boolean = { true },
    onSaveFailure: (PersistenceStoreException) -> Nothing = { throw it },
    transform: (DeviceDTO) -> DeviceDTO,
): DeviceDTO? {
    val persistence = this as? DeviceEditPersisting
        ?: throw UnsupportedOperationException("Device edits require versioned device persistence")
    return editDeviceWith(persistence, id, isCurrent, onSaveFailure, transform)
}

interface DeviceEditPersisting {
    /** Initialization only: atomically create an inactive row, never replace an existing device. */
    suspend fun insertDeviceIfAbsent(dto: DeviceDTO): Boolean
    /** Evaluate connection ownership inside the serialized activation transaction, not before waiting. */
    suspend fun activateDeviceIfCurrent(id: UUID, isCurrent: () -> Boolean): Boolean
    suspend fun fetchDeviceWriteSnapshot(id: UUID): DeviceWriteSnapshot?
    suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean
    /** Implementations must recheck [isCurrent] at their conditional-save commit boundary. */
    suspend fun saveDeviceIfUnchanged(
        dto: DeviceDTO,
        expected: DeviceWriteSnapshot,
        isCurrent: () -> Boolean,
    ): Boolean
}

/** Persistence required by a live connection: ordinary device data plus transactional write authority. */
interface DeviceSessionPersisting : DevicePersisting, DeviceEditPersisting {
    override suspend fun saveDeviceIfUnchanged(
        dto: DeviceDTO,
        expected: DeviceWriteSnapshot,
        isCurrent: () -> Boolean,
    ): Boolean

    suspend fun addDeviceKnownRegionIfCurrent(
        id: UUID,
        radioId: RadioId,
        region: String,
        isCurrent: () -> Boolean,
    ): Boolean

    suspend fun removeDeviceKnownRegionIfCurrent(
        id: UUID,
        radioId: RadioId,
        region: String,
        isCurrent: () -> Boolean,
    ): Boolean
}

suspend fun DevicePersisting.activateDeviceIfCurrent(id: UUID, isCurrent: () -> Boolean): Boolean =
    (this as? DeviceEditPersisting
        ?: throw UnsupportedOperationException("Connection activation requires versioned device persistence"))
        .activateDeviceIfCurrent(id, isCurrent)

interface DevicePersisting {
    suspend fun fetchDevice(id: UUID): DeviceDTO?
    suspend fun fetchDevice(radioId: RadioId): DeviceDTO?
    suspend fun fetchDevice(publicKey: Bytes): DeviceDTO?
    suspend fun fetchDevices(): SnapshotList<DeviceDTO>
    suspend fun fetchActiveDevice(): DeviceDTO?
    suspend fun saveDevice(dto: DeviceDTO)
    suspend fun setActiveDevice(id: UUID)
    suspend fun updateDeviceLastContactSync(radioId: RadioId, timestamp: UInt)
    suspend fun addDeviceKnownRegion(radioId: RadioId, region: String)
    suspend fun removeDeviceKnownRegion(radioId: RadioId, region: String)
    suspend fun deleteDeviceData(id: UUID)
    suspend fun deleteDevice(id: UUID)
    suspend fun demoteDeviceToGhost(id: UUID)
    suspend fun deleteDeviceAndData(id: UUID)
    suspend fun reconcileGhostIdentity(currentDeviceID: UUID, newPublicKey: Bytes): RadioId?
}

interface ContactPersisting {
    suspend fun fetchContacts(radioId: RadioId): SnapshotList<ContactDTO>
    suspend fun fetchConversations(radioId: RadioId): SnapshotList<ContactDTO>
    suspend fun fetchContact(key: EntityKey): ContactDTO?
    suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO?
    suspend fun fetchContactByPrefix(radioId: RadioId, publicKeyPrefix: Bytes): ContactDTO?
    suspend fun fetchContactPublicKeysByPrefix(radioId: RadioId): SnapshotMap<UByte, SnapshotList<Bytes>>
    suspend fun findContactNameByKeyPrefix(prefix: Bytes): String?
    suspend fun findContactByPublicKey(publicKey: Bytes): ContactDTO?
    suspend fun saveContact(radioId: RadioId, frame: ContactFrame): ContactSaveResult
    /**
     * Atomic accepted-write upsert preserving newer timestamps and equal-timestamp frames changed
     * since [expectedFrame] was read. Returns null when a newer frame was retained rather than
     * applying this one. Local preferences/activity are retained even when applying.
     */
    suspend fun saveContactPreservingNewer(
        radioId: RadioId, frame: ContactFrame, expectedFrame: ContactFrame? = null,
    ): ContactSaveResult?
    suspend fun saveContact(dto: ContactDTO)
    /** Atomically changes only the avatar of an existing matching identity; null clears it. Never inserts. */
    suspend fun updateContactAvatar(
        key: EntityKey, expectedPublicKey: Bytes, imageData: Bytes?,
    ): ContactAvatarUpdateResult
    /** Changes only the favorite preference and flag bit on an existing matching identity. */
    suspend fun updateContactFavorite(
        key: EntityKey, expectedPublicKey: Bytes, isFavorite: Boolean,
    ): ContactFavoriteUpdateResult
    /** Applies only the selected local fields to an existing identity; reports the committed block transition. */
    suspend fun updateContactLocalFields(
        key: EntityKey, expectedPublicKey: Bytes, update: ContactLocalUpdate,
    ): ContactLocalUpdateResult
    suspend fun batchSaveContacts(radioId: RadioId, frames: SnapshotList<ContactFrame>): Long
    suspend fun deleteContact(key: EntityKey)
    suspend fun deleteContacts(
        radioId: RadioId, publicKeys: SnapshotSet<Bytes>, skippingPublicKeys: () -> SnapshotSet<Bytes> = { SnapshotSet.empty() },
    ): SnapshotList<UUID>
    suspend fun deleteContactIfUnreferenced(key: EntityKey)
    suspend fun adoptOrphanedDirectMessages(radioId: RadioId, contacts: SnapshotList<ContactIdentity>): SnapshotMap<UUID, Long>
    suspend fun touchContactHeard(radioId: RadioId, publicKey: Bytes, date: Instant): Boolean
    suspend fun updateContactLastMessage(key: EntityKey, date: Instant?)
    suspend fun recomputeContactLastMessageDate(key: EntityKey): Instant?
    suspend fun incrementUnreadCount(key: EntityKey)
    suspend fun clearUnreadCount(key: EntityKey)
    suspend fun markMentionSeen(key: EntityKey)
    suspend fun incrementUnreadMentionCount(key: EntityKey)
    suspend fun decrementUnreadMentionCount(key: EntityKey)
    suspend fun clearUnreadMentionCount(key: EntityKey)
    suspend fun fetchUnseenMentionIDs(key: EntityKey): SnapshotList<UUID>
    suspend fun setContactMuted(key: EntityKey, isMuted: Boolean)
    suspend fun deleteMessagesForContact(key: EntityKey)
    suspend fun deleteChannelMessages(senderName: String, radioId: RadioId)
    suspend fun fetchBlockedContacts(radioId: RadioId): SnapshotList<ContactDTO>
    suspend fun saveBlockedChannelSender(dto: BlockedChannelSenderDTO)
    suspend fun deleteBlockedChannelSender(radioId: RadioId, name: String)
    suspend fun fetchBlockedChannelSenders(radioId: RadioId): SnapshotList<BlockedChannelSenderDTO>
    suspend fun fetchContactPublicKeys(radioId: RadioId): SnapshotSet<Bytes>
}

interface ChannelPersisting {
    suspend fun <T> withChannelOperation(block: suspend (ChannelPersisting, ChannelIdentityWriteScope) -> T): T
    // Unknown/legacy/ambiguous slots have no proof. This read is not an atomic dispatch fence.
    suspend fun fetchChannelOccupantID(radioId: RadioId, index: UByte): UUID?
    suspend fun fetchChannelOccupantSnapshot(radioId: RadioId, index: UByte): ChannelOccupantSnapshot?
    suspend fun invalidateChannelOccupant(radioId: RadioId, index: UByte)
    /** Scoped facade only: commit unresolved authority before SET; only authoritative radio reconciliation clears it. */
    suspend fun beginChannelMutation(radioId: RadioId, index: UByte)
    suspend fun fetchChannels(radioId: RadioId): SnapshotList<ChannelDTO>
    suspend fun fetchChannel(radioId: RadioId, index: UByte): ChannelDTO?
    suspend fun fetchChannel(key: EntityKey): ChannelDTO?
    suspend fun saveChannel(radioId: RadioId, info: ChannelInfo): UUID
    suspend fun saveChannel(dto: ChannelDTO)
    suspend fun batchSaveChannels(
        radioId: RadioId, configured: SnapshotList<ChannelInfo>, unconfiguredIndices: SnapshotList<UByte>, pruneBeyond: UByte?,
    ): SnapshotList<ChannelDTO>
    suspend fun deleteChannel(key: EntityKey)
    suspend fun deleteMessagesForChannel(radioId: RadioId, channelIndex: UByte)
    suspend fun updateChannelLastMessage(key: EntityKey, date: Instant?)
    suspend fun incrementChannelUnreadCount(key: EntityKey)
    suspend fun clearChannelUnreadCount(key: EntityKey)
    suspend fun clearChannelUnreadCount(radioId: RadioId, index: UByte)
    suspend fun setChannelMuted(key: EntityKey, isMuted: Boolean)
    suspend fun setChannelNotificationLevel(key: EntityKey, level: NotificationLevel)
    suspend fun setSessionNotificationLevel(key: EntityKey, level: NotificationLevel)
    suspend fun setChannelFavorite(key: EntityKey, isFavorite: Boolean)
    suspend fun setChannelFloodScope(key: EntityKey, floodScope: ChannelFloodScope)
    suspend fun incrementChannelUnreadMentionCount(key: EntityKey)
    suspend fun decrementChannelUnreadMentionCount(key: EntityKey)
    suspend fun clearChannelUnreadMentionCount(key: EntityKey)
    suspend fun fetchUnseenChannelMentionIDs(radioId: RadioId, channelIndex: UByte): SnapshotList<UUID>
}
