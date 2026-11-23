// AndroidOnly: WP-307 Mounted conversation message actions and screen-owned deletion reconciliation.
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
import com.meshcoreone.android.core.contracts.domain.DirectTimelinePersisting
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryPersisting
import com.meshcoreone.android.core.contracts.domain.DisplayedChannelMessageMutating
import com.meshcoreone.android.core.contracts.domain.ChannelPersisting
import com.meshcoreone.android.core.contracts.domain.ChannelIdentityWriteScope
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.MessageWindow
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    @Test fun directReactionBadgeOpensSelectedDetails() = assertMountedReactionDetails(false)
    @Test fun channelReactionBadgeOpensSelectedDetails() = assertMountedReactionDetails(true)

    @Test fun deletingMessageReconcilesFromTheScreenAfterTheActionsSheetCloses() = verifyDeletion(false)
    @Test fun committedDeletionFinishesContactBookkeepingAfterTheActionsSheetCloses() = verifyDeletion(true)
    @Test fun committedDeletionFinishesContactBookkeepingAfterNavigationAway() = verifyDeletion(true, true)
    @Test fun reopenedChannelReceivesDeletionCommittedAfterTheOriginalScreenCloses() = verifyDeletion(true, true, true)

    private fun assertMountedReactionDetails(channelMessage: Boolean) = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val radio = requireNotNull(h.appState.currentRadioId)
            val contact = ContactDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 5 }),
                name = "Alice", lastHeardTimestamp = null)
            store.saveContact(contact)
            val channel = if (channelMessage) {
                seedReconciledChannel(ChannelDTO(radioId = radio, index = 1u, name = "Channel"))
            } else null
            val message = MessageDTO(
                radioId = radio, contactID = if (channel == null) contact.id else null,
                channelIndex = channel?.index, channelOccupantID = channel?.occupantID,
                text = "Reacted message", timestamp = 100u, senderNodeName = "Alice",
                direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
                reactionSummary = "👍:1,🎉:1",
            )
            store.saveMessage(message)
            listOf("👍" to "Thumb sender", "🎉" to "Party sender").forEach { (emoji, sender) ->
                store.saveReaction(ReactionDTO(messageID = message.id, emoji = emoji, senderName = sender,
                    messageHash = "hash", rawText = emoji, radioId = radio,
                    contactID = message.contactID, channelIndex = message.channelIndex))
            }
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
            compose.waitUntil(30_000) {
                h.settle()
                compose.onAllNodesWithContentDescription("🎉, 1").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithContentDescription("🎉, 1").performClick()
            compose.waitUntil(30_000) {
                h.settle()
                compose.onAllNodesWithText("Party sender").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText("Thumb sender").assertDoesNotExist()
            compose.onNodeWithText(host.get().getString(AppChatsStrings.reactionsTitle)).assertExists()
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            compose.waitForIdle()
            h.container.close()
        }
    }

    private fun verifyDeletion(
        parkDeletion: Boolean, navigateAway: Boolean = false, reopenChannel: Boolean = false,
    ) = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var park = false
        var recomputed = false
        val persistence = object : PersistenceStoreProtocol by store, DirectTimelinePersisting by store,
            ChannelHistoryPersisting by store, DisplayedChannelMessageMutating by store {
            override suspend fun <T> withChannelOperation(block: suspend (ChannelPersisting, ChannelIdentityWriteScope) -> T): T {
                if (reopenChannel && park && !entered.isCompleted) {
                    withContext(NonCancellable) { entered.complete(Unit); release.await() }
                }
                return store.withChannelOperation(block)
            }

            override suspend fun deleteMessage(key: EntityKey) {
                store.deleteMessage(key)
                if (park && parkDeletion) withContext(NonCancellable) { entered.complete(Unit); release.await() }
            }

            override suspend fun recomputeContactLastMessageDate(contact: EntityKey): Instant? {
                val date = store.recomputeContactLastMessageDate(contact)
                recomputed = true
                return date
            }

            override suspend fun fetchDirectTimelineWindow(contact: EntityKey, anchorSortDate: Instant?, floorLimit: Long): MessageWindow {
                val window = store.fetchDirectTimelineWindow(contact, anchorSortDate, floorLimit)
                if (park && !parkDeletion) withContext(NonCancellable) { entered.complete(Unit); release.await() }
                return window
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val radio = requireNotNull(h.appState.currentRadioId)
            val contact = ContactDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 5 }),
                name = "Alice", lastHeardTimestamp = null)
            store.saveContact(contact)
            val channel = if (reopenChannel) seedReconciledChannel(ChannelDTO(radioId = radio, index = 1u, name = "Channel")) else null
            val message = MessageDTO(radioId = radio, contactID = if (channel == null) contact.id else null,
                channelIndex = channel?.index, channelOccupantID = channel?.occupantID, senderNodeName = "Alice",
                text = "Delete this message", timestamp = 100u, direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED)
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
            compose.onNodeWithText(message.text, useUnmergedTree = true).performTouchInput { longClick() }
            val delete = host.get().getString(AppChatsStrings.chatsMessageActionDelete)
            compose.waitUntil(30_000) { h.settle(); compose.onAllNodesWithText(delete).fetchSemanticsNodes().size == 1 }
            park = true
            compose.onNodeWithText(delete).performClick()
            compose.waitUntil(30_000) { h.settle(); entered.isCompleted }
            if (!reopenChannel) assertNull(store.fetchMessage(EntityKey(radio, message.id)))
            // Exercise explicit dismissal on implementations that still keep the sheet open during refresh.
            if (compose.onAllNodesWithText(delete).fetchSemanticsNodes().isNotEmpty()) {
                compose.runOnUiThread { host.get().onBackPressedDispatcher.onBackPressed() }
            }
            compose.waitForIdle()
            compose.onNodeWithText(delete).assertDoesNotExist()
            if (navigateAway) {
                h.container.navigation.back()
                compose.runOnUiThread { host.get().setContent {} }
                compose.waitForIdle()
            }
            if (channel != null) {
                h.container.navigation.navigateToChannel(channel)
                val reopened = h.container.navigation.state.value.activeStack.last().destination
                compose.runOnUiThread {
                    host.get().setContent {
                        MeshCoreTheme(motionScale = 0f) {
                            AppChatsContent(h.container.chatsFeature, reopened, h.container.navigation)
                        }
                    }
                }
                compose.waitUntil(30_000) {
                    h.settle()
                    compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size == 1
                }
            }
            release.complete(Unit)
            if (channel != null) {
                compose.waitUntil(30_000) {
                    h.settle()
                    compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
                }
                assertNull(store.fetchMessage(EntityKey(radio, message.id)))
                return@runTest
            }
            compose.waitUntil(30_000) { h.settle(); recomputed }
            assertNull(store.fetchContact(EntityKey(radio, contact.id))?.lastMessageDate)
            if (navigateAway) return@runTest
            compose.waitUntil(30_000) {
                h.settle()
                compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
            assertEquals(destination, h.container.navigation.state.value.activeStack.last().destination)
            org.junit.Assert.assertTrue("Committed deletion must finish contact bookkeeping", recomputed)
        } finally {
            release.complete(Unit)
            compose.runOnUiThread { host.get().setContent {} }
            compose.waitForIdle()
            h.container.close()
        }
    }

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
            compose.onNodeWithText(message.text, useUnmergedTree = true).performTouchInput { longClick() }
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
