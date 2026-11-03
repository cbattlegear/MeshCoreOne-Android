// PortedFrom: MC1/State/MessageEventDispatcher.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.state

import com.meshcoreone.android.core.contracts.domain.MessageStatusEvent
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.services.remote.RemoteNodeEvent
import com.meshcoreone.android.core.services.remote.RoomServerEvent
import com.meshcoreone.android.core.services.sync.SyncDataEvent
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** App-level reactions the dispatcher triggers (Swift `AppState` weak reference). */
interface MessageEventHost {
    fun noteDirectMessageForPrewarm(contact: com.meshcoreone.android.core.model.ContactDTO)
    fun noteChannelMessageForPrewarm(radioId: com.meshcoreone.android.core.model.RadioId, channelIndex: UByte)
    suspend fun handleReactionNotification(messageId: UUID)
    fun handleSessionStateChange()
}

/**
 * Owns the service event-stream subscriptions that feed [MessageEventStream] and the session-state counter.
 * Each wiring subscribes synchronously (via [MessageEventSources]) before its consuming job starts, so events
 * emitted during the connection-ready window are not dropped. [cancelAll] cancels every job and closes the
 * message-status subscription; a re-wire cancels the previous jobs first, so a reconnect never leaves a stale
 * collector running against a torn-down graph.
 */
class MessageEventDispatcher(
    private val host: MessageEventHost,
    private val stream: MessageEventStream,
    private val scope: CoroutineScope,
) {
    private val logger: Logger = Logger.getLogger("com.mc1.MessageEventDispatcher")
    private val lock = Any()
    private data class Wiring(
        val token: SessionToken,
        val group: Job,
        val statusSubscription: AutoCloseable,
    )

    private var wiring: Wiring? = null

    // Each wiring has its own child group so retiring one session cannot cancel a replacement's consumers.
    private val group = SupervisorJob(scope.coroutineContext[Job])

    /** Live consumer jobs, including any that a re-wire failed to cancel (test visibility for duplicate collectors). */
    val activeJobCount: Int get() = group.children.sumOf { owner -> owner.children.count { it.isActive } }

    fun wire(token: SessionToken, sources: MessageEventSources) {
        val next = Wiring(token, SupervisorJob(group), sources.messageStatus)
        val previous = synchronized(lock) { wiring.also { wiring = next } }
        previous?.let(::cancel)
        consume(next, "syncData", sources.dataEvents, ::handleDataEvent)
        consume(next, "heardRepeats", sources.heardRepeats) { event ->
            stream.send(MessageEvent.HeardRepeatRecorded(event.messageID, event.count))
        }
        consume(next, "regionUpdates", sources.regionUpdates) { ids ->
            if (ids.isNotEmpty()) stream.send(MessageEvent.MessagesRegionUpdated(ids.toList()))
        }
        consume(next, "remoteNode", sources.remoteNode) { event ->
            when (event) {
                is RemoteNodeEvent.SessionStateChanged -> host.handleSessionStateChange()
            }
        }
        consume(next, "roomServer", sources.roomServer, ::handleRoomServerEvent)
        consume(next, "messageStatus", sources.messageStatus.events) { event -> handleStatusEvent(event.event) }
    }

    /** Cancels every stream-consuming job; called before re-wiring and from the disconnect teardown. */
    fun cancelAll() {
        val previous = synchronized(lock) { wiring.also { wiring = null } }
        previous?.let(::cancel)
    }

    internal fun cancelForSession(token: SessionToken?) {
        val previous = synchronized(lock) {
            wiring?.takeIf { it.token == token }?.also { wiring = null }
        }
        previous?.let(::cancel)
    }

    private fun cancel(wiring: Wiring) {
        wiring.group.cancel()
        try {
            wiring.statusSubscription.close()
        } catch (failure: Exception) {
            logger.log(Level.WARNING, "message status subscription close failed: ${failure.message}")
        }
    }

    private fun <T> consume(wiring: Wiring, label: String, source: Flow<T>, handle: suspend (T) -> Unit) {
        val consumers = CoroutineScope(scope.coroutineContext + wiring.group)
        consumers.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                source.collect { value ->
                    currentCoroutineContext().ensureActive()
                    try {
                        handle(value)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        // Swift handlers cannot throw; one bad event must not end the stream.
                        logger.log(Level.SEVERE, "$label handler failed: ${failure.message}")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.log(Level.SEVERE, "$label stream ended with failure: ${failure.message}")
            }
        }
    }

    private suspend fun handleDataEvent(event: SyncDataEvent) {
        when (event) {
            is SyncDataEvent.DirectMessageReceived -> {
                stream.send(MessageEvent.DirectMessageReceived(event.message, event.contact))
                // Keep a closed conversation's warm coordinator current so a reopen renders the new tail first.
                host.noteDirectMessageForPrewarm(event.contact)
            }
            is SyncDataEvent.ChannelMessageReceived -> {
                stream.send(MessageEvent.ChannelMessageReceived(event.message, event.channelIndex))
                host.noteChannelMessageForPrewarm(event.message.radioId, event.channelIndex)
            }
            is SyncDataEvent.RoomMessageReceived ->
                stream.send(MessageEvent.RoomMessageReceived(event.message, event.message.sessionID))
            is SyncDataEvent.ReactionReceived -> {
                stream.send(MessageEvent.ReactionReceived(event.messageID, event.summary))
                host.handleReactionNotification(event.messageID)
            }
            SyncDataEvent.ContactsChanged, SyncDataEvent.ConversationsChanged -> Unit
        }
    }

    private fun handleRoomServerEvent(event: RoomServerEvent) {
        when (event) {
            is RoomServerEvent.StatusUpdated ->
                if (event.status == MessageStatus.FAILED) stream.send(MessageEvent.RoomMessageFailed(event.message.id))
                else stream.send(MessageEvent.RoomMessageStatusUpdated(event.message.id))
            is RoomServerEvent.ConnectionRecovered -> host.handleSessionStateChange()
        }
    }

    private fun handleStatusEvent(event: MessageStatusEvent) {
        when (event) {
            is MessageStatusEvent.StatusResolved ->
                stream.send(MessageEvent.MessageStatusResolved(event.messageID, event.status, event.roundTripTime))
            is MessageStatusEvent.Resent -> stream.send(MessageEvent.MessageResent(event.messageID))
            is MessageStatusEvent.Retrying ->
                stream.send(MessageEvent.MessageRetrying(event.messageID, event.attempt, event.maxAttempts))
            is MessageStatusEvent.RoutingChanged -> stream.send(MessageEvent.RoutingChanged(event.contactID, event.isFlood))
            is MessageStatusEvent.Failed -> stream.send(MessageEvent.MessageFailed(event.messageID))
        }
    }
}
