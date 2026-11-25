// AndroidOnly: WP-306 Mounted offline list menu and mention-only filtering; persistence is covered by real Room tests.
package com.meshcoreone.android.uitests

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.chats.list.*
import com.meshcoreone.android.feature.chats.list.ui.ChatsListCallbacks
import com.meshcoreone.android.feature.chats.list.ui.ChatsListScreen
import com.meshcoreone.android.feature.chats.list.ui.ConversationAction
import java.time.Clock
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationReadStateComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.windowFocusChanged(true)
    }

    @After fun closeHost() { host.pause().stop().destroy() }

    @Test fun offlinePinnedRowOffersUnpinThroughTheNativeMenu() {
        val contact = ContactDTO(radioId = RadioId(UUID.randomUUID()), publicKey = Bytes(ByteArray(32)),
            name = "Pinned Ada", typeRawValue = 1u, lastHeardTimestamp = null, isPinned = true)
        val row = Conversation.Direct(contact)
        val intents = mutableListOf<Pair<Conversation, ConversationAction>>()
        host.get().setContent {
            MeshCoreTheme(motionScale = 0f) {
                ChatsListScreen(ChatListState(hasLoadedOnce = true, snapshot = ConversationSnapshot(listOf(row), emptyList())),
                    false, null, Clock.systemUTC(),
                    ChatsListCallbacks({}, {}, {}, { captured, action -> intents += captured to action }, {}),
                    { UiText.Verbatim("Failure") })
            }
        }
        compose.onNode(hasContentDescription("Pinned Ada", substring = true) and hasClickAction())
            .assertIsDisplayed().performTouchInput { longClick() }
        compose.onNodeWithText("Unpin").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(row to ConversationAction.FAVORITE), intents) }
    }

    @Test fun offlineMentionOnlyRowOffersExplicitReadThenUnreadAndRespectsUnreadFilter() {
        val contact = ContactDTO(radioId = RadioId(UUID.randomUUID()), publicKey = Bytes(ByteArray(32)),
            name = "Ada", typeRawValue = 1u, lastHeardTimestamp = null, unreadMentionCount = 1)
        val captured = Conversation.Direct(contact)
        val state = mutableStateOf(ChatListState(hasLoadedOnce = true,
            snapshot = ConversationSnapshot(emptyList(), listOf(captured))))
        val intents = mutableListOf<Pair<Conversation, ConversationAction>>()
        host.get().setContent {
            MeshCoreTheme(motionScale = 0f) {
                ChatsListScreen(state.value, false, null, Clock.systemUTC(),
                    ChatsListCallbacks({}, {}, {}, { row, action -> intents += row to action }, {}),
                    { UiText.Verbatim("Failure") })
            }
        }
        fun row() = compose.onNode(hasContentDescription("Ada", substring = true) and hasClickAction())
        row().assertIsDisplayed().performTouchInput { longClick() }
        compose.onNodeWithText("Mark as Read").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(captured to ConversationAction.MARK_READ), intents)
            state.value = state.value.copy(snapshot = ConversationSnapshot(emptyList(),
                listOf(Conversation.Direct(contact.copy(unreadMentionCount = 0)))))
        }
        row().performTouchInput { longClick() }
        compose.onNode(hasText("Unread") and hasAnyAncestor(isPopup()))
            .assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(ConversationAction.MARK_UNREAD, intents.last().second)
            state.value = state.value.copy(selectedFilter = ChatFilter.UNREAD)
        }
        row().assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(snapshot = ConversationSnapshot(emptyList(), listOf(captured)))
        }
        row().assertIsDisplayed()
    }
}
