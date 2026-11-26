// AndroidOnly: WP-306 Compact filter and row-menu presentation retains native input and accessibility behavior.
package com.meshcoreone.android.app.platform

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.chats.list.ChatFilter
import com.meshcoreone.android.feature.chats.list.ChatListState
import com.meshcoreone.android.feature.chats.list.Conversation
import com.meshcoreone.android.feature.chats.list.ConversationSnapshot
import com.meshcoreone.android.feature.chats.list.ui.ChatsListCallbacks
import com.meshcoreone.android.feature.chats.list.ui.ChatsListScreen
import com.meshcoreone.android.feature.chats.list.ui.ConversationAction
import java.time.Clock
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
@Config(sdk = [31], qualifiers = "w411dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ChatControlsPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val state = mutableStateOf(ChatListState(hasLoadedOnce = true))
    private val actions = mutableListOf<ConversationAction>()
    private var opened = 0
    private lateinit var inputMode: InputModeManager
    private val contact = ContactDTO(
        radioId = RadioId(UUID(0, 306)), publicKey = Bytes(ByteArray(32) { 1 }),
        name = "Alice Chen", lastHeardTimestamp = 0u,
    )

    private fun mount(connected: Boolean = true, rowContact: ContactDTO = contact) {
        state.value = state.value.copy(snapshot = ConversationSnapshot(emptyList(), listOf(Conversation.Direct(rowContact))))
        compose.runOnUiThread {
            compose.activity.setContent {
                inputMode = LocalInputModeManager.current
                MeshCoreTheme(motionScale = 0f) {
                    ChatsListScreen(
                        state.value, connected, null, Clock.systemUTC(),
                        ChatsListCallbacks(
                            { state.value = state.value.copy(searchText = it) },
                            { state.value = state.value.copy(selectedFilter = it) },
                            { opened++ }, { _, action -> actions += action }, {},
                        ), { UiText.Verbatim("Controlled failure") }, Modifier,
                    )
                }
            }
        }
    }

    @Test fun mentionOnlyUnreadUsesTheSameTitleEmphasisAsUnreadMessages() {
        mount(rowContact = contact.copy(unreadCount = 0, unreadMentionCount = 1))
        fun titleWeight(): FontWeight? {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Alice Chen", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            return layouts.single().layoutInput.style.fontWeight
        }
        assertEquals(FontWeight.SemiBold, titleWeight())
        compose.runOnIdle {
            state.value = state.value.copy(snapshot = ConversationSnapshot(emptyList(), listOf(Conversation.Direct(contact))))
        }
        assertEquals(FontWeight.Normal, titleWeight())
    }

    @Test fun textTabsKeepSelectionAndSearchDisablesCategoryChanges() {
        mount()
        val unread = compose.onNodeWithText(compose.activity.getString(AppChatsStrings.chatsFilterUnread))
        unread.assertIsDisplayed().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(ChatFilter.UNREAD, state.value.selectedFilter) }
        compose.onNode(hasSetTextAction()).performTextInput("Alice")
        unread.assertIsNotEnabled().assertIsSelected()
        compose.onNodeWithText("Alice Chen").assertIsDisplayed()
        compose.runOnIdle { assertEquals(ChatFilter.UNREAD, state.value.selectedFilter) }
    }

    @Test fun rowHasNoRepeatedOverflowButSupportsKeyboardMenuAndTap() {
        mount()
        compose.onNodeWithContentDescription(compose.activity.getString(AppContactsStrings.contactsDiscoveryMenu))
            .assertDoesNotExist()
        val row = compose.onNodeWithText("Alice Chen")
        row.performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        row.performSemanticsAction(SemanticsActions.RequestFocus)
        row.assertIsFocused()
        row.performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.F10)
            keyUp(Key.ShiftLeft)
        }
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(AppChatsStrings.chatsActionMute))
            .assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(ConversationAction.MUTE), actions) }
    }

    @Test fun rowRetainsSwipeLongPressAndEnabledAccessibilityActions() {
        mount(connected = false)
        val row = compose.onNodeWithText("Alice Chen")
        val config = row.fetchSemanticsNode().config
        assertEquals(
            setOf(compose.activity.getString(AppChatsStrings.chatsActionPin),
                compose.activity.getString(AppChatsStrings.chatsActionMarkAsUnread)),
            config[SemanticsActions.CustomActions].map { it.label }.toSet(),
        )
        row.performTouchInput { longClick() }
        compose.onNodeWithText(compose.activity.getString(AppChatsStrings.chatsActionDelete))
            .assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(AppChatsStrings.chatsActionMute))
            .assertIsNotEnabled()
    }

    @Test fun swipeAndAccessibilityActionsKeepTheirOriginalTargets() {
        mount()
        val row = compose.onNodeWithText("Alice Chen")
        val mute = row.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == compose.activity.getString(AppChatsStrings.chatsActionMute) }
        compose.runOnIdle { assertTrue(mute.action()) }
        row.performTouchInput { swipeRight() }
        compose.onNodeWithText(compose.activity.getString(AppChatsStrings.chatsActionPin))
            .assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(ConversationAction.MUTE, ConversationAction.FAVORITE), actions) }
    }
}
