// PortedFrom: MC1Services/Sources/MC1Services/Services/HeardRepeatEvent.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.reactions

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Notification that a heard repeat (a sent-channel echo or a distinct extra incoming path) was recorded.
 *
 * Broadcast by [HeardRepeatsService.events]. The stream is multicast: every subscriber receives every event.
 */
data class HeardRepeatEvent(
    /** The message the repeat was correlated to. */
    val messageID: UUID,
    /** The message's updated heard-repeat count (Swift `Int`). */
    val count: Long,
)

/**
 * Multicast fan-out for [HeardRepeatEvent] (Swift `EventBroadcaster<HeardRepeatEvent>`, which lives in
 * core:runtime and is not visible to this module). Producers yield synchronously and never block; registration
 * happens when [subscribe] is called (not when the flow is collected), so events yielded after the call are
 * never dropped. [finish] ends every subscriber and makes later subscriptions empty.
 */
internal class HeardRepeatEventBroadcaster {
    private val lock = Any()
    private val subscribers = LinkedHashMap<Long, Channel<HeardRepeatEvent>>()
    private var nextId = 0L
    private var finished = false

    /**
     * Registers a subscription now and returns its stream. The stream is single-collection (like the Swift
     * `AsyncStream` it replaces): collect it exactly once, because it buffers every later event until collected
     * or until [finish]; collecting it a second time throws [IllegalStateException].
     */
    fun subscribe(): Flow<HeardRepeatEvent> {
        val channel = Channel<HeardRepeatEvent>(Channel.UNLIMITED)
        val collected = AtomicBoolean(false)
        val id = synchronized(lock) {
            val assigned = nextId++
            if (finished) channel.close() else subscribers[assigned] = channel
            assigned
        }
        return flow {
            check(collected.compareAndSet(false, true)) { "A HeardRepeatsService.events() stream can be collected only once" }
            try {
                for (event in channel) emit(event)
            } finally {
                synchronized(lock) { subscribers.remove(id) }
                channel.cancel()
            }
        }
    }

    fun yield(event: HeardRepeatEvent) {
        synchronized(lock) {
            val closed = subscribers.filterValues { it.trySend(event).isClosed }.keys
            closed.forEach(subscribers::remove)
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

    /** Test-visible number of live subscriptions. */
    val subscriberCount: Int get() = synchronized(lock) { subscribers.size }
}
