// PortedFrom: MC1/State/LiveActivityManager.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.diagnostics

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/** Connection-owned arrivals; decay polling exists only while a visible consumer collects. */
internal class RxPacketRate(private val clock: Clock) {
    private val lock = Any()
    private val timestamps = ArrayDeque<Instant>()
    private val changes = MutableStateFlow(0L)

    fun record() = synchronized(lock) {
        val now = clock.instant()
        prune(now)
        timestamps.addLast(now)
        changes.value += 1
    }

    fun reset() = synchronized(lock) {
        timestamps.clear()
        changes.value += 1
    }

    fun rates(): Flow<Long> = combine(changes, flow {
        while (true) {
            emit(Unit)
            delay(15_000)
        }
    }) { _, _ ->
        synchronized(lock) {
            val now = clock.instant()
            prune(now)
            calculate(timestamps, now)
        }
    }.distinctUntilChanged()

    private fun prune(now: Instant) {
        val cutoff = now.minusSeconds(60)
        // Inspect every timestamp, including after a wall-clock correction.
        timestamps.removeAll { it < cutoff }
    }

    companion object {
        fun calculate(
            timestamps: Iterable<Instant>,
            now: Instant,
            window: Duration = Duration.ofSeconds(60),
        ): Long {
            require(!window.isZero && !window.isNegative) { "Packet-rate window must be positive" }
            val cutoff = now.minus(window)
            val count = timestamps.count { it >= cutoff }
            val seconds = window.seconds.toDouble() + window.nano / 1_000_000_000.0
            return (count * 60.0 / seconds).roundToLong()
        }
    }
}
