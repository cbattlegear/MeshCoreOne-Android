// PortedFrom: MC1/Views/Tools/TracePath/TracePathViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TracePathRunDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import com.meshcoreone.android.core.protocol.event.TraceInfo
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * Single and batch trace execution for [TracePathStateHolder]. Shares the holder's correlation
 * fields; a batch trace waits on a deferred that the response, the timeout or a cancel completes.
 */
internal class TraceBatchRunner(private val holder: TracePathStateHolder) {
    private var batchCancelled = false
    private var traceContinuation: CompletableDeferred<Unit>? = null
    private var generation = 0L

    private fun isCurrent(operation: Long): Boolean =
        holder.isConnectionCurrent() && generation == operation

    private data class Dispatch(val sender: TraceSender, val radioId: RadioId?, val mode: UByte)

    private fun captureDispatch(): Dispatch? {
        val sender = holder.deps.traceSender() ?: return null
        val device = holder.deps.connectedDevice()
        val mode: UByte = holder.current.traceHashMode ?: device?.pathHashMode ?: 0u
        if (!holder.isConnectionCurrent(device)) return null
        return Dispatch(sender, device?.radioId, mode)
    }

    suspend fun runTrace() {
        val dispatch = captureDispatch() ?: return
        if (holder.current.outboundPath.isEmpty()) return
        cancelBatchTrace()
        val operation = generation
        holder.update { it.copy(resultID = null) }
        holder.clearError()
        holder.update { it.copy(isRunning = true, result = null, batchPresentationActive = false) }
        matchSavedPathIfNeeded(operation)
        if (!isCurrent(operation)) return
        val pathData = holder.current.fullPathData
        holder.pendingPathHash = pathData
        val tag = beginCorrelation(dispatch)
        val sent = try {
            send(dispatch, tag, pathData)
        } catch (error: CancellationException) {
            // The source's send could not be cancelled; a cancelled caller must not leave it running.
            if (isCurrent(operation) && holder.pendingTag == tag) abandonCorrelation()
            throw error
        }
        if (!isCurrent(operation) || holder.pendingTag != tag) return
        if (sent == null) {
            holder.setError(holder.strings.sendFailed)
            holder.pendingPathHash = null
            holder.current.activeSavedPath?.let { holder.appendRunInBackground(it, holder.failedRun()) }
            holder.update { it.copy(isRunning = false) }
            holder.pendingTag = null
            holder.pendingDeviceId = null
            return
        }
        val timeout = FloodTraceTimeout.sanitized(sent.suggestedTimeoutMs)
        holder.traceJob = holder.scope.launch {
            holder.time.sleep(timeout)
            if (!isCurrent(operation) || holder.pendingTag != tag) return@launch
            holder.setError(holder.strings.noResponse)
            holder.pendingPathHash = null
            val savedPath = holder.current.activeSavedPath
            holder.traceJob = null
            holder.update { it.copy(isRunning = false) }
            holder.pendingTag = null
            holder.pendingDeviceId = null
            if (savedPath != null) holder.appendRunInBackground(savedPath, holder.failedRun())
        }
    }

    suspend fun runBatchTrace() {
        if (!holder.isConnectionCurrent()) return
        if (!holder.current.batchEnabled) {
            runTrace()
            return
        }
        cancelBatchTrace()
        val operation = generation
        holder.clearBatchState()
        batchCancelled = false
        holder.update { it.copy(resultID = null) }
        holder.clearError()
        val dispatch = captureDispatch() ?: return
        if (holder.current.outboundPath.isEmpty()) return
        holder.update { it.copy(isRunning = true, result = null, batchPresentationActive = true) }
        matchSavedPathIfNeeded(operation)
        if (!isCurrent(operation)) return
        val batchSize = holder.current.batchSize
        try {
            runTraces(dispatch, batchSize, operation)
        } catch (error: CancellationException) {
            // The source swallowed cancellation of its inter-trace sleep; Kotlin propagates it, so
            // clean up here instead of leaving the batch looking alive.
            if (isCurrent(operation)) {
                holder.traceJob?.cancel()
                holder.traceJob = null
                abandonCorrelation()
            }
            throw error
        }
        if (!isCurrent(operation)) return
        holder.update { it.copy(isRunning = false, currentTraceIndex = 0) }
        val snapshot = holder.current
        if (snapshot.isBatchComplete && snapshot.successCount == 0) holder.setError(holder.strings.allFailed(batchSize))
    }

    private suspend fun runTraces(dispatch: Dispatch, batchSize: Int, operation: Long) {
        for (traceIndex in 1..batchSize) {
            if (!isCurrent(operation) || batchCancelled) break
            holder.update { it.copy(currentTraceIndex = traceIndex) }
            executeSingleTrace(dispatch, operation)
            if (!isCurrent(operation) || batchCancelled) break
            publishLatestSuccess()
            if (traceIndex < batchSize) {
                if (!isCurrent(operation) || batchCancelled) break
                holder.time.sleep(INTER_TRACE_BUFFER)
                if (!isCurrent(operation) || batchCancelled) break
            }
        }
    }

    /** Clears correlation and running state after the caller's coroutine was cancelled. */
    private fun abandonCorrelation() {
        holder.update { it.copy(isRunning = false, currentTraceIndex = 0) }
        holder.pendingTag = null
        holder.pendingDeviceId = null
        holder.pendingPathHash = null
        holder.traceStartTime = null
    }

    fun cancelBatchTrace() {
        generation += 1
        batchCancelled = true
        holder.traceJob?.cancel()
        holder.traceJob = null
        holder.update { it.copy(isRunning = false, currentTraceIndex = 0) }
        holder.pendingTag = null
        holder.pendingDeviceId = null
        holder.pendingPathHash = null
        holder.traceStartTime = null
        resumeContinuation()
    }

    fun handleTraceResponse(traceInfo: TraceInfo, radioId: RadioId?) {
        val device = holder.deps.connectedDevice()
        if (!holder.isConnectionCurrent(device)) return
        if (traceInfo.tag != holder.pendingTag) return
        val pendingDevice = holder.pendingDeviceId
        if (pendingDevice != null && radioId != null && pendingDevice != radioId) return
        holder.traceJob?.cancel()
        holder.traceJob = null
        val durationMs = holder.traceStartTime?.let {
            java.time.Duration.between(it, holder.time.now()).toNanos() / 1_000_000
        } ?: 0L
        val hops = holder.resolver().responseHops(traceInfo, device, holder.strings.myDevice)
        val size = TraceHashModes.hashSize(holder.current.traceHashMode ?: device?.pathHashMode ?: 0u)
        val result = TraceResult(hops, durationMs, true, null, holder.pendingPathHash ?: Bytes.EMPTY, size)
        if (holder.current.batchEnabled) {
            holder.update { it.copy(result = result, completedResults = it.completedResults + result) }
        } else {
            holder.update { it.copy(result = result, resultID = UUID.randomUUID(), isRunning = false) }
        }
        holder.pendingPathHash = null
        holder.pendingTag = null
        holder.pendingDeviceId = null
        holder.traceStartTime = null
        // The source resumed first, but its resumed task ran later on the main actor; clearing
        // first keeps that order even on a dispatcher that resumes inline.
        resumeContinuation()
        holder.current.activeSavedPath?.let { savedPath ->
            val run = TracePathRunDTO(UUID.randomUUID(), holder.time.now(), true, durationMs, holder.intermediateSnr(hops))
            holder.appendRunInBackground(savedPath, run)
        }
    }

    private suspend fun executeSingleTrace(dispatch: Dispatch, operation: Long) {
        val pathData = holder.current.fullPathData
        holder.pendingPathHash = pathData
        val tag = beginCorrelation(dispatch)
        val sent = send(dispatch, tag, pathData)
        if (!isCurrent(operation) || batchCancelled || holder.pendingTag != tag) return
        if (sent == null) {
            val failed = TraceResult.sendFailed(holder.strings.sendFailed, holder.pendingPathHash ?: Bytes.EMPTY, holder.hashSize)
            holder.update { it.copy(completedResults = it.completedResults + failed) }
            recordFailedRun()
            holder.pendingPathHash = null
            holder.pendingTag = null
            return
        }
        // A cancel or a matching response while the send was suspended already settled this trace;
        // waiting now would never resume (the source leaked its continuation here).
        val timeout = FloodTraceTimeout.sanitized(sent.suggestedTimeoutMs)
        val continuation = CompletableDeferred<Unit>()
        traceContinuation = continuation
        holder.traceJob = holder.scope.launch {
            holder.time.sleep(timeout)
            if (!isCurrent(operation) || traceContinuation == null || holder.pendingTag != tag) return@launch
            val timedOut = TraceResult.timeout(holder.pendingPathHash ?: Bytes.EMPTY, holder.hashSize, holder.strings.noResponse)
            holder.update { it.copy(completedResults = it.completedResults + timedOut) }
            recordFailedRun()
            holder.pendingPathHash = null
            holder.pendingTag = null
            resumeContinuation()
        }
        try {
            continuation.await()
        } catch (error: CancellationException) {
            if (traceContinuation === continuation) traceContinuation = null
            if (isCurrent(operation)) holder.traceJob?.cancel()
            throw error
        }
    }

    /** First success presents the result (new resultID); later successes only replace it. */
    private fun publishLatestSuccess() {
        val latest = holder.current.completedResults.lastOrNull()?.takeIf { it.success } ?: return
        if (holder.current.successCount == 1) {
            holder.update { it.copy(result = latest, resultID = UUID.randomUUID()) }
        } else {
            holder.update { it.copy(result = latest) }
        }
    }

    private suspend fun matchSavedPathIfNeeded(operation: Long) {
        if (holder.current.activeSavedPath != null) return
        try {
            holder.findMatchingSavedPath()?.let { matched ->
                if (isCurrent(operation)) holder.update { it.copy(activeSavedPath = matched) }
            }
        } catch (error: CancellationException) {
            if (isCurrent(operation)) abandonCorrelation()
            throw error
        }
    }

    private fun beginCorrelation(dispatch: Dispatch): UInt {
        val tag = holder.tagSource()
        holder.pendingTag = tag
        holder.pendingDeviceId = dispatch.radioId
        holder.traceStartTime = holder.time.now()
        return tag
    }

    /** The sent info, or `null` after a send failure (reported to diagnostics). */
    private suspend fun send(dispatch: Dispatch, tag: UInt, pathData: Bytes): MessageSentInfo? = try {
        dispatch.sender.sendTrace(tag, 0u, dispatch.mode, pathData)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        holder.diagnostics.failure("sendTrace", error)
        null
    }

    private fun recordFailedRun() {
        val savedPath = holder.current.activeSavedPath ?: return
        holder.appendRunInBackground(savedPath, holder.failedRun())
    }

    private fun resumeContinuation() {
        val continuation = traceContinuation ?: return
        traceContinuation = null
        continuation.complete(Unit)
    }

    private companion object {
        /** Gap between consecutive batch traces, to avoid flooding the mesh. */
        val INTER_TRACE_BUFFER = 500.milliseconds
    }
}
