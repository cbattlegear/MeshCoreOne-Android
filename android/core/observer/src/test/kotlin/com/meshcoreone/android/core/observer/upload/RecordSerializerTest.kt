// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import com.meshcoreone.android.core.observer.ChannelAllowList
import com.meshcoreone.android.core.observer.ObserverRecord
import com.meshcoreone.android.core.observer.ObserverTestSupport.capture
import com.meshcoreone.android.core.observer.ObserverTestSupport.packet
import com.meshcoreone.android.core.observer.PrivacyFilter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordSerializerTest {
    private val rx = Instant.parse("2026-11-27T12:00:01.500Z")
    private val hash = Bytes.fromHex("0102030405060708")
    private val here = RecordLocation(46.8, -100.7, 12.0)

    @Test fun fullRecordMatchesContractExactly() {
        val record = TimedRecord(ObserverRecord.Full(Bytes.fromHex("aabbcc"), hash, 5.25, -80, "Public"), rx, here)
        assertEquals(
            "{\"mode\":\"full\",\"raw\":\"aabbcc\",\"SNR\":5.25,\"RSSI\":-80,\"timestamp\":\"2026-11-27T12:00:01.500Z\"," +
                "\"gps\":{\"lat\":46.800000,\"lon\":-100.700000,\"acc_m\":12.0,\"interpolated\":false}}",
            RecordSerializer.toJson(record),
        )
    }

    @Test fun neitherTheHashNorTheChannelNameIsSent() {
        val json = RecordSerializer.toJson(TimedRecord(ObserverRecord.Full(Bytes.fromHex("aa"), hash, 1.0, -1, "Public"), rx, here))!!
        for (key in listOf("hash", "channel", "Public", "0102030405060708", "rx_time", "snr", "rssi", "session", "delayed")) {
            assertFalse(json.contains(key), key)
        }
    }

    @Test fun absentSnrAndRssiAreOmitted() {
        val json = RecordSerializer.toJson(TimedRecord(ObserverRecord.Full(Bytes.fromHex("aa"), hash, null, null, null), rx, here))!!
        assertFalse(json.contains("SNR") || json.contains("RSSI"))
        assertTrue(json.startsWith("{\"mode\":\"full\",\"raw\":\"aa\",\"timestamp\":"))
    }

    @Test fun metaRecordCarriesHeaderTransportCodesPathByteAndPathAsRaw() {
        val meta = ObserverRecord.MetaOnly(0x0a, null, 2, Bytes.fromHex("0908"), 37, hash, -3.5, -101)
        assertEquals(
            "{\"mode\":\"meta\",\"raw\":\"0a020908\",\"SNR\":-3.5,\"RSSI\":-101,\"timestamp\":\"2026-11-27T12:00:01.500Z\"," +
                "\"gps\":{\"lat\":46.800000,\"lon\":-100.700000,\"acc_m\":12.0,\"interpolated\":false}}",
            RecordSerializer.toJson(TimedRecord(meta, rx, here)),
        )
        val withCodes = ObserverRecord.MetaOnly(0x08, Bytes.fromHex("01020304"), 0, Bytes.EMPTY, 10, hash, null, null)
        assertTrue(RecordSerializer.toJson(TimedRecord(withCodes, rx, here))!!.startsWith("{\"mode\":\"meta\",\"raw\":\"080102030400\",\"timestamp\""))
    }

    @Test fun timestampIsUtcRfc3339WithThreeFractionDigitsEvenWhenZero() {
        fun ts(at: String) = RecordSerializer.toJson(TimedRecord(ObserverRecord.Full(Bytes.fromHex("aa"), hash, null, null, null), Instant.parse(at), here))!!
        assertTrue(ts("2026-11-27T18:04:05Z").contains("\"timestamp\":\"2026-11-27T18:04:05.000Z\""))
        assertTrue(ts("2026-11-27T18:04:05.1Z").contains("\"timestamp\":\"2026-11-27T18:04:05.100Z\""))
        assertTrue(ts("2026-11-27T18:04:05.123456Z").contains("\"timestamp\":\"2026-11-27T18:04:05.123Z\""))
    }

    @Test fun everyGpsFieldIsSerializedInContractOrderWithSixDecimalCoordinates() {
        val base = ObserverRecord.Full(Bytes.fromHex("aa"), hash, null, null, null)
        val location = RecordLocation(
            46.8772123456, -96.7898, 4.5, altitudeMeters = 275.0, vAccuracyMeters = 3.0, speedMps = 1.25,
            bearingDegrees = 90.0, fixAgeMs = 1200, provider = "gps", interpolated = true,
        )
        assertEquals(
            "{\"mode\":\"full\",\"raw\":\"aa\",\"timestamp\":\"2026-11-27T12:00:01.500Z\",\"gps\":{\"lat\":46.877212," +
                "\"lon\":-96.789800,\"acc_m\":4.5,\"alt_m\":275.0,\"v_acc_m\":3.0,\"speed_mps\":1.25,\"bearing_deg\":90.0," +
                "\"fix_age_ms\":1200,\"provider\":\"gps\",\"interpolated\":true}}",
            RecordSerializer.toJson(TimedRecord(base, rx, location)),
        )
    }

    @Test fun absentGpsFieldsAreOmittedAndOldFlatKeysNeverAppear() {
        val json = RecordSerializer.toJson(TimedRecord(ObserverRecord.Full(Bytes.fromHex("aa"), hash, null, null, null), rx, here))!!
        for (key in listOf("alt_m", "v_acc_m", "speed_mps", "bearing_deg", "fix_age_ms", "provider")) assertFalse(json.contains("\"$key\""), key)
        for (old in listOf("h_acc", "\"alt\"", "\"speed\"", "\"bearing\"", "\"v_acc\"")) assertFalse(json.contains(old), old)
        // lat/lon live inside gps only.
        assertEquals(json.indexOf("\"lat\""), json.indexOf("\"gps\":{") + "\"gps\":{".length)
    }

    @Test fun droppedRecordsSerializeToNothing() {
        assertNull(RecordSerializer.toJson(TimedRecord(ObserverRecord.Dropped, rx, here)))
    }

    @Test fun batchEnvelopeMatchesContract() {
        assertEquals(
            "{\"origin\":\"Josh\",\"origin_id\":\"ab12\",\"direction\":\"rx\",\"records\":[{\"a\":1},{\"b\":2}]}",
            RecordSerializer.batchJson("Josh", "ab12", listOf("{\"a\":1}", "{\"b\":2}")),
        )
        assertEquals("{\"origin\":\"N\",\"origin_id\":\"ab12\",\"direction\":\"rx\",\"records\":[]}", RecordSerializer.batchJson("N", "ab12", emptyList()))
    }

    @Test fun aMetaOnlyRecordNeverSerializesAnyPayloadBytes() {
        // A private direct message (type 0x02) whose payload is recognisable, on a route with transport codes.
        val secretPayload = Bytes.fromHex("5ec2e7aaaa5ec2e7bbbb5ec2e7cccc5ec2e7dddd")
        for (route in listOf(0, 1)) {
            val raw = packet(route = route, type = 0x02, payload = secretPayload, pathLenByte = 2, path = byteArrayOf(9, 8))
            val record = PrivacyFilter(ChannelAllowList.from(emptyList())).filter(capture(raw))
            assertIs<ObserverRecord.MetaOnly>(record)
            val json = RecordSerializer.toJson(TimedRecord(record, rx, here))!!
            for (leak in listOf(secretPayload.hexString, "5ec2e7", raw.hexString)) assertFalse(json.contains(leak), leak)
            assertEquals(setOf("mode", "raw", "SNR", "RSSI", "timestamp", "gps", "lat", "lon", "acc_m", "interpolated"),
                Regex("\"([A-Za-z_]+)\":").findAll(json).map { it.groupValues[1] }.toSet())
            // raw ends exactly after the path: header [+ 4 codes] + path-length byte + 2 path bytes.
            val sentRaw = Regex("\"raw\":\"([0-9a-f]*)\"").find(json)!!.groupValues[1]
            assertEquals((1 + (if (route == 0) 4 else 0) + 1 + 2) * 2, sentRaw.length)
        }
    }

    @Test fun topicsMatchContract() {
        val topics = testTopics()
        val key = testNodeKey.hexString
        assertEquals(key, key.lowercase())
        assertEquals("meshcore/client/$key/packets", topics.packets)
        assertEquals("meshcore/client/$key/rf", topics.rf)
        assertFalse(topics.packets.contains("/nd/") || topics.packets.contains("/mn/") || topics.packets.contains("mobile"))
    }

    @Test fun invalidKeyIsRejected() {
        assertFailsWith<IllegalArgumentException> { ObserverTopics(Bytes(ByteArray(31))) }
    }
}
