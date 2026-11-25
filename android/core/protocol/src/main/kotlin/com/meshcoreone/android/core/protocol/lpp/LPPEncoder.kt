// PortedFrom: MeshCore/Sources/MeshCore/LPP/LPPEncoder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Sources/MeshCore/LPP/LPPDecoder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: checked, atomic records and typed encoding for every source decoder value.
package com.meshcoreone.android.core.protocol.lpp

import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.math.truncate

class LPPEncoder {
    private var buffer = ByteWriter()
    val count: Int get() = buffer.size

    fun reset() {
        buffer = ByteWriter()
    }

    fun encode(): Bytes = buffer.toBytes()

    fun addDigitalInput(channel: UByte, value: UByte) =
        add(channel, LPPSensorType.DIGITAL_INPUT, LPPValue.Digital(value != 0.toUByte()))

    fun addDigitalOutput(channel: UByte, value: UByte) =
        add(channel, LPPSensorType.DIGITAL_OUTPUT, LPPValue.Digital(value != 0.toUByte()))

    fun addAnalogInput(channel: UByte, value: Double) =
        add(channel, LPPSensorType.ANALOG_INPUT, LPPValue.Float(value))

    fun addAnalogOutput(channel: UByte, value: Double) =
        add(channel, LPPSensorType.ANALOG_OUTPUT, LPPValue.Float(value))

    fun addTemperature(channel: UByte, celsius: Double) =
        add(channel, LPPSensorType.TEMPERATURE, LPPValue.Float(celsius))

    fun addHumidity(channel: UByte, percent: Double) =
        add(channel, LPPSensorType.HUMIDITY, LPPValue.Float(percent))

    fun addBarometer(channel: UByte, hPa: Double) =
        add(channel, LPPSensorType.BAROMETER, LPPValue.Float(hPa))

    fun addIlluminance(channel: UByte, lux: UShort) =
        add(channel, LPPSensorType.ILLUMINANCE, LPPValue.Integer(lux.toLong()))

    fun addAccelerometer(channel: UByte, x: Double, y: Double, z: Double) =
        add(channel, LPPSensorType.ACCELEROMETER, LPPValue.Vector3(x, y, z))

    fun addGyrometer(channel: UByte, x: Double, y: Double, z: Double) =
        add(channel, LPPSensorType.GYROMETER, LPPValue.Vector3(x, y, z))

    fun addGPS(channel: UByte, latitude: Double, longitude: Double, altitude: Double) =
        add(channel, LPPSensorType.GPS, LPPValue.Gps(latitude, longitude, altitude))

    fun addVoltage(channel: UByte, volts: Double) =
        add(channel, LPPSensorType.VOLTAGE, LPPValue.Float(volts))

    // The source accepts unsigned milliamps, but decodes this field as signed milliamps.
    fun addCurrent(channel: UByte, milliamps: UShort) {
        val data = ByteWriter()
        data.appendBE(milliamps.toLong(), 2)
        addRaw(channel, LPPSensorType.CURRENT, data.toBytes())
    }

    fun addRaw(channel: UByte, type: LPPSensorType, data: Bytes) {
        if (data.size != type.dataSize) throw LPPEncodingException.RawDataSizeMismatch(type, data.size)
        buffer.appendUInt8(channel).appendUInt8(type.rawValue).append(data)
    }

    fun add(channel: UByte, type: LPPSensorType, value: LPPValue) = add(LPPDataPoint(channel, type, value))

    fun add(point: LPPDataPoint) {
        val type = point.type
        val data = ByteWriter()
        when (type) {
            LPPSensorType.DIGITAL_INPUT, LPPSensorType.DIGITAL_OUTPUT,
            LPPSensorType.PRESENCE, LPPSensorType.SWITCH_VALUE -> {
                val value = point.value.requireType<LPPValue.Digital>(type)
                data.appendUInt8(if (value.value) 1u else 0u)
            }
            LPPSensorType.PERCENTAGE, LPPSensorType.ILLUMINANCE, LPPSensorType.CONCENTRATION,
            LPPSensorType.POWER, LPPSensorType.DIRECTION, LPPSensorType.GENERIC_SENSOR,
            LPPSensorType.FREQUENCY -> {
                val value = point.value.requireType<LPPValue.Integer>(type)
                data.appendBE(checkedInteger(type, "value", value.value, unsignedMaximum(type.dataSize)), type.dataSize)
            }
            LPPSensorType.ANALOG_INPUT, LPPSensorType.ANALOG_OUTPUT ->
                data.appendScalar(point, scale = 100.0, signed = true)
            LPPSensorType.TEMPERATURE -> data.appendScalar(point, scale = 10.0, signed = true)
            LPPSensorType.HUMIDITY -> data.appendScalar(point, scale = 2.0, signed = false)
            LPPSensorType.BAROMETER -> data.appendScalar(point, scale = 10.0, signed = false)
            LPPSensorType.VOLTAGE -> data.appendScalar(point, scale = 100.0, signed = false)
            LPPSensorType.CURRENT -> data.appendScalar(point, scale = 1000.0, signed = true)
            LPPSensorType.ALTITUDE -> data.appendScalar(point, scale = 1.0, signed = true)
            LPPSensorType.LOAD -> data.appendScalar(point, scale = 1000.0, signed = true)
            LPPSensorType.DISTANCE, LPPSensorType.ENERGY ->
                data.appendScalar(point, scale = 1000.0, signed = false)
            LPPSensorType.ACCELEROMETER, LPPSensorType.GYROMETER -> {
                val value = point.value.requireType<LPPValue.Vector3>(type)
                val scale = if (type == LPPSensorType.ACCELEROMETER) 1000.0 else 100.0
                data.appendNumber(type, "x", value.x, scale, 2, signed = true)
                data.appendNumber(type, "y", value.y, scale, 2, signed = true)
                data.appendNumber(type, "z", value.z, scale, 2, signed = true)
            }
            LPPSensorType.COLOUR -> {
                val value = point.value.requireType<LPPValue.Rgb>(type)
                data.appendUInt8(value.red).appendUInt8(value.green).appendUInt8(value.blue)
            }
            LPPSensorType.GPS -> {
                val value = point.value.requireType<LPPValue.Gps>(type)
                data.appendNumber(type, "latitude", value.latitude, 10000.0, 3, signed = true)
                data.appendNumber(type, "longitude", value.longitude, 10000.0, 3, signed = true)
                data.appendNumber(type, "altitude", value.altitude, 100.0, 3, signed = true)
            }
            LPPSensorType.UNIX_TIME -> {
                val value = point.value.requireType<LPPValue.Timestamp>(type).value
                if (value.nano != 0) throw LPPEncodingException.SubsecondTimestamp(value)
                data.appendBE(checkedInteger(type, "epochSeconds", value.epochSecond, unsignedMaximum(4)), 4)
            }
        }
        addRaw(point.channel, type, data.toBytes())
    }

    private fun ByteWriter.appendScalar(point: LPPDataPoint, scale: Double, signed: Boolean) {
        val value = point.value.requireType<LPPValue.Float>(point.type)
        appendNumber(point.type, "value", value.value, scale, point.type.dataSize, signed)
    }

    private fun ByteWriter.appendNumber(
        type: LPPSensorType,
        field: String,
        value: Double,
        scale: Double,
        width: Int,
        signed: Boolean,
    ) {
        if (!value.isFinite()) throw LPPEncodingException.NonFiniteValue(type, field, value)
        val minimum = if (signed) -(1L shl (width * 8 - 1)) else 0L
        val maximum = if (signed) (1L shl (width * 8 - 1)) - 1 else unsignedMaximum(width)
        val raw = truncate(value * scale)
        if (raw < minimum.toDouble() || raw > maximum.toDouble()) {
            throw LPPEncodingException.NumericOutOfRange(type, field, value, scale, minimum, maximum)
        }
        appendBE(raw.toLong(), width)
    }

    private fun ByteWriter.appendBE(value: Long, width: Int) {
        for (shift in (width - 1) * 8 downTo 0 step 8) appendUInt8((value ushr shift).toUByte())
    }

    private fun unsignedMaximum(width: Int): Long = (1L shl (width * 8)) - 1

    private fun checkedInteger(type: LPPSensorType, field: String, value: Long, maximum: Long): Long {
        if (value !in 0..maximum) throw LPPEncodingException.IntegerOutOfRange(type, field, value, 0, maximum)
        return value
    }

    private inline fun <reified T : LPPValue> LPPValue.requireType(type: LPPSensorType): T =
        this as? T ?: throw LPPEncodingException.ValueTypeMismatch(type, T::class.java, this)
}
