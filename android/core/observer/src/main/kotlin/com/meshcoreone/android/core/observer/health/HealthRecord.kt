// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.health

import com.meshcoreone.android.core.observer.json.jsonObject
import com.meshcoreone.android.core.observer.upload.RecordLocation
import com.meshcoreone.android.core.observer.upload.WireFormat
import com.meshcoreone.android.core.protocol.event.CoreStats
import com.meshcoreone.android.core.protocol.event.PacketStats
import com.meshcoreone.android.core.protocol.event.RadioStats
import java.time.Instant

/**
 * What the node reported, every field optional (older firmware gives only the battery; a failed read gives nothing).
 * Battery is millivolts as the firmware reports it, never a percentage. Names follow the wire fields of the rf sample.
 */
data class HealthSnapshot(
    val batteryMillivolts: Int? = null,
    val uptimeSeconds: Long? = null,
    val queueLength: Int? = null,
    val errors: Int? = null,
    val noiseFloorDbm: Int? = null,
    val lastRssi: Int? = null,
    val lastSnr: Double? = null,
    val txAirSeconds: Long? = null,
    val rxAirSeconds: Long? = null,
    val received: Long? = null,
    val sent: Long? = null,
    val floodRx: Long? = null,
    val directRx: Long? = null,
    val floodTx: Long? = null,
    val directTx: Long? = null,
    val rxErrors: Long? = null,
) {
    companion object {
        /** Maps firmware stats (core 0, radio 1, packets 2) to the health fields; any part may be missing. */
        fun fromStats(core: CoreStats?, radio: RadioStats?, packets: PacketStats?) = HealthSnapshot(
            batteryMillivolts = core?.batteryMV?.toInt(),
            uptimeSeconds = core?.uptimeSeconds?.toLong(),
            queueLength = core?.queueLength?.toInt(),
            errors = core?.errors?.toInt(),
            noiseFloorDbm = radio?.noiseFloor?.toInt(),
            lastRssi = radio?.lastRSSI?.toInt(),
            lastSnr = radio?.lastSNR,
            txAirSeconds = radio?.txAirtimeSeconds?.toLong(),
            rxAirSeconds = radio?.rxAirtimeSeconds?.toLong(),
            received = packets?.received?.toLong(),
            sent = packets?.sent?.toLong(),
            floodRx = packets?.floodRx?.toLong(),
            directRx = packets?.directRx?.toLong(),
            floodTx = packets?.floodTx?.toLong(),
            directTx = packets?.directTx?.toLong(),
            rxErrors = packets?.receiveErrors?.toLong(),
        )
    }
}

/**
 * One rf sample. The server drops a sample without `uptime_secs` or a position, so both are required here: [location] is
 * already redacted (sharing, privacy zones) and [create] returns null when the node gave no uptime (older firmware without core
 * stats, or no node link), so such a sample is never built or sent.
 */
class HealthRecord private constructor(
    val timestamp: Instant,
    val uptimeSeconds: Long,
    val snapshot: HealthSnapshot,
    val location: RecordLocation,
    /** Null when unknown; omitted from the wire then. */
    val stationary: Boolean?,
) {
    companion object {
        fun create(timestamp: Instant, snapshot: HealthSnapshot, location: RecordLocation, stationary: Boolean?): HealthRecord? {
            val uptime = snapshot.uptimeSeconds ?: return null
            return HealthRecord(timestamp, uptime, snapshot, location, stationary)
        }
    }
}

/**
 * `{"timestamp","gps":{"lat","lon","acc_m"},"stationary","uptime_secs","battery_mv","queue_len","errors","noise_floor","last_rssi",
 * "last_snr","tx_air_secs","rx_air_secs","recv","sent","flood_rx","direct_rx","flood_tx","direct_tx","recv_errors"}`; absent fields are
 * omitted (never zero-filled: the server stores a missing counter as NULL). Millivolts only, never a percentage.
 */
object HealthRecordSerializer {
    fun toJson(record: HealthRecord): String = jsonObject {
        val s = record.snapshot
        string("timestamp", WireFormat.timestamp(record.timestamp))
        raw("gps", WireFormat.rfGps(record.location))
        record.stationary?.let { bool("stationary", it) }
        long("uptime_secs", record.uptimeSeconds)
        optLong("battery_mv", s.batteryMillivolts?.toLong())
        optLong("queue_len", s.queueLength?.toLong())
        optLong("errors", s.errors?.toLong())
        optLong("noise_floor", s.noiseFloorDbm?.toLong())
        optLong("last_rssi", s.lastRssi?.toLong())
        optDouble("last_snr", s.lastSnr)
        optLong("tx_air_secs", s.txAirSeconds)
        optLong("rx_air_secs", s.rxAirSeconds)
        optLong("recv", s.received)
        optLong("sent", s.sent)
        optLong("flood_rx", s.floodRx)
        optLong("direct_rx", s.directRx)
        optLong("flood_tx", s.floodTx)
        optLong("direct_tx", s.directTx)
        optLong("recv_errors", s.rxErrors)
    }
}
