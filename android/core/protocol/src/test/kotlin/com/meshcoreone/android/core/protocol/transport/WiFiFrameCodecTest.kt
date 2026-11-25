// PortedFrom: MeshCore/Tests/MeshCoreTests/Transport/WiFiFrameCodecTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Tests/MeshCoreTests/Fixtures/PythonReferenceBytes.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.transport

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiFrameCodec
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiFrameDecoder
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiFrameException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WiFiFrameCodecTest {
    @Test
    fun `Encodes frame with correct delimiter and length`() {
        assertEquals(Bytes.fromHex("3c0300010203"), WiFiFrameCodec.encode(Bytes.fromHex("010203")))
        assertEquals(0x3cu, WiFiFrameCodec.outboundDelimiter)
        assertEquals(0x3eu, WiFiFrameCodec.inboundDelimiter)
        assertEquals(3, WiFiFrameCodec.headerSize)
    }

    @Test
    fun `Encodes empty frame`() {
        assertEquals(Bytes.fromHex("3c0000"), WiFiFrameCodec.encode(Bytes.EMPTY))
    }

    @Test
    fun `Decodes single complete frame`() {
        val decoder = WiFiFrameDecoder()
        assertEquals(listOf(Bytes.fromHex("010203")), decoder.decode(Bytes.fromHex("3e0300010203")))
        assertEquals(0, decoder.bufferedByteCount)
        decoder.finish()
    }

    @Test
    fun `Decodes multiple frames in one chunk`() {
        assertEquals(
            listOf(Bytes.fromHex("aabb"), Bytes.fromHex("cc")),
            WiFiFrameDecoder().decode(Bytes.fromHex("3e0200aabb3e0100cc")),
        )
    }

    @Test
    fun `Buffers incomplete frame`() {
        val decoder = WiFiFrameDecoder()
        assertTrue(decoder.decode(Bytes.fromHex("3e0500")).isEmpty())
        assertEquals(3, decoder.bufferedByteCount)
        assertTrue(decoder.decode(Bytes.fromHex("0102")).isEmpty())
        assertEquals(5, decoder.bufferedByteCount)
        assertEquals(listOf(Bytes.fromHex("0102030405")), decoder.decode(Bytes.fromHex("030405")))
    }

    @Test
    fun `Handles frame split across chunks`() {
        val decoder = WiFiFrameDecoder()
        assertTrue(decoder.decode(Bytes.fromHex("3e")).isEmpty())
        assertTrue(decoder.decode(Bytes.fromHex("0300aa")).isEmpty())
        assertEquals(listOf(Bytes.fromHex("aabbcc")), decoder.decode(Bytes.fromHex("bbcc")))
    }

    @Test
    fun `Pinned Python device query payload has independently specified outbound frame`() {
        assertEquals(Bytes.fromHex("3c02001603"), WiFiFrameCodec.encode(Bytes.fromHex("1603")))
    }

    @Test
    fun `All splits of pinned inbound multi-frame vector retain exact ordered payloads`() {
        val wire = Bytes.fromHex("3e03000102033e00003e040080ff3e3c")
        val expected = listOf(Bytes.fromHex("010203"), Bytes.EMPTY, Bytes.fromHex("80ff3e3c"))
        for (split in 0..wire.size) {
            val decoder = WiFiFrameDecoder()
            val result = decoder.decode(wire.prefix(split)) + decoder.decode(wire.slice(split, wire.size))
            assertEquals(expected, result, "split=$split")
            assertEquals(0, decoder.bufferedByteCount)
            decoder.finish()
        }
    }

    @Test
    fun `Byte by byte input preserves empty repeated high-bit and delimiter payloads`() {
        val decoder = WiFiFrameDecoder()
        val wire = Bytes.fromHex("3e00003e020080ff3e020080ff3e02003e3c")
        val actual = wire.flatMap { decoder.decode(Bytes.of(it.toInt())) }
        assertEquals(
            listOf(Bytes.EMPTY, Bytes.fromHex("80ff"), Bytes.fromHex("80ff"), Bytes.fromHex("3e3c")),
            actual,
        )
    }

    @Test
    fun `Lengths use unsigned little endian at byte and high-bit boundaries`() {
        for ((size, prefix) in listOf(
            0 to "3c0000", 1 to "3c0100", 255 to "3cff00", 256 to "3c0001",
            32768 to "3c0080", 65535 to "3cffff",
        )) {
            val payload = Bytes(ByteArray(size) { 0x80.toByte() })
            val encoded = WiFiFrameCodec.encode(payload)
            assertEquals(Bytes.fromHex(prefix), encoded.prefix(3), "size=$size")
            assertEquals(size + 3, encoded.size)
            assertEquals(payload, encoded.slice(3, encoded.size))
        }
    }

    @Test
    fun `Maximum inbound length is valid and partial state never exceeds wire bound`() {
        val decoder = WiFiFrameDecoder()
        assertTrue(decoder.decode(Bytes.fromHex("3effff")).isEmpty())
        val payload = Bytes(ByteArray(65535) { 0xff.toByte() })
        for (offset in 0 until payload.size - 1) {
            assertTrue(decoder.decode(Bytes.of(255)).isEmpty())
            assertEquals(4 + offset, decoder.bufferedByteCount)
            assertTrue(decoder.bufferedByteCount <= 65538)
        }
        assertEquals(listOf(payload), decoder.decode(Bytes.of(255)))
        assertEquals(0, decoder.bufferedByteCount)
    }

    @Test
    fun `Oversized outbound payload is rejected without truncating or wrapping length`() {
        for (size in listOf(65536, 65537, 1_048_576)) {
            val failure = assertFailsWith<WiFiFrameException.PayloadTooLarge> {
                WiFiFrameCodec.encode(Bytes(ByteArray(size)))
            }
            assertEquals(size, failure.actualSize)
            assertEquals(65535, failure.maximumSize)
        }
    }

    @Test
    fun `Source delimiter resynchronization records discarded noise without buffering it`() {
        val decoder = WiFiFrameDecoder()
        assertTrue(decoder.decode(Bytes(ByteArray(1_048_576) { 0x3c })).isEmpty())
        assertEquals(1_048_576L, decoder.discardedByteCount)
        assertEquals(0, decoder.bufferedByteCount)
        assertEquals(listOf(Bytes.of(0xfe)), decoder.decode(Bytes.fromHex("ff003e0100fe")))
        assertEquals(1_048_578L, decoder.discardedByteCount)
        decoder.finish()
    }

    @Test
    fun `Empty decode does not consume or clear a partial frame`() {
        val decoder = WiFiFrameDecoder()
        decoder.decode(Bytes.fromHex("3e0200aa"))
        assertTrue(decoder.decode(Bytes.EMPTY).isEmpty())
        assertEquals(4, decoder.bufferedByteCount)
        assertEquals(listOf(Bytes.fromHex("aabb")), decoder.decode(Bytes.of(0xbb)))
    }

    @Test
    fun `EOF distinguishes incomplete header from incomplete payload`() {
        for (wire in listOf("3e", "3e02")) {
            val decoder = WiFiFrameDecoder()
            decoder.decode(Bytes.fromHex(wire))
            val failure = assertFailsWith<WiFiFrameException.TruncatedFrame> { decoder.finish() }
            assertEquals(wire.length / 2, failure.bufferedBytes)
            assertNull(failure.expectedBytes)
        }
        val decoder = WiFiFrameDecoder()
        decoder.decode(Bytes.fromHex("3e0500aabb"))
        val failure = assertFailsWith<WiFiFrameException.TruncatedFrame> { decoder.finish() }
        assertEquals(5, failure.bufferedBytes)
        assertEquals(8, failure.expectedBytes)
    }

    @Test
    fun `Complete frames preceding a truncated suffix are returned unchanged`() {
        val decoder = WiFiFrameDecoder()
        assertEquals(listOf(Bytes.fromHex("fe80")), decoder.decode(Bytes.fromHex("3e0200fe803e040001")))
        assertEquals(4, decoder.bufferedByteCount)
        assertFailsWith<WiFiFrameException.TruncatedFrame> { decoder.finish() }
    }

    @Test
    fun `Reset discards only the old generation partial frame and diagnostics`() {
        val decoder = WiFiFrameDecoder()
        decoder.decode(Bytes.fromHex("003e0500aa"))
        decoder.reset()
        assertEquals(0, decoder.bufferedByteCount)
        assertEquals(0, decoder.discardedByteCount)
        decoder.finish()
        assertEquals(listOf(Bytes.of(0xcc)), decoder.decode(Bytes.fromHex("3e0100cc")))
    }
}
