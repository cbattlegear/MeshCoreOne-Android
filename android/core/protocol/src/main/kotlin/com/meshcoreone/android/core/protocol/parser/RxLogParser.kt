// PortedFrom: MeshCore/Sources/MeshCore/Protocol/RxLogParser.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.parser

import com.meshcoreone.android.core.protocol.bytes.ByteReader
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ParsedRxLogData
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.protocol.model.decodePathLen

object RxLogParser {
    fun parse(snr: Double?, rssi: Long?, payload: Bytes): ParsedRxLogData? {
        if (payload.isEmpty) return rejected("Empty RF packet")
        val reader = ByteReader(payload)
        val header = reader.readUInt8().toInt()
        val route = checkNotNull(RouteType.fromRawValue((header and 3).toUByte()))
        val typeBits = ((header shr 2) and 15).toUByte()
        val type = PayloadType.fromBits(typeBits)
        val version = ((header shr 6) and 3).toUByte()
        val transportCode = if (route.hasTransportCode) {
            if (reader.remaining < 4) return rejected("RF transport code truncated")
            reader.readBytes(4)
        } else null
        if (reader.remaining < 1) return rejected("RF path length missing")
        val length = reader.readUInt8()
        val size = decodePathLen(length)?.byteLength ?: return rejected("Reserved RF path length: ${length.hexByte()}")
        if (reader.remaining < size) return rejected("RF path truncated: need $size, have ${reader.remaining}")
        val nodes = reader.readBytes(size).toList()
        val body = reader.readBytes(reader.remaining)
        val hasHashes = type == PayloadType.TEXT_MESSAGE && body.size >= 2
        return ParsedRxLogData(
            snr, rssi, payload, route, type, version, typeBits, transportCode, length, nodes, body,
            senderPubkeyPrefix = if (hasHashes) body.slice(1, 2) else null,
            recipientPubkeyPrefix = if (hasHashes) body.slice(0, 1) else null,
        )
    }

    private fun rejected(reason: String): ParsedRxLogData? {
        parserLogger.fine(reason)
        return null
    }
}
