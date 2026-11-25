// PortedFrom: MC1Services/Sources/MC1Services/Models/Rendering/SNRQuality.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

/** Four-tier LoRa SNR (dB) signal-quality classification used across the app. */
enum class SNRQuality(
    /** Bar level (0–1) for the cellular-bars indicator. */
    val barLevel: Double,
    /** Developer-facing English label for logs; the UI uses the localized label. */
    val qualityLabel: String,
) {
    EXCELLENT(1.0, "Excellent"), // SNR > +6 dB
    GOOD(0.75, "Good"), // SNR > 0 dB
    FAIR(0.5, "Fair"), // SNR > -6 dB
    POOR(0.25, "Weak"), // SNR <= -6 dB (and NaN, which fails every comparison as in Swift)
    UNKNOWN(0.0, "Unknown"); // nil SNR

    companion object {
        private const val EXCELLENT_ABOVE = 6.0
        private const val GOOD_ABOVE = 0.0
        private const val FAIR_ABOVE = -6.0

        /** Swift `SNRQuality(snr:)`. */
        fun of(snr: Double?): SNRQuality = when {
            snr == null -> UNKNOWN
            snr > EXCELLENT_ABOVE -> EXCELLENT
            snr > GOOD_ABOVE -> GOOD
            snr > FAIR_ABOVE -> FAIR
            else -> POOR
        }
    }
}
