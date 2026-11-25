// PortedFrom: MeshCore/Tests/MeshCoreTests/Protocol/DataExtensionsTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/Extensions/DataExtensionsTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.primitives

import com.meshcoreone.android.core.protocol.bytes.ByteReader
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.bytes.InvalidHexException
import com.meshcoreone.android.core.protocol.bytes.PacketBoundsException
import com.meshcoreone.android.core.protocol.bytes.snrValue
import com.meshcoreone.android.core.protocol.bytes.utf8PaddedOrTruncated
import com.meshcoreone.android.core.protocol.bytes.utf8Prefix
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BytesTest {
    @Test
    fun `paddedOrTruncated pads short data`() {
        assertEquals(Bytes.of(1, 2, 3, 0, 0, 0), Bytes.of(1, 2, 3).paddedOrTruncated(6))
    }

    @Test
    fun `paddedOrTruncated truncates long data`() {
        assertEquals(Bytes.of(1, 2, 3), Bytes.of(1, 2, 3, 4, 5, 6).paddedOrTruncated(3))
    }

    @Test
    fun `paddedOrTruncated returns exact size unchanged`() {
        val bytes = Bytes.of(1, 2, 3)
        assertEquals(bytes, bytes.paddedOrTruncated(3))
    }

    @Test
    fun `paddedOrTruncated returns empty for negative length`() {
        assertEquals(Bytes.EMPTY, Bytes.of(1, 2, 3).paddedOrTruncated(-1))
    }

    @Test
    fun `utf8PaddedOrTruncated pads short string`() {
        assertEquals(Bytes.of(0x48, 0x69, 0, 0, 0, 0), "Hi".utf8PaddedOrTruncated(6))
    }

    @Test
    fun `utf8PaddedOrTruncated truncates long string`() {
        assertEquals(Bytes.of(0x48, 0x65, 0x6c, 0x6c, 0x6f), "Hello World".utf8PaddedOrTruncated(5))
    }

    @Test
    fun `appendLittleEndian UInt32`() {
        assertEquals(Bytes.of(0x78, 0x56, 0x34, 0x12), ByteWriter().appendUInt32LE(0x12345678u).toBytes())
    }

    @Test
    fun `appendLittleEndian Int32`() {
        assertEquals(Bytes.of(255, 255, 255, 255), ByteWriter().appendInt32LE(-1).toBytes())
    }

    @Test
    fun `utf8Prefix ASCII unchanged when under limit`() = assertEquals("Hello", "Hello".utf8Prefix(10))

    @Test
    fun `utf8Prefix ASCII truncated at exact limit`() = assertEquals("Hel", "Hello".utf8Prefix(3))

    @Test
    fun `utf8Prefix CJK never splits three-byte characters`() {
        val result = "\u4f60\u597d\u4e16\u754c".utf8Prefix(7)
        assertEquals("\u4f60\u597d", result)
        assertEquals(6, Bytes.utf8(result).size)
    }

    @Test
    fun `utf8Prefix emoji never splits four-byte characters`() {
        val result = "\ud83d\ude00\ud83c\udf89\ud83d\udd25".utf8Prefix(5)
        assertEquals("\ud83d\ude00", result)
        assertEquals(4, Bytes.utf8(result).size)
    }

    @Test
    fun `utf8Prefix exact boundary includes character`() {
        assertEquals("\u4f60\u597d", "\u4f60\u597d".utf8Prefix(6))
    }

    @Test
    fun `utf8Prefix empty string returns empty`() = assertEquals("", "".utf8Prefix(10))

    @Test
    fun `utf8Prefix zero bytes returns empty`() = assertEquals("", "Hello".utf8Prefix(0))

    @Test
    fun `utf8Prefix negative bytes returns empty`() = assertEquals("", "Hello".utf8Prefix(-1))

    @Test
    fun `utf8Prefix mixed ASCII and multibyte`() = assertEquals("Hi", "Hi\u4f60".utf8Prefix(4))

    @Test
    fun `utf8PaddedOrTruncated does not split CJK characters`() {
        val result = "\u4f60\u597d\u4e16\u754c".utf8PaddedOrTruncated(8)
        assertEquals(8, result.size)
        assertEquals(0.toUByte(), result[6])
        assertEquals(0.toUByte(), result[7])
        assertEquals("\u4f60\u597d", result.prefix(6).decodingLongestValidUtf8Prefix())
    }

    @Test
    fun `utf8PaddedOrTruncated does not split emoji`() {
        val result = "\ud83d\ude00\ud83c\udf89".utf8PaddedOrTruncated(6)
        assertEquals(6, result.size)
        assertEquals("\ud83d\ude00", result.prefix(4).decodingLongestValidUtf8Prefix())
        assertEquals(Bytes.of(0, 0), result.slice(4, 6))
    }

    @Test
    fun `Empty data returns empty string`() = assertEquals("", Bytes.EMPTY.uppercaseHexString())

    @Test
    fun `Hex string with no separator`() {
        assertEquals("AABBCCDD", Bytes.of(0xaa, 0xbb, 0xcc, 0xdd).uppercaseHexString())
    }

    @Test
    fun `Hex string with space separator`() {
        assertEquals("AA BB CC DD", Bytes.of(0xaa, 0xbb, 0xcc, 0xdd).uppercaseHexString(" "))
    }

    @Test
    fun `Hex string with custom separator`() {
        assertEquals("AA:BB:CC", Bytes.of(0xaa, 0xbb, 0xcc).uppercaseHexString(":"))
    }

    @Test
    fun `Hex string for single byte`() = assertEquals("0F", Bytes.of(0x0f).uppercaseHexString())

    @Test
    fun `Hex string preserves leading zeros`() = assertEquals("000102", Bytes.of(0, 1, 2).uppercaseHexString())

    @Test
    fun `Init from valid hex string`() {
        assertEquals(Bytes.of(0xaa, 0xbb, 0xcc, 0xdd), parseFormattedHex("AABBCCDD"))
    }

    @Test
    fun `Init from hex string with spaces`() {
        assertEquals(Bytes.of(0xaa, 0xbb, 0xcc, 0xdd), parseFormattedHex("AA BB CC DD"))
    }

    @Test
    fun `Init from lowercase hex string`() {
        assertEquals(Bytes.of(0xaa, 0xbb, 0xcc, 0xdd), parseFormattedHex("aabbccdd"))
    }

    @Test
    fun `Init from mixed case hex string`() {
        assertEquals(Bytes.of(0xaa, 0xbb, 0xcc, 0xdd), parseFormattedHex("AaBbCcDd"))
    }

    @Test
    fun `Init from empty hex string`() = assertEquals(Bytes.EMPTY, parseFormattedHex(""))

    @Test
    fun `Init from odd-length hex string returns nil`() = assertNull(parseFormattedHex("ABC"))

    @Test
    fun `Init filters out non-hex characters`() {
        assertEquals(Bytes.of(0xaa, 0xbb, 0xcc), parseFormattedHex("AA-BB-CC"))
    }

    @Test
    fun `Round-trip preserves data`() {
        val original = Bytes(ByteArray(16) { (it * 0x11).toByte() })
        assertEquals(original, parseFormattedHex(original.uppercaseHexString()))
    }

    @Test
    fun `Round-trip with spaces preserves data`() {
        val original = Bytes.of(0xde, 0xad, 0xbe, 0xef)
        assertEquals(original, parseFormattedHex(original.uppercaseHexString(" ")))
    }

    @Test
    fun `Values remain immutable when caller arrays change`() {
        val source = byteArrayOf(1, 2, 3)
        val bytes = Bytes(source)
        val key = hashMapOf(bytes to "retained")
        source[0] = 100
        bytes.toByteArray()[1] = 99
        assertContentEquals(byteArrayOf(1, 2, 3), bytes.toByteArray())
        assertEquals("retained", key[Bytes.of(1, 2, 3)])
        assertEquals(Bytes.of(1, 2, 3).hashCode(), bytes.hashCode())
        assertEquals("Bytes(size=3)", bytes.toString())
    }

    @Test
    fun `Protocol hexadecimal parsing keeps its original pairwise rule`() {
        assertEquals(Bytes.of(0xab), Bytes.parseHex("ABC"))
        assertEquals(Bytes.of(0xab), Bytes.parseHex("ABz"))
        assertEquals(Bytes.EMPTY, Bytes.parseHex("z"))
        assertNull(Bytes.parseHex("AZ"))
        assertFailsWith<InvalidHexException> { Bytes.fromHex("GG") }
        assertEquals("00abcdff", Bytes.of(0, 0xab, 0xcd, 0xff).hexString)
    }

    @Test
    fun `Pairwise hexadecimal parsing preserves Swift unsigned integer sign rules`() {
        assertEquals(Bytes.of(10), Bytes.parseHex("+A"))
        assertEquals(Bytes.of(0), Bytes.parseHex("-0"))
        assertEquals(Bytes.of(15, 0), Bytes.parseHex("+F00"))
        assertNull(Bytes.parseHex("-1"))
        assertNull(Bytes.parseHex("++"))
        assertNull(Bytes.parseHex("\u00e91"))
        assertEquals(Bytes.of(0xab), Bytes.parseHex("AB\ud83c\uddfa\ud83c\uddf8"))
    }

    @Test
    fun `Little-endian reads preserve signed and high-bit values at nonzero offsets`() {
        val bytes = Bytes.of(0, 0x78, 0x56, 0x34, 0x12, 0xff, 0xff, 0xff, 0xff, 0x80)
        assertEquals(0x12345678u, bytes.readUInt32LE(1))
        assertEquals(0x5678.toUShort(), bytes.readUInt16LE(1))
        assertEquals(UInt.MAX_VALUE, bytes.readUInt32LE(5))
        assertEquals(-1, bytes.readInt32LE(5))
        assertEquals((-1).toShort(), bytes.readInt16LE(5))
        assertEquals(0xffff.toUShort(), bytes.readUInt16LE(7))
        assertEquals(bytes.readUInt16LE(1), bytes.readUInt16(1))
        assertEquals(bytes.readInt16LE(5), bytes.readInt16(5))
        assertEquals(bytes.readUInt32LE(1), bytes.readUInt32(1))
        assertEquals(bytes.readInt32LE(5), bytes.readInt32(5))
    }

    @Test
    fun `Legacy short reads return zero while checked cursors report truncation without advancing`() {
        val short = Bytes.of(1, 2, 3)
        assertEquals(0u, short.readUInt32LE(0))
        assertEquals(0u, short.readUInt32LE(Int.MAX_VALUE))
        assertEquals(0.toUShort(), short.readUInt16LE(2))
        assertFailsWith<IllegalArgumentException> { short.readUInt32LE(-1) }
        val reader = ByteReader(short)
        val failure = assertFailsWith<PacketBoundsException> { reader.readUInt32LE() }
        assertEquals(0, reader.position)
        assertEquals(4, failure.requested)
        assertEquals(3, failure.available)
        assertEquals(1.toUByte(), reader.readUInt8())
        assertEquals(0x0302.toUShort(), reader.readUInt16LE())
        assertEquals(0, reader.remaining)
        assertFailsWith<PacketBoundsException> { reader.readUInt8() }
    }

    @Test
    fun `Writer appends all integer widths and returns independent snapshots`() {
        val writer = ByteWriter()
            .appendUInt8(0xffu)
            .appendInt8((-128).toByte())
            .appendUInt16LE(0xcdefu)
            .appendInt16LE((-2).toShort())
            .appendUInt32LE(UInt.MAX_VALUE)
            .appendInt32LE(Int.MIN_VALUE)
        val snapshot = writer.toBytes()
        assertEquals("ff80efcdfeffffffffff00000080", snapshot.hexString)
        writer.append(Bytes.of(1))
        assertEquals(14, snapshot.size)
        assertEquals(15, writer.size)
        val reader = ByteReader(snapshot)
        assertEquals(0xff.toUByte(), reader.readUInt8())
        assertEquals((-128).toByte(), reader.readInt8())
        assertEquals(0xcdef.toUShort(), reader.readUInt16LE())
        assertEquals((-2).toShort(), reader.readInt16LE())
        assertEquals(UInt.MAX_VALUE, reader.readUInt32LE())
        assertEquals(Int.MIN_VALUE, reader.readInt32LE())
        assertEquals(0, reader.remaining)
    }

    @Test
    fun `UTF-8 byte limits preserve combined graphemes and ZWJ families`() {
        val accent = "e\u0301"
        assertEquals("", accent.utf8Prefix(1))
        assertEquals(accent, accent.utf8Prefix(3))
        val family = "\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67\u200d\ud83d\udc66"
        assertEquals(25, Bytes.utf8(family).size)
        assertEquals("", family.utf8Prefix(24))
        assertEquals(family, family.utf8Prefix(25))
        val flag = "\ud83c\uddfa\ud83c\uddf8"
        assertEquals("", flag.utf8Prefix(4))
        assertEquals(flag, flag.utf8Prefix(8))
    }

    @Test
    fun `Firmware name decoding drops only the malformed tail`() {
        val encoded = Bytes.utf8("Hi\u4f60\ud83d\ude00")
        assertEquals("Hi\u4f60", encoded.prefix(7).decodingLongestValidUtf8Prefix())
        assertEquals("Hi", encoded.prefix(4).decodingLongestValidUtf8Prefix())
        assertEquals("", Bytes.of(0xff, 0x80).decodingLongestValidUtf8Prefix())
        assertEquals("", Bytes.EMPTY.decodingLongestValidUtf8Prefix())
        assertEquals("\u0000", Bytes.of(0).decodingLongestValidUtf8Prefix())
    }

    @Test
    fun `SNR treats unsigned bytes as signed quarter-decibels`() {
        assertEquals(-32.0, 0x80.toUByte().snrValue())
        assertEquals(-0.25, 0xff.toUByte().snrValue())
        assertEquals(31.75, 0x7f.toUByte().snrValue())
        assertEquals(0.0, 0.toByte().snrValue())
    }

    @Test
    fun `Invalid slices byte values and cursor offsets fail explicitly`() {
        assertFailsWith<IllegalArgumentException> { Bytes.of(256) }
        assertFailsWith<IllegalArgumentException> { Bytes.of(-1) }
        assertFailsWith<IllegalArgumentException> { Bytes.of(1).slice(-1, 0) }
        assertFailsWith<IllegalArgumentException> { Bytes.of(1).slice(0, 2) }
        assertFailsWith<IllegalArgumentException> { ByteReader(Bytes.of(1), 2) }
        assertEquals(Bytes.of(1), Bytes.of(1).prefix(Int.MAX_VALUE))
        val iterator = Bytes.of(1).iterator()
        assertTrue(iterator.hasNext())
        assertEquals(1.toUByte(), iterator.next())
        assertFailsWith<NoSuchElementException> { iterator.next() }
    }
}
