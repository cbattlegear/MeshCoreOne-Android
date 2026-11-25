// PortedFrom: MC1Services/Sources/MC1Services/Services/RemoteNodeService+CLI.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import com.meshcoreone.android.core.protocol.session.ProtocolDispatchGuard
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MARK: - CLI Commands

/**
 * Send a CLI command to a remote node and wait for its response (admin only). Replies to structured
 * `get` queries must parse to their expected shape; anything else waiting in the mesh is dropped
 * instead of misattributed.
 *
 * @param timeout hard maximum time to wait for the response.
 */
suspend fun RemoteNodeService.sendCLICommand(
    session: EntityKey,
    command: String,
    timeout: Duration = RemoteOperationTimeoutPolicy.defaultCLITimeout,
): String = performCLICommand(session, command, timeout, acceptsAnyResponse = false)

/**
 * Send a raw CLI command (admin only). The next reply from the node is delivered verbatim without
 * shape validation; used by CLI terminals and commands whose reply format is free-form.
 */
suspend fun RemoteNodeService.sendRawCLICommand(
    session: EntityKey,
    command: String,
    timeout: Duration = RemoteOperationTimeoutPolicy.defaultCLITimeout,
): String {
    val response = performCLICommand(session, command, timeout, acceptsAnyResponse = true)
    // Clear the stored password after an admin password change.
    handlePasswordChangeIfNeeded(command, session)
    return response
}

suspend fun RemoteNodeService.sendSensorRawCLICommand(
    session: EntityKey,
    command: String,
    timeout: Duration = RemoteOperationTimeoutPolicy.defaultCLITimeout,
): String {
    val response = performCLICommand(session, command, timeout, acceptsAnyResponse = true, sensorOnly = true)
    handlePasswordChangeIfNeeded(command, session)
    return response
}

/** A contact's owner independently controls whether their radio executes this command. */
suspend fun RemoteNodeService.sendRadioCLICommand(
    radioId: RadioId,
    publicKey: Bytes,
    command: String,
    timeout: Duration = RemoteOperationTimeoutPolicy.defaultCLITimeout,
): String {
    if (ownerRadioId != radioId) throw RemoteNodeError.NotConnected()
    val callerGuard = currentCoroutineContext()[ProtocolDispatchGuard]
    val ownerGuard = ProtocolDispatchGuard {
        callerGuard?.requireCurrent()
        isRecoveryOwnerCurrent()
    }
    return withContext(ownerGuard) {
        ownerGuard.requireCurrent()
        val prefix = publicKey.prefix(6)
        acquireCLISlot(prefix, UUID.randomUUID())
        try {
            currentCoroutineContext().ensureActive()
            ownerGuard.requireCurrent()
            val contact = dataStore.fetchContact(radioId, publicKey) ?: throw RemoteNodeError.ContactNotFound()
            ownerGuard.requireCurrent()
            if (!contact.showsRemoteCLI(true)) throw RemoteNodeError.PermissionDenied()
            auditLogger.logCLICommand(publicKey, command)
            currentCoroutineContext().ensureActive()
            performCLIExchange(publicKey, prefix, command, acceptsAnyResponse = false, timeout = timeout, radioCLI = true)
        } finally {
            releaseCLISlot(prefix)
        }
    }
}

/** Shared send path: holds the node's CLI slot so exactly one command is in flight per node. */
private suspend fun RemoteNodeService.performCLICommand(
    session: EntityKey, command: String, timeout: Duration, acceptsAnyResponse: Boolean,
    sensorOnly: Boolean = false,
): String {
    val remoteSession = dataStore.fetchRemoteNodeSession(session) ?: throw RemoteNodeError.SessionNotFound()
    if (sensorOnly && !remoteSession.isSensor) throw RemoteNodeError.SessionNotFound()
    if (!remoteSession.isAdmin) throw RemoteNodeError.PermissionDenied()

    val wireCommand = if (remoteSession.isSensor) command.trimStart(' ') else RemoteCLICommandRewriter.rewrite(command, clock.wallClock.instant())
    if (remoteSession.isSensor) validateSensorCommand(wireCommand)
    // Audit log (with password redaction in the logger implementation).
    auditLogger.logCLICommand(remoteSession.publicKey, wireCommand)

    val destinationPrefix = remoteSession.publicKey.prefix(6)
    val requestID = UUID.randomUUID()
    acquireCLISlot(destinationPrefix, requestID)
    try {
        currentCoroutineContext().ensureActive()
        if (remoteSession.isSensor) {
            val current = dataStore.fetchRemoteNodeSession(session) ?: throw RemoteNodeError.SessionNotFound()
            if (!current.isSensor) throw RemoteNodeError.SessionNotFound()
            if (!current.isConnected) throw RemoteNodeError.NotConnected()
            if (!current.isAdmin) throw RemoteNodeError.PermissionDenied()
            if (isSensorNoReplyCLI(wireCommand)) {
                val sender = serviceScope.async(start = CoroutineStart.LAZY) {
                    val authorized = requireSensorAdmin(session)
                    currentCoroutineContext().ensureActive()
                    try {
                        this@performCLICommand.session.sendCommand(
                            authorized.publicKey, makeCLIWirePrefix() + wireCommand, clock.wallClock.instant(),
                        )
                    } catch (error: MeshCoreException) {
                        throw RemoteNodeError.SessionError(error)
                    }
                }
                try {
                    sender.await()
                } catch (cancellation: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw RemoteNodeError.Cancelled()
                } finally {
                    withContext(NonCancellable) { sender.cancelAndJoin() }
                }
                disconnect(session)
                return ""
            }
            return performCLIExchange(current.publicKey, destinationPrefix, wireCommand, true, timeout, session)
        }
        // Reboot has no reply; path-reset+resend would fire a second reboot.
        if (isFireAndForgetCLI(wireCommand)) {
            return performCLIExchange(remoteSession.publicKey, destinationPrefix, wireCommand, acceptsAnyResponse, timeout)
        }
        return performWithDirectPathFloodRecovery(remoteSession.radioId, remoteSession.publicKey, "remoteCLI") {
            performCLIExchange(remoteSession.publicKey, destinationPrefix, wireCommand, acceptsAnyResponse, timeout)
        }
    } finally {
        releaseCLISlot(destinationPrefix)
    }
}

private fun isSensorNoReplyCLI(command: String): Boolean =
    listOf("reboot", "clkreboot", "poweroff", "shutdown").any { command.startsWith(it) }

/** Commands that intentionally get no reply (or treat timeout as success). */
private fun isFireAndForgetCLI(command: String): Boolean {
    val lower = RemoteSwiftText.trimWhitespaces(command.lowercase())
    return lower == "reboot" || lower.startsWith("reboot ")
}

/**
 * Register the pending request, send the command, and poll the device for the reply until it arrives
 * or the effective timeout elapses.
 */
private suspend fun RemoteNodeService.performCLIExchange(
    publicKey: Bytes,
    destinationPrefix: Bytes,
    command: String,
    acceptsAnyResponse: Boolean,
    timeout: Duration,
    sensorSession: EntityKey? = null,
    radioCLI: Boolean = false,
): String {
    // A fresh id per attempt: the path-recovery retry must not be matched (and failed) by the first
    // attempt's late completion handler, which runs after that attempt already timed out.
    val requestID = UUID.randomUUID()
    val wirePrefix = makeCLIWirePrefix()
    val pending = RemoteNodeService.PendingCLIRequest(requestID, command, wirePrefix, acceptsAnyResponse, requiresEcho = radioCLI)
    synchronized(lock) { pendingCLIRequests[destinationPrefix] = pending }

    val dispatchGuard = currentCoroutineContext()[ProtocolDispatchGuard] ?: EmptyCoroutineContext
    val poller = serviceScope.launch(dispatchGuard, start = CoroutineStart.LAZY) {
        val sentInfo = try {
            if (sensorSession != null) {
                requireSensorAdmin(sensorSession)
                currentCoroutineContext().ensureActive()
            }
            if (radioCLI) session.sendRadioCLICommand(publicKey, wirePrefix + command, clock.wallClock.instant())
            else session.sendCommand(publicKey, wirePrefix + command, clock.wallClock.instant())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: RemoteNodeError) {
            takePendingCLIRequest(destinationPrefix, requestID)?.result?.completeExceptionally(error)
            return@launch
        } catch (error: Exception) {
            val meshError = error as? MeshCoreException ?: MeshCoreException.ConnectionLost(error)
            takePendingCLIRequest(destinationPrefix, requestID)
                ?.result?.completeExceptionally(RemoteNodeError.SessionError(meshError))
            return@launch
        }
        pollForCLIReply(sentInfo, destinationPrefix, requestID, timeout)
    }
    // Whatever ends the poller (service shutdown included) must not leave the caller parked.
    poller.invokeOnCompletion {
        takePendingCLIRequest(destinationPrefix, requestID)?.result?.completeExceptionally(RemoteNodeError.Cancelled())
    }
    poller.start()

    try {
        return pending.result.await()
    } catch (cancellation: CancellationException) {
        cancelPendingCLIRequest(destinationPrefix, requestID)
        poller.cancel()
        throw cancellation
    } finally {
        if (sensorSession != null) withContext(NonCancellable) { poller.cancelAndJoin() }
    }
}

/** Polls the device queue until the reply resolves the request or the effective timeout elapses. */
private suspend fun RemoteNodeService.pollForCLIReply(
    sentInfo: MessageSentInfo, destinationPrefix: Bytes, requestID: UUID, timeout: Duration,
) {
    val effectiveTimeout = RemoteOperationTimeoutPolicy.cliTimeout(sentInfo, timeout)
    val deadline = clock.now + effectiveTimeout
    while (clock.now < deadline) {
        // Resumed by handleCLIResponse or cancelled.
        if (synchronized(lock) { pendingCLIRequests[destinationPrefix]?.id != requestID }) return
        val pollDuration = minOf(RemoteOperationTimeoutPolicy.pollInterval, deadline - clock.now)
        try {
            session.getMessage(maxOf(0.1, pollDuration.toDouble(DurationUnit.SECONDS)))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Swift `try?`: a failed poll is retried until the deadline.
        }
    }
    takePendingCLIRequest(destinationPrefix, requestID)?.result?.completeExceptionally(RemoteNodeError.Timeout())
}

// MARK: - CLI Slot

/** Wait for the node's CLI slot (FIFO); cancellation while queued removes the waiter. */
private suspend fun RemoteNodeService.acquireCLISlot(prefix: Bytes, waiterID: UUID) {
    val waiter = synchronized(lock) {
        if (cliSlotBusy.add(prefix)) return
        RemoteNodeService.CLISlotWaiter(waiterID).also { cliSlotWaiters.getOrPut(prefix, ::ArrayDeque).addLast(it) }
    }
    try {
        waiter.granted.await()
    } catch (cancellation: CancellationException) {
        cancelCLISlotWaiter(prefix, waiter)
        throw cancellation
    }
}

/**
 * Hand the slot to the next waiter, or free it when none are queued. The grant completes under the
 * lock so a concurrently cancelled waiter always sees either "still queued" or "granted".
 */
private fun RemoteNodeService.releaseCLISlot(prefix: Bytes) {
    synchronized(lock) {
        val waiters = cliSlotWaiters[prefix]
        val next = waiters?.removeFirstOrNull()
        if (waiters != null && waiters.isEmpty()) cliSlotWaiters.remove(prefix)
        if (next == null) cliSlotBusy.remove(prefix) else next.granted.complete(Unit)
    }
}

/**
 * A cancelled waiter leaves the queue. If the slot was already handed to it (the grant raced the
 * cancellation), the slot passes on so it can never leak.
 */
private fun RemoteNodeService.cancelCLISlotWaiter(prefix: Bytes, waiter: RemoteNodeService.CLISlotWaiter) {
    val (wasQueued, wasGranted) = synchronized(lock) {
        val waiters = cliSlotWaiters[prefix]
        val removed = waiters?.remove(waiter) == true
        if (waiters != null && waiters.isEmpty()) cliSlotWaiters.remove(prefix)
        val granted = !removed && waiter.granted.isCompleted && waiter.granted.getCompletionExceptionOrNull() == null
        removed to granted
    }
    if (wasQueued) waiter.granted.completeExceptionally(RemoteNodeError.Cancelled())
    if (wasGranted) releaseCLISlot(prefix)
}

/** Clear the stored password if the command is an admin (not guest) password change. */
private suspend fun RemoteNodeService.handlePasswordChangeIfNeeded(command: String, session: EntityKey) {
    val lower = RemoteSwiftText.trimWhitespaces(command.lowercase())
    if (!lower.startsWith("password ") || lower.contains("guest.password")) return
    val remoteSession = try {
        dataStore.fetchRemoteNodeSession(session)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    } ?: return
    try {
        passwordStore.deletePassword(remoteSession.publicKey)
        logger.info { "Cleared stored password after password change for session ${session.id}" }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        // The next login fails naturally; the user re-enters the password, overwriting the stale one.
        logger.warning { "Failed to clear stored password for session ${session.id}: $error" }
    }
}
