// AndroidOnly: WP-210 Hand-written session, store and password fakes for RemoteNodeService and admin-service tests, mirroring the Swift MockMeshCoreSession stubs.
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.ContactSaveResult
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomPermissionLevel
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.event.EventFilter
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.MessageResult
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import com.meshcoreone.android.core.protocol.event.NeighboursResponse
import com.meshcoreone.android.core.protocol.event.OwnerInfoResponse
import com.meshcoreone.android.core.protocol.event.StatusResponse
import com.meshcoreone.android.core.protocol.event.TelemetryResponse
import com.meshcoreone.android.core.protocol.session.ContactFetchResult
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.MeshContact
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/** Error thrown by fake methods a test has not configured (Swift `NotStubbed.method`). */
class RemoteCoreNotStubbed(method: String) : Exception("Not stubbed: $method")

/**
 * Hand-written port of the Swift `MockMeshCoreSession` slice RemoteNodeService uses. Event
 * subscriptions register synchronously; `getMessage` waits its timeout on the virtual clock, modelling
 * a radio poll so CLI polling loops cannot spin.
 */
class RemoteCoreFakeSession(private val clock: RemoteCoreTestClock) : RemoteNodeSessionPort {
    data class SendLoginInvocation(val destination: Bytes, val password: String)
    data class SendCommandInvocation(val destination: Bytes, val command: String, val timestamp: Instant)
    data class SendKeepAliveInvocation(val publicKey: Bytes, val syncSince: UInt)
    data class RequestNeighboursInvocation(
        val publicKey: Bytes, val count: UByte, val offset: UShort, val orderBy: UByte, val pubkeyPrefixLength: UByte,
    )
    data class SendMessageWithRetryInvocation(
        val destination: Bytes, val text: String, val timestamp: Instant, val maxAttempts: Long, val floodAfter: Long,
        val maxFloodAttempts: Long, val timeout: Double?,
    )

    private val lock = Any()
    private val subscriptions = LinkedHashMap<Long, Pair<EventFilter?, Channel<MeshEvent>>>()
    private var nextSubscription = 0L

    private val loginCalls = mutableListOf<SendLoginInvocation>()
    private val commandCalls = mutableListOf<SendCommandInvocation>()
    private val radioCommandCalls = mutableListOf<SendCommandInvocation>()
    private val keepAliveCalls = mutableListOf<SendKeepAliveInvocation>()
    private val logoutCalls = mutableListOf<Bytes>()
    private val addedContacts = mutableListOf<MeshContact>()
    private val resetPaths = mutableListOf<Bytes>()
    private val messageTimeouts = mutableListOf<Double?>()
    private val statusCalls = mutableListOf<Pair<Bytes, ContactType>>()
    private val neighbourCalls = mutableListOf<RequestNeighboursInvocation>()
    private val messageRetryCalls = mutableListOf<SendMessageWithRetryInvocation>()
    private val pathDiscoveryCalls = mutableListOf<Bytes>()

    private val loginResults = ArrayDeque<Result<MessageSentInfo>>()
    private val statusResults = ArrayDeque<Result<StatusResponse>>()
    private val telemetryResults = ArrayDeque<Result<TelemetryResponse>>()
    private val ownerInfoResults = ArrayDeque<Result<OwnerInfoResponse>>()

    /** When set, `sendLogin` records the call then waits on this gate (a silent radio). */
    @Volatile var sendLoginGate: CompletableDeferred<MessageSentInfo>? = null
    @Volatile var sendCommandResult: Result<MessageSentInfo> = Result.success(sentInfo(100u))
    @Volatile var sendCommandHandler: (suspend () -> MessageSentInfo)? = null
    @Volatile var sendKeepAliveResult: Result<MessageSentInfo> = Result.failure(RemoteCoreNotStubbed("sendKeepAlive"))
    @Volatile var sendLogoutError: Exception? = null
    @Volatile var addContactError: Exception? = null
    @Volatile var resetPathError: Exception? = null
    @Volatile var requestStatusResult: Result<StatusResponse> = Result.failure(RemoteCoreNotStubbed("requestStatus"))
    @Volatile var requestStatusGate: CompletableDeferred<StatusResponse>? = null
    @Volatile var requestTelemetryResult: Result<TelemetryResponse> = Result.failure(RemoteCoreNotStubbed("requestTelemetry"))
    @Volatile var requestOwnerInfoResult: Result<OwnerInfoResponse> = Result.failure(RemoteCoreNotStubbed("requestOwnerInfo"))
    /** Answers `requestNeighbours`; unset means not stubbed. */
    @Volatile var requestNeighboursHandler: (suspend (RequestNeighboursInvocation) -> NeighboursResponse)? = null
    /** Answers `sendMessageWithRetry`; unset means not stubbed. */
    @Volatile var sendMessageWithRetryHandler: (suspend (SendMessageWithRetryInvocation) -> MessageSentInfo?)? = null
    /** Answers `sendPathDiscovery`; unset means not stubbed. */
    @Volatile var sendPathDiscoveryHandler: (suspend (Bytes) -> MessageSentInfo)? = null
    @Volatile var beforeRadioCLICommand: (suspend () -> Unit)? = null

    val sendLoginInvocations: List<SendLoginInvocation> get() = synchronized(lock) { loginCalls.toList() }
    val sendCommandInvocations: List<SendCommandInvocation> get() = synchronized(lock) { commandCalls.toList() }
    val sendRadioCLICommandInvocations: List<SendCommandInvocation> get() = synchronized(lock) { radioCommandCalls.toList() }
    val sendKeepAliveInvocations: List<SendKeepAliveInvocation> get() = synchronized(lock) { keepAliveCalls.toList() }
    val sendLogoutInvocations: List<Bytes> get() = synchronized(lock) { logoutCalls.toList() }
    val addContactInvocations: List<MeshContact> get() = synchronized(lock) { addedContacts.toList() }
    val resetPathPublicKeys: List<Bytes> get() = synchronized(lock) { resetPaths.toList() }
    val getMessageTimeouts: List<Double?> get() = synchronized(lock) { messageTimeouts.toList() }
    val requestStatusInvocations: List<Pair<Bytes, ContactType>> get() = synchronized(lock) { statusCalls.toList() }
    val eventSubscriptionCount: Int get() = synchronized(lock) { subscriptions.size }
    val requestNeighboursInvocations: List<RequestNeighboursInvocation> get() = synchronized(lock) { neighbourCalls.toList() }
    val sendMessageWithRetryInvocations: List<SendMessageWithRetryInvocation> get() = synchronized(lock) { messageRetryCalls.toList() }
    val sendPathDiscoveryInvocations: List<Bytes> get() = synchronized(lock) { pathDiscoveryCalls.toList() }

    fun setSendLoginResults(results: List<Result<MessageSentInfo>>) = synchronized(lock) {
        loginResults.clear(); loginResults.addAll(results)
    }
    fun setRequestStatusResults(results: List<Result<StatusResponse>>) = synchronized(lock) {
        statusResults.clear(); statusResults.addAll(results)
    }
    fun setRequestTelemetryResults(results: List<Result<TelemetryResponse>>) = synchronized(lock) {
        telemetryResults.clear(); telemetryResults.addAll(results)
    }
    fun setRequestOwnerInfoResults(results: List<Result<OwnerInfoResponse>>) = synchronized(lock) {
        ownerInfoResults.clear(); ownerInfoResults.addAll(results)
    }

    /** Yields an event to every subscriber whose filter matches. */
    fun yieldEvent(event: MeshEvent) = synchronized(lock) {
        subscriptions.values.forEach { (filter, channel) ->
            if (filter?.matches(event) != false) channel.trySend(event)
        }
    }

    // MARK: SessionEventStreaming

    override val connectionState: Flow<ConnectionState> = emptyFlow()
    override fun events(): Flow<MeshEvent> = subscribe(null)
    override fun events(filter: EventFilter): Flow<MeshEvent> = subscribe(filter)
    override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): MeshEvent? = null

    private fun subscribe(filter: EventFilter?): Flow<MeshEvent> {
        val channel = Channel<MeshEvent>(Channel.UNLIMITED)
        val id = synchronized(lock) { nextSubscription++.also { subscriptions[it] = filter to channel } }
        return flow {
            try {
                for (event in channel) emit(event)
            } finally {
                synchronized(lock) { subscriptions.remove(id) }
            }
        }
    }

    // MARK: RemoteAccessSessionOps

    override suspend fun sendLogin(destination: Bytes, password: String): MessageSentInfo {
        val next = synchronized(lock) {
            loginCalls += SendLoginInvocation(destination, password)
            loginResults.removeFirstOrNull()
        }
        sendLoginGate?.let { return it.await() }
        return (next ?: Result.failure(RemoteCoreNotStubbed("sendLogin"))).getOrThrow()
    }

    override suspend fun sendLogout(destination: Bytes) {
        synchronized(lock) { logoutCalls += destination }
        sendLogoutError?.let { throw it }
    }

    override suspend fun sendCommand(destination: Bytes, command: String, timestamp: Instant): MessageSentInfo {
        synchronized(lock) { commandCalls += SendCommandInvocation(destination, command, timestamp) }
        sendCommandHandler?.let { return it() }
        return sendCommandResult.getOrThrow()
    }

    override suspend fun sendRadioCLICommand(destination: Bytes, command: String, timestamp: Instant): MessageSentInfo {
        beforeRadioCLICommand?.invoke()
        synchronized(lock) { radioCommandCalls += SendCommandInvocation(destination, command, timestamp) }
        return sendCommandResult.getOrThrow()
    }

    override suspend fun sendKeepAlive(publicKey: Bytes, syncSince: UInt): MessageSentInfo {
        synchronized(lock) { keepAliveCalls += SendKeepAliveInvocation(publicKey, syncSince) }
        return sendKeepAliveResult.getOrThrow()
    }

    override suspend fun requestOwnerInfo(publicKey: Bytes): OwnerInfoResponse =
        (synchronized(lock) { ownerInfoResults.removeFirstOrNull() } ?: requestOwnerInfoResult).getOrThrow()

    override suspend fun requestStatus(publicKey: Bytes, type: ContactType): StatusResponse {
        val next = synchronized(lock) {
            statusCalls += publicKey to type
            statusResults.removeFirstOrNull()
        }
        requestStatusGate?.let { return it.await() }
        return (next ?: requestStatusResult).getOrThrow()
    }

    override suspend fun requestTelemetry(publicKey: Bytes): TelemetryResponse =
        (synchronized(lock) { telemetryResults.removeFirstOrNull() } ?: requestTelemetryResult).getOrThrow()

    override suspend fun requestNeighbours(
        publicKey: Bytes, count: UByte, offset: UShort, orderBy: UByte, pubkeyPrefixLength: UByte,
    ): NeighboursResponse {
        val invocation = RequestNeighboursInvocation(publicKey, count, offset, orderBy, pubkeyPrefixLength)
        synchronized(lock) { neighbourCalls += invocation }
        val handler = requestNeighboursHandler ?: throw RemoteCoreNotStubbed("requestNeighbours")
        return handler(invocation)
    }

    override suspend fun getMessage(timeout: Double?): MessageResult {
        synchronized(lock) { messageTimeouts += timeout }
        clock.sleepFor((timeout ?: 5.0).seconds)
        return MessageResult.NoMoreMessages
    }

    override suspend fun sendMessageWithRetry(
        destination: Bytes, text: String, timestamp: Instant, maxAttempts: Long, floodAfter: Long,
        maxFloodAttempts: Long, timeout: Double?,
    ): MessageSentInfo? {
        val invocation = SendMessageWithRetryInvocation(destination, text, timestamp, maxAttempts, floodAfter, maxFloodAttempts, timeout)
        synchronized(lock) { messageRetryCalls += invocation }
        val handler = sendMessageWithRetryHandler ?: throw RemoteCoreNotStubbed("sendMessageWithRetry")
        return handler(invocation)
    }

    override suspend fun sendPathDiscovery(destination: Bytes): MessageSentInfo {
        synchronized(lock) { pathDiscoveryCalls += destination }
        val handler = sendPathDiscoveryHandler ?: throw RemoteCoreNotStubbed("sendPathDiscovery")
        return handler(destination)
    }

    // MARK: ContactSessionOps

    override suspend fun getContacts(since: Instant?): List<MeshContact> = throw RemoteCoreNotStubbed("getContacts")
    override suspend fun getContactsReportingTotal(since: Instant?): ContactFetchResult =
        throw RemoteCoreNotStubbed("getContactsReportingTotal")
    override suspend fun getContact(publicKey: Bytes): MeshContact? = throw RemoteCoreNotStubbed("getContact")

    override suspend fun addContact(contact: MeshContact) {
        synchronized(lock) { addedContacts += contact }
        addContactError?.let { throw it }
    }

    override suspend fun removeContact(publicKey: Bytes) = throw RemoteCoreNotStubbed("removeContact")

    override suspend fun resetPath(publicKey: Bytes) {
        synchronized(lock) { resetPaths += publicKey }
        resetPathError?.let { throw it }
    }

    override suspend fun shareContact(publicKey: Bytes) = throw RemoteCoreNotStubbed("shareContact")
    override suspend fun exportContact(publicKey: Bytes?): String = throw RemoteCoreNotStubbed("exportContact")
    override suspend fun importContact(cardData: Bytes) = throw RemoteCoreNotStubbed("importContact")
    override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags) =
        throw RemoteCoreNotStubbed("changeContactFlags")

    companion object {
        fun sentInfo(suggestedTimeoutMs: UInt = 5000u): MessageSentInfo =
            MessageSentInfo(0u, Bytes.of(0x01, 0x02, 0x03, 0x04), suggestedTimeoutMs)
    }
}

/**
 * In-memory [RemoteNodeStore] standing in for Swift's `PersistenceStore.createTestDataStore`, with the
 * same row semantics for the operations RemoteNodeService calls.
 */
class RemoteCoreFakeStore : RemoteNodeStore {
    private val lock = Any()
    private val sessions = LinkedHashMap<EntityKey, RemoteNodeSessionDTO>()
    private val contacts = LinkedHashMap<Pair<RadioId, Bytes>, ContactDTO>()
    @Volatile var markDisconnectedError: Exception? = null
    @Volatile var markDisconnectedHandler: (suspend () -> Unit)? = null
    @Volatile var fetchConnectedError: Exception? = null
    @Volatile var fetchSessionHandler: (suspend () -> Unit)? = null

    fun session(key: EntityKey): RemoteNodeSessionDTO? = synchronized(lock) { sessions[key] }
    fun contact(radioId: RadioId, publicKey: Bytes): ContactDTO? = synchronized(lock) { contacts[radioId to publicKey] }
    val sessionCount: Int get() = synchronized(lock) { sessions.size }
    val allContacts: List<ContactDTO> get() = synchronized(lock) { contacts.values.toList() }

    fun saveContact(dto: ContactDTO) = synchronized(lock) { contacts[dto.radioId to dto.publicKey] = dto }

    override suspend fun fetchRemoteNodeSession(key: EntityKey): RemoteNodeSessionDTO? {
        fetchSessionHandler?.invoke()
        return session(key)
    }

    override suspend fun fetchRemoteNodeSessions(radioId: RadioId): SnapshotList<RemoteNodeSessionDTO> =
        synchronized(lock) { SnapshotList(sessions.values.filter { it.radioId == radioId }) }

    override suspend fun fetchConnectedRemoteNodeSessions(radioId: RadioId): SnapshotList<RemoteNodeSessionDTO> {
        fetchConnectedError?.let { throw it }
        return synchronized(lock) { SnapshotList(sessions.values.filter { it.radioId == radioId && it.isConnected }) }
    }

    override suspend fun saveRemoteNodeSessionDTO(dto: RemoteNodeSessionDTO) =
        synchronized(lock) { sessions[EntityKey(dto.radioId, dto.id)] = dto }

    override suspend fun updateRemoteNodeSessionConnection(
        key: EntityKey, isConnected: Boolean, permissionLevel: RoomPermissionLevel,
    ) = synchronized(lock) {
        sessions[key]?.let { existing ->
            sessions[key] = existing.copy(
                isConnected = isConnected, permissionLevel = permissionLevel,
                lastConnectedDate = if (isConnected) Instant.now() else existing.lastConnectedDate,
            )
        }
        Unit
    }

    override suspend fun cleanupDuplicateRemoteNodeSessions(publicKey: Bytes, keep: EntityKey) = synchronized(lock) {
        if (sessions.containsKey(keep)) {
            sessions.keys.filter { it != keep && it.radioId == keep.radioId && sessions[it]?.publicKey == publicKey }
                .forEach(sessions::remove)
        }
    }

    override suspend fun deleteRemoteNodeSession(key: EntityKey) = synchronized(lock) { sessions.remove(key); Unit }

    override suspend fun markSessionDisconnected(key: EntityKey) {
        markDisconnectedHandler?.invoke()
        markDisconnectedError?.let { throw it }
        synchronized(lock) { sessions[key]?.let { sessions[key] = it.copy(isConnected = false) } }
    }

    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? = contact(radioId, publicKey)

    override suspend fun saveContact(radioId: RadioId, frame: ContactFrame): ContactSaveResult = synchronized(lock) {
        val existing = contacts[radioId to frame.publicKey]
        val saved = existing?.updating(frame) ?: ContactDTO.fromFrame(radioId, frame)
        contacts[radioId to frame.publicKey] = saved
        ContactSaveResult(saved.id, existing == null)
    }
}

/** In-memory node-password store (Swift `KeychainService` stand-in). */
class RemoteCoreFakePasswordStore : RemoteNodePasswordStore {
    private val lock = Any()
    private val passwords = HashMap<Bytes, String>()
    @Volatile var deleteError: Exception? = null
    @Volatile var storeHandler: (suspend () -> Unit)? = null

    fun stored(publicKey: Bytes): String? = synchronized(lock) { passwords[publicKey] }

    override suspend fun storePassword(password: String, publicKey: Bytes) {
        storeHandler?.invoke()
        synchronized(lock) { passwords[publicKey] = password }
    }
    override suspend fun retrievePassword(publicKey: Bytes): String? = stored(publicKey)
    override suspend fun hasPassword(publicKey: Bytes): Boolean = stored(publicKey) != null
    override suspend fun deletePassword(publicKey: Bytes) {
        deleteError?.let { throw it }
        synchronized(lock) { passwords.remove(publicKey) }
    }
}
