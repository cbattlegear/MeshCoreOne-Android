// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.ParsedRxLogData
import com.meshcoreone.android.core.protocol.model.decodePathLen
import com.meshcoreone.android.core.protocol.parser.PacketParser
import com.meshcoreone.android.core.protocol.parser.RxLogParser

/** Turns PUSH_CODE_LOG_RX_DATA frames / raw packets into [ObservedPacket]s. Never throws on bad input. */
object PacketCapture {
    /** Full frame: [0x88][SNR*4 int8][RSSI int8][raw packet]. Reuses the protocol layer's frame parser. */
    fun fromLogFrame(frame: Bytes): CaptureResult = when (val event = PacketParser.parse(frame)) {
        is MeshEvent.RxLogData -> fromParsed(event.data)
        is MeshEvent.LogData -> CaptureResult.Malformed("Unparseable RF packet")
        else -> CaptureResult.Malformed("Not a LOG_RX_DATA frame")
    }

    /** Raw over-the-air packet with link metrics already split off. */
    fun fromRaw(raw: Bytes, snr: Double?, rssi: Long?): CaptureResult {
        val parsed = RxLogParser.parse(snr, rssi, raw) ?: return CaptureResult.Malformed("Unparseable RF packet")
        return fromParsed(parsed)
    }

    fun fromParsed(parsed: ParsedRxLogData): CaptureResult {
        val length = parsed.pathLength
        val decoded = decodePathLen(length) ?: return CaptureResult.Malformed("Reserved path length")
        if (decoded.byteLength > ObserverConstants.MAX_PATH_BYTES) {
            return CaptureResult.Malformed("Path too long: ${decoded.byteLength}")
        }
        if (parsed.packetPayload.size > ObserverConstants.MAX_PAYLOAD_BYTES) {
            return CaptureResult.Malformed("Payload too long: ${parsed.packetPayload.size}")
        }
        val bits = parsed.payloadTypeBits.toInt()
        val path = Bytes(ByteArray(parsed.pathNodes.size) { parsed.pathNodes[it].toByte() })
        val raw = parsed.rawPayload
        return CaptureResult.Captured(
            ObservedPacket(
                raw = raw,
                header = raw[0].toInt(),
                routeType = parsed.routeType,
                payloadType = parsed.payloadType,
                payloadTypeBits = bits,
                version = parsed.payloadVersion.toInt(),
                transportCodes = parsed.transportCode,
                pathLengthByte = length.toInt(),
                hopCount = decoded.hopCount,
                bytesPerHop = decoded.hashSize,
                path = path,
                payload = parsed.packetPayload,
                hash = PacketHasher.compute(bits, length.toInt(), parsed.packetPayload),
                snr = parsed.snr,
                rssi = parsed.rssi,
            ),
        )
    }
}
