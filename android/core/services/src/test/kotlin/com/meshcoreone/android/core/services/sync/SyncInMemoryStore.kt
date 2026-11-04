// AndroidOnly: WP-214 In-memory PersistenceStoreProtocol fake standing in for the source SwiftData test store.
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.ContactSaveResult
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.BlockedChannelSenderDTO
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageRepeatDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.SnapshotMap
import com.meshcoreone.android.core.model.SnapshotSet
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.snapshotMap
import com.meshcoreone.android.core.model.snapshotSet
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID

/** Every member not overridden below fails loudly, so a test never silently relies on an unmodeled call. */
private fun unsupportedStore(): PersistenceStoreProtocol = Proxy.newProxyInstance(
    PersistenceStoreProtocol::class.java.classLoader,
    arrayOf(PersistenceStoreProtocol::class.java),
) { proxy, method, args ->
    when (method.name) {
        "toString" -> "UnsupportedSyncStore"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.firstOrNull()
        else -> throw UnsupportedOperationException("Sync test store does not model ${method.name}")
    }
} as PersistenceStoreProtocol

/**
 * In-memory store with the source `PersistenceStore` semantics sync relies on: per-radio partitions,
 * contact upsert by public key, message dedup lookup by key, RX-log correlation queries. [failures]
 * makes a named member throw (Swift `MockPersistenceStore` stubbed errors).
 */
internal class SyncInMemoryStore : PersistenceStoreProtocol by unsupportedStore() {
    private val lock = Any()
    private val devices = LinkedHashMap<RadioId, DeviceDTO>()
    private val contacts = ArrayList<ContactDTO>()
    private val channels = ArrayList<ChannelDTO>()
    private val messages = ArrayList<MessageDTO>()
    private val repeats = ArrayList<Pair<RadioId, MessageRepeatDTO>>()
    private val rxLog = ArrayList<RxLogEntryDTO>()
    private val sessions = ArrayList<RemoteNodeSessionDTO>()
    private val reactions = ArrayList<ReactionDTO>()
    private val blockedSenders = ArrayList<BlockedChannelSenderDTO>()

    /** Member name to the error it throws. */
    val failures: MutableMap<String, Exception> = java.util.concurrent.ConcurrentHashMap()

    /** Names of every modeled member called, in order. */
    val calls: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    private fun enter(name: String) {
        calls += name
        failures[name]?.let { throw it }
    }

    private inline fun <T> locked(name: String, block: () -> T): T {
        enter(name)
        return synchronized(lock, block)
    }

    // MARK: - Devices

    override suspend fun fetchDevice(radioId: RadioId): DeviceDTO? = locked("fetchDevice") { devices[radioId] }
    override suspend fun saveDevice(dto: DeviceDTO) = locked("saveDevice") { devices[dto.radioId] = dto }
    override suspend fun updateDeviceLastContactSync(radioId: RadioId, timestamp: UInt) = locked("updateDeviceLastContactSync") {
        devices[radioId]?.let { devices[radioId] = it.copy(lastContactSync = timestamp) }
        Unit
    }

    // MARK: - Contacts

    override suspend fun fetchContacts(radioId: RadioId): SnapshotList<ContactDTO> =
        locked("fetchContacts") { contacts.filter { it.radioId == radioId }.snapshot() }

    override suspend fun fetchContact(key: EntityKey): ContactDTO? =
        locked("fetchContact") { contacts.firstOrNull { it.id == key.id && it.radioId == key.radioId } }

    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? =
        locked("fetchContactByKey") { contacts.firstOrNull { it.radioId == radioId && it.publicKey == publicKey } }

    override suspend fun fetchContactPublicKeysByPrefix(radioId: RadioId): SnapshotMap<UByte, SnapshotList<Bytes>> =
        locked("fetchContactPublicKeysByPrefix") {
            contacts.filter { it.radioId == radioId && !it.publicKey.isEmpty }
                .groupBy { it.publicKey[0] }
                .mapValues { (_, rows) -> rows.map { it.publicKey }.snapshot() }
                .snapshotMap()
        }

    override suspend fun fetchContactPublicKeys(radioId: RadioId): SnapshotSet<Bytes> =
        locked("fetchContactPublicKeys") { contacts.filter { it.radioId == radioId }.map { it.publicKey }.snapshotSet() }

    override suspend fun saveContact(radioId: RadioId, frame: ContactFrame): ContactSaveResult = locked("saveContactFrame") {
        upsertLocked(radioId, frame)
    }

    private fun upsertLocked(radioId: RadioId, frame: ContactFrame): ContactSaveResult {
        val index = contacts.indexOfFirst { it.radioId == radioId && it.publicKey == frame.publicKey }
        if (index >= 0) {
            contacts[index] = contacts[index].updating(frame)
            return ContactSaveResult(contacts[index].id, false)
        }
        val created = ContactDTO.fromFrame(radioId, frame)
        contacts += created
        return ContactSaveResult(created.id, true)
    }

    override suspend fun saveContact(dto: ContactDTO) = locked("saveContact") {
        contacts.removeAll { it.id == dto.id }
        contacts += dto
        Unit
    }

    override suspend fun batchSaveContacts(radioId: RadioId, frames: SnapshotList<ContactFrame>): Long =
        locked("batchSaveContacts") {
            frames.forEach { upsertLocked(radioId, it) }
            frames.size.toLong()
        }

    override suspend fun deleteContact(key: EntityKey) = locked("deleteContact") {
        contacts.removeAll { it.id == key.id && it.radioId == key.radioId }
        messages.removeAll { it.contactID == key.id && it.radioId == key.radioId }
        Unit
    }

    override suspend fun touchContactHeard(radioId: RadioId, publicKey: Bytes, date: Instant): Boolean = locked("touchContactHeard") {
        val index = contacts.indexOfFirst { it.radioId == radioId && it.publicKey == publicKey }
        if (index < 0) return@locked false
        val stamp = date.uint32Seconds()
        val current = contacts[index]
        if ((current.lastHeardTimestamp ?: 0u) >= stamp) return@locked false
        contacts[index] = current.copy(lastHeardTimestamp = stamp)
        true
    }

    override suspend fun updateContactLastMessage(key: EntityKey, date: Instant?) =
        updateContact("updateContactLastMessage", key) { it.copy(lastMessageDate = date) }

    override suspend fun incrementUnreadCount(key: EntityKey) =
        updateContact("incrementUnreadCount", key) { it.copy(unreadCount = it.unreadCount + 1) }

    override suspend fun incrementUnreadMentionCount(key: EntityKey) =
        updateContact("incrementUnreadMentionCount", key) { it.copy(unreadMentionCount = it.unreadMentionCount + 1) }

    private fun updateContact(name: String, key: EntityKey, transform: (ContactDTO) -> ContactDTO) = locked(name) {
        val index = contacts.indexOfFirst { it.id == key.id && it.radioId == key.radioId }
        if (index >= 0) contacts[index] = transform(contacts[index])
        Unit
    }

    override suspend fun deleteChannelMessages(senderName: String, radioId: RadioId) = locked("deleteChannelMessages") {
        messages.removeAll { it.radioId == radioId && it.channelIndex != null && it.senderNodeName == senderName }
        Unit
    }

    override suspend fun fetchBlockedContacts(radioId: RadioId): SnapshotList<ContactDTO> =
        locked("fetchBlockedContacts") { contacts.filter { it.radioId == radioId && it.isBlocked }.snapshot() }

    override suspend fun saveBlockedChannelSender(dto: BlockedChannelSenderDTO) = locked("saveBlockedChannelSender") {
        blockedSenders += dto
        Unit
    }

    override suspend fun fetchBlockedChannelSenders(radioId: RadioId): SnapshotList<BlockedChannelSenderDTO> =
        locked("fetchBlockedChannelSenders") { blockedSenders.filter { it.radioId == radioId }.snapshot() }

    // MARK: - Channels

    override suspend fun fetchChannels(radioId: RadioId): SnapshotList<ChannelDTO> =
        locked("fetchChannels") { channels.filter { it.radioId == radioId }.sortedBy { it.index }.snapshot() }

    override suspend fun fetchChannel(key: EntityKey): ChannelDTO? =
        locked("fetchChannel") { channels.firstOrNull { it.id == key.id && it.radioId == key.radioId } }

    override suspend fun saveChannel(dto: ChannelDTO) = locked("saveChannel") {
        channels.removeAll { it.id == dto.id }
        channels += dto
        Unit
    }

    override suspend fun updateChannelLastMessage(key: EntityKey, date: Instant?) =
        updateChannel("updateChannelLastMessage", key) { it.copy(lastMessageDate = date) }

    override suspend fun incrementChannelUnreadCount(key: EntityKey) =
        updateChannel("incrementChannelUnreadCount", key) { it.copy(unreadCount = it.unreadCount + 1) }

    override suspend fun incrementChannelUnreadMentionCount(key: EntityKey) =
        updateChannel("incrementChannelUnreadMentionCount", key) { it.copy(unreadMentionCount = it.unreadMentionCount + 1) }

    private fun updateChannel(name: String, key: EntityKey, transform: (ChannelDTO) -> ChannelDTO) = locked(name) {
        val index = channels.indexOfFirst { it.id == key.id && it.radioId == key.radioId }
        if (index >= 0) channels[index] = transform(channels[index])
        Unit
    }

    // MARK: - Messages

    override suspend fun fetchMessage(deduplicationKey: String, radioId: RadioId): MessageDTO? =
        locked("fetchMessageByDeduplicationKey") { messages.firstOrNull { it.radioId == radioId && it.deduplicationKey == deduplicationKey } }

    override suspend fun saveMessage(dto: MessageDTO) = locked("saveMessage") {
        messages.removeAll { it.id == dto.id }
        messages += dto
        Unit
    }

    override suspend fun fetchMessage(key: EntityKey): MessageDTO? =
        locked("fetchMessage") { messages.firstOrNull { it.id == key.id && it.radioId == key.radioId } }

    override suspend fun fetchMessages(contact: EntityKey, limit: Long, offset: Long): SnapshotList<MessageDTO> =
        locked("fetchContactMessages") {
            messages.filter { it.radioId == contact.radioId && it.contactID == contact.id }.drop(offset.toInt()).take(limit.toInt()).snapshot()
        }

    override suspend fun fetchMessages(radioId: RadioId, channelIndex: UByte, limit: Long, offset: Long): SnapshotList<MessageDTO> =
        locked("fetchChannelMessages") {
            messages.filter { it.radioId == radioId && it.channelIndex == channelIndex }.drop(offset.toInt()).take(limit.toInt()).snapshot()
        }

    override suspend fun newestUnreadIncomingMessage(contact: EntityKey): MessageDTO? = locked("newestUnreadIncomingMessage") {
        messages.filter {
            it.radioId == contact.radioId && it.contactID == contact.id && !it.isRead && it.direction == MessageDirection.INCOMING
        }.maxByOrNull { it.sortDate }
    }

    override suspend fun fetchDMMessageCandidates(contact: EntityKey, timestampWindow: ClosedRange<UInt>, limit: Long): SnapshotList<MessageDTO> =
        locked("fetchDMMessageCandidates") {
            messages.filter { it.radioId == contact.radioId && it.contactID == contact.id && it.reactionTimestamp in timestampWindow }
                .take(limit.toInt()).snapshot()
        }

    override suspend fun fetchChannelMessageCandidates(
        radioId: RadioId, channelIndex: UByte, timestampWindow: ClosedRange<UInt>, limit: Long,
    ): SnapshotList<MessageDTO> = locked("fetchChannelMessageCandidates") {
        messages.filter { it.radioId == radioId && it.channelIndex == channelIndex && it.reactionTimestamp in timestampWindow }
            .take(limit.toInt()).snapshot()
    }

    /** Hash matching belongs to WP-216's store; tests stub targets through [reactionTargets]. */
    val reactionTargets: MutableMap<String, UUID> = java.util.concurrent.ConcurrentHashMap()

    override suspend fun findDMMessageForReaction(contact: EntityKey, messageHash: String, timestampWindow: ClosedRange<UInt>, limit: Long): MessageDTO? =
        locked("findDMMessageForReaction") { reactionTargets[messageHash]?.let { id -> messages.firstOrNull { it.id == id } } }

    override suspend fun findChannelMessageForReaction(
        radioId: RadioId, channelIndex: UByte, parsedReaction: ParsedReaction, localNodeName: String?,
        timestampWindow: ClosedRange<UInt>, limit: Long,
    ): MessageDTO? = locked("findChannelMessageForReaction") {
        reactionTargets[parsedReaction.messageHash]?.let { id -> messages.firstOrNull { it.id == id } }
    }

    // MARK: - Heard repeats

    override suspend fun saveMessageRepeat(radioId: RadioId, dto: MessageRepeatDTO) = locked("saveMessageRepeat") {
        repeats += radioId to dto
        Unit
    }

    override suspend fun fetchMessageRepeats(message: EntityKey): SnapshotList<MessageRepeatDTO> =
        locked("fetchMessageRepeats") { repeats.filter { it.first == message.radioId && it.second.messageID == message.id }.map { it.second }.snapshot() }

    override suspend fun incrementMessageHeardRepeats(key: EntityKey): Long = locked("incrementMessageHeardRepeats") {
        val index = messages.indexOfFirst { it.id == key.id && it.radioId == key.radioId }
        if (index < 0) return@locked 0L
        messages[index] = messages[index].copy(heardRepeats = messages[index].heardRepeats + 1)
        messages[index].heardRepeats
    }

    // MARK: - RX log

    override suspend fun saveRxLogEntry(dto: RxLogEntryDTO) = locked("saveRxLogEntry") {
        rxLog += dto
        Unit
    }

    override suspend fun findRxLogEntry(radioId: RadioId, channelIndex: UByte?, senderTimestamp: UInt): RxLogEntryDTO? =
        locked("findRxLogEntry") {
            rxLog.lastOrNull { it.radioId == radioId && it.channelIndex == channelIndex && it.senderTimestamp == senderTimestamp }
        }

    override suspend fun fetchRxLogEntries(radioId: RadioId, channelIndex: UByte, senderTimestamp: UInt): SnapshotList<RxLogEntryDTO> =
        locked("fetchRxLogEntries") {
            rxLog.filter { it.radioId == radioId && it.channelIndex == channelIndex && it.senderTimestamp == senderTimestamp }.snapshot()
        }

    override suspend fun findRxLogEntryBySenderPrefix(radioId: RadioId, senderPrefixByte: UByte, receivedSince: Instant): RxLogEntryDTO? =
        locked("findRxLogEntryBySenderPrefix") {
            rxLog.lastOrNull { it.radioId == radioId && it.senderPrefix?.firstOrNull() == senderPrefixByte && it.receivedAt >= receivedSince }
        }

    // MARK: - Rooms

    override suspend fun saveRemoteNodeSessionDTO(dto: RemoteNodeSessionDTO) = locked("saveRemoteNodeSessionDTO") {
        sessions.removeAll { it.id == dto.id }
        sessions += dto
        Unit
    }

    override suspend fun fetchRemoteNodeSession(key: EntityKey): RemoteNodeSessionDTO? =
        locked("fetchRemoteNodeSession") { sessions.firstOrNull { it.id == key.id && it.radioId == key.radioId } }

    // MARK: - Reactions

    override suspend fun reactionExists(message: EntityKey, senderName: String, emoji: String): Boolean = locked("reactionExists") {
        reactions.any { it.messageID == message.id && it.radioId == message.radioId && it.senderName == senderName && it.emoji == emoji }
    }

    override suspend fun saveReaction(dto: ReactionDTO) = locked("saveReaction") {
        reactions += dto
        Unit
    }

    override suspend fun fetchReactions(message: EntityKey, limit: Long): SnapshotList<ReactionDTO> =
        locked("fetchReactions") { reactions.filter { it.messageID == message.id }.take(limit.toInt()).snapshot() }

    // MARK: - Test inspection

    fun allMessages(): List<MessageDTO> = synchronized(lock) { messages.toList() }
    fun allReactions(): List<ReactionDTO> = synchronized(lock) { reactions.toList() }
    fun device(radioId: RadioId): DeviceDTO? = synchronized(lock) { devices[radioId] }

    companion object {
        /** Swift `PersistenceStore.createTestDataStore(radioID:maxChannels:maxContacts:lastContactSync:)`. */
        suspend fun createTestDataStore(
            radioId: RadioId,
            maxChannels: UByte = 8u,
            maxContacts: UShort = 100u,
            lastContactSync: UInt = 0u,
            nodeName: String = "TestDevice",
        ): SyncInMemoryStore = SyncInMemoryStore().also {
            it.saveDevice(testDevice(radioId, maxChannels = maxChannels, maxContacts = maxContacts, lastContactSync = lastContactSync, nodeName = nodeName))
        }

        /** Swift `DeviceDTO.testDevice` (self public key 32 x 0x01). */
        fun testDevice(
            radioId: RadioId,
            publicKey: Bytes = Bytes(ByteArray(32) { 1 }),
            maxChannels: UByte = 8u,
            maxContacts: UShort = 100u,
            lastContactSync: UInt = 0u,
            nodeName: String = "TestDevice",
        ): DeviceDTO = DeviceDTO(
            id = radioId.value, radioId = radioId, publicKey = publicKey, nodeName = nodeName, firmwareVersion = 8u,
            firmwareVersionString = "v1.0.0", maxContacts = maxContacts, maxChannels = maxChannels, multiAcks = 0u,
            lastContactSync = lastContactSync,
        )
    }
}
