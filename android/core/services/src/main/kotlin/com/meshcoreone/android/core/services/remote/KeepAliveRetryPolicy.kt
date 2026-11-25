// PortedFrom: MC1Services/Sources/MC1Services/Services/KeepAliveRetryPolicy.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.protocol.config.MeshCoreException
import kotlinx.coroutines.CancellationException

/**
 * Keep-alive retry decisions:
 * - Transient (timeout, deviceError, notConnected): retried up to [MAX_CONSECUTIVE_FAILURES] times
 * - Terminal (sessionNotFound, contactNotFound, unknown errors): disconnect immediately
 * - Skip (floodRouted): not a failure, continue the loop
 * - Stop (cancellation, cancelled): exit quietly
 *
 * Swift mutates an `inout` failure counter; Kotlin returns the updated count in [Evaluation].
 */
object KeepAliveRetryPolicy {
    enum class Action {
        /** Transient failure, try again next interval. */
        RETRY_NEXT_INTERVAL,
        /** Consecutive transient failures reached the threshold. */
        DISCONNECT,
        /** Terminal local-state error, disconnect immediately. */
        DISCONNECT_NOW,
        /** Flood-routed session, skip this iteration. */
        SKIP,
        /** Task cancelled, exit the loop quietly. */
        STOP;

        val shouldExitLoop: Boolean
            get() = when (this) {
                STOP, DISCONNECT, DISCONNECT_NOW -> true
                RETRY_NEXT_INTERVAL, SKIP -> false
            }
    }

    data class Evaluation(val action: Action, val consecutiveFailures: Int)

    /** The number of consecutive transient failures required before disconnecting. */
    const val MAX_CONSECUTIVE_FAILURES = 2

    fun evaluate(error: Throwable, consecutiveFailures: Int): Evaluation {
        if (error is CancellationException) return Evaluation(Action.STOP, consecutiveFailures)
        val nodeError = error as? RemoteNodeError ?: return Evaluation(Action.DISCONNECT_NOW, consecutiveFailures)
        return when (nodeError) {
            is RemoteNodeError.Cancelled -> Evaluation(Action.STOP, consecutiveFailures)
            is RemoteNodeError.FloodRouted -> Evaluation(Action.SKIP, consecutiveFailures)
            is RemoteNodeError.SessionNotFound, is RemoteNodeError.ContactNotFound ->
                Evaluation(Action.DISCONNECT_NOW, consecutiveFailures)
            else -> {
                val failures = consecutiveFailures + 1
                val action = if (failures >= MAX_CONSECUTIVE_FAILURES) Action.DISCONNECT else Action.RETRY_NEXT_INTERVAL
                Evaluation(action, failures)
            }
        }
    }

    /** Records a successful keep-alive by resetting the failure counter. */
    fun recordSuccess(@Suppress("UNUSED_PARAMETER") consecutiveFailures: Int): Int = 0

    /** Returns a human-readable reason for a keep-alive failure. */
    fun failureReason(error: Throwable): String {
        val inner = (error as? RemoteNodeError.SessionError)?.error
        return when {
            inner is MeshCoreException.Timeout -> "timeout"
            inner is MeshCoreException.DeviceError -> "device error (code: ${inner.code})"
            inner is MeshCoreException.NotConnected -> "transport not connected"
            error is RemoteNodeError.SessionNotFound -> "session not found"
            error is RemoteNodeError.ContactNotFound -> "contact not found"
            else -> error.toString()
        }
    }
}
