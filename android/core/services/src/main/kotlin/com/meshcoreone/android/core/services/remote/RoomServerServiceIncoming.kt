// PortedFrom: MC1Services/Sources/MC1Services/Services/RoomServerService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.TextRuleRoomAdmission
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException

// MARK: - Incoming Messages

/**
 * Handle an incoming room message (called by the message poller when a `signedPlain` message arrives
 * from a room). The room server's 6-byte key prefix is the sender; the original author's 4-byte prefix
 * comes from the payload. Any message, duplicate or not, proves the session is alive.
 *
 * @return the saved message, or null when no room session matches or the message is a duplicate.
 */
suspend fun RoomServerService.handleIncomingMessage(
    senderPublicKeyPrefix: Bytes,
    timestamp: UInt,
    authorPrefix: Bytes,
    text: String,
): RoomMessageDTO? {
    val result = handleIncomingMessageWithOrigin(senderPublicKeyPrefix, timestamp, authorPrefix, text) ?: return null
    val rules = textRules
    if (rules != null && (result.admission.saved || result.admission.recovered)) {
        rules.currentRoomTextSession(
            result.room, result.message.takeIf { result.admission.saved }, result.admission.messageIncarnation,
        ) ?: return null
        if (result.admission.recovered) publishIncomingRecovery(result.room)
    }
    return result.message.takeIf { result.admission.saved }
}

data class IncomingRoomText(
    val room: RemoteNodeSessionDTO,
    val message: RoomMessageDTO,
    val admission: TextRuleRoomAdmission,
)

suspend fun RoomServerService.handleIncomingMessageWithOrigin(
    senderPublicKeyPrefix: Bytes,
    timestamp: UInt,
    authorPrefix: Bytes,
    text: String,
    capturedRoom: RemoteNodeSessionDTO? = null,
    onRoomResolved: (RemoteNodeSessionDTO) -> Unit = {},
    onPrepared: (RoomMessageDTO) -> Unit = {},
    onCommitted: (IncomingRoomText) -> Unit = {},
): IncomingRoomText? {
    val remoteSession = capturedRoom ?: dataStore.fetchRemoteNodeSessions(radioId)
        .firstOrNull { it.publicKey.prefix(6) == senderPublicKeyPrefix && it.isRoom } ?: return null
    onRoomResolved(remoteSession)
    val key = remoteSession.remoteSessionKey

    // Receiving any message (even a duplicate) proves the session is active.
    if (textRules == null && !remoteSession.isConnected && markConnectedIgnoringErrors(key)) {
        broadcast(RoomServerEvent.ConnectionRecovered(key))
    }

    val dedupKey = RoomMessageDTO.generateDeduplicationKey(timestamp, authorPrefix, text)
    if (textRules == null && dataStore.isDuplicateRoomMessage(key, dedupKey)) return null

    // Metadata only, no content.
    auditLogger.logRoomMessageReceived(senderPublicKeyPrefix, authorPrefix, RemoteSwiftText.characterCount(text))

    // Defensive: room servers shouldn't push our own messages back.
    val isFromSelf = selfPublicKeyPrefix?.prefix(4) == authorPrefix.prefix(4)
    if (isFromSelf) logger.info { "Received self message from room server (unexpected)" }

    val authorName = dataStore.findContactNameByKeyPrefix(authorPrefix)
    val messageDTO = RoomMessageDTO(
        sessionID = remoteSession.id,
        authorKeyPrefix = authorPrefix,
        authorName = authorName,
        text = text,
        timestamp = timestamp,
        createdAt = clock.wallClock.instant(),
        isFromSelf = isFromSelf,
    )
    onPrepared(messageDTO)
    val rules = textRules
    if (rules != null) {
        val admission = rules.admitRoomText(messageDTO, remoteSession) {
            onCommitted(IncomingRoomText(remoteSession, messageDTO, it))
        }
        return IncomingRoomText(remoteSession, messageDTO, admission)
    }
    dataStore.saveRoomMessage(remoteSession.radioId, messageDTO)

    // Update the sync bookmark and sort date.
    dataStore.updateRoomActivity(key, timestamp)

    if (!isFromSelf) dataStore.incrementRoomUnreadCount(key)
    return IncomingRoomText(remoteSession, messageDTO, TextRuleRoomAdmission(saved = true, recovered = false))
}

fun RoomServerService.publishIncomingRecovery(room: RemoteNodeSessionDTO) {
    broadcast(RoomServerEvent.ConnectionRecovered(room.remoteSessionKey))
}

/** Swift `(try? markRoomSessionConnected(id)) ?? false`. */
private suspend fun RoomServerService.markConnectedIgnoringErrors(key: EntityKey): Boolean = try {
    dataStore.markRoomSessionConnected(key)
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Exception) {
    logger.fine { "markRoomSessionConnected failed (ignored): $error" }
    false
}

// MARK: - Message Retrieval

/** Fetch messages for a room session, optionally paginated. */
suspend fun RoomServerService.fetchMessages(
    session: EntityKey, limit: Long? = null, offset: Long? = null,
): SnapshotList<RoomMessageDTO> = dataStore.fetchRoomMessages(session, limit, offset)

/** Mark a room as read (reset its unread count); call when the user views the conversation. */
suspend fun RoomServerService.markAsRead(session: EntityKey) = dataStore.resetRoomUnreadCount(session)

// MARK: - Session Queries

/** Fetch all room sessions for a device. */
suspend fun RoomServerService.fetchRoomSessions(radioId: RadioId): List<RemoteNodeSessionDTO> =
    dataStore.fetchRemoteNodeSessions(radioId).filter { it.isRoom }

/** The connected room session on this service's radio whose key starts with the 6-byte [publicKeyPrefix]. */
suspend fun RoomServerService.getConnectedSession(publicKeyPrefix: Bytes): RemoteNodeSessionDTO? =
    dataStore.fetchRemoteNodeSessions(radioId)
        .firstOrNull { it.publicKey.prefix(6) == publicKeyPrefix && it.isRoom && it.isConnected }

// MARK: - History Sync

/** Path-discovery wait used by [discoverPathAndWait]. */
internal val ROOM_PATH_DISCOVERY_TIMEOUT: Duration = 10.seconds
private val ROOM_PATH_DISCOVERY_POLL_INTERVAL: Duration = 500.milliseconds

/**
 * Attempt to sync history: the advert path first when the contact has one, then path discovery. Never
 * throws for sync failures (messages still arrive via the normal flow).
 *
 * Deviation: Swift's catch-alls also swallow `CancellationError`; here cancellation always propagates.
 */
internal suspend fun RoomServerService.syncHistoryIfPossible(session: EntityKey) {
    try {
        val remoteSession = dataStore.fetchRemoteNodeSession(session) ?: return
        val contact = dataStore.findContactByPublicKey(remoteSession.publicKey) ?: return

        if (!contact.isFloodRouted) {
            logger.info { "Trying advert path for room ${remoteSession.name}" }
            try {
                remoteNodeService.requestHistorySync(session)
                logger.info { "History sync succeeded using advert path" }
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                logger.info { "Advert path failed for ${remoteSession.name}: $error, trying path discovery" }
            }
        } else {
            logger.info { "Room ${remoteSession.name} is flood-routed, attempting path discovery" }
        }

        if (!discoverPathAndWait(session)) {
            logger.info { "Could not establish direct route for ${remoteSession.name}, skipping history sync" }
            return
        }

        remoteNodeService.requestHistorySync(session)
        logger.info { "History sync succeeded after path discovery" }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        logger.warning { "Failed to sync history for session ${session.id}: $error" }
    }
}

/** Trigger path discovery and poll every 500 ms on [RoomServerService.clock] until the contact is direct. */
internal suspend fun RoomServerService.discoverPathAndWait(
    session: EntityKey, timeout: Duration = ROOM_PATH_DISCOVERY_TIMEOUT,
): Boolean {
    val remoteSession = dataStore.fetchRemoteNodeSession(session) ?: return false
    val contact = dataStore.findContactByPublicKey(remoteSession.publicKey) ?: return false
    if (!contact.isFloodRouted) return true

    try {
        this.session.sendPathDiscovery(remoteSession.publicKey)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        logger.warning { "Path discovery send failed: $error" }
        return false
    }

    val deadline = clock.now + timeout
    while (clock.now < deadline) {
        clock.sleepFor(ROOM_PATH_DISCOVERY_POLL_INTERVAL)
        val updated = dataStore.findContactByPublicKey(remoteSession.publicKey)
        if (updated != null && !updated.isFloodRouted) return true
    }
    return false
}
