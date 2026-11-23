// AndroidOnly: WP-309 Mounted timeline reaction badges and selected-message details.
package com.meshcoreone.android.app.platform

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.feature.chats.actions.MessageActionsDataSource
import com.meshcoreone.android.feature.chats.actions.MessageActionsStateHolder
import com.meshcoreone.android.feature.chats.actions.ReactionDetailsRoute
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineState
import com.meshcoreone.android.feature.chats.timeline.TimelineRow
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimeline
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimelineCallbacks
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h800dp")
class TimelineReactionBadgesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun summariesUpdateAndOpenDetailsForTheSelectedMessage() {
        val radio = RadioId(UUID(0, 100))
        val direct = MessageDTO(id = UUID(0, 1), radioId = radio, contactID = UUID(0, 9),
            text = "Direct", timestamp = 1u, direction = MessageDirection.INCOMING)
        val channel = direct.copy(id = UUID(0, 2), contactID = null, channelIndex = 1u,
            text = "Channel", senderNodeName = "Alice", reactionSummary = "😀:2,🎉:1,❤️:1,👍:1")
        val messages = mutableStateOf(listOf(direct, channel))
        val requested = mutableListOf<UUID>()
        var actions: UUID? = null
        var linkClicked = false
        val source = object : MessageActionsDataSource {
            override suspend fun contacts(radioId: RadioId) = emptyList<ContactDTO>()
            override suspend fun discoveredNodes(radioId: RadioId) = emptyList<DiscoveredNodeDTO>()
            override suspend fun repeats(messageId: UUID) = emptyList<MessageRepeatDTO>()
            override suspend fun reactions(messageId: UUID): List<ReactionDTO> {
                requested += messageId
                val emojis = if (messageId == direct.id) listOf("🔥") else listOf("😀", "😀", "🎉", "❤️", "👍")
                return emojis.mapIndexed { index, emoji ->
                    ReactionDTO(messageID = messageId, emoji = emoji, senderName = "${messageId.leastSignificantBits}-$index",
                        messageHash = "hash", rawText = emoji, radioId = radio, receivedAt = Instant.ofEpochSecond(index.toLong()))
                }
            }
            override suspend fun blockChannelSender(radioId: RadioId, senderName: String) = Unit
            override suspend fun blockContacts(contactIds: Set<UUID>) = Unit
            override suspend fun setMuted(contactId: UUID, muted: Boolean) = Unit
        }
        compose.runOnUiThread {
            compose.activity.setContent {
                MeshCoreTheme(motionScale = 0f) {
                    val scope = rememberCoroutineScope()
                    var selection by remember { mutableStateOf<Pair<MessageDTO, String?>?>(null) }
                    val current = messages.value
                    val state = ChatTimelineState(messages = current,
                        rows = current.map { TimelineRow.Message(it, true, true) },
                        hasLoadedOnce = true, initialAnchorConsumed = true, hasMoreMessages = false)
                    ChatTimeline(state, ChatTimelineCallbacks({}, {}, {}, { _, _ -> }, {}, {}),
                        onMessageActions = { actions = it.id },
                        onReactionDetails = { message, emoji -> selection = message to emoji },
                        messageBody = { message, _ ->
                            Text(message.text)
                            TextButton(onClick = { linkClicked = true }) { Text("Link ${message.text}") }
                        })
                    selection?.let { (message, emoji) ->
                        val holder = remember(message.id) { MessageActionsStateHolder(source, scope) }
                        ReactionDetailsRoute(message.id, emoji, holder, onDismiss = { selection = null })
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("🔥, 1").assertDoesNotExist()
        compose.onNodeWithContentDescription("🎉, 1").performScrollTo()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.waitUntil(5_000) { requested.isNotEmpty() }
        compose.onNodeWithText("2-2").assertExists()
        compose.runOnIdle { assertEquals(channel.id, requested.last()) }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("1 more reaction types").performScrollTo().performClick()
        compose.onNodeWithText("2-0").assertExists()
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { messages.value = listOf(direct.copy(reactionSummary = "🔥:1"), channel) }
        compose.onNodeWithContentDescription("🔥, 1").performScrollTo().performClick()
        compose.onNodeWithText("1-0").assertExists()
        compose.runOnIdle { assertEquals(direct.id, requested.last()) }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Link Direct").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, linkClicked) }
        compose.onNodeWithTag("timeline-bubble:${direct.id}", useUnmergedTree = true)
            .performScrollTo().performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(direct.id, actions) }
    }
}
