// PortedFrom: MC1/Views/RemoteNodes/NodeCLIView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Tools/CLI/CLICompletionEngineTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.remotenodes

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.app.navigation.AppRemoteNodeCli
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.feature.tools.diagnostics.ResourcesDiagnosticsText
import com.meshcoreone.android.feature.tools.diagnostics.cli.NodeCliStateHolder
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "w1080dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class AppRemoteNodeCliCompletionTest {
    @get:Rule val compose = createEmptyComposeRule()

    private data class Request(val session: EntityKey, val command: String, val timeout: Duration)

    private class Harness(activity: ComponentActivity, scope: CoroutineScope) {
        val fixture = RemoteNodesFixture()
        val holder = NodeCliStateHolder(scope, ResourcesDiagnosticsText(activity.resources))
        val requests = mutableListOf<Request>()
        var enabled by mutableStateOf(true)
        var narrow by mutableStateOf(false)
        var fontScale by mutableStateOf(1f)
        lateinit var inputMode: InputModeManager
    }

    private fun mounted(narrow: Boolean = false, test: (Harness) -> Unit) {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            host.get().actionBar?.hide()
            val harness = Harness(host.get(), scope)
            harness.narrow = narrow
            harness.fontScale = if (narrow) 2f else 1f
            host.get().setContent {
                harness.inputMode = LocalInputModeManager.current
                CompositionLocalProvider(LocalDensity provides Density(1f, harness.fontScale)) {
                    MeshCoreTheme(theme = ThemeRegistry.default, motionScale = 0f) {
                        Box(Modifier.width(if (harness.narrow) 320.dp else 1080.dp)) {
                            AppRemoteNodeCli(harness.fixture.session, { session, command, timeout ->
                                harness.requests += Request(session, command, timeout)
                                "response: $command"
                            }, harness.enabled, harness.holder)
                        }
                    }
                }
            }
            compose.waitForIdle()
            test(harness)
        } finally {
            host.pause().stop().destroy()
            scope.cancel()
        }
    }

    private fun input() = compose.onNodeWithTag("remote-cli-input")
    private fun suggestion(text: String) = compose.onNodeWithTag("remote-cli-suggestion:$text")
    private fun type(text: String) = input().performClick().performTextReplacement(text)
    private fun key(key: Key) {
        input().performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        input().performKeyInput { pressKey(key) }
    }

    private fun assertInput(text: String) {
        input().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))
        input().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(text.length)))
    }

    private fun assertNotSubmitted(harness: Harness) {
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertTrue(harness.requests.isEmpty()) }
    }

    private fun confirm(harness: Harness, command: String) {
        val before = harness.requests.size
        compose.onNodeWithTag("remote-cli-confirm").performClick()
        compose.waitUntil { harness.requests.size == before + 1 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(before + 1, harness.requests.size)
            assertEquals(
                Request(EntityKey(harness.fixture.radio, harness.fixture.session.id), command, 10.seconds),
                harness.requests.last(),
            )
            assertEquals(command, harness.holder.state.value.terminal.commandHistory.last())
            assertEquals("", harness.holder.ghostText)
        }
        assertInput("")
        compose.onNodeWithTag("remote-cli-suggestions").assertDoesNotExist()
    }

    @Test fun keyboardDisplaysCyclesAndAcceptsBeforeConfirmingTheActualCandidate() = mounted { harness ->
        for ((tabs, chosen) in listOf(2 to "start", 3 to "stop", 4 to "start", 5 to "stop")) {
            type("log st")
            key(Key.Tab)
            suggestion("start").assertIsDisplayed().assertIsNotSelected()
            suggestion("stop").assertIsDisplayed().assertIsNotSelected()
            assertInput("log st")
            repeat(tabs - 1) { key(Key.Tab) }
            suggestion(chosen).assertIsSelected()
            suggestion(if (chosen == "start") "stop" else "start").assertIsNotSelected()
            assertInput("log st")
            val before = harness.requests.size
            key(Key.Enter)
            assertInput("log $chosen ")
            compose.onNodeWithTag("remote-cli-suggestions").assertDoesNotExist()
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.runOnIdle { assertEquals(before, harness.requests.size) }
            key(Key.Enter)
            compose.onNode(hasText("log $chosen ") and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.runOnIdle { assertEquals(before, harness.requests.size) }
            confirm(harness, "log $chosen")
        }
        key(Key.DirectionUp)
        assertInput("log stop")
        key(Key.DirectionDown)
        assertInput("")
    }

    @Test fun touchChoosesTheClickedEntryBeforeAndAfterKeyboardWrapAtLargeFont() = mounted(narrow = true) { harness ->
        for (tabs in 1..4) {
            for (chosen in listOf("stop", "start")) {
                type("log st")
                repeat(tabs) { key(Key.Tab) }
                val before = harness.requests.size
                suggestion(chosen).assertHeightIsAtLeast(48.dp).performScrollTo().performClick()
                assertInput("log $chosen ")
                input().assertIsFocused()
                compose.onNodeWithTag("remote-cli-suggestions").assertDoesNotExist()
                compose.onNode(isDialog()).assertDoesNotExist()
                compose.runOnIdle { assertEquals(before, harness.requests.size) }
                compose.onNodeWithTag("remote-cli-submit").performScrollTo().assertIsDisplayed().performClick()
                confirm(harness, "log $chosen")
            }
        }
    }

    @Test fun imeAndSendButtonBothAcceptSelectionWithoutSendingTheUnfinishedPrefix() = mounted { harness ->
        for (ime in listOf(true, false)) {
            type("log st")
            repeat(3) { key(Key.Tab) }
            val before = harness.requests.size
            fun submit() {
                if (ime) input().performImeAction() else compose.onNodeWithTag("remote-cli-submit").performClick()
            }
            submit()
            assertInput("log stop ")
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.runOnIdle { assertEquals(before, harness.requests.size) }
            submit()
            confirm(harness, "log stop")
        }
    }

    @Test fun keyboardAndMouseChipChoiceReturnFocusToTheEditorBeforeActualSubmit() = mounted { harness ->
        for (mouse in listOf(false, true)) {
            type("log st")
            key(Key.Tab)
            compose.runOnIdle { assertTrue(harness.inputMode.requestInputMode(InputMode.Keyboard)) }
            val choice = suggestion("stop")
            if (mouse) {
                choice.performMouseInput { click() }
            } else {
                choice.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
                choice.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            }
            assertInput("log stop ")
            input().assertIsFocused()
            compose.onNode(isDialog()).assertDoesNotExist()
            val before = harness.requests.size
            input().performKeyInput { pressKey(Key.Enter) }
            compose.runOnIdle { assertEquals(before, harness.requests.size) }
            confirm(harness, "log stop")
        }
    }

    @Test fun editsAndEscapeDiscardOldChoicesAndCannotInvokeAStaleChip() = mounted { harness ->
        type("log st")
        repeat(3) { key(Key.Tab) }
        key(Key.Escape)
        compose.onNodeWithTag("remote-cli-suggestions").assertDoesNotExist()
        assertInput("log st")
        assertNotSubmitted(harness)

        key(Key.Tab)
        val staleClick = suggestion("stop").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        type("ver")
        compose.onNodeWithTag("remote-cli-suggestions").assertDoesNotExist()
        compose.runOnIdle { staleClick() }
        assertInput("ver")
        assertNotSubmitted(harness)
        key(Key.Enter)
        confirm(harness, "ver")
    }

    @Test fun mountedNodeVocabularyExcludesAppSessionsWithoutRemovingNodeCommands() = mounted(narrow = true) { harness ->
        key(Key.Tab)
        for (command in listOf("session", "logout", "login")) suggestion(command).assertDoesNotExist()
        for (command in listOf("help", "ver")) suggestion(command).assertExists()
        compose.onAllNodesWithTag("remote-cli-suggestion:clear").assertCountEquals(2)
        compose.runOnIdle {
            val suggestions = harness.holder.state.value.terminal.tabSuggestions!!
            assertFalse(suggestions.contains("session"))
            assertFalse(suggestions.contains("logout"))
            assertTrue(suggestions.containsAll(listOf("help", "clear", "ver")))
        }
        type("lo")
        key(Key.Tab)
        assertInput("log ")
        compose.onNodeWithTag("remote-cli-suggestions").assertDoesNotExist()
        assertNotSubmitted(harness)

        type("")
        key(Key.Tab)
        val count = compose.runOnIdle { harness.holder.state.value.terminal.tabSuggestions!!.size }
        repeat(count) { key(Key.Tab) }
        suggestion("ver").assertIsSelected().assertIsDisplayed()
        assertInput("")
        compose.onNodeWithTag("remote-cli-submit").assertIsEnabled().performScrollTo().performClick()
        assertInput("ver ")
        assertNotSubmitted(harness)
        compose.onNodeWithTag("remote-cli-submit").performClick()
        confirm(harness, "ver")
    }

    @Test fun windowAndFontChangesPreserveTheSelectedCandidateWithoutSending() = mounted { harness ->
        key(Key.Tab)
        val count = compose.runOnIdle { harness.holder.state.value.terminal.tabSuggestions!!.size }
        repeat(count) { key(Key.Tab) }
        suggestion("ver").assertIsSelected().assertIsDisplayed()
        for ((narrow, fontScale) in listOf(true to 1f, true to 2f, false to 2f, false to 1f)) {
            compose.runOnIdle {
                harness.narrow = narrow
                harness.fontScale = fontScale
            }
            suggestion("ver").assertIsSelected().assertIsDisplayed()
            assertInput("")
            assertNotSubmitted(harness)
        }
        key(Key.Enter)
        assertInput("ver ")
        assertNotSubmitted(harness)
        key(Key.Enter)
        confirm(harness, "ver")
    }

    @Test fun unselectedChoicesDoNotInventACandidateOrSurviveAConfirmedSend() = mounted { harness ->
        type("log st")
        key(Key.Tab)
        suggestion("start").assertIsNotSelected()
        suggestion("stop").assertIsNotSelected()
        key(Key.Enter)
        compose.onNode(hasText("log st") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.runOnIdle { assertTrue(harness.requests.isEmpty()) }
        confirm(harness, "log st")
    }

    @Test fun completionStillRequiresConfirmationAndCurrentPermissionToSend() = mounted { harness ->
        type("log st")
        repeat(3) { key(Key.Tab) }
        key(Key.Enter)
        assertNotSubmitted(harness)
        key(Key.Enter)
        compose.runOnIdle { harness.enabled = false }
        compose.onNodeWithTag("remote-cli-confirm").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(harness.requests.isEmpty()) }
        compose.runOnIdle { harness.enabled = true }
        confirm(harness, "log stop")
    }
}
