// PortedFrom: MC1Tests/Services/MapSnapshotStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.maps

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSnapshotStoreTest {
    @Test
    fun conflatedRevisionRetainsTheLastBurstChangeAndFailureInvalidationWithoutPerKeyDelivery() = runBlocking {
        val store = MapSnapshotStore(object : MapSnapshotRenderer<String> {
            override suspend fun render(request: MapSnapshotRequest): String? = null
        }, this, { it.length.toLong() })
        val requests = (0 until 70).map { MapSnapshotRequest(1.0, it / 1000.0, false, false) }
        try {
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val values = mutableListOf<Long>()
            val collector = async(start = CoroutineStart.UNDISPATCHED) {
                store.revision.first {
                    values += it
                    if (it == 0L) {
                        held.complete(Unit)
                        release.await()
                    }
                    it == 140L
                }
            }
            held.await()
            for (request in requests) {
                val resolved = async(start = CoroutineStart.UNDISPATCHED) { store.resolutions.first { it == request } }
                store.request(request)
                resolved.await()
            }
            assertTrue(requests.all(store::isResolved))
            assertEquals(70L, store.revision.value)
            store.clearFailures()
            assertTrue(requests.none(store::isResolved))
            assertEquals(140L, store.revision.value)
            release.complete(Unit)
            collector.await()
            assertEquals(listOf(0L, 140L), values)
        } finally { store.close() }
    }

    @Test
    fun requestNormalizesJitterSignedZeroAndOfflineDarkness() {
        val negativeZero = MapSnapshotRequest(-0.000004, 1.23456789, true, false)
        val positiveZero = MapSnapshotRequest(0.0, 1.23456789, true, false)
        assertEquals(positiveZero, negativeZero)
        assertEquals("0.0,1.23457,true,false", negativeZero.cacheKey)
        assertEquals("0.0,1.23457,false,true", MapSnapshotRequest(0.0, 1.23456789, true, true).cacheKey)
    }

    @Test
    fun exactHalfwayCoordinatesRoundAwayFromZeroLikeTheFrozenSwiftRequest() {
        for ((input, expected) in listOf(
            0.000005 to 0.00001, -0.000005 to -0.00001,
            0.000025 to 0.00003, -0.000025 to -0.00003,
            Math.nextDown(0.000005) to 0.0, -Math.nextDown(0.000005) to 0.0,
        )) {
            val request = MapSnapshotRequest(input, input, false, false)
            assertEquals(expected, request.latitude, 0.0)
            assertEquals(expected, request.longitude, 0.0)
            if (expected == 0.0) assertEquals("0.0,0.0,false,false", request.cacheKey)
        }
    }

    @Test
    fun memoryClearCannotLetTheOldRenderRemoveTheReplacementDeduplicationEntry() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        var calls = 0
        val store = MapSnapshotStore(object : MapSnapshotRenderer<String> {
            override suspend fun render(request: MapSnapshotRequest): String {
                when (++calls) {
                    1 -> { firstStarted.complete(Unit); firstGate.await() }
                    2 -> { secondStarted.complete(Unit); secondGate.await() }
                }
                return "render-$calls"
            }
        }, this, { it.length.toLong() })
        val request = MapSnapshotRequest(1.0, 2.0, false, false)
        try {
            store.request(request)
            firstStarted.await()
            val invalidated = async(start = CoroutineStart.UNDISPATCHED) { store.resolutions.first() }
            store.clear()
            assertEquals(request, invalidated.await())
            store.request(request)
            firstGate.complete(Unit)
            secondStarted.await()
            assertFalse(store.isResolved(request))
            store.request(request)
            val resolved = async(start = CoroutineStart.UNDISPATCHED) { store.resolutions.first() }
            secondGate.complete(Unit)
            assertEquals(request, resolved.await())
            yield()
            assertEquals(2, calls)
            assertEquals("render-2", store.value(request))
        } finally { store.close() }
    }

    @Test
    fun closeIsTerminalAndReleasesTheCachedImage() = runBlocking {
        val store = MapSnapshotStore(RecordingRenderer(), this, { it.length.toLong() })
        val request = MapSnapshotRequest(1.0, 2.0, false, false)
        val resolved = async(start = CoroutineStart.UNDISPATCHED) { store.resolutions.first() }
        store.request(request)
        resolved.await()
        store.close()
        store.close()
        store.clear()
        assertNull(store.value(request))
        assertFalse(store.isResolved(request))
        try {
            store.request(request)
            throw AssertionError("A closed process owner must not start another render")
        } catch (expected: IllegalStateException) {
            assertEquals("Map snapshot store is closed", expected.message)
        }
    }

    @Test
    fun cacheMissRenderDeduplicationAndMulticastResolution() = runBlocking {
        val renderer = RecordingRenderer()
        val store = MapSnapshotStore(renderer, this, { it.length.toLong() })
        val request = MapSnapshotRequest(37.0, -122.0, false, false)
        assertNull(store.value(request))
        assertFalse(store.isResolved(request))

        val first = async(start = CoroutineStart.UNDISPATCHED) { store.resolutions.first() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { store.resolutions.first() }
        store.request(request)
        store.request(request)

        assertEquals(request, first.await())
        assertEquals(request, second.await())
        assertEquals(1, renderer.renderCount)
        assertEquals(request.cacheKey, store.value(request))
        assertTrue(store.isResolved(request))
    }

    @Test
    fun failureRetryAndClearInvalidateResolvedState() = runBlocking {
        val renderer = RecordingRenderer(fail = true)
        val store = MapSnapshotStore(renderer, this, { it.length.toLong() })
        val request = MapSnapshotRequest(1.0, 2.0, false, false)
        val failed = async(start = CoroutineStart.UNDISPATCHED) { store.resolutions.first() }
        store.request(request)
        failed.await()
        assertTrue(store.isResolved(request))
        assertNull(store.value(request))

        renderer.fail = false
        store.retry(request)
        val retried = async(start = CoroutineStart.UNDISPATCHED) { store.resolutions.first() }
        store.request(request)
        retried.await()
        assertEquals(2, renderer.renderCount)
        assertTrue(store.value(request) != null)

        store.clear()
        assertFalse(store.isResolved(request))
        assertNull(store.value(request))
    }

    @Test
    fun countAndFailureCapsEvictOldestEntries() = runBlocking {
        val renderer = RecordingRenderer()
        val store = MapSnapshotStore(
            renderer,
            this,
            { it.length.toLong() },
            cacheCountLimit = 2,
            failedSetSizeLimit = 2,
        )
        val requests = (0..2).map { MapSnapshotRequest(it.toDouble(), 0.0, false, false) }
        requests.forEach {
            val resolution = async(start = CoroutineStart.UNDISPATCHED) { store.resolutions.first() }
            store.request(it)
            resolution.await()
        }
        assertFalse(store.isResolved(requests.first()))
        assertTrue(store.isResolved(requests.last()))

        renderer.fail = true
        val failures = (10..12).map { MapSnapshotRequest(it.toDouble(), 0.0, false, false) }
        failures.forEach {
            val resolution = async(start = CoroutineStart.UNDISPATCHED) { store.resolutions.first() }
            store.request(it)
            resolution.await()
        }
        assertFalse(store.isResolved(failures.first()))
        assertTrue(store.isResolved(failures.last()))
    }

    @Test
    fun closeCancelsInFlightRenderWithoutCaching() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val renderer = RecordingRenderer(gate = gate)
        val store = MapSnapshotStore(renderer, this, { it.length.toLong() })
        val request = MapSnapshotRequest(1.0, 2.0, false, false)
        store.request(request)
        yield()
        store.close()
        gate.complete(Unit)
        yield()
        assertFalse(store.isResolved(request))
    }

    @Test
    fun cachedRequestIsNotRenderedAgainAndKeepsTheSameValue() = runBlocking {
        val renderer = RecordingRenderer()
        val store = MapSnapshotStore(renderer, this, { it.length.toLong() })
        try {
            val request = MapSnapshotRequest(37.7749, -122.4194, false, false)
            val resolved = async(start = CoroutineStart.UNDISPATCHED) { store.resolutions.first() }
            store.request(request)
            assertEquals(request, resolved.await())
            val firstValue = store.value(request)
            assertEquals(request.cacheKey, firstValue)
            repeat(10) { store.request(request) }
            yield()
            assertEquals(1, renderer.renderCount)
            assertEquals(firstValue, store.value(request))
            assertTrue(store.isResolved(request))
        } finally { store.close() }
    }

    @Test
    fun concurrentRequestsForOneKeyEnqueueExactlyOneRenderAndResolveBothSubscribers() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val store = MapSnapshotStore(object : MapSnapshotRenderer<String> {
            override suspend fun render(request: MapSnapshotRequest): String {
                calls++
                started.complete(Unit)
                gate.await()
                return "independent-image"
            }
        }, this, { it.length.toLong() })
        try {
            val request = MapSnapshotRequest(37.7749, -122.4194, false, false)
            val first = async(start = CoroutineStart.UNDISPATCHED) { store.resolutions.first() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { store.resolutions.first() }
            val requesters = List(20) { async { store.request(request) } }
            requesters.forEach { it.await() }
            started.await()
            assertEquals(1, calls)
            assertFalse(store.isResolved(request))
            assertNull(store.value(request))
            gate.complete(Unit)
            assertEquals(request, first.await())
            assertEquals(request, second.await())
            assertEquals("independent-image", store.value(request))
            assertTrue(store.isResolved(request))
            assertEquals(1, calls)
        } finally { store.close() }
    }

    @Test
    fun onlineAndOfflineCoordinatesResolveDistinctKeysAndValues() = runBlocking {
        val renderer = RecordingRenderer()
        val store = MapSnapshotStore(renderer, this, { it.length.toLong() })
        try {
            val online = MapSnapshotRequest(51.5074, -0.1278, true, false)
            val offline = MapSnapshotRequest(51.5074, -0.1278, true, true)
            assertFalse(online == offline)
            assertFalse(online.cacheKey == offline.cacheKey)
            for (request in listOf(online, offline)) {
                val resolved = async(start = CoroutineStart.UNDISPATCHED) { store.resolutions.first() }
                store.request(request)
                assertEquals(request, resolved.await())
            }
            assertEquals(2, renderer.renderCount)
            assertEquals(online.cacheKey, store.value(online))
            assertEquals(offline.cacheKey, store.value(offline))
            assertTrue(store.isResolved(online))
            assertTrue(store.isResolved(offline))
        } finally { store.close() }
    }

    @Test
    fun actualFailureCapRetainsExactlyTheNewestTwoHundredKeysAndEvictedKeysCanRetry() = runBlocking {
        val renderer = RecordingRenderer(fail = true)
        val store = MapSnapshotStore(renderer, this, { it.length.toLong() })
        try {
            val requests = (0..200).map { MapSnapshotRequest(1.0, it / 1000.0, false, false) }
            for (request in requests) {
                val resolved = async(start = CoroutineStart.UNDISPATCHED) {
                    store.resolutions.first { it == request }
                }
                store.request(request)
                assertEquals(request, resolved.await())
            }
            assertEquals(201, renderer.renderCount)
            assertFalse(store.isResolved(requests.first()))
            assertEquals(200, requests.count(store::isResolved))
            assertTrue(requests.drop(1).all(store::isResolved))
            assertTrue(requests.all { store.value(it) == null })
            val resolved = async(start = CoroutineStart.UNDISPATCHED) {
                store.resolutions.first { it == requests.first() }
            }
            store.request(requests.first())
            assertEquals(requests.first(), resolved.await())
            assertEquals(202, renderer.renderCount)
            assertTrue(store.isResolved(requests.first()))
            assertFalse(store.isResolved(requests[1]))
            assertEquals(200, requests.count(store::isResolved))
        } finally { store.close() }
    }

    @Test
    fun clearNotifiesBothSubscribersForEveryPreviouslyResolvedImageAndFailure() = runBlocking {
        val renderer = RecordingRenderer()
        val store = MapSnapshotStore(renderer, this, { it.length.toLong() })
        try {
            val image = MapSnapshotRequest(1.0, 2.0, false, false)
            val failure = MapSnapshotRequest(3.0, 4.0, false, false)
            for (request in listOf(image, failure)) {
                renderer.fail = request == failure
                val resolved = async(start = CoroutineStart.UNDISPATCHED) { store.resolutions.first() }
                store.request(request)
                resolved.await()
            }
            assertNotNull(store.value(image))
            assertNull(store.value(failure))
            assertTrue(store.isResolved(image) && store.isResolved(failure))
            suspend fun invalidations(): List<MapSnapshotRequest> {
                val events = mutableListOf<MapSnapshotRequest>()
                store.resolutions.first { events += it; events.size == 2 }
                return events
            }
            val first = async(start = CoroutineStart.UNDISPATCHED) { invalidations() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { invalidations() }
            store.clear()
            assertEquals(listOf(image, failure), first.await())
            assertEquals(listOf(image, failure), second.await())
            assertFalse(store.isResolved(image) || store.isResolved(failure))
            assertNull(store.value(image))
            assertNull(store.value(failure))
            assertEquals(2, renderer.renderCount)
        } finally { store.close() }
    }

    @Test
    fun slowDisplaySubscriberKeepsNewestSixtyFourNotificationsButStoreResolvesEveryKey() = runBlocking {
        val releaseSubscriber = CompletableDeferred<Unit>()
        val subscriberHeld = CompletableDeferred<Unit>()
        val allRendered = CompletableDeferred<Unit>()
        var renders = 0
        val store = MapSnapshotStore(object : MapSnapshotRenderer<String> {
            override suspend fun render(request: MapSnapshotRequest): String? {
                if (++renders == 70) allRendered.complete(Unit)
                return null
            }
        }, this, { it.length.toLong() })
        val requests = (0 until 70).map { MapSnapshotRequest(1.0, it / 1000.0, false, false) }
        try {
            val collected = async(start = CoroutineStart.UNDISPATCHED) {
                val events = mutableListOf<MapSnapshotRequest>()
                store.resolutions.first {
                    events += it
                    if (events.size == 1) {
                        subscriberHeld.complete(Unit)
                        releaseSubscriber.await()
                    }
                    events.size == 65
                }
                events
            }
            store.request(requests.first())
            subscriberHeld.await()
            requests.drop(1).forEach(store::request)
            allRendered.await()
            yield()
            assertEquals(70, renders)
            assertTrue(requests.all(store::isResolved))
            assertTrue(requests.all { store.value(it) == null })
            releaseSubscriber.complete(Unit)
            assertEquals(listOf(requests.first()) + requests.takeLast(64), collected.await())
        } finally { store.close() }
    }

    private class RecordingRenderer(
        var fail: Boolean = false,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : MapSnapshotRenderer<String> {
        var renderCount = 0
        override suspend fun render(request: MapSnapshotRequest): String? {
            renderCount += 1
            gate?.await()
            return if (fail) null else request.cacheKey
        }
    }
}
