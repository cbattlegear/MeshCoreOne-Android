// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer

import com.meshcoreone.android.core.observer.ObserverTestSupport.packet
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PacketHasherTest {
    private val payload = Bytes.fromHex("0102030405060708090a")

    @Test
    fun hashLengthIsEightBytes() {
        assertEquals(8, ObserverConstants.PACKET_HASH_LENGTH)
        assertEquals(8, PacketHasher.compute(5, 0, payload).size)
    }

    // Vectors computed independently with Python hashlib: sha256(bytes([5]) + payload)[:8], etc.
    @Test
    fun nonTraceKnownVector_ignoresPathLength() {
        assertEquals("e8895e5b8009ccbc", PacketHasher.compute(5, 0x00, payload).hexString)
        assertEquals("e8895e5b8009ccbc", PacketHasher.compute(5, 0x42, payload).hexString)
    }

    @Test
    fun traceKnownVector_includesPathLengthByte() {
        assertEquals("dade428dd1094ecb", PacketHasher.compute(9, 0x42, payload).hexString)
        assertNotEquals(PacketHasher.compute(9, 0x42, payload), PacketHasher.compute(9, 0x43, payload))
        assertNotEquals("71f83b8675b4a862", PacketHasher.compute(9, 0x42, payload).hexString)
    }

    @Test
    fun capturedPacketUsesFirmwareHash() {
        val p = ObserverTestSupport.capture(packet(1, 5, payload, pathLenByte = 0x42, path = ByteArray(4)))
        assertEquals("e8895e5b8009ccbc", p.hash.hexString)
        val trace = ObserverTestSupport.capture(packet(2, 9, payload, pathLenByte = 0x42, path = ByteArray(4)))
        assertEquals("dade428dd1094ecb", trace.hash.hexString)
    }

    @Test
    fun hashIsIndependentOfPathContents() {
        val a = ObserverTestSupport.capture(packet(1, 5, payload, pathLenByte = 1, path = byteArrayOf(1)))
        val b = ObserverTestSupport.capture(packet(1, 5, payload, pathLenByte = 2, path = byteArrayOf(1, 2)))
        assertEquals(a.hash, b.hash)
    }
}
