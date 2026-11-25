// PortedFrom: MC1/Views/Tools/NoiseFloorViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.diagnostics.noisefloor

import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import java.time.Instant
import java.util.UUID

/** One radio-stats sample. */
data class NoiseFloorReading(
    val id: UUID,
    val timestamp: Instant,
    val noiseFloor: Short,
    val lastRSSI: Byte,
    val lastSNR: Double,
)

/** Minimum, maximum and mean noise floor over the retained readings. */
data class NoiseFloorStatistics(val min: Short, val max: Short, val average: Double) {
    companion object {
        fun of(readings: List<NoiseFloorReading>): NoiseFloorStatistics? {
            if (readings.isEmpty()) return null
            val values = readings.map { it.noiseFloor }
            return NoiseFloorStatistics(
                min = values.min(),
                max = values.max(),
                average = values.sumOf { it.toLong() }.toDouble() / values.size,
            )
        }
    }
}

/**
 * Noise-floor quality bands. [labelId] is the core:l10n string; [sourceSymbol] keeps the Swift SF
 * Symbol name as the semantic icon token the UI layer maps to a Material icon.
 */
enum class NoiseFloorQuality(val labelId: Int, val sourceSymbol: String) {
    EXCELLENT(AppToolsStrings.toolsNoiseFloorQualityExcellent, "checkmark.circle.fill"),
    GOOD(AppToolsStrings.toolsNoiseFloorQualityGood, "circle.fill"),
    FAIR(AppToolsStrings.toolsNoiseFloorQualityFair, "exclamationmark.circle.fill"),
    POOR(AppToolsStrings.toolsNoiseFloorQualityPoor, "xmark.circle.fill"),
    UNKNOWN(AppToolsStrings.toolsNoiseFloorQualityUnknown, "questionmark.circle"),
    ;

    companion object {
        private const val EXCELLENT_MAX: Short = -100
        private const val GOOD_MAX: Short = -90
        private const val FAIR_MAX: Short = -80

        fun from(noiseFloor: Short): NoiseFloorQuality = when {
            noiseFloor <= EXCELLENT_MAX -> EXCELLENT
            noiseFloor <= GOOD_MAX -> GOOD
            noiseFloor <= FAIR_MAX -> FAIR
            else -> POOR
        }
    }
}
