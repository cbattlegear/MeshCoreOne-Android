// PortedFrom: MeshCore/Sources/MeshCore/Events/EventDispatcher.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: eager per-subscriber channels preserve bounded hot delivery and explicit termination.
// Native adaptation: WP-208 Nonactivated critical ingress retains bounded terminal uncertainty separately.
package com.meshcoreone.android.core.protocol.event

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class EventDrop(val subscriptionId: UUID, val caseName: String, val totalDroppedEvents: Long)

/** Metadata for the actual issuing generation, not a radio/session authorization capability. */
class CriticalEventOwner internal constructor(val generation: Long) {
    val id: UUID = UUID.randomUUID()
}

enum class CriticalEventKind {
    ACKNOWLEDGEMENTS, RECEIVE_LOG, MESSAGES;

    internal val filter: EventFilter
        get() = when (this) {
            ACKNOWLEDGEMENTS -> EventFilter.anyAcknowledgement
            RECEIVE_LOG -> EventFilter.rxLogData
            MESSAGES -> EventFilter.anyContactMessage or EventFilter.anyChannelMessage
        }
}

enum class CriticalEventEnd { OVERFLOW, CONSUMER_ENDED, GENERATION_ENDED }

/**
 * Immutable custody of the original unresolved input, never a replay or successor-selection grant.
 * The in-flight consumer may already have selected or committed work; do not redrive it as raw input.
 * Later physical input may be unknown. This is bounded uncertainty reporting, not lossless ingress.
 */
class CriticalEventTerminal internal constructor(
    val owner: CriticalEventOwner,
    val kind: CriticalEventKind,
    val reason: CriticalEventEnd,
    val inFlight: MeshEvent?,
    val backlog: EventList<MeshEvent>,
    val firstOverflow: MeshEvent?,
) : Exception("Critical event ingress ended: $kind/$reason; subsequent input may be unknown")

/**
 * One eager subscription per kind per actual generation, with independent terminal observation.
 * Cancellation/overflow never discards custody or permits registration of a replacement subscription.
 * Ordinary event subscriptions retain their existing policy.
 */
class CriticalEventSubscription internal constructor(
    val owner: CriticalEventOwner,
    val kind: CriticalEventKind,
    private val requestPublication: (CriticalEventSubscription) -> Unit = { it.publishNow() },
) {
    private val lock = Any()
    private val collected = AtomicBoolean(false)
    private val queue = ArrayDeque<MeshEvent>()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private val ended = CompletableDeferred<CriticalEventTerminal>()
    private var inFlight: MeshEvent? = null
    private var failure: CriticalEventTerminal? = null

    val terminal: CriticalEventTerminal? get() = synchronized(lock) { failure }

    suspend fun awaitTermination(): CriticalEventTerminal = ended.await()

    fun close() {
        end(CriticalEventEnd.CONSUMER_ENDED)
        publish()
    }

    val stream: Flow<MeshEvent> = flow {
        check(collected.compareAndSet(false, true)) { "A critical subscription permits one consumer" }
        try {
            while (true) {
                emit(next())
                synchronized(lock) { if (failure == null) inFlight = null }
            }
        } finally {
            close()
        }
    }

    internal fun offer(event: MeshEvent) = synchronized(lock) {
        if (failure == null) {
            if (queue.size == EventDispatcher.BUFFER_CAPACITY) endLocked(CriticalEventEnd.OVERFLOW, event)
            else queue.addLast(event)
        }
    }

    internal fun end(reason: CriticalEventEnd) = synchronized(lock) {
        if (failure == null) endLocked(reason, null)
    }

    private fun endLocked(reason: CriticalEventEnd, overflow: MeshEvent?) {
        failure = CriticalEventTerminal(owner, kind, reason, inFlight, EventList(queue), overflow)
        queue.clear()
        inFlight = null
    }

    internal fun publish() = requestPublication(this)

    /** Session subscriptions route every publication, including close, through the outermost core boundary. */
    internal fun publishNow() {
        terminal?.let { ended.complete(it) }
        wakeup.trySend(Unit)
    }

    private suspend fun next(): MeshEvent {
        while (true) {
            val event = synchronized(lock) {
                failure?.let { throw it }
                if (queue.isEmpty()) null else queue.removeFirst().also { inFlight = it }
            }
            if (event != null) return event
            wakeup.receive()
        }
    }
}

class EventSubscription internal constructor(
    val id: UUID,
    private val buffer: EventBuffer,
    private val terminated: (UUID) -> Unit,
) {
    private val collected = AtomicBoolean(false)

    val stream: Flow<MeshEvent> = flow {
        check(collected.compareAndSet(false, true)) { "An event subscription permits one consumer" }
        try {
            while (true) {
                val event = buffer.next() ?: break
                emit(event)
            }
        } finally {
            terminated(id)
        }
    }
}

internal class EventBuffer {
    private sealed interface Next {
        data class Value(val event: MeshEvent) : Next
        data object Waiting : Next
        data object Finished : Next
    }

    private val lock = Any()
    private val queue = ArrayDeque<MeshEvent>()
    // Only wakeups conflate; every data event remains in the separately locked bounded queue.
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private var finished = false

    fun offer(event: MeshEvent): MeshEvent? {
        val dropped = synchronized(lock) {
            if (finished) return null
            val oldest = if (queue.size == EventDispatcher.BUFFER_CAPACITY) queue.removeFirst() else null
            queue.addLast(event)
            oldest
        }
        wakeup.trySend(Unit)
        return dropped
    }

    suspend fun next(): MeshEvent? {
        while (true) {
            val next = synchronized(lock) {
                when {
                    queue.isNotEmpty() -> Next.Value(queue.removeFirst())
                    finished -> Next.Finished
                    else -> Next.Waiting
                }
            }
            when (next) {
                is Next.Value -> return next.event
                Next.Finished -> return null
                Next.Waiting -> wakeup.receiveCatching()
            }
        }
    }

    fun finish(discardPending: Boolean = false) {
        synchronized(lock) {
            finished = true
            if (discardPending) queue.clear()
        }
        wakeup.close()
    }
}

class EventDispatcher(private val onDrop: (EventDrop) -> Unit = ::logDrop) {
    private data class Subscription(val buffer: EventBuffer, val filter: ((MeshEvent) -> Boolean)?)
    private val lock = Any()
    private val subscriptions = linkedMapOf<UUID, Subscription>()
    private val critical = linkedMapOf<CriticalEventKind, CriticalEventSubscription>()
    private var droppedCount = 0L

    val droppedEventCount: Long get() = synchronized(lock) { droppedCount }
    val subscriberCount: Int get() = synchronized(lock) {
        subscriptions.size + critical.values.count { it.terminal == null }
    }

    internal fun subscribeCritical(
        owner: CriticalEventOwner,
        kind: CriticalEventKind,
        requestPublication: (CriticalEventSubscription) -> Unit = { it.publishNow() },
    ): CriticalEventSubscription =
        synchronized(lock) {
            critical[kind]?.also {
                check(it.owner === owner) { "A critical subscription cannot change generation" }
            } ?: CriticalEventSubscription(owner, kind, requestPublication).also { critical[kind] = it }
        }

    fun subscribe(): Flow<MeshEvent> = subscribeTracked().stream
    fun subscribe(filter: (MeshEvent) -> Boolean): Flow<MeshEvent> = subscribeTracked(filter).stream
    fun subscribe(filter: EventFilter): Flow<MeshEvent> = subscribe(filter::matches)

    fun subscribeTracked(filter: ((MeshEvent) -> Boolean)? = null): EventSubscription {
        val id = UUID.randomUUID()
        val buffer = EventBuffer()
        synchronized(lock) { subscriptions[id] = Subscription(buffer, filter) }
        return EventSubscription(id, buffer, ::removeSubscription)
    }

    fun subscribeTracked(filter: EventFilter): EventSubscription = subscribeTracked(filter::matches)

    fun dispatch(event: MeshEvent) {
        dispatchDeferred(event).forEach { it.publish() }
    }

    internal fun dispatchDeferred(event: MeshEvent): List<CriticalEventSubscription> {
        synchronized(lock) {
            val notifications = captureCritical(event)
            dispatchOrdinary(event)
            return notifications
        }
    }

    internal fun captureCritical(event: MeshEvent): List<CriticalEventSubscription> = synchronized(lock) {
        critical.values.filter { it.terminal == null && it.kind.filter.matches(event) }.onEach {
            it.offer(event)
        }
    }

    internal fun dispatchOrdinary(event: MeshEvent) {
        synchronized(lock) {
            for ((id, subscription) in subscriptions.toList()) {
                if (subscription.filter?.invoke(event) == false) continue
                val discarded = subscription.buffer.offer(event)
                if (discarded != null) {
                    droppedCount += 1
                    onDrop(EventDrop(id, discarded.caseName, droppedCount))
                }
            }
        }
    }

    fun finishAllSubscriptions() {
        finishAllDeferred().forEach { it.publish() }
    }

    internal fun finishAllDeferred(): List<CriticalEventSubscription> {
        synchronized(lock) {
            val notifications = endCritical()
            val endingSubscriptions = subscriptions.values.toList()
            subscriptions.clear()
            endingSubscriptions.forEach { it.buffer.finish() }
            return notifications
        }
    }

    internal fun endCritical(): List<CriticalEventSubscription> = synchronized(lock) {
        critical.values.toList().onEach { it.end(CriticalEventEnd.GENERATION_ENDED) }
    }

    fun finishSubscription(id: UUID) {
        synchronized(lock) { subscriptions.remove(id)?.buffer?.finish() }
    }

    private fun removeSubscription(id: UUID) {
        synchronized(lock) { subscriptions.remove(id)?.buffer?.finish(discardPending = true) }
    }

    companion object {
        const val BUFFER_CAPACITY = 100
        private val logger = Logger.getLogger("MeshCore.EventDispatcher")
        private fun logDrop(drop: EventDrop) {
            logger.warning("dropped event case=${drop.caseName} totalDrops=${drop.totalDroppedEvents}")
        }
    }
}
