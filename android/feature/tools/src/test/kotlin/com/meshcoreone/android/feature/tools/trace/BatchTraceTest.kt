// PortedFrom: MC1Tests/ViewModels/BatchTraceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatchTraceTest {
    private val harness = TraceHarness()
    private val holder = harness.holder

    private fun batchResult(hopSnrs: List<Double>, durationMs: Long, success: Boolean = true): TraceResult {
        val hops = listOf(hop(0.0, start = true)) + hopSnrs.map { hop(it) } + hop(hopSnrs.lastOrNull() ?: 0.0, end = true)
        return result(hops, durationMs, success)
    }

    private fun seed(vararg results: TraceResult, index: Int? = null, size: Int? = null) {
        holder.editStateForTesting { state ->
            state.copy(
                completedResults = results.toList(),
                currentTraceIndex = index ?: state.currentTraceIndex,
                batchSize = size ?: state.batchSize,
            )
        }
    }

    @Test @OriginalCase("BatchTraceStateTests::batch properties have correct defaults()")
    fun `batch properties have correct defaults`() {
        val state = harness.state
        assertFalse(state.batchEnabled)
        assertEquals(3, state.batchSize)
        assertEquals(0, state.currentTraceIndex)
        assertTrue(state.completedResults.isEmpty())
        assertFalse(state.isBatchInProgress)
        assertFalse(state.isBatchComplete)
    }

    @Test @OriginalCase("BatchTraceStateTests::successfulResults filters to successful traces only()")
    fun `successfulResults filters to successful traces only`() {
        val success = result(emptyList(), 100)
        seed(success, result(emptyList(), 0, success = false), success)
        assertEquals(2, harness.state.successfulResults.size)
    }

    @Test @OriginalCase("BatchTraceStateTests::successCount returns number of successful traces()")
    fun `successCount returns number of successful traces`() {
        val success = result(emptyList(), 100)
        seed(success, result(emptyList(), 0, success = false), success)
        assertEquals(2, harness.state.successCount)
    }

    @Test @OriginalCase("BatchTraceStateTests::batchEnabled didSet clears batch state when disabled()")
    fun `batchEnabled didSet clears batch state when disabled`() {
        holder.setBatchEnabled(true)
        seed(result(emptyList(), 100), index = 3)
        holder.setBatchEnabled(false)
        assertEquals(0, harness.state.currentTraceIndex)
        assertTrue(harness.state.completedResults.isEmpty())
    }

    @Test @OriginalCase("BatchAggregateTests::RTT aggregates compute correctly()")
    fun `RTT aggregates compute correctly`() {
        holder.setBatchEnabled(true)
        seed(batchResult(listOf(5.0), 100), batchResult(listOf(5.0), 200), batchResult(listOf(5.0), 150))
        assertEquals(150L, harness.state.averageRTT)
        assertEquals(100L, harness.state.minRTT)
        assertEquals(200L, harness.state.maxRTT)
    }

    @Test @OriginalCase("BatchAggregateTests::RTT aggregates exclude failed traces()")
    fun `RTT aggregates exclude failed traces`() {
        holder.setBatchEnabled(true)
        seed(batchResult(listOf(5.0), 100), batchResult(emptyList(), 0, success = false), batchResult(listOf(5.0), 200))
        assertEquals(150L, harness.state.averageRTT)
        assertEquals(100L, harness.state.minRTT)
        assertEquals(200L, harness.state.maxRTT)
    }

    @Test @OriginalCase("BatchAggregateTests::RTT aggregates return nil when no successful traces()")
    fun `RTT aggregates return nil when no successful traces`() {
        holder.setBatchEnabled(true)
        seed(batchResult(emptyList(), 0, success = false))
        assertNull(harness.state.averageRTT)
        assertNull(harness.state.minRTT)
        assertNull(harness.state.maxRTT)
    }

    @Test @OriginalCase("BatchAggregateTests::hop stats compute correctly for intermediate hops()")
    fun `hop stats compute correctly for intermediate hops`() {
        holder.setBatchEnabled(true)
        seed(batchResult(listOf(5.0, 3.0), 100), batchResult(listOf(7.0, 1.0), 100), batchResult(listOf(6.0, 2.0), 100))
        assertEquals(HopStats(6.0, 5.0, 7.0), harness.state.hopStats(1))
        assertEquals(HopStats(2.0, 1.0, 3.0), harness.state.hopStats(2))
    }

    @Test @OriginalCase("BatchAggregateTests::hop stats return nil for start node()")
    fun `hop stats return nil for start node`() {
        holder.setBatchEnabled(true)
        seed(batchResult(listOf(5.0), 100))
        assertNull(harness.state.hopStats(0))
    }

    @Test @OriginalCase("BatchAggregateTests::latestHopSNR returns SNR from most recent successful result()")
    fun `latestHopSNR returns SNR from most recent successful result`() {
        holder.setBatchEnabled(true)
        seed(batchResult(listOf(5.0), 100), batchResult(listOf(7.0), 100))
        assertEquals(7.0, harness.state.latestHopSNR(1))
    }

    @Test @OriginalCase("BatchExecutionTests::runBatchTrace resets batch state before starting()")
    fun `runBatchTrace resets batch state before starting`() {
        holder.setBatchEnabled(true)
        holder.setBatchSize(3)
        seed(batchResult(listOf(5.0), 100), index = 2)
        harness.complete { holder.runBatchTrace() }
        assertTrue(harness.state.completedResults.isEmpty())
        assertEquals(0, harness.state.currentTraceIndex)
    }

    @Test @OriginalCase("BatchExecutionTests::clearBatchState resets all batch properties()")
    fun `clearBatchState resets all batch properties`() {
        holder.setBatchEnabled(true)
        seed(batchResult(listOf(5.0), 100), index = 3)
        holder.clearBatchState()
        assertEquals(0, harness.state.currentTraceIndex)
        assertTrue(harness.state.completedResults.isEmpty())
    }

    @Test @OriginalCase("BatchExecutionTests::cancelBatchTrace clears running state()")
    fun `cancelBatchTrace clears running state`() {
        holder.setBatchEnabled(true)
        holder.editStateForTesting { it.copy(isRunning = true, currentTraceIndex = 2) }
        holder.setPendingTagForTesting(12345u)
        holder.cancelBatchTrace()
        assertFalse(harness.state.isRunning)
        assertEquals(0, harness.state.currentTraceIndex)
    }

    @Test @OriginalCase("BatchCancellationTests::isBatchInProgress returns false after cancel()")
    fun `isBatchInProgress returns false after cancel`() {
        holder.setBatchEnabled(true)
        seed(batchResult(listOf(5.0), 100), index = 2, size = 5)
        assertTrue(harness.state.isBatchInProgress)
        holder.cancelBatchTrace()
        assertFalse(harness.state.isBatchInProgress)
    }

    @Test @OriginalCase("BatchCancellationTests::batch state preserved on cancel for partial results access()")
    fun `batch state preserved on cancel for partial results access`() {
        holder.setBatchEnabled(true)
        seed(batchResult(listOf(5.0), 100), batchResult(listOf(6.0), 110), index = 3, size = 5)
        holder.cancelBatchTrace()
        assertEquals(2, harness.state.completedResults.size)
        assertEquals(0, harness.state.currentTraceIndex)
    }

    @Test
    fun `batch results card model exposes progress aggregates and partial results after cancel`() {
        holder.setBatchEnabled(true)
        seed(batchResult(listOf(5.0), 100), batchResult(listOf(6.0), 200, success = false), index = 3, size = 5)
        assertEquals(BatchStatus(false, 3, 5, 1), harness.state.batchStatus)
        assertEquals(BatchRtt(100, 100, 100), harness.state.batchRtt)
        holder.cancelBatchTrace()
        assertEquals(BatchStatus(false, 0, 5, 1), harness.state.batchStatus)
        assertTrue(harness.state.hasBatchResults)
        assertEquals(BatchRtt(100, 100, 100), harness.state.batchRtt)
    }

    @Test
    fun `batch status reports completion percentage and no aggregates without successes`() {
        holder.setBatchEnabled(true)
        seed(batchResult(listOf(5.0), 0, success = false), batchResult(listOf(5.0), 0, success = false), batchResult(listOf(5.0), 90), size = 3)
        val status = checkNotNull(harness.state.batchStatus)
        assertTrue(status.isComplete)
        assertEquals(33, status.successPercent)
        seed(batchResult(listOf(5.0), 0, success = false), size = 1)
        assertNull(harness.state.batchRtt)
    }

    @Test
    fun `first trace exposes progress before a response and default state has no card`() {
        assertNull(harness.state.batchStatus)
        assertNull(harness.state.batchRtt)
        assertNull(harness.state.displayedResult)
        holder.setBatchEnabled(true)
        seed(index = 1)
        assertEquals(BatchStatus(false, 1, 3, 0), harness.state.batchStatus)
    }

    @Test
    fun `partial batch selects latest successful response even after a failure`() {
        val success = batchResult(listOf(7.0), 100)
        val failure = batchResult(emptyList(), 0, success = false)
        holder.editStateForTesting {
            it.copy(batchEnabled = true, result = failure, completedResults = listOf(success, failure))
        }
        assertEquals(success, harness.state.displayedResult)
        assertEquals(7.0, harness.state.latestHopSNR(1))
        holder.cancelBatchTrace()
        assertEquals(success, harness.state.displayedResult)
        assertEquals(7.0, harness.state.hopStats(1)?.avg)
    }
}
