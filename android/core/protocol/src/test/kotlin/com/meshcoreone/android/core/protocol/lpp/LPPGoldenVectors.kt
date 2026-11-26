// PortedFrom: MeshCore/Sources/MeshCore/LPP/LPPDecoder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Tests/MeshCoreTests/Fixtures/PythonReferenceBytes.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Hand-calculated extended-type vectors supplement, rather than replace, the pinned Python fixtures.
package com.meshcoreone.android.core.protocol.lpp

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant

internal data class LPPGoldenVector(val point: LPPDataPoint, val hex: String) {
    val bytes: Bytes get() = Bytes.fromHex(hex)
}

internal val lppGoldenVectors = listOf(
    LPPGoldenVector(LPPDataPoint(1u, LPPSensorType.DIGITAL_INPUT, LPPValue.Digital(true)), "010001"),
    LPPGoldenVector(LPPDataPoint(2u, LPPSensorType.DIGITAL_OUTPUT, LPPValue.Digital(false)), "020100"),
    LPPGoldenVector(LPPDataPoint(3u, LPPSensorType.ANALOG_INPUT, LPPValue.Float(-3.3)), "0302feb6"),
    LPPGoldenVector(LPPDataPoint(4u, LPPSensorType.ANALOG_OUTPUT, LPPValue.Float(3.3)), "0403014a"),
    LPPGoldenVector(LPPDataPoint(5u, LPPSensorType.GENERIC_SENSOR, LPPValue.Integer(2_147_483_649L)), "056480000001"),
    LPPGoldenVector(LPPDataPoint(6u, LPPSensorType.ILLUMINANCE, LPPValue.Integer(65_535)), "0665ffff"),
    LPPGoldenVector(LPPDataPoint(7u, LPPSensorType.PRESENCE, LPPValue.Digital(true)), "076601"),
    LPPGoldenVector(LPPDataPoint(8u, LPPSensorType.TEMPERATURE, LPPValue.Float(-10.5)), "0867ff97"),
    LPPGoldenVector(LPPDataPoint(9u, LPPSensorType.HUMIDITY, LPPValue.Float(65.5)), "096883"),
    LPPGoldenVector(LPPDataPoint(10u, LPPSensorType.ACCELEROMETER, LPPValue.Vector3(0.5, -0.5, 1.0)), "0a7101f4fe0c03e8"),
    LPPGoldenVector(LPPDataPoint(11u, LPPSensorType.BAROMETER, LPPValue.Float(1013.2)), "0b732794"),
    LPPGoldenVector(LPPDataPoint(12u, LPPSensorType.VOLTAGE, LPPValue.Float(3.8)), "0c74017c"),
    LPPGoldenVector(LPPDataPoint(13u, LPPSensorType.CURRENT, LPPValue.Float(-0.001)), "0d75ffff"),
    LPPGoldenVector(LPPDataPoint(14u, LPPSensorType.FREQUENCY, LPPValue.Integer(2_271_560_481L)), "0e7687654321"),
    LPPGoldenVector(LPPDataPoint(15u, LPPSensorType.PERCENTAGE, LPPValue.Integer(200)), "0f78c8"),
    LPPGoldenVector(LPPDataPoint(16u, LPPSensorType.ALTITUDE, LPPValue.Float(-123.0)), "1079ff85"),
    LPPGoldenVector(LPPDataPoint(17u, LPPSensorType.LOAD, LPPValue.Float(-1.5)), "117afffa24"),
    LPPGoldenVector(LPPDataPoint(18u, LPPSensorType.CONCENTRATION, LPPValue.Integer(50_000)), "127dc350"),
    LPPGoldenVector(LPPDataPoint(19u, LPPSensorType.POWER, LPPValue.Integer(60_000)), "1380ea60"),
    LPPGoldenVector(LPPDataPoint(20u, LPPSensorType.DISTANCE, LPPValue.Float(2_147_483.648)), "148280000000"),
    LPPGoldenVector(LPPDataPoint(21u, LPPSensorType.ENERGY, LPPValue.Float(4_294_967.295)), "1583ffffffff"),
    LPPGoldenVector(LPPDataPoint(22u, LPPSensorType.DIRECTION, LPPValue.Integer(360)), "16840168"),
    LPPGoldenVector(LPPDataPoint(23u, LPPSensorType.UNIX_TIME, LPPValue.Timestamp(Instant.ofEpochSecond(4_294_967_295L))), "1785ffffffff"),
    LPPGoldenVector(LPPDataPoint(24u, LPPSensorType.GYROMETER, LPPValue.Vector3(10.5, -5.25, 0.0)), "1886041afdf30000"),
    LPPGoldenVector(LPPDataPoint(25u, LPPSensorType.COLOUR, LPPValue.Rgb(128u, 255u, 1u)), "198780ff01"),
    LPPGoldenVector(LPPDataPoint(26u, LPPSensorType.GPS, LPPValue.Gps(37.7749, -122.4194, 10.0)), "1a8805c395ed51fe0003e8"),
    LPPGoldenVector(LPPDataPoint(27u, LPPSensorType.SWITCH_VALUE, LPPValue.Digital(false)), "1b8e00"),
)
