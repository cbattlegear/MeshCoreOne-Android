// PortedFrom: MC1Services/Sources/MC1Services/Services/RepeaterAdminService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ContactMessage
import com.meshcoreone.android.core.protocol.event.NeighboursResponse
import com.meshcoreone.android.core.protocol.event.OwnerInfoResponse
import com.meshcoreone.android.core.protocol.event.StatusResponse
import com.meshcoreone.android.core.protocol.event.TelemetryResponse
import com.meshcoreone.android.core.protocol.session.RemoteAccessSessionOps
import com.meshcoreone.android.core.protocol.session.SessionClock
import java.util.logging.Logger
import kotlin.time.Duration

// MARK: - Neighbor Sort Order

/** Sort order options for neighbor queries. */
enum class NeighborSortOrder(val rawValue: UByte) {
    NEWEST_FIRST(0u),
    OLDEST_FIRST(1u),
    STRONGEST_FIRST(2u),
    WEAKEST_FIRST(3u),
    ;

    companion object {
        fun fromRawValue(rawValue: UByte): NeighborSortOrder? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

// MARK: - Repeater Admin Service

/**
 * Service for repeater admin interactions: connecting as admin, viewing status/telemetry/neighbors, and
 * sending CLI commands.
 *
 * Swift actor isolation becomes confinement: the only mutable state is the four handler slots, each a
 * `@Volatile` reference read once per invocation (Swift reads the actor property the same way before
 * awaiting the handler). Waits run on the injected [clock].
 */
class RepeaterAdminService(
    private val session: RemoteAccessSessionOps,
    private val remoteNodeService: RemoteNodeService,
    private val dataStore: RemoteAdminStore,
    private val clock: SessionClock = remoteNodeService.clock,
    private val auditLogger: RemoteCommandAuditLog = remoteNodeService.auditLogger,
) {
    private val logger: Logger = Logger.getLogger("com.mc1.RepeaterAdmin")

    /** Handler for neighbor responses. */
    @Volatile
    var neighboursResponseHandler: (suspend (NeighboursResponse) -> Unit)? = null
        private set

    /** Handler for telemetry responses. */
    @Volatile
    var telemetryResponseHandler: (suspend (TelemetryResponse) -> Unit)? = null
        private set

    /** Handler for status responses. */
    @Volatile
    var statusResponseHandler: (suspend (StatusResponse) -> Unit)? = null
        private set

    /** Handler for CLI text responses. */
    @Volatile
    var cliResponseHandler: (suspend (ContactMessage, ContactDTO) -> Unit)? = null
        private set

    // MARK: - Admin Connection

    /**
     * Connect to a repeater as admin by creating a session and authenticating.
     *
     * @param onTimeoutKnown invoked with the timeout in seconds once the firmware responds.
     */
    suspend fun connectAsAdmin(
        radioId: RadioId,
        contact: ContactDTO,
        password: String?,
        rememberPassword: Boolean = true,
        pathLength: UByte = 0u,
        onTimeoutKnown: (suspend (Long) -> Unit)? = null,
    ): RemoteNodeSessionDTO {
        val remoteSession = remoteNodeService.createSession(radioId, contact)
        val key = remoteSession.remoteSessionKey

        // Login to the repeater with the appropriate timeout.
        remoteNodeService.login(key, password, pathLength, onTimeoutKnown)

        // Store the password only after a successful login.
        if (password != null && rememberPassword) {
            remoteNodeService.storePassword(password, contact.publicKey)
        }

        return dataStore.fetchRemoteNodeSession(key) ?: throw RemoteNodeError.SessionNotFound()
    }

    /** Disconnect from a repeater by sending logout and removing the session. */
    suspend fun disconnect(session: EntityKey, publicKey: Bytes) {
        remoteNodeService.logout(session)
        remoteNodeService.removeSession(session, publicKey)
    }

    // MARK: - Neighbors (Repeater-Specific)

    /** Request the neighbors list from a repeater; waits at most [timeout] (default [RemoteOperationTimeoutPolicy.binaryMaximum]). */
    suspend fun requestNeighbors(
        session: EntityKey,
        count: UByte = 20u,
        offset: UShort = 0u,
        orderBy: NeighborSortOrder = NeighborSortOrder.NEWEST_FIRST,
        pubkeyPrefixLength: UByte = DEFAULT_PUBKEY_PREFIX_LENGTH,
        timeout: Duration? = null,
    ): NeighboursResponse {
        val remoteSession = dataStore.fetchRemoteNodeSession(session)
        if (remoteSession == null || !remoteSession.isRepeater) throw RemoteNodeError.SessionNotFound()

        auditLogger.logNeighborsRequest(remoteSession.publicKey, count, offset)

        return mappingBinaryErrors {
            withRemoteTimeout(timeout ?: RemoteOperationTimeoutPolicy.binaryMaximum, "remoteNeighbours", clock) {
                this.session.requestNeighbours(remoteSession.publicKey, count, offset, orderBy.rawValue, pubkeyPrefixLength)
            }
        }
    }

    /** Fetch all neighbors with automatic pagination. */
    suspend fun fetchAllNeighbors(
        session: EntityKey,
        orderBy: NeighborSortOrder = NeighborSortOrder.NEWEST_FIRST,
        pubkeyPrefixLength: UByte = DEFAULT_PUBKEY_PREFIX_LENGTH,
        timeout: Duration? = null,
    ): NeighboursResponse {
        // Paginate over the per-page request so each round-trip keeps its audit log entry and timeout
        // ceiling; a single node response is capped to one radio frame.
        val response = NeighboursResponse.collectingAllPages { offset ->
            if (offset > 0u) clock.sleepFor(NeighboursResponse.INTER_PAGE_DELAY)
            requestNeighbors(session, NEIGHBOR_PAGE_SIZE, offset, orderBy, pubkeyPrefixLength, timeout)
        }
        if (response.neighbours.size < response.totalCount) {
            logger.warning { "Neighbour pagination returned ${response.neighbours.size} of ${response.totalCount} entries" }
        }
        return response
    }

    // MARK: - Status / Telemetry / Owner Info

    /** Request status from a repeater. */
    suspend fun requestStatus(session: EntityKey, timeout: Duration? = null): StatusResponse =
        remoteNodeService.requestStatus(session, timeout)

    /** Request telemetry from a repeater. */
    suspend fun requestTelemetry(session: EntityKey, timeout: Duration? = null): TelemetryResponse =
        remoteNodeService.requestTelemetry(session, timeout)

    /** Request owner info from a repeater using the binary protocol. */
    suspend fun requestOwnerInfo(session: EntityKey, timeout: Duration? = null): OwnerInfoResponse =
        remoteNodeService.requestOwnerInfo(session, timeout)

    // MARK: - CLI Commands

    /**
     * Send a CLI command to a repeater and wait for the response (admin only). One command is in flight
     * per node; replies to structured gets must parse to their expected shape or they are dropped.
     */
    suspend fun sendCommand(
        session: EntityKey,
        command: String,
        timeout: Duration = RemoteOperationTimeoutPolicy.defaultCLITimeout,
    ): String = remoteNodeService.sendCLICommand(session, command, timeout)

    /** Send a raw CLI command; the next reply is delivered verbatim (admin only). Used by CLI terminals. */
    suspend fun sendRawCommand(
        session: EntityKey,
        command: String,
        timeout: Duration = RemoteOperationTimeoutPolicy.defaultCLITimeout,
    ): String = remoteNodeService.sendRawCLICommand(session, command, timeout)

    // MARK: - Session Queries

    /** Fetch all repeater sessions for a device. */
    suspend fun fetchRepeaterSessions(radioId: RadioId): List<RemoteNodeSessionDTO> =
        dataStore.fetchRemoteNodeSessions(radioId).filter { it.isRepeater }

    /**
     * Check if a contact is a known repeater with an active session. Swift's store lookup is
     * radio-agnostic; the Android store partitions sessions by radio, so the caller names the radio.
     */
    suspend fun getConnectedSession(radioId: RadioId, publicKeyPrefix: Bytes): RemoteNodeSessionDTO? {
        val remoteSession = dataStore.fetchRemoteNodeSessionByPrefix(radioId, publicKeyPrefix) ?: return null
        return remoteSession.takeIf { it.isRepeater && it.isConnected }
    }

    // MARK: - Handler Invocation

    /** Audit-log a status response, then hand it to the status handler. */
    suspend fun invokeStatusHandler(status: StatusResponse) {
        auditLogger.logStatusResponse(
            RemoteAuditTarget.REPEATER, status.publicKeyPrefix, status.batteryMillivolts, status.uptimeSeconds,
        )
        val handler = statusResponseHandler ?: run {
            logger.fine { "No status handler registered for response from ${status.publicKeyPrefix.hexString}, ignoring" }
            return
        }
        handler(status)
    }

    /** Audit-log a neighbours response, then hand it to the neighbours handler. */
    suspend fun invokeNeighboursHandler(response: NeighboursResponse) {
        auditLogger.logNeighborsResponse(response.publicKeyPrefix, response.totalCount.toInt(), response.neighbours.size)
        val handler = neighboursResponseHandler ?: run {
            logger.fine { "No neighbours handler registered, ignoring response with ${response.neighbours.size} neighbours" }
            return
        }
        handler(response)
    }

    /** Audit-log a telemetry response, then hand it to the telemetry handler. */
    suspend fun invokeTelemetryHandler(response: TelemetryResponse) {
        auditLogger.logTelemetryResponse(RemoteAuditTarget.REPEATER, response.publicKeyPrefix, response.dataPoints.size)
        val handler = telemetryResponseHandler ?: run {
            logger.fine { "No telemetry handler registered, ignoring response" }
            return
        }
        handler(response)
    }

    /** Audit-log a CLI response (full content), then hand it to the CLI handler. */
    suspend fun invokeCLIHandler(message: ContactMessage, contact: ContactDTO) {
        auditLogger.logCLIResponse(contact.publicKey, message.text)
        val handler = cliResponseHandler ?: run {
            logger.fine { "No CLI handler registered, ignoring response from ${contact.displayName}" }
            return
        }
        handler(message, contact)
    }

    // MARK: - Handler Setters

    fun setStatusHandler(handler: suspend (StatusResponse) -> Unit) {
        statusResponseHandler = handler
    }

    fun setNeighboursHandler(handler: suspend (NeighboursResponse) -> Unit) {
        neighboursResponseHandler = handler
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
        neighboursResponseHandler = null
        telemetryResponseHandler = null
    }

    companion object {
        /** Default pubkey prefix length for neighbor queries. */
        const val DEFAULT_PUBKEY_PREFIX_LENGTH: UByte = 6u

        /**
         * Per-request neighbour count used while paginating. A node caps each response to one radio frame
         * regardless, so requesting the maximum minimizes the number of round-trips.
         */
        private const val NEIGHBOR_PAGE_SIZE: UByte = 255u
    }
}
