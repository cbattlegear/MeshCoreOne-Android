// PortedFrom: MC1/Views/RemoteNodes/SharedNodeSettingsViews.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.settings

import kotlin.math.abs

/** Duration units of Swift's `.units(allowed: [.days, .hours, .minutes, .seconds])`. */
enum class DriftUnit(val seconds: Long, val capacity: Long) {
    DAY(86_400, Long.MAX_VALUE), HOUR(3_600, 24), MINUTE(60, 60), SECOND(1, 60)
}

data class DriftPart(val unit: DriftUnit, val value: Long)

/**
 * Device Info clock warning (Swift `NodeDeviceInfoSection.clockDriftWarning`): shown when the node's
 * clock is 300 s or more away from the reference clock. [ahead] picks `clockAhead` over `clockBehind`;
 * [parts] is the `Duration.formatted(.units(maximumUnitCount: 2))` magnitude, which the screen renders
 * with its locale's abbreviated unit names ("5 min, 2 sec").
 */
data class ClockDriftWarning(val ahead: Boolean, val parts: List<DriftPart>) {
    companion object {
        /** Clock drift below this magnitude is normal RTC scatter and not shown. */
        const val THRESHOLD_SECONDS = 300.0
        private const val MAX_UNITS = 2

        fun of(drift: Double?): ClockDriftWarning? {
            if (drift == null || !drift.isFinite() || abs(drift) < THRESHOLD_SECONDS) return null
            return ClockDriftWarning(drift > 0, magnitudeParts(abs(drift)))
        }

        /**
         * Reproduces Foundation's output for the oracle cases (`drift.swift.txt`): whole seconds
         * (half up), the first two non-zero units, and the dropped tail rounded half up into the
         * second unit with a carry into the first ("11 days, 14 hr"; 86399 s is "24 hr, 0 min").
         */
        internal fun magnitudeParts(seconds: Double): List<DriftPart> {
            val total = Math.floor(seconds + 0.5).toLong()
            val values = DriftUnit.entries.map { it to (total / it.seconds) % it.capacity }
            val shown = values.filter { it.second != 0L }.take(MAX_UNITS).toMutableList()
            if (shown.size == MAX_UNITS) {
                val last = shown[MAX_UNITS - 1].first
                val remainder = total % last.seconds
                if (remainder * 2 >= last.seconds) {
                    var rounded = shown[MAX_UNITS - 1].second + 1
                    var first = shown[0].second
                    if (rounded >= last.capacity) {
                        rounded = 0
                        first += 1
                    }
                    shown[0] = shown[0].first to first
                    shown[MAX_UNITS - 1] = last to rounded
                }
            }
            return shown.map { DriftPart(it.first, it.second) }
        }
    }
}
