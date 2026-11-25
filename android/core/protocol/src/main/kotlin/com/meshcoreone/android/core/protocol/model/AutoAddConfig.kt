// PortedFrom: MeshCore/Sources/MeshCore/Events/DevicePayloads.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.model

data class AutoAddConfig(val bitmask: UByte, val maxHops: UByte = 0u) {
    companion object {
        val OVERWRITE_OLDEST_BIT: UByte = 0x01u
        val CONTACTS_BIT: UByte = 0x02u
        val REPEATERS_BIT: UByte = 0x04u
        val ROOM_SERVERS_BIT: UByte = 0x08u
        val SENSORS_BIT: UByte = 0x10u
    }
}
