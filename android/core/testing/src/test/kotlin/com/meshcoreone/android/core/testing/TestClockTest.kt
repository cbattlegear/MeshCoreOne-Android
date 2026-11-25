// PortedFrom: MeshCore/Tests/MeshCoreTests/Helpers/TestClockTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/Helpers/TestClockTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.testing

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TestClockTest {
    @Test
    fun alreadyCancelledSleepThrowsAndDoesNotPark() = runTest {
        val clock = TestClock()
        var asserted = false
        val task = launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineContext.job.cancel()
            assertFailsWith<CancellationException> { clock.sleepFor(2.seconds) }
            asserted = true
        }
        task.join()
        assertTrue(asserted)
        assertEquals(0, clock.sleeperCount)
    }

    @Test
    fun cancellingAfterParkThrowsAndClearsSleeperCount() = runTest {
        val clock = TestClock()
        val task = async { clock.sleepFor(2.seconds) }
        runCurrent()
        assertEquals(1, clock.sleeperCount)
        task.cancel()
        assertFailsWith<CancellationException> { task.await() }
        assertEquals(0, clock.sleeperCount)
    }

    @Test
    fun advancingWakesAnUncancelledSleeper() = runTest {
        val clock = TestClock()
        val task = async { clock.sleepFor(2.seconds) }
        runCurrent()
        assertEquals(1, clock.sleeperCount)
        clock.advanceBy(2.seconds)
        task.await()
        assertEquals(0, clock.sleeperCount)
        assertEquals(2.seconds, clock.now)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun pastAndCurrentDeadlinesReturnWithoutParking() = runTest {
        val clock = TestClock()
        clock.advanceBy(3.seconds)
        clock.sleepUntil(1.seconds)
        clock.sleepUntil(3.seconds)
        assertEquals(0, clock.sleeperCount)
        assertEquals(Duration.ZERO, clock.minimumResolution)
    }

    @Test
    fun onlyDueSleepersWakeAndCancelledSleeperDoesNotAffectAnother() = runTest {
        val clock = TestClock()
        val first = async { clock.sleepUntil(1.seconds) }
        val second = async { clock.sleepUntil(3.seconds) }
        val cancelled = async { clock.sleepUntil(2.seconds) }
        runCurrent()
        assertEquals(3, clock.sleeperCount)
        cancelled.cancelAndJoin()
        clock.advanceBy(1.seconds)
        first.await()
        assertFalse(second.isCompleted)
        assertEquals(1, clock.sleeperCount)
        clock.advanceBy(2.seconds)
        second.await()
        assertEquals(0, clock.sleeperCount)
    }

    @Test
    fun wakingConsumerCanRegisterAnotherSleep() = runTest {
        val clock = TestClock()
        val task = async {
            clock.sleepFor(1.seconds)
            clock.sleepFor(1.seconds)
        }
        runCurrent()
        clock.advanceBy(1.seconds)
        runCurrent()
        assertEquals(1, clock.sleeperCount)
        clock.advanceBy(1.seconds)
        task.await()
        assertEquals(0, clock.sleeperCount)
    }

    @Test
    fun negativeAndZeroAdvancesKeepPinnedSourceBehavior() = runTest {
        val clock = TestClock()
        val task = async { clock.sleepUntil(1.seconds) }
        runCurrent()
        clock.advanceBy(-1.seconds)
        clock.advanceBy()
        assertEquals(-1.seconds, clock.now)
        assertEquals(1, clock.sleeperCount)
        clock.advanceBy(2.seconds)
        task.await()
        assertEquals(0, clock.sleeperCount)
    }

    @Test
    fun cancellationRacingAdvanceNeverDoubleResumesOrLeaks() = runTest {
        repeat(50) {
            val clock = TestClock()
            val task = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                clock.sleepFor(1.seconds)
            }
            assertEquals(1, clock.sleeperCount)
            coroutineScope {
                launch(Dispatchers.Default) { clock.advanceBy(1.seconds) }
                launch(Dispatchers.Default) { task.cancel() }
            }
            task.join()
            assertEquals(0, clock.sleeperCount)
        }
    }

    @Test
    fun infiniteTimeIsAnExplicitInvalidInput() = runTest {
        val clock = TestClock()
        assertFailsWith<IllegalArgumentException> { clock.advanceBy(Duration.INFINITE) }
        assertFailsWith<IllegalArgumentException> { clock.sleepUntil(Duration.INFINITE) }
        assertEquals(Duration.ZERO, clock.now)
        assertEquals(0, clock.sleeperCount)
    }
}
