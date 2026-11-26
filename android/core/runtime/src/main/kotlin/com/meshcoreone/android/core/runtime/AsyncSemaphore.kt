// PortedFrom: MC1/Utilities/AsyncSemaphore.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.runtime

import java.util.ArrayDeque
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

class AsyncSemaphore(value: Long) {
    init { require(value >= 0) { "Semaphore permits must not be negative" } }
    private val lock = Any()
    private var permits = value
    private val waiters = ArrayDeque<CancellableContinuation<Unit>>()
    val waitingCount: Int get() = synchronized(lock) { waiters.size }
    val availablePermits: Long get() = synchronized(lock) { permits }

    suspend fun wait() = suspendCancellableCoroutine { continuation ->
        synchronized(lock) {
            if (permits > 0) {
                permits--
                continuation.resume(Unit, onCancellation = { _, _, _ -> signal() })
            } else {
                waiters.addLast(continuation)
                continuation.invokeOnCancellation { synchronized(lock) { waiters.remove(continuation) } }
            }
        }
    }

    fun signal() {
        synchronized(lock) {
            while (waiters.isNotEmpty()) {
                val waiter = waiters.removeFirst()
                if (waiter.isActive) {
                    waiter.resume(Unit, onCancellation = { _, _, _ -> signal() })
                    return
                }
            }
            permits = Math.incrementExact(permits)
        }
    }

    suspend fun <T> withPermit(operation: suspend () -> T): T {
        wait()
        try { return operation() } finally { signal() }
    }
}
