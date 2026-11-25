// PortedFrom: MC1Tests/ViewModels/TracePathViewModelTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/** Virtual-time ports of the source's sleep-based auto-clear cases. */
class TracePathErrorHandlingTest {
    private val harness = TraceHarness()
    private val holder = harness.holder

    @Test @OriginalCase("ErrorHandlingTests::setError sets errorMessage()")
    fun `setError sets errorMessage`() {
        assertNull(harness.state.errorMessage)
        holder.setError("Test error")
        assertEquals("Test error", harness.state.errorMessage)
        assertEquals(1, harness.state.errorHapticTrigger)
    }

    @Test @OriginalCase("ErrorHandlingTests::clearError clears errorMessage()")
    fun `clearError clears errorMessage`() {
        holder.setError("Test error")
        assertNotNull(harness.state.errorMessage)
        holder.clearError()
        assertNull(harness.state.errorMessage)
    }

    @Test @OriginalCase("ErrorHandlingTests::setError replaces previous error()")
    fun `setError replaces previous error`() {
        holder.setError("First error")
        holder.setError("Second error")
        assertEquals("Second error", harness.state.errorMessage)
    }

    @Test @OriginalCase("ErrorHandlingTests::addRepeater clears error()")
    fun `addRepeater clears error`() {
        holder.setError("Test error")
        holder.addNode(contact(0xAB, name = "Test Repeater"))
        assertNull(harness.state.errorMessage)
    }

    @Test @OriginalCase("ErrorHandlingTests::removeRepeater clears error()")
    fun `removeRepeater clears error`() {
        holder.addNode(contact(0xAB, name = "Test Repeater"))
        holder.setError("Test error")
        holder.removeRepeater(0)
        assertNull(harness.state.errorMessage)
    }

    @Test @OriginalCase("ErrorHandlingTests::moveRepeater clears error()")
    fun `moveRepeater clears error`() {
        holder.addNode(contact(0xAB, name = "Test Repeater"))
        holder.addNode(contact(0xAB, name = "Test Repeater"))
        holder.setError("Test error")
        holder.moveRepeater(setOf(0), 2)
        assertNull(harness.state.errorMessage)
    }

    @Test @OriginalCase("ErrorHandlingTests::error auto-clears after delay()", "virtual-time-equivalent")
    fun `error auto-clears after delay`() {
        holder.errorAutoClearDelay = 100.milliseconds
        holder.setError("Test error")
        harness.time.advanceBy(99.milliseconds)
        assertNotNull(harness.state.errorMessage)
        harness.time.advanceBy(1.milliseconds)
        assertNull(harness.state.errorMessage)
    }

    @Test @OriginalCase("ErrorHandlingTests::clearError cancels pending auto-clear()", "virtual-time-equivalent")
    fun `clearError cancels pending auto-clear`() {
        holder.errorAutoClearDelay = 100.milliseconds
        holder.setError("Test error")
        harness.main.runCurrent()
        holder.clearError()
        harness.time.advanceBy(150.milliseconds)
        assertNull(harness.state.errorMessage)
        assertEquals(0, harness.time.pendingSleepers)
    }

    @Test @OriginalCase("ErrorHandlingTests::new setError cancels previous auto-clear timer()", "virtual-time-equivalent")
    fun `new setError cancels previous auto-clear timer`() {
        holder.errorAutoClearDelay = 200.milliseconds
        holder.setError("First error")
        harness.time.advanceBy(100.milliseconds)
        holder.setError("Second error")
        harness.time.advanceBy(150.milliseconds)
        assertEquals("Second error", harness.state.errorMessage)
        harness.time.advanceBy(100.milliseconds)
        assertNull(harness.state.errorMessage)
    }
}
