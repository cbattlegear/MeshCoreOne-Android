// AndroidOnly: WP-313 Adapt the existing service graph without introducing a feature-to-feature dependency.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.*
import com.meshcoreone.android.core.services.device.FirmwareDeviceErrorCode
import com.meshcoreone.android.core.services.device.RadioOptions
import com.meshcoreone.android.core.services.remote.BinaryProtocolError
import com.meshcoreone.android.core.services.remote.RemoteNodeError
import com.meshcoreone.android.core.services.remote.RepeaterAdminService
import com.meshcoreone.android.core.services.remote.RoomAdminService
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesClock
import com.meshcoreone.android.feature.remotenodes.dependencies.*
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine

internal fun AppContainer.createRemoteNodesFeatureDependencies(): RemoteNodesUiDependencies =
    object : RemoteNodesUiDependencies {
        override val radioOptions = RemoteRadioOptions(
            RadioOptions.bandwidthsKHz, RadioOptions.spreadingFactors.toList(), RadioOptions.codingRates.toList(),
        )
        override val updates = combine(
            connectionManager.snapshot, appState.servicesVersion, appState.contactsVersion, appState.sessionStateChangeCount,
        ) { _, _, _, _ ->
            RemoteNodesConnection(
                appState.currentRadioId,
                sessions.current?.token?.toString(),
                appState.connectionState == DeviceConnectionState.READY,
            )
        }

        override suspend fun catalog(): RemoteNodesCatalog {
            val radio = appState.currentRadioId ?: return RemoteNodesCatalog(emptyList(), emptyList(), emptyList())
            val store = appState.offlineDataStore ?: throw RemoteNodeError.NotConnected()
            return RemoteNodesCatalog(
                store.fetchContacts(radio).toList(), store.fetchDiscoveredNodes(radio).toList(),
                store.fetchRemoteNodeSessions(radio).toList(),
            )
        }

        override fun services(): RemoteNodesFeatureDependencies {
            val bound = sessions.current
            val store = appState.offlineDataStore
            return object : RemoteNodesFeatureDependencies {
                private fun active() = bound?.takeIf { sessions.current === it }
                override val clock = object : RemoteNodesClock {
                    override val now: Instant get() = Instant.now()
                    override val elapsed: Duration get() = System.nanoTime().nanoseconds
                    override suspend fun sleep(duration: Duration) = delay(duration)
                }
                override val faults = AppRemoteNodeFaults
                override fun repeaterAdmin() = active()?.repeaterAdminService?.let(::AppRepeaterAdmin)
                override fun roomAdmin() = active()?.roomAdminService?.let(::AppRoomAdmin)
                override fun binaryTelemetry() = active()?.binaryProtocolService?.let { service ->
                    BinaryTelemetryPort(service::requestTelemetry)
                }
                override fun historyStore() = store?.let { remoteNodeHistoryStore(it, it, it) }
                override fun connectedRadioId() = active()?.token?.radioId
                override fun deviceHashSize() = active()?.let { appState.connectedDevice?.hashSize }
                override fun nodeSnapshots(): NodeSnapshotPort? = active()?.nodeSnapshotService?.let { service ->
                    object : NodeSnapshotPort {
                        override suspend fun recordSnapshot(
                            nodePublicKey: Bytes, status: NodeStatusMetrics?, telemetry: SnapshotList<TelemetrySnapshotEntry>?,
                            neighbors: SnapshotList<NeighborSnapshotEntry>?, location: NodeLocationFix?,
                        ) = service.recordSnapshot(nodePublicKey, status, telemetry, neighbors, location)
                        override suspend fun neighborBaseline(nodePublicKey: Bytes) = service.neighborBaseline(nodePublicKey)
                        override suspend fun previousStatusSnapshot(nodePublicKey: Bytes, before: Instant) =
                            service.previousStatusSnapshot(nodePublicKey, before)
                        override suspend fun fetchSnapshots(nodePublicKey: Bytes, since: Instant?) =
                            service.fetchSnapshots(nodePublicKey, since)
                    }
                }
                override fun contactOcv(): ContactOcvPort? = active()?.contactService?.let { service ->
                    object : ContactOcvPort {
                        override suspend fun getContact(radioId: RadioId, publicKey: Bytes) = service.getContact(radioId, publicKey)
                        override suspend fun updateContactOCVSettings(contactId: java.util.UUID, preset: String, customArray: String?) =
                            service.updateContactOCVSettings(contactId, preset, customArray)
                    }
                }
                override fun login(): RemoteNodeLoginPort? = active()?.let { service ->
                    object : RemoteNodeLoginPort {
                        override suspend fun retrievePassword(contact: ContactDTO) = service.remoteNodeService.retrievePassword(contact)
                        override suspend fun deletePassword(contact: ContactDTO) = service.remoteNodeService.deletePassword(contact)
                        override suspend fun resetPath(radioId: RadioId, publicKey: Bytes) = service.contactService.resetPath(radioId, publicKey)
                        override suspend fun connectAsAdmin(
                            radioId: RadioId, contact: ContactDTO, password: String, rememberPassword: Boolean,
                            pathLength: UByte, onTimeoutKnown: suspend (Long) -> Unit,
                        ) = service.repeaterAdminService.connectAsAdmin(radioId, contact, password, rememberPassword, pathLength, onTimeoutKnown)
                        override suspend fun joinRoom(
                            radioId: RadioId, contact: ContactDTO, password: String, rememberPassword: Boolean,
                            pathLength: UByte, onTimeoutKnown: suspend (Long) -> Unit,
                        ) = service.roomServerService.joinRoom(radioId, contact, password, rememberPassword, pathLength, onTimeoutKnown)
                    }
                }
            }
        }
    }

internal object AppRemoteNodeFaults : RemoteNodeFaultClassifier {
    override fun isTimeout(error: Throwable) = error is RemoteNodeError.Timeout
    override fun isRemoteNoResponseYet(error: Throwable): Boolean {
        val mesh = when (error) {
            is RemoteNodeError.SessionError -> error.error
            is BinaryProtocolError.SessionError -> error.error
            else -> return false
        }
        return mesh is MeshCoreException.DeviceError && mesh.code == FirmwareDeviceErrorCode.remoteNodeNoResponseYet
    }
    override fun isBinarySessionTimeout(error: Throwable) =
        error is BinaryProtocolError.SessionError && error.error is MeshCoreException.Timeout
}

internal class AppRepeaterAdmin(private val service: RepeaterAdminService) : RepeaterAdminPort {
    override suspend fun sendCommand(session: EntityKey, command: String, timeout: Duration) = service.sendCommand(session, command, timeout)
    override suspend fun sendRawCommand(session: EntityKey, command: String, timeout: Duration) = service.sendRawCommand(session, command, timeout)
    override fun setCLIHandler(handler: suspend (ContactMessage, ContactDTO) -> Unit) = service.setCLIHandler(handler)
    override suspend fun requestStatus(session: EntityKey, timeout: Duration?) = service.requestStatus(session, timeout)
    override suspend fun requestTelemetry(session: EntityKey, timeout: Duration?) = service.requestTelemetry(session, timeout)
    override suspend fun requestOwnerInfo(session: EntityKey, timeout: Duration?) = service.requestOwnerInfo(session, timeout)
    override suspend fun fetchAllNeighbors(session: EntityKey, timeout: Duration?) = service.fetchAllNeighbors(session, timeout)
    override fun setStatusHandler(handler: suspend (StatusResponse) -> Unit) = service.setStatusHandler(handler)
    override fun setTelemetryHandler(handler: suspend (TelemetryResponse) -> Unit) = service.setTelemetryHandler(handler)
    override fun setNeighboursHandler(handler: suspend (NeighboursResponse) -> Unit) = service.setNeighboursHandler(handler)
    override fun clearHandlers() = service.clearHandlers()
    override fun clearStatusHandlers() = service.clearStatusHandlers()
}

internal class AppRoomAdmin(private val service: RoomAdminService) : RoomAdminPort {
    override suspend fun sendCommand(session: EntityKey, command: String, timeout: Duration) = service.sendCommand(session, command, timeout)
    override suspend fun sendRawCommand(session: EntityKey, command: String, timeout: Duration) = service.sendRawCommand(session, command, timeout)
    override fun setCLIHandler(handler: suspend (ContactMessage, ContactDTO) -> Unit) = service.setCLIHandler(handler)
    override suspend fun requestStatus(session: EntityKey, timeout: Duration?) = service.requestStatus(session, timeout)
    override suspend fun requestTelemetry(session: EntityKey, timeout: Duration?) = service.requestTelemetry(session, timeout)
    override fun setStatusHandler(handler: suspend (StatusResponse) -> Unit) = service.setStatusHandler(handler)
    override fun setTelemetryHandler(handler: suspend (TelemetryResponse) -> Unit) = service.setTelemetryHandler(handler)
    override fun clearHandlers() = service.clearHandlers()
    override fun clearStatusHandlers() = service.clearStatusHandlers()
}
