// PortedFrom: MC1Services/Sources/MC1Services/Connection/ConnectionManager.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Connection/ConnectionManager+BLE.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Connection/ConnectionManager+WiFi.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.runtime

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ConnectionCircuitBreaker(private val clock: RuntimeClock) {
    sealed interface State {
        data object Closed : State
        data class Open(val since: Duration) : State
        data object HalfOpen : State
    }
    var state: State = State.Closed
        private set
    fun allows(force: Boolean): Boolean {
        if (force) return true
        val current = state
        if (current is State.Open) {
            if (clock.elapsed - current.since < 30.seconds) return false
            state = State.HalfOpen
        }
        return true
    }
    fun recordFailure() { if (state !is State.Open) state = State.Open(clock.elapsed) }
    fun recordSuccess() { state = State.Closed }
}

object ConnectionRetryPolicy {
    fun connectAttempts(forceReconnect: Boolean, hasSystemPairingRegistry: Boolean): Int =
        if (forceReconnect && !hasSystemPairingRegistry) 2 else 4

    fun connectDelay(attempt: Int, jitter: Double): Duration {
        require(attempt in 1..4 && jitter.isFinite() && jitter in 0.0..0.1)
        return (300L shl (attempt - 1)).milliseconds * (1 + jitter)
    }
    fun watchdogDelay(attempt: Int): Duration {
        require(attempt >= 1)
        return when (attempt) { 1 -> 30.seconds; 2 -> 60.seconds; else -> 120.seconds }
    }
    fun wifiDelay(attempt: Int): Duration {
        require(attempt >= 1)
        return when (attempt) { 1 -> 500.milliseconds; 2 -> 1.seconds; 3 -> 2.seconds; else -> 4.seconds }
    }
    val WIFI_RECONNECT_WINDOW = 30.seconds
    val WIFI_RECONNECT_COOLDOWN = 35.seconds
    const val MAX_REBUILD_FAILURES_PRESERVING_LINK = 3
}
