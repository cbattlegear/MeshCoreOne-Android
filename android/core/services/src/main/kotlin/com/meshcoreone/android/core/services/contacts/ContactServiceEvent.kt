// PortedFrom: MC1Services/Sources/MC1Services/Services/ContactServiceEvent.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/ContactService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.contacts

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One-to-many notifications broadcast by [ContactService] to its `events()` subscribers. */
sealed interface ContactServiceEvent {
    /** Progress during a contact sync: [received] of [total] contacts persisted. */
    data class SyncProgress(val received: Long, val total: Long) : ContactServiceEvent

    /** A contact was removed from the device, so node storage is no longer full. */
    data object NodeDeleted : ContactServiceEvent
}

/**
 * A registered `events()` stream. Registration happens when the subscription is created (the
 * Swift `AsyncStream` continuation is installed before `subscribe()` returns), so an event
 * yielded right after `events()` returns is never dropped. [events] has exactly one consumer;
 * [close] unregisters, matching the Swift stream's `onTermination`.
 */
class ContactEventSubscription internal constructor(
    private val channel: Channel<ContactServiceEvent>,
    private val unregister: () -> Unit,
) : AutoCloseable {
    private val collected = AtomicBoolean()

    val events: Flow<ContactServiceEvent> = flow {
        check(collected.compareAndSet(false, true)) { "A contact event subscription has exactly one consumer" }
        try {
            for (event in channel) emit(event)
        } finally {
            close()
        }
    }

    override fun close() {
        unregister()
        channel.cancel()
    }
}

/**
 * Unbounded multicast broadcaster for [ContactServiceEvent], confined behind a lock like the Swift
 * `EventBroadcaster` (OSAllocatedUnfairLock). `yield` is synchronous so per-producer ordering is
 * preserved; `finish` ends every subscriber and later subscriptions start already finished.
 */
internal class ContactEventBroadcaster {
    private val lock = Any()
    private val subscribers = linkedMapOf<UUID, Channel<ContactServiceEvent>>()
    private var finished = false

    fun subscribe(): ContactEventSubscription {
        val channel = Channel<ContactServiceEvent>(Channel.UNLIMITED)
        val id = UUID.randomUUID()
        synchronized(lock) {
            if (finished) channel.close() else subscribers[id] = channel
        }
        return ContactEventSubscription(channel) { synchronized(lock) { subscribers.remove(id) } }
    }

    fun yield(event: ContactServiceEvent) {
        synchronized(lock) {
            val stale = subscribers.filterValues { channel -> channel.trySend(event).isClosed }.keys
            stale.forEach(subscribers::remove)
        }
    }

    fun finish() {
        synchronized(lock) {
            if (finished) return
            finished = true
            subscribers.values.forEach { it.close() }
            subscribers.clear()
        }
    }
}
