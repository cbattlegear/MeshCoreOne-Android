// AndroidOnly: WP-211 Narrow exercised repository doubles fail explicitly on every unexercised role operation.
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.UUID

internal class ContactRows(var rows: List<ContactDTO> = emptyList()) : ContactPersisting {
    var failure: PersistenceStoreException? = null
    override suspend fun fetchContacts(radioId: RadioId): SnapshotList<ContactDTO> {
        failure?.let { throw it }
        return rows.filter { it.radioId == radioId }.snapshot()
    }
    override suspend fun fetchConversations(radioId: RadioId): SnapshotList<ContactDTO> = unsupported()
    override suspend fun fetchContact(key: EntityKey): ContactDTO? = unsupported()
    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? = unsupported()
    override suspend fun fetchContactByPrefix(radioId: RadioId, publicKeyPrefix: Bytes): ContactDTO? = unsupported()
    override suspend fun fetchContactPublicKeysByPrefix(radioId: RadioId): SnapshotMap<UByte, SnapshotList<Bytes>> = unsupported()
    override suspend fun findContactNameByKeyPrefix(prefix: Bytes): String? = unsupported()
    override suspend fun findContactByPublicKey(publicKey: Bytes): ContactDTO? = unsupported()
    override suspend fun saveContact(radioId: RadioId, frame: ContactFrame): ContactSaveResult = unsupported()
    override suspend fun saveContactPreservingNewer(
        radioId: RadioId, frame: ContactFrame, expectedFrame: ContactFrame?,
    ): ContactSaveResult? = unsupported()
    override suspend fun saveContact(dto: ContactDTO): Unit = unsupported()
    override suspend fun updateContactAvatar(
        key: EntityKey, expectedPublicKey: Bytes, imageData: Bytes?,
    ): ContactAvatarUpdateResult = unsupported()
    override suspend fun updateContactFavorite(
        key: EntityKey, expectedPublicKey: Bytes, isFavorite: Boolean,
    ): ContactFavoriteUpdateResult = unsupported()
    override suspend fun updateContactLocalFields(
        key: EntityKey, expectedPublicKey: Bytes, update: ContactLocalUpdate,
    ): ContactLocalUpdateResult = unsupported()
    override suspend fun batchSaveContacts(radioId: RadioId, frames: SnapshotList<ContactFrame>): Long = unsupported()
    override suspend fun deleteContact(key: EntityKey): Unit = unsupported()
    override suspend fun deleteContacts(
        radioId: RadioId, publicKeys: SnapshotSet<Bytes>, skippingPublicKeys: () -> SnapshotSet<Bytes>,
    ): SnapshotList<UUID> = unsupported()
    override suspend fun deleteContactIfUnreferenced(key: EntityKey): Unit = unsupported()
    override suspend fun adoptOrphanedDirectMessages(
        radioId: RadioId, contacts: SnapshotList<ContactIdentity>,
    ): SnapshotMap<UUID, Long> = unsupported()
    override suspend fun touchContactHeard(radioId: RadioId, publicKey: Bytes, date: Instant): Boolean = unsupported()
    override suspend fun updateContactLastMessage(key: EntityKey, date: Instant?): Unit = unsupported()
    override suspend fun recomputeContactLastMessageDate(key: EntityKey): Instant? = unsupported()
    override suspend fun incrementUnreadCount(key: EntityKey): Unit = unsupported()
    override suspend fun clearUnreadCount(key: EntityKey): Unit = unsupported()
    override suspend fun markMentionSeen(key: EntityKey): Unit = unsupported()
    override suspend fun incrementUnreadMentionCount(key: EntityKey): Unit = unsupported()
    override suspend fun decrementUnreadMentionCount(key: EntityKey): Unit = unsupported()
    override suspend fun clearUnreadMentionCount(key: EntityKey): Unit = unsupported()
    override suspend fun fetchUnseenMentionIDs(key: EntityKey): SnapshotList<UUID> = unsupported()
    override suspend fun setContactMuted(key: EntityKey, isMuted: Boolean): Unit = unsupported()
    override suspend fun deleteMessagesForContact(key: EntityKey): Unit = unsupported()
    override suspend fun deleteChannelMessages(senderName: String, radioId: RadioId): Unit = unsupported()
    override suspend fun fetchBlockedContacts(radioId: RadioId): SnapshotList<ContactDTO> = unsupported()
    override suspend fun saveBlockedChannelSender(dto: BlockedChannelSenderDTO): Unit = unsupported()
    override suspend fun deleteBlockedChannelSender(radioId: RadioId, name: String): Unit = unsupported()
    override suspend fun fetchBlockedChannelSenders(radioId: RadioId): SnapshotList<BlockedChannelSenderDTO> = unsupported()
    override suspend fun fetchContactPublicKeys(radioId: RadioId): SnapshotSet<Bytes> = unsupported()
    private fun unsupported(): Nothing = throw UnsupportedOperationException("Untested contact role operation")
}

internal class DiscoveredRows(var rows: List<DiscoveredNodeDTO> = emptyList()) : DiscoveredNodePersisting {
    var failure: PersistenceStoreException? = null
    override suspend fun fetchDiscoveredNodes(radioId: RadioId): SnapshotList<DiscoveredNodeDTO> {
        failure?.let { throw it }
        return rows.filter { it.radioId == radioId }.snapshot()
    }
    override suspend fun upsertDiscoveredNode(radioId: RadioId, frame: ContactFrame): DiscoveredNodeSaveResult = unsupported()
    override suspend fun setInboundHopCount(
        radioId: RadioId, publicKey: Bytes, hopCount: Long, advertTimestamp: UInt?,
    ): Unit = unsupported()
    override suspend fun deleteDiscoveredNode(key: EntityKey): Unit = unsupported()
    override suspend fun clearDiscoveredNodes(radioId: RadioId): Unit = unsupported()
    override suspend fun fetchContactPublicKeys(radioId: RadioId): SnapshotSet<Bytes> = unsupported()
    private fun unsupported(): Nothing = throw UnsupportedOperationException("Untested discovered-node role operation")
}
