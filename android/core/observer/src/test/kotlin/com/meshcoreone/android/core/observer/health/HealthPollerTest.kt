// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.health

import com.meshcoreone.android.core.observer.upload.MutableUploadClock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class HealthPollerTest {
    private val clock = MutableUploadClock()
    private val poller = HealthPoller(clock)
    private var polls = 0
    private fun tick(): Boolean = runBlocking { poller.runIfDue { polls++ } }

    @Test fun theFirstPollIsDueImmediately() {
        assertTrue(poller.isDue()); assertEquals(clock.now(), poller.nextDueAt())
        assertTrue(tick()); assertEquals(1, polls)
    }

    @Test fun thenItWaitsFiveMinutes() {
        tick()
        clock.advance(Duration.ofMinutes(4).plusSeconds(59)); assertFalse(tick()); assertEquals(1, polls)
        clock.advance(Duration.ofSeconds(1)); assertTrue(tick()); assertEquals(2, polls)
    }

    @Test fun nextDueAtIsLastPollPlusInterval() {
        tick()
        assertEquals(clock.now().plus(Duration.ofMinutes(5)), poller.nextDueAt())
    }

    @Test fun aLongGapPollsOnceNotOncePerMissedSlot() {
        tick(); clock.advance(Duration.ofHours(1))
        assertTrue(tick()); assertFalse(tick()); assertEquals(2, polls)
        assertEquals(clock.now().plus(Duration.ofMinutes(5)), poller.nextDueAt())
    }

    @Test fun aFailingPollStillWaitsTheFullIntervalAndThrowsNothing() {
        val failing = runBlocking { poller.runIfDue { error("ble gone") } }
        assertFalse(failing)
        clock.advance(Duration.ofMinutes(1)); assertFalse(tick())
        clock.advance(Duration.ofMinutes(4)); assertTrue(tick())
    }

    @Test fun theIntervalIsConfigurable() {
        val fast = HealthPoller(clock, Duration.ofSeconds(30))
        runBlocking { fast.runIfDue { } }
        clock.advance(Duration.ofSeconds(30)); assertTrue(fast.isDue())
    }

    @Test fun resetMakesTheNextPollDueAgain() {
        tick(); poller.reset(); assertTrue(poller.isDue())
    }
}
