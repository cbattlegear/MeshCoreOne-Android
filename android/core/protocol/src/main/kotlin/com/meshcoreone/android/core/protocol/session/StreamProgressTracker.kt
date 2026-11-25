// PortedFrom: MeshCore/Sources/MeshCore/Session/StreamProgressTracker.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.session

import kotlin.time.Duration

internal class StreamProgressTracker(private val clock: SessionClock) {
    data class Snapshot(val generation: Long, val elapsed: Duration)
    private val lock = Any()
    private val startedAt = clock.now
    private var generation = 0L

    fun markProgress() = synchronized(lock) { generation += 1 }
    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(generation, clock.now - startedAt) }
}
