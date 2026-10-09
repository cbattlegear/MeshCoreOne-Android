// AndroidOnly: WP-314 Engine-free route-map membership/taps and tools selection rules (no source unit tests), under the trace test path.
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.feature.tools.navigation.ToolSelection
import com.meshcoreone.android.feature.tools.navigation.ToolsNavigationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceMapAndNavigationTest {
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
