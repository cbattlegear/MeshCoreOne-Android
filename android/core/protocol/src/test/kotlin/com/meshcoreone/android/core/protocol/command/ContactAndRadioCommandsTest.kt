// PortedFrom: MeshCore/Tests/MeshCoreTests/Protocol/UpdateContactTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Tests/MeshCoreTests/Validation/RoundTripTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.command

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.MeshContact
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ContactAndRadioCommandsTest {
    private val room = MeshContact(
        "test", repeatedByte(0xaa, 32), ContactType.ROOM, ContactFlags(3u), 3u,
        Bytes.of(0x11, 0x22, 0x33), "Node", Instant.ofEpochSecond(1_000),
        10.0, -20.0, Instant.EPOCH,
    )

    @Test
    fun `updateContact produces 147 bytes`() = assertEquals(147, PacketBuilder.updateContact(room).size)

    @Test
    fun `updateContact correct layout`() {
        val expected = Bytes.of(0x09) + repeatedByte(0xaa, 32) + Bytes.of(3, 3, 3) +
            Bytes.of(0x11, 0x22, 0x33) + repeatedByte(0, 61) +
            Bytes.utf8("Node") + repeatedByte(0, 28) +
            Bytes.of(0xe8, 0x03, 0, 0, 0x80, 0x96, 0x98, 0, 0, 0xd3, 0xce, 0xfe, 0, 0, 0)
        assertEquals(expected, PacketBuilder.updateContact(room))
    }

    @Test
    fun `radio CLI uses the firmware CLI command text type`() {
        val destination = Bytes.of(1, 2, 3, 4, 5, 6, 7)
        val timestamp = Instant.ofEpochSecond(0x01020304)
        val expected = Bytes.of(2, 3, 0, 4, 3, 2, 1, 1, 2, 3, 4, 5, 6) + Bytes.utf8("A0|ver")
        assertEquals(expected, PacketBuilder.sendRadioCLICommand(destination, "A0|ver", timestamp))
        assertEquals(1u.toUByte(), PacketBuilder.sendCommand(destination, "A0|ver", timestamp)[1])
    }

    @Test
    fun `updateContact signed path length`() {
        val contact = room.copy(outPathLength = 0xffu, outPath = Bytes.EMPTY)
        assertEquals(0xffu.toUByte(), PacketBuilder.updateContact(contact)[35])
    }

    @Test
    fun `Legacy changeContactFlags encoder seam clamps non-finite coordinates and date`() {
        val contact = room.copy(
            flags = ContactFlags(2u), latitude = Double.NaN, longitude = 200.0,
            lastAdvertisement = Instant.ofEpochSecond(-1),
        )
        val frame = PacketBuilder.updateContact(contact)
        assertEquals(0u, frame.readUInt32LE(132))
        assertEquals(0, frame.readInt32LE(136))
        assertEquals(180_000_000, frame.readInt32LE(140))
    }

    @Test
    fun `setRadio with repeat appends byte`() = assertEquals(
        Bytes.of(0x0b, 0x38, 0xf6, 0x0d, 0, 0x90, 0xd0, 3, 0, 11, 8, 1),
        PacketBuilder.setRadio(915.0, 250.0, 11u, 8u, true),
    )

    @Test
    fun `setRadio without repeat no extra byte`() = assertEquals(
        Bytes.of(0x0b, 0x38, 0xf6, 0x0d, 0, 0x90, 0xd0, 3, 0, 11, 8),
        PacketBuilder.setRadio(915.0, 250.0, 11u, 8u),
    )

    @Test
    fun `setRadio rounds frequency to nearest kHz`() =
        assertEquals(512_002u, PacketBuilder.setRadio(512.002, 250.0, 11u, 8u).readUInt32LE(1))

    @Test
    fun `setRadio clamps out-of-range frequency and bandwidth instead of trapping`() {
        val high = PacketBuilder.setRadio(9_999_999.0, 9_999_999.0, 11u, 8u)
        assertEquals(2_500_000u, high.readUInt32LE(1))
        assertEquals(500_000u, high.readUInt32LE(5))
        val low = PacketBuilder.setRadio(-1.0, Double.NaN, 11u, 8u)
        assertEquals(150_000u, low.readUInt32LE(1))
        assertEquals(7_000u, low.readUInt32LE(5))
    }

    @Test
    fun `setTime saturates out-of-range dates instead of trapping`() {
        assertEquals(
            Bytes.of(6, 0, 0, 0, 0), PacketBuilder.setTime(Instant.ofEpochSecond(-1_000)),
        )
        assertEquals(
            Bytes.of(6, 0xff, 0xff, 0xff, 0xff),
            PacketBuilder.setTime(Instant.ofEpochSecond(UInt.MAX_VALUE.toLong() + 1_000)),
        )
    }

    @Test
    fun `setTxPower positive power packet format`() =
        assertEquals(Bytes.of(0x0c, 0x14), PacketBuilder.setTxPower(20))

    @Test
    fun `setTxPower negative power packet format`() =
        assertEquals(Bytes.of(0x0c, 0xfb), PacketBuilder.setTxPower(-5))

    @Test
    fun `getRepeatFreq packet format`() = assertEquals(Bytes.of(0x3c), PacketBuilder.getRepeatFreq())
}
