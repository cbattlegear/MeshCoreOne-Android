// PortedFrom: MC1Services/Sources/MC1Services/Services/RadioOptions.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.logging.Logger
import kotlin.math.abs

object RadioOptions {
    val bandwidthsHz: SnapshotList<UInt> = listOf(
        7_800u, 10_400u, 15_600u, 20_800u, 31_250u, 41_700u, 62_500u, 125_000u, 250_000u, 500_000u,
    ).snapshot()
    val bandwidthsKHz: SnapshotList<Double> = bandwidthsHz.map { it.toDouble() / 1000.0 }.snapshot()
    val spreadingFactors: LongRange = 5L..12L
    val codingRates: LongRange = 5L..8L

    fun formatBandwidth(hz: UInt): String = when (hz) {
        7_800u -> "7.8"
        10_400u -> "10.4"
        15_600u -> "15.6"
        20_800u -> "20.8"
        31_250u -> "31.25"
        41_700u -> "41.7"
        62_500u -> "62.5"
        125_000u -> "125"
        250_000u -> "250"
        500_000u -> "500"
        else -> BigDecimal.valueOf(hz.toLong()).movePointLeft(3)
            .setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
    }

    fun nearestBandwidth(hz: UInt): UInt {
        if (hz in bandwidthsHz) return hz
        val nearest = bandwidthsHz.minBy { abs(it.toLong() - hz.toLong()) }
        Logger.getLogger("MeshCore.Radio").fine("Nonstandard bandwidth mapped to the nearest catalog option")
        return nearest
    }
}
