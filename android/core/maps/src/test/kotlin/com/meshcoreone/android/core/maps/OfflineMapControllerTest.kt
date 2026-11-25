// PortedFrom: MC1/Services/OfflineMapService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.maps

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class OfflineMapControllerTest {
    private val attribution = listOf(MapProviderAttribution("approved", "https://example.invalid/legal"))
    private val policies = mapOf(
        OfflineLayer.BASE to OfflineLayerPolicy(OfflineLayer.BASE, 14, attribution),
        OfflineLayer.TOPO to OfflineLayerPolicy(OfflineLayer.TOPO, 17, attribution),
    )
    private val bounds = GeoBounds(GeoPoint(37.0, -123.0), GeoPoint(38.0, -122.0))

    @Test
    fun validatesDiskNetworkLayersAndZoomBeforeBackendCalls() = runBlocking {
        val backend = RecordingBackend()
        suspend fun result(bytes: Long, network: Boolean, layers: Set<OfflineLayer>, minZoom: Int = 10) =
            OfflineMapController(backend, policies, { bytes }, { network })
                .downloadRegion("Region", bounds, layers, minZoom)

        assertTrue(result(1, true, setOf(OfflineLayer.BASE)).exceptionOrNull() is OfflineMapError.InsufficientDiskSpace)
        assertTrue(result(Long.MAX_VALUE, false, setOf(OfflineLayer.BASE)).exceptionOrNull() is OfflineMapError.NetworkUnavailable)
        assertTrue(result(Long.MAX_VALUE, true, emptySet()).exceptionOrNull() is OfflineMapError.EmptyLayerSelection)
        assertTrue(result(Long.MAX_VALUE, true, setOf(OfflineLayer.BASE), 15).exceptionOrNull() is OfflineMapError.InvalidZoomRange)
        assertTrue(backend.requests.isEmpty())
    }

    @Test
    fun createsDeterministicBaseAndTopoRequestsThenDelegatesLifecycle() = runBlocking {
        val backend = RecordingBackend()
        val ids = ArrayDeque(listOf(UUID(0, 1), UUID(0, 2)))
        val instant = Instant.parse("2026-10-08T12:00:00Z")
        val controller = OfflineMapController(
            backend,
            policies,
            { Long.MAX_VALUE },
            { true },
            { instant },
            { ids.removeFirst() },
        )
        val packs = controller.downloadRegion("Region", bounds, setOf(OfflineLayer.TOPO, OfflineLayer.BASE)).getOrThrow()
        assertEquals(listOf(OfflineLayer.BASE, OfflineLayer.TOPO), backend.requests.map { it.layer })
        assertEquals(listOf(14, 17), backend.requests.map { it.maxZoom })
        assertEquals(instant, packs.first().createdAt)

        controller.pause(packs.first())
        controller.resume(packs.first())
        controller.delete(packs.first())
        assertEquals(listOf(packs.first().id), backend.paused)
        assertEquals(listOf(packs.first().id), backend.resumed)
        assertEquals(listOf(packs.first().id), backend.deleted)
    }

    @Test
    fun unavailableNativeRuntimeFailsClosedWithTypedError() = runBlocking {
        val controller = OfflineMapController(
            UnavailableOfflineMapBackend,
            policies,
            { Long.MAX_VALUE },
            { true },
        )

        assertTrue(
            controller.downloadRegion("Region", bounds, setOf(OfflineLayer.BASE))
                .exceptionOrNull() is OfflineMapError.NativeRuntimeUnavailable,
        )
        assertTrue(controller.packs.value.isEmpty())
    }

    @Test
    fun missingPolicyAndCancellationRemainTyped() = runBlocking {
        val missing = OfflineMapController(RecordingBackend(), emptyMap(), { Long.MAX_VALUE }, { true })
        assertTrue(
            missing.downloadRegion("Region", bounds, setOf(OfflineLayer.BASE))
                .exceptionOrNull() is OfflineMapError.MissingLayerPolicy,
        )

        val cancellation = CancellationException("cancel")
        val backend = RecordingBackend(failure = cancellation)
        val controller = OfflineMapController(backend, policies, { Long.MAX_VALUE }, { true })
        val thrown = runCatching {
            controller.downloadRegion("Region", bounds, setOf(OfflineLayer.BASE))
        }.exceptionOrNull()
        assertSame(cancellation, thrown)
    }

    private class RecordingBackend(
        private val failure: Throwable? = null,
    ) : OfflineMapBackend {
        private val mutablePacks = MutableStateFlow<List<OfflinePack>>(emptyList())
        override val packs: StateFlow<List<OfflinePack>> = mutablePacks
        val requests = mutableListOf<OfflinePackRequest>()
        val paused = mutableListOf<UUID>()
        val resumed = mutableListOf<UUID>()
        val deleted = mutableListOf<UUID>()

        override suspend fun createAndStart(request: OfflinePackRequest) {
            failure?.let { throw it }
            requests += request
        }

        override suspend fun delete(packId: UUID) { deleted += packId }
        override suspend fun pause(packId: UUID) { paused += packId }
        override suspend fun resume(packId: UUID) { resumed += packId }
    }
}
