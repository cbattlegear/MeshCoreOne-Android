// PortedFrom: MeshCore/Sources/MeshCore/LPP/LPPDecoder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.lpp

enum class LPPSensorType(
    val rawValue: UByte,
    val dataSize: Int,
    val displayName: String,
    val unit: String = "",
) {
    DIGITAL_INPUT(0u, 1, "Digital Input"),
    DIGITAL_OUTPUT(1u, 1, "Digital Output"),
    ANALOG_INPUT(2u, 2, "Analog Input"),
    ANALOG_OUTPUT(3u, 2, "Analog Output"),
    GENERIC_SENSOR(100u, 4, "Sensor"),
    ILLUMINANCE(101u, 2, "Illuminance", "lux"),
    PRESENCE(102u, 1, "Presence"),
    TEMPERATURE(103u, 2, "Temperature", "\u00b0C"),
    HUMIDITY(104u, 1, "Humidity", "%"),
    ACCELEROMETER(113u, 6, "Accelerometer"),
    BAROMETER(115u, 2, "Pressure", "hPa"),
    VOLTAGE(116u, 2, "Voltage", "V"),
    CURRENT(117u, 2, "Current", "A"),
    FREQUENCY(118u, 4, "Frequency", "Hz"),
    PERCENTAGE(120u, 1, "Percentage", "%"),
    ALTITUDE(121u, 2, "Altitude", "m"),
    LOAD(122u, 3, "Load", "kg"),
    CONCENTRATION(125u, 2, "Concentration", "ppm"),
    POWER(128u, 2, "Power", "W"),
    DISTANCE(130u, 4, "Distance", "m"),
    ENERGY(131u, 4, "Energy", "kWh"),
    DIRECTION(132u, 2, "Direction", "\u00b0"),
    UNIX_TIME(133u, 4, "Time"),
    GYROMETER(134u, 6, "Gyrometer"),
    COLOUR(135u, 3, "Colour"),
    GPS(136u, 9, "GPS"),
    SWITCH_VALUE(142u, 1, "Switch");

    companion object {
        fun fromRawValue(value: UByte): LPPSensorType? = entries.firstOrNull { it.rawValue == value }
        fun fromName(name: String): LPPSensorType? = entries.firstOrNull { it.displayName == name }
    }
}
