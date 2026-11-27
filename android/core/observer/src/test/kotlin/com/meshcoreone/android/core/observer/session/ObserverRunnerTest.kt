// AndroidOnly: WP-101 Prizm Mobile Observer service logic tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.session

import com.meshcoreone.android.core.observer.geo.testFence
import com.meshcoreone.android.core.observer.location.FixProvider
import com.meshcoreone.android.core.observer.location.LocationFix
import com.meshcoreone.android.core.observer.location.LocationSharing
import com.meshcoreone.android.core.observer.location.PrivacyZoneSource
import com.meshcoreone.android.core.observer.upload.FakePublisher
import com.meshcoreone.android.core.observer.upload.InMemoryUploadQueueRepository
import com.meshcoreone.android.core.observer.upload.MutableUploadClock
import com.meshcoreone.android.core.observer.upload.fullRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.meshcoreone.android.core.observer.upload.rawOf
import kotlinx.coroutines.runBlocking

class ObserverRunnerTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val publisher = FakePublisher()
    private val clock = MutableUploadClock()
    private val created = mutableListOf<FakeLocation>()
    private val runner = ObserverRunner(scope) { link ->
        val location = FakeLocation().also { created += it }
        ObserverSession(link, location, publisher, LocationSharing { true }, PrivacyZoneSource { emptyList() }, testFence(), clock, { 1_000L })
    }

    @Test fun offMeansNoSessionAndNoPublishing() = runBlocking {
        runner.update(false, FakeLink())
        assertEquals(ObserverMode.OFF, runner.status.value.mode)
        assertTrue(created.isEmpty() && publisher.attempts.isEmpty())
    }

    @Test fun onWithoutANodeWaitsAndThenStartsWhenTheNodeAppears() = runBlocking {
        runner.update(true, null)
        assertEquals(ObserverMode.WAITING_FOR_NODE, runner.status.value.mode)
        runner.update(true, FakeLink())
        assertEquals(ObserverMode.UPLOADING, runner.status.value.mode); assertEquals(1, created.size)
        runner.shutdown()
    }

    @Test fun repeatedUpdatesWithTheSameLinkKeepOneSession() = runBlocking {
        val link = FakeLink()
        runner.update(true, link); runner.update(true, link)
        assertEquals(1, created.size)
        runner.shutdown()
    }

    @Test fun aNewLinkReplacesTheSessionAndStopsTheOldOne() = runBlocking {
        runner.update(true, FakeLink())
        runner.update(true, FakeLink())
        assertEquals(2, created.size); assertTrue(created[0].stopped)
        runner.shutdown()
    }

    @Test fun switchingOffStopsAtOnce() = runBlocking {
        val link = FakeLink()
        runner.update(true, link)
        runner.onFix(fix())
        link.feed.emit(advertEvent())
        runner.update(false, link)
        assertTrue(created.single().stopped)
        assertTrue(publisher.attempts.none { it.payload.contains("offline") }, "there is no status topic any more")
        assertEquals(ObserverStatus(), runner.status.value)
    }

    @Test fun countersCarryAcrossAReconnectAndResetWhenSwitchedOff() = runBlocking {
        val first = FakeLink(); val second = FakeLink()
        runner.update(true, first); runner.onFix(fix()); first.feed.emit(advertEvent())
        assertEquals(1, runner.status.value.counters.heard)
        runner.update(true, second); runner.onFix(fix()); second.feed.emit(advertEvent())
        assertEquals(2, runner.status.value.counters.heard)
        runner.update(false, null)
        assertEquals(0, runner.status.value.counters.heard)
    }

    @Test fun backlogFromAnEarlierRunIsResentWhenTheSessionStarts() = runBlocking {
        val queue = InMemoryUploadQueueRepository()
        queue.append(listOf(clock.now()), listOf(com.meshcoreone.android.core.observer.upload.RecordSerializer.toJson(fullRecord(7, clock.now()))!!))
        val session = ObserverSession(
            FakeLink(), FakeLocation(), publisher, LocationSharing { true }, PrivacyZoneSource { emptyList() }, testFence(), clock, { 1_000L }, queue = queue,
        )
        session.start(scope)
        assertEquals(1, publisher.deliveries().size); assertEquals(rawOf(7), publisher.deliveries().single())
        assertEquals(0, queue.count())
        session.stop()
    }

    private fun fix() = LocationFix(46.5, -97.5, 5.0, speedMps = 3.0, fixElapsedMs = 1_000, provider = FixProvider.GPS)
}
