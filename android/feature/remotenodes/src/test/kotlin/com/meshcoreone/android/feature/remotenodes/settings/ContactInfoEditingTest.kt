// PortedFrom: MC1Tests/Views/RemoteNodes/NodeContactInfoSectionTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.settings

import com.meshcoreone.android.feature.remotenodes.support.OriginalCase
import com.meshcoreone.android.feature.remotenodes.support.VirtualClock
import com.meshcoreone.android.feature.remotenodes.support.runSuspend
import com.meshcoreone.android.feature.remotenodes.support.session
import kotlin.test.assertEquals
import org.junit.Test

/**
 * The Swift suite hosts the SwiftUI section in a UIWindow and types through a real UITextView. Only the
 * state-holder half is portable here: the text field's edits arrive as [NodeSettingsStateHolder.setOwnerInfo]
 * and Apply sends the edited value. The UITextView input path and the focused-color check are deferred to
 * the Compose screen (see docs/android/evidence/WP-313/README.md).
 */
class ContactInfoEditingTest {
    private val existing = "KD7ABC"
    private val suffix = " extra"

    private fun loaded(recorder: CommandRecorder): NodeSettingsStateHolder =
        NodeSettingsStateHolder(VirtualClock(), TestFaults).apply {
            configure(session(name = "Test Repeater"), recorder::send, recorder::send)
            setNodeInfo("v1.17.1", "Test Repeater", existing)
            setExpanded(NodeSettingsSection.CONTACT_INFO, true)
        }

    @Test
    @OriginalCase("NodeContactInfoSectionTests::typing into contact info updates ownerInfo while focused()", "platform-adaptation")
    fun `typing into contact info updates ownerInfo`() {
        val holder = loaded(CommandRecorder())
        holder.setOwnerInfo(holder.state.value.ownerInfo + suffix)
        assertEquals(existing + suffix, holder.state.value.ownerInfo)
        assertEquals(true, holder.state.value.contactInfoSettingsModified)
    }

    @Test
    @OriginalCase("NodeContactInfoSectionTests::apply after typing sends set owner.info with the edited value()", "platform-adaptation")
    fun `apply after typing sends set owner info with the edited value`() = runSuspend {
        val recorder = CommandRecorder()
        val holder = loaded(recorder)
        holder.setOwnerInfo(existing + suffix)
        holder.applyContactInfoSettings()
        assertEquals(listOf("set owner.info $existing$suffix"), recorder.commands)
    }

    @Test
    @OriginalCase("NodeContactInfoSectionTests::backspace on loaded contact info updates ownerInfo()", "platform-adaptation")
    fun `backspace on loaded contact info updates ownerInfo`() {
        val holder = loaded(CommandRecorder())
        holder.setOwnerInfo(holder.state.value.ownerInfo?.dropLast(1))
        assertEquals(existing.dropLast(1), holder.state.value.ownerInfo)
    }
}
