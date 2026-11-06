// AndroidOnly: WP-215 Process-memory receipt bridging notification delivery and session-owned operations.
package com.meshcoreone.android.core.contracts.notifications

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * One queued action, not a message identity or durable delivery guarantee. The dispatcher retains this
 * across consumer replacement. The session-owned sender must capture it before switching scopes.
 * Acceptance means the radio command returned successfully, not that the recipient received a message.
 */
class NotificationActionReceipt : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NotificationActionReceipt>

    enum class State { READY, IN_FLIGHT, ACCEPTED, UNCERTAIN, COMMITTED }

    @Volatile
    var state: State = State.READY
        private set

    val preventsReplay: Boolean get() = state != State.READY
    val isAccepted: Boolean get() = state == State.ACCEPTED

    @Synchronized
    fun wireStarted() {
        check(state == State.READY)
        state = State.IN_FLIGHT
    }

    /** A definite device rejection permits the existing backoff/retry path. */
    @Synchronized
    fun wireRejected() {
        check(state == State.IN_FLIGHT)
        state = State.READY
    }

    /**
     * Only the actual protocol dispatch owner may prove this after the cancelled call settles.
     * A failed persistence row, a missing response or cancellation alone is not proof of no send.
     */
    @Synchronized
    fun wireNotDispatched() {
        check(state == State.IN_FLIGHT)
        state = State.READY
    }

    /** The first mark-read persistence write has started and may have committed despite failure. */
    @Synchronized
    fun writeStarted() {
        check(state == State.READY)
        state = State.IN_FLIGHT
    }

    @Synchronized
    fun accepted() {
        check(state == State.READY || state == State.IN_FLIGHT || state == State.ACCEPTED)
        state = State.ACCEPTED
    }

    @Synchronized
    fun uncertain() {
        if (state == State.IN_FLIGHT) state = State.UNCERTAIN
    }

    /** A mark-read action whose writes started is committed only after all of them return. */
    @Synchronized
    fun writeCommitted() {
        check(state == State.IN_FLIGHT)
        state = State.COMMITTED
    }

    /** A mark-read action with no started writes can commit only from its initial state. */
    @Synchronized
    fun committed() {
        check(state == State.READY)
        state = State.COMMITTED
    }
}

class NotificationReplyOutcomeUncertain(cause: Throwable) :
    Exception("Notification reply acceptance is unknown; automatic retransmission is unsafe", cause)

/**
 * Process-memory local-write observation, never wire evidence. A failed write may have committed
 * before throwing; completedWrites counts returned calls, not the full set of possible effects.
 */
class NotificationReadReceipt : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NotificationReadReceipt>

    enum class State { READY, STARTED, COMMITTED, UNCERTAIN }

    @Volatile
    var state: State = State.READY
        private set

    @Volatile
    var completedWrites: Int = 0
        private set

    val preventsReplay: Boolean get() = state != State.READY

    @Synchronized
    fun started() {
        check(state == State.READY)
        state = State.STARTED
    }

    @Synchronized
    fun writeReturned() {
        check(state == State.STARTED)
        completedWrites++
    }

    @Synchronized
    fun committed() {
        check(state == State.STARTED)
        state = State.COMMITTED
    }

    @Synchronized
    fun uncertain() {
        if (state == State.STARTED) state = State.UNCERTAIN
    }
}
