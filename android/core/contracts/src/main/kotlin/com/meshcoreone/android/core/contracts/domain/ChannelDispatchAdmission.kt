// AndroidOnly: WP-201 Exact connection admission shared by channel dispatch and runtime lifecycle owners.
package com.meshcoreone.android.core.contracts.domain

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One immutable connection-owner binding. Revocation prevents new admission but does not
 * cancel admitted operations or prove that a physical transport has drained.
 */
class ChannelDispatchAdmission(val token: SessionToken, val connectionIdentity: Any) {
    private val state = AtomicLong(0)
    private val paused = AtomicBoolean(false)

    val isRevoked: Boolean get() = state.get() < 0
    val isPaused: Boolean get() = paused.get()
    val inFlightCount: Long get() = state.get() and Long.MAX_VALUE

    fun tryAcquire(token: SessionToken, connectionIdentity: Any): Permit? {
        if (this.token != token || this.connectionIdentity !== connectionIdentity) return null
        while (true) {
            val current = state.get()
            if (current < 0 || paused.get() || current == Long.MAX_VALUE) return null
            if (state.compareAndSet(current, current + 1)) {
                val permit = Permit(this)
                if (!paused.get()) return permit
                permit.complete()
                return null
            }
        }
    }

    fun revoke() {
        while (true) {
            val current = state.get()
            if (current < 0 || state.compareAndSet(current, current or Long.MIN_VALUE)) return
        }
    }

    fun pause() {
        paused.set(true)
    }

    fun resume() {
        paused.set(false)
    }

    private fun completeAdmission() {
        while (true) {
            val current = state.get()
            check((current and Long.MAX_VALUE) > 0) { "Channel admission accounting underflow" }
            if (state.compareAndSet(current, current - 1)) return
        }
    }

    class Permit internal constructor(private val admission: ChannelDispatchAdmission) {
        private val completed = AtomicBoolean(false)

        fun complete() {
            if (completed.compareAndSet(false, true)) admission.completeAdmission()
        }
    }
}
