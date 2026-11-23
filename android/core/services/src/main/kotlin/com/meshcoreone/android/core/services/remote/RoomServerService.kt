// PortedFrom: MC1Services/Sources/MC1Services/Services/RoomServerService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import com.meshcoreone.android.core.model.ackCodeUInt32
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.session.RemoteAccessSessionOps
import com.meshcoreone.android.core.protocol.session.SessionClock
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Service for room server interactions: joining rooms, posting messages, and receiving room messages.
 *
 * Swift actor isolation becomes confinement: [selfPublicKeyPrefix] and the in-flight retry set are read
 * and written only inside `synchronized(lock)`, and nothing suspends while holding the lock. The Swift
 * unstructured send `Task` becomes a child of a [SupervisorJob] under the injected [scope]; all waits use
 * the injected [clock]. Incoming-message handling and history sync live in `RoomServerServiceIncoming.kt`.
 *
 * Sessions are addressed by [EntityKey] (Swift session UUIDs) because the Android store partitions rows by
 * radio; room messages are keyed by their session's radio plus the Swift message UUID.
 */
class RoomServerService(
    internal val session: RemoteAccessSessionOps,
    internal val remoteNodeService: RemoteNodeService,
    internal val dataStore: RemoteAdminStore,
    internal val radioId: RadioId,
    scope: CoroutineScope,
    /** Retry behavior shared with the message service (Swift `MessageServiceConfig`). */
    private val config: RoomMessageRetryConfig = RoomMessageRetryConfig(),
    internal val clock: SessionClock = remoteNodeService.clock,
    internal val auditLogger: RemoteCommandAuditLog = remoteNodeService.auditLogger,
    internal val textRules: com.meshcoreone.android.core.contracts.domain.TextRulePersisting? = null,
) {
    internal val logger: Logger = Logger.getLogger("com.mc1.RoomServer")
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val serviceScope = CoroutineScope(scope.coroutineContext + job)
    private val lock = Any()

    /** Self public key prefix (4 bytes) for author comparison; set from SelfInfo when the device connects. */
    private var selfPrefix: Bytes? = null

    /** Message keys currently being retried, to prevent concurrent retry attempts. */
    private val inFlightRetries = HashSet<EntityKey>()

    /** Multicast broadcaster for status-update and connection-recovery events. */
    private val eventBroadcaster = RemoteEventBroadcaster<RoomServerEvent>()

    internal val selfPublicKeyPrefix: Bytes? get() = synchronized(lock) { selfPrefix }

    /** Set self public key prefix from SelfInfo; only the first 4 bytes are kept. */
    fun setSelfPublicKeyPrefix(prefix: Bytes) {
        synchronized(lock) { selfPrefix = prefix.prefix(4) }
    }

    // MARK: - Events

    /**
     * Returns a fresh stream of room-server events. Registration happens in this call, so events yielded
     * afterwards are never dropped. Consumers re-subscribe per connection because the owning container is
     * rebuilt on every connection.
     */
    fun events(): Flow<RoomServerEvent> = eventBroadcaster.subscribe()

    /** Ends every [events] subscriber's collection; called by the container's teardown. */
    fun finishEvents() = eventBroadcaster.finish()

    internal fun broadcast(event: RoomServerEvent) = eventBroadcaster.yield(event)

    // MARK: - Room Management

    /**
     * Join a room server by creating a session and authenticating, then sync message history when
     * possible (never failing the join).
     *
     * @param password authentication password; the stored password when null.
     * @param pathLength path length for the timeout (0 = direct).
     * @param onTimeoutKnown invoked with the timeout in seconds once the firmware responds.
     */
    suspend fun joinRoom(
        radioId: RadioId,
        contact: ContactDTO,
        password: String?,
        rememberPassword: Boolean = true,
        pathLength: UByte = 0u,
        onTimeoutKnown: (suspend (Long) -> Unit)? = null,
    ): RemoteNodeSessionDTO {
        val remoteSession = remoteNodeService.createSession(radioId, contact)
        val key = remoteSession.remoteSessionKey

        remoteNodeService.login(key, password, pathLength, onTimeoutKnown)

        // Store the password only after a successful login.
        if (password != null && rememberPassword) {
            remoteNodeService.storePassword(password, contact.publicKey)
        }

        syncHistoryIfPossible(key)

        return dataStore.fetchRemoteNodeSession(key) ?: throw RemoteNodeError.SessionNotFound()
    }

    /**
     * Re-authenticate to an existing room session (app restart or link reconnection) and sync any missed
     * messages.
     *
     * @param pathLength path-length hint (0 = use the shortest known path).
     */
    suspend fun reconnectRoom(session: EntityKey, pathLength: UByte = 0u): RemoteNodeSessionDTO {
        val remoteSession = dataStore.fetchRemoteNodeSession(session) ?: throw RemoteNodeError.SessionNotFound()
        if (!remoteSession.isRoom) throw RemoteNodeError.InvalidResponse()

        remoteNodeService.login(session, pathLength = pathLength)

        syncHistoryIfPossible(session)

        return dataStore.fetchRemoteNodeSession(session) ?: throw RemoteNodeError.SessionNotFound()
    }

    /** Leave a room by sending logout and removing the session (and its stored password). */
    suspend fun leaveRoom(session: EntityKey, publicKey: Bytes) {
        remoteNodeService.logout(session)
        remoteNodeService.removeSession(session, publicKey)
    }

    // MARK: - Message Posting

    /**
     * Post a message to a room server.
     *
     * Posts use `TextType.plain`; the room server converts to `signedPlain` when pushing to other clients
     * and never pushes a message back to its author, so the local record is created immediately with
     * pending status and returned while the send runs in the background.
     */
    suspend fun postMessage(session: EntityKey, text: String): RoomMessageDTO {
        val remoteSession = dataStore.fetchRemoteNodeSession(session) ?: throw RoomServerError.SessionNotFound()
        if (!remoteSession.canPost) throw RoomServerError.PermissionDenied()

        val timestamp = clock.wallClock.instant()
        val messageDTO = RoomMessageDTO(
            id = UUID.randomUUID(),
            sessionID = session.id,
            authorKeyPrefix = selfPublicKeyPrefix ?: Bytes(ByteArray(4)),
            authorName = "Me",
            text = text,
            timestamp = RemoteCLICommandRewriter.epochSeconds32(timestamp),
            createdAt = timestamp,
            isFromSelf = true,
            statusRawValue = MessageStatus.PENDING.rawValue,
            maxRetryAttempts = config.maxAttempts,
        )
        dataStore.saveRoomMessage(session.radioId, messageDTO)

        val messageKey = EntityKey(session.radioId, messageDTO.id)
        try {
            // Metadata only, no content.
            auditLogger.logRoomMessagePosted(remoteSession.publicKey, RemoteSwiftText.characterCount(text))
        } finally {
            // Send in the background so the UI can show the pending message immediately. The row is already
            // saved as pending, so the send must start even if this caller is cancelled during the audit, and
            // ATOMIC runs its body even if the scope is cancelled first: deliver then records `failed` instead
            // of leaving a pending row that retryMessage refuses. (Swift's send Task always runs.)
            serviceScope.launch(start = CoroutineStart.ATOMIC) {
                // sendMessageWithRetry requires the full 32-byte public key for path reset.
                deliver(messageKey, session, remoteSession.publicKey, text, timestamp, isRetry = false)
            }
        }
        return messageDTO
    }

    /**
     * Retry sending a failed room message.
     *
     * @param message the message key (its session's radio plus the Swift message UUID).
     */
    suspend fun retryMessage(message: EntityKey): RoomMessageDTO {
        if (!synchronized(lock) { inFlightRetries.add(message) }) {
            logger.warning { "Retry already in progress for message: ${message.id}" }
            throw RoomServerError.SendFailed("Retry already in progress")
        }
        try {
            val stored = dataStore.fetchRoomMessage(message) ?: throw RoomServerError.SendFailed("Message not found")
            if (stored.status != MessageStatus.FAILED) throw RoomServerError.SendFailed("Message is not in failed state")
            val sessionKey = EntityKey(message.radioId, stored.sessionID)
            val remoteSession = dataStore.fetchRemoteNodeSession(sessionKey) ?: throw RoomServerError.SessionNotFound()

            dataStore.updateRoomMessageRetryStatus(message, MessageStatus.PENDING, stored.retryAttempt + 1, config.maxAttempts)
            broadcast(RoomServerEvent.StatusUpdated(message, MessageStatus.PENDING))

            deliver(message, sessionKey, remoteSession.publicKey, stored.text, clock.wallClock.instant(), isRetry = true)

            return dataStore.fetchRoomMessage(message) ?: throw RoomServerError.SendFailed("Failed to fetch message after retry")
        } finally {
            synchronized(lock) { inFlightRetries.remove(message) }
        }
    }

    /**
     * Send with retry and record the outcome: an ACK marks `delivered`, exhausted retries without an ACK
     * mark `sent` (the room server likely received it), and an error marks `failed`. Storage failures are
     * logged, never rethrown, so the send outcome is still broadcast.
     *
     * Deviation: Swift's send task is never cancelled and its catch-all records `failed`. Here the only
     * cancellation source is the caller (retry) or the owning scope, so cancellation also records `failed`
     * (under [NonCancellable]) and is then rethrown instead of swallowed.
     */
    private suspend fun deliver(
        message: EntityKey, session: EntityKey, publicKey: Bytes, text: String, timestamp: Instant, isRetry: Boolean,
    ) {
        val sentInfo = try {
            this.session.sendMessageWithRetry(
                publicKey, text, timestamp, config.maxAttempts, config.floodAfter, config.maxFloodAttempts,
            )
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { recordFailure(message, isRetry) }
            throw cancellation
        } catch (error: Exception) {
            recordFailure(message, isRetry)
            logger.warning { if (isRetry) "Room message retry failed: $error" else "Room message send failed: $error" }
            return
        }

        // The radio already transmitted: record the outcome even if cancellation lands now, so the row never
        // stays pending (Swift's send task is never cancelled).
        withContext(NonCancellable) {
            if (sentInfo != null) {
                recordStatus(message, MessageStatus.DELIVERED, sentInfo.expectedAck.ackCodeUInt32, sentInfo.suggestedTimeoutMs) {
                    if (isRetry) "Failed to update message status after successful retry: $it"
                    else "Failed to update message status after successful send: $it"
                }
            } else {
                // All retries exhausted: the radio transmitted but no ACK arrived. Mark sent (not failed)
                // since the message likely reached the room server.
                recordStatus(message, MessageStatus.SENT, null, null) { "Failed to update message status to sent: $it" }
            }
            // Update the sort date only (no sync bookmark, which avoids clock-skew issues); Swift `try?`.
            try {
                dataStore.updateRoomActivity(session)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                logger.fine { "updateRoomActivity failed (ignored): $error" }
            }
        }
    }

    private suspend fun recordFailure(message: EntityKey, isRetry: Boolean) =
        recordStatus(message, MessageStatus.FAILED, null, null) {
            if (isRetry) "Failed to update message status after retry error: $it"
            else "Failed to update message status after send error: $it"
        }

    /** Persist [status] (logging storage failures), then broadcast it. */
    private suspend fun recordStatus(
        message: EntityKey, status: MessageStatus, ackCode: UInt?, roundTripTime: UInt?, failureLog: (Exception) -> String,
    ) {
        try {
            dataStore.updateRoomMessageStatus(message, status, ackCode, roundTripTime)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logger.severe { failureLog(error) }
        }
        broadcast(RoomServerEvent.StatusUpdated(message, status))
    }
}
