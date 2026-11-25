// PortedFrom: MC1Services/Sources/MC1Services/Models/ConnectionMethod.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Connection/TransportType.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.model

import java.util.UUID

sealed interface ConnectionMethod {
    val displayName: String?
    val id: String
    val shortDescription: String
    val isBluetooth: Boolean get() = this is Bluetooth
    val isWiFi: Boolean get() = this is WiFi

    // This is a legacy Apple handle, never an Android pairing or a radio identity.
    data class Bluetooth(val peripheralUUID: UUID, override val displayName: String? = null) : ConnectionMethod {
        override val id: String get() = "ble:${peripheralUUID.canonicalString()}"
        override val shortDescription: String get() = "Bluetooth"
    }

    data class WiFi(val host: String, val port: UShort, override val displayName: String? = null) : ConnectionMethod {
        override val id: String get() = "wifi:$host:$port"
        override val shortDescription: String get() = if (port == 5000.toUShort()) host else "$host:$port"
    }
}

enum class TransportType { BLUETOOTH, WIFI, SIMULATOR }
