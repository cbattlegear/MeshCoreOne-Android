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

    @Synchronized
    fun accepted() {
        check(state == State.READY || state == State.IN_FLIGHT || state == State.ACCEPTED)
        state = State.ACCEPTED
    }

    @Synchronized
    fun uncertain() {
        if (state == State.IN_FLIGHT) state = State.UNCERTAIN
    }

    /** All requested mark-read writes returned; partial writes are not a committed action. */
    @Synchronized
    fun committed() {
        check(state == State.READY)
        state = State.COMMITTED
    }
}

class NotificationReplyOutcomeUncertain(cause: Throwable) :
    Exception("Notification reply acceptance is unknown; automatic retransmission is unsafe", cause)
