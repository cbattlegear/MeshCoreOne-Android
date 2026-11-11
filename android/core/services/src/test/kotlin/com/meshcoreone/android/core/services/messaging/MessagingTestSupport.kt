// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/MessageService+Testing.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: WP-208 deterministic real-session/raw-transport harness; unsupported fake roles throw.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.ByteReader
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.*
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.MeshCoreSessionProtocol
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import com.meshcoreone.android.core.protocol.transport.mock.MockTransportException
import com.meshcoreone.android.core.services.contacts.ChannelDispatchFence
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.DynamicTest

internal val RADIO = RadioId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
internal val PEER_RADIO = RadioId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
internal val SELF = Bytes(ByteArray(32) { 0x01 })
internal val TARGET = Bytes(ByteArray(32) { 0x22 })
internal val CONTACT = UUID.fromString("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")
internal val BASE_TIME = Instant.parse("2026-10-05T12:00:00Z")
internal fun token(radio: RadioId = RADIO, generation: Long = 1) =
    SessionToken(ProcessEpoch(UUID.fromString("33333333-3333-3333-3333-333333333333")), Generation(generation), radio)

internal class TestMessagingClock(val scheduler: TestCoroutineScheduler) : SessionClock {
    var offset: Long = 0
    override val now: Duration get() = scheduler.currentTime.milliseconds
    override val wallClock: Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = BASE_TIME.plusMillis(scheduler.currentTime + offset)
    }
    override suspend fun sleepFor(duration: Duration) { delay(duration) }
}

internal class TestSignals(initial: SessionToken = token()) : ConnectionSignals {
    private val state = MutableStateFlow(TestSignals.snapshot(initial, DeviceConnectionState.READY))
    override val snapshot: StateFlow<ConnectionSnapshot> = state.asStateFlow()
    private val lock = Any()
    private val subscribers = mutableSetOf<kotlinx.coroutines.channels.Channel<ConnectionSnapshot>>()
    val subscriberCount: Int get() = synchronized(lock) { subscribers.size }
    fun set(token: SessionToken?, rung: DeviceConnectionState) {
        val value = TestSignals.snapshot(token, rung)
        synchronized(lock) {
            state.value = value
            subscribers.forEach { it.trySend(value).getOrThrow() }
        }
    }
    override suspend fun subscribeTransitions(): ConnectionSubscription = synchronized(lock) {
        val queue = kotlinx.coroutines.channels.Channel<ConnectionSnapshot>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        subscribers += queue
        object : ConnectionSubscription {
            override val initial = state.value
            override val transitions: Flow<ConnectionSnapshot> = flow {
                try { for (next in queue) emit(next) } finally { close() }
            }
            override fun close() { synchronized(lock) { subscribers.remove(queue); queue.cancel() } }
        }
    }
    companion object {
        fun snapshot(token: SessionToken?, rung: DeviceConnectionState) = ConnectionSnapshot(
            rung, if (rung == DeviceConnectionState.DISCONNECTED) ConnectionState.Disconnected else ConnectionState.Connected,
            null, ConnectionIntent.WantsConnection(), token, null,
        )
    }
}

private fun <T> unsupportedRole(type: Class<T>): T = type.cast(
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
        throw UnsupportedOperationException("Undeclared test operation: ${method.name}")
    },
)

private class RevisionedMap<K, V>(private val changed: (K, V?, V?) -> Unit) : LinkedHashMap<K, V>() {
    override fun put(key: K, value: V): V? {
        val old = super.put(key, value)
        changed(key, old, value)
        return old
    }
    override fun putAll(from: Map<out K, V>) { from.forEach { (key, value) -> put(key, value) } }
    override fun remove(key: K): V? {
        if (!containsKey(key)) return null
        val old = super.remove(key)
        changed(key, old, null)
        return old
    }
    override fun clear() { keys.toList().forEach { remove(it) } }
}

internal class RecordingStore : PersistenceStoreProtocol by unsupportedRole(PersistenceStoreProtocol::class.java),
    ChannelHistoryPersisting by unsupportedRole(ChannelHistoryPersisting::class.java),
    QueuedChannelTerminalPersisting, QueuedChannelReadinessPersisting, InlineChannelAttemptPersisting {
    val directAckAuthority = RecordingDirectAckAuthority(this)
    private val messageRevisions = mutableMapOf<EntityKey, Long>()
    private val messageIncarnations = mutableMapOf<EntityKey, Long>()
    private val pendingRevisions = mutableMapOf<EntityKey, Long>()
    internal fun messageRevision(key: EntityKey) = messageRevisions[key] ?: 0L
    internal fun messageIncarnation(key: EntityKey) = messageIncarnations[key] ?: 0L
    internal fun pendingRevision(key: EntityKey) = pendingRevisions[key] ?: 0L
    internal fun invalidateMessage(key: EntityKey) {
        messageRevisions[key] = Math.incrementExact(messageRevision(key))
    }
    internal fun invalidatePending(key: EntityKey, invalidateMessage: Boolean = true) {
        pendingRevisions[key] = Math.incrementExact(pendingRevision(key))
        if (invalidateMessage) pending[key]?.let { this.invalidateMessage(EntityKey(it.radioId, it.messageID)) }
    }
    val messages: MutableMap<EntityKey, MessageDTO> = RevisionedMap { key, old, new ->
        invalidateMessage(key)
        if ((old == null) != (new == null)) {
            messageIncarnations[key] = Math.incrementExact(messageIncarnation(key))
        }
    }
    val contacts = linkedMapOf<EntityKey, ContactDTO>()
    val channels = linkedMapOf<EntityKey, ChannelDTO>()
    val devices = linkedMapOf<RadioId, DeviceDTO>()
    val pending: MutableMap<EntityKey, PendingSendDTO> = RevisionedMap { key, old, new ->
        invalidatePending(key, invalidateMessage = false)
        old?.let { invalidateMessage(EntityKey(it.radioId, it.messageID)) }
        new?.let { invalidateMessage(EntityKey(it.radioId, it.messageID)) }
    }
    val channelAttempts = linkedMapOf<QueuedChannelAttemptKey, QueuedChannelAttempt>()
    val channelOccupants = mutableMapOf<Pair<RadioId, UByte>, UUID>()
    val calls = mutableListOf<String>()
    val faults = mutableMapOf<String, PersistenceStoreException>()
    var before: (suspend (String) -> Unit)? = null
    var after: (suspend (String) -> Unit)? = null
    private suspend fun access(name: String) {
        calls += name
        before?.invoke(name)
        faults[name]?.let { throw it }
    }
    internal suspend fun authorityAccess(name: String) = access(name)
    override suspend fun saveMessage(dto: MessageDTO) {
        access("saveMessage")
        val key = EntityKey(dto.radioId, dto.id)
        invalidateMessage(key); messages[key] = dto
    }
    fun seedChannelCreation(index: UByte = 0u, radio: RadioId = RADIO): ChannelOccupantSnapshot {
        val channel = ChannelDTO(radioId = radio, index = index, name = "Test", secret = Bytes(ByteArray(16) { 0x11 }))
        channels[EntityKey(radio, channel.id)] = channel
        val occupant = channelOccupants.getOrPut(radio to index, UUID::randomUUID)
        return ChannelOccupantSnapshot(channel, occupant)
    }
    override suspend fun fetchChannelOccupantSnapshot(radioId: RadioId, index: UByte): ChannelOccupantSnapshot? {
        access("fetchChannelOccupantSnapshot")
        val channel = channels.values.singleOrNull { it.radioId == radioId && it.index == index } ?: return null
        return channelOccupants[radioId to index]?.let { ChannelOccupantSnapshot(channel, it) }
    }
    suspend fun capturedChannelMessage(creation: ChannelMessageCreation, timestamp: UInt, createdAt: Instant): MessageDTO {
        access("createOrReconcileChannelMessage")
        val origin = creation.origin
        val key = EntityKey(origin.radioId, creation.messageID)
        messages[key]?.let { existing ->
            val conflict = when {
                existing.contactID != null || existing.channelIndex != origin.channelIndex ||
                    existing.direction != MessageDirection.OUTGOING -> ChannelHistoryConflict.MESSAGE_DESTINATION_MISMATCH
                existing.channelOccupantID == null -> ChannelHistoryConflict.UNKNOWN_MESSAGE_ORIGIN
                existing.channelOccupantID != origin.expectedOccupantID || existing.text != creation.text ||
                    existing.textType != creation.textType -> ChannelHistoryConflict.ORIGIN_MISMATCH
                else -> null
            }
            if (conflict != null) {
                throw PersistenceStoreException(PersistenceStoreError.InvalidData,
                    ChannelHistoryRejected(conflict, origin, key))
            }
            return existing
        }
        return MessageDTO(id = creation.messageID, radioId = origin.radioId, channelIndex = origin.channelIndex,
            text = creation.text, timestamp = timestamp, createdAt = createdAt, textType = creation.textType,
            channelOccupantID = origin.expectedOccupantID)
    }
    override suspend fun createOrReconcileChannelMessage(
        creation: ChannelMessageCreation, timestamp: UInt, createdAt: Instant,
    ): MessageDTO {
        val row = capturedChannelMessage(creation, timestamp, createdAt)
        if (messages[EntityKey(row.radioId, row.id)] == null) saveMessage(row)
        return row
    }
    override suspend fun validateChannelMessageOrigin(message: EntityKey): ChannelHistoryScope {
        access("validateChannelMessageOrigin")
        val row = messages[message]
            ?: throw PersistenceStoreException(PersistenceStoreError.InvalidData,
                ChannelHistoryRejected(ChannelHistoryConflict.MESSAGE_NOT_FOUND, messageKey = message))
        val index = row.channelIndex
        if (index == null || row.contactID != null) {
            throw PersistenceStoreException(PersistenceStoreError.InvalidData,
                ChannelHistoryRejected(ChannelHistoryConflict.MESSAGE_DESTINATION_MISMATCH, messageKey = message))
        }
        val occupant = row.channelOccupantID
            ?: throw PersistenceStoreException(PersistenceStoreError.InvalidData,
                ChannelHistoryRejected(ChannelHistoryConflict.UNKNOWN_MESSAGE_ORIGIN, messageKey = message))
        val origin = ChannelHistoryScope(row.radioId, index, occupant)
        val current = channelOccupants[row.radioId to index]
        if (current == null || current != occupant) {
            throw PersistenceStoreException(PersistenceStoreError.InvalidData,
                ChannelHistoryRejected(if (current == null) ChannelHistoryConflict.UNKNOWN_AUTHORITY
                    else ChannelHistoryConflict.STALE_OCCUPANT, origin, message))
        }
        return origin
    }
    override suspend fun fetchMessage(key: EntityKey): MessageDTO? { access("fetchMessage"); return messages[key] }
    override suspend fun fetchMessage(deduplicationKey: String, radioId: RadioId): MessageDTO? =
        messages.values.firstOrNull { it.radioId == radioId && it.deduplicationKey == deduplicationKey }
    override suspend fun isDuplicateMessage(deduplicationKey: String, radioId: RadioId): Boolean =
        fetchMessage(deduplicationKey, radioId) != null
    override suspend fun fetchMessages(contact: EntityKey, limit: Long, offset: Long): SnapshotList<MessageDTO> =
        messages.values.filter { it.radioId == contact.radioId && it.contactID == contact.id }.snapshot()
    override suspend fun fetchMessages(radioId: RadioId, channelIndex: UByte, limit: Long, offset: Long): SnapshotList<MessageDTO> =
        messages.values.filter { it.radioId == radioId && it.channelIndex == channelIndex }.snapshot()
    override suspend fun updateMessageStatus(key: EntityKey, status: MessageStatus) {
        access("updateMessageStatus"); messages[key]?.let { invalidateMessage(key); messages[key] = it.copy(status = status) }
    }
    override suspend fun updateMessageStatusUnlessDelivered(key: EntityKey, status: MessageStatus): Boolean {
        access("updateMessageStatusUnlessDelivered")
        val row = messages[key] ?: return false
        if (row.status == MessageStatus.DELIVERED) return false
        invalidateMessage(key)
        messages[key] = row.copy(status = status)
        return true
    }
    override suspend fun clearRetryingToSent(key: EntityKey): Boolean {
        access("clearRetryingToSent")
        val row = messages[key] ?: return false
        if (row.status == MessageStatus.FAILED || row.status == MessageStatus.DELIVERED) return false
        invalidateMessage(key)
        messages[key] = row.copy(status = MessageStatus.SENT)
        return true
    }
    override suspend fun updateMessageAck(key: EntityKey, ackCode: UInt, status: MessageStatus, roundTripTime: UInt?) {
        access("updateMessageAck")
        val row = messages[key] ?: return
        if (row.status == MessageStatus.DELIVERED && status != MessageStatus.DELIVERED ||
            row.status == MessageStatus.FAILED && status == MessageStatus.DELIVERED) return
        invalidateMessage(key)
        messages[key] = row.copy(ackCode = ackCode, status = status, roundTripTime = roundTripTime ?: row.roundTripTime)
    }
    override suspend fun updateMessageRetryStatus(key: EntityKey, status: MessageStatus, retryAttempt: Long, maxRetryAttempts: Long) {
        access("updateMessageRetryStatus")
        messages[key]?.takeIf { it.status != MessageStatus.DELIVERED }?.let {
            invalidateMessage(key)
            messages[key] = it.copy(status = status, retryAttempt = retryAttempt, maxRetryAttempts = maxRetryAttempts)
        }
    }
    override suspend fun updateMessageTimestamp(key: EntityKey, timestamp: UInt) {
        access("updateMessageTimestamp"); messages[key]?.let { invalidateMessage(key); messages[key] = it.copy(timestamp = timestamp) }
    }
    override suspend fun incrementMessageSendCount(key: EntityKey): Long {
        access("incrementMessageSendCount")
        val row = messages[key] ?: return 0
        val count = Math.incrementExact(row.sendCount)
        invalidateMessage(key)
        messages[key] = row.copy(sendCount = count)
        return count
    }
    override suspend fun updateMessageHeardRepeats(key: EntityKey, heardRepeats: Long) {
        access("updateMessageHeardRepeats"); messages[key]?.let { invalidateMessage(key); messages[key] = it.copy(heardRepeats = heardRepeats) }
    }
    override suspend fun deleteMessageRepeats(message: EntityKey) { access("deleteMessageRepeats") }
    override suspend fun deleteMessage(key: EntityKey) {
        invalidateMessage(key); messages.remove(key); deletePendingSendsForMessage(key)
    }
    override suspend fun hasOutgoingSentDM(radioId: RadioId, ackCode: UInt): Boolean =
        messages.values.any { it.radioId == radioId && it.ackCode == ackCode && it.status == MessageStatus.SENT && it.channelIndex == null }
    override suspend fun fetchContact(key: EntityKey): ContactDTO? { access("fetchContact"); return contacts[key] }
    override suspend fun fetchContactByPrefix(radioId: RadioId, publicKeyPrefix: Bytes): ContactDTO? {
        access("fetchContactByPrefix")
        return contacts.values.filter { it.radioId == radioId && it.publicKey.prefix(publicKeyPrefix.size) == publicKeyPrefix }.singleOrNull()
    }
    override suspend fun saveContact(dto: ContactDTO) { contacts[EntityKey(dto.radioId, dto.id)] = dto }
    override suspend fun saveContact(radioId: RadioId, frame: ContactFrame): ContactSaveResult {
        val existing = contacts.values.firstOrNull { it.radioId == radioId && it.publicKey == frame.publicKey }
        val dto = existing?.updating(frame) ?: ContactDTO.fromFrame(radioId, frame)
        saveContact(dto)
        return ContactSaveResult(dto.id, existing == null)
    }
    override suspend fun updateContactLastMessage(key: EntityKey, date: Instant?) {
        access("updateContactLastMessage"); contacts[key]?.let { contacts[key] = it.copy(lastMessageDate = date) }
    }
    override suspend fun fetchConversations(radioId: RadioId): SnapshotList<ContactDTO> =
        contacts.values.filter { it.radioId == radioId && it.lastMessageDate != null }.snapshot()
    override suspend fun fetchDevice(radioId: RadioId): DeviceDTO? { access("fetchDevice"); return devices[radioId] }
    override suspend fun fetchDevice(id: UUID): DeviceDTO? = devices.values.firstOrNull { it.id == id }
    override suspend fun saveDevice(dto: DeviceDTO) { devices[dto.radioId] = dto }
    override suspend fun fetchChannel(radioId: RadioId, index: UByte): ChannelDTO? {
        access("fetchChannel"); return channels.values.firstOrNull { it.radioId == radioId && it.index == index }
    }
    override suspend fun fetchChannelOccupantID(radioId: RadioId, index: UByte): UUID? {
        access("fetchChannelOccupantID"); return channelOccupants[radioId to index]
    }
    override suspend fun updateChannelLastMessage(key: EntityKey, date: Instant?) {
        access("updateChannelLastMessage"); channels[key]?.let { channels[key] = it.copy(lastMessageDate = date) }
    }
    override suspend fun applyChannelMessageMetadataIfCurrentOccupant(
        scope: ChannelHistoryScope, lastMessageDate: Instant?, incrementUnreadCount: Boolean,
    ): ChannelOccupantMetadataMutationOutcome {
        access("applyChannelMessageMetadataIfCurrentOccupant")
        val channel = channels.values.singleOrNull { it.radioId == scope.radioId && it.index == scope.channelIndex }
        if (channel == null || !channel.isEnabled || channelOccupants[scope.radioId to scope.channelIndex] != scope.expectedOccupantID) {
            return ChannelOccupantMetadataMutationOutcome.STALE_OR_UNPROVED
        }
        channels[EntityKey(channel.radioId, channel.id)] = channel.copy(lastMessageDate = lastMessageDate,
            unreadCount = if (incrementUnreadCount) Math.incrementExact(channel.unreadCount) else channel.unreadCount)
        return ChannelOccupantMetadataMutationOutcome.APPLIED
    }
    override suspend fun insertPendingSendAssigningSequence(dto: PendingSendDTO): Long {
        access("insertPendingSendAssigningSequence")
        val sequence = Math.incrementExact(pending.values.filter { it.radioId == dto.radioId }.maxOfOrNull { it.sequence } ?: 0)
        val key = EntityKey(dto.radioId, dto.id)
        invalidatePending(key)
        invalidateMessage(EntityKey(dto.radioId, dto.messageID))
        if (dto.kind == PendingSendKind.CHANNEL) {
            if (key in pending || channelAttempts.keys.any { it.radioId == dto.radioId && it.pendingID == dto.id } ||
                pending.values.any { it.radioId == dto.radioId && it.messageID == dto.messageID &&
                    it.channelDispatchState != QueuedChannelDispatchState.READY }
            ) reject(dto, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
            if (dto.channelDispatchState != null || dto.channelAttemptID != null) {
                reject(dto, QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION)
            }
        }
        pending[key] = dto.copy(sequence = sequence,
            channelDispatchState = QueuedChannelDispatchState.READY.takeIf {
                dto.kind == PendingSendKind.CHANNEL && dto.channelOccupantID != null
            })
        return sequence
    }
    override suspend fun upsertPendingSend(dto: PendingSendDTO) {
        val key = EntityKey(dto.radioId, dto.id)
        invalidatePending(key)
        invalidateMessage(EntityKey(dto.radioId, dto.messageID))
        pending[key] = dto
    }
    override suspend fun replacePendingSendForRetry(messageID: UUID, dto: PendingSendDTO): Long {
        access("replacePendingSendForRetry")
        require(dto.kind == PendingSendKind.DM && dto.messageID == messageID)
        val key = EntityKey(dto.radioId, messageID)
        pending.values.filter { it.radioId == dto.radioId && it.messageID == messageID }
            .map { EntityKey(it.radioId, it.id) }.forEach { pending.remove(it) }
        messages[key]?.takeIf { it.status != MessageStatus.DELIVERED }?.let {
            messages[key] = it.copy(status = MessageStatus.PENDING)
        }
        val sequence = Math.incrementExact(pending.values.filter { it.radioId == dto.radioId }
            .maxOfOrNull { it.sequence } ?: 0)
        pending[EntityKey(dto.radioId, dto.id)] = dto.copy(sequence = sequence)
        directAckAuthority.recordExplicitRetry(key, dto.id)
        return sequence
    }
    override suspend fun fetchPendingSends(radioId: RadioId): SnapshotList<PendingSendDTO> {
        access("fetchPendingSends"); return pending.values.filter { it.radioId == radioId }.sortedBy { it.sequence }.snapshot()
    }
    override suspend fun fetchPendingSendsForMessage(key: EntityKey): SnapshotList<PendingSendDTO> {
        access("fetchPendingSendsForMessage")
        return pending.values.filter { it.radioId == key.radioId && it.messageID == key.id }.sortedBy { it.sequence }.snapshot()
    }
    override suspend fun hasPendingSend(key: EntityKey): Boolean {
        access("hasPendingSend"); return pending.values.any { it.radioId == key.radioId && it.messageID == key.id }
    }
    override suspend fun incrementPendingSendAttemptCount(key: EntityKey): Long? {
        access("incrementPendingSendAttemptCount")
        val row = pending.values.firstOrNull { it.radioId == key.radioId && it.messageID == key.id } ?: return null
        val next = Math.incrementExact(row.attemptCount ?: 0)
        val pendingKey = EntityKey(row.radioId, row.id)
        invalidatePending(pendingKey)
        pending[pendingKey] = row.copy(attemptCount = next)
        return next
    }
    override suspend fun incrementReadyQueuedChannelAttemptCount(pending: EntityKey, messageID: UUID): Long {
        access("incrementReadyQueuedChannelAttemptCount")
        val row = this.pending[pending] ?: throw PersistenceStoreException(PersistenceStoreError.InvalidData,
            QueuedChannelAttemptRejected(pending, QueuedChannelAttemptConflict.MISSING_SUBMISSION))
        if (row.kind != PendingSendKind.CHANNEL || row.messageID != messageID) {
            reject(row, QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)
        }
        if (row.channelDispatchState == null) reject(row, QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION)
        if (row.channelDispatchState != QueuedChannelDispatchState.READY || row.channelAttemptID != null) {
            reject(row, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
        }
        val message = messages[EntityKey(row.radioId, messageID)]
        if (row.channelIndex == null || row.contactID != null || row.channelOccupantID == null ||
            message == null || message.contactID != null || message.direction != MessageDirection.OUTGOING ||
            message.channelIndex != row.channelIndex || message.channelOccupantID != row.channelOccupantID ||
            message.text != row.messageText
        ) reject(row, QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)
        if (this.pending.values.any { it.radioId == row.radioId && it.messageID == messageID && it.id != row.id &&
                (it.channelDispatchState != QueuedChannelDispatchState.READY || it.channelAttemptID != null) }) {
            reject(row, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
        }
        val next = Math.incrementExact(row.attemptCount ?: 0)
        this.pending[pending] = row.copy(attemptCount = next)
        after?.invoke("incrementReadyQueuedChannelAttemptCount")
        return next
    }
    override suspend fun deletePendingSendsForMessage(key: EntityKey) {
        access("deletePendingSendsForMessage")
        pending.filterValues { it.radioId == key.radioId && it.messageID == key.id }.keys.forEach {
            invalidatePending(it)
        }
        pending.filterValues { it.radioId == key.radioId && it.messageID == key.id }.keys.toList().forEach {
            pending.remove(it)
        }
    }
    override suspend fun beginQueuedChannelAttempt(
        key: QueuedChannelAttemptKey, expectedOccupantID: UUID, wireTimestamp: UInt,
    ): QueuedChannelAttempt {
        access("beginQueuedChannelAttempt")
        val pendingKey = EntityKey(key.radioId, key.pendingID)
        val row = pending[pendingKey] ?: reject(key, QueuedChannelAttemptConflict.MISSING_SUBMISSION)
        val attempt = preparedChannelAttempt(row, key, expectedOccupantID, wireTimestamp)
        channelAttempts[key] = attempt
        pending[pendingKey] = row.copy(channelDispatchState = QueuedChannelDispatchState.DISPATCHING, channelAttemptID = key.attemptID)
        after?.invoke("beginQueuedChannelAttempt")
        return attempt
    }
    override suspend fun beginInlineChannelAttempt(
        submission: PendingSendDTO, key: QueuedChannelAttemptKey, expectedOccupantID: UUID, wireTimestamp: UInt,
    ): QueuedChannelAttempt {
        access("beginInlineChannelAttempt")
        val pendingKey = EntityKey(key.radioId, key.pendingID)
        if (submission.radioId != key.radioId || submission.id != key.pendingID || submission.messageID != key.messageID
        ) reject(submission, QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)
        if (submission.channelDispatchState != null || submission.channelAttemptID != null) {
            reject(submission, QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION)
        }
        if (pendingKey in pending || channelAttempts.keys.any { it.radioId == key.radioId && it.pendingID == key.pendingID } ||
            pending.values.any { it.radioId == key.radioId && it.messageID == key.messageID }
        ) reject(submission, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
        val sequence = Math.incrementExact(pending.values.filter { it.radioId == key.radioId }.maxOfOrNull { it.sequence } ?: 0)
        val row = submission.copy(sequence = sequence, channelDispatchState = QueuedChannelDispatchState.READY)
        val attempt = preparedChannelAttempt(row, key, expectedOccupantID, wireTimestamp)
        // No suspension or observable READY insertion between the two committed fixture writes.
        channelAttempts[key] = attempt
        pending[pendingKey] = row.copy(channelDispatchState = QueuedChannelDispatchState.DISPATCHING, channelAttemptID = key.attemptID)
        after?.invoke("beginInlineChannelAttempt")
        return attempt
    }
    private fun preparedChannelAttempt(
        row: PendingSendDTO, key: QueuedChannelAttemptKey, expectedOccupantID: UUID, wireTimestamp: UInt,
    ): QueuedChannelAttempt {
        val message = messages[EntityKey(key.radioId, key.messageID)]
        if (row.channelDispatchState != QueuedChannelDispatchState.READY || row.channelAttemptID != null) {
            reject(row, QueuedChannelAttemptConflict.SUBMISSION_NOT_READY)
        }
        if (row.kind != PendingSendKind.CHANNEL || row.contactID != null ||
            row.messageID != key.messageID || row.channelOccupantID != expectedOccupantID ||
            message?.channelOccupantID != expectedOccupantID || message.channelIndex != row.channelIndex ||
            message.text != row.messageText || message.direction != MessageDirection.OUTGOING || message.contactID != null
        ) reject(row, QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)
        if (pending.values.any { it.radioId == key.radioId && it.messageID == key.messageID && it.id != key.pendingID &&
                (it.channelDispatchState != QueuedChannelDispatchState.READY || it.channelAttemptID != null) }) {
            reject(row, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
        }
        if (channelAttempts.keys.any { it.radioId == key.radioId && it.attemptID == key.attemptID }) {
            reject(row, QueuedChannelAttemptConflict.ATTEMPT_ID_REUSED)
        }
        return QueuedChannelAttempt(key, requireNotNull(row.channelIndex), expectedOccupantID,
            wireTimestamp, row.isResend, QueuedChannelAttemptPhase.DISPATCHING, false)
    }
    override suspend fun settleQueuedChannelAttempt(
        key: QueuedChannelAttemptKey, outcome: QueuedChannelAttemptOutcome,
    ): QueuedChannelAttempt {
        access("settleQueuedChannelAttempt")
        val attempt = channelAttempts[key] ?: reject(key, QueuedChannelAttemptConflict.MISSING_ATTEMPT)
        if (attempt.phase == outcome.phase) return attempt
        if (attempt.phase != QueuedChannelAttemptPhase.DISPATCHING) reject(key, QueuedChannelAttemptConflict.CONFLICTING_SETTLEMENT)
        val pendingKey = EntityKey(key.radioId, key.pendingID)
        val row = pending[pendingKey] ?: reject(key, QueuedChannelAttemptConflict.MISSING_SUBMISSION)
        if (row.messageID != key.messageID || row.channelAttemptID != key.attemptID ||
            row.channelDispatchState != QueuedChannelDispatchState.DISPATCHING ||
            row.channelOccupantID != attempt.channelOccupantID
        ) reject(row, QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)
        val settled = attempt.copy(phase = outcome.phase)
        channelAttempts[key] = settled
        pending[pendingKey] = row.copy(channelDispatchState = outcome.submissionState,
            channelAttemptID = key.attemptID.takeUnless { outcome == QueuedChannelAttemptOutcome.DEFINITELY_UNSENT })
        after?.invoke("settleQueuedChannelAttempt")
        return settled
    }
    override suspend fun fetchQueuedChannelAttempt(key: QueuedChannelAttemptKey): QueuedChannelAttempt? {
        access("fetchQueuedChannelAttempt"); return channelAttempts[key]
    }
    override suspend fun fetchQueuedChannelAttempts(pending: EntityKey): SnapshotList<QueuedChannelAttempt> {
        access("fetchQueuedChannelAttempts")
        return channelAttempts.values.filter { it.key.radioId == pending.radioId && it.key.pendingID == pending.id }.snapshot()
    }
    override suspend fun completeAcceptedQueuedChannelAttempt(key: QueuedChannelAttemptKey) {
        access("completeAcceptedQueuedChannelAttempt")
        val attempt = channelAttempts[key] ?: reject(key, QueuedChannelAttemptConflict.MISSING_ATTEMPT)
        if (attempt.phase != QueuedChannelAttemptPhase.ACCEPTED) reject(key, QueuedChannelAttemptConflict.NOT_ACCEPTED)
        if (attempt.isCompleted) return
        val row = pending[EntityKey(key.radioId, key.pendingID)]
            ?: reject(key, QueuedChannelAttemptConflict.MISSING_SUBMISSION)
        if (pending.values.any { it.radioId == key.radioId && it.messageID == key.messageID && it.id != key.pendingID }) {
            reject(row, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
        }
        val messageKey = EntityKey(key.radioId, key.messageID)
        val message = messages.getValue(messageKey)
        if (row.messageID != key.messageID || row.channelDispatchState != QueuedChannelDispatchState.ACCEPTED ||
            row.channelAttemptID != key.attemptID || row.channelOccupantID != attempt.channelOccupantID ||
            row.isResend != attempt.isResend || message.channelOccupantID != attempt.channelOccupantID ||
            message.channelIndex != attempt.channelIndex || message.text != row.messageText ||
            message.direction != MessageDirection.OUTGOING || message.contactID != null
        ) reject(row, QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)
        messages[messageKey] = message.copy(
            status = if (message.status == MessageStatus.DELIVERED) MessageStatus.DELIVERED else MessageStatus.SENT,
            timestamp = attempt.wireTimestamp, sendCount = message.sendCount + if (attempt.isResend) 1 else 0,
            heardRepeats = if (attempt.isResend) 0 else message.heardRepeats)
        pending.remove(EntityKey(key.radioId, key.pendingID))
        channelAttempts[key] = attempt.copy(isCompleted = true)
        after?.invoke("completeAcceptedQueuedChannelAttempt")
    }
    override suspend fun settleAndRetireDefinitelyUnsentQueuedChannelAttempt(
        key: QueuedChannelAttemptKey,
    ): QueuedChannelRetirement {
        access("settleAndRetireDefinitelyUnsentQueuedChannelAttempt")
        val row = pending[EntityKey(key.radioId, key.pendingID)]
            ?: reject(key, QueuedChannelAttemptConflict.MISSING_SUBMISSION)
        val latest = channelAttempts.values.lastOrNull { it.key.radioId == key.radioId && it.key.pendingID == key.pendingID }
        if (row.channelDispatchState != QueuedChannelDispatchState.DISPATCHING || row.channelAttemptID != key.attemptID ||
            latest?.key != key || latest.phase != QueuedChannelAttemptPhase.DISPATCHING || latest.isCompleted ||
            pending.values.any { it.radioId == key.radioId && it.messageID == key.messageID && it.id != key.pendingID }
        ) reject(row, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
        val messageKey = EntityKey(key.radioId, key.messageID)
        val message = messages.getValue(messageKey)
        if (row.messageID != key.messageID || row.kind != PendingSendKind.CHANNEL || row.contactID != null ||
            row.channelOccupantID != latest.channelOccupantID || row.channelIndex != latest.channelIndex ||
            row.isResend != latest.isResend || message.direction != MessageDirection.OUTGOING || message.contactID != null ||
            message.channelOccupantID != latest.channelOccupantID || message.channelIndex != latest.channelIndex ||
            message.text != row.messageText
        ) reject(row, QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)
        val status = if (message.status == MessageStatus.DELIVERED) message.status else MessageStatus.FAILED
        pending.remove(EntityKey(key.radioId, key.pendingID))
        messages[messageKey] = message.copy(status = status,
            failureSeen = if (status != message.status) false else message.failureSeen)
        channelAttempts[key] = latest.copy(phase = QueuedChannelAttemptPhase.DEFINITELY_UNSENT)
        after?.invoke("settleAndRetireDefinitelyUnsentQueuedChannelAttempt")
        return QueuedChannelRetirement(status, status != message.status)
    }

    override suspend fun retireDefinitelyUnsentQueuedChannelAttempt(key: QueuedChannelAttemptKey): QueuedChannelRetirement {
        access("retireDefinitelyUnsentQueuedChannelAttempt")
        val row = pending[EntityKey(key.radioId, key.pendingID)]
            ?: reject(key, QueuedChannelAttemptConflict.MISSING_SUBMISSION)
        val latest = channelAttempts.values.lastOrNull { it.key.radioId == key.radioId && it.key.pendingID == key.pendingID }
        if (row.channelDispatchState != QueuedChannelDispatchState.READY || row.channelAttemptID != null ||
            latest?.key != key || latest.phase != QueuedChannelAttemptPhase.DEFINITELY_UNSENT ||
            pending.values.any { it.radioId == key.radioId && it.messageID == key.messageID && it.id != key.pendingID }
        ) reject(row, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
        val messageKey = EntityKey(key.radioId, key.messageID)
        val message = messages.getValue(messageKey)
        if (row.messageID != key.messageID || row.kind != PendingSendKind.CHANNEL || row.contactID != null ||
            latest.isCompleted || row.channelOccupantID != latest.channelOccupantID ||
            row.channelIndex != latest.channelIndex || row.isResend != latest.isResend ||
            message.direction != MessageDirection.OUTGOING || message.contactID != null ||
            message.channelOccupantID != latest.channelOccupantID || message.channelIndex != latest.channelIndex ||
            message.text != row.messageText
        ) reject(row, QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)
        val status = if (message.status == MessageStatus.DELIVERED) message.status else MessageStatus.FAILED
        pending.remove(EntityKey(key.radioId, key.pendingID))
        messages[messageKey] = message.copy(status = status)
        after?.invoke("retireDefinitelyUnsentQueuedChannelAttempt")
        return QueuedChannelRetirement(status, status != message.status)
    }
    private fun reject(row: PendingSendDTO, reason: QueuedChannelAttemptConflict): Nothing =
        throw PersistenceStoreException(PersistenceStoreError.InvalidData,
            QueuedChannelAttemptRejected(EntityKey(row.radioId, row.id), reason))
    private fun reject(key: QueuedChannelAttemptKey, reason: QueuedChannelAttemptConflict): Nothing =
        throw PersistenceStoreException(PersistenceStoreError.InvalidData,
            QueuedChannelAttemptRejected(EntityKey(key.radioId, key.pendingID), reason))
}

internal class FirmwareTransport(private val senderPublicKey: Bytes = SELF) : MeshTransport {
    val mock = MockTransport()
    var appStartReply: Bytes = selfInfoPacket(senderPublicKey)
    var beforeReply: (suspend (Bytes) -> Unit)? = null
    var holdMessageReplies = false
    var holdChannelReplies = false
    var holdGetReplies = false
    var acknowledge = false
    var ackBeforeAcceptance = false
    var acceptanceGate: CompletableDeferred<Unit>? = null
    var expectedAckOverride: Bytes? = null
    var suggestedTimeout: UInt = 10u
    var directError: UByte? = null
    var channelError: UByte? = null
    val channelReplies = mutableMapOf<UByte, ChannelInfo>()
    val incomingMessages = ArrayDeque<Bytes>()
    var afterNoMore: (suspend () -> Unit)? = null
    val sentData: List<Bytes> get() = mock.sentData
    var disconnectCalls = 0
        private set
    override suspend fun isConnected() = mock.isConnected()
    override suspend fun receivedData() = mock.receivedData()
    override suspend fun supportsWriteWithoutResponse() = false
    override suspend fun supportsPipelinedReads() = false
    override suspend fun connect() = mock.connect()
    override suspend fun disconnect() { disconnectCalls++; mock.disconnect() }
    override suspend fun send(data: Bytes) {
        try { mock.send(data) }
        catch (failure: MockTransportException.SendFailed) { throw MeshTransportError.SendFailed(failure.reason) }
        catch (failure: MockTransportException.NotConnected) { throw MeshTransportError.NotConnected }
        beforeReply?.invoke(data)
        when (CommandCode.fromRawValue(data[0])) {
            CommandCode.APP_START -> mock.simulateReceive(appStartReply)
            CommandCode.SEND_MESSAGE -> {
                directError?.let { mock.simulateError(it); return }
                if (holdMessageReplies) return
                val reader = ByteReader(data, 3)
                val stamp = reader.readUInt32LE()
                reader.readBytes(6)
                val text = reader.readBytes(reader.remaining).toByteArray().toString(Charsets.UTF_8)
                val input = ByteWriter().appendUInt32LE(stamp).appendUInt8((data[2].toInt() and 3).toUByte())
                    .append(Bytes.utf8(text)).append(senderPublicKey).toBytes().toByteArray()
                val predicted = Bytes(MessageDigest.getInstance("SHA-256").digest(input)).prefix(4)
                val ack = expectedAckOverride ?: predicted
                if (acknowledge && ackBeforeAcceptance) pushAck(ack, 123u)
                acceptanceGate?.await()
                mock.simulateReceive(ByteWriter().appendUInt8(ResponseCode.MESSAGE_SENT.rawValue).appendUInt8(0u)
                    .append(ack).appendUInt32LE(suggestedTimeout).toBytes())
                if (acknowledge && !ackBeforeAcceptance) pushAck(ack, 123u)
            }
            CommandCode.SEND_CHANNEL_MESSAGE -> {
                channelError?.let { mock.simulateError(it); return }
                if (holdChannelReplies) return
                mock.simulateOK()
            }
            CommandCode.RESET_PATH -> mock.simulateOK()
            CommandCode.GET_CONTACT_BY_KEY -> mock.simulateError(2u)
            CommandCode.GET_CHANNEL -> {
                val channel = channelReplies[data[1]]
                if (channel == null) mock.simulateError(2u)
                else mock.simulateReceive(ByteWriter().appendUInt8(ResponseCode.CHANNEL_INFO.rawValue).appendUInt8(channel.index)
                    .append(Bytes.utf8(channel.name).paddedOrTruncated(32)).append(channel.secret).toBytes())
            }
            CommandCode.GET_MESSAGE -> if (!holdGetReplies) {
                val empty = incomingMessages.isEmpty()
                mock.simulateReceive(if (empty) Bytes.of(ResponseCode.NO_MORE_MESSAGES.rawValue.toInt()) else incomingMessages.removeFirst())
                if (empty) afterNoMore?.also { afterNoMore = null }?.invoke()
            }
            else -> throw AssertionError("Unexpected deterministic firmware command ${data[0]}")
        }
    }
    suspend fun pushAck(code: Bytes, trip: UInt? = null) {
        val builder = ByteWriter().appendUInt8(ResponseCode.ACK.rawValue).append(code)
        if (trip != null) builder.appendUInt32LE(trip)
        mock.simulateReceive(builder.toBytes())
    }
    companion object {
        fun selfInfoPacket(publicKey: Bytes = SELF): Bytes = ByteWriter().appendUInt8(ResponseCode.SELF_INFO.rawValue)
            .appendUInt8(1u).appendUInt8(22u).appendUInt8(22u).append(publicKey)
            .appendInt32LE(0).appendInt32LE(0).appendUInt8(0u).appendUInt8(0u).appendUInt8(0u)
            .appendUInt32LE(915_000u).appendUInt32LE(125_000u).appendUInt8(7u).appendUInt8(5u)
            .append(Bytes.utf8("Test")).toBytes()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class Harness(
    val test: TestScope,
    config: MessageServiceConfig = MessageServiceConfig(),
    val store: RecordingStore = RecordingStore(),
    generation: Long = 1,
    sessionTimeout: Double = 2.0,
    private val eventDispatcher: EventDispatcher? = null,
    val contact: ContactDTO = ContactDTO(CONTACT, RADIO, TARGET, "Peer", lastHeardTimestamp = 0u),
    val localPublicKey: Bytes = SELF,
    startupPacket: Bytes? = null,
    radio: RadioId = RADIO,
) {
    val token = token(radio, generation)
    private val fixtureJob = SupervisorJob(test.coroutineContext[Job])
    val scope = CoroutineScope(test.coroutineContext + fixtureJob)
    val signals = TestSignals(token)
    val clock = TestMessagingClock(test.testScheduler)
    val transport = FirmwareTransport(localPublicKey).also { if (startupPacket != null) it.appStartReply = startupPacket }
    val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = sessionTimeout), clock, scope.coroutineContext)
    private val serviceSession = eventDispatcher?.let { dispatcher ->
        object : MeshCoreSessionProtocol by session {
            override fun events(): Flow<MeshEvent> = dispatcher.subscribe()
            override fun events(filter: EventFilter): Flow<MeshEvent> = dispatcher.subscribe(filter)
        }
    } ?: session
    private val channelIdentity = ChannelIdentityMutex()
    val channelFence = ChannelDispatchFence(channelIdentity)
    val channelLease = channelFence.openSession(token, serviceSession, ChannelDispatchAdmission(token, serviceSession))
    val diagnostics = mutableListOf<MessagingDiagnostic>()
    private val queues = mutableListOf<ChatSendQueueService>()
    private val pollers = mutableListOf<MessagePollingService>()
    val service = MessageService(token, localPublicKey, serviceSession, store, signals, scope, config, clock,
        jitter = { 1.0 }, reporter = MessagingIssueReporter { diagnostics += it },
        directAckAuthority = store.directAckAuthority, directPendingCompletionAuthority = store.directAckAuthority,
        channelDispatchLease = channelLease)
    init { synchronized(fixtures) { fixtures.getOrPut(test) { mutableListOf() } += this } }
    suspend fun start() {
        store.saveContact(contact)
        session.start()
        assertEquals(localPublicKey, session.currentSelfInfo?.publicKey)
        channelIdentity.withChannelOperationScope { identity ->
            channelFence.recordReconciled(channelLease, identity,
                store.channelOccupants.keys.filter { it.first == token.radioId }.map { it.second })
        }
    }
    fun stored(id: UUID): MessageDTO = assertNotNull(store.messages[EntityKey(RADIO, id)])
    suspend fun message(
        status: MessageStatus = MessageStatus.SENT, channel: UByte? = null,
        text: String = "hello", timestamp: UInt = 100u,
    ): MessageDTO {
        val occupant = channel?.let { index ->
            store.channelOccupants.getOrPut(RADIO to index, UUID::randomUUID).also {
                channelIdentity.withChannelOperationScope { identity ->
                    channelFence.recordReconciled(channelLease, identity, listOf(index))
                }
            }
        }
        val dto = MessageDTO(radioId = RADIO, contactID = if (channel == null) CONTACT else null,
            channelIndex = channel, text = text, timestamp = timestamp, createdAt = clock.wallClock.instant(), status = status,
            channelOccupantID = occupant)
        store.saveMessage(dto)
        return dto
    }
    fun tracking(
        message: MessageDTO, code: Bytes = Bytes.of(0xAB, 0xCD, 0xEF, 0x12), age: Long = 0,
        timeout: Double = 30.0, delivered: Boolean = false,
    ) = PendingAck(message.id, CONTACT, SnapshotSet(listOf(code)), clock.wallClock.instant().minusSeconds(age), timeout, delivered)
    suspend fun close() {
        var failure: Exception? = null
        suspend fun release(action: suspend () -> Unit) {
            try { action() }
            catch (cause: Exception) {
                val first = failure
                if (first == null) failure = cause else first.addSuppressed(cause)
            }
        }
        try {
            pollers.asReversed().forEach { release { it.close() } }
            queues.asReversed().forEach { release { it.shutdown() } }
            release { service.close() }
            channelFence.retire(channelLease)
            release { session.stop() }
        }
        finally {
            fixtureJob.cancelAndJoin()
            eventDispatcher?.finishAllSubscriptions()
        }
        failure?.let { throw it }
    }
    fun sends(code: CommandCode): List<Bytes> = transport.sentData.filter { it[0] == code.rawValue }
    fun queue(
        config: ChatSendQueueConfig = ChatSendQueueConfig(),
        query: MessagingChannelQuery = MessagingChannelQuery { null },
        indexer: OutgoingChannelReactionIndexer = OutgoingChannelReactionIndexer { _, _, _, _, _ -> },
        progress: ChatSendQueueProgress = ChatSendQueueProgress(),
        onQueryAccounting: ((ChannelQueryAccountingEvent) -> Unit)? = null,
    ) = ChatSendQueueService(token, store, service, query, indexer, signals, scope, config, clock,
        MessagingIssueReporter { diagnostics += it }, progress = progress, onQueryAccounting = onQueryAccounting)
        .also { queues += it }
    fun poller(role: MeshCoreSessionProtocol = session) =
        MessagePollingService(token, role, store, signals, scope, clock, MessagingIssueReporter { diagnostics += it })
            .also { pollers += it }
    suspend fun pending(message: MessageDTO, attempt: Long? = 0, isResend: Boolean = false): PendingSendDTO {
        val channelIndex = message.channelIndex
        val dto = if (channelIndex == null) PendingSendDTO.fromEnvelope(
            DirectMessageEnvelope(message.id, CONTACT, isResend), RADIO, enqueuedAt = clock.wallClock.instant())
        else PendingSendDTO.fromEnvelope(ChannelMessageEnvelope(message.id, channelIndex, isResend,
            message.text, message.timestamp, "Test", message.channelOccupantID), RADIO, enqueuedAt = clock.wallClock.instant())
        val persisted = dto.copy(sequence = 1, attemptCount = attempt)
        if (channelIndex == null) {
            store.upsertPendingSend(persisted)
            return persisted
        }
        store.insertPendingSendAssigningSequence(persisted)
        return store.pending.getValue(EntityKey(RADIO, persisted.id))
    }
}

internal fun original(
    suite: String, name: String, signature: String = "()", row: String = "",
    assertion: suspend TestScope.() -> Unit,
): DynamicTest = DynamicTest.dynamicTest("$suite::$name$signature$row") { runMessagingCase(assertion) }

internal fun native(name: String, assertion: suspend TestScope.() -> Unit): DynamicTest =
    DynamicTest.dynamicTest("WP-208::$name") { runMessagingCase(assertion) }

private val fixtures = java.util.IdentityHashMap<TestScope, MutableList<Harness>>()

private fun runMessagingCase(assertion: suspend TestScope.() -> Unit) = runTest {
    val test = this
    var originalFailure: Throwable? = null
    try { assertion() }
    catch (failure: Throwable) { originalFailure = failure; throw failure }
    finally {
        val owned = synchronized(fixtures) { fixtures.remove(test).orEmpty().asReversed() }
        withContext(NonCancellable) {
            var cleanupFailure: Exception? = null
            for (fixture in owned) {
                try { fixture.close() }
                catch (failure: Exception) {
                    val prior = originalFailure
                    if (prior != null) prior.addSuppressed(failure)
                    else {
                        val first = cleanupFailure
                        if (first == null) cleanupFailure = failure else first.addSuppressed(failure)
                    }
                }
            }
            cleanupFailure?.let { throw it }
        }
    }
}

internal suspend fun statuses(service: MessageService, subscription: SessionEventSubscription<MessageStatusEvent>): List<MessageStatusEvent> {
    service.finishStatusEvents()
    return subscription.events.toList().map { it.event }
}
internal fun storageFailure() = PersistenceStoreException(PersistenceStoreError.SaveFailed("deterministic"), IllegalStateException("test fault"))

internal fun reviewContactPacket(text: String, type: UByte = 0u): Bytes {
    val packet = ByteWriter().appendUInt8(ResponseCode.CONTACT_MESSAGE_RECEIVED.rawValue).append(TARGET.prefix(6))
        .appendUInt8(0u).appendUInt8(type).appendUInt32LE(42u)
    if (type == 2.toUByte()) packet.append(Bytes.of(0x80, 0xFF, 1, 2))
    return packet.append(Bytes.utf8(text)).toBytes()
}
