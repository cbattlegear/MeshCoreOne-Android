// PortedFrom: MC1Tests/ViewModels/TracePathViewModelTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.TraceNode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TracePathCoreTest {
    private fun testContact() = contact(0xAB, name = "Test Repeater")
    private fun testSavedPath(runs: List<com.meshcoreone.android.core.model.TracePathRunDTO> = emptyList()) =
        savedPath(Bytes.of(0x01, 0x02, 0x01), runs = runs)

    private fun locationHop(lat: Double?, lon: Double?) =
        TraceHop(Bytes.of(0x3F), "Tower", 5.0, false, false, lat, lon)

    @Test @OriginalCase("TraceHopLocationTests::hasLocation returns true with valid non-zero coordinates()")
    fun `hasLocation returns true with valid non-zero coordinates`() = assertTrue(locationHop(37.7749, -122.4194).hasLocation)

    @Test @OriginalCase("TraceHopLocationTests::hasLocation returns false with zero coordinates()")
    fun `hasLocation returns false with zero coordinates`() = assertFalse(locationHop(0.0, 0.0).hasLocation)

    @Test @OriginalCase("TraceHopLocationTests::hasLocation returns false with nil coordinates()")
    fun `hasLocation returns false with nil coordinates`() = assertFalse(locationHop(null, null).hasLocation)

    @Test @OriginalCase("TraceHopLocationTests::hasLocation returns true if only latitude is non-zero()")
    fun `hasLocation returns true if only latitude is non-zero`() = assertTrue(locationHop(45.0, 0.0).hasLocation)

    @Test @OriginalCase("TraceHopLocationTests::hasLocation returns true if only longitude is non-zero()")
    fun `hasLocation returns true if only longitude is non-zero`() = assertTrue(locationHop(0.0, -122.0).hasLocation)

    @Test @OriginalCase("PathEditClearsSavedPathTests::addRepeater clears activeSavedPath()")
    fun `addRepeater clears activeSavedPath`() {
        val harness = TraceHarness()
        harness.holder.editStateForTesting { it.copy(activeSavedPath = testSavedPath()) }
        assertNotNull(harness.state.activeSavedPath)
        harness.holder.addNode(testContact())
        assertNull(harness.state.activeSavedPath)
    }

    @Test @OriginalCase("PathEditClearsSavedPathTests::removeRepeater clears activeSavedPath()")
    fun `removeRepeater clears activeSavedPath`() {
        val harness = TraceHarness()
        harness.holder.addNode(testContact())
        harness.holder.editStateForTesting { it.copy(activeSavedPath = testSavedPath()) }
        harness.holder.removeRepeater(0)
        assertNull(harness.state.activeSavedPath)
    }

    @Test @OriginalCase("PathEditClearsSavedPathTests::moveRepeater clears activeSavedPath()")
    fun `moveRepeater clears activeSavedPath`() {
        val harness = TraceHarness()
        harness.holder.addNode(testContact())
        harness.holder.addNode(testContact())
        harness.holder.editStateForTesting { it.copy(activeSavedPath = testSavedPath()) }
        val before = harness.state.outboundPath
        harness.holder.moveRepeater(setOf(0), 2)
        assertNull(harness.state.activeSavedPath)
        assertEquals(listOf(before[1], before[0]), harness.state.outboundPath)
    }

    @Test @OriginalCase("PreviousRunComparisonTests::previousRun returns nil when no runs exist()")
    fun `previousRun returns nil when no runs exist`() =
        assertNull(TracePathState(activeSavedPath = testSavedPath()).previousRun)

    @Test @OriginalCase("PreviousRunComparisonTests::previousRun returns nil when only one run exists()")
    fun `previousRun returns nil when only one run exists`() =
        assertNull(TracePathState(activeSavedPath = testSavedPath(listOf(run(T0)))).previousRun)

    @Test @OriginalCase("PreviousRunComparisonTests::previousRun returns second-to-last run when two runs exist()")
    fun `previousRun returns second-to-last run when two runs exist`() {
        val runs = listOf(run(T0.minusSeconds(60), 150), run(T0, 100))
        assertEquals(150, TracePathState(activeSavedPath = testSavedPath(runs)).previousRun?.roundTripMs)
    }

    @Test @OriginalCase("PreviousRunComparisonTests::previousRun returns second-to-last run when multiple runs exist()")
    fun `previousRun returns second-to-last run when multiple runs exist`() {
        val runs = listOf(run(T0.minusSeconds(120), 200), run(T0.minusSeconds(60), 150), run(T0, 100))
        assertEquals(150, TracePathState(activeSavedPath = testSavedPath(runs)).previousRun?.roundTripMs)
    }

    @Test @OriginalCase("PreviousRunComparisonTests::previousRun skips failed runs when finding comparison()")
    fun `previousRun skips failed runs when finding comparison`() {
        val runs = listOf(run(T0.minusSeconds(120), 200), run(T0.minusSeconds(60), success = false), run(T0, 100))
        assertEquals(200, TracePathState(activeSavedPath = testSavedPath(runs)).previousRun?.roundTripMs)
    }

    @Test @OriginalCase("PreviousRunComparisonTests::previousRun returns nil when only one successful run exists among failures()")
    fun `previousRun returns nil when only one successful run exists among failures`() {
        val runs = listOf(run(T0.minusSeconds(120), success = false), run(T0.minusSeconds(60), success = false), run(T0, 100))
        assertNull(TracePathState(activeSavedPath = testSavedPath(runs)).previousRun)
    }

    private fun respondSingleHop(harness: TraceHarness, tag: UInt = 12345u, radioId: RadioId? = null) {
        harness.holder.setPendingTagForTesting(12345u)
        harness.respond(tag, listOf(node(0xAB, 5.0), node(null, 3.0)), radioId)
    }

    @Test @OriginalCase("TraceResponseHopParsingTests::handleTraceResponse creates correct hops with receiver SNR attribution()")
    fun `handleTraceResponse creates correct hops with receiver SNR attribution`() {
        val harness = TraceHarness()
        respondSingleHop(harness)
        val result = assertNotNull(harness.state.result)
        assertTrue(result.success)
        assertEquals(3, result.hops.size)
        assertTrue(result.hops[0].isStartNode)
        assertNull(result.hops[0].hashBytes)
        assertEquals(0.0, result.hops[0].snr)
        assertFalse(result.hops[1].isStartNode)
        assertFalse(result.hops[1].isEndNode)
        assertEquals(Bytes.of(0xAB), result.hops[1].hashBytes)
        assertEquals(5.0, result.hops[1].snr)
        assertTrue(result.hops[2].isEndNode)
        assertNull(result.hops[2].hashBytes)
        assertEquals(3.0, result.hops[2].snr)
    }

    @Test @OriginalCase("TraceResponseHopParsingTests::handleTraceResponse creates correct hops for multi-hop trace with receiver SNR attribution()")
    fun `handleTraceResponse creates correct hops for multi-hop trace with receiver SNR attribution`() {
        val harness = TraceHarness()
        harness.holder.setPendingTagForTesting(12345u)
        harness.respond(12345u, listOf(node(0xAA, 6.0), node(0xBB, 4.0), node(0xCC, 2.0), node(null, -1.0)))
        val result = assertNotNull(harness.state.result)
        assertEquals(5, result.hops.size)
        assertEquals(listOf(0.0, 6.0, 4.0, 2.0, -1.0), result.hops.map { it.snr })
        assertEquals(listOf(Bytes.of(0xAA), Bytes.of(0xBB), Bytes.of(0xCC)), result.hops.subList(1, 4).map { it.hashBytes })
    }

    @Test @OriginalCase("TraceResponseHopParsingTests::handleTraceResponse ignores non-matching tags()")
    fun `handleTraceResponse ignores non-matching tags`() {
        val harness = TraceHarness()
        respondSingleHop(harness, tag = 99999u)
        assertNull(harness.state.result)
    }

    @Test @OriginalCase("ResultIDBehaviorTests::resultID is set on successful trace()")
    fun `resultID is set on successful trace`() {
        val harness = TraceHarness()
        harness.holder.setPendingTagForTesting(12345u)
        assertNull(harness.state.resultID)
        harness.respond(12345u, listOf(node(0xAB, 5.0), node(null, 3.0)))
        assertNotNull(harness.state.resultID)
    }

    @Test @OriginalCase("ResultIDBehaviorTests::resultID changes on each successful trace()")
    fun `resultID changes on each successful trace`() {
        val harness = TraceHarness()
        respondSingleHop(harness)
        val first = harness.state.resultID
        harness.holder.setPendingTagForTesting(12346u)
        harness.respond(12346u, listOf(node(0xAB, 5.0), node(null, 3.0)))
        assertNotEquals(first, harness.state.resultID)
    }

    @Test @OriginalCase("MultiByteHashTests::multi-byte hash produces hop with full hashBytes and nil resolvedName()")
    fun `multi-byte hash produces hop with full hashBytes and nil resolvedName`() {
        val harness = TraceHarness()
        harness.holder.setPendingTagForTesting(12345u)
        harness.respond(12345u, listOf(TraceNode(Bytes.of(0xAB, 0xCD), 5.0), TraceNode(null, 3.0)))
        val result = assertNotNull(harness.state.result)
        assertEquals(Bytes.of(0xAB, 0xCD), result.hops[1].hashBytes)
        assertNull(result.hops[1].resolvedName)
        assertEquals("ABCD", result.hops[1].hashDisplayString)
    }

    @Test @OriginalCase("MultiByteHashTests::single-byte hash still resolves to contact name()")
    fun `single-byte hash still resolves to contact name`() {
        val harness = TraceHarness()
        respondSingleHop(harness)
        val result = assertNotNull(harness.state.result)
        assertEquals(Bytes.of(0xAB), result.hops[1].hashBytes)
        assertEquals("AB", result.hops[1].hashDisplayString)
    }

    @Test @OriginalCase("DeviceIDValidationTests::response from different device is ignored()")
    fun `response from different device is ignored`() {
        val harness = TraceHarness()
        harness.holder.setPendingDeviceIdForTesting(RadioId(UUID.randomUUID()))
        respondSingleHop(harness, radioId = RadioId(UUID.randomUUID()))
        assertNull(harness.state.result)
    }

    @Test @OriginalCase("DeviceIDValidationTests::response accepted when device IDs match()")
    fun `response accepted when device IDs match`() {
        val harness = TraceHarness()
        val radio = RadioId(UUID.randomUUID())
        harness.holder.setPendingDeviceIdForTesting(radio)
        respondSingleHop(harness, radioId = radio)
        assertEquals(true, harness.state.result?.success)
    }

    @Test @OriginalCase("DeviceIDValidationTests::tag-only matching works when pendingDeviceID is nil()")
    fun `tag-only matching works when pendingDeviceID is nil`() {
        val harness = TraceHarness()
        harness.holder.setPendingDeviceIdForTesting(null)
        respondSingleHop(harness, radioId = RadioId(UUID.randomUUID()))
        assertNotNull(harness.state.result)
    }

    @Test @OriginalCase("DeviceIDValidationTests::tag-only matching works when received deviceID is nil()")
    fun `tag-only matching works when received deviceID is nil`() {
        val harness = TraceHarness()
        harness.holder.setPendingDeviceIdForTesting(RadioId(UUID.randomUUID()))
        respondSingleHop(harness, radioId = null)
        assertNotNull(harness.state.result)
    }

    @Test @OriginalCase("PathCaptureTests::result contains original path even if outboundPath modified()")
    fun `result contains original path even if outboundPath modified`() {
        val harness = TraceHarness()
        val original = Bytes.of(0xAA, 0xBB, 0xAA)
        harness.holder.setPendingPathHashForTesting(original)
        harness.holder.setPendingTagForTesting(12345u)
        harness.respond(12345u, listOf(node(0xAA, 5.0), node(null, 3.0)))
        val result = assertNotNull(harness.state.result)
        assertEquals(original, result.tracedPathBytes)
        assertEquals("AA,BB,AA", result.tracedPathString)
    }

    @Test @OriginalCase("PathCaptureTests::canSavePath is false when path modified after trace()")
    fun `canSavePath is false when path modified after trace`() {
        val harness = TraceHarness()
        val original = Bytes.of(0xAA, 0xAA)
        harness.holder.setPendingPathHashForTesting(original)
        harness.holder.setPendingTagForTesting(12345u)
        harness.respond(12345u, listOf(node(0xAA, 5.0), node(null, 3.0)))
        assertEquals(original, harness.state.result?.tracedPathBytes)
        harness.holder.addNode(contact(0xBB, name = "Different"))
        assertFalse(harness.state.canSavePath)
    }

    @Test @OriginalCase("PathCaptureTests::canSavePath is true when path unchanged after trace()")
    fun `canSavePath is true when path unchanged after trace`() {
        val harness = TraceHarness()
        harness.holder.addNode(contact(0xAA, name = "Repeater"))
        harness.holder.setPendingPathHashForTesting(harness.state.fullPathData)
        harness.holder.setPendingTagForTesting(12345u)
        harness.respond(12345u, listOf(node(0xAA, 5.0), node(null, 3.0)))
        assertTrue(harness.state.canSavePath)
    }

    @Test
    fun `canSavePath compares the current wire path with the traced bytes without relying on a cleared result`() {
        val harness = TraceHarness()
        harness.holder.addNode(contact(0xAA, name = "Repeater"))
        val traced = harness.state.fullPathData
        harness.holder.editStateForTesting { it.copy(result = result(emptyList(), path = traced)) }
        assertTrue(harness.state.canSavePath)
        harness.holder.editStateForTesting { it.copy(autoReturnPath = false, outboundPath = it.outboundPath + it.outboundPath) }
        assertFalse(harness.state.canSavePath)
    }
}
