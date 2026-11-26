// PortedFrom: MeshCore/Sources/MeshCore/LPP/LPPDecoder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: the source's accepted prefix is retained, but malformed suffixes are never silent success.
package com.meshcoreone.android.core.protocol.lpp

import com.meshcoreone.android.core.protocol.bytes.ByteReader
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant

object LPPDecoder {
    fun decode(data: Bytes): LPPDecodeResult {
        val result = mutableListOf<LPPDataPoint>()
        val reader = ByteReader(data)
        while (reader.remaining > 0) {
            val start = reader.position
            if (reader.remaining < 2) {
                return incomplete(data, result, start, LPPDecodeDiagnostic.TruncatedHeader(start, reader.remaining))
            }
            val channel = reader.readUInt8()
            val typeCode = reader.readUInt8()
            val type = LPPSensorType.fromRawValue(typeCode)
                ?: return incomplete(data, result, start, LPPDecodeDiagnostic.UnknownSensorType(start, channel, typeCode))
            if (reader.remaining < type.dataSize) {
                return incomplete(
                    data, result, start,
                    LPPDecodeDiagnostic.TruncatedValue(start, channel, type, reader.remaining),
                )
            }
            result += LPPDataPoint(channel, type, readValue(type, reader))
        }
        return LPPDecodeResult.Complete(result, reader.position)
    }

    fun decodeStrict(data: Bytes): List<LPPDataPoint> = decode(data).requireComplete()

    private fun incomplete(
        data: Bytes,
        result: List<LPPDataPoint>,
        offset: Int,
        diagnostic: LPPDecodeDiagnostic,
    ): LPPDecodeResult.Incomplete = LPPDecodeResult.Incomplete(
        result, offset, data.slice(offset, data.size), diagnostic,
    )

    private fun readValue(type: LPPSensorType, reader: ByteReader): LPPValue = when (type) {
        LPPSensorType.DIGITAL_INPUT, LPPSensorType.DIGITAL_OUTPUT,
        LPPSensorType.PRESENCE, LPPSensorType.SWITCH_VALUE -> LPPValue.Digital(reader.readUInt8() != 0.toUByte())
        LPPSensorType.PERCENTAGE -> LPPValue.Integer(reader.readUInt8().toLong())
        LPPSensorType.HUMIDITY -> LPPValue.Float(reader.readUInt8().toDouble() * 0.5)
        LPPSensorType.TEMPERATURE -> LPPValue.Float(reader.readSignedBE(2) / 10.0)
        LPPSensorType.BAROMETER -> LPPValue.Float(reader.readUnsignedBE(2) / 10.0)
        LPPSensorType.VOLTAGE -> LPPValue.Float(reader.readUnsignedBE(2) / 100.0)
        LPPSensorType.CURRENT -> LPPValue.Float(reader.readSignedBE(2) / 1000.0)
        LPPSensorType.ILLUMINANCE, LPPSensorType.CONCENTRATION,
        LPPSensorType.POWER, LPPSensorType.DIRECTION -> LPPValue.Integer(reader.readUnsignedBE(2))
        LPPSensorType.ALTITUDE -> LPPValue.Float(reader.readSignedBE(2).toDouble())
        LPPSensorType.LOAD -> LPPValue.Float(reader.readSignedBE(3) / 1000.0)
        LPPSensorType.ANALOG_INPUT, LPPSensorType.ANALOG_OUTPUT -> LPPValue.Float(reader.readSignedBE(2) / 100.0)
        LPPSensorType.GENERIC_SENSOR, LPPSensorType.FREQUENCY -> LPPValue.Integer(reader.readUnsignedBE(4))
        LPPSensorType.DISTANCE, LPPSensorType.ENERGY -> LPPValue.Float(reader.readUnsignedBE(4) / 1000.0)
        LPPSensorType.UNIX_TIME -> LPPValue.Timestamp(Instant.ofEpochSecond(reader.readUnsignedBE(4)))
        LPPSensorType.ACCELEROMETER, LPPSensorType.GYROMETER -> {
            val scale = if (type == LPPSensorType.ACCELEROMETER) 1000.0 else 100.0
            LPPValue.Vector3(
                reader.readSignedBE(2) / scale, reader.readSignedBE(2) / scale, reader.readSignedBE(2) / scale,
            )
        }
        LPPSensorType.COLOUR -> LPPValue.Rgb(reader.readUInt8(), reader.readUInt8(), reader.readUInt8())
        LPPSensorType.GPS -> LPPValue.Gps(
            reader.readSignedBE(3) / 10000.0, reader.readSignedBE(3) / 10000.0, reader.readSignedBE(3) / 100.0,
        )
    }

    private fun ByteReader.readUnsignedBE(width: Int): Long {
        var value = 0L
        repeat(width) { value = (value shl 8) or readUInt8().toLong() }
        return value
    }

    private fun ByteReader.readSignedBE(width: Int): Long {
        val shift = 64 - width * 8
        return (readUnsignedBE(width) shl shift) shr shift
    }
}
