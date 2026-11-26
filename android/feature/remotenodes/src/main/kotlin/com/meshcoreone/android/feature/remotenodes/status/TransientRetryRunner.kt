// PortedFrom: MC1/Views/RemoteNodes/NodeStatusViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.status

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.feature.remotenodes.cli.RemoteOperationTimeouts
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesClock
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeFaultClassifier
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Swift `RemoteNodeError.timeout` raised by the status screens themselves: the shared retry budget ran
 * out, or a direct binary telemetry request hit its session timeout. Service-raised timeouts are
 * recognised through [RemoteNodeFaultClassifier.isTimeout] instead.
 */
class RemoteRequestTimeoutException(operationName: String) : Exception("$operationName request timed out")

/**
 * The status screens' transient-retry machinery (Swift `NodeStatusViewModel.performWithTransientRetries`
 * and `runRetryingSectionRequest`). One shared [requestTimeout] budget on the monotonic clock covers
 * every attempt; only "remote node has not answered yet" replies are retried, after 500 ms, 1 s and 2 s
 * (each capped by the remaining budget).
 */
class TransientRetryRunner(
    private val clock: RemoteNodesClock,
    private val faults: RemoteNodeFaultClassifier,
) {
    /** Swift `isTransientError`: only the firmware's "no response yet" session error is transient. */
    fun isTransientError(error: Throwable): Boolean = faults.isRemoteNoResponseYet(error)

    /** Whether [error] ends a section request with the timeout text. */
    fun isTimeout(error: Throwable): Boolean = error is RemoteRequestTimeoutException || faults.isTimeout(error)

    /**
     * Runs [operation] with the remaining budget as its timeout, retrying transient failures until the
     * delays or the budget run out. An exhausted budget throws [RemoteRequestTimeoutException].
     */
    suspend fun <T> performWithTransientRetries(operationName: String, operation: suspend (Duration) -> T): T {
        val deadline = clock.elapsed + requestTimeout
        val delays = transientRetryDelays.iterator()
        while (true) {
            val timeout = remainingBudget(deadline) ?: throw RemoteRequestTimeoutException(operationName)
            try {
                return operation(timeout)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!isTransientError(error) || !delays.hasNext()) throw error
                waitForRetry(operationName, delays.next(), deadline)
            }
        }
    }

    /**
     * Runs a section request owning that section's loading and error flags. Timeouts set
     * [timeoutMessage], other failures carry the error itself; cancellation only clears loading and
     * then propagates.
     */
    suspend fun <T> runRetryingSectionRequest(
        operationName: String,
        setLoading: (Boolean) -> Unit,
        setError: (RemoteNodesText?) -> Unit,
        timeoutMessage: RemoteNodesText = REQUEST_TIMED_OUT,
        operation: suspend (Duration) -> T,
        onSuccess: suspend (T) -> Unit,
    ) {
        setLoading(true)
        setError(null)
        try {
            val response = performWithTransientRetries(operationName, operation)
            onSuccess(response)
            setLoading(false)
        } catch (error: CancellationException) {
            setLoading(false)
            throw error
        } catch (error: Exception) {
            setError(if (isTimeout(error)) timeoutMessage else RemoteNodesText.Failure(error))
            setLoading(false)
        }
    }

    private fun remainingBudget(deadline: Duration): Duration? {
        val remaining = deadline - clock.elapsed
        return if (remaining > Duration.ZERO) remaining else null
    }

    private suspend fun waitForRetry(operationName: String, delay: Duration, deadline: Duration) {
        val remaining = remainingBudget(deadline) ?: throw RemoteRequestTimeoutException(operationName)
        clock.sleep(minOf(delay, remaining))
    }

    companion object {
        /** Swift `requestTimeout` (`RemoteOperationTimeoutPolicy.binaryMaximum`). */
        val requestTimeout: Duration = RemoteOperationTimeouts.binaryMaximum

        /** Swift `transientRetryDelays`. */
        val transientRetryDelays: List<Duration> = listOf(500.milliseconds, 1.seconds, 2.seconds)

        /** Default section timeout text (`L10n...Status.requestTimedOut`). */
        val REQUEST_TIMED_OUT: RemoteNodesText =
            RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesStatusRequestTimedOut)
    }
}
