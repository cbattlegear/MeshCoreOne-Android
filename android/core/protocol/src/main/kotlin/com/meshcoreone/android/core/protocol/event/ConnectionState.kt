// PortedFrom: MeshCore/Sources/MeshCore/Events/ConnectionState.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.event

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Reconnecting(val attempt: Long) : ConnectionState
    data class Failed(val error: MeshTransportError) : ConnectionState
}
