// PortedFrom: MC1Services/Sources/MC1Services/Services/BinaryProtocolService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.ACLResponse
import com.meshcoreone.android.core.protocol.event.MMAResponse
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import com.meshcoreone.android.core.protocol.event.NeighboursResponse
import com.meshcoreone.android.core.protocol.event.StatusResponse
import com.meshcoreone.android.core.protocol.event.TelemetryResponse
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Binary protocol operations with remote mesh nodes: status, telemetry, neighbours, MMA and ACL requests
 * through the MeshCore session, plus push-response handlers.
 *
 * Swift actor isolation becomes confinement: the handler slots are `@Volatile` references read once per
 * event, and the monitor job is swapped inside `synchronized(lock)` with cancellation after the lock is
 * released. The monitor runs in a child [SupervisorJob] of the injected [scope].
 *
 * Deviation: Swift's initializer also takes a `PersistenceStoreProtocol` it never reads; it is omitted.
 */
class BinaryProtocolService(
    private val session: BinaryProtocolSessionPort,
    scope: CoroutineScope,
    private val isFloodFallbackEnabled: suspend () -> Boolean = { true },
    private val isRecoveryOwnerCurrent: () -> Boolean = { true },
) : AutoCloseable {
    private val logger: Logger = Logger.getLogger("com.mc1.BinaryProtocol")
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val serviceScope = CoroutineScope(scope.coroutineContext + job)
    private val lock = Any()

    /** Handler for status responses (from push notifications). */
    @Volatile private var statusResponseHandler: (suspend (StatusResponse) -> Unit)? = null

    /** Handler for telemetry responses (from push notifications). */
    @Volatile private var telemetryResponseHandler: (suspend (TelemetryResponse) -> Unit)? = null

    /** Handler for neighbours responses (from push notifications). */
    @Volatile private var neighboursResponseHandler: (suspend (NeighboursResponse) -> Unit)? = null

    private var eventMonitorTask: Job? = null

    // MARK: - Event Handlers

    fun setStatusResponseHandler(handler: suspend (StatusResponse) -> Unit) {
        statusResponseHandler = handler
    }

    fun setTelemetryResponseHandler(handler: suspend (TelemetryResponse) -> Unit) {
        telemetryResponseHandler = handler
    }

    fun setNeighboursResponseHandler(handler: suspend (NeighboursResponse) -> Unit) {
        neighboursResponseHandler = handler
    }

    // MARK: - Event Monitoring

    /**
     * Start monitoring MeshCore events for binary protocol responses, replacing any previous monitor.
     *
     * Deviation: Swift subscribes inside the new task, so pushes arriving before it first runs are missed;
     * UNDISPATCHED registers the subscription before this function returns, so none are.
     */
    fun startEventMonitoring() {
        // Swift cancels the previous monitor first, so one push never reaches a handler twice.
        synchronized(lock) { eventMonitorTask.also { eventMonitorTask = null } }?.cancel()
        val monitor = serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.events().collect { event -> handleEventKeepingMonitorAlive(event) }
        }
        synchronized(lock) { eventMonitorTask.also { eventMonitorTask = monitor } }?.cancel()
    }

    /**
     * Swift's handlers cannot throw. A throwing Kotlin handler must not end monitoring (later pushes would
     * be dropped) or escape to the uncaught-exception handler.
     */
    private suspend fun handleEventKeepingMonitorAlive(event: MeshEvent) {
        try {
            handleEvent(event)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logger.severe { "Binary protocol push handler failed for ${event::class.simpleName}: $error" }
        }
    }

    /** Stop monitoring events. */
    fun stopEventMonitoring() {
        synchronized(lock) { eventMonitorTask.also { eventMonitorTask = null } }?.cancel()
    }

    private suspend fun handleEvent(event: MeshEvent) {
        when (event) {
            is MeshEvent.StatusResponse -> statusResponseHandler?.invoke(event.response)
            is MeshEvent.TelemetryResponse -> telemetryResponseHandler?.invoke(event.response)
            is MeshEvent.NeighboursResponse -> neighboursResponseHandler?.invoke(event.response)
            else -> Unit
        }
    }

    // MARK: - Status Request

    /** Request status from a remote node by its full 32-byte public key (repeater layout). */
    suspend fun requestStatus(publicKey: Bytes): StatusResponse =
        performWithPathResetOnTimeout(publicKey, "status") { session.requestStatus(publicKey) }

    /** Request status, selecting the firmware status layout from the target node [type]. */
    suspend fun requestStatus(publicKey: Bytes, type: ContactType): StatusResponse =
        performWithPathResetOnTimeout(publicKey, "status") { session.requestStatus(publicKey, type) }

    // MARK: - Telemetry Request

    /** Request telemetry from a remote node. */
    suspend fun requestTelemetry(publicKey: Bytes): TelemetryResponse =
        performWithPathResetOnTimeout(publicKey, "telemetry") { session.requestTelemetry(publicKey) }

    // MARK: - Neighbours Request

    /**
     * Request one page of a remote node's neighbours list.
     *
     * @param count maximum number of neighbours to return (255 = all that fit).
     * @param orderBy sort order (0 = newest first).
     * @param pubkeyPrefixLength length of each neighbour's key prefix in the response.
     */
    suspend fun requestNeighbours(
        publicKey: Bytes,
        count: UByte = 255u,
        offset: UShort = 0u,
        orderBy: UByte = 0u,
        pubkeyPrefixLength: UByte = DEFAULT_PUBKEY_PREFIX_LENGTH,
    ): NeighboursResponse = performWithPathResetOnTimeout(publicKey, "neighbours") {
        session.requestNeighbours(publicKey, count, offset, orderBy, pubkeyPrefixLength)
    }

    /** Fetch all neighbours from a remote node with automatic pagination. */
    suspend fun fetchAllNeighbours(
        publicKey: Bytes,
        orderBy: UByte = 0u,
        pubkeyPrefixLength: UByte = DEFAULT_PUBKEY_PREFIX_LENGTH,
    ): NeighboursResponse = performWithPathResetOnTimeout(publicKey, "neighbours") {
        session.fetchAllNeighbours(publicKey, orderBy, pubkeyPrefixLength)
    }

    // MARK: - MMA Request

    /** Request min/max/average telemetry for the [start]..[end] range from a remote node. */
    suspend fun requestMMA(publicKey: Bytes, start: Instant, end: Instant): MMAResponse =
        performWithPathResetOnTimeout(publicKey, "mma") { session.requestMMA(publicKey, start, end) }

    // MARK: - ACL Request

    /** Request the access control list from a remote node. */
    suspend fun requestACL(publicKey: Bytes): ACLResponse =
        performWithPathResetOnTimeout(publicKey, "acl") { session.requestACL(publicKey) }

    // MARK: - Direct-path flood recovery

    /**
     * On a mesh timeout, calls `resetPath` and retries once if enabled. This does not inspect the route because it has no
     * radio-scoped contact to check for flood routing. Non-timeout mesh errors (and any error on the
     * retry, a second timeout included) map to [BinaryProtocolError.SessionError]; a failed reset rethrows
     * the first timeout wrapped the same way; non-mesh errors and cancellation propagate unchanged.
     */
    private suspend fun <T> performWithPathResetOnTimeout(
        publicKey: Bytes,
        operationName: String,
        operation: suspend () -> T,
    ): T {
        val firstTimeout = try {
            return operation()
        } catch (error: MeshCoreException) {
            if (error !is MeshCoreException.Timeout) throw BinaryProtocolError.SessionError(error)
            error
        }
        return serviceScope.runRemotePathRecovery(isRecoveryOwnerCurrent) {
            requireCurrent()
            if (!isFloodFallbackEnabled()) throw BinaryProtocolError.SessionError(firstTimeout)
            requireCurrent()
            logger.info { "$operationName: mesh timeout; resetting path to flood and retrying once" }
            try {
                session.resetPath(publicKey)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                requireCurrent()
                logger.warning { "$operationName: path reset failed (${error.message}); not retrying" }
                throw BinaryProtocolError.SessionError(firstTimeout)
            }
            requireCurrent()
            try {
                operation()
            } catch (retryError: MeshCoreException) {
                throw BinaryProtocolError.SessionError(retryError)
            }
        }
    }

    // MARK: - Self Telemetry

    /** Get telemetry from the local device. */
    suspend fun getSelfTelemetry(): TelemetryResponse = mappingSessionErrors { session.getSelfTelemetry() }

    // MARK: - Path Discovery

    /** Send a path discovery request to a contact; returns the expected ACK. */
    suspend fun sendPathDiscovery(publicKey: Bytes): MessageSentInfo =
        mappingSessionErrors { session.sendPathDiscovery(publicKey) }

    // MARK: - Trace Route

    /**
     * Send a trace route request.
     *
     * @param tag trace tag (random when null).
     * @param authCode auth code (random when null).
     * @param path optional fixed path to trace.
     */
    suspend fun sendTrace(
        tag: UInt? = null,
        authCode: UInt? = null,
        flags: UByte = 0u,
        path: Bytes? = null,
    ): MessageSentInfo = mappingSessionErrors { session.sendTrace(tag, authCode, flags, path) }

    private inline fun <T> mappingSessionErrors(operation: () -> T): T = try {
        operation()
    } catch (error: MeshCoreException) {
        throw BinaryProtocolError.SessionError(error)
    }

    /** Swift `deinit`: stops event monitoring and cancels the service's jobs. */
    override fun close() {
        stopEventMonitoring()
        job.cancel()
    }

    companion object {
        /** Default pubkey prefix length for neighbour queries, so response parsing uses a matching length. */
        const val DEFAULT_PUBKEY_PREFIX_LENGTH: UByte = 6u
    }
}
