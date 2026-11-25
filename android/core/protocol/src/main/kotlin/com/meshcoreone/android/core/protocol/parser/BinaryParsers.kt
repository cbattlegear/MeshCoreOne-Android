// PortedFrom: MeshCore/Sources/MeshCore/Protocol/ACLParser.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/MMAParser.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/NeighboursParser.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: diagnostic decoding retains unconsumed bytes alongside the source-compatible parse APIs.
package com.meshcoreone.android.core.protocol.parser

import com.meshcoreone.android.core.protocol.bytes.ByteReader
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.ACLEntry
import com.meshcoreone.android.core.protocol.event.MMAEntry
import com.meshcoreone.android.core.protocol.event.Neighbour
import com.meshcoreone.android.core.protocol.event.NeighboursResponse
import com.meshcoreone.android.core.protocol.lpp.LPPSensorType

object ACLParser {
    fun parse(data: Bytes): List<ACLEntry> = decode(data).sourcePrefix()

    fun decode(data: Bytes): BinaryListDecodeResult<ACLEntry> {
        val entries = mutableListOf<ACLEntry>()
        val reader = ByteReader(data)
        while (reader.remaining >= 7) {
            val key = reader.readBytes(6)
            val permissions = reader.readUInt8()
            if (key.any { it != 0.toUByte() }) entries += ACLEntry(key, permissions)
        }
        return if (reader.remaining == 0) BinaryListDecodeResult.Complete(entries, reader.position) else {
            BinaryListDecodeResult.Incomplete(
                entries, reader.position, data.slice(reader.position, data.size),
                BinaryParseDiagnostic.TruncatedRecord(reader.position, 7, reader.remaining),
            )
        }
    }
}

object MMAParser {
    fun parse(data: Bytes): List<MMAEntry> = decode(data).sourcePrefix()

    fun decode(data: Bytes): BinaryListDecodeResult<MMAEntry> {
        val entries = mutableListOf<MMAEntry>()
        val reader = ByteReader(data)
        while (reader.remaining > 0) {
            val start = reader.position
            if (reader.remaining < 2) return incomplete(
                data, entries, start, BinaryParseDiagnostic.TruncatedRecord(start, 2, reader.remaining),
            )
            val channel = reader.readUInt8()
            val typeByte = reader.readUInt8()
            val type = LPPSensorType.fromRawValue(typeByte) ?: return incomplete(
                data, entries, start, BinaryParseDiagnostic.UnknownSensorType(start, channel, typeByte),
            )
            if (reader.remaining < type.dataSize * 3) return incomplete(
                data, entries, start,
                BinaryParseDiagnostic.TruncatedRecord(start, 2 + type.dataSize * 3, data.size - start),
            )
            entries += MMAEntry(
                channel, type.displayName, value(type, reader.readBytes(type.dataSize)),
                value(type, reader.readBytes(type.dataSize)), value(type, reader.readBytes(type.dataSize)),
            )
        }
        return BinaryListDecodeResult.Complete(entries, reader.position)
    }

    private fun incomplete(
        data: Bytes,
        entries: List<MMAEntry>,
        start: Int,
        diagnostic: BinaryParseDiagnostic,
    ): BinaryListDecodeResult.Incomplete<MMAEntry> =
        BinaryListDecodeResult.Incomplete(entries, start, data.slice(start, data.size), diagnostic)

    private fun value(type: LPPSensorType, data: Bytes): Double {
        val reader = ByteReader(data)
        return when (type) {
            LPPSensorType.DIGITAL_INPUT, LPPSensorType.DIGITAL_OUTPUT, LPPSensorType.PRESENCE,
            LPPSensorType.SWITCH_VALUE, LPPSensorType.PERCENTAGE -> reader.readUInt8().toDouble()
            LPPSensorType.HUMIDITY -> reader.readUInt8().toDouble() * 0.5
            LPPSensorType.TEMPERATURE -> reader.signedBE(2) / 10.0
            LPPSensorType.BAROMETER -> reader.unsignedBE(2) / 10.0
            LPPSensorType.VOLTAGE -> reader.unsignedBE(2) / 100.0
            // MMA current is unsigned in the source; regular LPP current is signed.
            LPPSensorType.CURRENT -> reader.unsignedBE(2) / 1000.0
            LPPSensorType.ILLUMINANCE, LPPSensorType.CONCENTRATION,
            LPPSensorType.POWER, LPPSensorType.DIRECTION -> reader.unsignedBE(2).toDouble()
            LPPSensorType.ALTITUDE -> reader.signedBE(2).toDouble()
            LPPSensorType.LOAD -> reader.signedBE(3) / 1000.0
            LPPSensorType.ANALOG_INPUT, LPPSensorType.ANALOG_OUTPUT -> reader.signedBE(2) / 100.0
            LPPSensorType.GENERIC_SENSOR, LPPSensorType.FREQUENCY, LPPSensorType.UNIX_TIME -> reader.unsignedBE(4).toDouble()
            LPPSensorType.DISTANCE, LPPSensorType.ENERGY -> reader.unsignedBE(4) / 1000.0
            LPPSensorType.ACCELEROMETER -> reader.signedBE(2) / 1000.0
            // Even colour/GPS use the first signed Int16 / 100 in the pinned MMA parser.
            LPPSensorType.GYROMETER, LPPSensorType.COLOUR, LPPSensorType.GPS -> reader.signedBE(2) / 100.0
        }
    }

    private fun ByteReader.unsignedBE(width: Int): Long {
        var value = 0L
        repeat(width) { value = (value shl 8) or readUInt8().toLong() }
        return value
    }

    private fun ByteReader.signedBE(width: Int): Long {
        val shift = 64 - width * 8
        return (unsignedBE(width) shl shift) shr shift
    }
}

data class NeighboursDecodeResult(
    val response: NeighboursResponse,
    val bytesConsumed: Int,
    val remainingData: Bytes,
    val diagnostic: BinaryParseDiagnostic?,
) {
    fun requireComplete(): NeighboursResponse {
        if (diagnostic != null) throw BinaryParseException(diagnostic)
        return response
    }
}

object NeighboursParser {
    fun parse(data: Bytes, publicKeyPrefix: Bytes, tag: Bytes, prefixLength: Int = 4): NeighboursResponse {
        val result = decode(data, publicKeyPrefix, tag, prefixLength)
        if (result.diagnostic != null) {
            parserLogger.warning("Neighbours response: ${result.diagnostic}; preserving the source prefix/default")
        }
        return result.response
    }

    fun decode(data: Bytes, publicKeyPrefix: Bytes, tag: Bytes, prefixLength: Int = 4): NeighboursDecodeResult {
        if (prefixLength !in 0..255) throw MeshCoreException.InvalidInput("Neighbour prefix length must fit the request's UInt8")
        if (data.size < 4) return NeighboursDecodeResult(
            NeighboursResponse(publicKeyPrefix, tag, 0, emptyList()), 0, data,
            BinaryParseDiagnostic.TruncatedRecord(0, 4, data.size),
        )
        val reader = ByteReader(data)
        val total = reader.readInt16LE().toLong()
        val count = reader.readInt16LE()
        if (count < 0) throw BinaryParseException(BinaryParseDiagnostic.NegativeResultsCount(2, count))
        val neighbours = mutableListOf<Neighbour>()
        val entrySize = prefixLength + 5
        repeat(count.toInt()) {
            if (reader.remaining < entrySize) return NeighboursDecodeResult(
                NeighboursResponse(publicKeyPrefix, tag, total, neighbours), reader.position,
                data.slice(reader.position, data.size),
                BinaryParseDiagnostic.TruncatedRecord(reader.position, entrySize, reader.remaining),
            )
            neighbours += Neighbour(reader.readBytes(prefixLength), reader.readInt32LE().toLong(), reader.readInt8().toDouble() / 4.0)
        }
        return NeighboursDecodeResult(
            NeighboursResponse(publicKeyPrefix, tag, total, neighbours), reader.position,
            data.slice(reader.position, data.size), null,
        )
    }
}
