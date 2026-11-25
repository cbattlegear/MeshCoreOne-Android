// PortedFrom: MC1Services/Sources/MC1Services/Services/RoomAdminService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ContactMessage
import com.meshcoreone.android.core.protocol.event.StatusResponse
import com.meshcoreone.android.core.protocol.event.TelemetryResponse
import java.util.logging.Logger
import kotlin.time.Duration

/**
 * Service for room server admin interactions: viewing status/telemetry and sending CLI commands to room
 * servers. Room authentication is handled by [RoomServerService.joinRoom].
 *
 * Swift actor isolation becomes confinement: the only mutable state is the three handler slots, each a
 * `@Volatile` reference read once per invocation.
 */
class RoomAdminService(
    private val remoteNodeService: RemoteNodeService,
    private val dataStore: RemoteAdminStore,
    private val auditLogger: RemoteCommandAuditLog = remoteNodeService.auditLogger,
) {
    private val logger: Logger = Logger.getLogger("com.mc1.RoomAdmin")

    @Volatile private var telemetryResponseHandler: (suspend (TelemetryResponse) -> Unit)? = null
    @Volatile private var statusResponseHandler: (suspend (StatusResponse) -> Unit)? = null
    @Volatile private var cliResponseHandler: (suspend (ContactMessage, ContactDTO) -> Unit)? = null

    // MARK: - Status / Telemetry

    /** Request status from a room server. */
    suspend fun requestStatus(session: EntityKey, timeout: Duration? = null): StatusResponse =
        remoteNodeService.requestStatus(session, timeout)

    /** Request telemetry from a room server. */
    suspend fun requestTelemetry(session: EntityKey, timeout: Duration? = null): TelemetryResponse =
        remoteNodeService.requestTelemetry(session, timeout)

    // MARK: - CLI Commands

    /**
     * Send a CLI command to a room server and wait for the response (admin only). One command is in flight
     * per node; replies to structured gets must parse to their expected shape or they are dropped.
     */
    suspend fun sendCommand(
        session: EntityKey,
        command: String,
        timeout: Duration = RemoteOperationTimeoutPolicy.defaultCLITimeout,
    ): String = remoteNodeService.sendCLICommand(session, command, timeout)

    /** Send a raw CLI command; the next reply is delivered verbatim (admin only). */
    suspend fun sendRawCommand(
        session: EntityKey,
        command: String,
        timeout: Duration = RemoteOperationTimeoutPolicy.defaultCLITimeout,
    ): String = remoteNodeService.sendRawCLICommand(session, command, timeout)

    // MARK: - Session Queries

    /** Fetch all room admin sessions for a device. */
    suspend fun fetchRoomAdminSessions(radioId: RadioId): List<RemoteNodeSessionDTO> =
        dataStore.fetchRemoteNodeSessions(radioId).filter { it.isRoom }

    /**
     * Check if a contact is a known room with an active session. Swift's store lookup is radio-agnostic;
     * the Android store partitions sessions by radio, so the caller names the radio.
     */
    suspend fun getConnectedSession(radioId: RadioId, publicKeyPrefix: Bytes): RemoteNodeSessionDTO? {
        val remoteSession = dataStore.fetchRemoteNodeSessionByPrefix(radioId, publicKeyPrefix) ?: return null
        return remoteSession.takeIf { it.isRoom && it.isConnected }
    }

    // MARK: - Handler Invocation

    /** Audit-log a room status response, then hand it to the status handler. */
    suspend fun invokeStatusHandler(status: StatusResponse) {
        auditLogger.logStatusResponse(RemoteAuditTarget.ROOM, status.publicKeyPrefix, status.batteryMillivolts, status.uptimeSeconds)
        val handler = statusResponseHandler ?: run {
            logger.fine { "No status handler registered for room response from ${status.publicKeyPrefix.hexString}, ignoring" }
            return
        }
        handler(status)
    }

    /** Audit-log a room telemetry response, then hand it to the telemetry handler. */
    suspend fun invokeTelemetryHandler(response: TelemetryResponse) {
        auditLogger.logTelemetryResponse(RemoteAuditTarget.ROOM, response.publicKeyPrefix, response.dataPoints.size)
        val handler = telemetryResponseHandler ?: run {
            logger.fine { "No telemetry handler registered for room, ignoring response" }
            return
        }
        handler(response)
    }

    /** Audit-log a room CLI response (full content), then hand it to the CLI handler. */
    suspend fun invokeCLIHandler(message: ContactMessage, contact: ContactDTO) {
        auditLogger.logCLIResponse(contact.publicKey, message.text)
        val handler = cliResponseHandler ?: run {
            logger.fine { "No CLI handler registered for room, ignoring response from ${contact.displayName}" }
            return
        }
        handler(message, contact)
    }

    // MARK: - Handler Setters

    fun setStatusHandler(handler: suspend (StatusResponse) -> Unit) {
        statusResponseHandler = handler
    }

    fun setTelemetryHandler(handler: suspend (TelemetryResponse) -> Unit) {
        telemetryResponseHandler = handler
    }

    fun setCLIHandler(handler: suspend (ContactMessage, ContactDTO) -> Unit) {
        cliResponseHandler = handler
    }

    /** Clear all handlers (called when the view disappears). */
    fun clearHandlers() {
        clearStatusHandlers()
        cliResponseHandler = null
    }

    /**
     * Clears only the status-surface handlers so the merged admin surface can tear down its status segment
     * without dropping the settings screen's CLI handler on the shared per-connection service.
     */
    fun clearStatusHandlers() {
        statusResponseHandler = null
        telemetryResponseHandler = null
    }
}
