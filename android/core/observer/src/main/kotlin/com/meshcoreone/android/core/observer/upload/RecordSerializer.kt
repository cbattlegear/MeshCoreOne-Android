// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import com.meshcoreone.android.core.observer.ObserverRecord
import com.meshcoreone.android.core.observer.json.JsonObjectBuilder
import com.meshcoreone.android.core.observer.json.jsonObject
import com.meshcoreone.android.core.protocol.bytes.Bytes

/**
 * Spec JSON for one record. Optional fields (snr, rssi, channel, location) are omitted when absent.
 * Full: {hash, mode:"full", raw, snr, rssi, channel?, rx_time, ...}; MetaOnly: {hash, mode:"meta", header, path, len, snr,
 * rssi, rx_time, ...}. A MetaOnly record has no payload field to serialize, so no payload byte can reach the wire.
 * OPEN QUESTION: rx_time is ISO-8601 UTC (Instant.toString) and location keys are lat/lon/acc; confirm with Prizm.
 */
object RecordSerializer {
    const val MODE_FULL = "full"
    const val MODE_META = "meta"

    /** Returns null for [ObserverRecord.Dropped]: nothing is ever uploaded or queued for it. */
    fun toJson(timed: TimedRecord): String? = when (val record = timed.record) {
        is ObserverRecord.Full -> jsonObject {
            string("hash", record.hash.hexString)
            string("mode", MODE_FULL)
            string("raw", record.raw.hexString)
            optDouble("snr", record.snr)
            optLong("rssi", record.rssi)
            optString("channel", record.channel)
            string("rx_time", timed.rxTime.toString())
            location(timed.location)
        }
        is ObserverRecord.MetaOnly -> jsonObject {
            string("hash", record.hash.hexString)
            string("mode", MODE_META)
            string("header", Bytes.of(record.header and 0xff).hexString)
            string("path", record.path.hexString)
            int("len", record.packetLength)
            optDouble("snr", record.snr)
            optLong("rssi", record.rssi)
            string("rx_time", timed.rxTime.toString())
            location(timed.location)
        }
        ObserverRecord.Dropped -> null
    }

    /** {"session":..., "delayed":..., "records":[...]} where [recordJson] are outputs of [toJson]. */
    fun batchJson(session: String, delayed: Boolean, recordJson: List<String>): String = jsonObject {
        string("session", session)
        bool("delayed", delayed)
        raw("records", JsonObjectBuilder.array(recordJson))
    }

    private fun JsonObjectBuilder.location(location: RecordLocation?) {
        if (location == null) return
        double("lat", location.latitude)
        double("lon", location.longitude)
        optDouble("acc", location.accuracyMeters)
    }
}
