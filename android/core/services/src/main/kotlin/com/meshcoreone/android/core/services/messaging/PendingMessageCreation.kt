// AndroidOnly: WP-208 Caller-held identity and commit observation for cancellable pending-message creation.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.model.MessageDTO
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Install with `withContext(receipt)` around one pending-create call and retain it across cancellation.
 * This observes row creation only: COMMITTED does not mean enqueued, transmitted or delivered.
 * NOT_COMMITTED requires a store result serialized against the original insert; an absent readback
 * alone is UNKNOWN. Reconcile UNKNOWN by identity, never by blindly recreating from the text.
 * The receipt is process memory, not a durable operation journal.
 */
class PendingMessageCreation : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PendingMessageCreation>

    enum class State { NOT_STARTED, WRITING, COMMITTED, NOT_COMMITTED, UNKNOWN }

    data class Snapshot(val state: State, val message: MessageDTO?)

    @Volatile
    var snapshot: Snapshot = Snapshot(State.NOT_STARTED, null)
        private set

    @Synchronized
    internal fun writing(message: MessageDTO) {
        check(snapshot.state == State.NOT_STARTED) { "A pending creation receipt is single-use" }
        snapshot = Snapshot(State.WRITING, message)
    }

    @Synchronized
    internal fun settled(state: State) {
        check(snapshot.state == State.WRITING)
        check(state == State.COMMITTED || state == State.NOT_COMMITTED || state == State.UNKNOWN)
        snapshot = Snapshot(state, checkNotNull(snapshot.message))
    }
}
