// PortedFrom: MC1Services/Sources/MC1Services/Connection/DeviceConnectionState.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Connection/ConnectionIntent.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Transport/BLEPhaseKind.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/ServiceContainer.swift@db14559b39d32322b06477c6ae676112f583db50
// Epoch/generation and factory seams from the approved neutral lifetime contract.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.event.MeshTransportError
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@JvmInline value class ProcessEpoch(val value: UUID)
@JvmInline value class Generation(val value: Long) { init { require(value >= 0) { "Generation must not be negative" } } }
data class SessionToken(val epoch: ProcessEpoch, val generation: Generation, val radioId: RadioId)

/** An accepted device replacement, ordered within its connection generation. */
data class ConnectedDeviceEdit(
    val token: SessionToken,
    val sequence: Long,
    val previousKnownRegions: SnapshotList<String>,
) {
    init { require(sequence > 0) { "Device edit sequence must be positive" } }
}

enum class DeviceConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, SYNCING, READY;
    val isOperational: Boolean get() = this == SYNCING || this == READY
    val isConnected: Boolean get() = this == CONNECTED || isOperational
    val canDrainSendQueue: Boolean get() = this == READY
}

enum class BleLinkPhase(val rawValue: String) {
    IDLE("idle"), WAITING_FOR_BLUETOOTH("waitingForBluetooth"), CONNECTING("connecting"),
    DISCOVERING_SERVICES("discoveringServices"), DISCOVERING_CHARACTERISTICS("discoveringCharacteristics"),
    SUBSCRIBING_TO_NOTIFICATIONS("subscribingToNotifications"), DISCOVERY_COMPLETE("discoveryComplete"),
    CONNECTED("connected"), AUTO_RECONNECTING("autoReconnecting"), RESTORING_STATE("restoringState"),
    DISCONNECTING("disconnecting");
    val isDiscoveryChain: Boolean get() = this == DISCOVERING_SERVICES ||
        this == DISCOVERING_CHARACTERISTICS || this == SUBSCRIBING_TO_NOTIFICATIONS
    val isActive: Boolean get() = this != IDLE
}

sealed interface ConnectionIntent {
    data object None : ConnectionIntent
    data object UserDisconnected : ConnectionIntent
    data class WantsConnection(val forceFullSync: Boolean = false) : ConnectionIntent
    val wantsConnection: Boolean get() = this is WantsConnection
    val persistedUserDisconnected: Boolean get() = this == UserDisconnected
    companion object {
        fun restored(userDisconnected: Boolean): ConnectionIntent = if (userDisconnected) UserDisconnected else None
    }
}

enum class Capability { BLUETOOTH_CONNECT, BLUETOOTH_SCAN, COMPANION_ASSOCIATION, LOCAL_NETWORK, NOTIFICATIONS, LOCATION, TRANSLATION }
enum class LifecycleStage { CONNECT, QUERY_IDENTITY, CONSTRUCT_SERVICES, START_MONITORING, FLUSH_RX, FLUSH_DEBUG, CLOSE_TRANSPORT, STOP_SERVICES }

sealed interface ConnectionIssue {
    data class PermissionDenied(val capability: Capability) : ConnectionIssue
    data class Unsupported(val capability: Capability) : ConnectionIssue
    data object BluetoothOff : ConnectionIssue
    data class Transport(val error: MeshTransportError) : ConnectionIssue
    data class Protocol(val error: MeshCoreException) : ConnectionIssue
    data class Storage(val error: PersistenceStoreException) : ConnectionIssue
    data class Lifecycle(val stage: LifecycleStage, val cause: Throwable) : ConnectionIssue
}

data class ConnectionSnapshot(
    val state: DeviceConnectionState,
    val transport: ConnectionState,
    val blePhase: BleLinkPhase?,
    val intent: ConnectionIntent,
    val token: SessionToken?,
    val issue: ConnectionIssue?,
) {
    init {
        require(state != DeviceConnectionState.DISCONNECTED || token == null) { "Disconnected snapshot cannot retain an active generation" }
        require(!state.isOperational || token != null) { "Operational snapshot requires a resolved radio generation" }
    }
}

@JvmInline value class BluetoothAddress(val value: String) {
    init { require(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}").matches(value)) { "Malformed Bluetooth address" } }
}

data class BluetoothPairingHandle(val address: BluetoothAddress, val associationId: Int?)
sealed interface ConnectionTarget {
    data class Bluetooth(val handle: BluetoothPairingHandle, val deviceId: UUID) : ConnectionTarget
    data class WiFi(val host: String, val port: UShort) : ConnectionTarget
    data object Simulator : ConnectionTarget
}
enum class DisconnectReason { USER_REQUEST, DEVICE_SWITCH, TRANSPORT_LOST, PROCESS_SHUTDOWN, SESSION_FAILURE }

interface ConnectionSignals {
    val snapshot: StateFlow<ConnectionSnapshot>
    suspend fun subscribeTransitions(): ConnectionSubscription
}

interface ConnectionSubscription : AutoCloseable {
    val initial: ConnectionSnapshot
    val transitions: Flow<ConnectionSnapshot>
    override fun close()
}

interface ConnectionController : ConnectionSignals {
    suspend fun connect(target: ConnectionTarget)
    suspend fun disconnect(reason: DisconnectReason)
}

// WP-107 supplies Session; no substitute session implementation is constructed here.
data class SessionInputs<Session : Any>(
    val token: SessionToken, val session: Session, val signals: ConnectionSignals, val scope: CoroutineScope,
)
data class MonitoringOptions(val enableAutoFetch: Boolean = true, val enableAdvertisementMonitoring: Boolean = true)
data class TeardownIssue(val stage: LifecycleStage, val cause: Throwable)
data class TeardownReport(val issues: SnapshotList<TeardownIssue>) { val isComplete: Boolean get() = issues.isEmpty() }

interface SessionServiceFactory<Session : Any> { suspend fun create(inputs: SessionInputs<Session>): SessionServices }
interface SessionServices {
    val token: SessionToken
    suspend fun startMonitoring(options: MonitoringOptions = MonitoringOptions())
    suspend fun tearDown(): TeardownReport
}

data class SessionEvent<out Event>(val token: SessionToken, val event: Event)
interface SessionEventSubscription<out Event> : AutoCloseable {
    val events: Flow<SessionEvent<Event>>
    override fun close()
}
