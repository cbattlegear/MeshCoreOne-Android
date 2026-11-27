// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.mqtt

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

class MqttPacketsTest {
    @Test fun connectMatchesTheMqtt311ByteLayout() {
        val packet = MqttPackets.connect("ab", "u", "p".toByteArray(), 60)
        assertContentEquals(
            bytes(
                0x10, 20, 0, 4, 'M'.code, 'Q'.code, 'T'.code, 'T'.code, 4, 0xC2, 0, 60,
                0, 2, 'a'.code, 'b'.code, 0, 1, 'u'.code, 0, 1, 'p'.code,
            ),
            packet,
        )
    }

    @Test fun publishIsQos1NotRetainedWithThePacketIdAfterTheTopic() {
        assertContentEquals(
            bytes(0x32, 9, 0, 3, 'a'.code, '/'.code, 'b'.code, 0, 1, 'h'.code, 'i'.code),
            MqttPackets.publish("a/b", "hi".toByteArray(), 1),
        )
        assertEquals(0x3A, MqttPackets.publish("a/b", ByteArray(0), 7, duplicate = true)[0].toInt() and 0xff)
        assertEquals(0x32, MqttPackets.publish("a/b", ByteArray(0), 7)[0].toInt() and 0xff, "retain bit never set")
    }

    @Test fun remainingLengthUsesTheVariableByteEncodingForBigPayloads() {
        val p = MqttPackets.publish("t", ByteArray(200), 2)
        // body = 2 + 1 + 2 + 200 = 205 -> 0xCD 0x01
        assertEquals(0xCD, p[1].toInt() and 0xff); assertEquals(0x01, p[2].toInt())
        assertEquals(1 + 2 + 205, p.size)
        val big = MqttPackets.publish("t", ByteArray(20_000), 2)
        assertEquals(3, big.drop(1).takeWhile { it.toInt() and 0x80 != 0 }.size + 1)
    }

    @Test fun pingAndDisconnectAreTwoBytes() {
        assertContentEquals(bytes(0xC0, 0), MqttPackets.pingReq()); assertContentEquals(bytes(0xE0, 0), MqttPackets.disconnect())
    }

    @Test fun invalidInputsAreRefusedBeforeAnythingIsSent() {
        assertFailsWith<IllegalArgumentException> { MqttPackets.publish("a/b", ByteArray(0), 0) }
        assertFailsWith<IllegalArgumentException> { MqttPackets.publish("a/b", ByteArray(0), 65_536) }
        for (topic in listOf("", "a/#", "a/+/b", "a\u0000b")) assertFailsWith<IllegalArgumentException>(topic) { MqttPackets.publish(topic, ByteArray(0), 1) }
        assertFailsWith<IllegalArgumentException> { MqttPackets.connect("c", "u", ByteArray(70_000), 60) }
        assertFailsWith<IllegalArgumentException> { MqttPackets.connect("c", "u", ByteArray(0), 70_000) }
    }

    @Test fun decoderReadsConnackPubackAndPingresp() {
        val d = MqttPackets.Decoder()
        assertEquals(listOf(MqttIncoming.ConnAck(false, 0)), d.feed(bytes(0x20, 2, 0, 0)))
        assertEquals(listOf(MqttIncoming.ConnAck(true, 5)), d.feed(bytes(0x20, 2, 1, 5)))
        assertEquals(listOf(MqttIncoming.PubAck(0x0102)), d.feed(bytes(0x40, 2, 1, 2)))
        assertEquals(listOf(MqttIncoming.PingResp), d.feed(bytes(0xD0, 0)))
        assertTrue(MqttIncoming.ConnAck(false, 0).accepted && !MqttIncoming.ConnAck(false, 5).accepted)
    }

    @Test fun decoderHandlesSplitAndJoinedPackets() {
        val d = MqttPackets.Decoder()
        assertTrue(d.feed(bytes(0x40)).isEmpty()); assertTrue(d.feed(bytes(2, 0)).isEmpty())
        assertEquals(listOf(MqttIncoming.PubAck(9)), d.feed(bytes(9)))
        assertEquals(
            listOf(MqttIncoming.PubAck(1), MqttIncoming.PubAck(2), MqttIncoming.PingResp),
            d.feed(bytes(0x40, 2, 0, 1, 0x40, 2, 0, 2, 0xD0, 0)),
        )
        assertEquals(listOf(MqttIncoming.Other(3)), d.feed(bytes(0x30, 3, 0, 1, 'x'.code)))
    }

    @Test fun decoderRejectsMalformedPackets() {
        assertFailsWith<MqttProtocolException> { MqttPackets.Decoder().feed(bytes(0x20, 3, 0, 0, 0)) }
        assertFailsWith<MqttProtocolException> { MqttPackets.Decoder().feed(bytes(0x40, 3, 0, 0, 0)) }
        assertFailsWith<MqttProtocolException> { MqttPackets.Decoder().feed(bytes(0x30, 0x80, 0x80, 0x80, 0x80, 1)) }
    }
}
