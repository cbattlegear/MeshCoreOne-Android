// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomStatusViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.status

import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.protocol.event.StatusResponse
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesClock
import com.meshcoreone.android.feature.remotenodes.dependencies.ContactOcvPort
import com.meshcoreone.android.feature.remotenodes.dependencies.NodeSnapshotPort
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeFaultClassifier
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodesFeatureDependencies
import com.meshcoreone.android.feature.remotenodes.dependencies.RoomAdminPort
import java.util.Locale

/** Room server status screen logic (Swift `RoomStatusViewModel`); all state lives on [helper]. */
class RoomStatusStateHolder(clock: RemoteNodesClock, faults: RemoteNodeFaultClassifier) {
    val helper = NodeStatusStateHolder(clock, faults)

    @Volatile private var roomAdminProvider: () -> RoomAdminPort? = { null }

    private val roomAdmin: RoomAdminPort? get() = roomAdminProvider()

    /** Null providers mirror a disconnected radio; requests then no-op. */
    fun configure(roomAdmin: () -> RoomAdminPort?, contactOcv: () -> ContactOcvPort?, nodeSnapshots: () -> NodeSnapshotPort?) {
        roomAdminProvider = roomAdmin
        helper.configure(contactOcv, nodeSnapshots)
    }

    fun configure(dependencies: RemoteNodesFeatureDependencies) =
        configure(dependencies::roomAdmin, dependencies::contactOcv, dependencies::nodeSnapshots)

    /** Sets only the status and telemetry slots, leaving the shared CLI handler intact. */
    fun registerHandlers() {
        val service = roomAdmin ?: return
        service.setStatusHandler { status ->
            if (helper.matchesSession(status.publicKeyPrefix)) handleStatusResponse(status)
        }
        service.setTelemetryHandler { response ->
            if (helper.matchesSession(response.publicKeyPrefix)) helper.handleTelemetryResponse(response)
        }
    }

    /** True surface teardown: clears every slot, the CLI handler included. */
    fun cleanup() {
        roomAdmin?.clearHandlers()
    }

    /** Status-segment teardown: clears this screen's slots, leaving the CLI handler. */
    fun clearStatusHandlers() {
        roomAdmin?.clearStatusHandlers()
    }

    suspend fun requestStatus(session: RemoteNodeSessionDTO) {
        val service = roomAdmin ?: return
        helper.adoptSessionIfAbsent(session)
        helper.runStatusSectionRequest({ service.requestStatus(session.entityKey, it) }, ::handleStatusResponse)
    }

    private suspend fun handleStatusResponse(response: StatusResponse) = helper.handleStatusResponse(
        response, postedCount = response.roomServerPostedCount, postPushCount = response.roomServerPostPushCount,
    )

    suspend fun requestTelemetry(session: RemoteNodeSessionDTO) {
        val service = roomAdmin ?: return
        helper.adoptSessionIfAbsent(session)
        helper.runTelemetrySectionRequest(operation = { service.requestTelemetry(session.entityKey, it) }) {
            helper.handleTelemetryResponse(it)
        }
    }

    fun postsReceivedDisplay(locale: Locale): String =
        NodeStatusDisplay.count(helper.state.value.status?.roomServerPostedCount, locale)

    fun postsPushedDisplay(locale: Locale): String =
        NodeStatusDisplay.count(helper.state.value.status?.roomServerPostPushCount, locale)
}
