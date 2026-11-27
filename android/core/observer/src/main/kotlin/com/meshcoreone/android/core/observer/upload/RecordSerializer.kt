// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import com.meshcoreone.android.core.observer.ObserverRecord
import com.meshcoreone.android.core.observer.json.JsonObjectBuilder
import com.meshcoreone.android.core.observer.json.jsonObject

/**
 * Packet JSON per the Prizm server contract (`meshcore/client/<pk>/packets`).
 * Record: `{"mode":"full"|"meta","raw":hex,"SNR":..,"RSSI":..,"timestamp":"...Z","gps":{...}}`; `gps` is required (the server
 * drops a record without it), so [TimedRecord.location] is not nullable. A full record's raw is the whole packet; a meta record's
 * raw is the header, transport codes, path-length byte and path only ([ObserverRecord.MetaOnly] has no payload field, so no
 * payload byte can reach the wire). The hash and the channel name are not sent: the server computes its own hash.
 * Envelope: `{"origin":<node name>,"origin_id":<pk hex>,"direction":"rx","records":[...]}`; no session id and no delayed flag
 * (a resent record keeps its original `timestamp`).
 */
object RecordSerializer {
    const val MODE_FULL = "full"
    const val MODE_META = "meta"
    const val DIRECTION_RX = "rx"

    /** Returns null for [ObserverRecord.Dropped]: nothing is ever uploaded or queued for it. */
    fun toJson(timed: TimedRecord): String? = when (val record = timed.record) {
        is ObserverRecord.Full -> jsonObject {
            string("mode", MODE_FULL)
            string("raw", record.raw.hexString)
            linkAndPosition(record.snr, record.rssi, timed)
        }
        is ObserverRecord.MetaOnly -> jsonObject {
            string("mode", MODE_META)
            string("raw", record.metaRaw.hexString)
            linkAndPosition(record.snr, record.rssi, timed)
        }
        ObserverRecord.Dropped -> null
    }

    /** One message; [recordJson] are outputs of [toJson]. [originId] is the node public key as lowercase hex. */
    fun batchJson(origin: String, originId: String, recordJson: List<String>): String = jsonObject {
        string("origin", origin)
        string("origin_id", originId)
        string("direction", DIRECTION_RX)
        raw("records", JsonObjectBuilder.array(recordJson))
    }

    private fun JsonObjectBuilder.linkAndPosition(snr: Double?, rssi: Long?, timed: TimedRecord) {
        optDouble("SNR", snr)
        optLong("RSSI", rssi)
        string("timestamp", WireFormat.timestamp(timed.rxTime))
        raw("gps", WireFormat.packetGps(timed.location))
    }
}
