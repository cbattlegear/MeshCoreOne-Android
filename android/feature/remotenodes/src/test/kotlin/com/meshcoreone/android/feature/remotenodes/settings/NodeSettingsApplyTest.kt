// PortedFrom: MC1Tests/ViewModels/NodeSettingsViewModelValidationTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.settings

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.feature.remotenodes.cli.NodeSettingsResponseParser
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.support.OriginalCase
import com.meshcoreone.android.feature.remotenodes.support.VirtualClock
import com.meshcoreone.android.feature.remotenodes.support.runSuspend
import com.meshcoreone.android.feature.remotenodes.support.session
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Swift suites NodeSettingsIdentityApplyGuardTests, NodeSettingsRadioApplyTests, NodeSettingsClockSyncTests, NodeSettingsContactInfoTests. */
class NodeSettingsApplyTest {
    private val clock = VirtualClock()

    private fun configured(recorder: CommandRecorder): NodeSettingsStateHolder =
        NodeSettingsStateHolder(clock, TestFaults).apply {
            configure(session(name = "Test Node"), recorder::send, recorder::send)
        }

    // MARK: identity apply guard

    @Test @OriginalCase("NodeSettingsIdentityApplyGuardTests::out of range latitude blocks set lat()")
    fun `out of range latitude blocks set lat`() = runSuspend {
        val recorder = CommandRecorder()
        val holder = configured(recorder)
        holder.setLatitude(999.0)
        holder.applyIdentitySettings()
        assertTrue(recorder.commands.isEmpty(), "No command may be sent when a field is out of range")
        assertNotNull(holder.state.value.latitudeError, "The out-of-range field must be flagged inline")
        assertFalse(holder.state.value.identityApplySuccess)
    }

    @Test @OriginalCase("NodeSettingsIdentityApplyGuardTests::out of range longitude blocks set lon()")
    fun `out of range longitude blocks set lon`() = runSuspend {
        val recorder = CommandRecorder()
        val holder = configured(recorder)
        holder.setLongitude(-400.0)
        holder.applyIdentitySettings()
        assertTrue(recorder.commands.isEmpty())
        assertNotNull(holder.state.value.longitudeError)
    }

    @Test @OriginalCase("NodeSettingsIdentityApplyGuardTests::over long name blocks set name()")
    fun `over long name blocks set name`() = runSuspend {
        val recorder = CommandRecorder()
        val holder = configured(recorder)
        holder.setName("a".repeat(ProtocolLimits.MAX_USABLE_NAME_BYTES + 1))
        holder.applyIdentitySettings()
        assertTrue(recorder.commands.isEmpty())
        assertNotNull(holder.state.value.nameError)
    }

    @Test @OriginalCase("NodeSettingsIdentityApplyGuardTests::valid coordinates are sent over the transport()")
    fun `valid coordinates are sent over the transport`() = runSuspend {
        val recorder = CommandRecorder()
        val holder = configured(recorder)
        holder.setLatitude(37.7749)
        holder.setLongitude(-122.4194)
        holder.applyIdentitySettings()
        assertTrue(recorder.commands.any { it.startsWith("set lat 37.7749") })
        assertTrue(recorder.commands.any { it.startsWith("set lon -122.4194") })
        assertNull(holder.state.value.latitudeError)
        assertNull(holder.state.value.longitudeError)
        // Native: exact Swift Double interpolation and the success flash lifecycle.
        assertEquals(listOf("set lat 37.7749", "set lon -122.4194"), recorder.commands)
        assertEquals(listOf(NodeSettingsStateHolder.SUCCESS_FLASH), clock.sleeps)
        assertFalse(holder.state.value.identityApplySuccess)
        assertFalse(holder.state.value.isApplying)
        assertEquals(37.7749, holder.state.value.originalLatitude)
    }

    @Test @OriginalCase("NodeSettingsIdentityApplyGuardTests::reapply clears stale errors once corrected()")
    fun `reapply clears stale errors once corrected`() = runSuspend {
        val recorder = CommandRecorder()
        val holder = configured(recorder)
        holder.setLatitude(999.0)
        holder.applyIdentitySettings()
        assertNotNull(holder.state.value.latitudeError)
        holder.setLatitude(45.0)
        holder.applyIdentitySettings()
        assertNull(holder.state.value.latitudeError, "Correcting the value must clear the stale inline error")
        assertTrue(recorder.commands.any { it.startsWith("set lat 45") })
        assertEquals(listOf("set lat 45.0"), recorder.commands)
    }

    // MARK: radio

    @Test
    fun `invalid native radio drafts never reach transport`() = runSuspend {
        val invalid = listOf(
            listOf(Double.NaN, 250.0, 10L, 5L),
            listOf(Double.POSITIVE_INFINITY, 250.0, 10L, 5L),
            listOf(149.999, 250.0, 10L, 5L),
            listOf(2500.001, 250.0, 10L, 5L),
            listOf(915.0, Double.NaN, 10L, 5L),
            listOf(915.0, 500.001, 10L, 5L),
            listOf(915.0, 250.0, 4L, 5L),
            listOf(915.0, 250.0, 13L, 5L),
            listOf(915.0, 250.0, 10L, Long.MIN_VALUE),
            listOf(915.0, 250.0, 10L, 9L),
        )
        invalid.forEach { fields ->
            val recorder = CommandRecorder()
            val holder = configured(recorder)
            holder.setRadio(fields[0].toDouble(), fields[1].toDouble(), fields[2].toLong(), fields[3].toLong())
            holder.applyRadioSettings()
            assertTrue(recorder.commands.isEmpty())
            assertEquals(RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRadioApplyFailed), holder.state.value.errorMessage)
            assertFalse(holder.state.value.isApplying)
        }
        for (frequency in listOf(150.0, 2500.0)) {
            val recorder = CommandRecorder()
            val holder = configured(recorder)
            holder.setRadio(frequency, 7.8, 5L, 8L)
            holder.applyRadioSettings()
            assertEquals(1, recorder.commands.size)
            assertNull(holder.state.value.errorMessage)
        }
    }

    @Test @OriginalCase("NodeSettingsRadioApplyTests::apply radio settings sends only set radio and clears modified flag()")
    fun `apply radio settings sends only set radio and clears modified flag`() = runSuspend {
        val recorder = CommandRecorder()
        val holder = configured(recorder)
        holder.setRadio(915.0, 250.0, 10, 5, modified = true)
        holder.applyRadioSettings()
        assertEquals(listOf("set radio 915.0,250.0,10,5"), recorder.commands)
        assertFalse(recorder.commands.any { it.contains("set tx") })
        assertFalse(holder.state.value.radioSettingsModified)
        assertEquals(RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRadioAppliedSuccess), holder.state.value.successMessage)
        assertTrue(holder.state.value.showSuccessAlert)
    }

    @Test @OriginalCase("NodeSettingsRadioApplyTests::fetch radio settings sends only get radio()")
    fun `fetch radio settings sends only get radio`() = runSuspend {
        val recorder = CommandRecorder()
        val holder = configured(recorder)
        holder.fetchRadioSettings()
        assertEquals(listOf("get radio"), recorder.commands)
    }

    // MARK: clock sync

    @Test @OriginalCase("NodeSettingsClockSyncTests::syncTime sends the host epoch as time not bare clock sync()", "platform-adaptation")
    fun `syncTime sends the host epoch as time not bare clock sync`() = runSuspend {
        val recorder = CommandRecorder("OK - clock set: 15:35 - 14/8/2026 UTC")
        val holder = configured(recorder)
        // The injected clock replaces Swift's real before/after bracket with an exact expectation.
        val before = clock.now.epochSecond
        holder.syncTime()
        val after = clock.now.epochSecond
        assertEquals(1, recorder.commands.size)
        val command = recorder.commands.single()
        assertTrue(command.startsWith("time "))
        val epoch = command.removePrefix("time ").toUInt().toLong()
        assertTrue(epoch in before..after)
        assertEquals("time ${clock.now.epochSecond}", command)
    }

    @Test @OriginalCase("NodeSettingsClockSyncTests::fetchDeviceInfo records drift from clock against a fixed now()")
    fun `fetchDeviceInfo records drift from clock against a fixed now`() = runSuspend {
        val recorder = CommandRecorder("06:40 - 18/4/2025 UTC")
        val holder = configured(recorder)
        val node = assertNotNull(NodeSettingsResponseParser.utcDate(recorder.reply))
        clock.set(node.plusSeconds(3600))
        holder.fetchDeviceInfo()
        assertEquals(-3600.0, holder.state.value.clockDrift)
        assertEquals(listOf("ver", "clock"), recorder.commands)
    }

    @Test @OriginalCase("NodeSettingsClockSyncTests::syncTime on OK with clock text updates device time and drift()")
    fun `syncTime on OK with clock text updates device time and drift`() = runSuspend {
        val recorder = CommandRecorder("OK - clock set: 15:35 - 14/8/2026 UTC")
        val holder = configured(recorder)
        val node = assertNotNull(NodeSettingsResponseParser.utcDate(recorder.reply))
        clock.set(node)
        holder.syncTime()
        assertEquals(1, recorder.commands.size)
        assertEquals(0.0, holder.state.value.clockDrift)
        assertNotNull(holder.state.value.deviceTimeUTC)
        assertTrue(holder.state.value.deviceInfoLoaded)
    }

    @Test @OriginalCase("NodeSettingsClockSyncTests::syncTime on bare OK hides the warning without a second command()")
    fun `syncTime on bare OK hides the warning without a second command`() = runSuspend {
        val recorder = CommandRecorder("OK - clock set")
        val holder = configured(recorder)
        holder.setClockDrift(-10000.0)
        holder.syncTime()
        assertEquals(1, recorder.commands.size)
        assertNull(holder.state.value.clockDrift)
    }

    // MARK: contact info

    @Test @OriginalCase("NodeSettingsContactInfoTests::apply converts display newlines to the pipe wire form()")
    fun `apply converts display newlines to the pipe wire form`() = runSuspend {
        val recorder = CommandRecorder()
        val holder = configured(recorder)
        holder.setNodeInfo("v1.17.1", "Repeater", "KD7ABC")
        holder.setOwnerInfo("KD7ABC\nch 31")
        holder.applyContactInfoSettings()
        assertEquals(listOf("set owner.info KD7ABC|ch 31"), recorder.commands)
        assertEquals("KD7ABC\nch 31", holder.state.value.originalOwnerInfo)
    }

    @Test @OriginalCase("NodeSettingsContactInfoTests::apply failure sets errorMessage()")
    fun `apply failure sets errorMessage`() = runSuspend {
        val recorder = CommandRecorder("ERR: not allowed")
        val holder = configured(recorder)
        holder.setNodeInfo("v1.17.1", "Repeater", "old")
        holder.setOwnerInfo("new")
        holder.applyContactInfoSettings()
        assertNotNull(holder.state.value.errorMessage)
        assertFalse(holder.state.value.contactInfoApplySuccess)
        assertEquals("old", holder.state.value.originalOwnerInfo)
        assertFalse(holder.state.value.isApplying)
    }
}
