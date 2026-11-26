// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/TestPolling.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/Helpers/TestPolling.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Tests/MeshCoreTests/Helpers/TestPolling.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.testing

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TestPollingTest {
    @Test
    fun initiallyTrueConditionDoesNotAdvanceTime() = runTest {
        var calls = 0
        waitUntil(SchedulerClock(testScheduler)) { calls++; true }
        assertEquals(1, calls)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun conditionCanBecomeTrueOnTheFinalDeadlineCheck() = runTest {
        val checkedAt = mutableListOf<Long>()
        waitUntil(SchedulerClock(testScheduler), timeout = 20.milliseconds) {
            checkedAt.add(testScheduler.currentTime)
            testScheduler.currentTime == 20L
        }
        assertEquals(listOf(0L, 10L, 20L), checkedAt)
    }

    @Test
    fun intervalOvershootStillPerformsThePinnedFinalCheck() = runTest {
        val checkedAt = mutableListOf<Long>()
        val error = assertFailsWith<WaitTimeoutError> {
            waitUntil(SchedulerClock(testScheduler), timeout = 25.milliseconds, message = "did not park") {
                checkedAt.add(testScheduler.currentTime)
                false
            }
        }
        assertEquals(listOf(0L, 10L, 20L, 30L), checkedAt)
        assertEquals("did not park", error.description)
    }

    @Test
    fun zeroAndNegativeTimeoutsCheckOnceWithoutSleeping() = runTest {
        for (timeout in listOf(Duration.ZERO, -1.seconds)) {
            var calls = 0
            assertFailsWith<WaitTimeoutError> {
                waitUntil(SchedulerClock(testScheduler), timeout = timeout) { calls++; false }
            }
            assertEquals(1, calls)
        }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun defaultTimeoutAndIntervalUseOnlyInjectedVirtualTime() = runTest {
        var calls = 0
        assertFailsWith<WaitTimeoutError> {
            waitUntil(SchedulerClock(testScheduler)) { calls++; false }
        }
        assertEquals(1001, calls)
        assertEquals(10000L, testScheduler.currentTime)
    }

    @Test
    fun cancellationPropagatesAndRemovesTheClockSleeper() = runTest {
        val clock = TestClock()
        val task = async { waitUntil(clock) { false } }
        runCurrent()
        assertEquals(1, clock.sleeperCount)
        task.cancel()
        assertFailsWith<CancellationException> { task.await() }
        assertEquals(0, clock.sleeperCount)
    }

    @Test
    fun conditionFailureIsNotConvertedToTimeoutOrSuccess() = runTest {
        val failure = IllegalStateException("fixture failed")
        val caught = assertFailsWith<IllegalStateException> {
            waitUntil(SchedulerClock(testScheduler)) { throw failure }
        }
        assertTrue(caught === failure)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun invalidPollingIntervalsFailRatherThanSpinForever() = runTest {
        for (interval in listOf(Duration.ZERO, -1.milliseconds, Duration.INFINITE)) {
            assertFailsWith<IllegalArgumentException> {
                waitUntil(SchedulerClock(testScheduler), pollingInterval = interval) { false }
            }
        }
        assertEquals(0L, testScheduler.currentTime)
    }
}
