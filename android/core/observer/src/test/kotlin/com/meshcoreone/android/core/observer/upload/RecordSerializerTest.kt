// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import com.meshcoreone.android.core.observer.ObserverConstants
import com.meshcoreone.android.core.observer.ObserverRecord
import com.meshcoreone.android.core.observer.ObserverTestSupport
import com.meshcoreone.android.core.observer.ObserverTestSupport.capture
import com.meshcoreone.android.core.observer.ObserverTestSupport.packet
import com.meshcoreone.android.core.observer.PrivacyFilter
import com.meshcoreone.android.core.observer.ChannelAllowList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordSerializerTest {
    private val rx = Instant.parse("2026-11-27T12:00:01.500Z")
    private val hash = Bytes.fromHex("0102030405060708")

    @Test fun fullRecordMatchesSpecExactly() {
        val record = TimedRecord(ObserverRecord.Full(Bytes.fromHex("aabbcc"), hash, 5.25, -80, "Public"), rx)
        assertEquals(
            "{\"hash\":\"0102030405060708\",\"mode\":\"full\",\"raw\":\"aabbcc\",\"snr\":5.25,\"rssi\":-80," +
                "\"channel\":\"Public\",\"rx_time\":\"2026-11-27T12:00:01.500Z\"}",
            RecordSerializer.toJson(record),
        )
    }

    @Test fun absentOptionalFieldsAreOmitted() {
        val record = TimedRecord(ObserverRecord.Full(Bytes.fromHex("aa"), hash, null, null, null), rx)
        assertEquals(
            "{\"hash\":\"0102030405060708\",\"mode\":\"full\",\"raw\":\"aa\",\"rx_time\":\"2026-11-27T12:00:01.500Z\"}",
            RecordSerializer.toJson(record),
        )
    }

    @Test fun metaRecordMatchesSpecExactly() {
        val record = TimedRecord(ObserverRecord.MetaOnly(0x0a, 2, Bytes.fromHex("0908"), 37, hash, -3.5, -101), rx)
        assertEquals(
            "{\"hash\":\"0102030405060708\",\"mode\":\"meta\",\"header\":\"0a\",\"path\":\"0908\",\"len\":37," +
                "\"snr\":-3.5,\"rssi\":-101,\"rx_time\":\"2026-11-27T12:00:01.500Z\"}",
            RecordSerializer.toJson(record),
        )
    }

    @Test fun headerIsAlwaysTwoHexCharsAndEmptyPathIsEmptyString() {
        val json = RecordSerializer.toJson(TimedRecord(ObserverRecord.MetaOnly(0x00, 0, Bytes.EMPTY, 10, hash, null, null), rx))!!
        assertTrue(json.contains("\"header\":\"00\"") && json.contains("\"path\":\"\""))
    }

    @Test fun locationIsOptionalAndAppendedOnlyWhenPresent() {
        val base = ObserverRecord.Full(Bytes.fromHex("aa"), hash, null, null, null)
        assertFalse(RecordSerializer.toJson(TimedRecord(base, rx))!!.contains("lat"))
        val withLocation = RecordSerializer.toJson(TimedRecord(base, rx, RecordLocation(46.8, -100.7, 12.0)))!!
        assertTrue(withLocation.endsWith(",\"lat\":46.800000,\"lon\":-100.700000,\"h_acc\":12.0,\"interpolated\":false}"), withLocation)
        assertFalse(withLocation.contains("\"acc\""))
    }

    @Test fun everyLocationFieldIsSerializedInSpecOrderWithSixDecimalCoordinates() {
        val base = ObserverRecord.Full(Bytes.fromHex("aa"), hash, null, null, null)
        val location = RecordLocation(
            46.8772123456, -96.7898, 4.5, altitudeMeters = 275.0, vAccuracyMeters = 3.0, speedMps = 1.25,
            bearingDegrees = 90.0, fixAgeMs = 1200, provider = "gps", interpolated = true,
        )
        assertEquals(
            "{\"hash\":\"0102030405060708\",\"mode\":\"full\",\"raw\":\"aa\",\"rx_time\":\"2026-11-27T12:00:01.500Z\"," +
                "\"lat\":46.877212,\"lon\":-96.789800,\"h_acc\":4.5,\"alt\":275.0,\"v_acc\":3.0,\"speed\":1.25," +
                "\"bearing\":90.0,\"fix_age_ms\":1200,\"provider\":\"gps\",\"interpolated\":true}",
            RecordSerializer.toJson(TimedRecord(base, rx, location)),
        )
    }

    @Test fun metaRecordsCarryTheSameLocationFields() {
        val meta = ObserverRecord.MetaOnly(0x0a, 0, Bytes.EMPTY, 10, hash, null, null)
        val json = RecordSerializer.toJson(TimedRecord(meta, rx, RecordLocation(1.0, 2.0, 3.0, provider = "fused")))!!
        assertTrue(json.endsWith("\"lat\":1.000000,\"lon\":2.000000,\"h_acc\":3.0,\"provider\":\"fused\",\"interpolated\":false}"), json)
    }

    @Test fun absentLocationFieldsAreOmitted() {
        val base = ObserverRecord.Full(Bytes.fromHex("aa"), hash, null, null, null)
        val json = RecordSerializer.toJson(TimedRecord(base, rx, RecordLocation(1.0, 2.0, 3.0)))!!
        for (key in listOf("alt", "v_acc", "speed", "bearing", "fix_age_ms", "provider")) assertFalse(json.contains("\"$key\""), key)
    }

    @Test fun droppedRecordsSerializeToNothing() {
        assertNull(RecordSerializer.toJson(TimedRecord(ObserverRecord.Dropped, rx)))
    }

    @Test fun batchEnvelopeMatchesSpec() {
        assertEquals("{\"session\":\"s1\",\"delayed\":false,\"records\":[{\"a\":1},{\"b\":2}]}", RecordSerializer.batchJson("s1", false, listOf("{\"a\":1}", "{\"b\":2}")))
        assertEquals("{\"session\":\"s1\",\"delayed\":true,\"records\":[]}", RecordSerializer.batchJson("s1", true, emptyList()))
    }

    @Test fun aMetaOnlyRecordNeverSerializesAnyPayloadBytes() {
        // A private direct message (type 0x02) whose payload and raw bytes are recognisable.
        val secretPayload = Bytes.fromHex("5ec2e7aaaa5ec2e7bbbb5ec2e7cccc5ec2e7dddd")
        val raw = packet(route = 1, type = 0x02, payload = secretPayload, pathLenByte = 2, path = byteArrayOf(9, 8))
        val record = PrivacyFilter(ChannelAllowList.from(emptyList())).filter(capture(raw))
        assertIs<ObserverRecord.MetaOnly>(record)
        val json = RecordSerializer.toJson(TimedRecord(record, rx))!!
        for (leak in listOf(secretPayload.hexString, "5ec2e7", raw.hexString, "raw")) assertFalse(json.contains(leak), leak)
        // Even a 1 byte slice of the payload must not appear as a field value: only hash/header/path/len/snr/rssi/rx_time.
        assertEquals(
            setOf("hash", "mode", "header", "path", "len", "snr", "rssi", "rx_time"),
            Regex("\"([a-z_]+)\":").findAll(json).map { it.groupValues[1] }.toSet(),
        )
        assertEquals(ObserverConstants.PACKET_HASH_LENGTH * 2, Regex("\"hash\":\"([0-9a-f]+)\"").find(json)!!.groupValues[1].length)
    }

    @Test fun topicsAndPresenceMessagesMatchSpec() {
        val topics = testTopics("mn")
        val key = ClaimKey.hex
        assertEquals("meshcore/mn/mobile/$key/packets", topics.packets)
        assertEquals("meshcore/mn/mobile/$key/status", topics.status)
        assertEquals("meshcore/mn/mobile/$key/device", topics.device)
        assertEquals(MqttMessage(topics.status, "{\"state\":\"online\"}", 1, true), ObserverPresence.online(topics))
        assertEquals(MqttMessage(topics.status, "{\"state\":\"offline\"}", 1, true), ObserverPresence.offline(topics))
        val will = MqttConnectionConfig.forNode(com.meshcoreone.android.core.observer.prizm.StandInPrizmClient.LOCAL_TEST_BROKER, topics, "c").lastWill
        assertEquals(ObserverPresence.offline(topics), will)
    }

    @Test fun invalidRegionOrKeyIsRejected() {
        for (bad in listOf("", "n/d", "ND", "#", "+")) kotlin.test.assertFailsWith<IllegalArgumentException>(bad) { ObserverTopics(bad, testNodeKey) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { ObserverTopics("nd", Bytes(ByteArray(31))) }
    }
}

private object ClaimKey { val hex = testNodeKey.hexString }
