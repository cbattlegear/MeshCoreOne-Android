// PortedFrom: MC1Services/Sources/MC1Services/Sync/SyncDataEvent.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Data-change and incoming-message notifications broadcast by [SyncCoordinator].
 *
 * Subscribe via [SyncCoordinator.dataEvents]. The stream is multicast: every subscriber receives every
 * event, so coexisting consumers (app state for version bumps, the message event dispatcher for chat
 * forwarding) never steal each other's events.
 */
sealed interface SyncDataEvent {
    /** Contacts data changed; observers should reload contact lists. */
    data object ContactsChanged : SyncDataEvent

    /** Conversations data changed; observers should reload chat lists. */
    data object ConversationsChanged : SyncDataEvent

    /** An incoming direct message was persisted for a known contact. */
    data class DirectMessageReceived(val message: MessageDTO, val contact: ContactDTO) : SyncDataEvent

    /** An incoming channel message was persisted. */
    data class ChannelMessageReceived(val message: MessageDTO, val channelIndex: UByte) : SyncDataEvent

    /** An incoming signed room message was persisted. */
    data class RoomMessageReceived(val message: RoomMessageDTO) : SyncDataEvent

    /** A reaction was persisted and its target message's summary updated. */
    data class ReactionReceived(val messageID: UUID, val summary: String) : SyncDataEvent
}

/**
 * Multicast fan-out for [SyncDataEvent] (Swift `EventBroadcaster<SyncDataEvent>`, WP-207's utility, which
 * this module cannot reach). Producers yield synchronously from any thread. Registration happens when
 * [subscribe] is called, not when the flow is collected, so events yielded after the call are never
 * dropped. Swift unregisters a stream when it is deallocated; here a subscriber is removed when its
 * collection ends or [finish] runs, so a caller that subscribes must collect.
 */
class SyncDataEventBroadcaster {
    private val lock = Any()
    private val subscribers = LinkedHashMap<Long, Channel<SyncDataEvent>>()
    private var nextId = 0L
    private var finished = false

    /** Live subscriber count (Swift `subscriberCount`). */
    val subscriberCount: Int get() = synchronized(lock) { subscribers.size }

    fun subscribe(): Flow<SyncDataEvent> {
        val channel = Channel<SyncDataEvent>(Channel.UNLIMITED)
        val id = synchronized(lock) {
            val assigned = nextId++
            if (finished) channel.close() else subscribers[assigned] = channel
            assigned
        }
        return flow {
            try {
                for (event in channel) emit(event)
            } finally {
                synchronized(lock) { subscribers.remove(id) }
                channel.cancel()
            }
        }
    }

    fun yield(event: SyncDataEvent) {
        tryYield(event)
    }

    /** Returns false when teardown has already finished this destination. */
    fun tryYield(event: SyncDataEvent): Boolean = synchronized(lock) {
        if (finished) return@synchronized false
        val closed = subscribers.filterValues { it.trySend(event).isClosed }.keys
        closed.forEach(subscribers::remove)
        true
    }

    /** Ends every subscriber's collection after it drains what was already yielded. */
    fun finish() {
        synchronized(lock) {
            if (finished) return
            finished = true
            subscribers.values.forEach { it.close() }
            subscribers.clear()
        }
    }
}
