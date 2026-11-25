// PortedFrom: MC1Services/Sources/MC1Services/Models/RxLogEntry.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ParsedRxLogData
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.protocol.model.decodePathLen
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class RxLogEntryDTO(
    val id: UUID,
    val radioId: RadioId,
    val receivedAt: Instant,
    val snr: Double?,
    val rssi: Long?,
    val routeType: RouteType,
    val payloadType: PayloadType,
    val payloadVersion: UByte,
    val transportCode: Bytes?,
    val pathLength: UByte,
    val pathNodes: Bytes,
    val packetPayload: Bytes,
    val rawPayload: Bytes,
    val packetHash: String,
    val channelIndex: UByte? = null,
    val channelName: String? = null,
    val decryptStatus: DecryptStatus = DecryptStatus.NOT_APPLICABLE,
    val fromContactName: String? = null,
    val toContactName: String? = null,
    val senderTimestamp: UInt? = null,
    val regionScope: String? = null,
    val regionScopeMatches: SnapshotList<String> = SnapshotList.empty(),
    val payloadTypeBits: UByte,
    val decodedText: String? = null,
) {
    private val fields get() = arrayOf(
        id, radioId, receivedAt, snr, rssi, routeType, payloadType, payloadVersion, transportCode, pathLength,
        pathNodes, packetPayload, rawPayload, packetHash, channelIndex, channelName, decryptStatus,
        fromContactName, toContactName, senderTimestamp, regionScope, regionScopeMatches, payloadTypeBits, decodedText,
    )
    override fun equals(other: Any?): Boolean = other is RxLogEntryDTO && sourceFieldsEqual(fields, other.fields)

    val pathHashSize: Long get() = decodePathLen(pathLength)?.hashSize?.toLong() ?: 1
    val hopCount: Long get() = decodePathLen(pathLength)?.hopCount?.toLong() ?: 0
    val traceTargetHashes: SnapshotList<Bytes>?
        get() {
            if (payloadType != PayloadType.TRACE || packetPayload.size <= 9) return null
            val hashSize = 1 shl (packetPayload[8].toInt() and 3)
            val bytes = packetPayload.slice(9, packetPayload.size)
            if (bytes.isEmpty || bytes.size % hashSize != 0) return null
            return bytes.pathHops(hashSize.toLong()).map { it.data }.snapshot()
        }
    val senderPrefix: Bytes?
        get() = if (!isFlood && payloadType == PayloadType.TEXT_MESSAGE && packetPayload.size >= 2) packetPayload.slice(1, 2) else null
    val recipientPrefix: Bytes?
        get() = if (!isFlood && payloadType == PayloadType.TEXT_MESSAGE && packetPayload.size >= 2) packetPayload.prefix(1) else null
    val isFlood: Boolean get() = routeType.isFlood
    val routeTypeSimple: String get() = if (isFlood) "FLOOD" else "DIRECT"

    fun snrDisplayString(locale: Locale = Locale.getDefault()): String? = snr?.let {
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(it) + " dB"
    }

    override fun hashCode(): Int = id.hashCode()

    companion object {
        fun fromParsed(
            radioId: RadioId, parsed: ParsedRxLogData,
            id: UUID = UUID.randomUUID(), receivedAt: Instant = Instant.now(),
            channelIndex: UByte? = null, channelName: String? = null,
            decryptStatus: DecryptStatus = DecryptStatus.NOT_APPLICABLE,
            fromContactName: String? = null, toContactName: String? = null,
            senderTimestamp: UInt? = null, regionScope: String? = null,
            regionScopeMatches: SnapshotList<String> = SnapshotList.empty(), decodedText: String? = null,
        ): RxLogEntryDTO = RxLogEntryDTO(
            id, radioId, receivedAt, parsed.snr, parsed.rssi, parsed.routeType, parsed.payloadType,
            parsed.payloadVersion, parsed.transportCode, parsed.pathLength,
            Bytes(ByteArray(parsed.pathNodes.size) { parsed.pathNodes[it].toByte() }),
            parsed.packetPayload, parsed.rawPayload, parsed.packetHash, channelIndex, channelName,
            decryptStatus, fromContactName, toContactName, senderTimestamp, regionScope,
            regionScopeMatches, parsed.payloadTypeBits, decodedText,
        )
    }
}
