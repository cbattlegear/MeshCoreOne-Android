// PortedFrom: MeshCore/Sources/MeshCore/Protocol/RxLogTypes.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.event

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.sha256

enum class RouteType(val rawValue: UByte, val displayName: String) {
    TC_FLOOD(0u, "TC_FLOOD"), FLOOD(1u, "FLOOD"), DIRECT(2u, "DIRECT"), TC_DIRECT(3u, "TC_DIRECT");

    val hasTransportCode: Boolean get() = this == TC_FLOOD || this == TC_DIRECT
    val isFlood: Boolean get() = this == FLOOD || this == TC_FLOOD

    companion object {
        fun fromRawValue(rawValue: UByte): RouteType? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

enum class PayloadType(val rawValue: UByte, val displayName: String) {
    REQUEST(0u, "REQUEST"), RESPONSE(1u, "RESPONSE"), TEXT_MESSAGE(2u, "TEXT_MSG"),
    ACK(3u, "ACK"), ADVERT(4u, "ADVERT"), GROUP_TEXT(5u, "GROUP_TEXT"), GROUP_DATA(6u, "GROUP_DATA"),
    ANON_REQUEST(7u, "ANON_REQ"), PATH(8u, "PATH"), TRACE(9u, "TRACE"), MULTIPART(10u, "MULTIPART"),
    CONTROL(11u, "CONTROL"), RAW_CUSTOM(15u, "RAW_CUSTOM"), UNKNOWN(255u, "UNKNOWN");

    companion object {
        fun fromRawValue(rawValue: UByte): PayloadType? = entries.firstOrNull { it.rawValue == rawValue }
        fun fromBits(bits: UByte): PayloadType = fromRawValue(bits) ?: UNKNOWN
    }
}

data class ParsedRxLogData(
    val snr: Double?,
    val rssi: Long?,
    val rawPayload: Bytes,
    val routeType: RouteType,
    val payloadType: PayloadType,
    val payloadVersion: UByte,
    val payloadTypeBits: UByte,
    val transportCode: Bytes?,
    val pathLength: UByte,
    val pathNodes: EventList<UByte>,
    val packetPayload: Bytes,
    val senderPubkeyPrefix: Bytes? = null,
    val recipientPubkeyPrefix: Bytes? = null,
) {
    constructor(
        snr: Double?,
        rssi: Long?,
        rawPayload: Bytes,
        routeType: RouteType,
        payloadType: PayloadType,
        payloadVersion: UByte,
        payloadTypeBits: UByte,
        transportCode: Bytes?,
        pathLength: UByte,
        pathNodes: Collection<UByte>,
        packetPayload: Bytes,
        senderPubkeyPrefix: Bytes? = null,
        recipientPubkeyPrefix: Bytes? = null,
    ) : this(
        snr, rssi, rawPayload, routeType, payloadType, payloadVersion, payloadTypeBits, transportCode,
        pathLength, EventList(pathNodes), packetPayload, senderPubkeyPrefix, recipientPubkeyPrefix,
    )

    val packetHash: String = computePacketHash(packetPayload)

    private val fields get() = arrayOf(
        snr, rssi, rawPayload, routeType, payloadType, payloadVersion, payloadTypeBits, transportCode,
        pathLength, pathNodes, packetPayload, senderPubkeyPrefix, recipientPubkeyPrefix,
    )
    override fun equals(other: Any?): Boolean = other is ParsedRxLogData && eventFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = eventFieldsHash(fields)

    companion object {
        fun computePacketHash(packetPayload: Bytes): String = sha256(packetPayload).prefix(8).hexString
    }
}
