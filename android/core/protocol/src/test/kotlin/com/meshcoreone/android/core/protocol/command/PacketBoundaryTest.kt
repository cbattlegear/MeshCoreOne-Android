// PortedFrom: MeshCore/Sources/MeshCore/Protocol/PacketBuilder.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.command

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.AnonRequestType
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import com.meshcoreone.android.core.protocol.model.BinaryRequestType
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.FloodScope
import com.meshcoreone.android.core.protocol.model.MeshContact
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PacketBoundaryTest {
    private val time = Instant.ofEpochSecond(1, 999_999_999)
    private val key = Bytes.of(0x11, 0x22)

    @Test
    fun `Every original public builder overload is present`() {
        val original = PinnedCommandSource.read("MeshCore/Sources/MeshCore/Protocol/PacketBuilder.swift")
        val expected = Regex("public static func ([A-Za-z0-9]+)\\(").findAll(original)
            .map { it.groupValues[1] }.groupingBy { it }.eachCount()
        val actual = Files.readString(PinnedCommandSource.root.resolve(
            "android/core/protocol/src/main/kotlin/com/meshcoreone/android/core/protocol/command/PacketBuilder.kt",
        ))
        val implemented = Regex("^    fun ([A-Za-z0-9]+)\\(", RegexOption.MULTILINE).findAll(actual)
            .map { it.groupValues[1] }.groupingBy { it }.eachCount()
        assertEquals(63, expected.values.sum(), "Pinned builder surface changed")
        assertEquals(expected + ("sendRadioCLICommand" to 1), implemented)
    }

    @Test
    fun `App identifier prefix counts graphemes rather than bytes or UTF16 units`() {
        val prefix = Bytes.of(1, 3, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20)
        assertEquals(prefix, PacketBuilder.appStart(""))
        assertEquals(prefix + Bytes.utf8("abcde"), PacketBuilder.appStart("abcdef"))
        val family = "\uD83D\uDC69\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        val five = "a\u0301" + family + "\uD83C\uDDEC\uD83C\uDDE7" + "\u4E2D" + "z"
        assertEquals(prefix + Bytes.utf8(five), PacketBuilder.appStart(five + "discarded"))
    }

    @Test
    fun `Set name preserves complete UTF8 graphemes at firmware 31-byte boundary`() {
        assertEquals(Bytes.of(8) + repeatedByte(0x41, 31), PacketBuilder.setName("A".repeat(32)))
        assertEquals(
            Bytes.of(8) + repeatedByte(0x41, 28),
            PacketBuilder.setName("A".repeat(28) + "\uD83D\uDE80"),
        )
        assertEquals(
            Bytes.of(8) + repeatedByte(0x41, 29),
            PacketBuilder.setName("A".repeat(29) + "e\u0301"),
        )
    }

    @Test
    fun `Date fields truncate fractional seconds and saturate the full Instant range`() {
        assertEquals(Bytes.of(6, 1, 0, 0, 0), PacketBuilder.setTime(time))
        assertEquals(Bytes.of(6, 0, 0, 0, 0), PacketBuilder.setTime(Instant.MIN))
        assertEquals(Bytes.of(6, 0xff, 0xff, 0xff, 0xff), PacketBuilder.setTime(Instant.MAX))
        assertEquals(Bytes.of(4), PacketBuilder.getContacts())
        assertEquals(Bytes.of(4, 1, 0, 0, 0), PacketBuilder.getContacts(time))
        assertEquals(Bytes.of(4, 0, 0, 0, 0), PacketBuilder.getContacts(Instant.MIN))
    }

    @Test
    fun `Coordinate encoders saturate finite bounds and encode non-finite values as zero`() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(Bytes.of(0x0e) + repeatedByte(0, 12), PacketBuilder.setCoordinates(value, value))
        }
        assertEquals(-90_000_000, PacketBuilder.scaledCoordinate(-100.0, PacketBuilder.LATITUDE_RANGE))
        assertEquals(90_000_000, PacketBuilder.scaledCoordinate(100.0, PacketBuilder.LATITUDE_RANGE))
        assertEquals(-180_000_000, PacketBuilder.scaledCoordinate(-200.0, PacketBuilder.LONGITUDE_RANGE))
        assertEquals(180_000_000, PacketBuilder.scaledCoordinate(200.0, PacketBuilder.LONGITUDE_RANGE))
        assertEquals(-1, PacketBuilder.scaledCoordinate(-0.0000019, PacketBuilder.LATITUDE_RANGE))
        assertEquals(1, PacketBuilder.scaledCoordinate(0.0000019, PacketBuilder.LATITUDE_RANGE))
    }

    @Test
    fun `Radio encoder uses Swift half-away rounding and handles multiplication overflow`() {
        assertEquals(500_001u, PacketBuilder.scaledRadioValue(500.0005, PacketBuilder.FREQUENCY_RANGE_KHZ))
        assertEquals(500_000u, PacketBuilder.scaledRadioValue(500.0004, PacketBuilder.FREQUENCY_RANGE_KHZ))
        assertEquals(2_500_000u, PacketBuilder.scaledRadioValue(Double.MAX_VALUE, PacketBuilder.FREQUENCY_RANGE_KHZ))
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -Double.MAX_VALUE)) {
            assertEquals(150_000u, PacketBuilder.scaledRadioValue(value, PacketBuilder.FREQUENCY_RANGE_KHZ))
        }
    }

    @Test
    fun `Radio false repeat is present zero and low-level SF CR pass through`() {
        assertEquals(
            Bytes.of(0x0b, 0x38, 0xf6, 0x0d, 0, 0x90, 0xd0, 3, 0, 0xff, 0, 0),
            PacketBuilder.setRadio(915.0, 250.0, 0xffu, 0u, false),
        )
    }

    @Test
    fun `Legacy key commands truncate oversized fields without padding short inputs`() {
        val keys: List<Pair<Int, (Bytes) -> Bytes>> = listOf(
            0x0d to { PacketBuilder.resetPath(it) }, 0x0f to { PacketBuilder.removeContact(it) },
            0x10 to { PacketBuilder.shareContact(it) }, 0x11 to { PacketBuilder.exportContact(it) },
            0x1d to { PacketBuilder.sendLogout(it) }, 0x1b to { PacketBuilder.sendStatusRequest(it) },
        )
        for ((code, builder) in keys) {
            assertEquals(Bytes.of(code) + key, builder(key))
            assertEquals(Bytes.of(code) + repeatedByte(0x55, 32), builder(repeatedByte(0x55, 33)))
            assertEquals(Bytes.of(code), builder(Bytes.EMPTY))
        }
        assertEquals(Bytes.of(0x11), PacketBuilder.exportContact())
    }

    @Test
    fun `Login and discovery preserve source prefix behavior`() {
        assertEquals(Bytes.of(0x1a, 0x11, 0x22, 0x70, 0x77), PacketBuilder.sendLogin(key, "pw"))
        assertEquals(Bytes.of(0x34, 0, 0x11, 0x22), PacketBuilder.sendPathDiscovery(key))
        assertEquals(
            Bytes.of(0x27, 0, 0, 0) + repeatedByte(0x55, 32),
            PacketBuilder.getSelfTelemetry(repeatedByte(0x55, 33)),
        )
        assertEquals(Bytes.of(0x27, 0, 0, 0, 0x11, 0x22), PacketBuilder.getSelfTelemetry(key))
    }

    @Test
    fun `Direct messages retain plain type retry prefix and raw UTF8 without hidden truncation`() {
        val text = "\u4E2D" + "x".repeat(200)
        val expected = Bytes.of(2, 0, 0xff, 1, 0, 0, 0, 0x11, 0x22) + Bytes.utf8(text)
        assertEquals(expected, PacketBuilder.sendMessage(key, text, time, 0xffu))
        assertEquals(
            Bytes.of(2, 1, 0, 1, 0, 0, 0, 0x11, 0x22) + Bytes.utf8(text),
            PacketBuilder.sendCommand(key, text, time),
        )
        assertEquals(
            Bytes.of(2, 0, 0, 1, 0, 0, 0) + repeatedByte(0xaa, 6),
            PacketBuilder.sendMessage(repeatedByte(0xaa, 32), "", time),
        )
        assertEquals(
            Bytes.of(3, 0, 0xff, 1, 0, 0, 0), PacketBuilder.sendChannelMessage(0xffu, "", time),
        )
    }

    @Test
    fun `Binary requests cover every original subtype and optional payload`() {
        val originalCodes = listOf(1, 2, 3, 4, 5, 6, 7)
        assertEquals(originalCodes, BinaryRequestType.entries.map { it.rawValue.toInt() })
        for ((type, code) in BinaryRequestType.entries.zip(originalCodes)) {
            assertEquals(Bytes.of(0x32, 0x11, 0x22, code), PacketBuilder.binaryRequest(key, type))
            assertEquals(
                Bytes.of(0x32, 0x11, 0x22, code, 0xaa),
                PacketBuilder.binaryRequest(key, type, Bytes.of(0xaa)),
            )
        }
    }

    @Test
    fun `Channel name is fixed width while the source key only truncates`() {
        assertEquals(
            Bytes.of(0x20, 0xff) + repeatedByte(0x41, 32) + key,
            PacketBuilder.setChannel(0xffu, "A".repeat(33), key),
        )
        assertEquals(
            Bytes.of(0x20, 0) + repeatedByte(0, 32) + repeatedByte(0xbb, 16),
            PacketBuilder.setChannel(0u, "", repeatedByte(0xbb, 17)),
        )
        assertEquals(Bytes.of(0x1f, 0xff), PacketBuilder.getChannel(0xffu))
    }

    @Test
    fun `Contact layout preserves unknown raw type padding and excludes last-modified timestamp`() {
        val contact = MeshContact(
            "test", key, ContactType.CHAT, ContactFlags(0xffu), 0xc1u,
            repeatedByte(0xcc, 65), "x".repeat(33), Instant.ofEpochSecond(1),
            Double.NaN, Double.POSITIVE_INFINITY, Instant.MAX, 0x7fu,
        )
        val expected = Bytes.of(9, 0x11, 0x22) + repeatedByte(0, 30) +
            Bytes.of(0x7f, 0xff, 0xc1) + repeatedByte(0xcc, 64) + repeatedByte(0x78, 32) +
            Bytes.of(1, 0, 0, 0) + repeatedByte(0, 11)
        assertEquals(147, expected.size)
        assertEquals(expected, PacketBuilder.updateContact(contact))
        assertEquals(
            PacketBuilder.updateContact(contact),
            PacketBuilder.updateContact(contact.copy(lastModified = Instant.MIN)),
        )
    }

    @Test
    fun `Tuning and device PIN use full unsigned little-endian words`() {
        assertEquals(
            Bytes.of(0x15, 0x78, 0x56, 0x34, 0x12, 0xff, 0xff, 0xff, 0xff, 0, 0),
            PacketBuilder.setTuning(0x12345678u, UInt.MAX_VALUE),
        )
        assertEquals(Bytes.of(0x25, 0xff, 0xff, 0xff, 0xff), PacketBuilder.setDevicePin(UInt.MAX_VALUE))
    }

    @Test
    fun `Other params exhaustively masks three two-bit telemetry fields and optional ACK byte`() {
        for (environment in 0..255) {
            for (location in 0..3) {
                for (base in 0..3) {
                    val packed = ((environment % 4) * 16) + (location * 4) + base
                    assertEquals(
                        Bytes.of(0x26, 1, packed, 0xff),
                        PacketBuilder.setOtherParams(
                            true, environment.toUByte(), (location + 252).toUByte(),
                            (base + 252).toUByte(), 0xffu,
                        ),
                    )
                }
            }
        }
        assertEquals(
            Bytes.of(0x26, 0, 0, 1, 0),
            PacketBuilder.setOtherParams(false, 0u, 0u, 0u, 1u, 0u),
        )
    }

    @Test
    fun `Auto-add config retains unknown bits and all maximum-hop byte values`() {
        val bits = listOf(
            AutoAddConfig.OVERWRITE_OLDEST_BIT, AutoAddConfig.CONTACTS_BIT, AutoAddConfig.REPEATERS_BIT,
            AutoAddConfig.ROOM_SERVERS_BIT, AutoAddConfig.SENSORS_BIT,
        )
        assertEquals(listOf(1, 2, 4, 8, 16), bits.map { it.toInt() })
        for (hops in 0..255) {
            assertEquals(
                Bytes.of(0x3a, 0xe0, hops),
                PacketBuilder.setAutoAddConfig(AutoAddConfig(0xe0u, hops.toUByte())),
            )
        }
        assertEquals(AutoAddConfig(1u, 0u), AutoAddConfig(1u))
    }

    @Test
    fun `Custom variables and signing raw bytes retain full payload`() {
        assertEquals(Bytes.of(0x28), PacketBuilder.getCustomVars())
        assertEquals(
            Bytes.of(0x29) + Bytes.utf8("a:b:c:d"), PacketBuilder.setCustomVar("a:b", "c:d"),
        )
        assertEquals(Bytes.of(0x18) + key, PacketBuilder.importPrivateKey(key))
        assertEquals(Bytes.of(0x18), PacketBuilder.importPrivateKey(Bytes.EMPTY))
        val raw = repeatedByte(0xfe, 512)
        assertEquals(Bytes.of(0x22) + raw, PacketBuilder.signData(raw))
        assertEquals(Bytes.of(0x22), PacketBuilder.signData(Bytes.EMPTY))
    }

    @Test
    fun `Trace fields are LE and optional path is passed through`() = assertEquals(
        Bytes.of(0x24, 0x78, 0x56, 0x34, 0x12, 0xff, 0xff, 0xff, 0xff, 0x80, 0x11, 0x22),
        PacketBuilder.sendTrace(0x12345678u, UInt.MAX_VALUE, 0x80u, key),
    )

    @Test
    fun `Session flood-scope reset and explicit unscoped commands remain distinct`() {
        assertEquals(Bytes.of(0x36, 0), PacketBuilder.setFloodScope(Bytes.EMPTY))
        assertEquals(Bytes.of(0x36, 1), PacketBuilder.setFloodScopeUnscoped())
        assertEquals(
            Bytes.of(0x36, 0) + repeatedByte(0xaa, 16),
            PacketBuilder.setFloodScope(repeatedByte(0xaa, 17)),
        )
    }

    @Test
    fun `Persisted flood-scope overloads derive keys without changing clear semantics`() {
        assertEquals(
            PacketBuilder.setDefaultFloodScope("scope", Bytes.of(0xaa, 0xbb)),
            PacketBuilder.setDefaultFloodScope("scope", FloodScope.RawKey(Bytes.of(0xaa, 0xbb))),
        )
        assertEquals(
            PacketBuilder.setDefaultFloodScope("scope", Bytes.of(
                0x5f, 0x16, 0x1c, 0x91, 0x49, 0x88, 0x2e, 0x0e, 0x10, 0x12, 0x4b, 0xc5, 0xdd, 0x5c, 0x11, 0xf0,
            )),
            PacketBuilder.setDefaultFloodScope("scope", FloodScope.ChannelName("scope")),
        )
    }

    @Test
    fun `Anonymous requests reverse bytes rather than multi-byte hop groups`() {
        val codes = listOf(1, 2, 3)
        assertEquals(codes, AnonRequestType.entries.map { it.rawValue.toInt() })
        for ((type, code) in AnonRequestType.entries.zip(codes)) {
            assertEquals(
                Bytes.of(0x39, 0x11, 0x22, code, 0x42, 4, 3, 2, 1),
                PacketBuilder.sendAnonReq(key, type, 0x42u, Bytes.of(1, 2, 3, 4)),
            )
        }
    }

    @Test
    fun `All 256 path hash mode input bytes are source-clamped`() {
        for (mode in 0..255) {
            assertEquals(Bytes.of(0x3d, 0, minOf(mode, 2)), PacketBuilder.setPathHashMode(mode.toUByte()))
        }
    }

    @Test
    fun `Control and tagged node discovery preserve optional since and zero explicit tag`() {
        assertEquals(Bytes.of(0x37, 0xff, 0x11, 0x22), PacketBuilder.sendControlData(0xffu, key))
        assertEquals(
            Bytes.of(0x37, 0x81, 0xaa, 0x78, 0x56, 0x34, 0x12),
            PacketBuilder.sendNodeDiscoverRequest(0xaau, tag = 0x12345678u),
        )
        assertEquals(
            Bytes.of(0x37, 0x80, 0xff, 0, 0, 0, 0, 0xff, 0xff, 0xff, 0xff),
            PacketBuilder.sendNodeDiscoverRequest(0xffu, false, 0u, UInt.MAX_VALUE),
        )
    }

    @Test
    fun `Implicit node discovery tag is always a full-range nonzero word`() {
        repeat(100) {
            val result = PacketBuilder.sendNodeDiscoverRequest(0u)
            assertEquals(7, result.size)
            assertEquals(Bytes.of(0x37, 0x81, 0), result.prefix(3))
            assertTrue(result.readUInt32LE(3) != 0u)
        }
    }

    @Test
    fun `Raw data clamp boundaries preserve zero empty and maximum fields independently`() {
        for (pathSize in listOf(0, 1, 63, 64, 65, 255)) {
            for (payloadSize in listOf(0, 1, 183, 184, 185, 256)) {
                assertEquals(
                    Bytes.of(0x19, minOf(pathSize, 64)) +
                        repeatedByte(0xab, minOf(pathSize, 64)) +
                        repeatedByte(0xcd, minOf(payloadSize, 184)),
                    PacketBuilder.sendRawData(repeatedByte(0xab, pathSize), repeatedByte(0xcd, payloadSize)),
                )
            }
        }
    }
}
