// PortedFrom: MC1/Views/Chats/ChatConversationView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Sheets/BlockSenderSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/BlockedSenderMessageDeletionTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.actions.MessageActionsFailure
import com.meshcoreone.android.feature.chats.actions.MessageActionsDataSource
import com.meshcoreone.android.feature.chats.actions.MessageActionsRoute
import com.meshcoreone.android.feature.chats.actions.MessageActionsStateHolder
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimelineRoute
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BlockedSenderMountedStoreTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private val radio = RadioId(UUID(0, 1))
    private val message = MessageDTO(
        radioId = radio, channelIndex = 0u, text = "History to block", timestamp = 1_700_000_000u,
        createdAt = Instant.ofEpochSecond(1_700_000_000), direction = MessageDirection.INCOMING,
        status = MessageStatus.DELIVERED, senderNodeName = "Spammer",
    )

    private fun mount(holder: MessageActionsStateHolder, timeline: ChatViewModel? = null): ActivityController<ComponentActivity> {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        host.get().setContent {
            MeshCoreTheme(motionScale = 0f) {
                if (timeline == null) {
                    MessageActionsRoute(message, holder, null, null, emptyList(), {})
                } else {
                    Column(Modifier.fillMaxSize()) {
                        ChatTimelineRoute(timeline, Modifier.weight(1f).testTag("blocked-sender-timeline"))
                        MessageActionsRoute(message, holder, null, null, emptyList(), {}, Modifier.height(400.dp))
                    }
                }
            }
        }
        return host
    }

    @Test fun failedContactReadShowsFetchRecoveryAndSuccessfulRetryCanBeCancelledWithoutBlocking() = runTest {
        val harness = ContainerHarness(this, store)
        val actual = harness.container.chatsFeature.actions(radio)
        var reads = 0
        val source = object : MessageActionsDataSource by actual {
            override suspend fun contacts(radioId: RadioId): List<ContactDTO> {
                reads++
                if (reads == 1) throw IllegalStateException("controlled directory read failure")
                return actual.contacts(radioId)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val holder = MessageActionsStateHolder(source, scope)
        store.saveMessage(message)
        val host = mount(holder)
        try {
            compose.waitForIdle()
            assertEquals(0, reads)
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsMessageActionBlockSender))
                .performScrollTo().performClick()
            compose.waitUntil(5_000) { holder.state.value.blockContactsFailure != null }
            val loadError = AppLocalizableStrings.errorPersistenceFetchFailed(
                host.get().resources,
                host.get().getString(AppChatsStrings.chatsBlockSenderMatchingContacts),
            )
            val saveError = AppLocalizableStrings.errorPersistenceSaveFailed(
                host.get().resources,
                host.get().getString(AppChatsStrings.chatsMessageActionBlockSender),
            )
            compose.onNodeWithText(loadError).assertExists()
            compose.onNodeWithText(saveError).assertDoesNotExist()
            assertNull(holder.state.value.failure)
            assertTrue(store.fetchBlockedChannelSenders(radio).isEmpty())
            compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonTryAgain)).performClick()
            compose.waitUntil(5_000) { reads == 2 && holder.state.value.blockContactsFailure == null }
            compose.onNodeWithText(loadError).assertDoesNotExist()
            compose.onNodeWithText(saveError).assertDoesNotExist()
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsBlockSenderCancel)).performClick()
            compose.waitForIdle()
            assertNull(holder.state.value.failure)
            assertTrue(store.fetchBlockedChannelSenders(radio).isEmpty())
            assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
        } finally {
            host.pause().stop().destroy()
            scope.cancel()
            harness.container.close()
        }
    }

    @Test fun cancelPreservesHistoryAndConfirmedMountedActionDeletesOnlyTheSelectedSenderRadio() = runTest {
        val harness = ContainerHarness(this, store)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val holder = MessageActionsStateHolder(harness.container.chatsFeature.actions(radio), scope)
        val foreign = message.copy(radioId = RadioId(UUID(0, 2)))
        val kept = message.copy(id = UUID.randomUUID(), senderNodeName = "Legit", text = "Kept history")
        for (row in listOf(message, foreign, kept)) store.saveMessage(row)
        val channel = ChannelDTO(radioId = radio, index = 0u, name = "Test channel")
        store.saveChannel(channel)
        val conversation = TimelineConversation.Channel(channel)
        val timeline = ChatViewModel(conversation, harness.container.chatsFeature.timeline(conversation), scope)
        val host = mount(holder, timeline)
        try {
            compose.waitUntil(5_000) { timeline.state.value.hasLoadedOnce }
            val loadedSpam = hasText(message.text) and hasAnyAncestor(hasTestTag("blocked-sender-timeline"))
            compose.onNode(loadedSpam).assertExists()
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsMessageActionBlockSender))
                .performScrollTo().performClick()
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsBlockSenderCancel)).performClick()
            compose.waitForIdle()
            assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
            assertTrue(store.fetchBlockedChannelSenders(radio).isEmpty())
            compose.onNode(loadedSpam).assertExists()
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsMessageActionBlockSender))
                .performScrollTo().performClick()
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsBlockSenderBlockAnyway)).performClick()
            compose.waitUntil(5_000) { holder.state.value.blockedSender == "Spammer" }
            compose.waitUntil(5_000) { timeline.state.value.messages.none { it.id == message.id } }
            compose.onNode(loadedSpam).assertDoesNotExist()
            compose.onNode(hasText(kept.text) and hasAnyAncestor(hasTestTag("blocked-sender-timeline"))).assertExists()
            assertNull(holder.state.value.failure)
            assertNull(store.fetchMessage(EntityKey(radio, message.id)))
            assertEquals(kept, store.fetchMessage(EntityKey(radio, kept.id)))
            assertEquals(foreign, store.fetchMessage(EntityKey(foreign.radioId, foreign.id)))
            assertEquals(listOf("Spammer"), store.fetchBlockedChannelSenders(radio).map { it.name })
            assertTrue(harness.lifecycle.created.isEmpty(), "Offline block must not construct a radio service graph")
        } finally {
            host.pause().stop().destroy()
            scope.cancel()
            harness.container.close()
        }
    }

    @Test fun mountedContactSaveFailureRetainsTheConfirmedSelectionForRetry() = runTest {
        val harness = ContainerHarness(this, store)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val holder = MessageActionsStateHolder(harness.container.chatsFeature.actions(radio), scope)
        val selected = ContactDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 0x45 }), name = "Spammer",
            typeRawValue = 1u, lastHeardTimestamp = 0u)
        val foreign = selected.copy(radioId = RadioId(UUID(0, 2)))
        store.saveMessage(message)
        store.saveContact(selected)
        store.saveContact(foreign)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_mounted_contact AFTER UPDATE ON contacts WHEN NEW.isBlocked = 1 " +
                "BEGIN SELECT RAISE(FAIL, 'controlled mounted contact failure'); END",
        )
        val host = mount(holder)
        try {
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsMessageActionBlockSender))
                .performScrollTo().performClick()
            compose.waitForIdle()
            compose.waitUntil(5_000) { holder.state.value.directory.contacts.any { it.id == selected.id } }
            compose.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsBlockSenderBlockAnyway)).performClick()
            compose.waitUntil(5_000) { holder.state.value.failure == MessageActionsFailure.BlockFailed }
            val error = AppLocalizableStrings.errorPersistenceSaveFailed(
                host.get().resources, host.get().getString(AppChatsStrings.chatsMessageActionBlockSender),
            )
            compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
            assertNull(holder.state.value.blockedSender)
            assertNull(store.fetchMessage(EntityKey(radio, message.id)))
            assertEquals(selected, store.fetchContact(EntityKey(radio, selected.id)))
            assertEquals(foreign, store.fetchContact(EntityKey(foreign.radioId, foreign.id)))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_mounted_contact")
            compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonTryAgain))
                .performScrollTo().performClick()
            compose.onNode(isToggleable()).assertIsOn()
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsBlockSenderBlockAnyway)).performClick()
            compose.waitUntil(5_000) { holder.state.value.blockedSender == "Spammer" }
            assertEquals(selected.copy(isBlocked = true), store.fetchContact(EntityKey(radio, selected.id)))
            assertEquals(foreign, store.fetchContact(EntityKey(foreign.radioId, foreign.id)))
            assertEquals(1, store.fetchBlockedChannelSenders(radio).size)
            assertNull(holder.state.value.failure)
            compose.onNodeWithText(error).assertDoesNotExist()
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS reject_mounted_contact")
            host.pause().stop().destroy()
            scope.cancel()
            harness.container.close()
        }
    }

    @Test fun mountedFailedDeletionShowsLocalizedRecoveryAndRetryCommitsWithoutDuplicateBlocks() = runTest {
        val harness = ContainerHarness(this, store)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val holder = MessageActionsStateHolder(harness.container.chatsFeature.actions(radio), scope)
        store.saveMessage(message)
        val selected = ContactDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 0x44 }), name = "Spammer",
            typeRawValue = 1u, lastHeardTimestamp = 0u)
        val foreign = selected.copy(radioId = RadioId(UUID(0, 2)))
        store.saveContact(selected)
        store.saveContact(foreign)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_mounted_block AFTER DELETE ON messages " +
                "BEGIN SELECT RAISE(FAIL, 'controlled mounted deletion failure'); END",
        )
        val host = mount(holder)
        try {
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsMessageActionBlockSender))
                .performScrollTo().performClick()
            compose.waitForIdle()
            compose.waitUntil(5_000) { holder.state.value.directory.contacts.any { it.id == selected.id } }
            compose.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsBlockSenderBlockAnyway)).performClick()
            compose.waitUntil(5_000) { holder.state.value.failure == MessageActionsFailure.BlockFailed }
            val error = AppLocalizableStrings.errorPersistenceSaveFailed(
                host.get().resources, host.get().getString(AppChatsStrings.chatsMessageActionBlockSender),
            )
            compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
            assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
            assertEquals(selected.copy(isBlocked = true), store.fetchContact(EntityKey(radio, selected.id)))
            assertEquals(foreign, store.fetchContact(EntityKey(foreign.radioId, foreign.id)))
            assertNull(holder.state.value.blockedSender)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_mounted_block")
            compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonTryAgain))
                .performScrollTo().performClick()
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsBlockSenderBlockAnyway)).performClick()
            compose.waitUntil(5_000) { holder.state.value.blockedSender == "Spammer" }
            assertNull(store.fetchMessage(EntityKey(radio, message.id)))
            assertEquals(1, store.fetchBlockedChannelSenders(radio).size)
            compose.onNodeWithText(error).assertDoesNotExist()
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS reject_mounted_block")
            host.pause().stop().destroy()
            scope.cancel()
            harness.container.close()
        }
    }
}
