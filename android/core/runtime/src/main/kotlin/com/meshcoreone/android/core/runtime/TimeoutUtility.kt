// PortedFrom: MC1Services/Sources/MC1Services/Utilities/TimeoutUtility.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.errors.RuntimeTimeoutFault
import com.meshcoreone.android.core.contracts.domain.errors.SourceServiceFaultCarrier
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

class TimeoutError(val operationName: String, val timeout: Duration) :
    Exception("Operation '$operationName' timed out after $timeout"), SourceServiceFaultCarrier {
    override val sourceServiceFault: RuntimeTimeoutFault
        get() = RuntimeTimeoutFault(operationName, timeout)
}

suspend fun <T> withRuntimeTimeout(
    timeout: Duration,
    operationName: String,
    clock: DeadlineClock,
    operation: suspend CoroutineScope.() -> T,
): T = raceAgainstDeadline(timeout, clock, { TimeoutError(operationName, timeout) }, operation)

suspend fun <T> withCooperativeTimeout(
    timeout: Duration,
    clock: DeadlineClock,
    operation: suspend CoroutineScope.() -> T,
): T = raceAgainstDeadline(timeout, clock, { CancellationException("Cooperative deadline exceeded") }, operation)

private suspend fun <T> raceAgainstDeadline(
    timeout: Duration,
    clock: DeadlineClock,
    error: () -> Exception,
    operation: suspend CoroutineScope.() -> T,
): T {
    require(timeout.isFinite() && !timeout.isNegative()) { "Deadline must be finite and non-negative" }
    val outcome: Result<T> = supervisorScope {
        val work = async {
            try { Result.success(operation()) }
            catch (failure: Exception) { Result.failure(failure) }
        }
        val deadline = async { clock.sleep(timeout) }
        try {
            select {
                work.onAwait { it }
                deadline.onAwait { Result.failure(error()) }
            }
        } finally {
            // Like Swift's task group, returning awaits even a cancellation-ignoring loser.
            withContext(NonCancellable) {
                work.cancelAndJoin()
                deadline.cancelAndJoin()
            }
        }
    }
    return outcome.getOrThrow()
}
