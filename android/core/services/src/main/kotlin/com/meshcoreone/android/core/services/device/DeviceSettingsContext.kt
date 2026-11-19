// AndroidOnly: WP-211 Connection-token guard and owned, caller-cancellable service operations without a runtime dependency.
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.ConnectionSignals
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class DeviceSettingsContext(
    val token: SessionToken,
    private val signals: ConnectionSignals,
    parentScope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
) {
    private val parent = requireNotNull(parentScope.coroutineContext[Job]) { "Connection scope must own a Job" }

    init {
        parent.ensureActive()
        requireSnapshot()
    }

    private val job = SupervisorJob(parent)
    private val scope = CoroutineScope(parentScope.coroutineContext + job + dispatcher)
    private val closed = AtomicBoolean(false)
    private val closeLock = Any()
    private val closeHandlers = mutableListOf<() -> Unit>()
    private val completion = job.invokeOnCompletion { finishHandlers() }

    val isCurrent: Boolean
        get() {
            val snapshot = signals.snapshot.value
            return !closed.get() && job.isActive && snapshot.token == token && snapshot.state.isConnected
        }

    fun requireCurrent() {
        // A closed or cancelled connection context is a typed NotConnected for callers, never a bare
        // CancellationException: that would silently end a still-active UI coroutine with no retryable error.
        if (closed.get() || !job.isActive) throw SettingsServiceException(SettingsServiceError.NotConnected)
        requireSnapshot()
    }

    private fun requireSnapshot() {
        val snapshot = signals.snapshot.value
        if (snapshot.token != token || !snapshot.state.isConnected) {
            throw SettingsServiceException(SettingsServiceError.NotConnected)
        }
    }

    internal fun onClose(handler: () -> Unit) {
        val alreadyClosed = synchronized(closeLock) {
            if (closed.get() || job.isCompleted) true else {
                closeHandlers += handler
                false
            }
        }
        if (alreadyClosed) handler()
    }

    internal suspend fun <T> operation(action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        requireCurrent()
        val task = scope.async {
            requireCurrent()
            val result = action()
            currentCoroutineContext().ensureActive()
            requireCurrent()
            result
        }
        try {
            return task.await()
        } catch (cancelled: CancellationException) {
            // Our own caller's cancellation still propagates; a context closed under a live caller is NotConnected.
            currentCoroutineContext().ensureActive()
            throw SettingsServiceException(SettingsServiceError.NotConnected)
        } finally {
            task.cancel()
        }
    }

    internal fun cancel() {
        closed.set(true)
        finishHandlers()
        job.cancel()
    }

    suspend fun close() {
        cancel()
        job.cancelAndJoin()
        completion.dispose()
    }

    private fun finishHandlers() {
        closed.set(true)
        val handlers = synchronized(closeLock) {
            closeHandlers.toList().also { closeHandlers.clear() }
        }
        handlers.forEach { it() }
    }
}
