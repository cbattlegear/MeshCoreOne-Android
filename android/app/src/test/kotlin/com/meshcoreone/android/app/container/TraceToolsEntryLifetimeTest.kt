// AndroidOnly: WP-314 Mounted production Tools entry observes real manager withdrawal and reconnect.
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.feature.tools.ToolsEntry
import com.meshcoreone.android.feature.tools.navigation.ToolSelection
import com.meshcoreone.android.feature.tools.trace.RecentHopsStorage
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TraceToolsEntryLifetimeTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun realToolsEntryObservesQuietDisconnectAndSameRadioReconnect() = quietDisconnect(false)
    @Test fun realToolsEntryObservesQuietDisconnectAndDifferentRadioReconnect() = quietDisconnect(true)

    private fun quietDisconnect(crossRadio: Boolean) = runTest {
        val firstDevice = UUID.randomUUID()
        val h = ContainerHarness(this, store, identity = { target ->
            key(if ((target as ConnectionTarget.Bluetooth).deviceId == firstDevice || !crossRadio) 7 else 8)
        })
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        try {
            h.container.start()
            h.settle()
            h.manager.connect(h.target(firstDevice))
            h.settle()
            h.assertReady()
            val a = assertNotNull(h.sessions.current).token
            val saved = store.createSavedTracePath(a.radioId, "Mounted A route", Bytes.of(0xAA), 1, null)
            val recents = object : RecentHopsStorage {
                override fun stringList(key: String): List<String>? = null
                override fun setStringList(key: String, value: List<String>) = Unit
            }
            val actual = h.container.createToolsFeatureDependencies(
                recents, h.container.createToolsDiagnosticsDependencies(),
            )
            host.get().setContent {
                MaterialTheme {
                    ToolsEntry(
                        FeatureRoute(FeatureId.TOOLS), { error("No navigation requested") },
                        selection = ToolSelection.TRACE_PATH, dependencies = actual,
                    )
                }
            }
            compose.onNodeWithText("Saved").performClick()
            compose.onNodeWithText(saved.name).performClick()
            compose.onNodeWithText("Add Hop").performScrollTo().performClick()
            input().performTextReplacement("A1")
            val oldText = assertNotNull(input().fetchSemanticsNode().config[SemanticsActions.SetText].action)
            val oldDone = assertNotNull(compose.onNodeWithText("Done").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            val version = h.appState.servicesVersion.value
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            h.settle()
            assertNull(h.sessions.current)
            assertNull(actual.radioId())
            assertEquals(version, h.appState.servicesVersion.value, "The test does not manually publish a generation")
            compose.waitForIdle()
            val writesAfterDisconnect = h.links.radios.sumOf { it.sent.size }
            val run = compose.onNodeWithText("Run Trace").performScrollTo().assertIsNotEnabled().fetchSemanticsNode()
            compose.runOnIdle { assertTrue(assertNotNull(run.config[SemanticsActions.OnClick].action)()) }
            input().performScrollTo().performTextReplacement("CC")
            assertDraft("CC")
            compose.runOnIdle {
                assertTrue(oldText(AnnotatedString("Retired A")))
                assertTrue(oldDone())
            }
            assertDraft("CC")
            val offlineText = assertNotNull(input().fetchSemanticsNode().config[SemanticsActions.SetText].action)
            compose.onNodeWithText("Done").performScrollTo().performClick()
            input().assertDoesNotExist()
            compose.onNodeWithText("Add Hop").performScrollTo().performClick()
            input().performTextReplacement("Offline draft")
            assertDraft("Offline draft")
            assertEquals(writesAfterDisconnect, h.links.radios.sumOf { it.sent.size }, "Offline edits never write to the radio")
            h.manager.connect(h.target(if (crossRadio) UUID.randomUUID() else firstDevice))
            h.settle()
            h.assertReady()
            val b = assertNotNull(h.sessions.current).token
            assertNotEquals(a, b)
            assertEquals(!crossRadio, a.radioId == b.radioId)
            compose.waitForIdle()
            input().performScrollTo().performTextReplacement("Fresh B")
            compose.runOnIdle {
                assertTrue(oldText(AnnotatedString("Stale A on B")))
                assertTrue(offlineText(AnnotatedString("Stale offline on B")))
            }
            assertDraft("Fresh B")
            compose.onNodeWithText("Done").performScrollTo().performClick()
            input().assertDoesNotExist()
            compose.onNodeWithText("Clear Path").performScrollTo().performClick()
            compose.onNode(hasText("Clear Path") and hasClickAction()).performClick()
            compose.onNodeWithText("Add Hop").performScrollTo().performClick()
            input().performTextReplacement("Fresh B after clear")
            assertDraft("Fresh B after clear")
        } finally {
            host.windowFocusChanged(false)
            host.pause().stop().destroy()
            h.container.close()
        }
    }

    private fun input() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))

    private fun assertDraft(value: String) {
        input().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
    }
}
