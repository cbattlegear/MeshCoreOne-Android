// PortedFrom: MC1Services/Sources/MC1Services/Services/RemoteNodeService+Telemetry.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.OwnerInfoResponse
import com.meshcoreone.android.core.protocol.event.StatusResponse
import com.meshcoreone.android.core.protocol.event.TelemetryResponse
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.session.SessionClock
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

// MARK: - Status

/** Request status from a remote node; waits at most [timeout] (default [RemoteOperationTimeoutPolicy.binaryMaximum]). */
suspend fun RemoteNodeService.requestStatus(session: EntityKey, timeout: Duration? = null): StatusResponse {
    val remoteSession = dataStore.fetchRemoteNodeSession(session) ?: throw RemoteNodeError.SessionNotFound()
    if (remoteSession.isSensor) throw RemoteNodeError.InvalidResponse()
    auditLogger.logStatusRequest(remoteSession.auditTarget, remoteSession.publicKey)
    val contactType = if (remoteSession.isRoom) ContactType.ROOM else ContactType.REPEATER
    return mappingBinaryErrors {
        withRemoteTimeout(timeout ?: RemoteOperationTimeoutPolicy.binaryMaximum, "remoteStatus", clock) {
            this.session.requestStatus(remoteSession.publicKey, contactType)
        }
    }
}

// MARK: - Telemetry

/** Request telemetry from a remote node. */
suspend fun RemoteNodeService.requestTelemetry(session: EntityKey, timeout: Duration? = null): TelemetryResponse {
    val remoteSession = dataStore.fetchRemoteNodeSession(session) ?: throw RemoteNodeError.SessionNotFound()
    auditLogger.logTelemetryRequest(remoteSession.auditTarget, remoteSession.publicKey)
    return mappingBinaryErrors {
        withRemoteTimeout(timeout ?: RemoteOperationTimeoutPolicy.binaryMaximum, "remoteTelemetry", clock) {
            this.session.requestTelemetry(remoteSession.publicKey)
        }
    }
}

// MARK: - Owner Info

/** Request owner info from a repeater using the binary protocol. */
suspend fun RemoteNodeService.requestOwnerInfo(session: EntityKey, timeout: Duration? = null): OwnerInfoResponse {
    val remoteSession = dataStore.fetchRemoteNodeSession(session) ?: throw RemoteNodeError.SessionNotFound()
    if (remoteSession.isSensor) throw RemoteNodeError.InvalidResponse()
    return mappingBinaryErrors {
        withRemoteTimeout(timeout ?: RemoteOperationTimeoutPolicy.binaryMaximum, "remoteOwnerInfo", clock) {
            this.session.requestOwnerInfo(remoteSession.publicKey)
        }
    }
}

/** Outer timeout → `.timeout`; mesh timeout → `.timeout`; other mesh errors → `.sessionError`; the rest propagate. */
internal inline fun <T> mappingBinaryErrors(operation: () -> T): T = try {
    operation()
} catch (_: RemoteOperationTimeoutError) {
    throw RemoteNodeError.Timeout()
} catch (error: MeshCoreException) {
    throw if (error is MeshCoreException.Timeout) RemoteNodeError.Timeout() else RemoteNodeError.SessionError(error)
}

/** Swift `TimeoutError`: an async operation exceeded its deadline. */
class RemoteOperationTimeoutError(val operationName: String, val timeout: Duration) :
    Exception("Operation '$operationName' timed out after $timeout")

/**
 * Swift `withTimeout`: races [operation] against [clock]; the first to finish wins, and returning always
 * cancels and awaits the loser, like a Swift task group. Errors from the operation propagate unchanged.
 */
internal suspend fun <T> withRemoteTimeout(
    timeout: Duration,
    operationName: String,
    clock: SessionClock,
    operation: suspend () -> T,
): T {
    val outcome: Result<T> = supervisorScope {
        val work = async {
            try {
                Result.success(operation())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
        val deadline = async { clock.sleepFor(timeout) }
        try {
            select {
                work.onAwait { it }
                deadline.onAwait { Result.failure(RemoteOperationTimeoutError(operationName, timeout)) }
            }
        } finally {
            withContext(NonCancellable) {
                work.cancelAndJoin()
                deadline.cancelAndJoin()
            }
        }
    }
    return outcome.getOrThrow()
}
