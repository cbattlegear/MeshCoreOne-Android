// PortedFrom: MC1/Views/RemoteNodes/Repeaters/RepeaterStatusViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.status

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.NeighborSnapshotEntry
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.protocol.event.Neighbour
import com.meshcoreone.android.core.protocol.event.NeighboursResponse
import com.meshcoreone.android.core.protocol.event.OwnerInfoResponse
import com.meshcoreone.android.core.protocol.event.StatusResponse
import com.meshcoreone.android.feature.remotenodes.cli.RemoteOperationTimeouts
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesClock
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.common.SwiftNumberFormat
import com.meshcoreone.android.feature.remotenodes.dependencies.ContactOcvPort
import com.meshcoreone.android.feature.remotenodes.dependencies.NodeSnapshotPort
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeFaultClassifier
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodesFeatureDependencies
import com.meshcoreone.android.feature.remotenodes.dependencies.RepeaterAdminPort
import com.meshcoreone.android.feature.remotenodes.resolver.NeighborNameResolver
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Repeater-only state (Swift `RepeaterStatusViewModel` stored properties). */
data class RepeaterStatusState(
    val neighbors: List<Neighbour> = emptyList(),
    val isLoadingNeighbors: Boolean = false,
    val neighborsLoaded: Boolean = false,
    val neighborsExpanded: Boolean = false,
    val neighborsSectionError: RemoteNodesText? = null,
    /** Hex width for neighbour prefixes, captured with each response so a disconnect does not reflow. */
    val neighborKeyDisplayByteCount: Int = NeighborNameResolver.MINIMUM_KEY_DISPLAY_BYTE_COUNT,
    val isDiscovering: Boolean = false,
    val discoverySecondsRemaining: Int = 0,
    val ownerInfo: String? = null,
    /** Firmware from owner info; null when the node predates owner-info and replies with "". */
    val firmwareVersion: String? = null,
    val isLoadingOwnerInfo: Boolean = false,
    val ownerInfoExpanded: Boolean = false,
    val ownerInfoError: RemoteNodesText? = null,
) {
    val ownerInfoLoaded: Boolean get() = ownerInfo != null
}

/**
 * Repeater status screen logic (Swift `RepeaterStatusViewModel`): status, neighbours, telemetry and
 * owner info through the shared retry runner, plus the 60 s neighbour discovery loop on [scope].
 */
class RepeaterStatusStateHolder(
    private val clock: RemoteNodesClock,
    faults: RemoteNodeFaultClassifier,
    private val scope: CoroutineScope,
) {
    val helper = NodeStatusStateHolder(clock, faults)

    private val _state = MutableStateFlow(RepeaterStatusState())
    val state: StateFlow<RepeaterStatusState> = _state.asStateFlow()

    @Volatile private var repeaterAdminProvider: () -> RepeaterAdminPort? = { null }
    @Volatile private var deviceHashSizeProvider: () -> Int? = { null }
    @Volatile private var discoverJob: Job? = null

    private val repeaterAdmin: RepeaterAdminPort? get() = repeaterAdminProvider()

    /** Null providers mirror a disconnected radio; requests then no-op. */
    fun configure(
        repeaterAdmin: () -> RepeaterAdminPort?,
        contactOcv: () -> ContactOcvPort?,
        nodeSnapshots: () -> NodeSnapshotPort?,
        deviceHashSize: () -> Int?,
    ) {
        repeaterAdminProvider = repeaterAdmin
        deviceHashSizeProvider = deviceHashSize
        helper.configure(contactOcv, nodeSnapshots)
    }

    fun configure(dependencies: RemoteNodesFeatureDependencies) = configure(
        dependencies::repeaterAdmin, dependencies::contactOcv, dependencies::nodeSnapshots, dependencies::deviceHashSize,
    )

    fun setNeighborsExpanded(expanded: Boolean) = _state.update { it.copy(neighborsExpanded = expanded) }
    fun setOwnerInfoExpanded(expanded: Boolean) = _state.update { it.copy(ownerInfoExpanded = expanded) }

    /**
     * Sets only the status, neighbours and telemetry slots: the admin service is shared with the
     * settings/CLI surface, whose CLI handler must survive.
     */
    fun registerHandlers() {
        val service = repeaterAdmin ?: return
        service.setStatusHandler { status ->
            if (helper.matchesSession(status.publicKeyPrefix)) handleStatusResponse(status)
        }
        service.setNeighboursHandler { response ->
            if (helper.matchesSession(response.publicKeyPrefix)) handleNeighboursResponse(response)
        }
        service.setTelemetryHandler { response ->
            if (helper.matchesSession(response.publicKeyPrefix)) helper.handleTelemetryResponse(response)
        }
    }

    /** True surface teardown: clears every slot, the CLI handler included. */
    fun cleanup() {
        repeaterAdmin?.clearHandlers()
    }

    /** Status-segment teardown: clears this screen's slots, leaving the CLI handler. */
    fun clearStatusHandlers() {
        repeaterAdmin?.clearStatusHandlers()
    }

    suspend fun requestStatus(session: RemoteNodeSessionDTO) {
        val service = repeaterAdmin ?: return
        helper.adoptSessionIfAbsent(session)
        helper.runStatusSectionRequest({ service.requestStatus(session.entityKey, it) }, ::handleStatusResponse)
    }

    private suspend fun handleStatusResponse(response: StatusResponse) =
        helper.handleStatusResponse(response, rxAirtimeSeconds = response.rxAirtime, receiveErrors = response.receiveErrors)

    suspend fun requestNeighbors(session: RemoteNodeSessionDTO) {
        val service = repeaterAdmin ?: return
        helper.adoptSessionIfAbsent(session)
        helper.retry.runRetryingSectionRequest(
            operationName = "neighbors",
            setLoading = { loading -> _state.update { it.copy(isLoadingNeighbors = loading) } },
            setError = { error -> _state.update { it.copy(neighborsSectionError = error) } },
            operation = { service.fetchAllNeighbors(session.entityKey, it) },
            onSuccess = ::handleNeighboursResponse,
        )
    }

    suspend fun handleNeighboursResponse(response: NeighboursResponse) {
        val width = NeighborNameResolver.keyDisplayByteCount(deviceHashSizeProvider())
        _state.update {
            it.copy(
                neighbors = response.neighbours.toList(), neighborKeyDisplayByteCount = width,
                isLoadingNeighbors = false, neighborsLoaded = true,
            )
        }
        helper.enrichNeighbors(response.neighbours.map { NeighborSnapshotEntry(it.publicKeyPrefix, it.snr, it.secondsAgo) })
    }

    suspend fun requestTelemetry(session: RemoteNodeSessionDTO) {
        val service = repeaterAdmin ?: return
        helper.adoptSessionIfAbsent(session)
        helper.runTelemetrySectionRequest(operation = { service.requestTelemetry(session.entityKey, it) }) {
            helper.handleTelemetryResponse(it)
        }
    }

    suspend fun requestOwnerInfo(session: RemoteNodeSessionDTO) {
        val service = repeaterAdmin ?: return
        helper.adoptSessionIfAbsent(session)
        helper.retry.runRetryingSectionRequest(
            operationName = "ownerInfo",
            setLoading = { loading -> _state.update { it.copy(isLoadingOwnerInfo = loading) } },
            setError = { error -> _state.update { it.copy(ownerInfoError = error) } },
            operation = { service.requestOwnerInfo(session.entityKey, it) },
            onSuccess = { applyOwnerInfo(it) },
        )
    }

    /** Maps an empty firmware string (nodes predating owner-info) to null so the row stays hidden. */
    fun applyOwnerInfo(response: OwnerInfoResponse) = _state.update {
        it.copy(ownerInfo = response.ownerInfo, firmwareVersion = response.firmwareVersion.ifEmpty { null })
    }

    /** Swift `receiveErrorsDisplay`: the count when positive, else null (row hidden). */
    fun receiveErrorsDisplay(locale: Locale): String? {
        val count = helper.state.value.status?.receiveErrors ?: return null
        return if (count > 0u) SwiftNumberFormat.integer(count.toLong(), locale) else null
    }

    // MARK: - Discovery

    /**
     * Sends `discover.neighbors`, then counts down 60 s in 1 s ticks, re-requesting neighbours every
     * fifth tick. Returns the discovery job, or null when disconnected or already discovering.
     */
    fun startDiscovery(session: RemoteNodeSessionDTO): Job? {
        val service = repeaterAdmin ?: return null
        if (discoverJob != null) return null
        _state.update { it.copy(discoverySecondsRemaining = DISCOVERY_DURATION_SECONDS, isDiscovering = true) }
        val job = scope.launch(start = CoroutineStart.LAZY) { runDiscovery(service, session) }
        discoverJob = job
        job.invokeOnCompletion { finishDiscovery(job) }
        job.start()
        return job
    }

    fun stopDiscovery() {
        val job = discoverJob
        discoverJob = null
        job?.cancel()
        _state.update { it.copy(isDiscovering = false, discoverySecondsRemaining = 0) }
    }

    private suspend fun runDiscovery(service: RepeaterAdminPort, session: RemoteNodeSessionDTO) {
        try {
            service.sendCommand(session.entityKey, DISCOVER_COMMAND, RemoteOperationTimeouts.defaultCLITimeout)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { it.copy(neighborsSectionError = RemoteNodesText.Failure(error)) }
            return
        }
        val start = clock.now
        var ticks = 0
        while (currentCoroutineContext().isActive) {
            clock.sleep(1.seconds)
            val elapsed = (java.time.Duration.between(start, clock.now).toNanos() / NANOS_PER_SECOND).toInt()
            val remaining = maxOf(0, DISCOVERY_DURATION_SECONDS - elapsed)
            _state.update { it.copy(discoverySecondsRemaining = remaining) }
            ticks += 1
            if (ticks % POLL_INTERVAL_TICKS == 0) requestNeighbors(session)
            if (remaining <= 0) break
        }
    }

    private fun finishDiscovery(job: Job) {
        if (discoverJob !== job) return
        discoverJob = null
        _state.update { it.copy(isDiscovering = false, discoverySecondsRemaining = 0) }
    }

    companion object {
        const val DISCOVERY_DURATION_SECONDS = 60
        const val POLL_INTERVAL_TICKS = 5
        const val DISCOVER_COMMAND = "discover.neighbors"
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

/** Service addressing for a session (core:contracts `EntityKey`). */
internal val RemoteNodeSessionDTO.entityKey: EntityKey get() = EntityKey(radioId, id)
