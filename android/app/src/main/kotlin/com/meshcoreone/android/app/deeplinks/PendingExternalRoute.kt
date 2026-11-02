// PortedFrom: MC1/State/PendingExternalURL.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.deeplinks

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

    suspend fun submit(delivery: Delivery): SubmitOutcome {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (delivery.id in consumed || delivery.id == pending?.id) return SubmitOutcome.Duplicate
            pending?.let { rememberConsumed(it.id) }
            inFlight?.let {
                it.guard.invalidate()
                it.superseded = true
                it.execution.cancel(CancellationException("External link delivery superseded"))
                inFlight = null
            }
            pending = delivery
            if (!ready) return SubmitOutcome.Staged
        }
        return drain()
    }

    suspend fun markReady(): SubmitOutcome {
        mutex.withLock { ready = true }
        return drain()
    }

    suspend fun retry(): SubmitOutcome = drain()

    suspend fun snapshot(): Delivery? = mutex.withLock { pending }

    private suspend fun drain(): SubmitOutcome = coroutineScope {
        val routing = mutex.withLock {
            val next = pending ?: return@coroutineScope SubmitOutcome.Staged
            if (!ready || inFlight != null) return@coroutineScope SubmitOutcome.Staged
            val guard = CommitGuard()
            Routing(next, guard, async(guard, start = CoroutineStart.LAZY) { route(next.uri) })
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
            // Caller cancellation must release the slot without dropping its retryable delivery.
            withContext(NonCancellable) {
                mutex.withLock { if (inFlight === routing) inFlight = null }
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

        fun invalidate() = synchronized(this) { current = false }

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
