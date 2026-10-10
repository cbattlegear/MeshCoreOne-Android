// PortedFrom: MC1Tests/Views/RemoteNodes/NodeContactInfoSectionTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.remotenodes

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
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
import com.meshcoreone.android.app.navigation.AppRemoteNodeCli
import com.meshcoreone.android.core.maps.MapCamera
import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.services.remote.RemoteNodeError
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.core.model.RoomPermissionLevel
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.feature.remotenodes.RemoteNodesEntry
import com.meshcoreone.android.feature.remotenodes.RemoteNodeLaunch
import com.meshcoreone.android.feature.remotenodes.RemoteNodeLaunchAction
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.RemoteNodeAction
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
import java.io.File
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "w1080dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
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

    private fun show(launch: RemoteNodeLaunch? = null, onJoinRoom: (com.meshcoreone.android.core.model.RemoteNodeSessionDTO) -> Unit = {}) {
        host.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, font.floatValue)) {
                Box(Modifier.width(width.value).height(1000.dp)) {
                    MeshCoreTheme(theme = ThemeRegistry.default, motionScale = 0f) {
                        val map: RemoteNodesMapSurface = { state, _, _, _, camera, select, modifier ->
                            Column(modifier.testTag("synthetic-map")) {
                                Text("Local map fixture: ${state.points.size} markers")
                                Button({ state.points.firstOrNull()?.let(select) }) { Text("Select fixture report") }
                                Button({ camera(MapCamera(GeoPoint(38.0, -121.0), .05, .05)) }) { Text("Move fixture camera") }
                            }
                        }
                        RemoteNodesEntry(FeatureRoute(FeatureId.REMOTE_NODES), {}, fixture, mapSurface = map, launch = launch, onJoinRoom = onJoinRoom)
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
        capture("focused-owner")
        compose.onNodeWithTag("action:${L.remoteNodesSettingsApplyContactInfo}").performScrollTo().performClick()
        assertFalse(fixture.commands.any { it.startsWith("set owner.info") })
        compose.onNodeWithTag("confirm-remote-action").performClick()
        compose.waitUntil { fixture.commands.contains("set owner.info KD7ABC extra") }
        assertEquals(1, fixture.commands.count { it == "set owner.info KD7ABC extra" })
    }

    @Test fun ownerInfoSupportsDeletionAndSurvivesResizeAndTwoHundredPercentFont() {
        show(); open(); expand(L.remoteNodesSettingsContactInfo)
        val field = compose.onNodeWithTag("remote-owner-info").performScrollTo().performClick()
        field.performKeyInput { pressKey(Key.Backspace) }
        field.assertTextContains("KD7AB")
        compose.runOnIdle { width.value = 834.dp; font.floatValue = 2f }
        field.performScrollTo().assertIsFocused().assertTextContains("KD7AB")
        compose.onNodeWithTag("remote-node-list").assertExists()
        compose.onNodeWithTag("remote-management").assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2f, layouts.first().layoutInput.density.fontScale, 0f)
        assertFalse("Focused native text must not clip at 200 percent", layouts.first().hasVisualOverflow)
        capture("expanded-owner-font200")
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
        capture("history-fixture")
        compose.onNodeWithText(text(L.remoteNodesStatusViewOnMap)).performScrollTo().performClick()
        compose.onNodeWithTag("remote-history-full-map").assertExists()
        compose.onNode(hasText("Move fixture camera") and hasAnyAncestor(hasTestTag("remote-history-full-map")))
            .performScrollTo().assertExists()
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

    @Test fun catalogUpdatesDoNotReloadTheHistorySnapshotUntilTheUserRefreshes() {
        show(); open()
        compose.onNodeWithTag("remote-history").performClick()
        compose.waitForIdle()
        assertEquals("Opening history must read one snapshot", 1, fixture.historyReads)
        compose.runOnIdle {
            fixture.sessions = listOf(fixture.session.copy(name = "Updated synthetic name"))
            fixture.publish()
        }
        compose.onNodeWithText("Updated synthetic name").assertExists()
        assertEquals(1, fixture.historyReads)
        compose.onNode(hasText(text(L.remoteNodesStatusRefresh)) and hasAnyAncestor(hasTestTag("remote-history-content")))
            .performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals("Explicit refresh must read one new snapshot", 2, fixture.historyReads)
    }

    @Test fun authenticationCancelStopsThePendingLoginAndKeepsTheCatalogReachable() {
        fixture.sessions = emptyList()
        show(); compose.onNodeWithTag("open-node:${fixture.contact.id}").performClick()
        compose.onNodeWithTag("remote-login-password").performTextInput("password")
        compose.onNodeWithText(text(L.remoteNodesAuthRememberPassword)).assertIsToggleable().assertHeightIsAtLeast(48.dp)
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

    @Test fun contactHistoryNavigationRemainsPrivateAndWorksWithoutLoginOrRadio() {
        fixture.connection = fixture.connection.copy(ready = false)
        fixture.snapshots = emptyList()
        fixture.publish()
        val navigation = NavigationCoordinator()
        navigation.navigateToContactDetail(fixture.contact)
        navigation.navigateToRemoteNode(fixture.contact, RemoteNodeAction.SAVED_HISTORY)
        val tokens = com.meshcoreone.android.app.navigation.NavigationSavedState.encode(navigation.state.value)
        assertFalse(tokens.any { fixture.publicKey.hexString in it || fixture.contact.name in it })
        host.get().setContent {
            MeshCoreTheme(theme = ThemeRegistry.default, motionScale = 0f) { NativeNavigationShell(navigation, remoteNodes = fixture) }
        }
        compose.onNodeWithTag("remote-history-content").assertExists()
        assertEquals(0, fixture.loginStarted)
        assertTrue(fixture.commands.isEmpty())
        compose.onNodeWithTag("navigation-back").performClick()
        assertTrue(navigation.state.value.activeStack.last().destination is com.meshcoreone.android.app.navigation.NavigationDestination.ContactDetail)
        navigation.replaceRadio(com.meshcoreone.android.core.model.RadioId(java.util.UUID.randomUUID()))
        assertFalse(navigation.state.value.stacks.values.flatten().any { it.destination is com.meshcoreone.android.app.navigation.NavigationDestination.RemoteNode })
    }

    @Test fun contactTelemetryAuthenticatesWithoutExposingAdminSettings() {
        fixture.loginResult = fixture.session
        show(RemoteNodeLaunch(fixture.contact, RemoteNodeLaunchAction.TELEMETRY))
        compose.onNodeWithTag("remote-login-password").performTextInput("password")
        compose.onNodeWithTag("remote-login").performClick()
        compose.onNodeWithTag("remote-management").assertExists()
        compose.onNodeWithTag("remote-tab:SETTINGS").assertDoesNotExist()
        expand(L.remoteNodesStatusTitle)
        compose.waitUntil { fixture.statusReads > 0 }
        assertTrue(fixture.commands.isEmpty())
    }

    @Test fun roomJoinReturnsTheAuthenticatedRoomToTheAppConversationConsumer() {
        val room = fixture.contact.copy(typeRawValue = ContactType.ROOM.rawValue)
        fixture.contacts = listOf(room)
        fixture.loginResult = fixture.session.copy(role = RemoteNodeRole.ROOM_SERVER, permissionLevel = RoomPermissionLevel.READ_WRITE)
        var joined: com.meshcoreone.android.core.model.RemoteNodeSessionDTO? = null
        show(RemoteNodeLaunch(room, RemoteNodeLaunchAction.JOIN_ROOM)) { joined = it }
        compose.onNodeWithTag("remote-login-password").performTextInput("password")
        compose.onNodeWithTag("remote-login").performClick()
        compose.waitUntil { joined != null }
        assertEquals(fixture.loginResult, joined)
        assertTrue(requireNotNull(joined).canPost)
        assertEquals(1, fixture.loginStarted)
    }

    @Test fun chatNodeTelemetryUsesBinaryPublicKeyIdentityWithoutRemoteLogin() {
        val chat = fixture.contact.copy(typeRawValue = ContactType.CHAT.rawValue)
        fixture.contacts = listOf(chat)
        show(RemoteNodeLaunch(chat, RemoteNodeLaunchAction.TELEMETRY))
        compose.onNodeWithTag("direct-node-telemetry").assertExists()
        compose.onNodeWithTag("direct-telemetry-refresh").performClick()
        compose.waitUntil { fixture.binaryReads == 1 }
        compose.onNodeWithText("72.5 °F").assertExists()
        assertEquals(0, fixture.loginStarted)
        assertTrue(fixture.commands.isEmpty())
    }

    @Test fun realContactDetailActionsOpenOfflineHistoryAndAuthenticatedManagement() {
        fixture.snapshots = emptyList()
        fixture.loginResult = fixture.session
        val navigation = NavigationCoordinator()
        navigation.navigateToContactDetail(fixture.contact)
        host.get().setContent {
            MeshCoreTheme(theme = ThemeRegistry.default, motionScale = 0f) {
                NativeNavigationShell(navigation, nodes = fixture.nodes, remoteNodes = fixture)
            }
        }
        compose.onNodeWithText(text(com.meshcoreone.android.core.l10n.generated.AppContactsStrings.contactsDetailSavedHistory))
            .performScrollTo().performClick()
        compose.onNodeWithTag("remote-history-content").assertExists()
        compose.onNodeWithTag("navigation-back").performClick()
        compose.onNode(hasText(text(com.meshcoreone.android.core.l10n.generated.AppContactsStrings.contactsDetailManagement)) and !hasTestTag("open-remote-nodes"))
            .performScrollTo().performClick()
        compose.onNodeWithTag("remote-login-password").performTextInput("password")
        compose.onNodeWithTag("remote-login").performClick()
        compose.onNodeWithTag("remote-tab:SETTINGS").assertExists()
        assertEquals(1, fixture.loginStarted)
    }

    @Test fun missingLocationPickerUsesCameraSelectionAndRequiresConfirmationBeforeRemoteWrite() {
        fixture.location = Coordinate(0.0, 0.0)
        show(); open(); expand(L.remoteNodesSettingsIdentityLocation)
        compose.onNodeWithText(text(L.remoteNodesSettingsPickOnMap)).performScrollTo().performClick()
        compose.onNodeWithTag("action:${L.remoteNodesDone}").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Move fixture camera").performScrollTo().performClick()
        compose.onNodeWithTag("action:${L.remoteNodesDone}").performScrollTo().assertIsEnabled().performClick()
        assertFalse(fixture.commands.any { it.startsWith("set lat") || it.startsWith("set lon") })
        compose.onNodeWithTag("action:${L.remoteNodesSettingsApplyIdentitySettings}").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-remote-action").performClick()
        compose.waitUntil { fixture.commands.contains("set lon -121.0") }
        assertTrue(fixture.commands.contains("set lat 38.0"))
    }

    @Test fun nativeCliKeyboardAndAccessibleHistoryControlsPreserveRebootTimeoutSemantics() {
        val commands = mutableListOf<String>()
        host.get().setContent {
            MeshCoreTheme(theme = ThemeRegistry.default, motionScale = 0f) {
                AppRemoteNodeCli(fixture.session, { _, command, _ ->
                    commands += command
                    throw RemoteNodeError.Timeout()
                }, true)
            }
        }
        val input = compose.onNodeWithTag("remote-cli-input")
        input.performClick().performTextInput("reboot")
        input.performKeyInput { pressKey(Key.Enter) }
        assertTrue(commands.isEmpty())
        compose.onNodeWithTag("remote-cli-confirm").performClick()
        compose.onNodeWithText(text(L.remoteNodesNodeCliRebootSent)).assertExists()
        assertEquals(listOf("reboot"), commands)
        compose.onNodeWithContentDescription(text(AppToolsStrings.toolsCliHistoryUp)).performClick()
        input.assertTextContains("reboot")
        compose.onNodeWithContentDescription(text(AppToolsStrings.toolsCliHistoryDown)).performClick()
        input.assertTextContains("")
        compose.onNodeWithContentDescription(text(AppToolsStrings.toolsCliTabComplete)).assertHasClickAction()
    }

    @Test fun nativeCliConfigurationChangePreservesDraftAndUsesTheNewResourceLocale() {
        val config = mutableStateOf(android.content.res.Configuration(host.get().resources.configuration))
        val resources = mutableStateOf(host.get().resources)
        host.get().setContent {
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalResources provides resources.value,
                androidx.compose.ui.platform.LocalConfiguration provides config.value,
            ) {
                MeshCoreTheme(theme = ThemeRegistry.default, motionScale = 0f) {
                    AppRemoteNodeCli(fixture.session, { _, _, _ -> throw RemoteNodeError.Timeout() }, true)
                }
            }
        }
        val input = compose.onNodeWithTag("remote-cli-input")
        input.performClick().performTextInput("reboot")
        compose.runOnIdle {
            config.value = android.content.res.Configuration(config.value).apply { setLocale(java.util.Locale.GERMANY) }
            resources.value = host.get().createConfigurationContext(config.value).resources
        }
        input.assertTextContains("reboot")
        input.performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("remote-cli-confirm").performClick()
        compose.onNodeWithText(resources.value.getString(L.remoteNodesNodeCliRebootSent)).assertExists()
    }

    private fun capture(id: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = host.get().window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("A blank host is not native render evidence", pixels.toSet().size > 16)
        val directory = File(requireNotNull(System.getProperty("navigationArtifactDirectory")))
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "wp313-sdk-${Build.VERSION.SDK_INT}-$id.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
