// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer

import com.meshcoreone.android.core.observer.ObserverTestSupport.capture
import com.meshcoreone.android.core.observer.ObserverTestSupport.channelPayload
import com.meshcoreone.android.core.observer.ObserverTestSupport.hashtagKey
import com.meshcoreone.android.core.observer.ObserverTestSupport.packet
import com.meshcoreone.android.core.observer.ObserverTestSupport.slot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PrivacyFilterTest {
    private val publicKey = ObserverConstants.PUBLIC_CHANNEL_KEY
    private val testKey = hashtagKey("#test")
    private val randomKey = Bytes.fromHex("000102030405060708090a0b0c0d0e0f")
    private val slots = listOf(slot(0, "Public", publicKey), slot(1, "#test", testKey), slot(2, "#other", hashtagKey("#other")))
    private val filter = PrivacyFilter(ChannelAllowList.from(slots))
    private val opaque = Bytes.fromHex("deadbeefcafef00d1122334455667788")

    private fun run(type: Int, payload: Bytes, f: PrivacyFilter = filter) =
        f.filter(capture(packet(1, type, payload, pathLenByte = 2, path = byteArrayOf(9, 8))))

    @Test
    fun knownVectors() {
        assertEquals("9cd8fcf22a47333b591d96a2b848b73f", testKey.hexString)
        assertEquals(0x11, ChannelAllowList.from(emptyList()).let { AllowedChannel("Public", publicKey).hashByte })
        assertEquals(0xd9, AllowedChannel("#test", testKey).hashByte)
    }

    @Test
    fun publicTypesAreFull() {
        for (type in listOf(0x04, 0x09, 0x08, 0x03, 0x0b)) {
            val r = assertIs<ObserverRecord.Full>(run(type, opaque), "type $type")
            assertEquals(null, r.channel)
        }
    }

    @Test
    fun publicChannelMessageIsFull() {
        val r = assertIs<ObserverRecord.Full>(run(0x05, channelPayload(publicKey)))
        assertEquals("Public", r.channel)
    }

    @Test
    fun hashtagChannelMessageIsFull_forGroupTextAndData() {
        for (type in listOf(0x05, 0x06)) {
            val r = assertIs<ObserverRecord.Full>(run(type, channelPayload(testKey)))
            assertEquals("#test", r.channel)
        }
    }

    @Test
    fun fullCarriesRawPacketHashAndMetrics() {
        val raw = packet(1, 0x05, channelPayload(publicKey))
        val p = capture(raw)
        val r = assertIs<ObserverRecord.Full>(filter.filter(p))
        assertEquals(raw, r.raw)
        assertEquals(p.hash, r.hash)
        assertEquals(5.0, r.snr)
        assertEquals(-80L, r.rssi)
        assertTrue("Full" in r.toString() && "deadbeef" !in r.toString())
    }

    @Test
    fun privateChannelNamedLikeHashtagWithRandomKeyIsMetaOnly() {
        val bogus = PrivacyFilter(ChannelAllowList.from(listOf(slot(1, "#test", randomKey))))
        assertIs<ObserverRecord.MetaOnly>(run(0x05, channelPayload(randomKey), bogus))
        assertEquals(listOf("Public"), ChannelAllowList.from(listOf(slot(1, "#test", randomKey))).names)
    }

    @Test
    fun hashtagSlotsAreAllowListed() {
        assertEquals(listOf("Public", "#test", "#other"), ChannelAllowList.from(slots).names)
        assertEquals(listOf("Public"), ChannelAllowList.publicOnly.names)
    }

    @Test
    fun nonHashtagNamesWithDerivedKeysAreNotAllowListed() {
        val list = ChannelAllowList.from(listOf(slot(1, "test", hashtagKey("test")), slot(2, "#", hashtagKey("#"))))
        assertEquals(listOf("Public"), list.names)
    }

    @Test
    fun privateChannelWithCollidingOneByteHashFailsMac() {
        // Find a random key whose channel hash equals #test's (0xd9).
        val colliding = (0 until 100_000).asSequence()
            .map { Bytes(ObserverTestSupport.sha256(byteArrayOf(it.toByte(), (it shr 8).toByte(), (it shr 16).toByte())).copyOf(16)) }
            .first { ObserverTestSupport.sha256(it.toByteArray())[0] == 0xd9.toByte() && it != testKey }
        val payload = channelPayload(colliding)
        assertEquals(0xd9.toByte(), payload.toByteArray()[0])
        assertIs<ObserverRecord.MetaOnly>(run(0x05, payload))
        assertIs<ObserverRecord.MetaOnly>(run(0x06, payload))
    }

    @Test
    fun truncatedOrMisalignedChannelPayloadIsMetaOnly() {
        val good = channelPayload(publicKey).toByteArray()
        assertIs<ObserverRecord.MetaOnly>(run(0x05, Bytes(good.copyOf(10))))
        assertIs<ObserverRecord.MetaOnly>(run(0x05, Bytes(good.copyOf(good.size - 1))))
        assertIs<ObserverRecord.MetaOnly>(run(0x05, Bytes.EMPTY))
    }

    @Test
    fun tamperedCiphertextFailsMac() {
        val bytes = channelPayload(publicKey).toByteArray()
        bytes[bytes.size - 1] = (bytes.last() + 1).toByte()
        assertIs<ObserverRecord.MetaOnly>(run(0x05, Bytes(bytes)))
    }

    @Test
    fun privateTypesAreMetaOnly() {
        // TXT_MSG, REQ, RESPONSE, ANON_REQ, MULTIPART, RAW_CUSTOM, reserved/unknown 0x0c
        for (type in listOf(0x02, 0x00, 0x01, 0x07, 0x0a, 0x0f, 0x0c)) {
            assertIs<ObserverRecord.MetaOnly>(run(type, opaque), "type $type")
        }
    }

    @Test
    fun directMessageAndLoginRequestAreMetaOnly_evenIfPayloadLooksLikeAChannel() {
        assertIs<ObserverRecord.MetaOnly>(run(0x02, channelPayload(publicKey)))
        assertIs<ObserverRecord.MetaOnly>(run(0x07, channelPayload(testKey)))
        assertIs<ObserverRecord.MetaOnly>(run(0x00, channelPayload(testKey)))
    }

    @Test
    fun metaOnlyContainsNoPayloadBytes() {
        val payload = Bytes.fromHex("feedfacecafebabe0102030405060708")
        val p = capture(packet(0, 0x02, payload, pathLenByte = 2, path = byteArrayOf(9, 8)))
        val m = assertIs<ObserverRecord.MetaOnly>(filter.filter(p))
        assertEquals(p.header, m.header)
        assertEquals(Bytes.of(9, 8), m.path)
        assertEquals(p.raw.size, m.packetLength)
        assertEquals(p.hash, m.hash)
        assertFalse(payload.hexString in m.toString())
        // Structural check: no field of MetaOnly can hold the payload, transport codes or raw packet.
        val needle = payload.toByteArray()
        for (field in ObserverRecord.MetaOnly::class.java.declaredFields) {
            field.isAccessible = true
            val v = field.get(m)
            if (v is Bytes) assertFalse(contains(v.toByteArray(), needle.copyOf(4)), field.name)
            assertFalse(field.type.name == "[B" || field.name.contains("payload") || field.name.contains("raw"), field.name)
        }
        val fieldNames = ObserverRecord.MetaOnly::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(setOf("header", "pathLengthByte", "path", "packetLength", "hash", "snr", "rssi"), fieldNames)
    }

    @Test
    fun dropPolicyDropsPrivateTrafficButKeepsPublic() {
        val dropper = PrivacyFilter(ChannelAllowList.from(slots), PrivateTrafficPolicy.DROP)
        assertSame(ObserverRecord.Dropped, run(0x02, opaque, dropper))
        assertSame(ObserverRecord.Dropped, run(0x05, channelPayload(randomKey), dropper))
        assertIs<ObserverRecord.Full>(run(0x04, opaque, dropper))
        assertIs<ObserverRecord.Full>(run(0x05, channelPayload(publicKey), dropper))
    }

    @Test
    fun defaultPolicyIsMetaOnly() {
        assertEquals(PrivateTrafficPolicy.META_ONLY, ObserverConstants.DEFAULT_PRIVATE_TRAFFIC_POLICY)
        assertIs<ObserverRecord.MetaOnly>(PrivacyFilter(ChannelAllowList.publicOnly).filter(capture(packet(1, 2, opaque))))
    }

    @Test
    fun endToEndFromLogFrame() {
        val frame = ObserverTestSupport.logFrame(20, -70, packet(1, 0x05, channelPayload(testKey)))
        val p = assertIs<CaptureResult.Captured>(PacketCapture.fromLogFrame(frame)).packet
        val r = assertIs<ObserverRecord.Full>(filter.filter(p))
        assertEquals("#test", r.channel)
        assertEquals(5.0, r.snr)
    }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean =
        (0..hay.size - needle.size).any { i -> needle.indices.all { hay[i + it] == needle[it] } }
}
