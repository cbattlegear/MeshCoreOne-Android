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
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.feature.chats.composer.ui.MessageBody
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineState
import com.meshcoreone.android.feature.chats.timeline.TimelineRow
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h800dp")
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
        compose.runOnIdle { assertEquals(emptyList<UUID>(), selected) }
        compose.onNodeWithTag("timeline-bubble:${direct.id}", useUnmergedTree = true)
            .performTouchInput { longClick() }
        compose.onNodeWithText(channel.text, useUnmergedTree = true)
            .performTouchInput { longClick() }
        compose.onNodeWithText(channel.text, useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithTag("timeline-bubble:${direct.id}", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            assertEquals(listOf(direct.id, channel.id, direct.id), selected)
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
        val row = compose.onNodeWithContentDescription(narrative)
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
}
