// AndroidOnly: WP-315 native tests pinning LOS number formatting and frequency text to the Swift oracle (no source test file exists).
package com.meshcoreone.android.feature.tools.los

import com.meshcoreone.android.core.l10n.R
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class LosFormattingTest {
    @Test
    fun `diffraction and path loss formatting match Foundation`() {
        // value -> (en diffraction, de diffraction, en path loss, de path loss); null diffraction = negligible.
        val cases = listOf(
            Triple(0.0, null, "0.0 dB" to "0,0 dB"),
            Triple(0.05, null, "0.0 dB" to "0,0 dB"),
            Triple(0.1, "+ 0.1 dB" to "+ 0,1 dB", "0.1 dB" to "0,1 dB"),
            Triple(-0.1, "+ -0.1 dB" to "+ -0,1 dB", "-0.1 dB" to "-0,1 dB"),
            Triple(0.15, "+ 0.2 dB" to "+ 0,2 dB", "0.2 dB" to "0,2 dB"),
            Triple(0.25, "+ 0.2 dB" to "+ 0,2 dB", "0.2 dB" to "0,2 dB"),
            Triple(0.35, "+ 0.4 dB" to "+ 0,4 dB", "0.4 dB" to "0,4 dB"),
            Triple(8.45, "+ 8.4 dB" to "+ 8,4 dB", "8.4 dB" to "8,4 dB"),
            Triple(126.65, "+ 126.6 dB" to "+ 126,6 dB", "126.6 dB" to "126,6 dB"),
            Triple(126.55, "+ 126.6 dB" to "+ 126,6 dB", "126.6 dB" to "126,6 dB"),
            Triple(1234.56, "+ 1,234.6 dB" to "+ 1.234,6 dB", "1,234.6 dB" to "1.234,6 dB"),
            Triple(-0.04, null, "-0.0 dB" to "-0,0 dB"),
            Triple(22.3, "+ 22.3 dB" to "+ 22,3 dB", "22.3 dB" to "22,3 dB"),
        )
        cases.forEach { (value, diffraction, pathLoss) ->
            assertEquals(diffraction?.first, LosFormatters.formatDiffractionLoss(value, Locale.US), "diff.en.$value")
            assertEquals(diffraction?.second, LosFormatters.formatDiffractionLoss(value, Locale.GERMANY), "diff.de.$value")
            assertEquals(pathLoss.first, LosFormatters.formatPathLoss(value, Locale.US), "pathloss.en.$value")
            assertEquals(pathLoss.second, LosFormatters.formatPathLoss(value, Locale.GERMANY), "pathloss.de.$value")
        }
        assertNull(LosFormatters.formatDiffractionLoss(Double.NaN, Locale.US))
    }

    @Test
    fun `clearance percent and k-factor formatting match Foundation`() {
        mapOf(-15.0 to 0, -0.5 to 0, 0.0 to 0, 47.9 to 47, 99.99 to 99, 100.0 to 100, 150.0 to 100, 59.9999 to 59)
            .forEach { (percent, expected) -> assertEquals(expected, LosFormatters.formatClearancePercent(percent), "clear.$percent") }
        // Swift min/max keep the non-NaN bound: min(100, NaN) == 100.
        assertEquals(100, LosFormatters.formatClearancePercent(Double.NaN))
        mapOf(1.0 to "1.00", 4.0 / 3.0 to "1.33", 4.0 to "4.00", 1.335 to "1.34", 1.325 to "1.32", 1.005 to "1.00", 2.675 to "2.68")
            .forEach { (k, expected) ->
                assertEquals("k=$expected", LosFormatters.formatKFactor(k, Locale.US), "k.en.$k")
                assertEquals("k=${expected.replace('.', ',')}", LosFormatters.formatKFactor(k, Locale.GERMANY), "k.de.$k")
            }
    }

    @Test
    fun `status labels and relay headline follow the result card views`() {
        assertEquals(R.string.l10n_app_tools_tools_lineofsight_status_clear, LosFormatters.statusLabel(ClearanceStatus.CLEAR))
        assertEquals(R.string.l10n_app_tools_tools_lineofsight_status_marginal, LosFormatters.statusLabel(ClearanceStatus.MARGINAL))
        assertEquals(
            R.string.l10n_app_tools_tools_lineofsight_status_partialobstruction,
            LosFormatters.statusLabel(ClearanceStatus.PARTIAL_OBSTRUCTION),
        )
        assertEquals(R.string.l10n_app_tools_tools_lineofsight_status_blocked, LosFormatters.statusLabel(ClearanceStatus.BLOCKED))
        assertEquals("Clear", englishString("l10n_app_tools_tools_lineofsight_status_clear"))
        assertEquals(R.string.l10n_app_tools_tools_lineofsight_status_blockedsubtitle, LosFormatters.blockedSubtitle)
        assertFalse(LosFormatters.showsClearancePercent(ClearanceStatus.BLOCKED))
        assertTrue(LosFormatters.showsClearancePercent(ClearanceStatus.PARTIAL_OBSTRUCTION))
        val relay = RelayPathAnalysisResult(
            SegmentAnalysisResult("A", "R", ClearanceStatus.CLEAR, 5200.0, 85.0),
            SegmentAnalysisResult("R", "B", ClearanceStatus.PARTIAL_OBSTRUCTION, 7200.0, 45.0),
        )
        assertEquals(45.0, LosFormatters.relayHeadlineClearancePercent(relay))
        assertEquals(ClearanceStatus.PARTIAL_OBSTRUCTION, relay.overallStatus)
        assertEquals(12.4, relay.totalDistanceKm)
    }

    @Test
    fun `frequency parsing follows the Swift Double grammar`() {
        val accepted = mapOf(
            "868.5" to 868.5, "868,5" to 868.5, "906" to 906.0, "1e3" to 1000.0, "1E3" to 1000.0, "0x10" to 16.0,
            "0x1p3" to 8.0, "1." to 1.0, ".5" to 0.5, "+5" to 5.0, "0.0001" to 0.0001, "0x.8" to 0.5,
            "00906.50" to 906.5, "0X1P-2" to 0.25, "9007199254740993" to 9007199254740992.0,
        )
        accepted.forEach { (text, value) -> assertEquals(value, LineOfSightFrequencyText.parseFrequency(text), "parse |$text|") }
        listOf("inf", "Infinity", "INF", "1e400").forEach {
            assertEquals(Double.POSITIVE_INFINITY, LineOfSightFrequencyText.parseFrequency(it), "parse |$it|")
        }
        assertBits("0000000000000001", requireNotNull(LineOfSightFrequencyText.parseFrequency("4.9e-324")))
        val rejected = listOf(
            "", "abc", "0", "-906", " 906", "906 ", "nan", "906f", "906d", "1_000", "1,000.5", "868..5", "+", "-0", "1e",
            "0x", "٣", "\t906", "1e-400", "infinit", "-inf", "nan(0x1)",
        )
        rejected.forEach { assertNull(LineOfSightFrequencyText.parseFrequency(it), "parse |$it|") }
    }

    @Test
    fun `frequency editing format matches C printf rounding`() {
        val cases = mapOf(
            906.0 to "906", 868.5 to "868.5", 869.525 to "869.5", 915.0 to "915", 0.15 to "0.1", 0.25 to "0.2",
            0.35 to "0.3", 0.05 to "0.1", 433.175 to "433.2", 2400.45 to "2400.4", 1e-7 to "0.0", 0.95 to "0.9",
            868.45 to "868.5", 868.55 to "868.5", 902.125 to "902.1", 1e15 to "1000000000000000",
            Double.POSITIVE_INFINITY to "inf", Double.NaN to "nan",
        )
        cases.forEach { (value, expected) ->
            assertEquals(expected, LineOfSightFrequencyText.formatFrequencyForEditing(value), "fmtfreq.$value")
        }
        assertEquals("868.5", LineOfSightFrequencyText.normalizeInput("868,5"))
        assertEquals("1.000.5", LineOfSightFrequencyText.normalizeInput("1,000,5"))
    }

    @Test
    fun `large frequency editing preserves values across the integer boundary`() {
        val integerLimit = Long.MAX_VALUE.toDouble()
        val values = listOf(
            Math.nextDown(integerLimit),
            integerLimit,
            Math.nextUp(integerLimit),
            1e20,
            Double.MAX_VALUE,
        )
        values.forEach { value ->
            val formatted = LineOfSightFrequencyText.formatFrequencyForEditing(value)
            assertEquals(value, LineOfSightFrequencyText.parseFrequency(formatted), "round trip $value")
        }
        assertEquals("9223372036854774784", LineOfSightFrequencyText.formatFrequencyForEditing(Math.nextDown(integerLimit)))
        assertEquals("9223372036854775808.0", LineOfSightFrequencyText.formatFrequencyForEditing(integerLimit))
        assertEquals("-9223372036854775808", LineOfSightFrequencyText.formatFrequencyForEditing(Long.MIN_VALUE.toDouble()))
        assertEquals("-9223372036854777856.0", LineOfSightFrequencyText.formatFrequencyForEditing(Math.nextDown(Long.MIN_VALUE.toDouble())))
    }

    @Test
    fun `device kHz seed and option tables match the source`() {
        assertBits("408b2c3333333333", LineOfSightState.frequencyMHzFromDeviceKHz(869_525u), "freq.kHz.869525")
        assertBits("408c743333333333", LineOfSightState.frequencyMHzFromDeviceKHz(910_525u), "freq.kHz.910525")
        assertEquals(listOf(1.0, 4.0 / 3.0, 4.0), RefractionPreset.entries.map { it.refractionK })
        assertEquals(RefractionPreset.STANDARD, RefractionPreset.of(4.0 / 3.0))
        assertNull(RefractionPreset.of(1.33))
        assertEquals(R.string.l10n_app_tools_tools_lineofsight_refraction_ducting, RefractionPreset.DUCTING.label)
        assertEquals(0.3048, LineOfSightHeightEditor.heightStepMeters(usesMetricSystem = false))
        assertEquals(1.0, LineOfSightHeightEditor.heightStepMeters(usesMetricSystem = true))
        assertEquals(0.0..200.0, LineOfSightHeightEditor.MIN_HEIGHT_METERS..LineOfSightHeightEditor.MAX_HEIGHT_METERS)
    }
}
