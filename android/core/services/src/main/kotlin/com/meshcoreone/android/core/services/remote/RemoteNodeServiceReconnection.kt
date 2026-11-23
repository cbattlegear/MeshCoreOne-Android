// PortedFrom: MC1Services/Sources/MC1Services/Services/RemoteNodeService+Reconnection.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// MARK: - Link Disconnection

/**
 * Called when the companion link is lost. Marks every connected session of [radioId] disconnected,
 * stops their keep-alives, and broadcasts [RemoteNodeEvent.SessionStateChanged] for each. Returns the
 * sessions that were connected, for re-auth on reconnect. (The Android store scopes the connected-session
 * query by radio, so the radio is a parameter where Swift's query was global.)
 */
suspend fun RemoteNodeService.handleBLEDisconnection(radioId: RadioId): Set<EntityKey> {
    val connectedSessions = try {
        dataStore.fetchConnectedRemoteNodeSessions(radioId)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        logger.severe { "Failed to fetch connected sessions for BLE disconnection: $error" }
        return emptySet()
    }
    if (connectedSessions.isEmpty()) return emptySet()

    logger.info { "BLE disconnection: marking ${connectedSessions.size} session(s) disconnected" }
    val sessions = LinkedHashSet<EntityKey>()
    for (remoteSession in connectedSessions) {
        val key = remoteSession.remoteSessionKey
        sessions += key
        stopKeepAlive(key)
        try {
            dataStore.markSessionDisconnected(key)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logger.severe { "Failed to mark session ${key.id} disconnected: $error" }
        }
        broadcast(RemoteNodeEvent.SessionStateChanged(key, isConnected = false))
    }
    return sessions
}

// MARK: - Link Reconnection

/**
 * Called when the companion link is re-established: re-authenticates sessions that were connected
 * before the loss. An empty set (for example after an app restart) re-authenticates nothing; the user
 * reconnects manually.
 */
suspend fun RemoteNodeService.handleBLEReconnection(sessions: Set<EntityKey>) {
    // The guard is claimed atomically up front (Swift checks it, then sets it after awaiting fetches).
    val claimed = synchronized(lock) { (!isReauthenticating).also { if (it) isReauthenticating = true } }
    if (!claimed) {
        logger.info { "Skipping re-auth: already in progress" }
        return
    }
    try {
        if (sessions.isEmpty()) return
        val sessionsToReauth = sessions.mapNotNull { key ->
            val found = try {
                dataStore.fetchRemoteNodeSession(key)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            if (found == null) logger.warning { "Session ${key.id} not found for re-auth, skipping" }
            found
        }
        if (sessionsToReauth.isEmpty()) return
        logger.info { "BLE reconnection: re-authenticating ${sessionsToReauth.size} session(s)" }
        coroutineScope {
            for (remoteSession in sessionsToReauth) launch { reauthenticate(remoteSession) }
        }
    } finally {
        synchronized(lock) { isReauthenticating = false }
    }
}

private suspend fun RemoteNodeService.reauthenticate(remoteSession: RemoteNodeSessionDTO) {
    val key = remoteSession.remoteSessionKey
    val previousPermission = remoteSession.permissionLevel
    try {
        val result = login(key)
        val newPermission = if (remoteSession.isSensor && !result.isAdmin) com.meshcoreone.android.core.model.RoomPermissionLevel.GUEST
            else result.permissionLevel
        if (newPermission.rawValue < previousPermission.rawValue) {
            logger.warning {
                "Re-auth returned degraded permission for session ${remoteSession.id}: " +
                    "$previousPermission -> $newPermission, marking disconnected"
            }
            try {
                dataStore.markSessionDisconnected(key)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Swift `try?`.
            }
            broadcast(RemoteNodeEvent.SessionStateChanged(key, isConnected = false))
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        logger.warning { "Re-auth failed for session ${remoteSession.id}: $error" }
        persistDisconnected(key)
        broadcast(RemoteNodeEvent.SessionStateChanged(key, isConnected = false))
    }
}
