// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.health

import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.CoreStats
import com.meshcoreone.android.core.protocol.event.PacketStats
import com.meshcoreone.android.core.protocol.event.RadioStats
import com.meshcoreone.android.core.protocol.model.BatteryInfo
import com.meshcoreone.android.core.protocol.session.ConfigurationSessionOps
import kotlinx.coroutines.CancellationException

/** A snapshot plus a short description of every read that failed (for the adapter to log). */
class HealthReading(val snapshot: HealthSnapshot, val failures: List<String>)

fun interface HealthSource { suspend fun read(): HealthReading }

/** The four node commands health needs; [ConfigurationSessionOps] already provides all of them, see [statsReader]. */
interface DeviceStatsReader {
    /** CMD_GET_STATS (0x38) type 0; reply RESP_CODE_STATS (0x18). Firmware v8+. */
    suspend fun getStatsCore(): CoreStats
    /** CMD_GET_STATS type 1. */
    suspend fun getStatsRadio(): RadioStats
    /** CMD_GET_STATS type 2. */
    suspend fun getStatsPackets(): PacketStats
    /** CMD_GET_BATT_AND_STORAGE (0x14) -> RESP_CODE_BATT_AND_STORAGE (0x0c); [BatteryInfo.level] is millivolts. */
    suspend fun getBattery(): BatteryInfo
}

/** Hook for the Android service: `ProtocolHealthSource(session.statsReader())`. */
fun ConfigurationSessionOps.statsReader(): DeviceStatsReader = object : DeviceStatsReader {
    override suspend fun getStatsCore() = this@statsReader.getStatsCore()
    override suspend fun getStatsRadio() = this@statsReader.getStatsRadio()
    override suspend fun getStatsPackets() = this@statsReader.getStatsPackets()
    override suspend fun getBattery() = this@statsReader.getBattery()
}

/**
 * Reads node health through the existing protocol builders and parsers (core:protocol already has CMD_GET_STATS and
 * CMD_GET_BATT_AND_STORAGE). Core stats are tried first: if the node rejects them (firmware older than v8, or the feature is
 * disabled) the battery command supplies millivolts only, and later reads skip straight to it. A timeout or lost connection is
 * not taken as "unsupported". Radio and packet stats fail independently of each other. Never throws except for cancellation;
 * failures are returned in [HealthReading.failures]. A battery of 0 mV means "no reading" and is omitted.
 */
class ProtocolHealthSource(private val reader: DeviceStatsReader) : HealthSource {
    @Volatile private var statsUnsupported = false

    override suspend fun read(): HealthReading {
        val failures = ArrayList<String>()
        if (!statsUnsupported) {
            val core = attempt("core", failures) { reader.getStatsCore() }
            if (core != null) {
                val radio = attempt("radio", failures) { reader.getStatsRadio() }
                val packets = attempt("packets", failures) { reader.getStatsPackets() }
                return HealthReading(HealthSnapshot.fromStats(core, radio, packets).withValidBattery(), failures)
            }
        }
        val battery = attempt("battery", failures) { reader.getBattery() }
        return HealthReading(HealthSnapshot(batteryMillivolts = battery?.level?.toInt()).withValidBattery(), failures)
    }

    private suspend fun <T> attempt(name: String, failures: MutableList<String>, block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: MeshCoreException.DeviceError) {
        if (name == "core") statsUnsupported = true
        failures += "$name: ${error.message}"; null
    } catch (error: MeshCoreException.FeatureDisabled) {
        if (name == "core") statsUnsupported = true
        failures += "$name: ${error.message}"; null
    } catch (error: Exception) {
        failures += "$name: ${error.message ?: error.javaClass.simpleName}"; null
    }

    private fun HealthSnapshot.withValidBattery() =
        if (batteryMillivolts != null && batteryMillivolts <= 0) copy(batteryMillivolts = null) else this
}
