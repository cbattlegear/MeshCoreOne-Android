// PortedFrom: MC1Tests/AppStateThemeWiringTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/MessageTextThemeTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/AppColorSchemePreferenceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: real process restore, mounted message and settings selection consumers.
package com.meshcoreone.android.app.container

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.datastore.AppearanceStorageKey
import com.meshcoreone.android.core.datastore.BackupPreferenceSnapshot
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.datastore.PreferenceValue
import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.designsystem.ColorScheme
import com.meshcoreone.android.core.designsystem.LocalMeshTheme
import com.meshcoreone.android.core.designsystem.MeshThemeTokens
import com.meshcoreone.android.core.designsystem.ThemeId
import com.meshcoreone.android.core.designsystem.ThemeProblem
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.designsystem.ThemeService
import com.meshcoreone.android.core.designsystem.ThemeServiceFailure
import com.meshcoreone.android.core.designsystem.ThemeServiceHost
import com.meshcoreone.android.core.designsystem.ThemeServiceState
import com.meshcoreone.android.core.designsystem.WCAGContrast
import com.meshcoreone.android.core.designsystem.labelResource
import com.meshcoreone.android.core.designsystem.toThemeColor
import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.PersistenceKeys
import com.meshcoreone.android.core.model.canonicalString
import com.meshcoreone.android.core.runtime.RuntimePreferenceValue
import com.meshcoreone.android.feature.chats.composer.ui.MessageBody
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineState
import com.meshcoreone.android.feature.chats.timeline.TimelineRow
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimeline
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimelineCallbacks
import com.meshcoreone.android.feature.settings.app.appearance.ThemeServiceAppearanceController
import com.meshcoreone.android.feature.settings.app.appearance.ui.AppearanceScreen
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31], qualifiers = "en-rUS-w600dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeConsumerIntegrationTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var host: ActivityController<ComponentActivity>

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    private suspend fun TestScope.withThemeProcess(
        block: suspend (ContainerHarness, MeshCoreStorage, ThemeService) -> Unit,
    ) {
        val directory = temporary.newFolder()
        val application = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        val storage = MeshCoreStorage.get(context)
        val service = ThemeService.create(storage.preferences, backgroundScope)
        val h = ContainerHarness(this, store, themeService = service)
        try {
            block(h, storage, service)
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            compose.waitForIdle()
            h.container.close()
            service.close()
            storage.close()
        }
    }

    @OriginalCase("AppStateThemeWiringTests::AppState exposes a themeService defaulting to the default theme()")
    @Test fun processStateUsesTheSameServiceAsTheHostWithoutWritingMissingKeys() = runTest {
        withThemeProcess { h, storage, service ->
            assertSame(service, h.container.themeService)
            assertSame(service, h.appState.themeService)
            assertEquals(ThemeId.DEFAULT, assertIs<ThemeServiceState.Ready>(service.state.value).selection.current.id)
            assertFalse(storage.preferences.snapshot().contains(AppearanceStorageKey.selectedThemeID))
            assertFalse(storage.preferences.snapshot().contains(AppearanceStorageKey.appColorSchemePreference))
            assertEquals(10, service.availableToCurrentUser.size)
            assertTrue(h.sessions.current == null)
        }
    }

    @OriginalCase("AppStateThemeWiringTests::notifyDataRestored does not wipe a restored paid theme while the store is unloaded()", "platform-adaptation")
    @Test fun processRestoreRebakesMountedMessagesWithoutReplacingRememberedContent() = runTest {
        withThemeProcess { h, storage, service ->
            val radio = RadioId(UUID(0, 301))
            val messages = MessageDirection.entries.mapIndexed { index, direction ->
                MessageDTO(
                    id = UUID(0, index.toLong() + 1), radioId = radio, channelIndex = 0u,
                    text = "hello #news", timestamp = 100u, direction = direction, status = MessageStatus.SENT,
                )
            }
            val state = ChatTimelineState(
                messages = messages, rows = messages.map { TimelineRow.Message(it, true, true) },
                hasLoadedOnce = true, hasMoreMessages = false, initialAnchorConsumed = true,
            )
            var tokens: MeshThemeTokens? = null
            var incomingBackground = Color.Unspecified
            var outgoingBackground = Color.Unspecified
            var retained = 0
            var contentInitializations = 0
            compose.runOnUiThread {
                host.get().setContent {
                    ThemeServiceHost(service) {
                        val remembered = remember { mutableIntStateOf(++contentInitializations) }
                        val current = LocalMeshTheme.current
                        val incoming = MaterialTheme.colorScheme.surfaceVariant
                        val outgoing = MaterialTheme.colorScheme.primaryContainer
                        SideEffect {
                            tokens = current
                            incomingBackground = incoming
                            outgoingBackground = outgoing
                            retained = remembered.intValue
                        }
                        ChatTimeline(
                            state, ChatTimelineCallbacks({}, {}, {}, { _, _ -> }, {}, {}),
                            showsIncomingAvatars = false, showSenderName = false,
                            messageBody = { message, outgoingMessage ->
                                MessageBody(message.text, outgoingMessage, null, {},
                                    Modifier.testTag(if (outgoingMessage) "outgoing" else "incoming"))
                            },
                        )
                    }
                }
            }
            compose.onNodeWithTag("incoming", useUnmergedTree = true).assertIsDisplayed()
            val originalIncoming = colors(layout("incoming"))
            val originalOutgoing = colors(layout("outgoing"))
            val versions = Triple(h.appState.contactsVersion.value, h.appState.conversationsVersion.value, h.appState.servicesVersion.value)
            for (id in listOf(ThemeId.NORD, ThemeId.EMBER)) {
                storage.preferences.remove(AppearanceStorageKey.selectedThemeID)
                val restored = mapOf(AppearanceStorageKey.selectedThemeID.rawValue to PreferenceValue.StringValue(id.rawValue))
                assertEquals(restored.keys, storage.backupPreferences.restoreMissing(BackupPreferenceSnapshot(restored)).toSet())
                h.appState.notifyDataRestored()
                assertEquals(id, assertIs<ThemeServiceState.Ready>(service.state.value).selection.current.id)
                compose.waitForIdle()
                assertEquals(id, assertNotNull(tokens).frame.theme.id)
                if (id == ThemeId.EMBER) assertEquals(ColorScheme.DARK, assertNotNull(tokens).frame.colorScheme)
                assertEquals(1, retained)
                assertEquals(1, contentInitializations)
                val incoming = layout("incoming")
                val outgoing = layout("outgoing")
                assertNotEquals(originalIncoming, colors(incoming))
                assertNotEquals(originalOutgoing, colors(outgoing))
                assertReadable(incoming, incomingBackground)
                assertReadable(outgoing, outgoingBackground)
                assertEquals(id.rawValue, storage.preferences.get(AppearanceStorageKey.selectedThemeID))
                assertEquals(ThemeRegistry.allThemes, service.availableToCurrentUser)
                assertTrue(h.sessions.current == null)
            }
            assertEquals(versions.first + 2, h.appState.contactsVersion.value)
            assertEquals(versions.second + 2, h.appState.conversationsVersion.value)
            assertEquals(versions.third + 2, h.appState.servicesVersion.value)
        }
    }

    @Test fun restoreDoesNotReportSuccessWhenTheProcessThemeRefreshFails() = runTest {
        withThemeProcess { h, _, service ->
            h.preferences.values[PersistenceKeys.LAST_CONNECTED_DEVICE_ID] =
                RuntimePreferenceValue.Text(UUID(0, 301).canonicalString())
            h.container.connectionPort.refreshLastConnection()
            val registry = assertNotNull(h.appState.ensureChatCoordinatorRegistry())
            val conversation = ChatConversationID.channel(RadioId(UUID(0, 301)), 0u)
            val original = registry.coordinator(conversation)
            val versions = Triple(h.appState.contactsVersion.value, h.appState.conversationsVersion.value, h.appState.servicesVersion.value)
            service.close()
            val failure = assertFailsWith<ThemeServiceFailure> { h.appState.notifyDataRestored() }
            assertEquals(ThemeProblem.OwnerClosed, failure.problem)
            assertEquals(versions.first + 1, h.appState.contactsVersion.value)
            assertEquals(versions.second + 1, h.appState.conversationsVersion.value)
            assertEquals(versions.third + 1, h.appState.servicesVersion.value)
            assertSame(registry, h.appState.chatCoordinatorRegistry)
            assertNull(registry.existingCoordinator(conversation))
            assertNotSame(original, registry.coordinator(conversation))
            assertNull(h.sessions.current)
        }
    }

    @OriginalCase("AppColorSchemePreferenceTests::allCases is exactly system, light, dark and id equals rawValue()")
    @Test fun mountedSchemeSelectionPersistsItsRawIdentityAndRefreshesTheHost() = runTest {
        withThemeProcess { h, storage, service ->
            val controller = ThemeServiceAppearanceController(service, backgroundScope)
            var scheme: ColorScheme? = null
            compose.runOnUiThread {
                host.get().setContent {
                    ThemeServiceHost(service) {
                        val current = LocalMeshTheme.current.frame.colorScheme
                        SideEffect { scheme = current }
                        AppearanceScreen(controller)
                    }
                }
            }
            for (preference in listOf(AppColorSchemePreference.DARK, AppColorSchemePreference.LIGHT, AppColorSchemePreference.SYSTEM)) {
                val label = host.get().getString(preference.labelResource)
                compose.onNodeWithText(label).performClick()
                compose.waitUntil(10_000) {
                    testScheduler.runCurrent()
                    (service.state.value as? ThemeServiceState.Ready)?.selection?.colorSchemePreference == preference
                }
                compose.onNodeWithText(label).assertIsSelected()
                assertEquals(preference.id, storage.preferences.get(AppearanceStorageKey.appColorSchemePreference))
                h.appState.notifyDataRestored()
                assertEquals(preference, assertIs<ThemeServiceState.Ready>(service.state.value).selection.colorSchemePreference)
                compose.waitForIdle()
                val systemDark = host.get().resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                assertEquals(preference.colorScheme ?: if (systemDark) ColorScheme.DARK else ColorScheme.LIGHT, scheme)
            }
        }
    }

    private fun layout(tag: String): AnnotatedString {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(layouts))
        }
        return layouts.single().layoutInput.text
    }

    private fun colors(text: AnnotatedString): List<Color> {
        val hashtag = text.getLinkAnnotations(6, text.length).single().item.styles?.style
        val base = text.spanStyles.first { it.start == 0 && it.end == text.length }.item
        return listOf(base.color, assertNotNull(hashtag).color)
    }

    private fun assertReadable(text: AnnotatedString, background: Color) {
        for (color in colors(text)) {
            assertTrue(WCAGContrast.contrastRatio(color.toThemeColor(), background.toThemeColor()) >= 4.5)
        }
    }
}
