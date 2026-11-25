// PortedFrom: MeshCore/Sources/MeshCore/Events/DevicePayloads.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.event

import com.meshcoreone.android.core.protocol.bytes.Bytes

data class OwnerInfoResponse(val firmwareVersion: String, val nodeName: String, val ownerInfo: String)

data class StatusResponse(
    val publicKeyPrefix: Bytes,
    val battery: Long,
    val txQueueLength: Long,
    val noiseFloor: Long,
    val lastRSSI: Long,
    val packetsReceived: UInt,
    val packetsSent: UInt,
    val airtime: UInt,
    val uptime: UInt,
    val sentFlood: UInt,
    val sentDirect: UInt,
    val receivedFlood: UInt,
    val receivedDirect: UInt,
    val fullEvents: Long,
    val lastSNR: Double,
    val directDuplicates: Long,
    val floodDuplicates: Long,
    val rxAirtime: UInt,
    val layout: Layout = Layout.REPEATER,
    val receiveErrors: UInt = 0u,
    val roomServerPostedCount: UShort? = null,
    val roomServerPostPushCount: UShort? = null,
) {
    enum class Layout { REPEATER, ROOM_SERVER }

    private val fields get() = arrayOf(
        publicKeyPrefix, battery, txQueueLength, noiseFloor, lastRSSI, packetsReceived, packetsSent, airtime,
        uptime, sentFlood, sentDirect, receivedFlood, receivedDirect, fullEvents, lastSNR, directDuplicates,
        floodDuplicates, rxAirtime, layout, receiveErrors, roomServerPostedCount, roomServerPostPushCount,
    )
    override fun equals(other: Any?): Boolean = other is StatusResponse && eventFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = eventFieldsHash(fields)
}

data class FrequencyRange(val lowerKHz: UInt, val upperKHz: UInt)
data class TuningParamsResponse(val rxDelayBase: Double, val airtimeFactor: Double) {
    private val fields get() = arrayOf(rxDelayBase, airtimeFactor)
    override fun equals(other: Any?): Boolean = other is TuningParamsResponse && eventFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = eventFieldsHash(fields)
}
