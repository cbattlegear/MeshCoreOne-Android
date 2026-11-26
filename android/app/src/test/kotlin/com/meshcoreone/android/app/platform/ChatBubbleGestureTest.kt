// AndroidOnly: WP-307 Mounted direct/channel bubble actions and link gesture arbitration.
package com.meshcoreone.android.app.platform

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.input.TextFieldValue
import com.meshcoreone.android.feature.chats.composer.ComposerUiState
import com.meshcoreone.android.feature.chats.composer.ui.ComposerBar
import com.meshcoreone.android.feature.chats.composer.ui.ComposerBarActions
import com.meshcoreone.android.feature.chats.composer.ui.ShareAvailability
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.contracts.domain.ProcessEpoch
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.feature.chats.composer.ui.MessageBody
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineState
import com.meshcoreone.android.feature.chats.timeline.TimelineRow
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimeline
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimelineCallbacks
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ChatBubbleGestureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun longPressSelectsTheMessageWhileOrdinaryLinkTapsStillOpen() {
        val radio = RadioId(UUID(0, 307))
        val direct = MessageDTO(
            id = UUID(0, 1), radioId = radio, text = "Direct message", timestamp = 1u,
            direction = MessageDirection.OUTGOING,
        )
        val channel = MessageDTO(
            id = UUID(0, 2), radioId = radio, text = "https://example.com", timestamp = 2u,
            channelIndex = 0u, direction = MessageDirection.INCOMING, senderNodeName = "Alice",
        )
        val messages = listOf(direct, channel)
        val state = ChatTimelineState(
            messages = messages,
            rows = messages.map { TimelineRow.Message(it, startsGroup = true, endsGroup = true) },
            hasLoadedOnce = true,
            hasMoreMessages = false,
        )
        val selected = mutableListOf<UUID>()
        val opened = mutableListOf<String>()
        val callbacks = ChatTimelineCallbacks({}, {}, {}, { _, _ -> }, {}, {})
        compose.runOnUiThread {
            compose.activity.setContent {
                MeshCoreTheme(motionScale = 0f) {
                    ChatTimeline(
                        state, callbacks, Modifier.size(320.dp, 400.dp),
                        onMessageActions = { selected += it.id },
                        messageBody = { message, outgoing ->
                            MessageBody(message.text, outgoing, null, onLink = { opened += it })
                        },
                    )
                }
            }
        }

        compose.onNodeWithTag("timeline-bubble:${direct.id}", useUnmergedTree = true)
            .performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf(direct.id), selected) }
        compose.onNodeWithTag("timeline-bubble:${direct.id}", useUnmergedTree = true)
            .performTouchInput { longClick() }
        compose.onNodeWithText(channel.text, useUnmergedTree = true)
            .performTouchInput { longClick() }
        compose.onNodeWithText(channel.text, useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithTag("timeline-bubble:${direct.id}", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            assertEquals(listOf(direct.id, direct.id, channel.id, direct.id), selected)
            assertEquals(listOf(channel.text), opened)
        }
    }

    @Test
    fun replacingChannelIdentityDuringHoldCancelsThePendingAction() {
        val original = MessageDTO(
            id = UUID(0, 3), radioId = RadioId(UUID(0, 307)), text = "Original", timestamp = 3u,
            channelIndex = 1u, channelOccupantID = UUID(0, 10), channelReactionIncarnation = "first",
            direction = MessageDirection.INCOMING,
        )
        val current = mutableStateOf(original)
        val selected = mutableListOf<MessageDTO>()
        compose.runOnUiThread {
            compose.activity.setContent {
                MeshCoreTheme(motionScale = 0f) {
                    val message = current.value
                    ChatTimeline(
                        timelineState(listOf(message)), emptyCallbacks(), Modifier.size(320.dp, 400.dp),
                        onMessageActions = { selected += it },
                    )
                }
            }
        }

        for (replacement in listOf(
            original.copy(channelOccupantID = UUID(0, 11)),
            original.copy(channelReactionIncarnation = "second"),
        )) {
            compose.runOnIdle { current.value = original }
            val bubble = compose.onNodeWithTag("timeline-bubble:${original.id}", useUnmergedTree = true)
            bubble.performTouchInput { down(center) }
            compose.runOnIdle {
                assertTrue(selected.isEmpty())
                current.value = replacement
            }
            bubble.performTouchInput { advanceEventTime(1_000); up() }
            compose.runOnIdle { assertTrue(selected.isEmpty()) }
        }
        compose.onNodeWithTag("timeline-bubble:${original.id}", useUnmergedTree = true)
            .performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(listOf(current.value), selected) }
    }

    @Test
    fun holdAcrossSessionReplacementKeepsTheOriginalConversation() {
        val radio = RadioId(UUID(0, 307))
        val channel = ChannelDTO(radioId = radio, index = 1u, name = "Channel")
        val first = SessionToken(ProcessEpoch(UUID(0, 1)), Generation(1), radio)
        val second = first.copy(generation = Generation(2))
        val session = mutableStateOf(first)
        val message = MessageDTO(
            id = UUID(0, 6), radioId = radio, text = "Channel message", timestamp = 6u,
            channelIndex = channel.index, channelOccupantID = channel.occupantID,
            direction = MessageDirection.INCOMING,
        )
        val selected = mutableListOf<TimelineConversation.Channel>()
        compose.runOnUiThread {
            compose.activity.setContent {
                MeshCoreTheme(motionScale = 0f) {
                    val conversation = TimelineConversation.Channel(channel, session.value)
                    ChatTimeline(
                        timelineState(listOf(message)), emptyCallbacks(), Modifier.size(320.dp, 400.dp),
                        onMessageActions = { selected += conversation },
                    )
                }
            }
        }
        val bubble = compose.onNodeWithTag("timeline-bubble:${message.id}", useUnmergedTree = true)
        bubble.performTouchInput { down(center) }
        compose.runOnIdle {
            assertTrue(selected.isEmpty())
            session.value = second
        }
        bubble.performTouchInput { advanceEventTime(1_000); up() }
        compose.runOnIdle { assertEquals(listOf(TimelineConversation.Channel(channel, first)), selected) }
        bubble.performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(
                listOf(TimelineConversation.Channel(channel, first), TimelineConversation.Channel(channel, second)),
                selected,
            )
        }
    }

    @Test
    fun mergedMessageSemanticsExposeDetailsLinksAndIndependentRetry() {
        val message = MessageDTO(
            id = UUID(0, 4), radioId = RadioId(UUID(0, 307)), text = "https://example.com",
            timestamp = 4u, direction = MessageDirection.OUTGOING, status = MessageStatus.FAILED,
        )
        val selected = mutableListOf<UUID>()
        val retried = mutableListOf<UUID>()
        val opened = mutableListOf<String>()
        val narrative = "Message narrative"
        compose.runOnUiThread {
            compose.activity.setContent {
                MeshCoreTheme(motionScale = 0f) {
                    ChatTimeline(
                        timelineState(listOf(message)),
                        emptyCallbacks().copy(onRetry = { retried += it.id }),
                        Modifier.size(320.dp, 400.dp),
                        messageAccessibilityLabel = { narrative },
                        onMessageActions = { selected += it.id },
                        messageBody = { item, outgoing ->
                            MessageBody(item.text, outgoing, null, onLink = { opened += it })
                        },
                    )
                }
            }
        }
        val time = android.text.format.DateFormat.getTimeFormat(compose.activity).format(java.util.Date.from(message.senderDate))
        val label = "$narrative. ${AppChatsStrings.chatsMessageSendTimeAccessibilityLabel(compose.activity.resources, time)}"
        val row = compose.onNodeWithContentDescription(label)
        val config = row.fetchSemanticsNode().config
        assertEquals(
            compose.activity.getString(AppChatsStrings.chatsMessageActionDetails),
            config[SemanticsActions.OnClick].label,
        )
        val linkLabel = AppChatsStrings.chatsMessageActionOpenWebLink(compose.activity.resources, "example.com")
        val link = config[SemanticsActions.CustomActions].single { it.label == linkLabel }
        row.performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertTrue(link.action()) }
        compose.onNodeWithText(compose.activity.getString(AppChatsStrings.chatsMessageStatusRetry))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            assertEquals(listOf(message.id), selected)
            assertEquals(listOf(message.id), retried)
            assertEquals(listOf(message.text), opened)
        }
    }

    @Test
    fun bubbleReceivesKeyboardFocusAndActivatesWithoutStealingNestedControl() {
        val message = MessageDTO(
            id = UUID(0, 10), radioId = RadioId(UUID(0, 307)), text = "Keyboard message", timestamp = 10u,
        )
        val selected = mutableListOf<UUID>()
        var nested = 0
        lateinit var inputMode: InputModeManager
        compose.runOnUiThread {
            compose.activity.setContent {
                inputMode = LocalInputModeManager.current
                MeshCoreTheme(motionScale = 0f) {
                    Column {
                        Button(onClick = {}) { Text("Before timeline") }
                        ChatTimeline(
                            timelineState(listOf(message)), emptyCallbacks(), Modifier.size(320.dp, 250.dp),
                            onMessageActions = { selected += it.id },
                            messageBody = { _, _ -> Button(onClick = { nested++ }) { Text("Nested action") } },
                        )
                    }
                }
            }
        }
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        val before = compose.onNodeWithText("Before timeline")
        before.performSemanticsAction(SemanticsActions.RequestFocus)
        before.performKeyInput { pressKey(Key.Tab) }
        val bubble = compose.onNodeWithTag("timeline-bubble:${message.id}", useUnmergedTree = true)
        bubble.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        bubble.performKeyInput { pressKey(Key.Spacebar) }
        bubble.performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithText("Nested action").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(listOf(message.id, message.id), selected); assertEquals(1, nested) }
    }

    @Test
    fun correctedTimeIsVisibleAndAnnouncedWithBothNarrativeProviders() {
        val message = MessageDTO(
            id = UUID(0, 11), radioId = RadioId(UUID(0, 307)), text = "Clock adjusted",
            timestamp = 1234u, timestampCorrected = true, direction = MessageDirection.INCOMING,
        )
        val custom = mutableStateOf(false)
        compose.runOnUiThread {
            compose.activity.setContent {
                MeshCoreTheme(motionScale = 0f) {
                    ChatTimeline(
                        timelineState(listOf(message)), emptyCallbacks(), Modifier.size(320.dp, 400.dp),
                        messageAccessibilityLabel = if (custom.value) { _ -> "Provided narrative" } else null,
                    )
                }
            }
        }
        val time = android.text.format.DateFormat.getTimeFormat(compose.activity).format(java.util.Date.from(message.senderDate))
        val label = AppChatsStrings.chatsMessageSendTimeCorrectedAccessibilityLabel(compose.activity.resources, time)
        compose.onNodeWithContentDescription("Clock adjusted. $label").assertExists()
        compose.onNodeWithTag("corrected-time-indicator", useUnmergedTree = true).assertExists()
        compose.runOnIdle { custom.value = true }
        compose.onNodeWithContentDescription("Provided narrative. $label").assertExists()
    }

    @Test
    fun nestedControlTapAndHoldRemainDistinct() {
        val message = MessageDTO(
            id = UUID(0, 5), radioId = RadioId(UUID(0, 307)), text = "Nested body", timestamp = 5u,
        )
        val selected = mutableListOf<UUID>()
        var controlClicks = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                MeshCoreTheme(motionScale = 0f) {
                    ChatTimeline(
                        timelineState(listOf(message)), emptyCallbacks(), Modifier.size(320.dp, 400.dp),
                        onMessageActions = { selected += it.id },
                        messageBody = { _, _ ->
                            Column { Button(onClick = { controlClicks++ }) { Text("Nested control") } }
                        },
                    )
                }
            }
        }
        val control = compose.onNodeWithText("Nested control")
        control.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, controlClicks); assertTrue(selected.isEmpty()) }
        control.performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(1, controlClicks)
            assertEquals(listOf(message.id), selected)
        }
    }

    @Test
    fun draggingMessageScrollsWithoutOpeningActions() {
        val radio = RadioId(UUID(0, 307))
        val messages = (0 until 25).map { index ->
            MessageDTO(id = UUID(0, index.toLong()), radioId = radio, text = "Row $index", timestamp = index.toUInt())
        }
        val selected = mutableListOf<UUID>()
        val listState = LazyListState()
        compose.runOnUiThread {
            compose.activity.setContent {
                MeshCoreTheme(motionScale = 0f) {
                    ChatTimeline(
                        timelineState(messages), emptyCallbacks(), Modifier.size(320.dp, 240.dp),
                        listState = listState, onMessageActions = { selected += it.id },
                    )
                }
            }
        }
        compose.onNodeWithText("Row 22", useUnmergedTree = true)
            .performTouchInput { swipeDown(startY = centerY, endY = centerY + 120f, durationMillis = 250) }
        compose.runOnIdle {
            assertTrue(selected.isEmpty())
            assertTrue(listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
        }
    }

    private fun timelineState(messages: List<MessageDTO>) = ChatTimelineState(
        messages = messages,
        rows = messages.map { TimelineRow.Message(it, startsGroup = true, endsGroup = true) },
        hasLoadedOnce = true,
        hasMoreMessages = false,
    )

    private fun emptyCallbacks() = ChatTimelineCallbacks({}, {}, {}, { _, _ -> }, {}, {})

    @Test
    fun latestControlOccupiesItsOwnFooterInsteadOfCoveringTheMessage() {
        val message = MessageDTO(
            id = UUID(0, 9), radioId = RadioId(UUID(0, 307)),
            text = "Visible above the latest control", timestamp = 9u,
        )
        var jumps = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                MeshCoreTheme(motionScale = 0f) {
                    ChatTimeline(
                        timelineState(listOf(message)).copy(isAtLatest = false, newMessageCount = 2),
                        ChatTimelineCallbacks({}, {}, {}, { _, _ -> }, { jumps++ }, {}),
                        Modifier.size(320.dp, 240.dp),
                    )
                }
            }
        }
        val jump = compose.onNodeWithTag("timeline-jump-to-latest")
        val list = compose.onNodeWithTag("timeline-list")
        assertTrue("Jump footer must not overlap scrollable message content",
            list.fetchSemanticsNode().boundsInRoot.bottom <= jump.fetchSemanticsNode().boundsInRoot.top)
        jump.performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, jumps) }
    }

    @Test
    fun shortMessageKeepsTimeStatusAndDetailsWithoutASeparateButtonRow() {
        val message = MessageDTO(
            id = UUID(0, 8), radioId = RadioId(UUID(0, 307)), text = "See you at 9",
            timestamp = 1767225600u, status = MessageStatus.DELIVERED,
        )
        compose.runOnUiThread {
            compose.activity.setContent {
                MeshCoreTheme(motionScale = 0f) {
                    ChatTimeline(
                        timelineState(listOf(message)), emptyCallbacks(), Modifier.size(320.dp, 400.dp),
                        onMessageActions = {},
                    )
                }
            }
        }
        val bubble = compose.onNodeWithTag("timeline-bubble:${message.id}", useUnmergedTree = true)
        val bounds = bubble.fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("Compact short bubble must not add a 48dp Details row: ${bounds.height / density}dp",
            bounds.height / density <= 72f)
        assertTrue("The whole bubble remains an accessible target", bounds.height / density >= 48f)
        compose.onNodeWithText(compose.activity.getString(AppChatsStrings.chatsMessageStatusDelivered), useUnmergedTree = true)
            .assertExists()
        assertEquals(compose.activity.getString(AppChatsStrings.chatsMessageActionDetails),
            bubble.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        val time = android.text.format.DateFormat.getTimeFormat(compose.activity)
            .format(java.util.Date.from(message.senderDate))
        compose.onNodeWithText(time, useUnmergedTree = true).assertExists()
    }

    @Test
    fun composerKeepsEmojiShareAndEditingInOneCompactRow() {
        val field = mutableStateOf(TextFieldValue())
        var emojiOpened = 0
        val focus = FocusRequester()
        compose.runOnUiThread {
            compose.activity.setContent {
                MeshCoreTheme(motionScale = 0f) {
                    ComposerBar(
                        ComposerUiState(draft = field.value.text, maxBytes = 180, isConnected = true),
                        field.value, "Direct Message", ShareAvailability(location = false, myInfo = false),
                        ComposerBarActions(
                            onFieldValueChange = { field.value = it }, onSend = {},
                            onShareLocation = {}, onShareContact = {}, onShareMyInfo = {},
                            onOpenEmoji = { emojiOpened++ }, onDismissFailure = {},
                        ),
                        focus, Modifier.size(360.dp, 200.dp).testTag("composer"),
                    )
                }
            }
        }
        val emoji = compose.onNodeWithContentDescription(compose.activity.getString(AppChatsStrings.reactionsMoreEmojis))
        val send = compose.onNodeWithContentDescription(compose.activity.getString(AppChatsStrings.chatsInputSendMessage))
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("Emoji and send must occupy the same row",
            kotlin.math.abs(emoji.fetchSemanticsNode().boundsInRoot.center.y -
                send.fetchSemanticsNode().boundsInRoot.center.y) / density <= 8f)
        emoji.performClick()
        val label = compose.activity.getString(AppChatsStrings.chatsInputAccessibilityLabel)
        val encrypted = compose.activity.getString(AppChatsStrings.chatsInputEncrypted)
        compose.onNodeWithContentDescription("$label, $encrypted").performTextInput("Hello")
        compose.runOnIdle { assertEquals("Hello", field.value.text); assertEquals(1, emojiOpened) }
        compose.onNodeWithContentDescription(
            compose.activity.getString(AppChatsStrings.chatsInputShareButtonAccessibilityLabel),
        ).performClick()
        compose.onNodeWithText(compose.activity.getString(AppChatsStrings.chatsShareContact)).assertExists()
    }
}
