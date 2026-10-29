// PortedFrom: MC1/Views/Chats/ChatConversationView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Sheets/BlockSenderSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/BlockedSenderMessageDeletionTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.feature.chats.actions.MessageActionsFailure
import com.meshcoreone.android.feature.chats.actions.MessageActionsRoute
import com.meshcoreone.android.feature.chats.actions.MessageActionsStateHolder
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

    private fun mount(holder: MessageActionsStateHolder): ActivityController<ComponentActivity> {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        host.get().setContent {
            MeshCoreTheme(motionScale = 0f) {
                MessageActionsRoute(message, holder, null, null, emptyList(), {})
            }
        }
        return host
    }

    @Test fun cancelPreservesHistoryAndConfirmedMountedActionDeletesOnlyTheSelectedSenderRadio() = runTest {
        val harness = ContainerHarness(this, store)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val holder = MessageActionsStateHolder(harness.container.chatsFeature.actions(radio), scope)
        val foreign = message.copy(radioId = RadioId(UUID(0, 2)))
        val kept = message.copy(id = UUID.randomUUID(), senderNodeName = "Legit")
        for (row in listOf(message, foreign, kept)) store.saveMessage(row)
        val host = mount(holder)
        try {
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsMessageActionBlockSender))
                .performScrollTo().performClick()
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsBlockSenderCancel)).performClick()
            compose.waitForIdle()
            assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
            assertTrue(store.fetchBlockedChannelSenders(radio).isEmpty())
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsMessageActionBlockSender))
                .performScrollTo().performClick()
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsBlockSenderBlockAnyway)).performClick()
            compose.waitUntil(5_000) { holder.state.value.blockedSender == "Spammer" }
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

    @Test fun mountedFailedDeletionShowsLocalizedRecoveryAndRetryCommitsWithoutDuplicateBlocks() = runTest {
        val harness = ContainerHarness(this, store)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val holder = MessageActionsStateHolder(harness.container.chatsFeature.actions(radio), scope)
        store.saveMessage(message)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_mounted_block AFTER DELETE ON messages " +
                "BEGIN SELECT RAISE(FAIL, 'controlled mounted deletion failure'); END",
        )
        val host = mount(holder)
        try {
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsMessageActionBlockSender))
                .performScrollTo().performClick()
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsBlockSenderBlockAnyway)).performClick()
            compose.waitUntil(5_000) { holder.state.value.failure == MessageActionsFailure.BlockFailed }
            val error = AppLocalizableStrings.errorPersistenceSaveFailed(
                host.get().resources, host.get().getString(AppChatsStrings.chatsMessageActionBlockSender),
            )
            compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
            assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
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
