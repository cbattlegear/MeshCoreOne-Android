// PortedFrom: MC1Services/Sources/MC1Services/Errors/WiFiTransportError+LocalizedError.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.transport

import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransportError

internal object WiFiAppErrorReference {
    fun description(error: WiFiTransportError): String = when (error) {
        is WiFiTransportError.ConnectionFailed -> "Connection failed: ${error.reason}"
        WiFiTransportError.ConnectionTimeout -> "Connection timed out. Check the hostname or IP address and ensure the device is reachable."
        WiFiTransportError.NotConnected -> "Not connected to device."
        is WiFiTransportError.SendFailed -> "Failed to send data: ${error.reason}"
        WiFiTransportError.SendTimeout -> "Send operation timed out."
        WiFiTransportError.InvalidHost -> "Invalid hostname or IP address."
        WiFiTransportError.InvalidPort -> "Invalid port number."
        WiFiTransportError.NotConfigured -> "Connection not configured."
    }
}
