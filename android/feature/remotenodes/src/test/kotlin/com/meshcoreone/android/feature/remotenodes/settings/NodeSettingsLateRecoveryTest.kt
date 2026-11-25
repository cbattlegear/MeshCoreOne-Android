// PortedFrom: MC1Tests/ViewModels/NodeSettingsLateRecoveryTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.settings

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.feature.remotenodes.support.OriginalCase
import com.meshcoreone.android.feature.remotenodes.support.VirtualClock
import com.meshcoreone.android.feature.remotenodes.support.bytes
import com.meshcoreone.android.feature.remotenodes.support.runSuspend
import com.meshcoreone.android.feature.remotenodes.support.session
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import org.junit.Test

/** Late CLI replies: a timed-out structured query is remembered and its belated answer adopted. */
class NodeSettingsLateRecoveryTest {
    private fun holder(responses: Map<String, Result<String>>): NodeSettingsStateHolder {
        val send: suspend (EntityKey, String, Duration) -> String = { _, command, _ ->
            val result = responses[command] ?: throw FakeTimeout()
            result.getOrThrow()
        }
        return NodeSettingsStateHolder(VirtualClock(), TestFaults).apply {
            configure(session(bytes(32, 0xCC), "TestNode", RemoteNodeRole.REPEATER), send, send)
        }
    }

    @Test @OriginalCase("NodeSettingsLateRecoveryTests::belated get radio reply is recovered after its command timed out()")
    fun `belated get radio reply is recovered after its command timed out`() = runSuspend {
        val holder = holder(emptyMap())
        holder.fetchRadioSettings()
        with(holder.state.value) {
            assertNull(frequency)
            assertNull(bandwidth)
            assertNull(spreadingFactor)
            assertNull(codingRate)
            assertTrue(radioError)
            assertFalse(radioLoaded)
        }
        holder.handleCommonLateResponse("> 915.000,250.0,10,5")
        with(holder.state.value) {
            assertEquals(915.0, frequency)
            assertEquals(250.0, bandwidth)
            assertEquals(10L, spreadingFactor)
            assertEquals(5L, codingRate)
            assertFalse(radioError)
            assertTrue(radioLoaded)
        }
    }

    @Test @OriginalCase("NodeSettingsLateRecoveryTests::mesh duplicate of an answered reply is not recovered for another query()")
    fun `mesh duplicate of an answered reply is not recovered for another query`() = runSuspend {
        val holder = holder(mapOf("get name" to Result.success("Alpha Repeater"), "get lat" to Result.success("38.5")))
        holder.fetchIdentity()
        assertEquals(38.5, holder.state.value.latitude)
        assertNull(holder.state.value.longitude)

        // A flood-routed duplicate of the latitude reply must not become the longitude.
        holder.handleCommonLateResponse("38.5")
        assertNull(holder.state.value.longitude)

        holder.handleCommonLateResponse("-122.4")
        assertEquals(-122.4, holder.state.value.longitude)
        assertFalse(holder.state.value.identityError)
    }

    @Test @OriginalCase("NodeSettingsLateRecoveryTests::a bare double is not recovered while both coordinates are unanswered()")
    fun `a bare double is not recovered while both coordinates are unanswered`() = runSuspend {
        val holder = holder(mapOf("get name" to Result.success("Alpha Repeater")))
        holder.fetchIdentity()
        assertTrue(holder.state.value.identityError)
        holder.handleCommonLateResponse("38.5")
        assertNull(holder.state.value.latitude)
        assertNull(holder.state.value.longitude)
        assertTrue(holder.state.value.identityError)
    }

    @Test
    fun `late clock reply applies device time and keeps the firmware gap flagged`() = runSuspend {
        val holder = holder(emptyMap())
        holder.fetchDeviceInfo()
        assertTrue(holder.state.value.deviceInfoError)
        holder.handleCommonLateResponse("06:40 - 18/4/2025 UTC")
        assertEquals("06:40 - 18/4/2025 UTC", holder.state.value.deviceTimeUTC)
        // `ver` is not a structured query, so firmware stays unknown and the section stays in error.
        assertTrue(holder.state.value.deviceInfoError)
    }

    @Test
    fun `a reply after cleanup is ignored`() = runSuspend {
        val holder = holder(emptyMap())
        holder.fetchRadioSettings()
        holder.cleanup()
        holder.handleCommonLateResponse("915.000,250.0,10,5")
        assertNull(holder.state.value.frequency)
    }
}
