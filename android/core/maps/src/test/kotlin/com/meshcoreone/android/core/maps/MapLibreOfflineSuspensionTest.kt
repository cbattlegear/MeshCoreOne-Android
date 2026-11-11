// AndroidOnly: WP-312 Actual backend create/resume/restoration policy with a deterministic native-region bridge.
package com.meshcoreone.android.core.maps

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class MapLibreOfflineSuspensionTest {
    private val request = OfflinePackRequest(
        UUID(0, 1), "Saved region", OfflineLayer.BASE,
        GeoBounds(GeoPoint(37.0, -123.0), GeoPoint(38.0, -122.0)), 10, 14, Instant.EPOCH,
    )
    private val suspended = OfflineMapError.OpenFreeMapDownloadsSuspended

    @Test fun rejectsNewAndResumedProviderPacksAtControllerAndBackendBeforeNativeWork() = runBlocking<Unit> {
        val region = Region(request.id)
        val manager = Regions(listOf(region))
        val backend = MapLibreOfflineBackend(MapLibreOpenFreeMap.STYLE_URI, this, manager)
        assertSame(suspended, runCatching { backend.resume(request.id) }.exceptionOrNull())
        yield()
        val controller = OfflineMapController(backend, emptyMap(), { error("No disk probe") }, { error("No network probe") })
        assertSame(suspended, controller.downloadError)
        assertSame(suspended, controller.downloadRegion("New", request.bounds, setOf(OfflineLayer.BASE)).exceptionOrNull())
        assertSame(suspended, runCatching { backend.createAndStart(request) }.exceptionOrNull())
        val pack = backend.packs.value.single()
        assertSame(suspended, runCatching { controller.resume(pack) }.exceptionOrNull())
        assertSame(suspended, runCatching { backend.resume(pack.id) }.exceptionOrNull())
        assertEquals(0, manager.creations)
        assertEquals(listOf(false), region.activations)
        assertEquals(OfflinePackState.PAUSED, backend.packs.value.single().state)
    }

    @Test fun restoresAndPausesActiveAndUnreadableProviderRegionsWithoutDeletingCompletedData() = runBlocking<Unit> {
        val partial = Region(UUID(0, 1))
        val complete = Region(UUID(0, 2), progress = OfflineRegionProgress(true, false, 1f))
        val legacy = Region(UUID(0, 3), metadata = byteArrayOf(0))
        val other = Region(UUID(0, 4), styleUri = "https://example.invalid/style")
        val manager = Regions(listOf(partial, complete, legacy, other))
        val backend = MapLibreOfflineBackend(MapLibreOpenFreeMap.STYLE_URI, this, manager)
        yield()
        listOf(partial, complete, legacy).forEach {
            assertEquals(listOf(false), it.activations)
            assertFalse(it.deleted)
        }
        assertTrue(other.activations.isEmpty())
        assertEquals(3, backend.packs.value.size)
        assertEquals(OfflinePackState.COMPLETE, backend.packs.value.single { it.id == complete.id }.state)
        assertEquals(1f, backend.packs.value.single { it.id == complete.id }.completedFraction)
        assertEquals(OfflinePackState.DOWNLOADING, backend.packs.value.single { it.id == other.id }.state)
        assertNull(backend.resumeError(other.id))
        backend.resume(other.id)
        assertEquals(listOf(true), other.activations)

        partial.report(OfflineRegionProgress(false, true, 0.6f))
        assertEquals(listOf(false, false), partial.activations)
        assertEquals(OfflinePackState.PAUSED, backend.packs.value.single { it.id == partial.id }.state)
        complete.fail()
        assertEquals(OfflinePackState.COMPLETE, backend.packs.value.single { it.id == complete.id }.state)
        backend.delete(partial.id)
        assertTrue(partial.deleted)
        assertFalse(complete.deleted)
        assertEquals(listOf(complete.id, other.id), backend.packs.value.map { it.id })
    }

    @Test fun cancellationOfRestorationDoesNotEnableAnyProviderRegion() = runBlocking<Unit> {
        val listed = CompletableDeferred<List<MapLibreOfflineRegion>>()
        val owner = launch {
            MapLibreOfflineBackend(MapLibreOpenFreeMap.STYLE_URI, this, object : MapLibreOfflineRegions {
                override suspend fun list() = listed.await()
                override suspend fun create(request: OfflinePackRequest, styleUri: String, metadata: ByteArray) =
                    error("Suspended creation")
            })
        }
        yield()
        owner.cancelAndJoin()
        val region = Region(request.id)
        listed.complete(listOf(region))
        yield()
        assertTrue(region.activations.isEmpty())
        assertFalse(region.deleted)
    }

    @Test fun initiatedDeletionFinishesBookkeepingAfterThePanelCallerIsCancelled() = runBlocking<Unit> {
        val region = Region(request.id)
        val completion = CompletableDeferred<Unit>()
        region.deletion = completion
        val backend = MapLibreOfflineBackend(MapLibreOpenFreeMap.STYLE_URI, this, Regions(listOf(region)))
        yield()
        val deletion = launch { backend.delete(request.id) }
        yield()
        assertTrue(region.deletionStarted)
        deletion.cancel()
        yield()
        assertFalse(deletion.isCompleted)
        assertEquals(request.id, backend.packs.value.single().id)
        completion.complete(Unit)
        deletion.join()
        assertTrue(region.deleted)
        assertTrue(backend.packs.value.isEmpty())
    }

    @Test fun failedNativeDeletionRetainsTheSavedPackAndSurfacesFailure() = runBlocking<Unit> {
        val region = Region(request.id)
        val failure = IllegalStateException("Native deletion failed")
        region.deletion = CompletableDeferred<Unit>().also { it.completeExceptionally(failure) }
        val backend = MapLibreOfflineBackend(MapLibreOpenFreeMap.STYLE_URI, this, Regions(listOf(region)))
        yield()
        val reported = runCatching { backend.delete(request.id) }.exceptionOrNull()
        assertEquals(failure.javaClass, reported?.javaClass)
        assertEquals(failure.message, reported?.message)
        assertFalse(region.deleted)
        assertEquals(request.id, backend.packs.value.single().id)
        region.deletion = null
        backend.delete(request.id)
        assertTrue(region.deleted)
        assertTrue(backend.packs.value.isEmpty())
    }

    @Test fun repeatedConfirmationWaitsThenRechecksWithoutAnotherNativeDelete() = runBlocking<Unit> {
        val region = Region(request.id)
        val completion = CompletableDeferred<Unit>()
        region.deletion = completion
        val backend = MapLibreOfflineBackend(MapLibreOpenFreeMap.STYLE_URI, this, Regions(listOf(region)))
        yield()
        val first = launch { backend.delete(request.id) }
        yield()
        val second = launch { backend.delete(request.id) }
        yield()
        try {
            assertEquals(1, region.deletionCalls)
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
        } finally {
            completion.complete(Unit)
        }
        first.join()
        second.join()
        assertEquals(1, region.deletionCalls)
        assertTrue(region.deleted)
        assertTrue(backend.packs.value.isEmpty())
    }

    @Test fun anotherProviderRetainsCreatePauseResumeDeleteAndCancellation() = runBlocking<Unit> {
        val manager = Regions(emptyList())
        val backend = MapLibreOfflineBackend("https://example.invalid/style", this, manager)
        yield()
        assertNull(backend.downloadError)
        backend.createAndStart(request)
        val region = requireNotNull(manager.created)
        backend.pause(request.id)
        backend.resume(request.id)
        assertEquals(listOf(true, false, true), region.activations)
        backend.delete(request.id)
        assertTrue(region.deleted)
        assertTrue(backend.packs.value.isEmpty())
        val cancelled = CancellationException("native cancellation")
        manager.failure = cancelled
        assertSame(cancelled, runCatching { backend.createAndStart(request) }.exceptionOrNull())
    }

    private inner class Regions(private val saved: List<MapLibreOfflineRegion>) : MapLibreOfflineRegions {
        var creations = 0
        var created: Region? = null
        var failure: Exception? = null
        override suspend fun list() = saved
        override suspend fun create(request: OfflinePackRequest, styleUri: String, metadata: ByteArray): MapLibreOfflineRegion {
            failure?.let { throw it }
            creations++
            return Region(request.id, styleUri, metadata).also { created = it }
        }
    }

    private class Region(
        val id: UUID,
        override val styleUri: String = MapLibreOpenFreeMap.STYLE_URI,
        override val metadata: ByteArray = regionMetadata(id),
        private val progress: OfflineRegionProgress = OfflineRegionProgress(false, true, 0.5f),
    ) : MapLibreOfflineRegion {
        val activations = mutableListOf<Boolean>()
        var deleted = false
        var deletionStarted = false
        var deletionCalls = 0
        var deletion: CompletableDeferred<Unit>? = null
        private var progressListener: (OfflineRegionProgress) -> Unit = {}
        private var failureListener: () -> Unit = {}
        override fun setActive(active: Boolean) { activations += active }
        override suspend fun status() = progress
        override suspend fun delete() {
            deletionCalls++
            deletionStarted = true
            deletion?.await()
            deleted = true
        }
        override fun observe(onProgress: (OfflineRegionProgress) -> Unit, onFailure: () -> Unit) {
            progressListener = onProgress
            failureListener = onFailure
        }
        fun report(progress: OfflineRegionProgress) = progressListener(progress)
        fun fail() = failureListener()
    }

    companion object {
        private fun regionMetadata(id: UUID): ByteArray = JSONObject()
            .put("schema", 1).put("id", id.toString()).put("name", "Saved region")
            .put("layer", "BASE").put("north", 38.0).put("east", -122.0)
            .put("south", 37.0).put("west", -123.0)
            .put("minZoom", 10).put("maxZoom", 14).put("createdAt", 0).toString().toByteArray()
    }
}
