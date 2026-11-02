// PortedFrom: MC1/Views/Tools/TracePath/TraceResultsSectionView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/TracePath/BatchRTTRow.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.trace

/** Batch progress or completion line of the result card (source `TraceResultsSectionView`). */
data class BatchStatus(val isComplete: Boolean, val currentIndex: Int, val size: Int, val successCount: Int) {
    /** Integer percentage as the source computed it; 0 for an empty batch. */
    val successPercent: Int get() = if (size > 0) successCount * 100 / size else 0
}

/** Average and range of successful round trips (source `BatchRTTRow`). */
data class BatchRtt(val averageMs: Long, val minMs: Long, val maxMs: Long)

/** A cancelled partial batch keeps its counts without being labelled complete or running. */
val TracePathState.batchStatus: BatchStatus?
    get() = if (batchEnabled && batchPresentationActive && (isBatchInProgress || completedResults.isNotEmpty())) {
        BatchStatus(isBatchComplete, currentTraceIndex, batchSize, successCount)
    } else {
        null
    }

/** Aggregates over the successful traces, available while running, when complete and after a cancel. */
val TracePathState.batchRtt: BatchRtt?
    get() {
        if (!batchEnabled || !batchPresentationActive) return null
        val average = averageRTT ?: return null
        val min = minRTT ?: return null
        val max = maxRTT ?: return null
        return BatchRtt(average, min, max)
    }

/** A batch has partial results to show even when it was cancelled before completing. */
val TracePathState.hasBatchResults: Boolean get() = batchEnabled && batchPresentationActive && completedResults.isNotEmpty()

/** Failed attempts must not hide an earlier successful batch response. */
val TracePathState.displayedResult: TraceResult?
    get() = if (batchEnabled && batchPresentationActive) successfulResults.lastOrNull() ?: result else result
