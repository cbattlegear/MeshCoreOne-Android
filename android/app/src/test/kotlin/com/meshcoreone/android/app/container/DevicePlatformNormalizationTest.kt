// AndroidOnly: WP-303 Bound native model-name matching beyond the preserved literal source vectors.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.model.DevicePlatform
import java.text.Normalizer
import java.util.Locale
import org.junit.Test
import kotlin.test.assertEquals

class DevicePlatformNormalizationTest {
    private val models = listOf(
        "Heltec V2", "Heltec V3", "Heltec V4", "Heltec Tracker", "Heltec E290", "Heltec E213",
        "Heltec T190", "Heltec CT62", "T-Beam", "T-Deck", "T-LoRa", "TLora", "Xiao S3 WIO",
        "Xiao C3", "Xiao C6", "RAK 3112", "Unit C6L", "Station G2", "Meshadventurer",
        "Generic ESP32", "ThinkNode M2", "ThinkNode M5",
    ).map { it to DevicePlatform.ESP32 } + listOf(
        "MeshPocket", "Mesh Pocket", "T114", "Mesh Solar", "Xiao-nrf52", "Xiao_nrf52", "WM1110",
        "Wio Tracker", "T1000-E", "SenseCap Solar", "WisMesh Tag", "RAK 4631", "RAK 3401",
        "T-Echo", "ThinkNode-M1", "ThinkNode M3", "ThinkNode-M6", "GAT562", "Ikoka", "ProMicro",
        "Minewsemi", "Meshtiny", "Keepteen", "Nano G2 Ultra",
    ).map { it to DevicePlatform.NRF52 }

    @Test fun allRulesMatchAsciiCaseAndCanonicalAccentVariantsRegardlessOfHostLocale() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.ROOT, Locale.US, Locale.forLanguageTag("tr-TR"), Locale.forLanguageTag("lt-LT"),
                Locale.FRANCE, Locale.JAPAN, Locale.forLanguageTag("ar"))) {
                Locale.setDefault(locale)
                for ((model, expected) in models) {
                    val accented = model.map { if (it in 'A'..'Z' || it in 'a'..'z') "$it\u0301" else "$it" }.joinToString("")
                    for (variant in listOf(model, model.uppercase(Locale.ROOT), model.lowercase(Locale.ROOT),
                        accented, Normalizer.normalize(accented, Normalizer.Form.NFC))) {
                        assertEquals(expected, DevicePlatform.detect("vendor $variant revision"), "$locale: $variant")
                    }
                }
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun matchingKeepsSourceRuleOrderAndDoesNotInventCompatibilityOrFuzzyFolding() {
        assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("MeshPocket and Heltec V3"))
        assertEquals(DevicePlatform.UNKNOWN, DevicePlatform.detect("T\u2011Deck"))
        assertEquals(DevicePlatform.UNKNOWN, DevicePlatform.detect("\uff2d\uff45\uff53\uff48\uff30\uff4f\uff43\uff4b\uff45\uff54"))
        assertEquals(DevicePlatform.UNKNOWN, DevicePlatform.detect("Heltec unknown"))
        assertEquals(DevicePlatform.UNKNOWN, DevicePlatform.detect(""))
    }
}
