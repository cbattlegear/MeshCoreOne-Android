// AndroidOnly: WP-307 Mounted conversation long-press opens actions for the selected message.
package com.meshcoreone.android.app.navigation

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatMessageActionsConsumerTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    @Test fun directMessageLongPressOpensTheRealActionsSheet() = assertLongPressCopiesSelectedMessage(false)

    @Test fun channelMessageLongPressOpensTheRealActionsSheet() = assertLongPressCopiesSelectedMessage(true)

    private fun assertLongPressCopiesSelectedMessage(channelMessage: Boolean) = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val radio = requireNotNull(h.appState.currentRadioId)
            val contact = ContactDTO(
                radioId = radio, publicKey = Bytes(ByteArray(32) { 5 }), name = "Alice", lastHeardTimestamp = null,
            )
            store.saveContact(contact)
            val channel = if (channelMessage) {
                seedReconciledChannel(ChannelDTO(radioId = radio, index = 1u, name = "Channel"))
            } else null
            val message = MessageDTO(
                radioId = radio, contactID = if (channel == null) contact.id else null,
                channelIndex = channel?.index, channelOccupantID = channel?.occupantID,
                text = "Selected original message", timestamp = 100u,
                direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
                senderNodeName = "Alice",
            )
            store.saveMessage(message)
            if (channel == null) h.container.navigation.navigateToChat(contact)
            else h.container.navigation.navigateToChannel(channel)
            val destination = h.container.navigation.state.value.activeStack.last().destination
            compose.runOnUiThread {
                host.get().setContent {
                    MeshCoreTheme(motionScale = 0f) {
                        AppChatsContent(h.container.chatsFeature, destination, h.container.navigation)
                    }
                }
            }
            val tag = "timeline-bubble:${message.id}"
            compose.waitUntil(30_000) {
                h.settle()
                compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size == 1
            }
            val copy = host.get().getString(AppChatsStrings.chatsMessageActionCopy)
            compose.onNodeWithText(copy).assertDoesNotExist()
            compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput { longClick() }
            compose.waitUntil(30_000) {
                h.settle()
                compose.onAllNodesWithText(copy).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText(copy).performClick()
            compose.waitForIdle()
            val clipboard = host.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(message.text, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            compose.waitForIdle()
            h.container.close()
        }
    }
}
