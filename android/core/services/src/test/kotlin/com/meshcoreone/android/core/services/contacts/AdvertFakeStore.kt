// AndroidOnly: WP-209 in-memory PersistenceStoreProtocol fake modelling the Swift PersistenceStore and MockPersistenceStore semantics the advertisement tests rely on.
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ContactIdentity
import com.meshcoreone.android.core.contracts.domain.ContactSaveResult
import com.meshcoreone.android.core.contracts.domain.DiscoveredNodeSaveResult
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.LocalDirectContactPersisting
import com.meshcoreone.android.core.contracts.domain.LocalDirectContactResolution
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
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
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal class AdvertStoreUnavailable : Exception("store unavailable")

private fun rejectingStore(): PersistenceStoreProtocol = PersistenceStoreProtocol::class.java.cast(
    Proxy.newProxyInstance(
        PersistenceStoreProtocol::class.java.classLoader, arrayOf(PersistenceStoreProtocol::class.java),
    ) { _, method, _ -> throw AssertionError("Unexpected store invocation: ${method.name}") },
)

private fun rejectingLocalDirectStore(): LocalDirectContactPersisting = LocalDirectContactPersisting::class.java.cast(
    Proxy.newProxyInstance(
        LocalDirectContactPersisting::class.java.classLoader, arrayOf(LocalDirectContactPersisting::class.java),
    ) { _, method, _ -> throw AssertionError("Unexpected local contact invocation: ${method.name}") },
)

/**
 * Store fake with the real store's frame-save (update-in-place), touch (stamp + Discover upsert) and
 * cascade-delete semantics, plus the Swift mock's stubbed errors, call recording and delete hold gate.
 */
internal class AdvertFakeStore :
    PersistenceStoreProtocol by rejectingStore(), LocalDirectContactPersisting by rejectingLocalDirectStore() {
    private val lock = Any()
    private val devices = LinkedHashMap<RadioId, DeviceDTO>()
    private val contacts = LinkedHashMap<UUID, ContactDTO>()
    private val nodes = LinkedHashMap<UUID, DiscoveredNodeDTO>()
    private val messages = LinkedHashMap<UUID, MessageDTO>()
    private val touchCalls = ArrayList<Bytes>()
    private val deletedIds = ArrayList<UUID>()
    private var deleteContactsCalls = 0
    private var deleteHoldRequested = false
    private var deleteHold: Continuation<Unit>? = null
    private var rosterRevision = UUID.randomUUID()

    @Volatile var touchContactHeardError: Exception? = null
    @Volatile var fetchContactError: Exception? = null
    @Volatile var fetchContactPublicKeysError: Exception? = null
    @Volatile var deleteContactError: Exception? = null

    val touchContactHeardCallCount: Int get() = synchronized(lock) { touchCalls.size }
    val deletedContactIDs: List<UUID> get() = synchronized(lock) { deletedIds.toList() }
    val deleteContactsCallCount: Int get() = synchronized(lock) { deleteContactsCalls }
    val isDeleteContactsHeld: Boolean get() = synchronized(lock) { deleteHold != null }

    fun message(id: UUID): MessageDTO? = synchronized(lock) { messages[id] }

    fun holdNextDeleteContacts() = synchronized(lock) { deleteHoldRequested = true }

    fun releaseDeleteContacts() {
        val held = synchronized(lock) {
            deleteHoldRequested = false
            deleteHold.also { deleteHold = null }
        }
        held?.resume(Unit)
    }

    override suspend fun saveDevice(dto: DeviceDTO) = synchronized(lock) { devices[dto.radioId] = dto }

    override suspend fun fetchDevice(radioId: RadioId): DeviceDTO? = synchronized(lock) { devices[radioId] }

    private fun failFetch() = fetchContactError?.let { throw it }

    private fun contactFor(radioId: RadioId, publicKey: Bytes): ContactDTO? =
        contacts.values.firstOrNull { it.radioId == radioId && it.publicKey == publicKey }

    override suspend fun fetchContact(key: EntityKey): ContactDTO? {
        failFetch()
        return synchronized(lock) { contacts[key.id]?.takeIf { it.radioId == key.radioId } }
    }

    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
        failFetch()
        return synchronized(lock) { contactFor(radioId, publicKey) }
    }

    override suspend fun fetchContactByPrefix(radioId: RadioId, publicKeyPrefix: Bytes): ContactDTO? {
        failFetch()
        return synchronized(lock) {
            contacts.values.firstOrNull { it.radioId == radioId && it.publicKey.advertStartsWith(publicKeyPrefix) }
        }
    }

    private fun classifyLocked(radioId: RadioId, prefix: Bytes): LocalDirectContactResolution {
        val matches = contacts.values.filter { it.radioId == radioId && it.publicKey.advertStartsWith(prefix) }
        return when (matches.size) {
            0 -> LocalDirectContactResolution.NotFound
            1 -> LocalDirectContactResolution.Unique(matches.single(), rosterRevision)
            else -> LocalDirectContactResolution.Ambiguous
        }
    }

    override suspend fun classifyLocalDirectContact(radioId: RadioId, prefix: Bytes): LocalDirectContactResolution {
        failFetch()
        return synchronized(lock) { classifyLocked(radioId, prefix) }
    }

    override suspend fun applyLocalDirectPathIfCurrent(
        radioId: RadioId, prefix: Bytes, observed: LocalDirectContactResolution,
        outPathLength: UByte, outPath: Bytes, heardAt: Instant,
    ): LocalDirectContactResolution {
        failFetch()
        touchContactHeardError?.let { throw it }
        return synchronized(lock) {
            val current = classifyLocked(radioId, prefix)
            if (current == LocalDirectContactResolution.Ambiguous) return@synchronized current
            if (current !is LocalDirectContactResolution.Unique || observed !is LocalDirectContactResolution.Unique ||
                current.rosterRevision != observed.rosterRevision ||
                current.contact.id != observed.contact.id || current.contact.publicKey != observed.contact.publicKey
            ) return@synchronized LocalDirectContactResolution.Stale
            val row = current.contact.copy(outPathLength = outPathLength, outPath = outPath)
            contacts[row.id] = row
            stampLocked(row, heardAt)
            LocalDirectContactResolution.Unique(contacts.getValue(row.id), rosterRevision)
        }
    }

    override suspend fun materializeLocalDirectContactIfAbsent(
        radioId: RadioId, prefix: Bytes, frame: ContactFrame, heardAt: Instant,
    ): LocalDirectContactResolution {
        failFetch()
        touchContactHeardError?.let { throw it }
        return synchronized(lock) {
            val current = classifyLocked(radioId, prefix)
            if (current != LocalDirectContactResolution.NotFound) return@synchronized current
            check(frame.publicKey.advertStartsWith(prefix))
            val row = ContactDTO.fromFrame(radioId, frame)
            contacts[row.id] = row
            rosterRevision = UUID.randomUUID()
            stampLocked(row, heardAt)
            LocalDirectContactResolution.Unique(contacts.getValue(row.id), rosterRevision)
        }
    }

    private fun stampLocked(contact: ContactDTO, date: Instant) {
        val stamp = minOf(date.epochSecond, Instant.now().epochSecond + 300).toUInt()
        contacts[contact.id] = contact.copy(lastHeardTimestamp = maxOf(contact.lastHeardTimestamp ?: 0u, stamp))
        val node = nodeFor(contact.radioId, contact.publicKey)
            ?: upsertNodeLocked(contact.radioId, contact.toContactFrame()).node
        nodes[node.id] = node.copy(lastHeard = date)
    }

    override suspend fun fetchContactPublicKeys(radioId: RadioId): SnapshotSet<Bytes> {
        fetchContactPublicKeysError?.let { throw it }
        failFetch()
        return synchronized(lock) { contacts.values.filter { it.radioId == radioId }.map { it.publicKey }.snapshotSet() }
    }

    override suspend fun saveContact(radioId: RadioId, frame: ContactFrame): ContactSaveResult = synchronized(lock) {
        val existing = contactFor(radioId, frame.publicKey)
        val dto = existing?.updating(frame) ?: ContactDTO.fromFrame(radioId, frame)
        contacts[dto.id] = dto
        rosterRevision = UUID.randomUUID()
        ContactSaveResult(dto.id, existing == null)
    }

    override suspend fun saveContact(dto: ContactDTO) = synchronized(lock) {
        contacts[dto.id] = dto
        rosterRevision = UUID.randomUUID()
    }

    override suspend fun deleteContact(key: EntityKey) = synchronized(lock) {
        deletedIds += key.id
        deleteContactError?.let { throw it }
        messages.values.removeAll { it.contactID == key.id }
        contacts.remove(key.id)
        rosterRevision = UUID.randomUUID()
        Unit
    }

    override suspend fun deleteContacts(
        radioId: RadioId,
        publicKeys: SnapshotSet<Bytes>,
        skippingPublicKeys: () -> SnapshotSet<Bytes>,
    ): SnapshotList<UUID> {
        val hold = synchronized(lock) {
            deleteContactsCalls += 1
            deleteHoldRequested.also { deleteHoldRequested = false }
        }
        // Swift CheckedContinuation: not cancellation-aware.
        if (hold) suspendCoroutine { continuation -> synchronized(lock) { deleteHold = continuation } }
        deleteContactError?.let { throw it }
        val ids = ArrayList<UUID>()
        for (key in publicKeys) {
            if (key in skippingPublicKeys()) continue
            val contact = synchronized(lock) { contactFor(radioId, key) } ?: continue
            if (key in skippingPublicKeys()) continue
            deleteContact(EntityKey(radioId, contact.id))
            ids += contact.id
        }
        return ids.snapshot()
    }

    override suspend fun deleteContactIfUnreferenced(key: EntityKey) {
        // Probe and delete with no suspension between, like the Swift store's single actor region.
        val referenced = synchronized(lock) { messages.values.any { it.contactID == key.id } }
        if (!referenced) deleteContact(key)
    }

    override suspend fun adoptOrphanedDirectMessages(
        radioId: RadioId,
        contacts: SnapshotList<ContactIdentity>,
    ): SnapshotMap<UUID, Long> = synchronized(lock) {
        val adopted = LinkedHashMap<UUID, Long>()
        for ((id, message) in messages.entries.toList()) {
            val prefix = message.senderKeyPrefix
            if (message.radioId != radioId || message.contactID != null || message.channelIndex != null) continue
            if (message.direction != MessageDirection.INCOMING || prefix == null || prefix.isEmpty) continue
            val match = contacts.filter { it.publicKey.advertStartsWith(prefix) }.singleOrNull() ?: continue
            messages[id] = message.copy(contactID = match.id)
            adopted[match.id] = (adopted[match.id] ?: 0L) + 1
            val contact = this.contacts[match.id] ?: continue
            val newest = maxOf(contact.lastMessageDate ?: message.sortDate, message.sortDate)
            val unread = if (message.isRead || contact.isBlocked) 0 else 1
            this.contacts[match.id] = contact.copy(lastMessageDate = newest, unreadCount = contact.unreadCount + unread)
        }
        adopted.snapshotMap()
    }

    override suspend fun touchContactHeard(radioId: RadioId, publicKey: Bytes, date: Instant): Boolean {
        synchronized(lock) { touchCalls += publicKey }
        touchContactHeardError?.let { throw it }
        return synchronized(lock) {
            val existing = contactFor(radioId, publicKey) ?: return@synchronized false
            val stamp = minOf(date.epochSecond, Instant.now().epochSecond + 300).toUInt()
            contacts[existing.id] = existing.copy(lastHeardTimestamp = maxOf(existing.lastHeardTimestamp ?: 0u, stamp))
            // Mirrors PersistenceStore: a known contact without a Discover row gets one.
            val node = nodeFor(radioId, publicKey) ?: upsertNodeLocked(radioId, existing.toContactFrame()).node
            nodes[node.id] = node.copy(lastHeard = date)
            true
        }
    }

    private fun nodeFor(radioId: RadioId, publicKey: Bytes): DiscoveredNodeDTO? =
        nodes.values.firstOrNull { it.radioId == radioId && it.publicKey == publicKey }

    private fun upsertNodeLocked(radioId: RadioId, frame: ContactFrame): DiscoveredNodeSaveResult {
        val existing = nodeFor(radioId, frame.publicKey)
        val node = DiscoveredNodeDTO(
            id = existing?.id ?: UUID.randomUUID(), radioId = radioId, publicKey = frame.publicKey, name = frame.name,
            typeRawValue = frame.typeRawValue, lastHeard = Instant.now(), lastAdvertTimestamp = frame.lastAdvertTimestamp,
            latitude = frame.latitude, longitude = frame.longitude, outPathLength = frame.outPathLength,
            outPath = frame.outPath, inboundHopCount = existing?.inboundHopCount,
            inboundHopAdvertTimestamp = existing?.inboundHopAdvertTimestamp,
        )
        nodes[node.id] = node
        return DiscoveredNodeSaveResult(node, existing == null)
    }

    override suspend fun upsertDiscoveredNode(radioId: RadioId, frame: ContactFrame): DiscoveredNodeSaveResult =
        synchronized(lock) { upsertNodeLocked(radioId, frame) }

    override suspend fun fetchDiscoveredNodes(radioId: RadioId): SnapshotList<DiscoveredNodeDTO> =
        synchronized(lock) { nodes.values.filter { it.radioId == radioId }.snapshot() }

    override suspend fun saveMessage(dto: MessageDTO) = synchronized(lock) { messages[dto.id] = dto }

    override suspend fun fetchMessages(contact: EntityKey, limit: Long, offset: Long): SnapshotList<MessageDTO> =
        synchronized(lock) {
            messages.values.filter { it.contactID == contact.id }.sortedBy { it.timestamp }
                .drop(offset.toInt()).take(limit.toInt()).snapshot()
        }
}
