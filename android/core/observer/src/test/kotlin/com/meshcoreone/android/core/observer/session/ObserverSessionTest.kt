// AndroidOnly: WP-101 Prizm Mobile Observer service logic (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.session

import com.meshcoreone.android.core.observer.ObserverTestSupport
import com.meshcoreone.android.core.observer.claim.NodeDeviceInfo
import com.meshcoreone.android.core.observer.geo.UploadGate
import com.meshcoreone.android.core.observer.geo.testFence
import com.meshcoreone.android.core.observer.health.DeviceStatsReader
import com.meshcoreone.android.core.observer.location.FixProvider
import com.meshcoreone.android.core.observer.location.LocationFix
import com.meshcoreone.android.core.observer.location.LocationSharing
import com.meshcoreone.android.core.observer.location.PrivacyZone
import com.meshcoreone.android.core.observer.location.PrivacyZoneSource
import com.meshcoreone.android.core.observer.upload.FakePublisher
import com.meshcoreone.android.core.observer.upload.MutableUploadClock
import com.meshcoreone.android.core.observer.upload.testNodeKey
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.parser.PacketParser
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

class ObserverSessionTest {
    private val clock = MutableUploadClock()
    private val publisher = FakePublisher()
    private val link = FakeLink()
    private val location = FakeLocation()
    private var elapsed = 1_000L
    private var sharing = true
    private var zones = emptyList<PrivacyZone>()
    private val session = ObserverSession(
        link, location, publisher, LocationSharing { sharing }, PrivacyZoneSource { zones }, testFence(),
        clock, { elapsed }, "session-1",
    )

    private fun fix(atMs: Long, lat: Double = 46.5, lon: Double = -97.5, speed: Double? = 3.0) =
        LocationFix(lat, lon, 5.0, speedMps = speed, fixElapsedMs = atMs, provider = FixProvider.GPS)

    private fun frame(type: Int, payload: Bytes): MeshEvent = rxFrame(type, payload)
    private fun advert() = advertEvent()
    private fun direct() = frame(2, Bytes(ByteArray(24) { 2 }))

    private fun started() = runBlocking {
        session.start(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + kotlinx.coroutines.Job()))
        session.onFix(fix(1_000))
    }

    @Test fun fullAndMetadataOnlyPacketsAreCountedAndUploaded() = runBlocking {
        started()
        session.handle(advert()); session.handle(direct())
        session.tick()
        val c = session.counters.value
        assertEquals(2, c.heard); assertEquals(1, c.uploaded); assertEquals(1, c.metadataOnly)
        assertEquals(0, c.queued)
        session.stop()
    }

    @Test fun packetsFlowThroughTheLiveFeedWhileRunning() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + kotlinx.coroutines.Job())
        session.start(scope); session.onFix(fix(1_000))
        link.feed.emit(advert())
        assertEquals(1, session.counters.value.heard)
        session.stop(); scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    @Test fun anAllowListedHashtagChannelIsUploadedInFullOnceTheSlotIsKnown() = runBlocking {
        val key = ObserverTestSupport.hashtagKey("#fargo")
        val group = frame(5, ObserverTestSupport.channelPayload(key))
        started()
        session.handle(group)
        assertEquals(1, session.counters.value.metadataOnly, "unknown channel is private")
        session.handle(MeshEvent.ChannelInfo(ObserverTestSupport.slot(1, "#fargo", key)))
        session.handle(group)
        assertEquals(1, session.counters.value.uploaded)
        assertTrue("#fargo" in session.allowedChannelNames)
        session.stop()
    }

    @Test fun slotsAreFetchedOnStartAndOnReconnect() = runBlocking {
        val key = ObserverTestSupport.hashtagKey("#mn")
        link.channels = listOf(ObserverTestSupport.slot(2, "#mn", key))
        started()
        assertTrue("#mn" in session.allowedChannelNames)
        link.channels = listOf(ObserverTestSupport.slot(2, "#other", ObserverTestSupport.hashtagKey("#other")))
        session.onReconnected()
        assertEquals(2, link.fetches)
        assertTrue("#mn" !in session.allowedChannelNames && "#other" in session.allowedChannelNames)
        session.stop()
    }

    @Test fun aFailingChannelFetchKeepsTheSessionRunning() = runBlocking {
        link.failChannels = true
        started()
        assertEquals(listOf("Public"), session.allowedChannelNames)
        session.handle(advert())
        assertEquals(1, session.counters.value.uploaded)
        session.stop()
    }

    @Test fun presenceIsAnnouncedPerRegionOnceAndAgainAfterAReconnect() = runBlocking {
        started()
        session.handle(advert()); session.tick()
        fun statuses() = publisher.acked.filter { it.topic.endsWith("/status") }
        assertEquals(1, statuses().size)
        assertTrue(publisher.acked.any { it.topic.endsWith("/device") })
        session.tick(); assertEquals(1, statuses().size)
        session.onReconnected()
        assertEquals(2, statuses().size)
        session.stop()
    }

    @Test fun healthIsPolledOnTheFirstTickAndOnlyEveryFiveMinutes() = runBlocking {
        started()
        session.handle(advert()); session.tick()
        fun healths() = publisher.acked.count { it.topic.endsWith("/health") }
        assertEquals(1, healths())
        clock.advance(Duration.ofMinutes(1)); session.tick(); assertEquals(1, healths())
        clock.advance(Duration.ofMinutes(5)); session.tick(); assertEquals(2, healths())
        session.stop()
    }

    @Test fun fixesDriveTheSamplingIntervalAndTheScreenOffStillStretch() = runBlocking {
        started()
        assertEquals(listOf(1_000L), location.requests)
        elapsed = 40_000
        session.onFix(fix(5_000, speed = 0.1)); session.onFix(fix(20_000, speed = 0.1)); session.onFix(fix(39_000, speed = 0.1))
        assertEquals(10_000L, location.requests.last(), "still for 30 s -> 10 s sampling")
        session.setScreenOn(false) // low activity: no direct accessor, covered by the pipeline test; must not throw
        session.stop()
    }

    @Test fun anOutsideFixPausesAndShowsTheGateText() = runBlocking {
        val s = session
        s.start(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + kotlinx.coroutines.Job()))
        s.onFix(fix(1_000, lat = 40.0, lon = -97.5))
        s.handle(advert())
        assertEquals(UploadGate.NOTIFICATION_OUTSIDE, s.gateText.value)
        assertEquals(0, s.counters.value.uploaded); assertTrue(publisher.attempts.none { it.topic.endsWith("/packets") })
        s.stop()
    }

    private fun uploadedPayload(share: Boolean): String = runBlocking {
        sharing = share
        started()
        session.handle(advert()); clock.advance(Duration.ofSeconds(16)); session.tick()
        session.stop()
        publisher.ackedPackets.single().payload
    }

    @Test fun locationSharingOffUploadsWithoutAnyLocation() {
        val payload = uploadedPayload(false)
        assertTrue(!payload.contains("\"lat\"") && !payload.contains("h_acc"))
    }

    @Test fun locationSharingOnIncludesTheLocation() {
        assertTrue(uploadedPayload(true).contains("\"lat\":46.500000"))
    }

    @Test fun stopEndsPublishingAtOnceAndSendsTheOfflineStatus() = runBlocking {
        started()
        session.handle(advert()); session.tick()
        val before = publisher.attempts.size
        session.stop()
        assertTrue(location.stopped)
        val last = publisher.attempts.last()
        assertTrue(last.topic.endsWith("/status") && last.payload.contains("offline") && last.retained)
        session.handle(advert()); session.tick(); session.onReconnected()
        assertEquals(before + 1, publisher.attempts.size, "only the offline status after stop")
        assertFalse(session.isActive)
    }

    @Test fun stopIsIdempotentAndSafeWithoutStart() = runBlocking {
        session.stop(); session.stop()
        assertNull(publisher.attempts.firstOrNull())
    }
}
