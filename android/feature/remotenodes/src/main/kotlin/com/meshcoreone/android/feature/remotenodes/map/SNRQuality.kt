// PortedFrom: MC1Services/Sources/MC1Services/Models/Rendering/SNRQuality.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Feature-local mirror pending WP-213's core:services SNRQuality (features may not depend on core:services).
package com.meshcoreone.android.feature.remotenodes.map

/**
 * LoRa SNR quality buckets (Swift `SNRQuality`). Thresholds are strict-greater: above +6 dB is
 * excellent, above 0 dB good, above -6 dB fair, anything else (including NaN) poor; a missing SNR is
 * unknown.
 */
enum class SNRQuality(val barLevel: Double) {
    EXCELLENT(1.0),
    GOOD(0.75),
    FAIR(0.5),
    POOR(0.25),
    UNKNOWN(0.0),
    ;

    companion object {
        private const val EXCELLENT_ABOVE_DB = 6.0
        private const val GOOD_ABOVE_DB = 0.0
        private const val FAIR_ABOVE_DB = -6.0

        fun of(snr: Double?): SNRQuality = when {
            snr == null -> UNKNOWN
            snr > EXCELLENT_ABOVE_DB -> EXCELLENT
            snr > GOOD_ABOVE_DB -> GOOD
            snr > FAIR_ABOVE_DB -> FAIR
            else -> POOR
        }
    }
}
