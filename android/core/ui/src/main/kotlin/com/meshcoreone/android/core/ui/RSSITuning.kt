// PortedFrom: MC1/Views/Components/RSSITuning.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.ui

import java.time.Duration

object RSSITuning {
    enum class SignalTier(val rawValue: Long, val fillLevel: Double) {
        WEAK(0, 0.33), MEDIUM(1, 0.66), STRONG(2, 1.0);
    }

    const val STRONG_THRESHOLD = -60L
    const val MEDIUM_THRESHOLD = -80L
    const val TIER_HYSTERESIS = 3L
    const val SMOOTHING_NEW_WEIGHT = 0.2
    const val UNAVAILABLE_RSSI = -127L
    val expiryTick: Duration = Duration.ofSeconds(2)
    val staleWindow: Duration = Duration.ofSeconds(4)

    fun isUsable(rssi: Long): Boolean = rssi < 0 && rssi != UNAVAILABLE_RSSI
    fun smooth(newRSSI: Long, previousRSSI: Long?): Long = previousRSSI?.let {
        (SMOOTHING_NEW_WEIGHT * newRSSI + (1 - SMOOTHING_NEW_WEIGHT) * it).toLong()
    } ?: newRSSI

    fun tier(currentTier: SignalTier?, smoothedRSSI: Long): SignalTier = when (currentTier) {
        SignalTier.STRONG -> if (smoothedRSSI < STRONG_THRESHOLD - TIER_HYSTERESIS) {
            if (smoothedRSSI < MEDIUM_THRESHOLD - TIER_HYSTERESIS) SignalTier.WEAK else SignalTier.MEDIUM
        } else SignalTier.STRONG
        SignalTier.MEDIUM -> when {
            smoothedRSSI >= STRONG_THRESHOLD + TIER_HYSTERESIS -> SignalTier.STRONG
            smoothedRSSI < MEDIUM_THRESHOLD - TIER_HYSTERESIS -> SignalTier.WEAK
            else -> SignalTier.MEDIUM
        }
        SignalTier.WEAK -> when {
            smoothedRSSI >= STRONG_THRESHOLD + TIER_HYSTERESIS -> SignalTier.STRONG
            smoothedRSSI >= MEDIUM_THRESHOLD + TIER_HYSTERESIS -> SignalTier.MEDIUM
            else -> SignalTier.WEAK
        }
        null -> when {
            smoothedRSSI >= STRONG_THRESHOLD -> SignalTier.STRONG
            smoothedRSSI >= MEDIUM_THRESHOLD -> SignalTier.MEDIUM
            else -> SignalTier.WEAK
        }
    }
}
