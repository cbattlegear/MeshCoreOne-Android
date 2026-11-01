// PortedFrom: MC1Tests/Views/Components/RSSITuningTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.ui

import kotlin.test.*
import com.meshcoreone.android.core.designsystem.ThemeColor
import com.meshcoreone.android.core.designsystem.WCAGContrast
import com.meshcoreone.android.core.designsystem.toThemeColor
import androidx.compose.ui.graphics.Color
import org.junit.Test

class RSSITuningTest : SourceCaseProof() {
    private val strong = RSSITuning.STRONG_THRESHOLD
    private val medium = RSSITuning.MEDIUM_THRESHOLD
    private val band = RSSITuning.TIER_HYSTERESIS

    @OriginalCase("RSSITuningTests::isUsable accepts negative dBm readings and rejects the unavailable sentinels(rssi : Int , expected : Bool)", 5)
    @Test fun usableReadings() = prove(5) {
        for ((rssi, expected) in listOf(-50L to true, -1L to true, -127L to false, 0L to false, 10L to false)) {
            assertEquals(expected, RSSITuning.isUsable(rssi))
        }
    }
    @OriginalCase("RSSITuningTests::smooth returns the new sample unchanged when there is no prior reading()")
    @Test fun firstSample() = prove { assertEquals(-50L, RSSITuning.smooth(-50, null)) }
    @OriginalCase("RSSITuningTests::smooth weights the newest sample at 0.2 against the previous smoothed value()")
    @Test fun smoothing() = prove { assertEquals(-66L, RSSITuning.smooth(-50, -70)) }

    @Test fun additionalUnequalSamplesPinTheWeightAndTruncationIndependently() {
        for ((new, previous, expected) in listOf(
            Triple(-55L, -50L, -51L),
            Triple(-41L, -80L, -72L),
            Triple(-80L, -41L, -48L),
        )) {
            assertEquals(expected, RSSITuning.smooth(new, previous))
        }
    }
    @OriginalCase("RSSITuningTests::first reading maps directly at the tier thresholds(rssi : Int , expected : RSSITuning . SignalTier)", 3)
    @Test fun firstTier() = prove(3) {
        for ((rssi, expected) in listOf(-60L to RSSITuning.SignalTier.STRONG,
            -80L to RSSITuning.SignalTier.MEDIUM, -81L to RSSITuning.SignalTier.WEAK)) {
            assertEquals(expected, RSSITuning.tier(null, rssi))
        }
    }
    @OriginalCase("RSSITuningTests::a reading inside the hysteresis band holds the current tier()")
    @Test fun holds() = prove {
        assertEquals(RSSITuning.SignalTier.STRONG, RSSITuning.tier(RSSITuning.SignalTier.STRONG, strong - band))
        assertEquals(RSSITuning.SignalTier.MEDIUM, RSSITuning.tier(RSSITuning.SignalTier.MEDIUM, -70))
        assertEquals(RSSITuning.SignalTier.WEAK, RSSITuning.tier(RSSITuning.SignalTier.WEAK, medium + band - 1))
    }
    @OriginalCase("RSSITuningTests::a reading clearing the band by the hysteresis margin flips the tier()")
    @Test fun clearsBand() = prove {
        assertEquals(RSSITuning.SignalTier.MEDIUM, RSSITuning.tier(RSSITuning.SignalTier.WEAK, medium + band))
        assertEquals(RSSITuning.SignalTier.WEAK, RSSITuning.tier(RSSITuning.SignalTier.STRONG, medium - band - 1))
    }
    @OriginalCase("RSSITuningTests::from medium, a reading moves up, drops, or holds at the band edges()")
    @Test fun mediumEdges() = prove {
        assertEquals(RSSITuning.SignalTier.MEDIUM, RSSITuning.tier(RSSITuning.SignalTier.MEDIUM, -70))
        assertEquals(RSSITuning.SignalTier.STRONG, RSSITuning.tier(RSSITuning.SignalTier.MEDIUM, strong + band))
        assertEquals(RSSITuning.SignalTier.MEDIUM, RSSITuning.tier(RSSITuning.SignalTier.MEDIUM, medium - band))
        assertEquals(RSSITuning.SignalTier.WEAK, RSSITuning.tier(RSSITuning.SignalTier.MEDIUM, medium - band - 1))
    }
    @OriginalCase("RSSITuningTests::a single strong reading jumps weak straight to strong()")
    @Test fun jumps() = prove {
        assertEquals(RSSITuning.SignalTier.STRONG, RSSITuning.tier(RSSITuning.SignalTier.WEAK, strong + band))
    }
    @OriginalCase("RSSITuningTests::fillLevel maps each tier to its glyph fill(tier : RSSITuning . SignalTier , expected : Double)", 3)
    @Test fun fillLevels() = prove(3) {
        for ((tier, expected) in listOf(RSSITuning.SignalTier.STRONG to 1.0,
            RSSITuning.SignalTier.MEDIUM to 0.66, RSSITuning.SignalTier.WEAK to 0.33)) {
            assertEquals(expected, tier.fillLevel)
        }
    }
    @OriginalCase("RSSITuningTests::color maps each tier to its glyph color()")
    @NativeAdaptation("licensed-native-green-yellow-red-roles")
    @Test fun colorMeanings() = prove {
        val vectors = listOf(
            Triple(RSSITuning.SignalTier.STRONG, SharedStatusColorRole.GREEN, ThemeColor.hex(0x34C759)),
            Triple(RSSITuning.SignalTier.MEDIUM, SharedStatusColorRole.YELLOW, ThemeColor.hex(0xFFCC00)),
            Triple(RSSITuning.SignalTier.WEAK, SharedStatusColorRole.RED, ThemeColor.hex(0xFF3B30)),
        )
        for ((tier, role, seed) in vectors) {
            assertEquals(role, tier.colorRole)
            assertEquals(seed, tier.colorRole.sourceSeed)
            val native = statusForeground(tier.colorRole, Color.Black, Color.White, false)
            assertTrue(WCAGContrast.contrastRatio(native.toThemeColor(), ThemeColor.WHITE) >= 4.5)
        }
    }
}
