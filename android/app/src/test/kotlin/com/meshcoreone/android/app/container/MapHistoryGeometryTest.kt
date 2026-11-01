// PortedFrom: MC1Tests/Views/LocationPathMapBuilderTests.swift@db14559b39d32322b06477c6ae676112f583db50
// AndroidOnly: WP-312 Real history builder -> mounted-consumer adapter -> renderer GeoJSON assertions, not JNI evidence.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.maps.*
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.simulator.MockDataProvider
import com.meshcoreone.android.core.services.simulator.nodeStatusSnapshots
import com.meshcoreone.android.feature.remotenodes.map.LocationPathMapBuilder
import com.meshcoreone.android.feature.remotenodes.map.NodeLocationMapContent
import com.meshcoreone.android.feature.remotenodes.map.NodeLocationMapStateHolder
import com.meshcoreone.android.feature.remotenodes.telemetry.MeasurementSystem
import com.meshcoreone.android.feature.remotenodes.ui.remoteMapPresentation
import java.time.Instant
import java.util.Locale
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.geojson.LineString

class MapHistoryGeometryTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")

    @Test fun seededHistoryProducesFiveActualOrderedGeoJsonRunsWithEveryReportAndHero() {
        val snapshots = MockDataProvider.nodeStatusSnapshots(now).toList()
        val content = NodeLocationMapContent.fromSnapshots(snapshots, snapshots.last().id)
        val holder = NodeLocationMapStateHolder(content)
        holder.onAppear()
        holder.onStyleLoaded(true)
        val state = holder.state.value
        val presentation = remoteMapPresentation(
            state.displayPoints, state.displayLines, state.cameraRegion, Locale.US, MeasurementSystem.METRIC, "dB",
        )
        assertEquals(20, snapshots.size)
        assertEquals(20, presentation.points.size)
        assertEquals(20, content.reports.size)
        assertEquals(listOf(3, 3, 3, 5, 6), presentation.lines.map { it.points.size })
        val expectedRuns = listOf(
            listOf(37.7690 to -122.4830, 37.7695 to -122.4800, 37.7700 to -122.4770),
            listOf(37.7585 to -122.4270, 37.7600 to -122.4255, 37.7615 to -122.4240),
            listOf(37.7980 to -122.4650, 37.7995 to -122.4620, 37.8010 to -122.4590),
            listOf(37.7350 to -122.4770, 37.7340 to -122.4780, 37.7330 to -122.4785, 37.7325 to -122.4790, 37.7320 to -122.4795),
            listOf(37.7260 to -122.4800, 37.7230 to -122.4798, 37.7200 to -122.4796, 37.7180 to -122.4795, 37.7160 to -122.4794, 37.7149 to -122.4794),
        )
        presentation.lines.forEachIndexed { index, line ->
            val feature = mapLineFeature(line, "#6B7280")
            assertEquals("location-trail-$index", feature.id())
            assertEquals("LOCATION_TRAIL", feature.getStringProperty("lineStyle"))
            assertEquals(1.0, feature.getNumberProperty("opacity").toDouble(), 0.0)
            assertEquals(expectedRuns[index], (feature.geometry() as LineString).coordinates().map { it.latitude() to it.longitude() })
        }
        assertTrue(presentation.points.dropLast(1).all { it.style == MapPinStyle.LOCATION_FIX })
        assertEquals(MapPinStyle.LOCATION_FIX_LATEST, presentation.points.last().style)
        assertTrue(presentation.points.none { it.clusterable })
        assertEquals(snapshots.map { it.id }.toSet(), content.reports.values.map { it.id }.toSet())
        val hero = content.reports.getValue(presentation.points.last().id)
        assertEquals(snapshots.last().id, hero.id)
        assertEquals(snapshots.last().timestamp, hero.timestamp)
        assertEquals(42.0, hero.altitude)
        assertEquals(1, content.reports.values.count { it.altitude == null })
        assertEquals(19, content.reports.values.count { it.altitude != null })
        assertEquals(presentation.points.last().id, holder.state.value.pendingSelectionPointId)
        holder.onAppear()
        assertEquals(presentation.points.map { it.id }, holder.state.value.displayPoints.map { it.id })
    }

    @Test fun realNineFixBuilderMapsEveryBucketToIndependentLightAndDarkPaintWithoutChangingIdentity() {
        val snapshots = (0 until 9).map {
            snapshot(it * 600L, 37.0 + it * 0.01, -122.0)
        }
        val built = LocationPathMapBuilder.build(snapshots, decimatePins = false)
        val presentation = remoteMapPresentation(built.points, built.lines, null, Locale.US, MeasurementSystem.METRIC, "dB")
        assertEquals(listOf(0, 1, 1, 2, 2, 3, 3, 4), presentation.points.dropLast(1).map { it.hopIndex })
        val light = listOf("#A19687", "#B68F68", "#B68F68", "#D17A47", "#D17A47", "#E76740", "#E76740", "#F55E47", "#FF375F")
        val dark = listOf("#B1A695", "#C59E77", "#C59E77", "#D8885A", "#D8885A", "#ED7C5A", "#ED7C5A", "#F87662", "#FF375F")
        assertEquals(light, presentation.points.map { mapMarkerFeature(it, false).getStringProperty("color") })
        assertEquals(dark, presentation.points.map { mapMarkerFeature(it, true).getStringProperty("color") })
        presentation.points.forEach { point ->
            assertEquals(point.id.toString(), mapMarkerFeature(point, false).id())
            assertEquals(point.id.toString(), mapMarkerFeature(point, true).id())
        }
        assertEquals(7.0, mapMarkerFeature(presentation.points.first(), false).getNumberProperty("radius").toDouble(), 0.0)
        assertEquals(11.0, mapMarkerFeature(presentation.points.last(), false).getNumberProperty("radius").toDouble(), 0.0)
    }

    @Test fun exactHourJoinsButOneSecondOverSplitsWithoutPhantomSingletonGeoJson() {
        for ((gap, count) in listOf(3_600L to 1, 3_601L to 0)) {
            val built = LocationPathMapBuilder.build(listOf(snapshot(0, 37.0, -122.0), snapshot(gap, 38.0, -123.0)))
            val presentation = remoteMapPresentation(built.points, built.lines, null, Locale.US, MeasurementSystem.METRIC, "dB")
            assertEquals(count, presentation.lines.size)
            assertEquals(2, presentation.points.size)
            assertEquals(MapPinStyle.LOCATION_FIX_LATEST, presentation.points.last().style)
        }
        val built = LocationPathMapBuilder.build(listOf(
            snapshot(0, null, null), snapshot(600, 0.0, 0.0), snapshot(1_200, 37.0, -122.0),
        ))
        assertTrue(built.lines.isEmpty())
        assertEquals(1, built.points.size)
        assertEquals(1, built.reports.size)
    }

    @Test fun decimationPreservesAllTwoHundredTrailCoordinatesAndFullScreenReportMembership() {
        val snapshots = (0 until 200).map { snapshot(it * 600L, 37.0 + it * 0.001, -122.0) }
        val preview = LocationPathMapBuilder.build(snapshots)
        val full = NodeLocationMapContent.fromSnapshots(snapshots, null)
        assertEquals(52, preview.points.size)
        assertTrue(preview.points.size <= LocationPathMapBuilder.MAX_PINS)
        assertEquals(200, preview.lines.single().coordinates.size)
        assertEquals(200, full.points.size)
        assertEquals(200, full.reports.size)
        val presentation = remoteMapPresentation(full.points, full.lines, null, Locale.US, MeasurementSystem.METRIC, "dB")
        val generated = (mapLineFeature(presentation.lines.single(), "#6B7280").geometry() as LineString).coordinates()
        assertEquals(200, generated.size)
        assertEquals(snapshots.map { it.latitude to it.longitude }, generated.map { it.latitude() to it.longitude() })
        assertTrue(presentation.points.dropLast(1).all { it.style == MapPinStyle.LOCATION_FIX })
        assertEquals(MapPinStyle.LOCATION_FIX_LATEST, presentation.points.last().style)
    }

    @Test fun actualSeededTimeBandsAddDistinctReportAndGeometryCounts() {
        val seeded = MockDataProvider.nodeStatusSnapshots(now).toList()
        val bands = listOf(7L, 30L, 90L, null)
        val counts = bands.map { days ->
            val snapshots = if (days == null) seeded else seeded.filter {
                !it.timestamp.isBefore(now.minusSeconds(days * 86_400L))
            }
            val content = NodeLocationMapContent.fromSnapshots(snapshots, null)
            assertEquals(snapshots.size, content.points.size)
            assertEquals(snapshots.size, content.reports.size)
            content.points.size
        }
        assertEquals(listOf(11, 14, 17, 20), counts)
        assertTrue(counts.zipWithNext().all { (before, after) -> before < after })
    }

    private fun snapshot(seconds: Long, lat: Double?, lon: Double?) = NodeStatusSnapshotDTO(
        id = UUID(0, seconds + 1), timestamp = now.plusSeconds(seconds),
        nodePublicKey = Bytes(ByteArray(32) { 0xDD.toByte() }), latitude = lat, longitude = lon,
    )
}
