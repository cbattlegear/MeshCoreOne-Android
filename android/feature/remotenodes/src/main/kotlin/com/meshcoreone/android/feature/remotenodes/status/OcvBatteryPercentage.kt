// PortedFrom: MC1/Extensions/BatteryInfo+Display.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Feature-local mirror of the two percentage helpers the status screens read (WP-304 owns the original
// in core:ui, which has no Kotlin port of it yet). Verified with swiftc (oracles/status_foundation.swift.txt).
package com.meshcoreone.android.feature.remotenodes.status

import com.meshcoreone.android.feature.remotenodes.telemetry.swiftRounded

/** Battery-percentage helpers over a millivolt reading (Swift `BatteryInfo` display extension). */
object OcvBatteryPercentage {
    private const val OCV_POINT_COUNT = 11
    private const val SEGMENT_COUNT = 10
    private const val PERCENT_PER_SEGMENT = 10
    private const val MILLIVOLTS_PER_VOLT = 1000.0
    private const val LIPO_EMPTY_VOLTS = 3.0
    private const val LIPO_RANGE_VOLTS = 1.2
    private const val FULL = 100.0

    /** Swift `BatteryInfo.percentage`: linear LiPo estimate (4.2 V = 100 %, 3.0 V = 0 %), truncated. */
    fun linear(millivolts: Long): Int {
        val voltage = millivolts / MILLIVOLTS_PER_VOLT
        val percent = ((voltage - LIPO_EMPTY_VOLTS) / LIPO_RANGE_VOLTS) * FULL
        return minOf(FULL, maxOf(0.0, percent)).toInt()
    }

    /**
     * Swift `BatteryInfo.percentage(using:)`: interpolates within the 11-point curve (100 %, 90 % ... 0 %),
     * rounding each segment's tenth half away from zero; any other curve length falls back to [linear].
     */
    fun percentage(millivolts: Long, ocvArray: List<Long>): Int {
        if (ocvArray.size != OCV_POINT_COUNT) return linear(millivolts)
        if (millivolts >= ocvArray[0]) return 100
        if (millivolts <= ocvArray[SEGMENT_COUNT]) return 0
        for (index in 0 until SEGMENT_COUNT) {
            val upper = ocvArray[index]
            val lower = ocvArray[index + 1]
            if (millivolts >= lower) {
                val segmentPercent = (millivolts - lower).toDouble() / (upper - lower).toDouble()
                val basePercent = (SEGMENT_COUNT - index - 1) * PERCENT_PER_SEGMENT
                return basePercent + swiftRounded(segmentPercent * PERCENT_PER_SEGMENT).toInt()
            }
        }
        return 0
    }
}
