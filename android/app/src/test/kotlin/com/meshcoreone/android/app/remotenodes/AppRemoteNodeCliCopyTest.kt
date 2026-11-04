// PortedFrom: MC1/Views/Tools/CLI/CLITerminalView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/NodeCLIViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.remotenodes

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.app.navigation.AppRemoteNodeCli
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "w1080dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class AppRemoteNodeCliCopyTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun copyUsesTheWholeResponseBlockButNotNeighboringCommandsOrResponses() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host.get().actionBar?.hide()
            val fixture = RemoteNodesFixture()
            val requests = mutableListOf<String>()
            val clipboard = host.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val copy = host.get().getString(AppToolsStrings.toolsRxLogCopy)
            host.get().setContent {
                MeshCoreTheme(theme = ThemeRegistry.default, motionScale = 0f) {
                    AppRemoteNodeCli(fixture.session, { _, command, _ ->
                        requests += command
                        when (command) {
                            "first" -> "> alpha\n\n> beta"
                            "second" -> "> separate"
                            else -> error("Unexpected command $command")
                        }
                    }, true)
                }
            }
            fun send(command: String) {
                val input = compose.onNodeWithTag("remote-cli-input")
                input.performClick().performTextReplacement(command)
                input.performKeyInput { pressKey(Key.Enter) }
                assertFalse(requests.contains(command))
                compose.onNodeWithTag("remote-cli-confirm").performClick()
                compose.waitUntil { requests.contains(command) }
                compose.waitForIdle()
            }
            fun copyFrom(text: String) {
                val line = hasText(text, substring = true)
                compose.onNode(hasText(copy) and hasAnySibling(line))
                    .performScrollTo().assertHasClickAction().performClick()
            }
            fun copied() = clipboard.primaryClip!!.getItemAt(0).text.toString()

            send("first")
            send("second")
            copyFrom("> beta")
            compose.runOnIdle { assertEquals("alpha\n\nbeta", copied()) }
            copyFrom("> separate")
            compose.runOnIdle { assertEquals("separate", copied()) }
            copyFrom("> first")
            compose.runOnIdle { assertEquals("first", copied()) }
            compose.runOnIdle { assertEquals(listOf("first", "second"), requests) }
        } finally {
            host.pause().stop().destroy()
        }
    }
}
