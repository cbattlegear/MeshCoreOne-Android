// PortedFrom: MC1Tests/ViewModels/TracePathViewModelTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceHashModeTest {
    private fun testContact() = contact(0xAB, name = "Test Repeater")

    private fun configured(supportsOverride: Boolean): TraceHarness = TraceHarness(configureDependencies = true).also {
        it.deps.device = device(firmwareVersion = 8u, firmwareVersionString = if (supportsOverride) "v1.11.0" else "v1.10.0")
    }

    @Test @OriginalCase("TraceHashSizeOverrideTests::setTraceHashMode rebuilds hop hash bytes from the public key prefix()")
    fun `setTraceHashMode rebuilds hop hash bytes from the public key prefix`() {
        val harness = TraceHarness()
        val holder = harness.holder
        holder.addNode(testContact())
        assertEquals(0u.toUByte(), holder.effectiveTraceMode)
        assertEquals(1, holder.hashSize)
        assertEquals(Bytes.of(0xAB), harness.state.outboundPath[0].hashBytes)
        holder.setTraceHashMode(1u)
        assertEquals(1u.toUByte(), holder.effectiveTraceMode)
        assertEquals(2, holder.hashSize)
        assertEquals(Bytes.of(0xAB, 0x00), harness.state.outboundPath[0].hashBytes)
        holder.setTraceHashMode(2u)
        assertEquals(4, holder.hashSize)
        assertEquals(Bytes.of(0xAB, 0x00, 0x00, 0x00), harness.state.outboundPath[0].hashBytes)
        holder.setTraceHashMode(0u)
        assertEquals(1, holder.hashSize)
        assertEquals(Bytes.of(0xAB), harness.state.outboundPath[0].hashBytes)
    }

    @Test @OriginalCase("TraceHashSizeOverrideTests::fullPathData width tracks the active hash mode()")
    fun `fullPathData width tracks the active hash mode`() {
        val harness = TraceHarness()
        harness.holder.addNode(testContact())
        harness.holder.addNode(testContact())
        assertTrue(harness.state.autoReturnPath)
        assertEquals(3, harness.state.fullPathData.size)
        harness.holder.setTraceHashMode(1u)
        assertEquals(6, harness.state.fullPathData.size)
        harness.holder.setTraceHashMode(2u)
        assertEquals(12, harness.state.fullPathData.size)
    }

    @Test @OriginalCase("TraceHashSizeOverrideTests::setTraceHashMode clears a stale result and saved-path reference()")
    fun `setTraceHashMode clears a stale result and saved-path reference`() {
        val harness = TraceHarness()
        harness.holder.addNode(testContact())
        harness.holder.editStateForTesting { it.copy(activeSavedPath = savedPath(Bytes.of(0x01, 0x02, 0x01)), result = result(emptyList())) }
        harness.holder.setTraceHashMode(2u)
        assertNull(harness.state.activeSavedPath)
        assertNull(harness.state.result)
    }

    @Test @OriginalCase("TraceHashSizeOverrideTests::widening zero-pads key-less hops so the wire stays a uniform width()")
    fun `widening zero-pads key-less hops so the wire stays a uniform width`() {
        val harness = TraceHarness()
        harness.holder.loadSavedPath(savedPath(Bytes.of(0x01, 0x02, 0x01)))
        assertTrue(harness.state.outboundPath.isNotEmpty())
        assertTrue(harness.state.outboundPath.all { it.publicKey == null })
        harness.holder.setTraceHashMode(2u)
        assertEquals(4, harness.holder.hashSize)
        assertTrue(harness.state.outboundPath.all { it.hashBytes.size == 4 })
        assertEquals(0, harness.state.fullPathData.size % 4)
    }

    @Test @OriginalCase("TraceHashSizeOverrideTests::clearPath resets the per-trace hash mode override()")
    fun `clearPath resets the per-trace hash mode override`() {
        val harness = TraceHarness()
        harness.holder.addNode(testContact())
        harness.holder.setTraceHashMode(2u)
        assertEquals(2u.toUByte(), harness.holder.effectiveTraceMode)
        harness.holder.clearPath()
        assertEquals(0u.toUByte(), harness.holder.effectiveTraceMode)
        assertTrue(harness.state.outboundPath.isEmpty())
    }

    @Test @OriginalCase("TraceHashSizeOverrideTests::loadSavedPath sets no override when the radio can't honor it()")
    fun `loadSavedPath sets no override when the radio cannot honor it`() {
        val harness = TraceHarness()
        harness.holder.loadSavedPath(savedPath(Bytes.of(0x01, 0x02, 0x01)))
        assertEquals(0u.toUByte(), harness.holder.effectiveTraceMode)
    }

    @Test @OriginalCase("InferredTraceHashModeTests::Uniform 1-byte codes infer mode 0()")
    fun `Uniform 1-byte codes infer mode 0`() = assertEquals(0u.toUByte(), TraceHashModes.inferredTraceHashMode("1A,2B"))

    @Test @OriginalCase("InferredTraceHashModeTests::Uniform 2-byte codes infer mode 1()")
    fun `Uniform 2-byte codes infer mode 1`() = assertEquals(1u.toUByte(), TraceHashModes.inferredTraceHashMode("1A2B,3C4D"))

    @Test @OriginalCase("InferredTraceHashModeTests::Uniform 4-byte codes infer mode 2()")
    fun `Uniform 4-byte codes infer mode 2`() = assertEquals(2u.toUByte(), TraceHashModes.inferredTraceHashMode("AABBCCDD,11223344"))

    @Test @OriginalCase("InferredTraceHashModeTests::Whitespace around codes is tolerated()")
    fun `Whitespace around codes is tolerated`() = assertEquals(0u.toUByte(), TraceHashModes.inferredTraceHashMode(" 1A , 2B "))

    @Test @OriginalCase("InferredTraceHashModeTests::Mixed widths infer nothing()")
    fun `Mixed widths infer nothing`() = assertNull(TraceHashModes.inferredTraceHashMode("1A,2B3C"))

    @Test @OriginalCase("InferredTraceHashModeTests::Odd-length code infers nothing()")
    fun `Odd-length code infers nothing`() = assertNull(TraceHashModes.inferredTraceHashMode("1A,2"))

    @Test @OriginalCase("InferredTraceHashModeTests::Non-hex code infers nothing()")
    fun `Non-hex code infers nothing`() = assertNull(TraceHashModes.inferredTraceHashMode("1A,ZZ"))

    @Test @OriginalCase("InferredTraceHashModeTests::Uniform but non-power-of-2 width (3 bytes) infers nothing()")
    fun `Uniform but non-power-of-2 width (3 bytes) infers nothing`() = assertNull(TraceHashModes.inferredTraceHashMode("1A2B3C,4D5E6F"))

    @Test @OriginalCase("InferredTraceHashModeTests::Empty and separator-only input infer nothing()")
    fun `Empty and separator-only input infer nothing`() {
        assertNull(TraceHashModes.inferredTraceHashMode(""))
        assertNull(TraceHashModes.inferredTraceHashMode(","))
    }

    @Test @OriginalCase("AdoptHashSizeFromPasteTests::Pasting narrower codes switches the active hash size down()")
    fun `Pasting narrower codes switches the active hash size down`() {
        val harness = configured(supportsOverride = true)
        harness.holder.addNode(testContact())
        harness.holder.setTraceHashMode(1u)
        assertEquals(2, harness.holder.hashSize)
        harness.holder.adoptHashSize("1A,2B")
        assertEquals(0u.toUByte(), harness.holder.effectiveTraceMode)
        assertEquals(1, harness.holder.hashSize)
    }

    @Test @OriginalCase("AdoptHashSizeFromPasteTests::Pasting the active width leaves a stale saved-path reference intact()")
    fun `Pasting the active width leaves a stale saved-path reference intact`() {
        val harness = configured(supportsOverride = true)
        harness.holder.addNode(testContact())
        harness.holder.setTraceHashMode(1u)
        harness.holder.editStateForTesting { it.copy(activeSavedPath = savedPath(Bytes.of(0x01, 0x02, 0x01))) }
        harness.holder.adoptHashSize("1A2B,3C4D")
        assertEquals(1u.toUByte(), harness.holder.effectiveTraceMode)
        assertNotNull(harness.state.activeSavedPath)
    }

    @Test @OriginalCase("AdoptHashSizeFromPasteTests::Mixed-width paste leaves the active hash size unchanged()")
    fun `Mixed-width paste leaves the active hash size unchanged`() {
        val harness = configured(supportsOverride = true)
        harness.holder.setTraceHashMode(1u)
        harness.holder.adoptHashSize("1A,2B3C")
        assertEquals(1u.toUByte(), harness.holder.effectiveTraceMode)
    }

    @Test @OriginalCase("AdoptHashSizeFromPasteTests::Unsupported firmware ignores the pasted width()")
    fun `Unsupported firmware ignores the pasted width`() {
        val harness = configured(supportsOverride = false)
        harness.holder.setTraceHashMode(1u)
        harness.holder.adoptHashSize("1A,2B")
        assertEquals(1u.toUByte(), harness.holder.effectiveTraceMode)
    }

    @Test @OriginalCase("TraceHashSizeCapabilityGateTests::VER_CODE 8 disambiguates v1.10 (off) from v1.11/v1.12 (on)()")
    fun `VER_CODE 8 disambiguates v1_10 (off) from v1_11 and v1_12 (on)`() {
        assertFalse(device(8u, "v1.10.0").supportsTraceHashSizeOverride)
        assertTrue(device(8u, "v1.11.0").supportsTraceHashSizeOverride)
        assertTrue(device(8u, "v1.12.0").supportsTraceHashSizeOverride)
    }

    @Test @OriginalCase("TraceHashSizeCapabilityGateTests::VER_CODE >= 9 enables the override even without a usable version string()")
    fun `VER_CODE at least 9 enables the override even without a usable version string`() =
        assertTrue(device(9u, "").supportsTraceHashSizeOverride)

    @Test @OriginalCase("TraceHashSizeCapabilityGateTests::older firmware does not support the override()")
    fun `older firmware does not support the override`() = assertFalse(device(7u, "v1.9.0").supportsTraceHashSizeOverride)
}
