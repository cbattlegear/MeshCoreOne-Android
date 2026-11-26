// PortedFrom: MeshCore/Sources/MeshCore/Transport/WiFiTransport.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.transport.tcp

sealed interface WiFiTransportError {
    data class ConnectionFailed(val reason: String) : WiFiTransportError
    data object ConnectionTimeout : WiFiTransportError
    data object NotConnected : WiFiTransportError
    data class SendFailed(val reason: String) : WiFiTransportError
    data object SendTimeout : WiFiTransportError
    data object InvalidHost : WiFiTransportError
    data object InvalidPort : WiFiTransportError
    data object NotConfigured : WiFiTransportError
}
