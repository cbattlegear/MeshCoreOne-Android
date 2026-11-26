// PortedFrom: MeshCore/Sources/MeshCore/Events/EventDispatcher.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: eager per-subscriber channels preserve bounded hot delivery and explicit termination.
package com.meshcoreone.android.core.protocol.event

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class EventDrop(val subscriptionId: UUID, val caseName: String, val totalDroppedEvents: Long)

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
    private var droppedCount = 0L

    val droppedEventCount: Long get() = synchronized(lock) { droppedCount }
    val subscriberCount: Int get() = synchronized(lock) { subscriptions.size }

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
        synchronized(lock) {
            subscriptions.values.forEach { it.buffer.finish() }
            subscriptions.clear()
        }
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
