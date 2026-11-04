// PortedFrom: MC1/Views/Tools/CLI/CLITerminalView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/NodeCLIViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.remotenodes

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.app.navigation.AppRemoteNodeCli
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.feature.tools.diagnostics.ResourcesDiagnosticsText
import com.meshcoreone.android.feature.tools.diagnostics.cli.NodeCliStateHolder
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliOutputType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
                compose.onNodeWithTag("remote-cli-copy").performClick()
                compose.onNode(hasText(text, substring = true) and hasAnyAncestor(isDialog()))
                    .performScrollTo().assertHasClickAction().performClick()
            }
            fun copied() = clipboard.primaryClip!!.getItemAt(0).text.toString()

            send("first")
            send("second")
            copyFrom("> beta")
            compose.runOnIdle { assertEquals("alpha\n\n> beta", copied()) }
            copyFrom("> separate")
            compose.runOnIdle { assertEquals("separate", copied()) }
            copyFrom("> first")
            compose.runOnIdle { assertEquals("first", copied()) }
            compose.runOnIdle { assertEquals(listOf("first", "second"), requests) }
        } finally {
            host.pause().stop().destroy()
        }
    }

    @Test fun multiRowSelectionCopiesOnlyTerminalTextAtNarrowWidthAndLargeFont() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            host.get().actionBar?.hide()
            val fixture = RemoteNodesFixture()
            val clipboard = host.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val holder = NodeCliStateHolder(scope, ResourcesDiagnosticsText(host.get().resources))
            lateinit var inputMode: InputModeManager
            host.get().setContent {
                inputMode = LocalInputModeManager.current
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, 2f),
                ) {
                    MeshCoreTheme(theme = ThemeRegistry.default, motionScale = 0f) {
                        Column(Modifier.width(320.dp)) {
                            Text("alpha", Modifier.testTag("terminal-text-baseline"), fontFamily = FontFamily.Monospace)
                            AppRemoteNodeCli(fixture.session, { _, _, _ -> "unused" }, true, holder)
                        }
                    }
                }
            }
            compose.runOnIdle {
                holder.appendOutput("probe", CliOutputType.COMMAND)
                holder.appendOutput("alpha", CliOutputType.RESPONSE)
                holder.appendOutput("beta", CliOutputType.RESPONSE)
            }
            val alpha = compose.onNode(hasText("alpha") and !hasTestTag("terminal-text-baseline")).performScrollTo()
            val beta = compose.onNodeWithText("beta")
            compose.waitForIdle()
            val first = alpha.fetchSemanticsNode().boundsInRoot
            val last = beta.fetchSemanticsNode().boundsInRoot
            val container = compose.onNodeWithTag("remote-cli-output")
            val origin = container.fetchSemanticsNode().boundsInRoot.topLeft
            container.performMouseInput {
                moveTo(Offset(first.left + 1f, first.center.y) - origin)
                press()
                advanceEventTime(500)
                moveTo(Offset(last.right - 1f, last.center.y) - origin)
                release()
            }
            compose.waitForIdle()
            container.performKeyInput {
                keyDown(Key.CtrlLeft)
                pressKey(Key.C)
                keyUp(Key.CtrlLeft)
            }
            val baselineHeight = compose.onNodeWithTag("terminal-text-baseline").fetchSemanticsNode().boundsInRoot.height
            compose.runOnIdle {
                assertEquals("alpha\nbeta", clipboard.primaryClip!!.getItemAt(0).text.toString())
                assertEquals(
                    "Reading rows must match Text-only height at the same font scale",
                    baselineHeight,
                    first.height,
                    .1f,
                )
            }
            val actions = beta.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            compose.runOnIdle {
                assertEquals(host.get().getString(AppToolsStrings.toolsRxLogCopy), actions.single().label)
                assertTrue(actions.single().action())
            }
            compose.runOnIdle {
                assertEquals("alpha\nbeta", clipboard.primaryClip!!.getItemAt(0).text.toString())
            }
            compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
            compose.onNodeWithTag("remote-cli-copy").assertHeightIsAtLeast(48.dp)
                .performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            compose.onNodeWithTag("remote-cli-copy").assertIsFocused()
            compose.onNodeWithTag("remote-cli-copy").performKeyInput { pressKey(Key.Enter) }
            compose.onNode(isDialog()).assertExists()
        } finally {
            scope.cancel()
            host.pause().stop().destroy()
        }
    }

    @Test fun chooserStartsAtTheVisibleRecentResponseInLongHistory() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val holder = NodeCliStateHolder(scope, ResourcesDiagnosticsText(host.get().resources))
            host.get().setContent {
                MeshCoreTheme(theme = ThemeRegistry.default, motionScale = 0f) {
                    AppRemoteNodeCli(RemoteNodesFixture().session, { _, _, _ -> "unused" }, true, holder)
                }
            }
            compose.runOnIdle {
                holder.appendOutput("long", CliOutputType.COMMAND)
                repeat(998) { holder.appendOutput("> response-$it", CliOutputType.RESPONSE) }
                holder.appendOutput("", CliOutputType.RESPONSE)
            }
            compose.waitForIdle()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex) and !hasAnyAncestor(isDialog()))
                .performScrollToIndex(900)
            compose.onNodeWithText("> response-900").assertIsDisplayed()
            compose.onNodeWithTag("remote-cli-copy").performClick()
            compose.onNode(hasText("> response-900") and hasAnyAncestor(isDialog()))
                .assertIsDisplayed().performClick()
            compose.runOnIdle {
                val clipboard = host.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals((0 until 998).joinToString("\n") { "response-$it" } + "\n",
                    clipboard.primaryClip!!.getItemAt(0).text.toString())
            }
            compose.onNodeWithTag("remote-cli-copy").performClick()
            compose.onNode(
                hasText(host.get().getString(AppToolsStrings.toolsCliChannelEmpty)) and hasAnyAncestor(isDialog())
            ).performScrollTo().assertHasClickAction().performClick()
            compose.runOnIdle {
                val clipboard = host.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals((0 until 998).joinToString("\n") { "response-$it" } + "\n",
                    clipboard.primaryClip!!.getItemAt(0).text.toString())
            }
        } finally {
            scope.cancel()
            host.pause().stop().destroy()
        }
    }
}
