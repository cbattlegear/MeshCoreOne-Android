// AndroidOnly: WP-302 Real native SDK31/37 Compose shell evidence with state-rich, explicitly synthetic feature content.
package com.meshcoreone.android.app.navigation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.designsystem.*
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineState
import com.meshcoreone.android.feature.chats.timeline.InitialTimelineAnchor
import com.meshcoreone.android.feature.chats.timeline.TimelineRow
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimeline
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimelineCallbacks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestName
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "w1080dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val executionName = TestName()
    private lateinit var host: ActivityController<ComponentActivity>
    private val backInput = DirectNavigationEventInput()
    private val width = mutableStateOf(360.dp)
    private val font = mutableFloatStateOf(1f)
    private val direction = mutableStateOf(LayoutDirection.Ltr)
    private val theme = mutableStateOf(ThemeRegistry.default)
    private val preference = mutableStateOf(AppColorSchemePreference.LIGHT)
    private val highContrast = mutableStateOf(false)
    private val navigation = NavigationCoordinator()

    @Before fun attachNativeHost() {
        emitNavigationExecutionBinding(javaClass.name, executionName.methodName, Build.VERSION.SDK_INT.toString())
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.get().navigationEventDispatcher.addInput(backInput)
        host.get().setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, font.floatValue),
                LocalLayoutDirection provides direction.value,
            ) {
                Box(Modifier.width(width.value).height(900.dp)) {
                    MeshCoreTheme(theme.value, preference.value, systemDark = false, highContrast = highContrast.value, motionScale = 0f) {
                        NativeNavigationShell(navigation, unreadCount = 123) { destination, _ -> FixtureContent(destination) }
                    }
                }
            }
        }
    }
    @After fun disposeNativeHost() {
        host.get().navigationEventDispatcher.removeInput(backInput)
        host.pause().stop().destroy()
    }

    @Composable private fun FixtureContent(destination: NavigationDestination) {
        val id = when (destination) {
            is NavigationDestination.Root -> "root-${destination.tab.name}"
            is NavigationDestination.Tool -> "tool-${destination.selection.sourceName}"
            is NavigationDestination.Setting -> "setting-${destination.selection.sourceName}"
            else -> "private-detail"
        }
        var draft by rememberSaveable { mutableStateOf("") }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("fixture:$id")) {
            Text("Synthetic shell content: $id")
            Text("\u4e2d\u6587\u6d88\u606f \u05e9\u05dc\u05d5\u05dd \u3053\u3093\u306b\u3061\u306f ".repeat(3),
                Modifier.testTag("body:$id"))
            OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().testTag("editor:$id"), label = { Text("Fixture draft") })
        }
    }

    @Test fun actualAvailableWidthDrivesBarRailAndMeasuredPaneThresholds() {
        for (value in listOf(360, 599, 600, 744, 779, 780, 834, 1080)) {
            compose.runOnIdle { width.value = value.dp }
            compose.onNodeWithTag(if (value < 600) "bottom-navigation" else "navigation-rail").assertIsDisplayed()
            compose.onNodeWithTag(if (value < 780) "single-pane" else "list-detail").assertIsDisplayed()
            compose.onNodeWithTag("navigation-list-pane").assertIsDisplayed()
            if (value >= 780) {
                compose.onNodeWithTag("navigation-list-pane").assertWidthIsAtLeast(380.dp)
                compose.onNodeWithTag("navigation-detail-pane").assertWidthIsAtLeast(320.dp)
                compose.onNodeWithTag("detail-empty").assertIsDisplayed()
            } else compose.onNodeWithTag("detail-empty").assertDoesNotExist()
            capture("width-$value")
        }
    }

    @Test fun replacingCoordinatorAtTheSameWidthMeasuresOnlyTheNewOwner() {
        for (windowWidth in listOf(390, 834)) {
            val first = NavigationCoordinator()
            val replacement = NavigationCoordinator()
            val owner = mutableStateOf(first)
            compose.runOnUiThread {
                width.value = windowWidth.dp
                host.get().setContent {
                    Box(Modifier.width(width.value).height(900.dp)) {
                        MeshCoreTheme(motionScale = 0f) {
                            NativeNavigationShell(owner.value) { destination, _ -> FixtureContent(destination) }
                        }
                    }
                }
            }
            compose.onNodeWithTag(if (windowWidth < 600) "bottom-navigation" else "navigation-rail")
                .assertIsDisplayed()
            compose.waitForIdle()
            assertEquals(windowWidth.toFloat(), first.state.value.widthDp, 0f)
            val retired = first.state.value

            compose.runOnIdle { owner.value = replacement }
            compose.waitForIdle()
            assertEquals(windowWidth.toFloat(), replacement.state.value.widthDp, 0f)
            assertEquals(retired, first.state.value)

            compose.runOnIdle { width.value = (windowWidth + 1).dp }
            compose.waitForIdle()
            assertEquals((windowWidth + 1).toFloat(), replacement.state.value.widthDp, 0f)
            assertEquals(retired, first.state.value)
        }
    }

    @Test fun fiveNativeTabsExposeSelectionHeadingUnreadAndFortyEightDpTargets() {
        for (tab in AppTab.entries) {
            compose.onNodeWithTag("tab:${tab.name}").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
            compose.onNodeWithTag("tab:${tab.name}").assertIsSelected()
            compose.onNodeWithTag("navigation-heading").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            compose.onNodeWithTag("fixture:root-${tab.name}").assertIsDisplayed()
        }
        compose.onNodeWithTag("tab:CHATS").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
        compose.onNodeWithTag("open-radio-setup").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        capture("tabs-selected-settings")
    }
    @Test fun inactiveTabDraftAndDetailFocusSurviveWindowResizeWithoutAnotherConnectionOwner() {
        compose.onNodeWithTag("editor:root-CHATS").performTextInput("retained draft")
        compose.onNodeWithTag("tab:NODES").performClick()
        compose.onNodeWithTag("tab:CHATS").performClick()
        compose.onNodeWithTag("editor:root-CHATS").assertTextContains("retained draft")
        compose.runOnIdle { navigation.navigateToSetting(SettingsDetail.LANGUAGE) }
        compose.onNodeWithTag("editor:setting-language").performClick().performTextInput("detail draft")
        compose.onNodeWithTag("editor:setting-language").assertIsFocused()
        val entry = navigation.state.value.activeStack.last()
        compose.runOnIdle { width.value = 834.dp }
        compose.onNodeWithTag("editor:setting-language").assertTextContains("detail draft").assertIsFocused()
        assertEquals(entry, navigation.state.value.activeStack.last())
        capture("resize-expanded-draft")
        compose.runOnIdle { width.value = 360.dp }
        compose.onNodeWithTag("editor:setting-language").assertTextContains("detail draft").assertIsFocused()
        assertEquals(entry, navigation.state.value.activeStack.last())
        capture("resize-compact-draft")
    }
    @Test fun collapsingToolsShowOnlyDetailAndNativeBackRestoresSectionNavigation() {
        for (value in listOf(744, 834)) {
            compose.runOnIdle { width.value = value.dp; navigation.navigateToTool(ToolSelection.CLI) }
            compose.onNodeWithTag("navigation-rail").assertIsDisplayed()
            compose.onNodeWithTag("fixture:tool-cli").assertIsDisplayed()
            if (value >= NavigationLayout.TILE_MIN_WIDTH_DP) {
                compose.onNodeWithTag("navigation-list-pane").assertIsDisplayed()
            } else {
                compose.onNodeWithTag("single-pane").assertIsDisplayed()
                compose.onNodeWithTag("navigation-list-pane").assertIsNotDisplayed()
            }
            for (tool in listOf(ToolSelection.TRACE_PATH, ToolSelection.LINE_OF_SIGHT)) {
                compose.runOnIdle { navigation.navigateToTool(tool) }
                compose.onNodeWithTag("navigation-hidden").assertIsDisplayed()
                compose.onNodeWithTag("single-pane").assertIsDisplayed()
                compose.onNodeWithTag("navigation-list-pane").assertIsNotDisplayed()
                compose.onNodeWithTag("fixture:tool-${tool.sourceName}").assertIsDisplayed()
                if (value == 834) capture("collapse-${tool.sourceName.lowercase()}")
            }
        }
        compose.onNodeWithTag("navigation-back").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(ToolSelection.TRACE_PATH, navigation.state.value.selectedTool)
    }
    @Test fun actualActivityBackDispatcherPopsDetailThenReturnsToChats() {
        for (value in listOf(360, 834)) {
            compose.runOnIdle { width.value = value.dp; navigation.navigateToSetting(SettingsDetail.LANGUAGE) }
            compose.onNodeWithTag("fixture:setting-language").assertIsDisplayed()
            compose.onNodeWithTag("editor:setting-language").performClick().performTextInput("cancelled Back draft")
            val before = navigation.state.value
            compose.runOnUiThread {
                backInput.backStarted(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, touchY = 100f))
                backInput.backProgressed(NavigationEvent(
                    swipeEdge = NavigationEvent.EDGE_LEFT, touchX = 80f, touchY = 100f, progress = 0.5f,
                ))
            }
            compose.waitForIdle()
            assertEquals("Progress must not commit the stack", before, navigation.state.value)
            compose.runOnUiThread { backInput.backCancelled() }
            compose.onNodeWithTag("editor:setting-language").assertTextContains("cancelled Back draft").assertIsFocused()
            assertEquals("Cancellation must preserve selection and entry identities", before, navigation.state.value)
            compose.runOnUiThread {
                backInput.backStarted(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, touchY = 100f))
                backInput.backProgressed(NavigationEvent(
                    swipeEdge = NavigationEvent.EDGE_LEFT, touchX = 120f, touchY = 100f, progress = 0.8f,
                ))
            }
            compose.runOnUiThread { backInput.backCompleted() }
            compose.onNodeWithTag("fixture:root-SETTINGS").assertIsDisplayed()
            assertNull(navigation.state.value.selectedSetting)
            compose.runOnUiThread { host.get().onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("fixture:root-CHATS").assertIsDisplayed()
            assertEquals(AppTab.CHATS, navigation.state.value.selectedTab)
        }
        capture("native-back-root")
    }

    @Test fun mountedBackLeavesThePresentedEntryInsteadOfAConcurrentUnseenRoute() {
        val radio = RadioId(UUID(0, 60))
        val contact = ContactDTO(
            radioId = radio, publicKey = Bytes(ByteArray(32) { 2 }), name = "Presented chat",
            lastHeardTimestamp = null,
        )
        val channel = ChannelDTO(radioId = radio, index = 2u, name = "Unseen channel")
        for (windowWidth in listOf(390, 834)) for (deferPublication in listOf(false, true)) {
            compose.runOnIdle {
                width.value = windowWidth.dp
                navigation.navigateToSetting(SettingsDetail.LANGUAGE)
                navigation.navigateToChat(contact)
            }
            compose.onNodeWithTag("fixture:private-detail").assertIsDisplayed()
            val settings = navigation.state.value.stacks.getValue(AppTab.SETTINGS)
            val presented = navigation.publishedNavigation.value
            val gate = if (deferPublication) {
                navigation.registerChatContactMount(ChatContactMount(presented.value.activeStack.last().id))
            } else null
            try {
                compose.onNodeWithTag("navigation-back").performSemanticsAction(SemanticsActions.OnClick) { click ->
                    navigation.navigateToChannel(channel)
                    assertEquals(ChatSelection.Channel(channel), navigation.currentState.chatsSelectedRoute)
                    if (deferPublication) assertEquals(presented, navigation.publishedNavigation.value)
                    else assertEquals(ChatSelection.Channel(channel), navigation.state.value.chatsSelectedRoute)
                    assertTrue(requireNotNull(click)())
                }
                assertEquals(1, navigation.currentState.activeStack.size)
                assertNull(navigation.currentState.chatsSelectedRoute)
                assertEquals(settings, navigation.currentState.stacks.getValue(AppTab.SETTINGS))
                if (deferPublication) {
                    assertEquals(presented, navigation.publishedNavigation.value)
                    compose.onNodeWithTag("fixture:private-detail").assertIsDisplayed()
                }
            } finally {
                compose.runOnUiThread { gate?.release() }
            }
            compose.onNodeWithTag("fixture:root-CHATS").assertIsDisplayed()
            assertNull(navigation.state.value.chatsSelectedRoute)
            assertEquals(settings, navigation.state.value.stacks.getValue(AppTab.SETTINGS))
        }
    }

    @Test fun predictiveBackPreviewCannotConsumeTheCurrentChatMessageScroll() {
        for (windowWidth in listOf(390, 834)) {
            val n = NavigationCoordinator()
            val radio = RadioId(UUID(0, 40))
            val contact = ContactDTO(
                radioId = radio, publicKey = Bytes(ByteArray(32) { 1 }), name = "Previous chat",
                lastHeardTimestamp = null,
            )
            val channel = ChannelDTO(radioId = radio, index = 1u, name = "Current channel")
            n.navigateToChat(contact)
            val previousEntry = n.state.value.activeStack.last().id
            n.navigateToChannel(channel, UUID(0, 41))
            val incoming = requireNotNull(n.state.value.pendingMessageScroll)
            val renderedEntries = CopyOnWriteArrayList<Long>()
            val requestOwners = CopyOnWriteArrayList<Long>()
            val consumed = CopyOnWriteArrayList<Long>()
            val release = CompletableDeferred<Unit>()
            var mountedDispatcher: NavigationEventDispatcher? = null
            try {
                compose.runOnUiThread {
                    width.value = windowWidth.dp
                    host.get().setContent {
                        mountedDispatcher = requireNotNull(LocalNavigationEventDispatcherOwner.current)
                            .navigationEventDispatcher
                        Box(Modifier.width(width.value).height(900.dp)) {
                            MeshCoreTheme(motionScale = 1f) {
                                NativeNavigationShell(n) { destination, _ ->
                                    val entryId = requireNotNull(LocalNavigationEntryId.current)
                                    val state by n.state.collectAsState()
                                    val request = state.pendingMessageScrollFor(entryId)
                                    if (destination is NavigationDestination.Chat) {
                                        SideEffect { renderedEntries += entryId }
                                        LaunchedEffect(request) {
                                            if (request != null) {
                                                requestOwners += entryId
                                                release.await()
                                                if (n.consumePendingMessageScroll(entryId, request)) consumed += entryId
                                            }
                                        }
                                        Text("Chat $entryId")
                                    }
                                }
                            }
                        }
                    }
                }
                compose.waitForIdle()
                val dispatcher = requireNotNull(mountedDispatcher)
                assertSame("The input must target the mounted NavDisplay owner", host.get().navigationEventDispatcher, dispatcher)
                assertTrue("The mounted dispatcher must expose Back history", dispatcher.history.value.currentIndex > 0)
                compose.waitUntil(5_000) { requestOwners.contains(incoming.entryId) }
                compose.waitForIdle()
                assertFalse("The previous entry must not be mounted before the preview", renderedEntries.contains(previousEntry))
                val before = n.state.value
                compose.runOnUiThread {
                    backInput.backStarted(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, touchY = 100f))
                }
                compose.waitForIdle()
                compose.runOnUiThread {
                    backInput.backProgressed(NavigationEvent(
                        swipeEdge = NavigationEvent.EDGE_LEFT, touchX = 120f, touchY = 100f, progress = 0.6f,
                    ))
                }
                val progress = dispatcher.transitionState.value
                assertTrue("The mounted dispatcher must receive the predictive gesture: $progress",
                    progress is NavigationEventTransitionState.InProgress)
                assertEquals(0.6f, (progress as NavigationEventTransitionState.InProgress).latestEvent.progress, 0f)
                compose.waitUntil(5_000) { renderedEntries.contains(previousEntry) }
                assertEquals(before, n.state.value)
                assertFalse(requestOwners.contains(previousEntry))
                assertTrue(consumed.isEmpty())
                compose.runOnUiThread { backInput.backCancelled() }
                compose.waitForIdle()
                assertEquals(before, n.state.value)
                assertEquals(incoming, n.state.value.pendingMessageScroll)
                assertFalse(requestOwners.contains(previousEntry))
                release.complete(Unit)
                compose.waitUntil(5_000) { n.state.value.pendingMessageScroll == null }
                compose.waitForIdle()
                assertEquals(listOf(incoming.entryId), consumed)
                assertEquals(before.activeStack, n.state.value.activeStack)
            } finally {
                release.complete(Unit)
                compose.runOnUiThread { host.get().setContent {} }
            }
        }
    }

    @Test fun identicalTimelineAnchorsRestartForANewDeliveryWithoutReplacingTheViewport() {
        val radio = RadioId(UUID(0, 50))
        val messages = (0 until 24).map { index ->
            val time = Instant.ofEpochSecond(1_000L + index)
            MessageDTO(
                id = UUID(0, 100L + index), radioId = radio, text = "Anchor message $index",
                timestamp = (1_000 + index).toUInt(), createdAt = time, sortDate = time,
            )
        }
        val target = messages[10]
        val rows = messages.map { TimelineRow.Message(it, startsGroup = true, endsGroup = true) }
        val timeline = ChatTimelineState(
            messages = messages, rows = rows, hasLoadedOnce = true, hasMoreMessages = false,
            initialAnchor = InitialTimelineAnchor.Message(target.id), initialAnchorConsumed = false,
        )
        val requestId = mutableLongStateOf(1)
        val consumed = CopyOnWriteArrayList<Long>()
        lateinit var list: LazyListState
        try {
            compose.runOnUiThread {
                host.get().setContent {
                    MeshCoreTheme(motionScale = 0f) {
                        val currentId = requestId.longValue
                        val currentList = rememberLazyListState()
                        SideEffect { list = currentList }
                        ChatTimeline(
                            timeline,
                            ChatTimelineCallbacks(
                                onLoadOlder = { error("The fixture has no older page") },
                                onRetry = { error("The fixture has no failed send") },
                                onInitialAnchorConsumed = { consumed += currentId },
                                onScrollPositionChanged = { _, _ -> },
                                onJumpToLatest = { error("The fixture does not jump to latest") },
                                onRetryLoad = { error("The fixture does not retry loading") },
                            ),
                            Modifier.size(width = 320.dp, height = 300.dp),
                            listState = currentList,
                            showSenderName = false,
                            showsIncomingAvatars = false,
                            initialAnchorRequestId = currentId,
                            messageBody = { message, _ -> Box(Modifier.height(100.dp)) { Text(message.text) } },
                        )
                    }
                }
            }
            compose.waitForIdle()
            compose.waitUntil(5_000) { consumed.contains(1L) }
            val originalList = list
            compose.runOnIdle {
                runBlocking { originalList.scrollToItem(0) }
                assertEquals(0, originalList.firstVisibleItemIndex)
                requestId.longValue = 2
            }
            compose.waitUntil(5_000) { consumed.contains(2L) }
            compose.runOnIdle {
                assertEquals(listOf(1L, 2L), consumed)
                assertSame(originalList, list)
                assertEquals(rows.asReversed().indexOfFirst { it.message.id == target.id }, list.firstVisibleItemIndex)
            }
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
        }
    }

    @Test fun twoHundredPercentFontRtlCjkAndShortRailKeepActionsAndTextAccessible() {
        compose.runOnIdle { font.floatValue = 2f; direction.value = LayoutDirection.Rtl }
        for (tab in AppTab.entries) {
            compose.onNodeWithTag("tab:${tab.name}").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            assertTextDoesNotOverflow("tab-label:${tab.name}")
        }
        assertTextDoesNotOverflow("navigation-heading")
        assertTextDoesNotOverflow("body:root-CHATS")
        assertTextDoesNotOverflow("navigation-unread-badge")
        capture("font200-rtl-cjk")
        compose.runOnIdle { width.value = 834.dp }
        for (tab in AppTab.entries) compose.onNodeWithTag("tab:${tab.name}").performScrollTo().performClick().assertIsSelected()
        capture("font200-rtl-rail")
    }
    @Test fun everyUnlockedThemeAndEffectiveSchemeRendersTheActualShellWithoutResettingTab() {
        compose.runOnIdle { width.value = 834.dp; navigation.selectTab(AppTab.SETTINGS) }
        val before = navigation.state.value.activeStack
        for (value in ThemeRegistry.allThemes) {
            val schemes = ColorScheme.entries.filter { value.preferredColorScheme == null || value.preferredColorScheme == it }
            for (scheme in schemes) for (contrast in listOf(false, true)) {
                compose.runOnIdle {
                    theme.value = value; highContrast.value = contrast
                    preference.value = if (scheme == ColorScheme.DARK) AppColorSchemePreference.DARK else AppColorSchemePreference.LIGHT
                }
                compose.onNodeWithTag("tab:SETTINGS").assertIsSelected()
                assertEquals(before, navigation.state.value.activeStack)
                capture("theme-${value.id.rawValue}-${scheme.name.lowercase()}-${if (contrast) "hc" else "standard"}")
            }
        }
    }
    private fun assertTextDoesNotOverflow(tag: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertEquals("The real native text must retain 200-percent scaling", 2f, layout.layoutInput.density.fontScale, 0f)
        assertFalse(
            "$tag must honor font scaling without clipping: size=${layout.size}, " +
                "text=${layout.layoutInput.text}, lines=${layout.lineCount}, " +
                "paragraphWidth=${layout.multiParagraph.width}, constraints=${layout.layoutInput.constraints}, " +
                "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}",
            layout.hasVisualOverflow,
        )
    }
    private fun capture(id: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = host.get().window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("Blank pixels are not native evidence", pixels.toSet().size > 16)
        val bytes = ByteArrayOutputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray()
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val name = "sdk-${Build.VERSION.SDK_INT}-$id"
        val directory = File(requireNotNull(System.getProperty("navigationArtifactDirectory")) {
            "The native App runner must declare navigationArtifactDirectory"
        })
        assertTrue(directory.isDirectory || directory.mkdirs()); File(directory, "$name.png").writeBytes(bytes)
        println("WP302_RENDER|$name|${bitmap.width}|${bitmap.height}|$hash|${Base64.getEncoder().encodeToString(bytes)}")
    }
}
