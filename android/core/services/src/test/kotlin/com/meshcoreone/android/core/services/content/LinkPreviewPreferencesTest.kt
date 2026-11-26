// PortedFrom: MC1Tests/Models/LinkPreviewPreferencesTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Uses InMemoryLinkPreviewPreferencesSource in place of the Swift test's per-test UserDefaults
// suite (test.<UUID>) - the port's unit boundary is the policy, not the storage mechanism.
// Setters go through suspend LinkPreviewPreferences.setXxx calls (matching the real
// asynchronous DataStore producer contract), run under kotlinx-coroutines-test's runTest.
package com.meshcoreone.android.core.services.content

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkPreviewPreferencesTest {
    @Test
    fun `Has expected defaults, previews off, auto-resolve on`() {
        val prefs = LinkPreviewPreferences(InMemoryLinkPreviewPreferencesSource())
        assertFalse(prefs.previewsEnabled)
        assertTrue(prefs.autoResolveDM)
        assertTrue(prefs.autoResolveChannels)
    }

    @Test
    fun `shouldAutoResolve for DM respects settings`() = runTest {
        val prefs = LinkPreviewPreferences(InMemoryLinkPreviewPreferencesSource())

        prefs.setPreviewsEnabled(true)
        prefs.setAutoResolveDM(true)
        assertTrue(prefs.shouldAutoResolve(isChannelMessage = false))

        prefs.setAutoResolveDM(false)
        assertFalse(prefs.shouldAutoResolve(isChannelMessage = false))

        prefs.setPreviewsEnabled(false)
        prefs.setAutoResolveDM(true)
        assertFalse(prefs.shouldAutoResolve(isChannelMessage = false))
    }

    @Test
    fun `shouldAutoResolve for channel respects settings`() = runTest {
        val prefs = LinkPreviewPreferences(InMemoryLinkPreviewPreferencesSource())

        prefs.setPreviewsEnabled(true)
        prefs.setAutoResolveChannels(true)
        assertTrue(prefs.shouldAutoResolve(isChannelMessage = true))

        prefs.setAutoResolveChannels(false)
        assertFalse(prefs.shouldAutoResolve(isChannelMessage = true))
    }

    @Test
    fun `shouldShowPreview reflects global toggle`() = runTest {
        val prefs = LinkPreviewPreferences(InMemoryLinkPreviewPreferencesSource())

        prefs.setPreviewsEnabled(true)
        assertTrue(prefs.shouldShowPreview)

        prefs.setPreviewsEnabled(false)
        assertFalse(prefs.shouldShowPreview)
    }

    @Test
    fun `Master off overrides channel auto-resolve too`() = runTest {
        val prefs = LinkPreviewPreferences(InMemoryLinkPreviewPreferencesSource())

        prefs.setPreviewsEnabled(false)
        prefs.setAutoResolveChannels(true)
        assertFalse(prefs.shouldAutoResolve(isChannelMessage = true))
    }

    @Test
    fun `Underlying source mutations are reflected through the wrapper`() = runTest {
        val source = InMemoryLinkPreviewPreferencesSource()
        val prefs = LinkPreviewPreferences(source)

        source.update(LinkPreviewPreferencesSnapshot(previewsEnabled = true))
        assertEquals(true, prefs.previewsEnabled)
    }
}
