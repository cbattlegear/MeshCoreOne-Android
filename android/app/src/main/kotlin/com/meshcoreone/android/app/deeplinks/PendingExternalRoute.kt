// PortedFrom: MC1/State/PendingExternalURL.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.deeplinks

import com.meshcoreone.android.core.model.SnapshotSet
import com.meshcoreone.android.core.model.snapshotSet
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PendingExternalRoute(
    private val route: suspend (String) -> DeepLinkRouteOutcome,
) {
    data class Delivery(val id: String, val uri: String)

    sealed interface SubmitOutcome {
        data object Staged : SubmitOutcome
        data object Duplicate : SubmitOutcome
        data object Superseded : SubmitOutcome
        data class Routed(val outcome: DeepLinkRouteOutcome) : SubmitOutcome
    }

    private val mutex = Mutex()
    private val consumed = LinkedHashSet<String>()
    private var ready = false
    private var pending: Delivery? = null
    private var inFlight: Routing? = null

    suspend fun submit(delivery: Delivery): SubmitOutcome = drain(delivery)
    suspend fun markReady(): SubmitOutcome = drain(becomeReady = true)
    suspend fun retry(): SubmitOutcome = drain()

    suspend fun snapshot(): Delivery? = mutex.withLock { pending }

    /**
     * Bounded deduplication history, including superseded deliveries, not successful routing or consent.
     * Read after drain cleanup, including cancellation, and match the exact saved delivery ID.
     */
    suspend fun consumedDeliveryIds(): SnapshotSet<String> = mutex.withLock { consumed.snapshotSet() }

    private suspend fun drain(delivery: Delivery? = null, becomeReady: Boolean = false): SubmitOutcome = coroutineScope {
        val routing = mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (delivery != null) {
                if (delivery.id in consumed || delivery.id == pending?.id) {
                    return@coroutineScope SubmitOutcome.Duplicate
                }
                pending?.let { rememberConsumed(it.id) }
                inFlight?.let {
                    it.guard.invalidate()
                    it.superseded = true
                    it.execution.cancel(CancellationException("External link delivery superseded"))
                    inFlight = null
                }
                pending = delivery
            }
            if (becomeReady) ready = true
            val next = pending ?: return@coroutineScope SubmitOutcome.Staged
            if (!ready || inFlight != null) return@coroutineScope SubmitOutcome.Staged
            val guard = CommitGuard()
            Routing(next, guard, async(guard, start = CoroutineStart.LAZY) {
                route(next.uri).also { guard.recordCompletion() }
            })
                .also { inFlight = it }
        }
        try {
            val outcome = routing.execution.await()
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                if (routing.superseded) {
                    SubmitOutcome.Superseded
                } else {
                    pending = null
                    rememberConsumed(routing.delivery.id)
                    SubmitOutcome.Routed(outcome)
                }
            }
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (mutex.withLock { routing.superseded }) SubmitOutcome.Superseded else throw cancelled
        } finally {
            // A committed effect cannot become retryable just because its caller was cancelled.
            withContext(NonCancellable) {
                routing.execution.join()
                mutex.withLock {
                    if (inFlight === routing) {
                        if (routing.guard.wasCommitted()) {
                            if (pending?.id == routing.delivery.id) pending = null
                            rememberConsumed(routing.delivery.id)
                        }
                        inFlight = null
                    }
                }
            }
        }
    }

    private fun rememberConsumed(id: String) {
        consumed += id
        while (consumed.size > MAX_CONSUMED_IDS) consumed.remove(consumed.first())
    }

    // The guard covers synchronous commits only, never a suspended lookup or the staging mutex.
    internal class CommitGuard : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<CommitGuard>
        private var current = true
        private var committed = false

        fun invalidate() = synchronized(this) { current = false }
        fun recordCompletion() = synchronized(this) { committed = true }
        fun wasCommitted(): Boolean = synchronized(this) { committed }

        fun commitOutcome(block: () -> DeepLinkRouteOutcome): DeepLinkRouteOutcome = commit {
            block().also { committed = true }
        }

        fun <T> commit(block: () -> T): T = synchronized(this) {
            if (!current) throw CancellationException("External link delivery superseded")
            block()
        }
    }

    private class Routing(
        val delivery: Delivery,
        val guard: CommitGuard,
        val execution: Deferred<DeepLinkRouteOutcome>,
    ) {
        var superseded = false
    }

    private companion object {
        const val MAX_CONSUMED_IDS = 64
    }
}
