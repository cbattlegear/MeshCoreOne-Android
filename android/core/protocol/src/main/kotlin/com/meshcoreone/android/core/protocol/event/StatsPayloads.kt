// PortedFrom: MeshCore/Sources/MeshCore/Events/StatsPayloads.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.event

data class CoreStats(
    val batteryMV: UShort,
    val uptimeSeconds: UInt,
    val errors: UShort,
    val queueLength: UByte,
)

data class RadioStats(
    val noiseFloor: Short,
    val lastRSSI: Byte,
    val lastSNR: Double,
    val txAirtimeSeconds: UInt,
    val rxAirtimeSeconds: UInt,
) {
    private val fields get() = arrayOf(noiseFloor, lastRSSI, lastSNR, txAirtimeSeconds, rxAirtimeSeconds)
    override fun equals(other: Any?): Boolean = other is RadioStats && eventFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = eventFieldsHash(fields)
}

data class PacketStats(
    val received: UInt,
    val sent: UInt,
    val floodTx: UInt,
    val directTx: UInt,
    val floodRx: UInt,
    val directRx: UInt,
    val receiveErrors: UInt = 0u,
)
