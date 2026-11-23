// PortedFrom: MC1Services/Sources/MC1Services/Services/RemoteNodeService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomPermissionLevel
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ContactMessage
import com.meshcoreone.android.core.protocol.event.EventFilter
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.session.SystemSessionClock
import java.util.UUID
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** Wire value 0x01, firmware `TXT_TYPE_CLI_DATA`. */
internal const val CLI_RESPONSE_TEXT_TYPE: UByte = 0x01u

/**
 * Shared service for remote node operations: login, keep-alive, status, telemetry, and CLI for both
 * room servers and repeaters.
 *
 * Swift actor isolation becomes confinement: every mutable field below is read and written only inside
 * `synchronized(lock)`, nothing suspends while holding the lock, and deferreds are completed and jobs
 * cancelled after the lock is released. Background work (event monitor, login and CLI waits, keep-alive
 * loops) runs in a child [SupervisorJob] of the injected [scope]; all waits use the injected [clock].
 *
 * The operation surface lives in same-package extension files mirroring the Swift extensions:
 * `RemoteNodeServiceLogin.kt`, `RemoteNodeServiceCLI.kt`, `RemoteNodeServicePathRecovery.kt`,
 * `RemoteNodeServiceReconnection.kt` and `RemoteNodeServiceTelemetry.kt`.
 */
class RemoteNodeService(
    internal val session: RemoteNodeSessionPort,
    internal val dataStore: RemoteNodeStore,
    internal val passwordStore: RemoteNodePasswordStore,
    scope: CoroutineScope,
    internal val clock: SessionClock = SystemSessionClock(),
    auditLogger: RemoteCommandAuditLog = RemoteCommandAuditLog.NONE,
    internal val isFloodFallbackEnabled: suspend () -> Boolean = { true },
    internal val isRecoveryOwnerCurrent: () -> Boolean = { true },
) : AutoCloseable {
    internal val logger: Logger = Logger.getLogger("com.mc1.RemoteNode")

    /** Swift's audit logger cannot throw; a throwing implementation is logged, never allowed to abort a flow. */
    internal val auditLogger: RemoteCommandAuditLog = NonThrowingAuditLog(auditLogger, logger)
    private val job = SupervisorJob(scope.coroutineContext[Job])
    internal val serviceScope = CoroutineScope(scope.coroutineContext + job)

    internal class PendingLogin(
        /** Session that called `login`, so a prefix match cannot update another radio's row. */
        val session: EntityKey,
        val result: CompletableDeferred<LoginResult> = CompletableDeferred(),
    )

    /**
     * The in-flight CLI request for a node. [acceptsAnyResponse] marks raw passthrough commands whose
     * reply shape can't be validated. [wirePrefix] is the correlation token prepended on the wire;
     * firmware reflects it back at the start of the reply.
     */
    internal class PendingCLIRequest(
        val id: UUID,
        val command: String,
        val wirePrefix: String,
        val acceptsAnyResponse: Boolean,
        val result: CompletableDeferred<String> = CompletableDeferred(),
    )

    /** A caller queued for a node's CLI slot while another command is in flight. */
    internal class CLISlotWaiter(val id: UUID, val granted: CompletableDeferred<Unit> = CompletableDeferred())

    internal val lock = Any()

    /** Pending logins keyed by 6-byte public key prefix, the MeshCore login-result format. */
    internal val pendingLogins = HashMap<Bytes, PendingLogin>()

    /** Send/retransmit/timeout tasks for pending logins, keyed by 6-byte prefix. */
    internal val pendingLoginTimeoutTasks = HashMap<Bytes, Job>()

    /**
     * The single in-flight CLI request per node, keyed by 6-byte prefix. Replies echoing the request's
     * wire prefix are attributed deterministically; unprefixed replies (firmware without the echo) fall
     * back to single-flight shape validation; foreign-prefixed replies are stale and dropped.
     */
    internal val pendingCLIRequests = HashMap<Bytes, PendingCLIRequest>()

    /** Cycling counter for CLI wire prefixes ("00|" through "FF|"). */
    internal var cliPrefixCounter = 0

    /** Nodes whose CLI slot is held by an in-flight command. */
    internal val cliSlotBusy = HashSet<Bytes>()

    /** FIFO waiters for a node's CLI slot, keyed by 6-byte prefix. */
    internal val cliSlotWaiters = HashMap<Bytes, ArrayDeque<CLISlotWaiter>>()

    internal val keepAliveTasks = HashMap<EntityKey, Job>()

    /** Keep-alive intervals per session; [DEFAULT_KEEP_ALIVE_INTERVAL] when unspecified. */
    internal val keepAliveIntervals = HashMap<EntityKey, Duration>()

    /** Reentrancy guard for link reconnection handling. */
    internal var isReauthenticating = false

    private var eventMonitorTask: Job? = null

    /** Handler for keep-alive ACK responses. As in Swift, nothing assigns or reads this today. */
    @Volatile
    var keepAliveResponseHandler: (suspend (EntityKey, Long) -> Unit)? = null

    // MARK: - Events

    private val eventBroadcaster = RemoteEventBroadcaster<RemoteNodeEvent>()

    /**
     * Returns a fresh stream of remote-node session events. Registration happens in this call, so
     * events yielded afterwards are never dropped. Consumers re-subscribe per connection because the
     * owning service container is rebuilt on every connection.
     */
    fun events(): Flow<RemoteNodeEvent> = eventBroadcaster.subscribe()

    /** Ends every [events] subscriber's collection; called by the container's teardown. */
    fun finishEvents() = eventBroadcaster.finish()

    internal fun broadcast(event: RemoteNodeEvent) = eventBroadcaster.yield(event)

    // MARK: - Event Monitoring

    /** Start monitoring MeshCore events for login results and CLI replies. */
    fun startEventMonitoring() {
        val filter = EventFilter { event ->
            when (event) {
                is MeshEvent.LoginSuccess, is MeshEvent.LoginFailed -> true
                is MeshEvent.ContactMessageReceived -> event.message.textType == CLI_RESPONSE_TEXT_TYPE
                is MeshEvent.StatusResponse, is MeshEvent.TelemetryResponse,
                is MeshEvent.NeighboursResponse, is MeshEvent.BinaryResponse -> true
                else -> false
            }
        }
        // Swift cancels the previous monitor before subscribing the new one, so no event is handled twice.
        synchronized(lock) { eventMonitorTask.also { eventMonitorTask = null } }?.cancel()
        // UNDISPATCHED registers the subscription before this function returns, like Swift's awaited
        // `session.events(filter:)`, even for a cold flow.
        val monitor = serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.events(filter).collect { event -> handleEventKeepingMonitorAlive(event) }
        }
        synchronized(lock) { eventMonitorTask.also { eventMonitorTask = monitor } }?.cancel()
    }

    /**
     * Swift's event handler cannot throw. A Kotlin collaborator that does must not end monitoring (later
     * logins would time out and CLI replies would be dropped) or escape to the uncaught-exception handler.
     */
    private suspend fun handleEventKeepingMonitorAlive(event: MeshEvent) {
        try {
            handleEvent(event)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logger.severe { "Remote node event handler failed for ${event::class.simpleName}: $error" }
        }
    }

    /** Stop monitoring events. */
    fun stopEventMonitoring() {
        synchronized(lock) { eventMonitorTask.also { eventMonitorTask = null } }?.cancel()
    }

    private suspend fun handleEvent(event: MeshEvent) {
        when (event) {
            is MeshEvent.LoginSuccess -> {
                val info = event.info
                logger.info { "loginSuccess received for prefix ${info.publicKeyPrefix.hexString}" }
                val result = LoginResult(true, info.isAdmin, info.permissions, info.publicKeyPrefix, info.serverTime)
                handleLoginResult(result, info.publicKeyPrefix)
            }
            is MeshEvent.LoginFailed -> event.publicKeyPrefix?.let { prefix ->
                handleLoginResult(LoginResult(false, false, null, prefix), prefix)
            }
            is MeshEvent.ContactMessageReceived ->
                if (event.message.textType == CLI_RESPONSE_TEXT_TYPE) handleCLIResponse(event.message)
            else -> Unit
        }
    }

    /**
     * Handle a CLI reply. The echoed wire prefix attributes a reply deterministically; an unprefixed
     * reply falls back to single-flight shape validation for older firmware; a reply echoing a
     * different prefix belongs to an earlier command and is dropped.
     */
    private fun handleCLIResponse(message: ContactMessage) {
        val prefix = message.senderPublicKeyPrefix.prefix(6)
        val delivery = synchronized(lock) {
            val pending = pendingCLIRequests[prefix]
            if (pending == null) {
                logger.fine { "Unmatched CLI response (no pending request): ${message.text.take(50)}" }
                return
            }
            val echoed = CLIResponse.splitEchoedPrefix(message.text)
            val responseText = if (echoed != null) {
                if (echoed.prefix != pending.wirePrefix) {
                    logger.warning { "Dropping stale CLI response with prefix ${echoed.prefix} while awaiting ${pending.wirePrefix}" }
                    return
                }
                echoed.body
            } else {
                // Firmware without prefix echo: fall back to shape validation.
                if (!pending.acceptsAnyResponse && !CLIResponse.isPlausibleResponse(message.text, pending.command)) {
                    logger.warning { "Dropping CLI response that doesn't match pending '${pending.command}': ${message.text.take(50)}" }
                    return
                }
                message.text
            }
            pendingCLIRequests.remove(prefix)
            pending to responseText
        }
        delivery.first.result.complete(delivery.second)
    }

    /** Returns the next cycling CLI wire prefix ("00|" through "FF|"). */
    internal fun makeCLIWirePrefix(): String = synchronized(lock) {
        val value = cliPrefixCounter
        cliPrefixCounter = (cliPrefixCounter + 1) and 0xFF
        "%02X%c".format(value, CLIResponse.ECHO_PREFIX_SEPARATOR)
    }

    /**
     * Fails the pending login for [prefix] with `RemoteNodeError.Cancelled` and cancels its task.
     * With [owner], only that exact registration is cancelled (a newer login for the prefix survives).
     */
    internal fun cancelPendingLogin(prefix: Bytes, owner: PendingLogin? = null) {
        val (pending, task) = synchronized(lock) {
            val current = pendingLogins[prefix]
            if (current == null || (owner != null && current !== owner)) return
            pendingLogins.remove(prefix)
            current to pendingLoginTimeoutTasks.remove(prefix)
        }
        task?.cancel()
        pending.result.completeExceptionally(RemoteNodeError.Cancelled())
    }

    /** Removes the pending CLI request when it is still [requestID]; Swift `cancelPendingCLIRequest`. */
    internal fun cancelPendingCLIRequest(prefix: Bytes, requestID: UUID) {
        takePendingCLIRequest(prefix, requestID)
            ?.result?.completeExceptionally(RemoteNodeError.Cancelled())
    }

    /** Remove and return the pending request if it is still the given one. */
    internal fun takePendingCLIRequest(prefix: Bytes, requestID: UUID): PendingCLIRequest? = synchronized(lock) {
        val pending = pendingCLIRequests[prefix]
        if (pending == null || pending.id != requestID) null else pendingCLIRequests.remove(prefix)
    }

    // MARK: - Session Management

    /** Create a session DTO for a contact, preserving data from an existing session when present. */
    private fun makeSessionDTO(
        radioId: RadioId, contact: ContactDTO, role: RemoteNodeRole, existing: RemoteNodeSessionDTO?,
    ): RemoteNodeSessionDTO = RemoteNodeSessionDTO(
        id = existing?.id ?: UUID.randomUUID(),
        radioId = radioId,
        publicKey = contact.publicKey,
        name = contact.displayName,
        role = role,
        latitude = contact.latitude,
        longitude = contact.longitude,
        isConnected = false,
        permissionLevel = existing?.permissionLevel ?: RoomPermissionLevel.GUEST,
        lastConnectedDate = existing?.lastConnectedDate,
        lastBatteryMillivolts = existing?.lastBatteryMillivolts,
        lastUptimeSeconds = existing?.lastUptimeSeconds,
        lastNoiseFloor = existing?.lastNoiseFloor,
        unreadCount = existing?.unreadCount ?: 0,
        notificationLevel = existing?.notificationLevel ?: NotificationLevel.ALL,
        // Swift's makeSessionDTO does not carry isFavorite, so the DTO default (false) applies.
        lastRxAirtimeSeconds = existing?.lastRxAirtimeSeconds,
        neighborCount = existing?.neighborCount ?: 0,
        lastSyncTimestamp = existing?.lastSyncTimestamp ?: 0u,
        lastMessageDate = existing?.lastMessageDate,
    )

    /** Create (or refresh) the session for a remote node. */
    suspend fun createSession(radioId: RadioId, contact: ContactDTO): RemoteNodeSessionDTO {
        val role = RemoteNodeRole.fromContactType(contact.type) ?: throw RemoteNodeError.InvalidResponse()
        if (contact.publicKey.size != ProtocolLimits.PUBLIC_KEY_SIZE) {
            throw RemoteNodeError.invalidPublicKeyLength(contact.publicKey.size)
        }
        val pubKeyHex = contact.publicKey.prefix(6).hexString
        val existing = dataStore.fetchRemoteNodeSessions(radioId).firstOrNull { it.publicKey == contact.publicKey }
        if (existing != null) {
            logger.info { "createSession: reusing existing session ${existing.id} for $pubKeyHex, isConnected=${existing.isConnected}" }
        } else {
            logger.info { "createSession: creating new session for $pubKeyHex" }
        }
        val dto = makeSessionDTO(radioId, contact, role, existing)
        dataStore.saveRemoteNodeSessionDTO(dto)
        dataStore.cleanupDuplicateRemoteNodeSessions(contact.publicKey, dto.remoteSessionKey)
        val saved = dataStore.fetchRemoteNodeSession(dto.remoteSessionKey) ?: run {
            logger.severe { "createSession: failed to fetch saved session for $pubKeyHex" }
            throw RemoteNodeError.SessionNotFound()
        }
        logger.info { "createSession: saved session ${saved.id} for $pubKeyHex" }
        return saved
    }

    /** Remove a session and its associated data. */
    suspend fun removeSession(session: EntityKey, publicKey: Bytes) {
        stopKeepAlive(session)
        passwordStore.deletePassword(publicKey)
        dataStore.deleteRemoteNodeSession(session)
    }

    /** Check if a password is stored for a contact's public key. */
    suspend fun hasPassword(contact: ContactDTO): Boolean = passwordStore.hasPassword(contact.publicKey)

    /** Retrieve the stored password for a contact; lookup failures read as "none", like Swift `try?`. */
    suspend fun retrievePassword(contact: ContactDTO): String? = try {
        passwordStore.retrievePassword(contact.publicKey)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        logger.fine { "retrievePassword failed: $error" }
        null
    }

    /** Store a password for a remote node; call after a successful login so only correct passwords persist. */
    suspend fun storePassword(password: String, publicKey: Bytes) = passwordStore.storePassword(password, publicKey)

    /** Delete the stored password for a contact's public key. */
    suspend fun deletePassword(contact: ContactDTO) = passwordStore.deletePassword(contact.publicKey)

    // MARK: - Disconnect

    /** Mark a session disconnected without sending logout. */
    suspend fun disconnect(session: EntityKey) {
        stopKeepAlive(session)
        persistDisconnected(session)
        broadcast(RemoteNodeEvent.SessionStateChanged(session, isConnected = false))
    }

    /** `markSessionDisconnected` that logs (never rethrows) storage failures, as every Swift call site does. */
    internal suspend fun persistDisconnected(session: EntityKey) {
        try {
            dataStore.markSessionDisconnected(session)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logger.severe { "Failed to persist disconnected state for session ${session.id}: $error" }
        }
    }

    // MARK: - Cleanup

    /** Stop every keep-alive and fail any parked login (app termination / container teardown). */
    fun stopAllKeepAlives() {
        val tasks = synchronized(lock) { keepAliveTasks.values.toList().also { keepAliveTasks.clear() } }
        tasks.forEach { it.cancel() }
        // Fail parked logins before dropping their tasks; otherwise the caller would hang.
        val prefixes = synchronized(lock) { pendingLogins.keys.toList() }
        prefixes.forEach { cancelPendingLogin(it) }
    }

    /** Stop keep-alive for a session. */
    internal fun stopKeepAlive(session: EntityKey) {
        synchronized(lock) { keepAliveTasks.remove(session) }?.cancel()
    }

    /** Number of parked logins; exposed for teardown tests. */
    internal val pendingLoginCount: Int get() = synchronized(lock) { pendingLogins.size }

    /**
     * Swift `deinit` plus container teardown: stops monitoring and keep-alives, fails parked logins and
     * CLI waits with `RemoteNodeError.Cancelled`, finishes [events], and cancels the service's jobs.
     */
    override fun close() {
        stopEventMonitoring()
        stopAllKeepAlives()
        finishEvents()
        job.cancel()
        val waiters = synchronized(lock) {
            cliSlotWaiters.values.flatten().also { cliSlotWaiters.clear(); cliSlotBusy.clear() }
        }
        waiters.forEach { it.granted.completeExceptionally(RemoteNodeError.Cancelled()) }
    }

    companion object {
        val DEFAULT_KEEP_ALIVE_INTERVAL: Duration = 90.seconds

        /** True when the error is a mesh wait timeout (session or outer wrapper). */
        fun isMeshTimeout(error: Throwable): Boolean = remoteMeshTimeout(error)
    }
}

/**
 * Swift `EventBroadcaster<T>` (WP-207 ports the shared one into core:runtime, which this module may not
 * depend on): synchronous registration, unbounded multicast buffers. Shared by RemoteNodeService and
 * RoomServerService.
 */
internal class RemoteEventBroadcaster<T> {
    private val lock = Any()
    private val subscribers = LinkedHashMap<Long, Channel<T>>()
    private var nextId = 0L
    private var finished = false

    fun subscribe(): Flow<T> {
        val channel = Channel<T>(Channel.UNLIMITED)
        val id = synchronized(lock) {
            if (finished) {
                channel.close()
                null
            } else {
                nextId++.also { subscribers[it] = channel }
            }
        }
        return flow {
            try {
                for (event in channel) emit(event)
            } finally {
                synchronized(lock) { id?.let(subscribers::remove) }
                channel.cancel()
            }
        }
    }

    fun yield(event: T) {
        synchronized(lock) { subscribers.values.forEach { it.trySend(event) } }
    }

    fun finish() {
        synchronized(lock) {
            finished = true
            subscribers.values.forEach { it.close() }
            subscribers.clear()
        }
    }
}
