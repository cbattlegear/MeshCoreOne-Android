// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/TestPolling.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/Helpers/TestPolling.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Tests/MeshCoreTests/Helpers/TestPolling.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.testing

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.TestCoroutineScheduler

class WaitTimeoutError(val description: String) : Exception(description)

@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerClock(private val scheduler: TestCoroutineScheduler) : SuspendingClock {
    override val now: Duration
        get() = scheduler.currentTime.milliseconds

    override suspend fun sleepUntil(deadline: Duration) {
        require(deadline.isFinite()) { "A scheduler-clock deadline must be finite" }
        currentCoroutineContext().ensureActive()
        delay(deadline - now)
    }
}

suspend fun waitUntil(
    clock: SuspendingClock,
    timeout: Duration = 10.seconds,
    pollingInterval: Duration = 10.milliseconds,
    message: String = "waitUntil timed out",
    condition: suspend () -> Boolean,
) {
    require(timeout.isFinite()) { "A polling timeout must be finite" }
    require(pollingInterval.isFinite() && pollingInterval > Duration.ZERO) {
        "A polling interval must be positive and finite"
    }
    val deadline = clock.now + timeout
    require(deadline.isFinite()) { "Polling deadline overflow" }
    while (clock.now < deadline) {
        currentCoroutineContext().ensureActive()
        if (condition()) return
        clock.sleepFor(pollingInterval)
    }
    currentCoroutineContext().ensureActive()
    if (condition()) return
    throw WaitTimeoutError(message)
}
