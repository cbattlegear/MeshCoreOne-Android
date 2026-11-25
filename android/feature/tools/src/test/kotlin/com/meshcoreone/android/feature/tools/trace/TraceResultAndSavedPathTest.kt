// PortedFrom: MC1Tests/ViewModels/TracePathViewModelTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceResultAndSavedPathTest {
    private val sf = 37.7749 to -122.4194
    private val oakland = 37.8044 to -122.2712
    private val berkeley = 37.8716 to -122.2727

    private fun state(hops: List<TraceHop>, success: Boolean = true) =
        TracePathState(result = result(hops, success = success))

    private fun device(start: Boolean, location: Pair<Double, Double>?) =
        hop(0.0, start = start, end = !start, lat = location?.first, lon = location?.second, name = "Device")

    private fun repeater(hash: Int, location: Pair<Double, Double>?, name: String? = "Tower", snr: Double = 5.0) =
        hop(snr, lat = location?.first, lon = location?.second, name = name, hash = Bytes.of(hash))

    // MARK: Saved path hash size

    @Test @OriginalCase("SavedPathHashSizeTests::loadSavedPath uses stored hashSize, not device hashSize()")
    fun `loadSavedPath uses stored hashSize, not device hashSize`() {
        val harness = TraceHarness()
        harness.holder.loadSavedPath(savedPath(Bytes.of(0xAA, 0xBB, 0xCC, 0xDD), hashSize = 2))
        assertEquals(listOf(Bytes.of(0xAA, 0xBB), Bytes.of(0xCC, 0xDD)), harness.state.outboundPath.map { it.hashBytes })
        assertFalse(harness.state.autoReturnPath)
    }

    @Test @OriginalCase("SavedPathHashSizeTests::fullPathString chunks by saved hash size, not device hash size()")
    fun `fullPathString chunks by saved hash size, not device hash size`() {
        val harness = TraceHarness()
        harness.holder.loadSavedPath(savedPath(Bytes.of(0xAA, 0xBB, 0xCC, 0xDD, 0xAA, 0xBB), hashSize = 2))
        val parts = harness.state.fullPathString.split(",")
        assertEquals(listOf("AABB", "CCDD", "AABB"), parts)
        assertTrue(parts.all { it.length == 4 })
    }

    @Test @OriginalCase("SavedPathHashSizeTests::loadSavedPath with hashSize 1 produces correct hops()")
    fun `loadSavedPath with hashSize 1 produces correct hops`() {
        val harness = TraceHarness()
        harness.holder.loadSavedPath(savedPath(Bytes.of(0xAA, 0xBB, 0xCC), hashSize = 1))
        assertEquals(listOf(Bytes.of(0xAA), Bytes.of(0xBB), Bytes.of(0xCC)), harness.state.outboundPath.map { it.hashBytes })
        assertFalse(harness.state.autoReturnPath)
    }

    @Test @OriginalCase("SavedPathLoadTests::loadSavedPath restores an asymmetric loop and turns auto-return off()")
    fun `loadSavedPath restores an asymmetric loop and turns auto-return off`() {
        val harness = TraceHarness()
        harness.holder.setAutoReturnPath(true)
        harness.holder.loadSavedPath(savedPath(Bytes.of(0x0A, 0x0B, 0x0C, 0x0A)))
        assertEquals(listOf(Bytes.of(0x0A), Bytes.of(0x0B), Bytes.of(0x0C), Bytes.of(0x0A)), harness.state.outboundPath.map { it.hashBytes })
        assertFalse(harness.state.autoReturnPath)
        assertEquals(Bytes.of(0x0A, 0x0B, 0x0C, 0x0A), harness.state.fullPathData)
    }

    @Test @OriginalCase("SavedPathLoadTests::loadSavedPath restores a symmetric path and turns auto-return on()")
    fun `loadSavedPath restores a symmetric path and turns auto-return on`() {
        val harness = TraceHarness()
        harness.holder.setAutoReturnPath(false)
        harness.holder.loadSavedPath(savedPath(Bytes.of(0x0A, 0x0B, 0x0A)))
        assertEquals(listOf(Bytes.of(0x0A), Bytes.of(0x0B)), harness.state.outboundPath.map { it.hashBytes })
        assertTrue(harness.state.autoReturnPath)
        assertEquals(Bytes.of(0x0A, 0x0B, 0x0A), harness.state.fullPathData)
    }

    // MARK: Failure results

    @Test @OriginalCase("FailureResultTests::timeout result contains attempted path()")
    fun `timeout result contains attempted path`() {
        val result = TraceResult.timeout(Bytes.of(0xAA, 0xBB, 0xAA), 1, EnglishTraceStrings.noResponse)
        assertFalse(result.success)
        assertEquals(Bytes.of(0xAA, 0xBB, 0xAA), result.tracedPathBytes)
        assertEquals("AA,BB,AA", result.tracedPathString)
        assertEquals("No response received", result.errorMessage)
    }

    @Test @OriginalCase("FailureResultTests::sendFailed result contains attempted path()")
    fun `sendFailed result contains attempted path`() {
        val result = TraceResult.sendFailed("Connection lost", Bytes.of(0xCC, 0xDD, 0xCC), 1)
        assertFalse(result.success)
        assertEquals("Connection lost", result.errorMessage)
        assertEquals(Bytes.of(0xCC, 0xDD, 0xCC), result.tracedPathBytes)
        assertEquals("CC,DD,CC", result.tracedPathString)
    }

    @Test @OriginalCase("FailureResultTests::empty path produces empty tracedPathString()")
    fun `empty path produces empty tracedPathString`() {
        val result = TraceResult.timeout(Bytes.EMPTY, 1, EnglishTraceStrings.noResponse)
        assertTrue(result.tracedPathBytes.isEmpty)
        assertEquals("", result.tracedPathString)
    }

    @Test @OriginalCase("FailureResultTests::tracedPathString chunks by 2-byte hash size()")
    fun `tracedPathString chunks by 2-byte hash size`() = assertEquals(
        "AABB,CCDD,AABB",
        TraceResult.timeout(Bytes.of(0xAA, 0xBB, 0xCC, 0xDD, 0xAA, 0xBB), 2, "").tracedPathString,
    )

    @Test @OriginalCase("FailureResultTests::tracedPathString chunks by 3-byte hash size()")
    fun `tracedPathString chunks by 3-byte hash size`() = assertEquals(
        "AABBCC,DDEEFF,AABBCC",
        TraceResult.timeout(Bytes.of(0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF, 0xAA, 0xBB, 0xCC), 3, "").tracedPathString,
    )

    // MARK: Total path distance

    @Test @OriginalCase("TotalPathDistanceTests::calculates full path distance when device has location()")
    fun `calculates full path distance when device has location`() {
        val distance = assertNotNull(
            state(listOf(device(true, sf), repeater(0x3F, oakland), repeater(0x4F, berkeley), device(false, sf))).totalPathDistance,
        )
        assertTrue(distance > 30_000 && distance < 50_000, "distance $distance")
    }

    @Test @OriginalCase("TotalPathDistanceTests::falls back to intermediate-only distance when device lacks location()")
    fun `falls back to intermediate-only distance when device lacks location`() {
        val distance = assertNotNull(
            state(listOf(device(true, null), repeater(0x3F, oakland), repeater(0x4F, berkeley), device(false, null))).totalPathDistance,
        )
        assertTrue(distance > 7_000 && distance < 9_000, "distance $distance")
    }

    @Test @OriginalCase("TotalPathDistanceTests::returns nil when hop missing location()")
    fun `returns nil when hop missing location`() =
        assertNull(state(listOf(device(true, sf), repeater(0x3F, null), device(false, sf))).totalPathDistance)

    @Test @OriginalCase("TotalPathDistanceTests::returns nil when hop has zero location()")
    fun `returns nil when hop has zero location`() =
        assertNull(state(listOf(device(true, sf), repeater(0x3F, 0.0 to 0.0), device(false, sf))).totalPathDistance)

    @Test @OriginalCase("TotalPathDistanceTests::returns nil for failed result()")
    fun `returns nil for failed result`() = assertNull(state(emptyList(), success = false).totalPathDistance)

    @Test @OriginalCase("TotalPathDistanceTests::calculates distance for single repeater when device has location()")
    fun `calculates distance for single repeater when device has location`() {
        assertNotNull(state(listOf(device(true, sf), repeater(0x3F, 37.8 to -122.3), device(false, sf))).totalPathDistance)
    }

    @Test @OriginalCase("TotalPathDistanceTests::returns nil with single repeater and no device location()")
    fun `returns nil with single repeater and no device location`() =
        assertNull(state(listOf(device(true, null), repeater(0x3F, 37.8 to -122.3), device(false, null))).totalPathDistance)

    @Test @OriginalCase("TotalPathDistanceTests::isDistanceUsingFallback is false when device has location()")
    fun `isDistanceUsingFallback is false when device has location`() = assertFalse(
        state(listOf(device(true, sf), repeater(0x3F, oakland), repeater(0x4F, berkeley), device(false, sf))).isDistanceUsingFallback,
    )

    @Test @OriginalCase("TotalPathDistanceTests::isDistanceUsingFallback is true when device lacks location()")
    fun `isDistanceUsingFallback is true when device lacks location`() = assertTrue(
        state(listOf(device(true, null), repeater(0x3F, oakland), repeater(0x4F, berkeley), device(false, null))).isDistanceUsingFallback,
    )

    @Test @OriginalCase("TotalPathDistanceTests::isDistanceUsingFallback is false when distance is nil()")
    fun `isDistanceUsingFallback is false when distance is nil`() {
        val subject = state(listOf(device(true, null), repeater(0x3F, null), device(false, null)))
        assertNull(subject.totalPathDistance)
        assertFalse(subject.isDistanceUsingFallback)
    }

    // MARK: Repeaters without location

    @Test @OriginalCase("RepeatersWithoutLocationTests::returns names of hops missing locations()")
    fun `returns names of hops missing locations`() {
        val missing = state(
            listOf(
                device(true, sf), repeater(0x3F, null, "Tower A"), repeater(0x4F, 37.8 to -122.3, "Tower B"),
                repeater(0x5F, 0.0 to 0.0, "Tower C"), device(false, sf),
            ),
        ).repeatersWithoutLocation
        assertEquals(listOf("Tower A", "Tower C"), missing)
    }

    @Test @OriginalCase("RepeatersWithoutLocationTests::uses hash display for unresolved names()")
    fun `uses hash display for unresolved names`() =
        assertEquals(listOf("3F"), state(listOf(device(true, sf), repeater(0x3F, null, null), device(false, sf))).repeatersWithoutLocation)

    @Test @OriginalCase("RepeatersWithoutLocationTests::excludes start and end nodes()")
    fun `excludes start and end nodes`() = assertEquals(
        emptyList(),
        state(listOf(device(true, null), repeater(0x3F, 37.8 to -122.3), device(false, null))).repeatersWithoutLocation,
    )

    @Test @OriginalCase("RepeatersWithoutLocationTests::returns empty when device location missing but no intermediate repeaters affected()")
    fun `returns empty when device location missing but no intermediate repeaters affected`() = assertEquals(
        emptyList(),
        state(listOf(device(true, null), repeater(0x3F, 37.8 to -122.3), device(false, null))).repeatersWithoutLocation,
    )
}
