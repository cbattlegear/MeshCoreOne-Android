// PortedFrom: MC1/State/MessageEventDispatcher.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.state

import com.meshcoreone.android.core.contracts.domain.MessageStatusEvent
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.services.remote.RemoteNodeEvent
import com.meshcoreone.android.core.services.remote.RoomServerEvent
import com.meshcoreone.android.core.services.sync.SyncDataEvent
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
 * emitted during the connection-ready window are not dropped. Prepared collectors buffer their first event until
 * their session is admitted. Rejected preparations and retired wirings cancel their jobs and close status
 * subscriptions without affecting a replacement.
 */
class MessageEventDispatcher(
    private val host: MessageEventHost,
    private val stream: MessageEventStream,
    private val scope: CoroutineScope,
) {
    private val logger: Logger = Logger.getLogger("com.mc1.MessageEventDispatcher")
    private val lock = Any()
    private class Wiring(
        val token: SessionToken,
        val group: Job,
        val statusSubscription: AutoCloseable,
        val admitted: CompletableDeferred<Unit> = CompletableDeferred(),
    ) {
        var preparing = true
        var retired = false
        val closed = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
    }

    private var wiring: Wiring? = null
    private val prepared = mutableSetOf<Wiring>()
    private var cancelRevision = 0L

    // Each wiring has its own child group so retiring one session cannot cancel a replacement's consumers.
    private val group = SupervisorJob(scope.coroutineContext[Job])

    /** Live consumer jobs, including any that a re-wire failed to cancel (test visibility for duplicate collectors). */
    val activeJobCount: Int get() = group.children.sumOf { owner -> owner.children.count { it.isActive } }

    fun wire(token: SessionToken, sources: MessageEventSources) {
        wireIfCurrent(token, sources, { it() }) { true }
    }

    internal fun wireIfCurrent(
        token: SessionToken,
        sources: MessageEventSources,
        boundary: (() -> Unit) -> Unit,
        reuseExistingSameSession: Boolean = false,
        isCurrent: () -> Boolean,
    ): Boolean {
        val next = Wiring(token, SupervisorJob(group), sources.messageStatus)
        val (expectedCancelRevision, observedWiring) = synchronized(lock) {
            prepared.add(next)
            cancelRevision to wiring
        }
        var previous: Wiring? = null
        var installed = false
        var reused = false
        var failedWiring = false
        try {
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
            val retired = synchronized(lock) {
                next.preparing = false
                next.retired
            }
            if (retired) return false
            boundary {
                if (next.group.isActive && isCurrent()) {
                    synchronized(lock) {
                        if (!next.retired && next.group.isActive && cancelRevision == expectedCancelRevision) {
                            val current = wiring
                            if (next.failed.get()) {
                                failedWiring = true
                                if (current != null && current.token != token) {
                                    previous = current
                                    wiring = null
                                }
                            } else if (reuseExistingSameSession && current != null && current.token == token && current.group.isActive) {
                                if (current.failed.get()) {
                                    failedWiring = true
                                } else {
                                    prepared.remove(next)
                                    reused = true
                                }
                            } else if (current == null || current === observedWiring) {
                                prepared.remove(next)
                                previous = current
                                wiring = next
                                installed = true
                            }
                        }
                    }
                }
            }
        } finally {
            synchronized(lock) { prepared.remove(next) }
            if (installed) {
                next.admitted.complete(Unit)
                previous?.let(::cancel)
            } else {
                try {
                    cancel(next)
                } finally {
                    previous?.let(::cancel)
                }
            }
        }
        if (failedWiring) {
            logger.warning("Message event wiring rejected because an event stream failed or was cancelled")
        }
        return installed || reused
    }

    /** Cancels every stream-consuming job; called before re-wiring and from the disconnect teardown. */
    fun cancelAll() {
        val retired = synchronized(lock) {
            cancelRevision = Math.incrementExact(cancelRevision)
            // Each source is already registered; preparation must start its collector before cancellation can clean it up.
            val pending = prepared.onEach { it.retired = true }.filterNot { it.preparing }
            val current = wiring
            wiring = null
            pending + listOfNotNull(current)
        }
        retired.forEach(::cancel)
    }

    internal fun cancelForSession(token: SessionToken?) {
        val retired = synchronized(lock) {
            val pending = prepared.filter { it.token == token }.onEach { it.retired = true }
                .filterNot { it.preparing }
            val current = wiring?.takeIf { it.token == token }
            if (current != null) wiring = null
            pending + listOfNotNull(current)
        }
        retired.forEach(::cancel)
    }

    private fun cancel(wiring: Wiring) {
        if (!wiring.closed.compareAndSet(false, true)) return
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
                    wiring.admitted.await()
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
                if (wiring.group.isActive) {
                    wiring.failed.set(true)
                    logger.log(Level.WARNING, "$label stream cancelled before session retirement")
                }
                throw cancelled
            } catch (failure: Exception) {
                wiring.failed.set(true)
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
