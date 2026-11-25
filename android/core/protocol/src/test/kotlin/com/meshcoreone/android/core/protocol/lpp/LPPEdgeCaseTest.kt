// PortedFrom: MeshCore/Sources/MeshCore/LPP/LPPEncoder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Sources/MeshCore/LPP/LPPDecoder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.lpp

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LPPEdgeCaseTest {
    private data class Scalar(
        val type: LPPSensorType,
        val code: Int,
        val scale: Double,
        val minimum: Long,
        val maximum: Long,
        val minimumHex: String,
        val maximumHex: String,
    )

    private val scalars = listOf(
        Scalar(LPPSensorType.ANALOG_INPUT, 2, 100.0, -32768, 32767, "8000", "7fff"),
        Scalar(LPPSensorType.ANALOG_OUTPUT, 3, 100.0, -32768, 32767, "8000", "7fff"),
        Scalar(LPPSensorType.TEMPERATURE, 103, 10.0, -32768, 32767, "8000", "7fff"),
        Scalar(LPPSensorType.HUMIDITY, 104, 2.0, 0, 255, "00", "ff"),
        Scalar(LPPSensorType.BAROMETER, 115, 10.0, 0, 65535, "0000", "ffff"),
        Scalar(LPPSensorType.VOLTAGE, 116, 100.0, 0, 65535, "0000", "ffff"),
        Scalar(LPPSensorType.CURRENT, 117, 1000.0, -32768, 32767, "8000", "7fff"),
        Scalar(LPPSensorType.ALTITUDE, 121, 1.0, -32768, 32767, "8000", "7fff"),
        Scalar(LPPSensorType.LOAD, 122, 1000.0, -8388608, 8388607, "800000", "7fffff"),
        Scalar(LPPSensorType.DISTANCE, 130, 1000.0, 0, 4_294_967_295L, "00000000", "ffffffff"),
        Scalar(LPPSensorType.ENERGY, 131, 1000.0, 0, 4_294_967_295L, "00000000", "ffffffff"),
    )

    private data class IntegerCase(val type: LPPSensorType, val code: Int, val maximum: Long, val maximumHex: String)

    private val integers = listOf(
        IntegerCase(LPPSensorType.PERCENTAGE, 120, 255, "ff"),
        IntegerCase(LPPSensorType.ILLUMINANCE, 101, 65535, "ffff"),
        IntegerCase(LPPSensorType.CONCENTRATION, 125, 65535, "ffff"),
        IntegerCase(LPPSensorType.POWER, 128, 65535, "ffff"),
        IntegerCase(LPPSensorType.DIRECTION, 132, 65535, "ffff"),
        IntegerCase(LPPSensorType.GENERIC_SENSOR, 100, 4_294_967_295L, "ffffffff"),
        IntegerCase(LPPSensorType.FREQUENCY, 118, 4_294_967_295L, "ffffffff"),
    )

    @TestFactory
    fun `Scalar signed and unsigned extrema decode from independent raw frames`(): List<DynamicTest> =
        scalars.map { row ->
            DynamicTest.dynamicTest("decode scalar bounds ${row.type}") {
                for ((raw, hex) in listOf(row.minimum to row.minimumHex, row.maximum to row.maximumHex)) {
                    val frame = Bytes.of(255, row.code) + Bytes.fromHex(hex)
                    assertEquals(
                        listOf(LPPDataPoint(255u, row.type, LPPValue.Float(raw / row.scale))),
                        LPPDecoder.decodeStrict(frame),
                    )
                }
            }
        }

    @TestFactory
    fun `Scalar extrema encode without saturation or signed narrowing`(): List<DynamicTest> =
        scalars.map { row ->
            DynamicTest.dynamicTest("encode scalar bounds ${row.type}") {
                for ((raw, hex) in listOf(row.minimum to row.minimumHex, row.maximum to row.maximumHex)) {
                    val encoder = LPPEncoder()
                    encoder.add(255u, row.type, LPPValue.Float(raw / row.scale))
                    assertEquals(Bytes.of(255, row.code) + Bytes.fromHex(hex), encoder.encode())
                }
            }
        }

    @TestFactory
    fun `Scalar overflow underflow and finite multiplication overflow reject atomically`(): List<DynamicTest> =
        scalars.map { row ->
            DynamicTest.dynamicTest("reject scalar range ${row.type}") {
                val encoder = prefixedEncoder()
                val original = encoder.encode()
                val invalid = listOf(
                    (row.minimum - 1.5) / row.scale, (row.maximum + 1.5) / row.scale,
                    Double.MAX_VALUE, -Double.MAX_VALUE,
                )
                for (value in invalid) {
                    val error = assertFailsWith<LPPEncodingException.NumericOutOfRange> {
                        encoder.add(0u, row.type, LPPValue.Float(value))
                    }
                    assertEquals(row.type, error.type)
                    assertEquals("value", error.field)
                    assertEquals(value, error.value)
                    assertEquals(row.scale, error.scale)
                    assertEquals(row.minimum, error.minimumRaw)
                    assertEquals(row.maximum, error.maximumRaw)
                    assertEquals(original, encoder.encode())
                    assertEquals(original.size, encoder.count)
                }
            }
        }

    @TestFactory
    fun `All scalar nonfinite inputs have typed failures rather than zero or clamped values`(): List<DynamicTest> =
        scalars.map { row ->
            DynamicTest.dynamicTest("reject nonfinite ${row.type}") {
                val encoder = prefixedEncoder()
                val original = encoder.encode()
                for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                    val error = assertFailsWith<LPPEncodingException.NonFiniteValue> {
                        encoder.add(0u, row.type, LPPValue.Float(value))
                    }
                    assertEquals(row.type, error.type)
                    assertEquals("value", error.field)
                    assertEquals(value.toBits(), error.value.toBits())
                    assertEquals(original, encoder.encode())
                }
            }
        }

    @TestFactory
    fun `Every sensor rejects the wrong associated value class atomically`(): List<DynamicTest> =
        lppGoldenVectors.map { vector ->
            DynamicTest.dynamicTest("reject value kind ${vector.point.type}") {
                val wrong = if (vector.point.value is LPPValue.Digital) LPPValue.Float(0.0) else LPPValue.Digital(true)
                val encoder = prefixedEncoder()
                val original = encoder.encode()
                val error = assertFailsWith<LPPEncodingException.ValueTypeMismatch> {
                    encoder.add(vector.point.copy(value = wrong))
                }
                assertEquals(vector.point.type, error.type)
                assertEquals(vector.point.value.javaClass, error.expected)
                assertEquals(wrong, error.actual)
                assertEquals(original, encoder.encode())
                assertEquals(original.size, encoder.count)
            }
        }

    @TestFactory
    fun `Every raw sensor rejects short and overlong data without appending a header`(): List<DynamicTest> =
        lppGoldenVectors.map { vector ->
            DynamicTest.dynamicTest("reject raw width ${vector.point.type}") {
                val encoder = prefixedEncoder()
                val original = encoder.encode()
                for (length in 0..vector.point.type.dataSize + 1) {
                    if (length == vector.point.type.dataSize) continue
                    val error = assertFailsWith<LPPEncodingException.RawDataSizeMismatch> {
                        encoder.addRaw(0u, vector.point.type, Bytes(ByteArray(length)))
                    }
                    assertEquals(vector.point.type, error.type)
                    assertEquals(vector.point.type.dataSize, error.expected)
                    assertEquals(length, error.actual)
                    assertEquals(original, encoder.encode())
                    assertEquals(original.size, encoder.count)
                }
            }
        }

    @TestFactory
    fun `Integer extrema and out of range Long values preserve unsigned wire semantics`(): List<DynamicTest> =
        integers.map { row ->
            DynamicTest.dynamicTest("integer bounds ${row.type}") {
                val width = row.maximumHex.length / 2
                for ((value, bytes) in listOf(0L to Bytes(ByteArray(width)), row.maximum to Bytes.fromHex(row.maximumHex))) {
                    val point = LPPDataPoint(0u, row.type, LPPValue.Integer(value))
                    val encoder = LPPEncoder()
                    encoder.add(point)
                    val frame = Bytes.of(0, row.code) + bytes
                    assertEquals(frame, encoder.encode())
                    assertEquals(listOf(point), LPPDecoder.decodeStrict(frame))
                }
                val encoder = prefixedEncoder()
                val original = encoder.encode()
                for (value in listOf(-1L, row.maximum + 1, Long.MIN_VALUE, Long.MAX_VALUE)) {
                    val error = assertFailsWith<LPPEncodingException.IntegerOutOfRange> {
                        encoder.add(0u, row.type, LPPValue.Integer(value))
                    }
                    assertEquals(row.type, error.type)
                    assertEquals(value, error.value)
                    assertEquals(0L, error.minimum)
                    assertEquals(row.maximum, error.maximum)
                    assertEquals(original, encoder.encode())
                }
            }
        }

    @Test
    fun `Original analog output method preserves its own type and signed big endian values`() {
        val cases = listOf(-3.3 to "feb6", 3.3 to "014a", -327.68 to "8000", 327.67 to "7fff")
        for ((value, hex) in cases) {
            val encoder = LPPEncoder()
            encoder.addAnalogOutput(255u, value)
            val frame = Bytes.of(255, 3) + Bytes.fromHex(hex)
            assertEquals(frame, encoder.encode())
            assertEquals(listOf(LPPDataPoint(255u, LPPSensorType.ANALOG_OUTPUT, LPPValue.Float(value))), LPPDecoder.decodeStrict(frame))
        }
    }

    @Test
    fun `The original unsigned current method retains the decoder signed high bit behavior`() {
        val cases = listOf(
            Triple(0, "0000", 0.0), Triple(1000, "03e8", 1.0), Triple(32767, "7fff", 32.767),
            Triple(32768, "8000", -32.768), Triple(65535, "ffff", -0.001),
        )
        for ((milliamps, hex, amps) in cases) {
            val encoder = LPPEncoder()
            encoder.addCurrent(255u, milliamps.toUShort())
            val expected = Bytes.of(255, 117) + Bytes.fromHex(hex)
            assertEquals(expected, encoder.encode())
            assertEquals(listOf(LPPDataPoint(255u, LPPSensorType.CURRENT, LPPValue.Float(amps))), LPPDecoder.decodeStrict(expected))
        }
    }

    @TestFactory
    fun `Every nonzero digital wire value is true for each boolean sensor`(): List<DynamicTest> =
        listOf(
            LPPSensorType.DIGITAL_INPUT to 0, LPPSensorType.DIGITAL_OUTPUT to 1,
            LPPSensorType.PRESENCE to 102, LPPSensorType.SWITCH_VALUE to 142,
        ).map { (type, code) ->
            DynamicTest.dynamicTest("boolean raw bytes $type") {
                for (raw in 0..255) {
                    assertEquals(
                        listOf(LPPDataPoint(0u, type, LPPValue.Digital(raw != 0))),
                        LPPDecoder.decodeStrict(Bytes.of(0, code, raw)),
                    )
                }
            }
        }

    @Test
    fun `Original digital input and output methods normalize every unsigned byte exactly`() {
        for (raw in 0..255) {
            val encoder = LPPEncoder()
            encoder.addDigitalInput(0u, raw.toUByte())
            encoder.addDigitalOutput(255u, raw.toUByte())
            val normalized = if (raw == 0) 0 else 1
            assertEquals(Bytes.of(0, 0, normalized, 255, 1, normalized), encoder.encode())
        }
    }

    @TestFactory
    fun `Truncation is toward zero not rounding for every scaled value shape`(): List<DynamicTest> {
        val cases = listOf(
            LPPGoldenVector(LPPDataPoint(0u, LPPSensorType.ANALOG_INPUT, LPPValue.Float(1.999)), "000200c7"),
            LPPGoldenVector(LPPDataPoint(0u, LPPSensorType.ANALOG_OUTPUT, LPPValue.Float(-1.999)), "0003ff39"),
            LPPGoldenVector(LPPDataPoint(0u, LPPSensorType.TEMPERATURE, LPPValue.Float(-0.19)), "0067ffff"),
            LPPGoldenVector(LPPDataPoint(0u, LPPSensorType.HUMIDITY, LPPValue.Float(1.49)), "006802"),
            LPPGoldenVector(LPPDataPoint(0u, LPPSensorType.BAROMETER, LPPValue.Float(1.19)), "0073000b"),
            LPPGoldenVector(LPPDataPoint(0u, LPPSensorType.VOLTAGE, LPPValue.Float(1.019)), "00740065"),
            LPPGoldenVector(LPPDataPoint(0u, LPPSensorType.CURRENT, LPPValue.Float(-0.0019)), "0075ffff"),
            LPPGoldenVector(LPPDataPoint(0u, LPPSensorType.ALTITUDE, LPPValue.Float(-1.9)), "0079ffff"),
            LPPGoldenVector(LPPDataPoint(0u, LPPSensorType.LOAD, LPPValue.Float(-0.0019)), "007affffff"),
            LPPGoldenVector(LPPDataPoint(0u, LPPSensorType.DISTANCE, LPPValue.Float(1.0009)), "0082000003e8"),
            LPPGoldenVector(LPPDataPoint(0u, LPPSensorType.ENERGY, LPPValue.Float(1.0009)), "0083000003e8"),
            LPPGoldenVector(LPPDataPoint(0u, LPPSensorType.ACCELEROMETER, LPPValue.Vector3(1.2349, -1.2349, -0.0009)), "007104d2fb2e0000"),
            LPPGoldenVector(LPPDataPoint(0u, LPPSensorType.GYROMETER, LPPValue.Vector3(1.239, -1.239, -0.009)), "0086007bff850000"),
            LPPGoldenVector(LPPDataPoint(0u, LPPSensorType.GPS, LPPValue.Gps(0.00019, -0.00019, -0.019)), "0088000001ffffffffffff"),
        )
        return cases.map { row ->
            DynamicTest.dynamicTest("truncate ${row.point.type}") {
                val encoder = LPPEncoder()
                encoder.add(row.point)
                assertEquals(row.bytes, encoder.encode())
            }
        }
    }

    @Test
    fun `GPS physical endpoints preserve big endian axes and zero altitude`() {
        val cases = listOf(
            LPPGoldenVector(LPPDataPoint(255u, LPPSensorType.GPS, LPPValue.Gps(90.0, 180.0, 0.0)), "ff880dbba01b7740000000"),
            LPPGoldenVector(LPPDataPoint(0u, LPPSensorType.GPS, LPPValue.Gps(-90.0, -180.0, 0.0)), "0088f24460e488c0000000"),
        )
        for (row in cases) {
            val encoder = LPPEncoder()
            encoder.add(row.point)
            assertEquals(row.bytes, encoder.encode())
            assertEquals(listOf(row.point), LPPDecoder.decodeStrict(row.bytes))
        }
    }

    @Test
    fun `GPS decoding sign extends each full 24-bit axis without geographic clamping`() {
        val frame = Bytes.fromHex("01888000007fffff800000")
        assertEquals(
            listOf(LPPDataPoint(1u, LPPSensorType.GPS, LPPValue.Gps(-838.8608, 838.8607, -83886.08))),
            LPPDecoder.decodeStrict(frame),
        )
    }

    @Test
    fun `GPS encoding preserves IEEE truncation near the widest signed values`() {
        val encoder = LPPEncoder()
        encoder.addGPS(1u, 838.8607, 0.0, 0.0)
        // IEEE multiplication is 8388606.999999999 here, as in the independent Python probe.
        assertEquals(Bytes.fromHex("01887ffffe000000000000"), encoder.encode())
        encoder.reset()
        encoder.addGPS(1u, 838.86075, -838.86085, 83886.075)
        assertEquals(Bytes.fromHex("01887fffff8000007fffff"), encoder.encode())
    }

    @Test
    fun `GPS overflow on each axis rejects instead of wrapping the 24-bit field`() {
        val invalid = listOf(
            "latitude" to LPPValue.Gps(838.861, 0.0, 0.0),
            "latitude" to LPPValue.Gps(-838.861, 0.0, 0.0),
            "longitude" to LPPValue.Gps(0.0, 838.861, 0.0),
            "longitude" to LPPValue.Gps(0.0, -838.861, 0.0),
            "altitude" to LPPValue.Gps(0.0, 0.0, 83886.1),
            "altitude" to LPPValue.Gps(0.0, 0.0, -83886.1),
        )
        val encoder = prefixedEncoder()
        val original = encoder.encode()
        for ((field, value) in invalid) {
            val error = assertFailsWith<LPPEncodingException.NumericOutOfRange> {
                encoder.add(0u, LPPSensorType.GPS, value)
            }
            assertEquals(LPPSensorType.GPS, error.type)
            assertEquals(field, error.field)
            assertEquals(-8_388_608L, error.minimumRaw)
            assertEquals(8_388_607L, error.maximumRaw)
            assertEquals(original, encoder.encode())
        }
    }

    @Test
    fun `Nonfinite GPS axes each report their field and leave the buffer unchanged`() {
        val encoder = prefixedEncoder()
        val original = encoder.encode()
        for (number in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for ((field, value) in listOf(
                "latitude" to LPPValue.Gps(number, 0.0, 0.0),
                "longitude" to LPPValue.Gps(0.0, number, 0.0),
                "altitude" to LPPValue.Gps(0.0, 0.0, number),
            )) {
                val error = assertFailsWith<LPPEncodingException.NonFiniteValue> {
                    encoder.add(0u, LPPSensorType.GPS, value)
                }
                assertEquals(field, error.field)
                assertEquals(LPPSensorType.GPS, error.type)
                assertEquals(original, encoder.encode())
            }
        }
    }

    @TestFactory
    fun `Both motion sensors sign extend all axes and preserve their different scales`(): List<DynamicTest> =
        listOf(
            Triple(LPPSensorType.ACCELEROMETER, 113, LPPValue.Vector3(-32.768, 32.767, -0.001)),
            Triple(LPPSensorType.GYROMETER, 134, LPPValue.Vector3(-327.68, 327.67, -0.01)),
        ).map { (type, code, value) ->
            DynamicTest.dynamicTest("motion signed bounds $type") {
                val frame = Bytes.of(255, code) + Bytes.fromHex("80007fffffff")
                val point = LPPDataPoint(255u, type, value)
                assertEquals(listOf(point), LPPDecoder.decodeStrict(frame))
                val encoder = LPPEncoder()
                encoder.add(point)
                assertEquals(frame, encoder.encode())
            }
        }

    @TestFactory
    fun `Motion errors on each axis remain atomic even after earlier valid axes`(): List<DynamicTest> =
        listOf(LPPSensorType.ACCELEROMETER to 40.0, LPPSensorType.GYROMETER to 400.0).flatMap { (type, large) ->
            listOf("x", "y", "z").map { field ->
                DynamicTest.dynamicTest("reject motion $type $field") {
                    val encoder = prefixedEncoder()
                    val original = encoder.encode()
                    for (number in listOf(large, -large, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                        val value = when (field) {
                            "x" -> LPPValue.Vector3(number, 0.0, 0.0)
                            "y" -> LPPValue.Vector3(0.0, number, 0.0)
                            else -> LPPValue.Vector3(0.0, 0.0, number)
                        }
                        if (number.isFinite()) {
                            val error = assertFailsWith<LPPEncodingException.NumericOutOfRange> { encoder.add(0u, type, value) }
                            assertEquals(field, error.field)
                            assertEquals(type, error.type)
                        } else {
                            val error = assertFailsWith<LPPEncodingException.NonFiniteValue> { encoder.add(0u, type, value) }
                            assertEquals(field, error.field)
                            assertEquals(type, error.type)
                        }
                        assertEquals(original, encoder.encode())
                        assertEquals(original.size, encoder.count)
                    }
                }
            }
        }

    @Test
    fun `Colour uses unsigned red green blue bytes at both extrema`() {
        for ((value, hex) in listOf(LPPValue.Rgb(0u, 0u, 0u) to "000000", LPPValue.Rgb(255u, 255u, 255u) to "ffffff")) {
            val point = LPPDataPoint(255u, LPPSensorType.COLOUR, value)
            val frame = Bytes.of(255, 135) + Bytes.fromHex(hex)
            val encoder = LPPEncoder()
            encoder.add(point)
            assertEquals(frame, encoder.encode())
            assertEquals(listOf(point), LPPDecoder.decodeStrict(frame))
        }
    }

    @Test
    fun `Unix timestamps keep the entire unsigned 32-bit seconds range`() {
        val cases = listOf(0L to "00000000", 2_147_483_647L to "7fffffff", 2_147_483_648L to "80000000", 4_294_967_295L to "ffffffff")
        for ((seconds, hex) in cases) {
            val point = LPPDataPoint(0u, LPPSensorType.UNIX_TIME, LPPValue.Timestamp(Instant.ofEpochSecond(seconds)))
            val frame = Bytes.of(0, 133) + Bytes.fromHex(hex)
            val encoder = LPPEncoder()
            encoder.add(point)
            assertEquals(frame, encoder.encode())
            assertEquals(listOf(point), LPPDecoder.decodeStrict(frame))
        }
    }

    @Test
    fun `Unix timestamp overflow and unsupported fractional seconds reject atomically`() {
        val encoder = prefixedEncoder()
        val original = encoder.encode()
        for (seconds in listOf(-1L, 4_294_967_296L)) {
            val error = assertFailsWith<LPPEncodingException.IntegerOutOfRange> {
                encoder.add(0u, LPPSensorType.UNIX_TIME, LPPValue.Timestamp(Instant.ofEpochSecond(seconds)))
            }
            assertEquals("epochSeconds", error.field)
            assertEquals(seconds, error.value)
            assertEquals(0L, error.minimum)
            assertEquals(4_294_967_295L, error.maximum)
            assertEquals(original, encoder.encode())
        }
        val instant = Instant.ofEpochSecond(1, 1)
        val error = assertFailsWith<LPPEncodingException.SubsecondTimestamp> {
            encoder.add(0u, LPPSensorType.UNIX_TIME, LPPValue.Timestamp(instant))
        }
        assertEquals(instant, error.value)
        assertEquals(original, encoder.encode())
    }

    @Test
    fun `Signed zeros encode canonical zero bits in every floating value shape`() {
        val points = scalars.map { LPPDataPoint(0u, it.type, LPPValue.Float(-0.0)) } + listOf(
            LPPDataPoint(0u, LPPSensorType.ACCELEROMETER, LPPValue.Vector3(-0.0, 0.0, -0.0)),
            LPPDataPoint(0u, LPPSensorType.GYROMETER, LPPValue.Vector3(0.0, -0.0, 0.0)),
            LPPDataPoint(0u, LPPSensorType.GPS, LPPValue.Gps(-0.0, -0.0, -0.0)),
        )
        for (point in points) {
            val encoder = LPPEncoder()
            encoder.add(point)
            assertEquals(Bytes.of(0, point.type.rawValue.toInt()) + Bytes(ByteArray(point.type.dataSize)), encoder.encode())
            assertEquals(listOf(point), LPPDecoder.decodeStrict(encoder.encode()))
        }
    }

    @TestFactory
    fun `Seeded scalar properties supplement independent golden vectors`(): List<DynamicTest> =
        scalars.map { row ->
            DynamicTest.dynamicTest("random scalar ${row.type}") {
                val random = Random(105 + row.code)
                val minimum = maxOf(row.minimum + 1, -1_000_000L)
                val maximum = minOf(row.maximum - 1, 1_000_000L)
                repeat(500) {
                    val input = (random.nextLong(minimum, maximum + 1) + random.nextDouble(-0.9, 0.9)) / row.scale
                    val raw = (input * row.scale).toLong()
                    val encoder = LPPEncoder()
                    encoder.add(0u, row.type, LPPValue.Float(input))
                    val frame = Bytes.of(0, row.code) + jdkBigEndian(raw, row.minimumHex.length / 2)
                    assertEquals(frame, encoder.encode())
                    assertEquals(
                        listOf(LPPDataPoint(0u, row.type, LPPValue.Float(raw / row.scale))),
                        LPPDecoder.decodeStrict(frame),
                    )
                    assertTrue(kotlin.math.abs(assertIs<LPPValue.Float>(LPPDecoder.decodeStrict(frame)[0].value).value - input) < 1.0 / row.scale)
                }
            }
        }

    @TestFactory
    fun `Seeded integer properties compare to an independent JDK big endian writer`(): List<DynamicTest> =
        integers.map { row ->
            DynamicTest.dynamicTest("random integer ${row.type}") {
                val random = Random(1050 + row.code)
                repeat(500) {
                    val raw = random.nextLong(0, row.maximum + 1)
                    val point = LPPDataPoint(255u, row.type, LPPValue.Integer(raw))
                    val encoder = LPPEncoder()
                    encoder.add(point)
                    val frame = Bytes.of(255, row.code) + jdkBigEndian(raw, row.maximumHex.length / 2)
                    assertEquals(frame, encoder.encode())
                    assertEquals(listOf(point), LPPDecoder.decodeStrict(frame))
                }
            }
        }

    private fun prefixedEncoder(): LPPEncoder = LPPEncoder().apply { addTemperature(1u, 25.5) }

    private fun jdkBigEndian(value: Long, width: Int): Bytes {
        val full = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()
        return Bytes(full.copyOfRange(8 - width, 8))
    }
}
