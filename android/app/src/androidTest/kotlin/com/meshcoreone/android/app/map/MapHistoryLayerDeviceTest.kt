// PortedFrom: MC1Tests/Views/LocationPathMapBuilderTests.swift@db14559b39d32322b06477c6ae676112f583db50
// AndroidOnly: WP-312 Installed native layers and source geometry from the real history consumer.
// This test's existence is not execution evidence; requires a supported device and actual provider readiness.
package com.meshcoreone.android.app.map

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.maps.*
import com.meshcoreone.android.core.services.simulator.MockDataProvider
import com.meshcoreone.android.core.services.simulator.nodeStatusSnapshots
import com.meshcoreone.android.feature.remotenodes.map.NodeLocationMapContent
import com.meshcoreone.android.feature.remotenodes.map.NodeLocationMapStateHolder
import com.meshcoreone.android.feature.remotenodes.telemetry.MeasurementSystem
import com.meshcoreone.android.feature.remotenodes.ui.remoteMapPresentation
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString

@RunWith(AndroidJUnit4::class)
class MapHistoryLayerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun actualInstalledTrailLayersPreserveSeededGeometryAndRefreshRecencyPaint() {
        assertTrue("Native MapLibre requires a supported 64-bit process", MapLibreRuntime.isSupported)
        val snapshots = MockDataProvider.nodeStatusSnapshots(Instant.parse("2026-01-01T12:00:00Z")).toList()
        val content = NodeLocationMapContent.fromSnapshots(snapshots, snapshots.last().id)
        val holder = NodeLocationMapStateHolder(content)
        holder.onAppear()
        holder.onStyleLoaded(true)
        val state = holder.state.value
        val initial = remoteMapPresentation(
            state.displayPoints, state.displayLines, state.cameraRegion, Locale.US, MeasurementSystem.METRIC, "dB",
        )
        val presentation = mutableStateOf(initial)
        val nativeMap = AtomicReference<MapLibreMap?>()
        compose.runOnUiThread {
            compose.activity.setContent {
                MeshCoreTheme(motionScale = 0f) {
                    Box(Modifier.width(320.dp).height(500.dp)) {
                        MapLibreMapSurface(
                            presentation.value, false, false, "WP312 native history layers",
                            {}, {}, modifier = Modifier,
                        )
                    }
                }
            }
        }
        compose.waitUntil(30_000) {
            compose.runOnUiThread {
                findMapView(compose.activity.window.decorView)?.getMapAsync { nativeMap.set(it) }
            }
            var ready = false
            compose.runOnUiThread {
                val style = nativeMap.get()?.style
                ready = style?.getLayer("mesh-location-trail") != null &&
                    style.getSourceAs<GeoJsonSource>("mesh-lines")
                        ?.querySourceFeatures(Expression.literal(true))?.size == 5
            }
            ready
        }
        compose.runOnUiThread {
            val style = checkNotNull(nativeMap.get()?.style)
            val casing = checkNotNull(style.getLayerAs<LineLayer>("mesh-location-trail-casing"))
            val trail = checkNotNull(style.getLayerAs<LineLayer>("mesh-location-trail"))
            val untraced = checkNotNull(style.getLayerAs<LineLayer>("mesh-untraced-lines"))
            val solid = checkNotNull(style.getLayerAs<LineLayer>("mesh-lines-layer"))
            assertEquals(listOf(0.8f, 1.4f), casing.lineDasharray.value?.toList())
            assertEquals(listOf(1.6f, 2.8f), trail.lineDasharray.value?.toList())
            assertEquals(5f, checkNotNull(casing.lineWidth.value), 0f)
            assertEquals(2.5f, checkNotNull(trail.lineWidth.value), 0f)
            assertEquals(0.8f, checkNotNull(casing.lineOpacity.value), 0f)
            assertEquals("round", casing.lineCap.value)
            assertEquals("round", casing.lineJoin.value)
            assertEquals("round", trail.lineCap.value)
            assertEquals("round", trail.lineJoin.value)
            assertEquals(listOf(1.75f, 3.25f), untraced.lineDasharray.value?.toList())
            val ids = style.layers.map { it.id }
            assertEquals(ids.indexOf("mesh-location-trail-casing") + 1, ids.indexOf("mesh-location-trail"))
            assertTrue(checkNotNull(trail.filter).toString().contains("LOCATION_TRAIL"))
            assertTrue(checkNotNull(casing.filter).toString().contains("LOCATION_TRAIL"))
            assertFalse(checkNotNull(solid.filter).toString().contains("LOCATION_TRAIL"))
            val lines = checkNotNull(style.getSourceAs<GeoJsonSource>("mesh-lines"))
                .querySourceFeatures(Expression.literal(true)).sortedBy { it.id() }
            assertEquals(listOf(3, 3, 3, 5, 6), lines.map { (it.geometry() as LineString).coordinates().size })
            assertEquals(initial.lines.map { it.points.map { point -> point.latitude to point.longitude } },
                lines.map { (it.geometry() as LineString).coordinates().map { point -> point.latitude() to point.longitude() } })
            val dots = checkNotNull(style.getSourceAs<GeoJsonSource>("mesh-fixed")).querySourceFeatures(Expression.literal(true))
            assertEquals(20, dots.size)
            val oldest = dots.single { it.id() == initial.points.first().id.toString() }
            val hero = dots.single { it.id() == initial.points.last().id.toString() }
            assertEquals("#A19687", oldest.getStringProperty("color"))
            assertEquals("#FF375F", hero.getStringProperty("color"))
            assertEquals(7.0, oldest.getNumberProperty("radius").toDouble(), 0.0)
            assertEquals(11.0, hero.getNumberProperty("radius").toDouble(), 0.0)
            val fixed = checkNotNull(style.getLayerAs<CircleLayer>("mesh-fixed-points"))
            assertTrue(fixed.circleColor.isExpression)
            assertTrue(fixed.circleRadius.isExpression)
        }
        compose.runOnUiThread { presentation.value = initial.copy(isDark = true) }
        compose.waitUntil(30_000) {
            var updated = false
            compose.runOnUiThread {
                val source = nativeMap.get()?.style?.getSourceAs<GeoJsonSource>("mesh-fixed")
                updated = source?.querySourceFeatures(Expression.literal(true))
                    ?.singleOrNull { it.id() == initial.points.first().id.toString() }
                    ?.getStringProperty("color") == "#B1A695"
            }
            updated
        }
        compose.runOnUiThread {
            val dots = checkNotNull(nativeMap.get()?.style?.getSourceAs<GeoJsonSource>("mesh-fixed"))
                .querySourceFeatures(Expression.literal(true))
            assertEquals(initial.points.map { it.id.toString() }.toSet(), dots.map { it.id() }.toSet())
            assertEquals("#FF375F", dots.single { it.id() == initial.points.last().id.toString() }.getStringProperty("color"))
            compose.activity.setContent {}
        }
    }

    private fun findMapView(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findMapView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
