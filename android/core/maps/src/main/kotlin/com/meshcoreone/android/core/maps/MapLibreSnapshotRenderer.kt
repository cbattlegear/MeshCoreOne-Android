// AndroidOnly: WP-312 MapLibre Native snapshot adapter with provider attribution enabled.
package com.meshcoreone.android.core.maps

import android.content.Context
import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.snapshotter.MapSnapshotter

class MapLibreSnapshotRenderer(
    context: Context,
    private val widthPixels: Int,
    private val heightPixels: Int,
) : MapSnapshotRenderer<Bitmap> {
    private val appContext = context.applicationContext

    init {
        require(widthPixels > 0 && heightPixels > 0)
    }

    override suspend fun render(request: MapSnapshotRequest): Bitmap? =
        render(request, { true }) { start -> start(); true }

    override suspend fun render(
        request: MapSnapshotRequest,
        isWanted: () -> Boolean,
        admit: MapSnapshotAdmission,
    ): Bitmap? = withContext(Dispatchers.Main.immediate) {
        if (!MapLibreRuntime.isSupported || request.isOffline) return@withContext null
        OpenFreeMapOfflineSuspension.install()
        MapLibre.getInstance(appContext)
        val result = CompletableDeferred<Bitmap?>()
        var snapshotter: MapSnapshotter? = null
        val finished = AtomicBoolean(false)
        try {
            if (!admit {
                if (!isWanted()) throw MapSnapshotNoLongerWanted()
                val options = MapSnapshotter.Options(widthPixels, heightPixels)
                    .withStyle(MapLibreOpenFreeMap.STYLE_URI)
                    .withCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(request.latitude, request.longitude))
                            .zoom(14.0)
                            .build(),
                    )
                    .withLogo(false)
                    .withAttribution(true)
                val current = MapSnapshotter(appContext, options).also { snapshotter = it }
                val appearance = MapLibreStyleAppearance(current::getLayer)
                current.setObserver(object : MapSnapshotter.Observer {
                    override fun onDidFinishLoadingStyle() {
                        appearance.apply(request.isDark)
                    }

                    override fun onStyleImageMissing(imageName: String) {
                        Logger.getLogger("com.meshcoreone.android.MapSnapshot")
                            .warning("MapLibre snapshot style image missing: $imageName")
                    }
                })
                current.start(
                    { snapshot -> if (finished.compareAndSet(false, true)) result.complete(snapshot.bitmap) },
                    { reason ->
                        Logger.getLogger("com.meshcoreone.android.MapSnapshot").warning("MapLibre snapshot failed: $reason")
                        if (finished.compareAndSet(false, true)) result.complete(null)
                    },
                )
            }) throw MapSnapshotNoLongerWanted()
            result.await()
        } finally {
            if (finished.compareAndSet(false, true)) snapshotter?.cancel()
        }
    }
}
