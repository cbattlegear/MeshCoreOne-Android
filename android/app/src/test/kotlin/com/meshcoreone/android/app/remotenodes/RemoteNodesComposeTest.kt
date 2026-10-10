// PortedFrom: MC1Tests/Views/RemoteNodes/NodeContactInfoSectionTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.remotenodes

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.core.model.RoomPermissionLevel
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.feature.remotenodes.RemoteNodesEntry
import com.meshcoreone.android.feature.remotenodes.ui.RemoteNodesMapSurface
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "w1080dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteNodesComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val fixture = RemoteNodesFixture()
    private val width = mutableStateOf(390.dp)
    private val font = mutableFloatStateOf(1f)

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
    }
    @After fun destroyHost() { host.pause().stop().destroy() }
    private fun text(id: Int) = host.get().getString(id)

    private fun show() {
        host.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, font.floatValue)) {
                Box(Modifier.width(width.value).height(1000.dp)) {
                    MeshCoreTheme(theme = ThemeRegistry.default, motionScale = 0f) {
                        val map: RemoteNodesMapSurface = { state, _, _, _, _, select, modifier ->
                            Column(modifier.testTag("synthetic-map")) {
                                Text("Local map fixture: ${state.points.size} markers")
                                Button({ state.points.firstOrNull()?.let(select) }) { Text("Select fixture report") }
                            }
                        }
                        RemoteNodesEntry(FeatureRoute(FeatureId.REMOTE_NODES), {}, fixture, mapSurface = map)
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun open() {
        compose.onNodeWithTag("open-node:${fixture.contact.id}").performClick()
        compose.onNodeWithTag("remote-management").assertExists()
    }
    private fun expand(id: Int) = compose.onNodeWithText(text(id), useUnmergedTree = true).performScrollTo().performClick()

    @Test fun ownerInfoRemainsReadableWhileFocusedAndApplyUsesTheEditedNativeBinding() {
        show(); open(); expand(L.remoteNodesSettingsContactInfo)
        val field = compose.onNodeWithTag("remote-owner-info").performScrollTo().performClick()
        field.performTextInput(" extra")
        field.assertIsFocused().assertTextContains("KD7ABC extra")
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertTrue(layouts.isNotEmpty())
        assertTrue("Focused glyph color must remain visible", layouts.first().layoutInput.style.color.alpha > .2f)
        field.assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("action:${L.remoteNodesSettingsApplyContactInfo}").performScrollTo().performClick()
        assertFalse(fixture.commands.any { it.startsWith("set owner.info") })
        compose.onNodeWithTag("confirm-remote-action").performClick()
        compose.waitUntil { fixture.commands.contains("set owner.info KD7ABC extra") }
        assertEquals(1, fixture.commands.count { it == "set owner.info KD7ABC extra" })
    }

    @Test fun ownerInfoSupportsDeletionAndSurvivesResizeAndTwoHundredPercentFont() {
        show(); open(); expand(L.remoteNodesSettingsContactInfo)
        val field = compose.onNodeWithTag("remote-owner-info").performScrollTo().performClick()
        field.performTextReplacement("KD7AB")
        field.assertTextContains("KD7AB")
        compose.runOnIdle { width.value = 834.dp; font.floatValue = 2f }
        field.performScrollTo().assertIsFocused().assertTextContains("KD7AB")
        compose.onNodeWithTag("remote-node-list").assertExists()
        compose.onNodeWithTag("remote-management").assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2f, layouts.first().layoutInput.density.fontScale, 0f)
        compose.runOnIdle { width.value = 390.dp }
        field.performScrollTo().assertTextContains("KD7AB")
        compose.onNodeWithTag("remote-node-list").assertDoesNotExist()
    }

    @Test fun guestHasReadOnlyTelemetryAndPermissionRevocationDiscardsPendingAdminAction() {
        show(); open()
        compose.onNodeWithTag("action:${L.remoteNodesSettingsRebootDevice}").performScrollTo().performClick()
        compose.runOnIdle {
            fixture.sessions = listOf(fixture.session.copy(permissionLevel = RoomPermissionLevel.GUEST))
            fixture.publish()
        }
        compose.onNodeWithTag("confirm-remote-action").assertDoesNotExist()
        compose.onNodeWithTag("remote-tab:SETTINGS").assertDoesNotExist()
        compose.onNodeWithTag("remote-tab:CLI").assertDoesNotExist()
        compose.onNodeWithText(text(L.remoteNodesStatusGuestMode)).assertExists()
        assertFalse(fixture.commands.contains("reboot"))
        expand(L.remoteNodesStatusTitle)
        compose.waitUntil { fixture.statusReads == 1 }
        compose.onNodeWithText("-87 dBm").assertExists()
    }

    @Test fun disconnectedHistoryHasChartsReportSelectionAttributionAndNoRadioWrites() {
        fixture.connection = fixture.connection.copy(ready = false)
        fixture.sessions = emptyList()
        fixture.publish()
        show()
        compose.onNodeWithText(text(L.remoteNodesHistoryOverviewTitle)).performClick()
        compose.onNodeWithTag("remote-history-content").assertExists()
        compose.onNodeWithText("3.9 V", substring = true).assertExists()
        compose.onNodeWithTag("synthetic-map").performScrollTo().assertExists()
        compose.onNodeWithText("Select fixture report").performClick()
        compose.onAllNodesWithText("37.7000", substring = true).onFirst().assertExists()
        compose.onNodeWithText("OpenFreeMap").assertExists()
        assertTrue(fixture.commands.isEmpty())
    }

    @Test fun historyStoreFailureIsVisibleRatherThanClaimingNoSnapshots() {
        fixture.historyError = IOException("synthetic disk failure")
        fixture.snapshots = emptyList()
        show()
        compose.onNodeWithText(text(L.remoteNodesHistoryOverviewTitle)).performClick()
        compose.onNodeWithTag("remote-error").assertExists()
        compose.onNodeWithText(text(L.remoteNodesHistoryNoSnapshotsMessage)).assertDoesNotExist()
    }

    @Test fun authenticationCancelStopsThePendingLoginAndKeepsTheCatalogReachable() {
        fixture.sessions = emptyList()
        show(); compose.onNodeWithTag("open-node:${fixture.contact.id}").performClick()
        compose.onNodeWithTag("remote-login-password").performTextInput("password")
        compose.onNodeWithTag("remote-login").performClick()
        compose.waitUntil { fixture.loginStarted == 1 }
        compose.onNodeWithText(text(L.remoteNodesCancel)).performClick()
        compose.waitUntil { fixture.loginCancelled == 1 }
        compose.onNodeWithTag("remote-login-password").assertDoesNotExist()
        compose.onNodeWithTag("remote-node-list").assertExists()
    }

    @Test fun roomAdminUsesRoomAccessControlsInsteadOfRepeaterRegions() {
        fixture.session = fixture.session.copy(role = RemoteNodeRole.ROOM_SERVER)
        fixture.sessions = listOf(fixture.session)
        show(); open()
        compose.onNodeWithText(text(L.remoteNodesRoomSettingsRoomSettingsSection), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(L.remoteNodesSettingsRegions), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("remote-tab:TELEMETRY").performClick()
        expand(L.remoteNodesStatusTitle)
        compose.onNodeWithText(text(L.remoteNodesRoomStatusPostsReceived)).assertExists()
        compose.onNodeWithText("12").assertExists()
    }

    @Test fun realNavigationShellMakesTheAuxiliaryManagementRouteReachableFromNodes() {
        val navigation = NavigationCoordinator()
        host.get().setContent {
            MeshCoreTheme(theme = ThemeRegistry.default, motionScale = 0f) {
                NativeNavigationShell(navigation, remoteNodes = fixture)
            }
        }
        compose.onNodeWithTag("tab:NODES").performClick()
        compose.onNodeWithTag("open-remote-nodes").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("remote-node-list").assertExists()
        assertEquals(AppTab.NODES, navigation.state.value.selectedTab)
        compose.onNodeWithTag("navigation-back").performClick()
        compose.onNodeWithTag("remote-node-list").assertDoesNotExist()
    }
}
