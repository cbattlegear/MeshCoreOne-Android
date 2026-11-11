// PortedFrom: MC1/Services/OfflineMapService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.maps

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.util.UUID

sealed class OfflineMapError(message: String) : Exception(message) {
    data object OpenFreeMapDownloadsSuspended : OfflineMapError("OpenFreeMap offline downloads are temporarily unavailable")
    data object InsufficientDiskSpace : OfflineMapError("At least 100 MB of free storage is required")
    data object EmptyLayerSelection : OfflineMapError("At least one offline layer is required")
    data class MissingLayerPolicy(val layer: OfflineLayer) : OfflineMapError("No approved policy exists for layer: $layer")
    data object NetworkUnavailable : OfflineMapError("A network connection is required to download a region")
    data object InvalidZoomRange : OfflineMapError("The requested zoom range is invalid")
    data object NativeRuntimeUnavailable : OfflineMapError("Maps require a 64-bit Android runtime")
}

enum class OfflinePackState { INACTIVE, DOWNLOADING, PAUSED, COMPLETE, FAILED }

data class OfflinePack(
    val id: UUID,
    val name: String,
    val layer: OfflineLayer,
    val bounds: GeoBounds,
    val minZoom: Int,
    val maxZoom: Int,
    val createdAt: Instant,
    val state: OfflinePackState,
    val completedFraction: Float,
) {
    init {
        require(name.isNotBlank())
        require(minZoom >= 0 && minZoom <= maxZoom)
        require(completedFraction in 0f..1f)
    }
}

data class OfflineLayerPolicy(
    val layer: OfflineLayer,
    val maxDownloadZoom: Int,
    val attribution: List<MapProviderAttribution>,
) {
    init {
        require(maxDownloadZoom >= 0)
        require(attribution.isNotEmpty())
    }
}

data class OfflinePackRequest(
    val id: UUID,
    val name: String,
    val layer: OfflineLayer,
    val bounds: GeoBounds,
    val minZoom: Int,
    val maxZoom: Int,
    val createdAt: Instant,
)

interface OfflineMapBackend {
    val packs: StateFlow<List<OfflinePack>>
    val downloadError: OfflineMapError? get() = null
    fun resumeError(packId: UUID): OfflineMapError? = downloadError
    suspend fun createAndStart(request: OfflinePackRequest)
    suspend fun delete(packId: UUID)
    suspend fun pause(packId: UUID)
    suspend fun resume(packId: UUID)
}

object UnavailableOfflineMapBackend : OfflineMapBackend {
    override val packs = MutableStateFlow<List<OfflinePack>>(emptyList())
    override suspend fun createAndStart(request: OfflinePackRequest) = unavailable()
    override suspend fun delete(packId: UUID) = unavailable()
    override suspend fun pause(packId: UUID) = unavailable()
    override suspend fun resume(packId: UUID) = unavailable()

    private fun unavailable(): Nothing = throw OfflineMapError.NativeRuntimeUnavailable
}

class OfflineMapController(
    private val backend: OfflineMapBackend,
    private val policies: Map<OfflineLayer, OfflineLayerPolicy>,
    private val availableBytes: () -> Long,
    private val networkAvailable: () -> Boolean,
    private val now: () -> Instant = Instant::now,
    private val nextId: () -> UUID = UUID::randomUUID,
) {
    val packs: StateFlow<List<OfflinePack>> = backend.packs
    val downloadError: OfflineMapError? get() = backend.downloadError
    fun resumeError(pack: OfflinePack): OfflineMapError? = backend.resumeError(pack.id)

    suspend fun downloadRegion(
        name: String,
        bounds: GeoBounds,
        layers: Set<OfflineLayer>,
        minZoom: Int = 10,
    ): Result<List<OfflinePack>> {
        downloadError?.let { return Result.failure(it) }
        if (availableBytes() < MINIMUM_DISK_BYTES) return Result.failure(OfflineMapError.InsufficientDiskSpace)
        if (!networkAvailable()) return Result.failure(OfflineMapError.NetworkUnavailable)
        if (layers.isEmpty()) return Result.failure(OfflineMapError.EmptyLayerSelection)
        val requestedAt = now()
        return try {
            val pending = layers.sortedBy { it.ordinal }.map { layer ->
                val policy = policies[layer] ?: return Result.failure(OfflineMapError.MissingLayerPolicy(layer))
                if (minZoom !in 0..policy.maxDownloadZoom) {
                    return Result.failure(OfflineMapError.InvalidZoomRange)
                }
                OfflinePackRequest(
                    id = nextId(),
                    name = name,
                    layer = layer,
                    bounds = bounds,
                    minZoom = minZoom,
                    maxZoom = policy.maxDownloadZoom,
                    createdAt = requestedAt,
                )
            }
            pending.forEach { backend.createAndStart(it) }
            Result.success(
                pending.map {
                    OfflinePack(
                        id = it.id,
                        name = it.name,
                        layer = it.layer,
                        bounds = it.bounds,
                        minZoom = it.minZoom,
                        maxZoom = it.maxZoom,
                        createdAt = it.createdAt,
                        state = OfflinePackState.DOWNLOADING,
                        completedFraction = 0f,
                    )
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun delete(pack: OfflinePack) {
        backend.delete(pack.id)
    }

    suspend fun pause(pack: OfflinePack) = backend.pause(pack.id)

    suspend fun resume(pack: OfflinePack) {
        resumeError(pack)?.let { throw it }
        backend.resume(pack.id)
    }

    companion object { const val MINIMUM_DISK_BYTES = 100_000_000L }
}
