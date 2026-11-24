// PortedFrom: MC1Services/Sources/MC1Services/Services/RemoteNodeService+Login.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomPermissionLevel
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.ErrorCode
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.session.SessionClock
import java.lang.ref.WeakReference
import java.time.Instant
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// MARK: - Login

/**
 * Login to a remote node (room server or repeater).
 *
 * @param session the remote session key.
 * @param password explicit password; the stored password when null.
 * @param pathLength encoded path-length hint for the timeout.
 * @param onTimeoutKnown invoked with the timeout in whole seconds once the firmware accepts the send.
 */
suspend fun RemoteNodeService.login(
    session: EntityKey,
    password: String? = null,
    pathLength: UByte = 0u,
    onTimeoutKnown: (suspend (Long) -> Unit)? = null,
): LoginResult {
    val remoteSession = dataStore.fetchRemoteNodeSession(session) ?: throw RemoteNodeError.SessionNotFound()
    val pwd = password ?: passwordStore.retrievePassword(remoteSession.publicKey) ?: throw RemoteNodeError.PasswordNotFound()
    val prefix = remoteSession.publicKey.prefix(6)

    // Cancel any existing pending login for this prefix.
    if (synchronized(lock) { pendingLogins.containsKey(prefix) }) {
        logger.warning { "Overwriting pending login for prefix ${prefix.hexString}" }
        cancelPendingLogin(prefix)
    }

    auditLogger.logLoginRequest(remoteSession.auditTarget, remoteSession.publicKey, pathLength)

    // Register the pending login BEFORE sending so a fast loginSuccess cannot race past it.
    logger.info { "login: registering pending login for prefix ${prefix.hexString}" }
    val pending = RemoteNodeService.PendingLogin(session)
    val displaced = synchronized(lock) {
        val previous = pendingLogins.put(prefix, pending)
        previous?.let { it to pendingLoginTimeoutTasks.remove(prefix) }
    }
    displaced?.let { (previous, task) ->
        task?.cancel()
        previous.result.completeExceptionally(RemoteNodeError.Cancelled())
    }

    val task = serviceScope.launch(start = CoroutineStart.LAZY) {
        runLoginExchange(prefix, pending, remoteSession, pwd, pathLength, onTimeoutKnown)
    }
    // Whatever ends the task (service shutdown included) must not leave the caller parked.
    task.invokeOnCompletion { failPendingLogin(prefix, pending, RemoteNodeError.Cancelled()) }
    val owned = synchronized(lock) {
        (pendingLogins[prefix] === pending).also { if (it) pendingLoginTimeoutTasks[prefix] = task }
    }
    if (owned) task.start() else task.cancel()

    try {
        return pending.result.await()
    } catch (cancellation: CancellationException) {
        // Swift onCancel: release this caller's registration; a newer login for the prefix survives.
        cancelPendingLogin(prefix, owner = pending)
        throw cancellation
    }
}

/** Send, retransmit until loginSuccess or the deadline, then time out. Runs as the login's task. */
private suspend fun RemoteNodeService.runLoginExchange(
    prefix: Bytes,
    pending: RemoteNodeService.PendingLogin,
    remoteSession: RemoteNodeSessionDTO,
    password: String,
    pathLength: UByte,
    onTimeoutKnown: (suspend (Long) -> Unit)?,
) {
    val sentInfo = try {
        sendLoginHealingIfNeeded(remoteSession.publicKey, remoteSession.radioId, password)
    } catch (cancellation: CancellationException) {
        // Cancellation means this task no longer owns the result: whoever cancelled it already failed it.
        throw cancellation
    } catch (error: Exception) {
        if (!currentCoroutineContext().isActive) return
        val mapped = error as? RemoteNodeError
            ?: RemoteNodeError.SessionError(error as? MeshCoreException ?: MeshCoreException.ConnectionLost(error))
        failPendingLogin(prefix, pending, mapped)
        return
    }

    val timeout = RemoteOperationTimeoutPolicy.loginTimeout(sentInfo, pathLength)
    logger.info { "login: send succeeded, starting $timeout timeout for prefix ${prefix.hexString}" }
    // Swift's callback cannot throw; a throwing Kotlin callback must not abort the login it reports on.
    try {
        onTimeoutKnown?.invoke(maxOf(1L, ceil(timeout.toDouble(DurationUnit.SECONDS)).toLong()))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        logger.warning { "login: onTimeoutKnown callback failed: $error" }
    }

    // Retransmit while waiting for loginSuccess, spaced at least one firmware-suggested RTT so
    // multi-hop paths are not flooded mid-flight.
    val deadline = clock.now + timeout
    val retransmitInterval = maxOf(
        RemoteOperationTimeoutPolicy.loginRetransmitInterval, sentInfo.suggestedTimeoutMs.toLong().milliseconds,
    )
    var retransmitCount = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val remaining = deadline - clock.now
        if (remaining <= Duration.ZERO) break
        clock.sleepFor(minOf(remaining, retransmitInterval))
        if (synchronized(lock) { pendingLogins[prefix] !== pending }) {
            logger.info { "login: continuation consumed for prefix ${prefix.hexString}; stopping retransmits" }
            return
        }
        if (clock.now >= deadline) break
        retransmitCount += 1
        try {
            logger.info { "login: retransmit #$retransmitCount for prefix ${prefix.hexString}" }
            session.sendLogin(remoteSession.publicKey, password)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logger.warning { "login: retransmit #$retransmitCount failed for ${prefix.hexString}: ${error.message}" }
        }
    }

    currentCoroutineContext().ensureActive()
    if (failPendingLogin(prefix, pending, RemoteNodeError.Timeout())) {
        logger.warning { "Login timeout after $timeout for session ${remoteSession.id}, prefix ${prefix.hexString}" }
    } else {
        logger.info { "login: timeout elapsed but continuation already consumed for prefix ${prefix.hexString}" }
    }
}

/** Fails [pending] with [error] when it is still the registration for [prefix]; true when it was. */
private fun RemoteNodeService.failPendingLogin(
    prefix: Bytes, pending: RemoteNodeService.PendingLogin, error: RemoteNodeError,
): Boolean {
    val owned = synchronized(lock) {
        (pendingLogins[prefix] === pending).also {
            if (it) {
                pendingLogins.remove(prefix)
                pendingLoginTimeoutTasks.remove(prefix)
            }
        }
    }
    if (owned) pending.result.completeExceptionally(error)
    return owned
}

/**
 * Sends the login; when the radio reports the contact missing from its table, pushes the local copy
 * (flood-routed) and retries once. A contact can be in the app database but absent on the radio after
 * a backup restore or a radio swap, which the firmware answers with notFound (0x02). The parked login
 * is covered here by the session commands' own timeouts; the login timeout starts after this returns.
 */
internal suspend fun RemoteNodeService.sendLoginHealingIfNeeded(
    publicKey: Bytes, radioId: RadioId, password: String,
): MessageSentInfo {
    try {
        return session.sendLogin(publicKey, password)
    } catch (error: MeshCoreException) {
        if (error !is MeshCoreException.DeviceError || error.code != ErrorCode.NOT_FOUND.rawValue) throw error
        currentCoroutineContext().ensureActive()
        addLocalContactToRadio(publicKey, radioId)
        // Bounded to a single retry: a second notFound after a successful add is a firmware
        // inconsistency, left to propagate rather than loop.
        return session.sendLogin(publicKey, password)
    }
}

/** Pushes the local contact to the radio flood-routed, then reconciles the local row to match. */
private suspend fun RemoteNodeService.addLocalContactToRadio(publicKey: Bytes, radioId: RadioId) {
    val contact = dataStore.fetchContact(radioId, publicKey) ?: throw RemoteNodeError.ContactNotFound()
    val frame = contact.floodedContactFrame(RemoteCLICommandRewriter.epochSeconds32(clock.wallClock.instant()))
    try {
        session.addContact(frame.toRemoteMeshContact())
    } catch (error: MeshCoreException) {
        if (error !is MeshCoreException.DeviceError || error.code != ErrorCode.TABLE_FULL.rawValue) throw error
        throw RemoteNodeError.RadioContactsFull()
    }
    // The radio is healed; failing to sync the local row is bookkeeping that must not abort the login
    // retry. Keep-alive routing self-corrects on the next contact refresh.
    try {
        dataStore.saveContact(radioId, frame)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        logger.warning { "Re-added contact to radio but failed to sync local row: $error" }
    }
    logger.info { "Re-added missing contact to radio during login" }
}

/** Swift `ContactFrame.toMeshContact()` (ContactService.swift); the raw type byte travels verbatim. */
internal fun ContactFrame.toRemoteMeshContact(): MeshContact = MeshContact(
    id = publicKey.uppercaseHexString(),
    publicKey = publicKey,
    type = type,
    flags = ContactFlags(flags),
    outPathLength = outPathLength,
    outPath = outPath,
    advertisedName = name,
    lastAdvertisement = Instant.ofEpochSecond(lastAdvertTimestamp.toLong()),
    latitude = latitude,
    longitude = longitude,
    lastModified = Instant.ofEpochSecond(lastModified.toLong()),
    typeRawValue = typeRawValue,
)

/** Handle a login result pushed by the device. */
internal suspend fun RemoteNodeService.handleLoginResult(result: LoginResult, fromPublicKeyPrefix: Bytes) {
    if (fromPublicKeyPrefix.size < 6) {
        logger.warning { "Login result has invalid prefix length: ${fromPublicKeyPrefix.size}" }
        return
    }
    val prefix = fromPublicKeyPrefix.prefix(6)
    val (pending, task) = synchronized(lock) {
        logger.info { "handleLoginResult: looking for prefix ${prefix.hexString}, pending keys: ${pendingLogins.keys.map { it.hexString }}" }
        val found = pendingLogins.remove(prefix) ?: run {
            logger.warning { "Login result with no pending request. Prefix: ${prefix.hexString}" }
            return
        }
        found to pendingLoginTimeoutTasks.remove(prefix)
    }
    task?.cancel()
    logger.info { "handleLoginResult: found continuation for prefix ${prefix.hexString}" }
    if (result.success) {
        try {
            recordLoginSuccess(pending.session, prefix, result)
        } catch (cancellation: CancellationException) {
            pending.result.complete(result)
            throw cancellation
        } catch (error: Exception) {
            logger.severe { "handleLoginResult: failed to update session state: $error" }
        }
        pending.result.complete(result)
    } else {
        val target = try {
            dataStore.fetchRemoteNodeSession(pending.session)?.auditTarget
        } catch (cancellation: CancellationException) {
            pending.result.completeExceptionally(RemoteNodeError.LoginFailed("authentication failed"))
            throw cancellation
        } catch (_: Exception) {
            null
        }
        try {
            auditLogger.logLoginFailed(target ?: RemoteAuditTarget.REPEATER, prefix, "authentication failed")
        } finally {
            pending.result.completeExceptionally(RemoteNodeError.LoginFailed("authentication failed"))
        }
    }
}

/** Persist the connected state, verify it, notify, and arm the default keep-alive interval. */
private suspend fun RemoteNodeService.recordLoginSuccess(session: EntityKey, prefix: Bytes, result: LoginResult) {
    val remoteSession = dataStore.fetchRemoteNodeSession(session) ?: run {
        logger.severe { "handleLoginResult: no session found for ${session.id} - database may be corrupted" }
        return
    }
    auditLogger.logLoginSuccess(remoteSession.auditTarget, prefix, result.isAdmin)
    val permission = if (remoteSession.isSensor && !result.isAdmin) RoomPermissionLevel.GUEST else result.permissionLevel
    logger.info { "handleLoginResult: updating session ${remoteSession.id} isConnected=true, permission=${permission.rawValue}" }
    dataStore.updateRemoteNodeSessionConnection(remoteSession.remoteSessionKey, true, permission)
    dataStore.fetchRemoteNodeSession(remoteSession.remoteSessionKey)?.let { verified ->
        if (verified.isConnected) logger.info { "handleLoginResult: verified session ${remoteSession.id} isConnected=true" }
        else logger.severe { "handleLoginResult: session ${remoteSession.id} still shows isConnected=false after update!" }
    }
    broadcast(RemoteNodeEvent.SessionStateChanged(remoteSession.remoteSessionKey, isConnected = true))
    synchronized(lock) { keepAliveIntervals[remoteSession.remoteSessionKey] = RemoteNodeService.DEFAULT_KEEP_ALIVE_INTERVAL }
}

// MARK: - Keep-Alive (Room Servers)

/** One keep-alive attempt's outcome for the loop. */
internal data class KeepAliveTick(val shouldContinue: Boolean, val consecutiveFailures: Int)

/**
 * Start periodic keep-alive: an immediate keep-alive (connectivity check + sync_since update), then one
 * per interval. Transient failures are retried up to [KeepAliveRetryPolicy.MAX_CONSECUTIVE_FAILURES]
 * times before disconnecting.
 */
private fun RemoteNodeService.startKeepAlive(session: EntityKey, publicKey: Bytes) {
    val interval = synchronized(lock) { keepAliveIntervals[session] } ?: RemoteNodeService.DEFAULT_KEEP_ALIVE_INTERVAL
    // The service retains this task via keepAliveTasks, so the loop holds the service weakly and
    // rebinds per tick; a strong capture would keep the service (and session/store) alive forever.
    val reference = WeakReference(this)
    val sleepClock = clock
    val task = serviceScope.launch(start = CoroutineStart.LAZY) {
        runKeepAliveLoop(reference, session, publicKey, interval, sleepClock)
    }
    val previous = synchronized(lock) { keepAliveTasks.put(session, task) }
    previous?.cancel()
    task.start()
}

private suspend fun runKeepAliveLoop(
    reference: WeakReference<RemoteNodeService>,
    session: EntityKey,
    publicKey: Bytes,
    interval: Duration,
    clock: SessionClock,
) {
    var consecutiveFailures = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val tick = reference.get()?.performKeepAliveTick(session, publicKey, consecutiveFailures) ?: return
        if (!tick.shouldContinue) return
        consecutiveFailures = tick.consecutiveFailures
        clock.sleepFor(interval)
    }
}

/** Runs one keep-alive attempt; returns whether the loop continues and the updated failure count. */
internal suspend fun RemoteNodeService.performKeepAliveTick(
    session: EntityKey, publicKey: Bytes, consecutiveFailures: Int,
): KeepAliveTick {
    try {
        sendKeepAliveIfDirectRouted(session, publicKey)
        return KeepAliveTick(true, KeepAliveRetryPolicy.recordSuccess(consecutiveFailures))
    } catch (error: Exception) {
        // Cancellation maps to STOP; it is rethrown so it is never swallowed.
        if (error is CancellationException) throw error
        val evaluation = KeepAliveRetryPolicy.evaluate(error, consecutiveFailures)
        when (evaluation.action) {
            KeepAliveRetryPolicy.Action.STOP -> Unit
            KeepAliveRetryPolicy.Action.SKIP -> logger.info { "Skipping keep-alive for flood-routed session ${session.id}" }
            KeepAliveRetryPolicy.Action.RETRY_NEXT_INTERVAL -> logger.warning {
                "Keep-alive ${evaluation.consecutiveFailures}/${KeepAliveRetryPolicy.MAX_CONSECUTIVE_FAILURES} failed for " +
                    "${session.id}: ${KeepAliveRetryPolicy.failureReason(error)}"
            }
            KeepAliveRetryPolicy.Action.DISCONNECT, KeepAliveRetryPolicy.Action.DISCONNECT_NOW -> {
                logger.warning { "Keep-alive failed for ${session.id}: ${KeepAliveRetryPolicy.failureReason(error)}" }
                persistDisconnected(session)
                broadcast(RemoteNodeEvent.SessionStateChanged(session, isConnected = false))
            }
        }
        return KeepAliveTick(!evaluation.action.shouldExitLoop, evaluation.consecutiveFailures)
    }
}

/** Send keep-alive only if the session has a direct routing path. */
private suspend fun RemoteNodeService.sendKeepAliveIfDirectRouted(session: EntityKey, publicKey: Bytes) {
    val remoteSession = dataStore.fetchRemoteNodeSession(session) ?: throw RemoteNodeError.SessionNotFound()
    val contact = dataStore.fetchContact(remoteSession.radioId, publicKey) ?: throw RemoteNodeError.ContactNotFound()
    // Keep-alive only works with direct routing.
    if (contact.isFloodRouted) throw RemoteNodeError.FloodRouted()
    auditLogger.logKeepAlive(remoteSession.auditTarget, publicKey)
    // sync_since doubles as a force-resync hint.
    try {
        this.session.sendKeepAlive(publicKey, remoteSession.lastSyncTimestamp)
    } catch (error: MeshCoreException) {
        throw RemoteNodeError.SessionError(error)
    }
}

/** Send a keep-alive now (manual refresh). */
suspend fun RemoteNodeService.sendKeepAlive(session: EntityKey) {
    val remoteSession = dataStore.fetchRemoteNodeSession(session) ?: throw RemoteNodeError.SessionNotFound()
    if (remoteSession.isSensor) throw RemoteNodeError.InvalidResponse()
    sendKeepAliveIfDirectRouted(session, remoteSession.publicKey)
}

/** Start keep-alive for a room session (room view appears). */
fun RemoteNodeService.startSessionKeepAlive(session: EntityKey, publicKey: Bytes) = startKeepAlive(session, publicKey)

/** Stop keep-alive for a room session (room view disappears). */
fun RemoteNodeService.stopSessionKeepAlive(session: EntityKey) = stopKeepAlive(session)

// MARK: - History Sync

/** Request message history from a room server (a status request triggers the sync). */
suspend fun RemoteNodeService.requestHistorySync(session: EntityKey) {
    val remoteSession = dataStore.fetchRemoteNodeSession(session) ?: throw RemoteNodeError.SessionNotFound()
    if (!remoteSession.isRoom) throw RemoteNodeError.InvalidResponse()
    val contact = dataStore.fetchContact(remoteSession.radioId, remoteSession.publicKey)
        ?: throw RemoteNodeError.ContactNotFound()
    if (contact.isFloodRouted) throw RemoteNodeError.FloodRouted()
    try {
        this.session.requestStatus(remoteSession.publicKey, if (remoteSession.isRoom) ContactType.ROOM else ContactType.REPEATER)
    } catch (error: MeshCoreException) {
        throw RemoteNodeError.SessionError(error)
    }
    logger.info { "Requested history sync for room ${remoteSession.name}" }
}

// MARK: - Logout

/** Explicitly log out from a remote node. */
suspend fun RemoteNodeService.logout(session: EntityKey) {
    val remoteSession = dataStore.fetchRemoteNodeSession(session) ?: throw RemoteNodeError.SessionNotFound()
    auditLogger.logLogout(remoteSession.auditTarget, remoteSession.publicKey)
    stopKeepAlive(session)
    try {
        this.session.sendLogout(remoteSession.publicKey)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        // Ignored: we're disconnecting anyway.
        logger.info { "Logout send failed (ignoring): $error" }
    }
    dataStore.updateRemoteNodeSessionConnection(session, false, RoomPermissionLevel.GUEST)
    broadcast(RemoteNodeEvent.SessionStateChanged(session, isConnected = false))
}
