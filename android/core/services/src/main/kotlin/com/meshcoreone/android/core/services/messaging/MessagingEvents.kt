// PortedFrom: MC1Services/Sources/MC1Services/Utilities/EventBroadcaster.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: WP-208 Android-free generation-tagged, lossless, explicitly terminal subscriptions.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.SessionEvent
import com.meshcoreone.android.core.contracts.domain.SessionEventSubscription
import com.meshcoreone.android.core.contracts.domain.SessionToken
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class MessagingEvents<T>(private val token: SessionToken) {
    private class Subscriber<T>(val queue: Channel<SessionEvent<T>>) {
        @Volatile var failure: Throwable? = null
    }
    private val lock = Any()
    private val subscribers = linkedMapOf<UUID, Subscriber<T>>()
    private var finished = false
    private var failure: Throwable? = null
    val subscriberCount: Int get() = synchronized(lock) { subscribers.size }

    fun subscribe(): SessionEventSubscription<T> {
        val id = UUID.randomUUID()
        val subscriber = Subscriber<T>(Channel(Channel.UNLIMITED))
        synchronized(lock) {
            if (finished) {
                subscriber.failure = failure
                subscriber.queue.close()
            } else subscribers[id] = subscriber
        }
        return object : SessionEventSubscription<T> {
            private val collected = AtomicBoolean()
            override val events: Flow<SessionEvent<T>> = flow {
                check(collected.compareAndSet(false, true)) { "A subscription has one consumer" }
                try {
                    for (event in subscriber.queue) emit(event)
                    subscriber.failure?.let { throw it }
                } finally { close() }
            }
            override fun close() {
                synchronized(lock) { subscribers.remove(id) }
                subscriber.queue.cancel()
            }
        }
    }

    fun yield(event: T) {
        synchronized(lock) {
            if (finished) return
            val stale = mutableListOf<UUID>()
            for ((id, subscriber) in subscribers) {
                val sent = subscriber.queue.trySend(SessionEvent(token, event))
                if (sent.isClosed) stale += id
                else check(sent.isSuccess) { "An unbounded messaging subscriber lost an event" }
            }
            stale.forEach(subscribers::remove)
        }
    }

    fun finish(cause: Throwable? = null) {
        synchronized(lock) {
            if (finished) return
            finished = true
            failure = cause
            subscribers.values.forEach { it.failure = cause; it.queue.close() }
            subscribers.clear()
        }
    }
}
