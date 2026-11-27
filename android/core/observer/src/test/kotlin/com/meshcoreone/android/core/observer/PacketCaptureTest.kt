// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer

import com.meshcoreone.android.core.observer.ObserverTestSupport.logFrame
import com.meshcoreone.android.core.observer.ObserverTestSupport.packet
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacketCaptureTest {
    private val body = Bytes.of(0xaa, 0xbb, 0xcc)

    private fun captured(raw: Bytes) = assertIs<CaptureResult.Captured>(PacketCapture.fromRaw(raw, 1.0, -90)).packet

    @Test
    fun logFrameCarriesSnrRssiAndPacket() {
        val frame = logFrame(snr4 = -22, rssi = -97, raw = packet(1, 5, body))
        val p = assertIs<CaptureResult.Captured>(PacketCapture.fromLogFrame(frame)).packet
        assertEquals(-5.5, p.snr)
        assertEquals(-97L, p.rssi)
        assertEquals(PayloadType.GROUP_TEXT, p.payloadType)
        assertEquals(body, p.payload)
    }

    @Test
    fun nonLogFrameAndGarbageAreFailures() {
        assertIs<CaptureResult.Malformed>(PacketCapture.fromLogFrame(Bytes.EMPTY))
        assertIs<CaptureResult.Malformed>(PacketCapture.fromLogFrame(Bytes.of(0x00)))
        assertIs<CaptureResult.Malformed>(PacketCapture.fromLogFrame(Bytes.of(0x88, 4, 0xb0)))
    }

    @Test
    fun flood_withoutTransportCodes_zeroHop() {
        val p = captured(packet(1, 4, body))
        assertEquals(RouteType.FLOOD, p.routeType)
        assertNull(p.transportCodes)
        assertEquals(0, p.hopCount)
        assertEquals(0, p.path.size)
        assertEquals(0x11, p.header)
    }

    @Test
    fun transportCodesPresentForRoute0And3() {
        for (route in listOf(0, 3)) {
            val p = captured(packet(route, 5, body))
            assertEquals(Bytes.of(1, 2, 3, 4), p.transportCodes)
            assertEquals(body, p.payload)
        }
        assertNull(captured(packet(2, 5, body)).transportCodes)
    }

    @Test
    fun pathHashSizesOneTwoThree() {
        for (size in 1..3) {
            val hops = 2
            val path = ByteArray(size * hops) { (0x10 + it).toByte() }
            val p = captured(packet(1, 5, body, pathLenByte = ((size - 1) shl 6) or hops, path = path))
            assertEquals(size, p.bytesPerHop)
            assertEquals(hops, p.hopCount)
            assertEquals(Bytes(path), p.path)
            assertEquals(body, p.payload)
        }
    }

    @Test
    fun versionBitsParsed() {
        assertEquals(1, captured(packet(1, 5, body, version = 1)).version)
    }

    @Test
    fun malformedPacketsReturnFailureWithoutThrowing() {
        val cases = listOf(
            Bytes.EMPTY,
            Bytes.of(0x11), // no path length
            Bytes.of(0x10, 1, 2, 3), // transport route, truncated codes
            Bytes.of(0x11, 0xc0), // reserved path-length mode 3
            Bytes.of(0x11, 0x03, 1, 2), // path longer than the packet
            packet(1, 5, body, pathLenByte = 0x40 or 33, path = ByteArray(66)), // 66 path bytes > MAX_PATH_BYTES
            packet(1, 5, Bytes(ByteArray(185))), // payload > MAX_PAYLOAD_BYTES
        )
        cases.forEach { assertIs<CaptureResult.Malformed>(PacketCapture.fromRaw(it, 1.0, 1), it.hexString) }
    }

    @Test
    fun maximumPathAndPayloadAccepted() {
        val p = captured(packet(1, 5, Bytes(ByteArray(184)), pathLenByte = 0x40 or 32, path = ByteArray(64)))
        assertEquals(184, p.payload.size)
        assertTrue(p.path.size == 64)
    }

    @Test
    fun toStringHasNoPayloadBytes() {
        assertTrue("aabbcc" !in captured(packet(1, 5, body)).toString())
    }
}
