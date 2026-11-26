// PortedFrom: MC1Services/Sources/MC1Services/Connection/DevicePlatform.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.model

import java.text.Normalizer
import java.time.Duration
import java.util.Locale

enum class DevicePlatform(val recommendedWritePacingSeconds: Double) {
    ESP32(0.060), NRF52(0.025), UNKNOWN(0.060);
    val recommendedWritePacing: Duration get() = Duration.ofMillis(if (this == NRF52) 25 else 60)

    companion object {
        private val rules = listOf(
            "Heltec V2", "Heltec V3", "Heltec V4", "Heltec Tracker", "Heltec E290", "Heltec E213",
            "Heltec T190", "Heltec CT62", "T-Beam", "T-Deck", "T-LoRa", "TLora", "Xiao S3 WIO",
            "Xiao C3", "Xiao C6", "RAK 3112", "Unit C6L", "Station G2", "Meshadventurer",
            "Generic ESP32", "ThinkNode M2", "ThinkNode M5",
        ).map { it to ESP32 } + listOf(
            "MeshPocket", "Mesh Pocket", "T114", "Mesh Solar", "Xiao-nrf52", "Xiao_nrf52", "WM1110",
            "Wio Tracker", "T1000-E", "SenseCap Solar", "WisMesh Tag", "RAK 4631", "RAK 3401",
            "T-Echo", "ThinkNode-M1", "ThinkNode M3", "ThinkNode-M6", "GAT562", "Ikoka", "ProMicro",
            "Minewsemi", "Meshtiny", "Keepteen", "Nano G2 Ultra",
        ).map { it to NRF52 }

        fun detect(model: String): DevicePlatform {
            val normalized = model.standardSearchForm()
            return rules.firstOrNull { normalized.contains(it.first.standardSearchForm()) }?.second ?: UNKNOWN
        }
    }
}

internal fun String.standardSearchForm(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
