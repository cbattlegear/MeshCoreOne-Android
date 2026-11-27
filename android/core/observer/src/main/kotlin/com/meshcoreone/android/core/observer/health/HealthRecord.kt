// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.health

import com.meshcoreone.android.core.observer.json.jsonObject
import com.meshcoreone.android.core.protocol.event.CoreStats
import com.meshcoreone.android.core.protocol.event.PacketStats
import com.meshcoreone.android.core.protocol.event.RadioStats
import java.time.Instant

/**
 * What the node reported, every field optional (older firmware gives only the battery; a failed read gives nothing).
 * Battery is millivolts as the firmware reports it, never a percentage.
 */
data class HealthSnapshot(
    val batteryMillivolts: Int? = null,
    val uptimeSeconds: Long? = null,
    val noiseFloorDbm: Int? = null,
    val txAirSeconds: Long? = null,
    val rxAirSeconds: Long? = null,
    val rxPackets: Long? = null,
    val txPackets: Long? = null,
    val rxErrors: Long? = null,
) {
    companion object {
        /** Maps firmware stats (core 0, radio 1, packets 2) to the health fields; any part may be missing. */
        fun fromStats(core: CoreStats?, radio: RadioStats?, packets: PacketStats?) = HealthSnapshot(
            batteryMillivolts = core?.batteryMV?.toInt(),
            uptimeSeconds = core?.uptimeSeconds?.toLong(),
            noiseFloorDbm = radio?.noiseFloor?.toInt(),
            txAirSeconds = radio?.txAirtimeSeconds?.toLong(),
            rxAirSeconds = radio?.rxAirtimeSeconds?.toLong(),
            rxPackets = packets?.received?.toLong(),
            txPackets = packets?.sent?.toLong(),
            rxErrors = packets?.receiveErrors?.toLong(),
        )
    }
}

/** One health message. [latitude]/[longitude] are already redacted (sharing, privacy zones) and are both set or both null. */
data class HealthRecord(
    val ts: Instant,
    val snapshot: HealthSnapshot,
    val bleConnected: Boolean,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    init { require((latitude == null) == (longitude == null)) { "lat and lon must be present together" } }
}

/** {"ts","batt_mv","uptime_s","noise_floor","tx_air_s","rx_air_s","rx_pkts","tx_pkts","rx_errors","ble_connected","lat","lon"}; absent fields omitted. */
object HealthRecordSerializer {
    private const val COORDINATE_DECIMALS = 6

    fun toJson(record: HealthRecord): String = jsonObject {
        val s = record.snapshot
        string("ts", record.ts.toString())
        optLong("batt_mv", s.batteryMillivolts?.toLong())
        optLong("uptime_s", s.uptimeSeconds)
        optLong("noise_floor", s.noiseFloorDbm?.toLong())
        optLong("tx_air_s", s.txAirSeconds)
        optLong("rx_air_s", s.rxAirSeconds)
        optLong("rx_pkts", s.rxPackets)
        optLong("tx_pkts", s.txPackets)
        optLong("rx_errors", s.rxErrors)
        bool("ble_connected", record.bleConnected)
        val lat = record.latitude
        val lon = record.longitude
        if (lat != null && lon != null) {
            fixed("lat", lat, COORDINATE_DECIMALS)
            fixed("lon", lon, COORDINATE_DECIMALS)
        }
    }
}
