// AndroidOnly: WP-314 Engine-free route-map membership/taps and tools selection rules (no source unit tests), under the trace test path.
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.maps.MapLineStyle
import com.meshcoreone.android.core.maps.MapPinStyle
import com.meshcoreone.android.feature.tools.navigation.ToolSelection
import com.meshcoreone.android.feature.tools.navigation.ToolsNavigationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceMapAndNavigationTest {
    @Test
    fun `pretrace overlays use real outbound identities and preserve skipped hop indices`() {
        map.configure(Coordinate(36.0, -123.0))
        trace.addNode(north)
        trace.addNode(unlocated)
        trace.addNode(hill)
        map.rebuildPathState()
        assertEquals(listOf("trace-0", "trace-2"), map.lines.map { it.id })
        assertEquals(listOf(0, 2), map.lines.map { it.pathIndex })
        assertEquals(
            listOf(GeoPoint(36.0, -123.0), GeoPoint(38.0, -122.0)), map.lines[0].points,
        )
        assertEquals(
            listOf(GeoPoint(38.0, -122.0), GeoPoint(37.5, -121.5)), map.lines[1].points,
        )
        assertTrue(map.lines.all { it.style == MapLineStyle.TRACE_UNTRACED })
        assertTrue(map.badges.isEmpty())
        val markers = map.mapMarkers { _, _ -> error("No badges before a trace") }
        assertEquals(1, markers.single { it.id == north.id }.hopIndex)
        assertEquals(3, markers.single { it.id == hill.id }.hopIndex)
        assertEquals(MapPinStyle.REPEATER_RING_WHITE, markers.single { it.id == hill.id }.style)
        assertTrue(markers.all { it.clusterable })
        map.clearPath()
        assertTrue(map.lines.isEmpty())
        assertTrue(map.badges.isEmpty())
    }

    @Test
    fun `table refresh rebuilds successful SNR geometry and deterministic midpoint badges`() {
        map.configure(Coordinate(37.0, 179.0))
        val endpoint = north.copy(latitude = 39.0, longitude = -179.0)
        trace.setContactsForTesting(listOf(endpoint))
        trace.addNode(endpoint)
        trace.editStateForTesting {
            it.copy(result = TraceResult(
                listOf(
                    TraceHop(null, "Origin", 0.0, true, false, null, null),
                    TraceHop(endpoint.publicKey, "North", -6.0, false, false, null, null),
                ).snapshot(),
                25, true, null, it.fullPathData, 1,
            ))
        }
        map.rebuildPathState()
        assertEquals(MapLineStyle.TRACE_WEAK, map.lines.single().style)
        assertEquals(GeoPoint(38.0, 180.0), map.badges.single().coordinate)
        assertEquals(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"), map.badges.single().id)
        val before = map.badges.single().distanceMeters
        assertTrue(before > 200_000 && before < 300_000)
        val badge = map.mapMarkers { _, snr -> "link $snr" }.single { it.style == MapPinStyle.BADGE }
        assertFalse(badge.clusterable)
        assertEquals("link -6.0", badge.badgeText)
        trace.setContactsForTesting(listOf(endpoint.copy(latitude = 40.0)))
        map.rebuildPathState()
        assertEquals(GeoPoint(40.0, -179.0), map.lines.single().points.last())
        assertEquals(MapLineStyle.TRACE_WEAK, map.lines.single().style)
        assertEquals(badge.id, map.badges.single().id)
        map.setShowLabels(false)
        assertTrue(map.mapMarkers { _, _ -> "link" }.filter { it.style != MapPinStyle.BADGE }.all { it.label == null })
    }

    @Test
    fun `late response after path edit cannot decorate new geometry`() {
        map.configure(Coordinate(37.0, -123.0))
        val sender = FakeTraceSender()
        harness.deps.device = device()
        harness.deps.sender = sender
        trace.configure(harness.deps)
        trace.addNode(north)
        harness.start { trace.runTrace() }
        val pendingTag = sender.sent.single().tag
        trace.addNode(south)
        assertNull(trace.pendingTag)
        harness.respond(pendingTag, listOf(node(0x3F, -6.0), node(null, 3.0)), RADIO)
        assertNull(trace.current.result)

        map.rebuildPathState()

        assertEquals(listOf(MapLineStyle.TRACE_UNTRACED, MapLineStyle.TRACE_UNTRACED), map.lines.map { it.style })
        assertTrue(map.badges.isEmpty())
    }

    @Test
    fun `initial camera uses path or filtered candidates and empty candidates have no camera`() {
        map.applyFilter(TraceMapPinFilter(favoritesOnly = true))
        assertEquals(GeoPoint(37.0, -122.0), map.initialCamera()?.center)
        trace.addNode(north)
        map.rebuildPathState()
        assertEquals(GeoPoint(38.0, -122.0), map.initialCamera()?.center)
        map.clearPath()
        trace.setContactsForTesting(emptyList())
        trace.editStateForTesting { it.copy(discoveredRepeaters = emptyList()) }
        map.rebuildPathState()
        assertNull(map.allPinsCamera())
        assertNull(map.initialCamera())
    }

    @Test
    fun `trace line quality preserves source SNR boundaries`() {
        assertEquals(TraceLineQuality.UNTRACED, traceLineQuality(null))
        assertEquals(TraceLineQuality.GOOD, traceLineQuality(0.01))
        assertEquals(TraceLineQuality.MEDIUM, traceLineQuality(0.0))
        assertEquals(TraceLineQuality.MEDIUM, traceLineQuality(-5.99))
        assertEquals(TraceLineQuality.WEAK, traceLineQuality(-6.0))
    }

    private val harness = TraceHarness()
    private val trace = harness.holder
    private val map = TraceMapPathLogic(trace)
    private val north = contact(0x3F, 0x01, name = "North", latitude = 38.0, longitude = -122.0)
    private val south = contact(0x3F, 0x02, name = "South", latitude = 37.0, longitude = -122.0, isFavorite = true)
    private val unlocated = contact(0x4F, name = "Nowhere")
    private val hill = discovered(key(0x5F), "Hill", latitude = 37.5, longitude = -121.5)

    init {
        trace.setContactsForTesting(listOf(north, south, unlocated))
        trace.editStateForTesting { it.copy(discoveredRepeaters = listOf(hill)) }
        map.rebuildPathState()
    }

    @Test
    fun `pins cover located contacts and discovered repeaters with 1-based path positions`() {
        assertEquals(listOf("North", "South", "Hill"), map.pins.map { it.label })
        assertEquals(PathPinTapResult.ADDED, map.handleRepeaterTap(north))
        assertEquals(PathPinTapResult.ADDED, map.handleMapPointTap(hill.id))
        assertEquals(RepeaterPathInfo(true, 1, false), map.pathState[north.id])
        assertEquals(RepeaterPathInfo(true, 2, true), map.pathState[hill.id])
        assertEquals(RepeaterPathInfo(false, null, false), map.pathState[south.id])
        assertNull(map.pathState[unlocated.id])
    }

    @Test
    fun `tapping the last hop removes it, a middle hop is rejected and unknown pins are ignored`() {
        map.handleRepeaterTap(north)
        map.handleRepeaterTap(south)
        assertEquals(PathPinTapResult.REJECTED_MIDDLE_HOP, map.handleRepeaterTap(north))
        assertEquals(PathPinTapResult.REMOVED, map.handleRepeaterTap(south))
        assertEquals(listOf("North"), harness.state.outboundPath.map { it.resolvedName })
        assertEquals(PathPinTapResult.IGNORED, map.handleMapPointTap(java.util.UUID.randomUUID()))
    }

    @Test
    fun `a hop keyed to a known node never falls back to a colliding prefix`() {
        trace.addNode(unlocated.copy(publicKey = key(0x3F, 0x09)))
        trace.setContactsForTesting(listOf(north, south, unlocated.copy(publicKey = key(0x3F, 0x09))))
        map.rebuildPathState()
        assertTrue(map.pathState.values.none { it.inPath })
        // A key-less hop falls back to the nearest prefix match from the map's user location.
        trace.setContactsForTesting(listOf(north, south, unlocated))
        trace.editStateForTesting { it.copy(outboundPath = listOf(TracePathHop(com.meshcoreone.android.core.protocol.bytes.Bytes.of(0x3F)))) }
        map.updateUserLocation(Coordinate(37.0001, -122.0))
        assertEquals(true, map.pathState[south.id]?.inPath)
        assertEquals(false, map.pathState[north.id]?.inPath)
    }

    @Test
    fun `favorites keep favorites and path members, and hide discovered except path members`() {
        map.handleMapPointTap(hill.id)
        map.applyFilter(TraceMapPinFilter(favoritesOnly = true))
        assertEquals(listOf("South", "Hill"), map.pins.map { it.label })
        map.applyFilter(TraceMapPinFilter(showDiscovered = false))
        assertEquals(listOf("North", "South", "Hill"), map.pins.map { it.label })
        assertFalse(TraceMapPinFilter(favoritesOnly = true, showDiscovered = true).effectiveShowDiscovered)
        map.setShowLabels(false)
        assertTrue(map.pins.all { it.label == null })
    }

    @Test
    fun `discovered nodes that are already contacts or unlocated are not shown`() {
        val duplicate = discovered(north.publicKey, "Dup", latitude = 1.0, longitude = 1.0)
        val nowhere = discovered(key(0x6F), "Nowhere")
        val visible = TraceMapPathLogic.visibleDiscoveredPins(
            listOf(duplicate, nowhere, hill), setOf(north.publicKey), emptySet(), TraceMapPinFilter(),
        )
        assertEquals(listOf(hill), visible)
    }

    @Test
    fun `location updates rebuild only with a path and after 10 m of movement`() {
        map.updateUserLocation(Coordinate(37.0, -122.0))
        trace.addNode(north)
        map.updateUserLocation(Coordinate(37.0, -122.0))
        assertEquals(true, map.pathState[north.id]?.inPath)
        trace.addNode(south)
        map.updateUserLocation(Coordinate(37.00005, -122.0))
        assertEquals(false, map.pathState[south.id]?.inPath)
        map.updateUserLocation(Coordinate(37.0002, -122.0))
        assertEquals(true, map.pathState[south.id]?.inPath)
        trace.clearPath()
        map.updateUserLocation(Coordinate(40.0, -122.0))
        assertEquals(true, map.pathState[south.id]?.inPath)
    }

    @Test
    fun `map traces are single traces and clearing resets membership`() {
        val sender = FakeTraceSender()
        harness.deps.sender = sender
        trace.configure(harness.deps)
        trace.setBatchEnabled(true)
        map.handleRepeaterTap(north)
        harness.complete { map.runTrace() }
        assertFalse(harness.state.batchEnabled)
        assertEquals(1, sender.sent.size)
        assertTrue(map.isRunning)
        assertEquals("North", map.generatePathName())
        map.clearPath()
        assertTrue(map.pathState.values.none { it.inPath })
        assertFalse(map.hasPath)
    }

    @Test
    fun `map run does not cancel an already running batch`() {
        val sender = FakeTraceSender()
        harness.deps.sender = sender
        trace.configure(harness.deps)
        trace.setBatchEnabled(true)
        map.handleRepeaterTap(north)
        harness.start { trace.runBatchTrace() }
        val tag = sender.sent.single().tag

        harness.complete { map.runTrace() }

        assertTrue(harness.state.batchEnabled)
        assertTrue(harness.state.isRunning)
        assertEquals(tag, trace.pendingTag)
        assertEquals(1, sender.sent.size)
    }

    @Test
    fun `tools keep source order and radio and sidebar rules`() {
        assertEquals(
            listOf("TRACE_PATH", "LINE_OF_SIGHT", "RX_LOG", "NOISE_FLOOR", "NODE_DISCOVERY", "CLI"),
            ToolSelection.entries.map { it.name },
        )
        assertEquals(listOf(ToolSelection.LINE_OF_SIGHT), ToolSelection.entries.filterNot { it.requiresRadio })
        assertEquals(setOf(ToolSelection.TRACE_PATH, ToolSelection.LINE_OF_SIGHT), ToolSelection.entries.filter { it.prefersCollapsedSidebar }.toSet())
    }

    @Test
    fun `entering or leaving Line of Sight is unanimated and titles follow the selection`() {
        val navigation = ToolsNavigationState()
        assertTrue(navigation.showsPlaceholder)
        assertFalse(navigation.detailShowsTitle)
        assertTrue(navigation.select(ToolSelection.TRACE_PATH).animated)
        assertTrue(navigation.detailShowsTitle)
        assertFalse(navigation.select(ToolSelection.LINE_OF_SIGHT).animated)
        assertTrue(navigation.showsLineOfSightPanel)
        assertFalse(navigation.detailShowsTitle)
        assertFalse(navigation.select(null).animated)
        assertTrue(navigation.select(ToolSelection.RX_LOG).animated)
        assertEquals(ToolSelection.RX_LOG, navigation.selectedTool.value)
    }
}
