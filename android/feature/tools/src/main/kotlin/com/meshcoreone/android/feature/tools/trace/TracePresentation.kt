// PortedFrom: MC1/Views/Tools/TracePath/SavedPathRow.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Tools/TracePath/ComparisonRowView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Tools/TracePath/TraceResultHopRow.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Tools/TracePath/SavedPathDetailView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Tools/TracePath/TracePathView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Only the engine-free decisions these views computed inline; layout, colors and formatting stay in the UI.
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.model.SavedTracePathDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes

/** List or map builder (source `TracePathViewMode`). */
enum class TracePathViewMode { LIST, MAP }

/** Saved-path health dot: >= 90% healthy, >= 50% degraded, else poor. */
enum class SavedPathHealth {
    HEALTHY, DEGRADED, POOR;

    companion object {
        fun of(successRate: Long): SavedPathHealth = when {
            successRate >= 90 -> HEALTHY
            successRate >= 50 -> DEGRADED
            else -> POOR
        }
    }
}

enum class RttTrend { INCREASING, DECREASING, STABLE }

/** Sparkline accessibility summary: integer mean and half-over-half trend (±50 ms). */
data class RttSummary(val averageMs: Long, val trend: RttTrend) {
    companion object {
        /** `null` when there is no response data. */
        fun of(path: SavedTracePathDTO): RttSummary? = of(path.recentRTTs)

        fun of(rtts: List<Long>): RttSummary? {
            if (rtts.isEmpty()) return null
            val average = rtts.sum() / rtts.size
            if (rtts.size < 2) return RttSummary(average, RttTrend.STABLE)
            val half = rtts.size / 2
            val divisor = maxOf(1, half)
            val firstHalf = rtts.take(half).sum() / divisor
            val secondHalf = rtts.takeLast(half).sum() / divisor
            val trend = when {
                secondHalf > firstHalf + 50 -> RttTrend.INCREASING
                secondHalf < firstHalf - 50 -> RttTrend.DECREASING
                else -> RttTrend.STABLE
            }
            return RttSummary(average, trend)
        }
    }
}

/** Current RTT against a previous run; percent is 0 when the previous RTT is 0. */
data class RttComparison(val differenceMs: Long, val percentChange: Double) {
    val increased: Boolean get() = differenceMs > 0
    val changed: Boolean get() = differenceMs != 0L

    companion object {
        fun of(currentMs: Long, previousMs: Long): RttComparison {
            val difference = currentMs - previousMs
            val percent = if (previousMs > 0) difference.toDouble() / previousMs.toDouble() * 100 else 0.0
            return RttComparison(difference, percent)
        }
    }
}

object TraceHopDisplay {
    /** Latest SNR while a batch runs, the batch average once complete, else the hop's own SNR. */
    fun displaySnr(hopSnr: Double, batchStats: HopStats?, latestSnr: Double?, isBatchInProgress: Boolean): Double = when {
        isBatchInProgress -> latestSnr ?: hopSnr
        batchStats != null -> batchStats.avg
        else -> hopSnr
    }

    /** Saved-path chips: one uppercase hex string per hop at the saved width. */
    fun hopHexStrings(pathData: Bytes, hashSize: Int): List<String> = TracePathHex.chunks(pathData, hashSize)
}
