// PortedFrom: MeshCore/Sources/MeshCore/Session/RequestContext.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.session

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RequestContext(val caller: CoroutineContext) {
    private val sendLock = Any()
    private var currentSend: Deferred<Unit>? = null
    private var currentSendIsCleanup = false
    private var dispatchClosed = false
    private var mayHaveWritten = false

    fun beforeSend(cleanup: Boolean = false) = synchronized(sendLock) {
        if (dispatchClosed) throw CancellationException("Request dispatch was cancelled")
        if (!cleanup) caller.ensureActive()
        mayHaveWritten = true
    }

    fun cancelDispatch(): ChannelWriteCancellationDisposition = synchronized(sendLock) {
        if (mayHaveWritten) ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN
        else {
            dispatchClosed = true
            ChannelWriteCancellationDisposition.DEFINITELY_UNSENT
        }
    }
    fun attachSend(send: Deferred<Unit>, cleanup: Boolean) = synchronized(sendLock) {
        currentSend = send
        currentSendIsCleanup = cleanup
        if (!cleanup && !caller.isActive) send.cancel()
    }
    fun detachSend(send: Deferred<Unit>) = synchronized(sendLock) {
        if (currentSend === send) currentSend = null
    }
    fun cancelInProgressSend() = synchronized(sendLock) { if (!currentSendIsCleanup) currentSend?.cancel(); Unit }
}

class RequestResponseSerializer(
    private val scope: CoroutineScope,
    private val onOrphanFailure: (Throwable) -> Unit = {
        java.util.logging.Logger.getLogger("MeshCore.Session").warning("session.orphanedExchangeFailed ${it.javaClass.name}")
    },
) {
    private val mutex = Mutex()

    suspend fun acquire() = mutex.lock()
    fun release() = mutex.unlock()

    suspend fun <T> withSerialization(operation: suspend () -> T): T = withOwnedSerialization {
        beforeSend()
        operation()
    }

    internal suspend fun <T> withOwnedSerialization(
        dispatch: ChannelWriteDispatch? = null,
        operation: suspend RequestContext.() -> T,
    ): T {
        val caller = currentCoroutineContext()
        try {
            caller.ensureActive()
        } catch (cancelled: CancellationException) {
            // No owned task exists yet, so this call cannot dispatch later.
            dispatch?.cancelled(ChannelWriteCancellationDisposition.DEFINITELY_UNSENT)
            throw cancelled
        }
        val context = RequestContext(caller)
        val task = scope.async {
            mutex.withLock {
                try {
                    caller.ensureActive()
                    context.operation()
                } catch (failure: Exception) {
                    dispatch?.cancelled(context.cancelDispatch())
                    throw failure
                } finally {
                    if (!caller.isActive) dispatch?.cancelled(context.cancelDispatch())
                    dispatch?.exchangeFinished()
                }
            }
        }
        task.invokeOnCompletion { cause ->
            if (!caller.isActive && cause != null && cause !is CancellationException) onOrphanFailure(cause)
        }
        try {
            return task.await()
        } catch (cancelled: CancellationException) {
            // A written request still owns its reply; cancellation never hands that reply to the next caller.
            val disposition = context.cancelDispatch()
            if (disposition == ChannelWriteCancellationDisposition.DEFINITELY_UNSENT) task.cancel()
            else context.cancelInProgressSend()
            dispatch?.cancelled(disposition)
            throw cancelled
        }
    }
}
