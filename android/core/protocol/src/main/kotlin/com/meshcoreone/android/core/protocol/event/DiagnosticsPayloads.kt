// PortedFrom: MeshCore/Sources/MeshCore/Events/DiagnosticsPayloads.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.event

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.decodePathLen

data class TraceInfo(
    val tag: UInt,
    val authCode: UInt,
    val flags: UByte,
    val pathLength: UByte,
    val path: EventList<TraceNode>,
) {
    constructor(tag: UInt, authCode: UInt, flags: UByte, pathLength: UByte, path: Collection<TraceNode>) :
        this(tag, authCode, flags, pathLength, EventList(path))
}

data class TraceNode(val hashBytes: Bytes?, val snr: Double) {
    val hash: UByte? get() = hashBytes?.takeUnless { it.isEmpty }?.get(0)

    companion object {
        fun fromHash(hash: UByte?, snr: Double): TraceNode =
            TraceNode(hash?.let { Bytes.of(it.toInt()) }, snr)
    }
    private val fields get() = arrayOf(hashBytes, snr)
    override fun equals(other: Any?): Boolean = other is TraceNode && eventFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = eventFieldsHash(fields)
}

data class PathInfo(
    val publicKeyPrefix: Bytes,
    val outPathLength: UByte,
    val outPath: Bytes,
    val inPathLength: UByte,
    val inPath: Bytes,
) {
    init {
        require(outPath.size == (decodePathLen(outPathLength)?.byteLength ?: 0)) {
            "Outbound path size does not match its encoded path length"
        }
        require(inPath.size == (decodePathLen(inPathLength)?.byteLength ?: 0)) {
            "Inbound path size does not match its encoded path length"
        }
    }

    val outHopCount: Int? get() = decodePathLen(outPathLength)?.hopCount
    val inHopCount: Int? get() = decodePathLen(inPathLength)?.hopCount
    fun matches(publicKey: Bytes): Boolean =
        !publicKeyPrefix.isEmpty && publicKey.prefix(publicKeyPrefix.size) == publicKeyPrefix
}

data class RawDataInfo(val snr: Double, val rssi: Long, val payload: Bytes) {
    private val fields get() = arrayOf(snr, rssi, payload)
    override fun equals(other: Any?): Boolean = other is RawDataInfo && eventFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = eventFieldsHash(fields)
}
data class LogDataInfo(val snr: Double?, val rssi: Long?, val payload: Bytes) {
    private val fields get() = arrayOf(snr, rssi, payload)
    override fun equals(other: Any?): Boolean = other is LogDataInfo && eventFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = eventFieldsHash(fields)
}
data class ControlDataInfo(
    val snr: Double,
    val rssi: Long,
    val pathLength: UByte,
    val payloadType: UByte,
    val payload: Bytes,
) {
    private val fields get() = arrayOf(snr, rssi, pathLength, payloadType, payload)
    override fun equals(other: Any?): Boolean = other is ControlDataInfo && eventFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = eventFieldsHash(fields)
}
