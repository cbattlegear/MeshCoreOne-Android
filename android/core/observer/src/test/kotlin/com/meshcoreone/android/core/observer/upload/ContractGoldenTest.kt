// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import com.meshcoreone.android.core.observer.ObserverRecord
import com.meshcoreone.android.core.observer.health.HealthRecord
import com.meshcoreone.android.core.observer.health.HealthRecordSerializer
import com.meshcoreone.android.core.observer.health.HealthSnapshot
import com.meshcoreone.android.core.observer.json.MiniJson
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The examples of the Prizm server contract ("Packets" and "Health"), with the placeholders filled in, compared to what the app
 * serializes: once structurally (every key and value) and once as the exact string, which also pins key order.
 */
class ContractGoldenTest {
    private val heard = Instant.parse("2026-11-27T18:04:05.123Z")
    private val position = RecordLocation(
        latitude = 46.877186, longitude = -96.789803, hAccuracyMeters = 4.1, altitudeMeters = 274.2, vAccuracyMeters = 6.0,
        speedMps = 13.4, bearingDegrees = 271.0, fixAgeMs = 420, provider = "fused", interpolated = true,
    )
    private val gpsJson = """{"lat":46.877186,"lon":-96.789803,"acc_m":4.1,"alt_m":274.2,"v_acc_m":6.0,"speed_mps":13.4,"bearing_deg":271.0,"fix_age_ms":420,"provider":"fused","interpolated":true}"""

    private val fullExpected = """{"mode":"full","raw":"1500aabbccddeeff","SNR":-3.25,"RSSI":-110,"timestamp":"2026-11-27T18:04:05.123Z","gps":$gpsJson}"""
    private val metaExpected = """{"mode":"meta","raw":"0a020908","SNR":-4.5,"RSSI":-118,"timestamp":"2026-11-27T18:04:05.123Z","gps":$gpsJson}"""
    private val originId = testNodeKey.hexString

    private val full = TimedRecord(ObserverRecord.Full(Bytes.fromHex("1500aabbccddeeff"), Bytes.fromHex("0102030405060708"), -3.25, -110, "Public"), heard, position)
    private val meta = TimedRecord(ObserverRecord.MetaOnly(0x0a, null, 2, Bytes.fromHex("0908"), 40, Bytes.fromHex("0102030405060708"), -4.5, -118), heard, position)

    private fun assertGolden(expected: String, actual: String?) {
        assertEquals(MiniJson.parse(expected), MiniJson.parse(actual!!), "structure")
        assertEquals(expected, actual, "exact string and key order")
    }

    @Test fun fullRecordMatchesTheContractExample() = assertGolden(fullExpected, RecordSerializer.toJson(full))

    @Test fun metaRecordMatchesTheContractExample() = assertGolden(metaExpected, RecordSerializer.toJson(meta))

    @Test fun theWholeBatchMessageMatchesTheContractEnvelope() {
        val message = RecordSerializer.batchJson("Josh Mobile", originId, listOf(RecordSerializer.toJson(full)!!, RecordSerializer.toJson(meta)!!))
        assertGolden("""{"origin":"Josh Mobile","origin_id":"$originId","direction":"rx","records":[$fullExpected,$metaExpected]}""", message)
    }

    @Test fun healthSampleMatchesTheContractExample() {
        val snapshot = HealthSnapshot(
            uptimeSeconds = 86_400, batteryMillivolts = 3_921, queueLength = 0, errors = 0, noiseFloorDbm = -112, lastRssi = -96,
            lastSnr = 7.25, txAirSeconds = 41, rxAirSeconds = 932, received = 5_120, sent = 37, floodRx = 4_000, directRx = 1_120,
            floodTx = 30, directTx = 7, rxErrors = 12,
        )
        val record = HealthRecord.create(Instant.parse("2026-11-27T18:05:00.000Z"), snapshot, RecordLocation(46.877186, -96.789803, 4.1), false)!!
        assertGolden(
            """{"timestamp":"2026-11-27T18:05:00.000Z","gps":{"lat":46.877186,"lon":-96.789803,"acc_m":4.1},"stationary":false,"uptime_secs":86400,""" +
                """"battery_mv":3921,"queue_len":0,"errors":0,"noise_floor":-112,"last_rssi":-96,"last_snr":7.25,"tx_air_secs":41,"rx_air_secs":932,""" +
                """"recv":5120,"sent":37,"flood_rx":4000,"direct_rx":1120,"flood_tx":30,"direct_tx":7,"recv_errors":12}""",
            HealthRecordSerializer.toJson(record),
        )
    }
}
