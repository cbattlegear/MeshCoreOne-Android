// PortedFrom: MeshCore/Tests/MeshCoreTests/Protocol/NewCommandsTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Tests/MeshCoreTests/Protocol/FactoryResetTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Tests/MeshCoreTests/Protocol/V112ProtocolTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.command

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import kotlin.test.Test
import kotlin.test.assertEquals

internal fun repeatedByte(byte: Int, size: Int): Bytes = Bytes(ByteArray(size) { byte.toByte() })

class NewCommandsTest {
    @Test
    fun `sendRawData format`() = assertEquals(
        Bytes.of(0x19, 2, 0x11, 0x22, 0xaa, 0xbb, 0xcc),
        PacketBuilder.sendRawData(Bytes.of(0x11, 0x22), Bytes.of(0xaa, 0xbb, 0xcc)),
    )

    @Test
    fun `sendRawData empty path`() =
        assertEquals(Bytes.of(0x19, 0, 0xaa), PacketBuilder.sendRawData(Bytes.EMPTY, Bytes.of(0xaa)))

    @Test
    fun `sendRawData clamps to firmware limits`() = assertEquals(
        Bytes.of(0x19, 64) + repeatedByte(0x11, 64) + repeatedByte(0x22, 184),
        PacketBuilder.sendRawData(repeatedByte(0x11, 80), repeatedByte(0x22, 220)),
    )

    @Test
    fun `hasConnection format`() = assertEquals(
        Bytes.of(0x1c) + repeatedByte(0xaa, 32),
        PacketBuilder.hasConnection(repeatedByte(0xaa, 32)),
    )

    @Test
    fun `hasConnection pads short public key to protocol width`() = assertEquals(
        Bytes.of(0x1c) + repeatedByte(0xaa, 31) + Bytes.of(0),
        PacketBuilder.hasConnection(repeatedByte(0xaa, 31)),
    )

    @Test
    fun `getContactByKey format`() = assertEquals(
        Bytes.of(0x1e) + repeatedByte(0xbb, 32),
        PacketBuilder.getContactByKey(repeatedByte(0xbb, 32)),
    )

    @Test
    fun `getContactByKey pads short public key to protocol width`() = assertEquals(
        Bytes.of(0x1e) + repeatedByte(0xbb, 31) + Bytes.of(0),
        PacketBuilder.getContactByKey(repeatedByte(0xbb, 31)),
    )

    @Test
    fun `getAdvertPath format`() = assertEquals(
        Bytes.of(0x2a, 0) + repeatedByte(0xcc, 32),
        PacketBuilder.getAdvertPath(repeatedByte(0xcc, 32)),
    )

    @Test
    fun `getAdvertPath pads short public key to protocol width`() = assertEquals(
        Bytes.of(0x2a, 0) + repeatedByte(0xcc, 31) + Bytes.of(0),
        PacketBuilder.getAdvertPath(repeatedByte(0xcc, 31)),
    )

    @Test
    fun `getTuningParams format`() = assertEquals(Bytes.of(0x2b), PacketBuilder.getTuningParams())

    @Test
    fun `setPathHashMode mode 0 1 and 2 parameter family`() {
        for (mode in 0..2) {
            assertEquals(Bytes.of(0x3d, 0, mode), PacketBuilder.setPathHashMode(mode.toUByte()))
        }
    }

    @Test
    fun `setPathHashMode clamps reserved values to max supported mode`() =
        assertEquals(Bytes.of(0x3d, 0, 2), PacketBuilder.setPathHashMode(3u))

    @Test
    fun `factoryReset includes guard string`() {
        val packet = PacketBuilder.factoryReset()
        assertEquals(6, packet.size)
        assertEquals(0x33u.toUByte(), packet[0])
        assertEquals("reset", packet.slice(1, 6).toByteArray().toString(Charsets.UTF_8))
    }

    @Test
    fun `factoryReset exact bytes`() =
        assertEquals(Bytes.of(0x33, 0x72, 0x65, 0x73, 0x65, 0x74), PacketBuilder.factoryReset())

    @Test
    fun `getAutoAddConfig packet builder`() =
        assertEquals(Bytes.of(0x3b), PacketBuilder.getAutoAddConfig())

    @Test
    fun `setAutoAddConfig packet builder`() = assertEquals(
        Bytes.of(0x3a, 0x0f, 0), PacketBuilder.setAutoAddConfig(AutoAddConfig(0x0fu)),
    )

    @Test
    fun `setAutoAddConfig all bits set`() = assertEquals(
        Bytes.of(0x3a, 0xff, 0), PacketBuilder.setAutoAddConfig(AutoAddConfig(0xffu)),
    )

    @Test
    fun `setAutoAddConfig zero bits`() =
        assertEquals(Bytes.of(0x3a, 0, 0), PacketBuilder.setAutoAddConfig(AutoAddConfig(0u)))

    @Test
    fun `setAutoAddConfig with maxHops`() = assertEquals(
        Bytes.of(0x3a, 0x0f, 3), PacketBuilder.setAutoAddConfig(AutoAddConfig(0x0fu, 3u)),
    )
}
