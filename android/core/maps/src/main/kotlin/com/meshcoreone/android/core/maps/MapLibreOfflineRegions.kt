// AndroidOnly: WP-312 Native region bridge; permits deterministic lifecycle tests without JNI.
package com.meshcoreone.android.core.maps

import android.content.Context
import java.util.logging.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

internal data class OfflineRegionProgress(
    val complete: Boolean,
    val active: Boolean,
    val fraction: Float,
)

internal interface MapLibreOfflineRegion {
    val styleUri: String?
    val metadata: ByteArray
    fun setActive(active: Boolean)
    fun observe(onProgress: (OfflineRegionProgress) -> Unit, onFailure: () -> Unit)
    suspend fun status(): OfflineRegionProgress
    suspend fun delete()
}

internal interface MapLibreOfflineRegions {
    suspend fun create(request: OfflinePackRequest, styleUri: String, metadata: ByteArray): MapLibreOfflineRegion
    suspend fun list(): List<MapLibreOfflineRegion>
}

internal class NativeMapLibreOfflineRegions(context: Context) : MapLibreOfflineRegions {
    private val appContext = context.applicationContext
    private val manager: OfflineManager

    init {
        OpenFreeMapOfflineSuspension.install()
        MapLibre.getInstance(appContext)
        manager = OfflineManager.getInstance(appContext)
    }

    override suspend fun create(
        request: OfflinePackRequest,
        styleUri: String,
        metadata: ByteArray,
    ): MapLibreOfflineRegion = suspendCancellableCoroutine { continuation ->
        val bounds = request.bounds
        val definition = OfflineTilePyramidRegionDefinition(
            styleUri,
            LatLngBounds.from(
                bounds.northeast.latitude, bounds.northeast.longitude,
                bounds.southwest.latitude, bounds.southwest.longitude,
            ),
            request.minZoom.toDouble(),
            request.maxZoom.toDouble(),
            appContext.resources.displayMetrics.density.coerceAtLeast(1f),
        )
        manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                if (continuation.isActive) continuation.resume(NativeRegion(offlineRegion))
                else offlineRegion.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() = Unit
                    override fun onError(error: String) {
                        Logger.getLogger("com.mc1.OfflineMaps").warning("Cancelled region cleanup failed: $error")
                    }
                })
            }

            override fun onError(error: String) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
            }
        })
    }

    override suspend fun list(): List<MapLibreOfflineRegion> = suspendCancellableCoroutine { continuation ->
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                if (continuation.isActive) continuation.resume(offlineRegions.orEmpty().map(::NativeRegion))
            }

            override fun onError(error: String) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
            }
        })
    }
}

private class NativeRegion(private val region: OfflineRegion) : MapLibreOfflineRegion {
    override val styleUri: String? get() = region.definition.styleURL
    override val metadata: ByteArray get() = region.metadata
    override fun setActive(active: Boolean) {
        region.setDownloadState(if (active) OfflineRegion.STATE_ACTIVE else OfflineRegion.STATE_INACTIVE)
    }

    override fun observe(onProgress: (OfflineRegionProgress) -> Unit, onFailure: () -> Unit) {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) = onProgress(status.progress())
            override fun onError(error: OfflineRegionError) = onFailure()
            override fun mapboxTileCountLimitExceeded(limit: Long) = onFailure()
        })
    }

    override suspend fun status(): OfflineRegionProgress = suspendCancellableCoroutine { continuation ->
        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) {
                if (!continuation.isActive) return
                if (status != null) continuation.resume(status.progress())
                else continuation.resumeWithException(IllegalStateException("Missing offline status"))
            }

            override fun onError(error: String?) {
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException(error ?: "Offline status failed"))
                }
            }
        })
    }

    override suspend fun delete(): Unit = suspendCancellableCoroutine { continuation ->
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                if (continuation.isActive) continuation.resume(Unit)
            }
            override fun onError(error: String) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
            }
        })
    }
}

private fun OfflineRegionStatus.progress(): OfflineRegionProgress {
    val complete = isRequiredResourceCountPrecise && requiredResourceCount > 0L && isComplete
    val fraction = when {
        complete -> 1f
        !isRequiredResourceCountPrecise || requiredResourceCount <= 0L -> 0f
        else -> (completedResourceCount.toDouble() / requiredResourceCount).toFloat().coerceIn(0f, 1f)
    }
    return OfflineRegionProgress(complete, downloadState == OfflineRegion.STATE_ACTIVE, fraction)
}
