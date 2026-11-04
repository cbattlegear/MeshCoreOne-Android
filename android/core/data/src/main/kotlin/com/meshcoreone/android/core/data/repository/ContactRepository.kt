// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/ContactPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Contacts.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Channels.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.UUID

internal class ContactRepository(
    private val context: RoomRepositoryContext,
    private val discovered: DiscoveredNodeRepository,
) : ContactPersisting {
    override suspend fun fetchContacts(radioId: RadioId): SnapshotList<ContactDTO> = context.read("fetchContacts") {
        database.contacts().forRadio(radioId.value).map { it.toDTO() }.snapshot()
    }

    override suspend fun fetchConversations(radioId: RadioId): SnapshotList<ContactDTO> =
        context.read("fetchConversations") {
            database.contacts().conversations(radioId.value).map { it.toDTO() }.snapshot()
        }

    override suspend fun fetchContact(key: EntityKey): ContactDTO? = context.read("fetchContactById") {
        database.contacts().byId(key.radioId.value, key.id)?.toDTO()
    }

    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? =
        context.read("fetchContactByPublicKey") {
            database.contacts().forPublicKey(radioId.value, publicKey).firstOrNull()?.toDTO()
        }

    override suspend fun fetchContactByPrefix(radioId: RadioId, publicKeyPrefix: Bytes): ContactDTO? =
        context.read("fetchContactByPrefix") {
            database.contacts().forPrefix(radioId.value, publicKeyPrefix, 6).firstOrNull()?.toDTO()
        }

    override suspend fun fetchContactPublicKeysByPrefix(radioId: RadioId): SnapshotMap<UByte, SnapshotList<Bytes>> =
        context.read("fetchContactPublicKeysByPrefix") {
            val groups = LinkedHashMap<UByte, MutableList<Bytes>>()
            for (row in database.contacts().forRadio(radioId.value)) {
                if (!row.publicKey.isEmpty) groups.getOrPut(row.publicKey[0]) { mutableListOf() }.add(row.publicKey)
            }
            groups.mapValues { it.value.snapshot() }.snapshotMap()
        }

    override suspend fun findContactNameByKeyPrefix(prefix: Bytes): String? =
        context.read("findContactNameByKeyPrefix") {
            database.contacts().globalPrefixHints(prefix, prefix.size).firstOrNull()?.toDTO()?.displayName
        }

    override suspend fun findContactByPublicKey(publicKey: Bytes): ContactDTO? =
        context.read("findContactByPublicKey") {
            database.contacts().globalPublicKeyHints(publicKey).firstOrNull()?.toDTO()
        }

    override suspend fun fetchContactPublicKeys(radioId: RadioId): SnapshotSet<Bytes> =
        context.read("fetchContactPublicKeys") {
            database.contacts().forRadio(radioId.value).map { it.publicKey }.snapshotSet()
        }

    override suspend fun saveContact(radioId: RadioId, frame: ContactFrame): ContactSaveResult =
        checkNotNull(saveContactFrame(radioId, frame, preserveNewer = false))

    override suspend fun saveContactPreservingNewer(
        radioId: RadioId, frame: ContactFrame, expectedFrame: ContactFrame?,
    ): ContactSaveResult? = saveContactFrame(radioId, frame, preserveNewer = true, expectedFrame = expectedFrame)

    private suspend fun saveContactFrame(
        radioId: RadioId, frame: ContactFrame, preserveNewer: Boolean, expectedFrame: ContactFrame? = null,
    ): ContactSaveResult? =
        context.write("saveContactFrame") {
            val existing = database.contacts().forPublicKey(radioId.value, frame.publicKey).firstOrNull()
            val stored = existing?.toDTO()
            if (preserveNewer && stored != null &&
                (stored.lastModified > frame.lastModified ||
                    (stored.lastModified == frame.lastModified && stored.toContactFrame() != expectedFrame &&
                        stored.toContactFrame() != frame))) {
                return@write null
            }
            val dto = stored?.updating(frame) ?: ContactDTO.fromFrame(radioId, frame)
            database.contacts().upsert(dto.toEntity())
            save()
            ContactSaveResult(dto.id, existing == null)
        }

    override suspend fun saveContact(dto: ContactDTO) = context.write("saveContact") {
        val existing = database.contacts().byId(dto.radioId.value, dto.id)
        database.contacts().upsert(existing?.applying(dto) ?: dto.toEntity())
        save()
    }

    override suspend fun batchSaveContacts(radioId: RadioId, frames: SnapshotList<ContactFrame>): Long {
        if (frames.isEmpty()) return 0
        return context.write("batchSaveContacts") {
            val byKey = LinkedHashMap<Bytes, ContactEntity>()
            for (row in database.contacts().forRadio(radioId.value)) byKey.putIfAbsent(row.publicKey, row)
            for (frame in frames) {
                val dto = byKey[frame.publicKey]?.toDTO()?.updating(frame) ?: ContactDTO.fromFrame(radioId, frame)
                val row = dto.toEntity()
                database.contacts().upsert(row)
                byKey[frame.publicKey] = row
            }
            save()
            frames.size.toLong()
        }
    }

    override suspend fun deleteContact(key: EntityKey) =
        context.write("deleteContact", flushBeforeRollback = true) {
            deleteContactMessages(key)
            database.contacts().delete(key.radioId.value, key.id)
            save()
        }

    override suspend fun deleteContacts(
        radioId: RadioId,
        publicKeys: SnapshotSet<Bytes>,
        skippingPublicKeys: () -> SnapshotSet<Bytes>,
    ): SnapshotList<UUID> {
        if (publicKeys.isEmpty()) return SnapshotList.empty()
        return context.write("deleteContacts", flushBeforeRollback = true) {
            val candidates = mutableListOf<Pair<ContactEntity, Bytes>>()
            for (publicKey in publicKeys) {
                if (publicKey in skippingPublicKeys()) continue
                val row = database.contacts().forPublicKey(radioId.value, publicKey).firstOrNull() ?: continue
                candidates += row to publicKey
            }
            val deleted = mutableListOf<UUID>()
            for ((row, publicKey) in candidates) {
                if (publicKey in skippingPublicKeys()) continue
                deleteContactMessages(EntityKey(radioId, row.id))
                database.contacts().delete(radioId.value, row.id)
                deleted += row.id
            }
            if (deleted.isNotEmpty()) save()
            deleted.snapshot()
        }
    }

    override suspend fun deleteContactIfUnreferenced(key: EntityKey) = context.guardedWrite(
        "deleteContactIfUnreferenced", Unit,
        guard = { database.messages().countForContact(key.radioId.value, key.id) == 0L },
    ) {
        deleteContactMessages(key)
        database.contacts().delete(key.radioId.value, key.id)
        save()
    }

    override suspend fun adoptOrphanedDirectMessages(
        radioId: RadioId,
        contacts: SnapshotList<ContactIdentity>,
    ): SnapshotMap<UUID, Long> {
        if (contacts.isEmpty()) return emptyMap<UUID, Long>().snapshotMap()
        return context.write("adoptOrphanedDirectMessages") {
            // AndroidOnly: WP-202 A delta subset must not hide another persisted prefix match.
            val allContacts = database.contacts().forRadio(radioId.value)
            val persistedById = allContacts.associateBy { it.id }
            val byId = LinkedHashMap<UUID, ContactEntity>()
            for (candidate in contacts) {
                persistedById[candidate.id]
                    ?.takeIf { it.publicKey == candidate.publicKey }
                    ?.let { byId[it.id] = it }
            }
            val counts = LinkedHashMap<UUID, Long>()
            val newest = LinkedHashMap<UUID, Instant>()
            val unread = LinkedHashMap<UUID, Long>()
            val mentions = LinkedHashMap<UUID, Long>()
            for (message in database.messages().orphanedDirectMessages(radioId.value)) {
                if (message.directionRawValue != MessageDirection.INCOMING.rawValue) continue
                val prefix = message.senderKeyPrefix ?: continue
                if (prefix.isEmpty || RepositoryReactionPolicy.isDirectReaction(message.text)) continue
                val matches = allContacts.filter { it.publicKey.startsWith(prefix) }
                if (matches.size != 1) continue
                val match = matches.single()
                if (match.id !in byId) continue
                val dto = message.toDTO()
                database.messages().upsert(message.copy(
                    contactID = match.id,
                    deduplicationKey = RepositoryDeduplicationKey.contentBased(
                        match.id, null, dto.senderNodeName, dto.timestamp, dto.text,
                    ),
                ))
                counts[match.id] = checkedIncrement(counts[match.id] ?: 0)
                newest[match.id] = maxOf(newest[match.id] ?: dto.sortDate, dto.sortDate)
                if (!message.isRead) unread[match.id] = checkedIncrement(unread[match.id] ?: 0)
                if (message.containsSelfMention && !message.mentionSeen) {
                    mentions[match.id] = checkedIncrement(mentions[match.id] ?: 0)
                }
            }
            for ((id, _) in counts) {
                val contact = requireNotNull(byId[id])
                val date = requireNotNull(newest[id])
                database.contacts().upsert(contact.copy(
                    lastMessageDate = StoredInstant.from(maxOf(contact.lastMessageDate?.toInstant() ?: date, date)),
                    unreadCount = if (contact.isBlocked) contact.unreadCount else checkedAdd(contact.unreadCount, unread[id] ?: 0),
                    unreadMentionCount = if (contact.isBlocked) contact.unreadMentionCount else checkedAdd(contact.unreadMentionCount, mentions[id] ?: 0),
                ))
            }
            if (counts.isNotEmpty()) save()
            counts.snapshotMap()
        }
    }

    override suspend fun touchContactHeard(radioId: RadioId, publicKey: Bytes, date: Instant): Boolean =
        context.write("touchContactHeard") {
            val row = database.contacts().forPublicKey(radioId.value, publicKey).firstOrNull()
                ?: return@write false
            val stamp = clampedPhoneClockTimestamp(date.truncatedUnixUInt(), date)
            database.contacts().upsert(row.copy(lastHeardTimestamp = maxOf(row.lastHeardTimestamp, stamp.toLong())))
            var node = database.discoveredNodes().forPublicKey(radioId.value, publicKey).firstOrNull()
            if (node == null) node = discovered.upsertInTransaction(this, radioId, row.toDTO().toContactFrame()).node.toEntity()
            database.discoveredNodes().upsert(node.copy(lastHeard = StoredInstant.from(date)))
            save()
            true
        }

    override suspend fun updateContactLastMessage(key: EntityKey, date: Instant?) =
        mutateContact("updateContactLastMessage", key) { it.copy(lastMessageDate = date?.let(StoredInstant::from)) }

    override suspend fun recomputeContactLastMessageDate(key: EntityKey): Instant? =
        context.write("recomputeContactLastMessageDate") {
            val date = database.messages().newestForContact(key.radioId.value, key.id, 1).firstOrNull()?.toDTO()?.date
            val row = database.contacts().byId(key.radioId.value, key.id)
            if (row != null) {
                database.contacts().upsert(row.copy(lastMessageDate = date?.let(StoredInstant::from)))
                save()
            }
            date
        }

    override suspend fun incrementUnreadCount(key: EntityKey) =
        mutateContact("incrementUnreadCount", key) { it.copy(unreadCount = checkedIncrement(it.unreadCount)) }
    override suspend fun clearUnreadCount(key: EntityKey) =
        mutateContact("clearUnreadCount", key) { it.copy(unreadCount = 0) }
    override suspend fun incrementUnreadMentionCount(key: EntityKey) =
        mutateContact("incrementUnreadMentionCount", key) { it.copy(unreadMentionCount = checkedIncrement(it.unreadMentionCount)) }
    override suspend fun decrementUnreadMentionCount(key: EntityKey) =
        mutateContact("decrementUnreadMentionCount", key) { it.copy(unreadMentionCount = decrementedUnread(it.unreadMentionCount)) }
    override suspend fun clearUnreadMentionCount(key: EntityKey) =
        mutateContact("clearUnreadMentionCount", key) { it.copy(unreadMentionCount = 0) }

    override suspend fun markMentionSeen(key: EntityKey) = context.write("markMentionSeen") {
        val row = database.messages().byId(key.radioId.value, key.id) ?: return@write
        database.messages().upsert(row.copy(mentionSeen = true))
        save()
    }

    override suspend fun fetchUnseenMentionIDs(key: EntityKey): SnapshotList<UUID> = context.read("fetchUnseenMentionIDs") {
        database.messages().unseenContactMentionIDs(key.radioId.value, key.id).snapshot()
    }

    override suspend fun setContactMuted(key: EntityKey, isMuted: Boolean) = context.write("setContactMuted") {
        val row = database.contacts().byId(key.radioId.value, key.id)
            ?: throw PersistenceStoreException(PersistenceStoreError.ContactNotFound)
        database.contacts().upsert(row.copy(isMuted = isMuted))
        save()
    }

    override suspend fun deleteMessagesForContact(key: EntityKey) = context.write("deleteMessagesForContact") {
        deleteContactMessages(key)
        save()
    }

    override suspend fun deleteChannelMessages(senderName: String, radioId: RadioId) =
        context.write("deleteChannelMessagesFromSender") {
            val rows = database.messages().backupPageWithoutPreviewBlobs(radioId.value, Long.MAX_VALUE, 0)
                .filter { it.channelIndex != null && it.senderNodeName == senderName }
            deleteMessageDependents(radioId, rows, reactionsByMessage = true)
            for (row in rows) database.messages().delete(radioId.value, row.id)
            save()
        }

    override suspend fun fetchBlockedContacts(radioId: RadioId): SnapshotList<ContactDTO> =
        context.read("fetchBlockedContacts") { database.contacts().blocked(radioId.value).map { it.toDTO() }.snapshot() }

    override suspend fun saveBlockedChannelSender(dto: BlockedChannelSenderDTO) =
        context.write("saveBlockedChannelSender") {
            val existing = database.blockedSenders().forName(dto.radioId.value, dto.name).firstOrNull()
            database.blockedSenders().upsert(existing?.copy(dateBlocked = StoredInstant.from(dto.dateBlocked)) ?: dto.toEntity())
            save()
        }

    override suspend fun deleteBlockedChannelSender(radioId: RadioId, name: String) =
        context.write("deleteBlockedChannelSender") {
            val row = database.blockedSenders().forName(radioId.value, name).firstOrNull() ?: return@write
            database.blockedSenders().delete(radioId.value, row.id)
            save()
        }

    override suspend fun fetchBlockedChannelSenders(radioId: RadioId): SnapshotList<BlockedChannelSenderDTO> =
        context.read("fetchBlockedChannelSenders") {
            database.blockedSenders().forRadio(radioId.value).map { it.toDTO() }.snapshot()
        }

    private suspend fun mutateContact(
        operation: String, key: EntityKey, mutation: (ContactEntity) -> ContactEntity,
    ) = context.write(operation) {
        val row = database.contacts().byId(key.radioId.value, key.id) ?: return@write
        database.contacts().upsert(mutation(row))
        save()
    }
}
