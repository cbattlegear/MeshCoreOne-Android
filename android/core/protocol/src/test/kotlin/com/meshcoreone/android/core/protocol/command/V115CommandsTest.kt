// PortedFrom: MeshCore/Tests/MeshCoreTests/Protocol/V115CommandsTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.command

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.FloodScope
import kotlin.test.Test
import kotlin.test.assertEquals

class V115CommandsTest {
    @Test
    fun `sendChannelData flood default pathLength format`() = assertEquals(
        Bytes.of(0x3e, 2, 0xff, 0xff, 0xff, 0xaa, 0xbb, 0xcc),
        PacketBuilder.sendChannelData(2u, 0xffffu, Bytes.of(0xaa, 0xbb, 0xcc)),
    )

    @Test
    fun `sendChannelData flood ignores pathBytes when pathLength is FF`() = assertEquals(
        Bytes.of(0x3e, 0, 0xff, 0xff, 0xff, 0xaa),
        PacketBuilder.sendChannelData(
            0u, 0xffffu, Bytes.of(0xaa), 0xffu, Bytes.of(0xde, 0xad, 0xbe, 0xef),
        ),
    )

    @Test
    fun `sendChannelData direct-path format 1-byte hashes`() = assertEquals(
        Bytes.of(0x3e, 0, 3, 0x11, 0x22, 0x33, 0x34, 0x12, 1, 2),
        PacketBuilder.sendChannelData(0u, 0x1234u, Bytes.of(1, 2), 3u, Bytes.of(0x11, 0x22, 0x33)),
    )

    @Test
    fun `sendChannelData direct-path format 2-byte hashes`() = assertEquals(
        Bytes.of(0x3e, 1, 0x42, 0x11, 0x22, 0x33, 0x44, 0xff, 0xff, 0xaa),
        PacketBuilder.sendChannelData(
            1u, 0xffffu, Bytes.of(0xaa), 0x42u, Bytes.of(0x11, 0x22, 0x33, 0x44),
        ),
    )

    @Test
    fun `sendChannelData passes caller-supplied pathBytes verbatim`() = assertEquals(
        Bytes.of(0x3e, 0, 5, 1, 2, 0xff, 0xff, 0),
        PacketBuilder.sendChannelData(0u, 0xffffu, Bytes.of(0), 5u, Bytes.of(1, 2)),
    )

    @Test
    fun `sendChannelData clamps payload to 163 bytes`() = assertEquals(
        Bytes.of(0x3e, 1, 0xff, 0xff, 0) + repeatedByte(0x55, 163),
        PacketBuilder.sendChannelData(1u, 0x00ffu, repeatedByte(0x55, 200)),
    )

    @Test
    fun `setDefaultFloodScope set format`() = assertEquals(
        Bytes.of(0x3f) + Bytes.utf8("test") + repeatedByte(0, 27) + repeatedByte(0xab, 16),
        PacketBuilder.setDefaultFloodScope("test", repeatedByte(0xab, 16)),
    )

    @Test
    fun `setDefaultFloodScope clear format`() =
        assertEquals(Bytes.of(0x3f), PacketBuilder.setDefaultFloodScope("", Bytes.EMPTY))

    @Test
    fun `setDefaultFloodScope truncates long name to 30 bytes`() = assertEquals(
        Bytes.of(0x3f) + repeatedByte(0x41, 30) + Bytes.of(0) + repeatedByte(0xcd, 16),
        PacketBuilder.setDefaultFloodScope("A".repeat(50), repeatedByte(0xcd, 16)),
    )

    @Test
    fun `setDefaultFloodScope pads short key to 16 bytes`() = assertEquals(
        Bytes.of(0x3f, 0x78) + repeatedByte(0, 30) + Bytes.of(1, 2, 3) + repeatedByte(0, 13),
        PacketBuilder.setDefaultFloodScope("x", Bytes.of(1, 2, 3)),
    )

    @Test
    fun `setDefaultFloodScope treats empty name as clear regardless of key`() =
        assertEquals(Bytes.of(0x3f), PacketBuilder.setDefaultFloodScope("", repeatedByte(0xaa, 16)))

    @Test
    fun `setDefaultFloodScope preserves codepoint boundary when truncating`() = assertEquals(
        Bytes.of(0x3f) + repeatedByte(0x41, 27) + repeatedByte(0, 4) + repeatedByte(1, 16),
        PacketBuilder.setDefaultFloodScope("A".repeat(27) + "\uD83D\uDE80", repeatedByte(1, 16)),
    )

    @Test
    fun `setDefaultFloodScope with disabled scope clears`() =
        assertEquals(Bytes.of(0x3f), PacketBuilder.setDefaultFloodScope("ignored", FloodScope.Disabled))

    @Test
    fun `getDefaultFloodScope format`() = assertEquals(Bytes.of(0x40), PacketBuilder.getDefaultFloodScope())
}
