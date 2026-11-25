// PortedFrom: MC1Services/Sources/MC1Services/Utilities/EventBroadcaster.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.runtime

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface BufferingPolicy {
    data object Unbounded : BufferingPolicy
    data class Newest(val capacity: Int) : BufferingPolicy { init { require(capacity >= 0) } }
    data class Oldest(val capacity: Int) : BufferingPolicy { init { require(capacity >= 0) } }
}

class EventSubscription<T> internal constructor(
    private val channel: Channel<T>,
    private val unregister: () -> Unit,
    private val terminalCause: () -> Throwable?,
) : AutoCloseable {
    private val collected = AtomicBoolean()
    val events: Flow<T> = flow {
        check(collected.compareAndSet(false, true)) { "A subscription has exactly one consumer" }
        try {
            for (event in channel) emit(event)
            terminalCause()?.let { throw it }
        } finally { close() }
    }
    override fun close() {
        unregister()
        channel.cancel()
    }
}

class EventBroadcaster<T> {
    private class Subscriber<T>(val channel: Channel<T>, val bounded: Boolean) {
        @Volatile var terminal: Throwable? = null
    }
    private val lock = Any()
    private val subscribers = linkedMapOf<UUID, Subscriber<T>>()
    private var finished = false
    private var failure: Throwable? = null
    val subscriberCount: Int get() = synchronized(lock) { subscribers.size }

    fun subscribe(policy: BufferingPolicy = BufferingPolicy.Unbounded): EventSubscription<T> {
        val channel = when (policy) {
            BufferingPolicy.Unbounded -> Channel<T>(Channel.UNLIMITED)
            is BufferingPolicy.Newest -> if (policy.capacity == 0) Channel<T>() else
                Channel<T>(policy.capacity, BufferOverflow.DROP_OLDEST)
            is BufferingPolicy.Oldest -> if (policy.capacity == 0) Channel<T>() else
                Channel<T>(policy.capacity, BufferOverflow.DROP_LATEST)
        }
        val id = UUID.randomUUID()
        val subscriber = Subscriber(channel, policy != BufferingPolicy.Unbounded)
        synchronized(lock) {
            if (finished) { subscriber.terminal = failure; channel.close() }
            else subscribers[id] = subscriber
        }
        return EventSubscription(channel, { synchronized(lock) { subscribers.remove(id) } }, { subscriber.terminal })
    }

    fun yield(event: T) {
        synchronized(lock) {
            val stale = mutableListOf<UUID>()
            for ((id, subscriber) in subscribers) {
                val result = subscriber.channel.trySend(event)
                if (result.isClosed) stale += id
                else check(result.isSuccess || subscriber.bounded) { "Unbounded subscriber lost an event" }
            }
            stale.forEach(subscribers::remove)
        }
    }

    fun finish(cause: Throwable? = null) {
        synchronized(lock) {
            if (finished) return
            finished = true
            failure = cause
            subscribers.values.forEach { it.terminal = cause; it.channel.close() }
            subscribers.clear()
        }
    }
}
