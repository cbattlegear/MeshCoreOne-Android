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
    suspend fun saveContact(dto: ContactDTO)
    /** Atomically changes only the avatar of an existing matching identity; null clears it. Never inserts. */
    suspend fun updateContactAvatar(
        key: EntityKey, expectedPublicKey: Bytes, imageData: Bytes?,
    ): ContactAvatarUpdateResult
    /** Changes only the favorite preference and flag bit on an existing matching identity. */
    suspend fun updateContactFavorite(
        key: EntityKey, expectedPublicKey: Bytes, isFavorite: Boolean,
    ): ContactFavoriteUpdateResult
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
