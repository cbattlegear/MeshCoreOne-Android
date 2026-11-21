// PortedFrom: MC1/Services/MapSnapshotStore.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Map/MapSnapshotRequest.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.maps

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs
import kotlin.math.floor

data class MapSnapshotRequest private constructor(
    val latitude: Double,
    val longitude: Double,
    val isDark: Boolean,
    val isOffline: Boolean,
) {
    val coordinate = GeoPoint(latitude, longitude)
    val cacheKey: String = "$latitude,$longitude,$isDark,$isOffline"

    companion object {
        operator fun invoke(
            latitude: Double,
            longitude: Double,
            isDark: Boolean,
            isOffline: Boolean,
        ): MapSnapshotRequest {
            fun normalized(value: Double): Double {
                // Swift's default rounded() breaks exact ties away from zero, not to even.
                val scaled = abs(value * 100_000.0)
                val integral = floor(scaled)
                val magnitude = integral + if (scaled - integral >= 0.5) 1.0 else 0.0
                val rounded = (if (value < 0.0) -magnitude else magnitude) / 100_000.0
                return if (rounded == 0.0) 0.0 else rounded
            }
            return MapSnapshotRequest(
                latitude = normalized(latitude),
                longitude = normalized(longitude),
                isDark = isDark && !isOffline,
                isOffline = isOffline,
            )
        }
    }
}

interface MapSnapshotRenderer<T : Any> {
    suspend fun render(request: MapSnapshotRequest): T?

    suspend fun render(request: MapSnapshotRequest, isWanted: () -> Boolean): T? =
        if (isWanted()) render(request) else throw MapSnapshotNoLongerWanted()
}

class MapSnapshotNoLongerWanted : CancellationException("Map snapshot is no longer wanted")

class MapSnapshotStore<T : Any>(
    private val renderer: MapSnapshotRenderer<T>,
    private val scope: CoroutineScope,
    private val costOf: (T) -> Long,
    private val cacheCountLimit: Int = 50,
    private val cacheCostLimitBytes: Long = 50L * 1024L * 1024L,
    private val failedSetSizeLimit: Int = 200,
) : AutoCloseable {
    private data class Entry<T>(val value: T, val cost: Long)
    private class Flight(val generation: Long) {
        var unscoped = false
        val consumers = mutableMapOf<Any, () -> Boolean>()
    }

    private val lock = Any()
    private val semaphore = Semaphore(1)
    private val entries = linkedMapOf<MapSnapshotRequest, Entry<T>>()
    private val inFlight = mutableMapOf<MapSnapshotRequest, Flight>()
    private val failed = linkedSetOf<MapSnapshotRequest>()
    private val jobs = mutableSetOf<Job>()
    private var totalCost = 0L
    private var generation = 0L
    private var closed = false

    private val _resolutions = MutableSharedFlow<MapSnapshotRequest>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val resolutions: SharedFlow<MapSnapshotRequest> = _resolutions.asSharedFlow()
    private val mutableRevision = MutableStateFlow(0L)
    /** Conflated invalidation for cache readers: unlike per-key events, the latest change cannot overflow. */
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    fun value(request: MapSnapshotRequest): T? = synchronized(lock) { entries[request]?.value }

    fun isResolved(request: MapSnapshotRequest): Boolean =
        synchronized(lock) { request in entries || request in failed }

    fun isPending(request: MapSnapshotRequest): Boolean = synchronized(lock) { request in inFlight }

    fun request(request: MapSnapshotRequest) = enqueue(request, null, null)

    fun request(request: MapSnapshotRequest, owner: Any, isWanted: () -> Boolean) =
        enqueue(request, owner, isWanted)

    private fun enqueue(
        request: MapSnapshotRequest, owner: Any?, isWanted: (() -> Boolean)?, expectedGeneration: Long? = null,
    ) {
        val flight = synchronized(lock) {
            if (expectedGeneration != null && (closed || generation != expectedGeneration)) return
            check(!closed) { "Map snapshot store is closed" }
            if (request in entries || request in failed) return
            val existing = inFlight[request]
            val current = existing ?: Flight(generation).also { inFlight[request] = it }
            if (owner == null) current.unscoped = true else current.consumers[owner] = requireNotNull(isWanted)
            if (existing != null) return
            current
        }
        var rejectedForNoDemand = false
        fun wanted(): Boolean = synchronized(lock) {
            (inFlight[request] === flight &&
                (flight.unscoped || flight.consumers.values.any { it() })).also {
                if (!it) rejectedForNoDemand = true
            }
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var completed = false
            try {
                semaphore.withPermit {
                    if (!wanted()) {
                        completed = true
                        return@withPermit
                    }
                    val rendered = try {
                        renderer.render(request, ::wanted)
                    } catch (_: MapSnapshotNoLongerWanted) {
                        completed = true
                        return@withPermit
                    }
                    completed = true
                    synchronized(lock) {
                        if (generation == flight.generation && wanted()) {
                            if (rendered == null) insertFailure(request) else insertValue(request, rendered)
                            notifyChanged(request)
                        }
                    }
                }
            } finally {
                var restartUnscoped = false
                synchronized(lock) {
                    if (inFlight[request] === flight) {
                        inFlight.remove(request)
                        if (completed && request !in entries && request !in failed &&
                            (flight.consumers.values.any { it() } || flight.unscoped)
                        ) {
                            restartUnscoped = rejectedForNoDemand && flight.unscoped &&
                                generation == flight.generation && !closed
                            if (!restartUnscoped) notifyChanged(request)
                        }
                    }
                }
                if (restartUnscoped) enqueue(request, null, null, flight.generation)
            }
        }
        synchronized(lock) {
            jobs += job
            job.invokeOnCompletion { synchronized(lock) { jobs.remove(job) } }
            if (closed) job.cancel()
        }
        job.start()
    }

    fun release(owner: Any) {
        synchronized(lock) { inFlight.values.forEach { it.consumers.remove(owner) } }
    }

    fun retry(request: MapSnapshotRequest) {
        synchronized(lock) {
            if (!failed.remove(request)) return
            notifyChanged(request)
        }
    }

    fun clearFailures() {
        synchronized(lock) {
            val invalidated = failed.toList()
            failed.clear()
            invalidated.forEach(::notifyChanged)
        }
    }

    fun clear() {
        synchronized(lock) {
            if (closed) return
            generation += 1
            val invalidated = (entries.keys + failed + inFlight.keys).distinct()
            entries.clear()
            failed.clear()
            inFlight.clear()
            totalCost = 0
            invalidated.forEach(::notifyChanged)
        }
    }

    override fun close() {
        val active = synchronized(lock) {
            if (closed) return
            closed = true
            generation += 1
            jobs.toList().also {
                jobs.clear()
                inFlight.clear()
                entries.clear()
                failed.clear()
                totalCost = 0
            }
        }
        active.forEach { it.cancel() }
    }

    private fun insertFailure(request: MapSnapshotRequest) {
        failed += request
        while (failed.size > failedSetSizeLimit) {
            val evicted = failed.first()
            failed.remove(evicted)
            notifyChanged(evicted)
        }
    }

    private fun insertValue(request: MapSnapshotRequest, value: T) {
        val cost = costOf(value).coerceAtLeast(0L)
        entries.remove(request)?.let { totalCost -= it.cost }
        entries[request] = Entry(value, cost)
        totalCost += cost
        while (entries.size > 1 && (entries.size > cacheCountLimit || totalCost > cacheCostLimitBytes)) {
            val evicted = entries.entries.first()
            entries.remove(evicted.key)
            totalCost -= evicted.value.cost
            notifyChanged(evicted.key)
        }
    }

    private fun notifyChanged(request: MapSnapshotRequest) {
        mutableRevision.value += 1
        _resolutions.tryEmit(request)
    }
}
