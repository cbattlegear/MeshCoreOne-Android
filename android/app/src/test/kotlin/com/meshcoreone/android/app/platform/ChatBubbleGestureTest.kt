// AndroidOnly: WP-307 Mounted direct/channel bubble actions and link gesture arbitration.
package com.meshcoreone.android.app.platform

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.feature.chats.composer.ui.MessageBody
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineState
import com.meshcoreone.android.feature.chats.timeline.TimelineRow
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimeline
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimelineCallbacks
import java.util.UUID
import org.junit.Assert.assertEquals
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
        compose.onNodeWithTag("timeline-bubble:${channel.id}", useUnmergedTree = true)
            .performTouchInput { longClick() }
        compose.onNodeWithText(channel.text, useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithTag("timeline-bubble:${direct.id}", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            assertEquals(listOf(direct.id, channel.id, direct.id), selected)
            assertEquals(listOf(channel.text), opened)
        }
    }
}
