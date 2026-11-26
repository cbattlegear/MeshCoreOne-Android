// PortedFrom: MC1/Views/Tools/LineOfSight/RFSettingsSectionView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Tools/LineOfSight/HeightEditorGrid.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Tools/LineOfSight/PointHeightEditorView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Tools/LineOfSight/RepeaterHeightEditorView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.los

import com.meshcoreone.android.core.l10n.generated.AppToolsStrings

/**
 * Frequency text handling from `LineOfSightViewModel.parseFrequency`/`formatFrequencyForEditing`
 * and `FrequencyInputRow`: a fixed '.' separator independent of locale, Swift `Double(String)`
 * grammar (no surrounding whitespace, hex floats and "inf" accepted, no type suffixes).
 */
object LineOfSightFrequencyText {
    private val SWIFT_DOUBLE = Regex(
        "[+-]?(?:" +
            "(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?" +
            "|0[xX](?:[0-9a-fA-F]+\\.?[0-9a-fA-F]*|\\.[0-9a-fA-F]+)(?:[pP][+-]?[0-9]+)?" +
            "|[iI][nN][fF](?:[iI][nN][iI][tT][yY])?" +
            "|[nN][aA][nN](?:\\([0-9A-Za-z_]*\\))?" +
            ")",
    )
    private val HEX_EXPONENT = Regex("[pP]")

    /** A positive MHz value, or null for empty, unparseable or non-positive input (',' and '.' both accepted). */
    fun parseFrequency(text: String): Double? {
        val value = swiftDouble(normalizeInput(text)) ?: return null
        return if (value > 0) value else null
    }

    /** Locale-stable editing text with Swift's integer-or-one-decimal formatting. */
    fun formatFrequencyForEditing(value: Double): String =
        if (value % 1.0 == 0.0 && value >= Long.MIN_VALUE.toDouble() && value < 9223372036854775808.0) {
            value.toLong().toString()
        } else {
            printfOneDecimal(value)
        }

    /** The text field rewrites ',' to '.' as the user types. */
    fun normalizeInput(text: String): String = text.replace(",", ".")

    private fun swiftDouble(text: String): Double? {
        if (!SWIFT_DOUBLE.matches(text)) return null
        val unsigned = text.trimStart('+', '-')
        val negative = text.startsWith("-")
        val magnitude = when {
            unsigned.startsWith("i", ignoreCase = true) -> Double.POSITIVE_INFINITY
            unsigned.startsWith("n", ignoreCase = true) -> Double.NaN
            unsigned.startsWith("0x", ignoreCase = true) ->
                (if (HEX_EXPONENT.containsMatchIn(unsigned)) unsigned else "${unsigned}p0").toDouble()
            else -> unsigned.toDouble()
        }
        return if (negative) -magnitude else magnitude
    }
}

/** Refraction k-factor choices offered by the RF settings picker, in display order. */
enum class RefractionPreset(val refractionK: Double) {
    NONE(1.0),
    STANDARD(4.0 / 3.0),
    DUCTING(4.0),
    ;

    val label: Int
        get() = when (this) {
            NONE -> AppToolsStrings.toolsLineOfSightRefractionNone
            STANDARD -> AppToolsStrings.toolsLineOfSightRefractionStandard
            DUCTING -> AppToolsStrings.toolsLineOfSightRefractionDucting
        }

    companion object {
        /** The picker's selected tag, or null when k matches none of the tags exactly. */
        fun of(refractionK: Double): RefractionPreset? = entries.firstOrNull { it.refractionK == refractionK }
    }
}

/** Antenna height editor bounds shared by the point and repeater editors. */
object LineOfSightHeightEditor {
    const val MIN_HEIGHT_METERS: Double = 0.0
    const val MAX_HEIGHT_METERS: Double = 200.0
    private const val FOOT_IN_METERS: Double = 0.3048
    private const val METRIC_STEP_METERS: Double = 1.0

    /** Stepper increment: one foot outside metric locales, one meter otherwise. */
    fun heightStepMeters(usesMetricSystem: Boolean): Double = if (usesMetricSystem) METRIC_STEP_METERS else FOOT_IN_METERS
}
