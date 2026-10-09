// AndroidOnly: WP-402 Presentation state of the ongoing connection notification, derived from the one connection owner's snapshot.
package com.meshcoreone.android.platform.notifications.status

import com.meshcoreone.android.core.contracts.domain.BleLinkPhase
import com.meshcoreone.android.core.contracts.domain.Capability
import com.meshcoreone.android.core.contracts.domain.ConnectionIssue
import com.meshcoreone.android.core.contracts.domain.ConnectionSnapshot
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.protocol.event.ConnectionState

/** What the ongoing notification tells the user. Read-only view of the connection owner; never a second source of truth. */
enum class ConnectionStatusKind { CONNECTING, SYNCING, RECONNECTING, READY, DISCONNECTED, BLUETOOTH_OFF, PERMISSION_NEEDED, FAILED }

/** An action button the notification can offer for a status. */
enum class ConnectionStatusAction { CONNECT, DISCONNECT, OPEN_PERMISSION_SETTINGS }

data class ConnectionStatus(
    val kind: ConnectionStatusKind,
    val radioName: String?,
    /** True only while the user's own connect request is the reason for the in-progress state. */
    val userInitiated: Boolean,
) {
    /** True while the status describes work in flight (not a steady state). */
    val inProgress: Boolean get() = kind == ConnectionStatusKind.CONNECTING || kind == ConnectionStatusKind.SYNCING
}

object ConnectionStatusMapper {
    /** Capabilities whose denial blocks a connection (as opposed to e.g. notifications or location). */
    private val connectionCapabilities = setOf(Capability.BLUETOOTH_CONNECT, Capability.BLUETOOTH_SCAN, Capability.COMPANION_ASSOCIATION)

    fun map(snapshot: ConnectionSnapshot, radioName: String?, userInitiated: Boolean): ConnectionStatus {
        val kind = when (snapshot.state) {
            DeviceConnectionState.READY -> ConnectionStatusKind.READY
            DeviceConnectionState.SYNCING, DeviceConnectionState.CONNECTED -> ConnectionStatusKind.SYNCING
            DeviceConnectionState.CONNECTING -> if (isReconnecting(snapshot)) ConnectionStatusKind.RECONNECTING else ConnectionStatusKind.CONNECTING
            DeviceConnectionState.DISCONNECTED -> disconnectedKind(snapshot)
        }
        val promotable = userInitiated && kind != ConnectionStatusKind.RECONNECTING
        return ConnectionStatus(kind, radioName, promotable)
    }

    private fun isReconnecting(snapshot: ConnectionSnapshot): Boolean =
        snapshot.transport is ConnectionState.Reconnecting || snapshot.blePhase == BleLinkPhase.AUTO_RECONNECTING

    private fun disconnectedKind(snapshot: ConnectionSnapshot): ConnectionStatusKind {
        if (snapshot.transport is ConnectionState.Reconnecting) return ConnectionStatusKind.RECONNECTING
        return when (val issue = snapshot.issue) {
            null -> ConnectionStatusKind.DISCONNECTED
            ConnectionIssue.BluetoothOff -> ConnectionStatusKind.BLUETOOTH_OFF
            is ConnectionIssue.PermissionDenied ->
                if (issue.capability in connectionCapabilities) ConnectionStatusKind.PERMISSION_NEEDED else ConnectionStatusKind.DISCONNECTED
            else -> ConnectionStatusKind.FAILED
        }
    }

    /** Which buttons to show. Connect needs a known last radio ([canConnect]); a permission problem offers the fix instead. */
    fun actionsFor(kind: ConnectionStatusKind, canConnect: Boolean, canOpenSettings: Boolean): List<ConnectionStatusAction> = when (kind) {
        ConnectionStatusKind.CONNECTING, ConnectionStatusKind.SYNCING,
        ConnectionStatusKind.RECONNECTING, ConnectionStatusKind.READY -> listOf(ConnectionStatusAction.DISCONNECT)
        ConnectionStatusKind.PERMISSION_NEEDED ->
            if (canOpenSettings) listOf(ConnectionStatusAction.OPEN_PERMISSION_SETTINGS) else emptyList()
        ConnectionStatusKind.DISCONNECTED, ConnectionStatusKind.FAILED, ConnectionStatusKind.BLUETOOTH_OFF ->
            if (canConnect) listOf(ConnectionStatusAction.CONNECT) else emptyList()
    }
}
