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
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.R
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
import java.util.concurrent.atomic.AtomicBoolean
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

    private fun mount(
        holder: MessageActionsStateHolder,
        timeline: ChatViewModel? = null,
        selectedMessage: MessageDTO = message,
    ): ActivityController<ComponentActivity> {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        host.get().setContent {
            MeshCoreTheme(motionScale = 0f) {
                if (timeline == null) {
                    MessageActionsRoute(selectedMessage, holder, null, null, emptyList(), {})
                } else {
                    Column(Modifier.fillMaxSize()) {
                        ChatTimelineRoute(timeline, Modifier.weight(1f).testTag("blocked-sender-timeline"))
                        MessageActionsRoute(selectedMessage, holder, null, null, emptyList(), {}, Modifier.height(400.dp))
                    }
                }
            }
        }
        return host
    }

    @Test fun mountedConnectedBlockReportsFailedRevocationAndContinuesWithOtherContacts() = runTest {
        val harness = ContainerHarness(this, store)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val rejectFirst = AtomicBoolean(true)
        harness.links.configureRadio = { radio ->
            radio.firmwareVersion = 14u
            fun allowedContact(seed: Int): Bytes {
                val frame = contactFrame(key(seed), "Spammer")
                return frame.slice(0, 34) + Bytes.of(0xb5) + frame.slice(35, frame.size)
            }
            radio.knownContacts += listOf(allowedContact(20), allowedContact(21))
            radio.respond = { packet ->
                if (packet[0].toInt() and 0xff == 0x09) {
                    val publicKey = packet.slice(1, 33)
                    if (publicKey == key(20) && rejectFirst.get()) {
                        listOf(Bytes.of(1, 6))
                    } else {
                        val index = radio.knownContacts.indexOfFirst { it.slice(1, 33) == publicKey }
                        check(index >= 0)
                        radio.knownContacts[index] = Bytes.of(3) + packet.slice(1, 144) + le32(999)
                        listOf(Bytes.of(0))
                    }
                } else null
            }
        }
        var host: ActivityController<ComponentActivity>? = null
        try {
            harness.manager.connect(harness.target())
            harness.eventually("ready") { harness.manager.connectionState == DeviceConnectionState.READY }
            val currentRadio = assertNotNull(harness.appState.currentRadioId)
            val channel = seedReconciledChannel(ChannelDTO(radioId = currentRadio, index = 0u, name = "Test channel"))
            val conversation = harness.container.chatsFeature.captureMessageActionConversation(TimelineConversation.Channel(channel))
            val row = message.copy(radioId = currentRadio)
            store.saveMessage(row)
            val selectedMessage = assertNotNull(harness.container.chatsFeature.timeline(conversation).data.fetchMessage(row.id))
            val first = assertNotNull(store.fetchContact(currentRadio, key(20)))
            val second = assertNotNull(store.fetchContact(currentRadio, key(21)))
            val holder = MessageActionsStateHolder(
                harness.container.chatsFeature.displayedActions(selectedMessage, conversation), scope,
            )
            val mounted = mount(holder, selectedMessage = selectedMessage)
            host = mounted
            compose.onNodeWithText(mounted.get().getString(AppChatsStrings.chatsMessageActionBlockSender))
                .performScrollTo().performClick()
            compose.waitUntil(30_000) {
                harness.settle()
                holder.state.value.directory.contacts.count { it.name == "Spammer" } == 2
            }
            compose.onAllNodes(isToggleable()).onFirst().performClick()
            compose.onAllNodes(isToggleable()).onLast().performClick()
            compose.onNodeWithText(mounted.get().getString(AppChatsStrings.chatsBlockSenderBlockAnyway)).performClick()
            compose.waitUntil(30_000) { harness.settle(); holder.state.value.remoteCLIBlockUncertain }
            compose.onNodeWithText(mounted.get().getString(R.string.radio_cli_recovery)).performScrollTo().assertIsDisplayed()
            assertNull(holder.state.value.failure)
            assertNull(holder.state.value.blockedSender)
            assertTrue(assertNotNull(store.fetchContact(EntityKey(currentRadio, first.id))).isBlocked)
            assertTrue(assertNotNull(store.fetchContact(EntityKey(currentRadio, second.id))).isBlocked)
            assertEquals(0xb5u.toUByte(), store.fetchContact(EntityKey(currentRadio, first.id))?.flags)
            assertEquals(0xa5u.toUByte(), store.fetchContact(EntityKey(currentRadio, second.id))?.flags)
            val radio = harness.links.radios.single()
            assertEquals(0xb5, radio.knownContacts.first { it.slice(1, 33) == key(20) }[34].toInt() and 0xff)
            assertEquals(0xa5, radio.knownContacts.first { it.slice(1, 33) == key(21) }[34].toInt() and 0xff)
            assertTrue(assertNotNull(harness.sessions.current).contactService.needsRemoteCLIWriteReconciliation(currentRadio))
            rejectFirst.set(false)
            compose.onNodeWithText(mounted.get().getString(AppChatsStrings.chatsMessageActionBlockSender))
                .performScrollTo().performClick()
            compose.waitUntil(30_000) {
                harness.settle()
                holder.state.value.directory.contacts.count { it.name == "Spammer" && it.isBlocked } == 2
            }
            compose.onNodeWithText(mounted.get().getString(AppChatsStrings.chatsBlockSenderBlockAnyway)).performClick()
            compose.waitUntil(30_000) { harness.settle(); holder.state.value.blockedSender == "Spammer" }
            assertFalse(holder.state.value.remoteCLIBlockUncertain)
            assertEquals(0xa5u.toUByte(), store.fetchContact(EntityKey(currentRadio, first.id))?.flags)
            assertEquals(0xa5, radio.knownContacts.first { it.slice(1, 33) == key(20) }[34].toInt() and 0xff)
            assertFalse(assertNotNull(harness.sessions.current).contactService.needsRemoteCLIWriteReconciliation(currentRadio))
        } finally {
            host?.pause()?.stop()?.destroy()
            scope.cancel()
            harness.container.close()
        }
    }

    @Test fun mountedOfflineBlockWarnsUntilReconnectRevokesCLI() = runTest {
        val harness = ContainerHarness(this, store)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val target = harness.target()
        harness.links.configureRadio = { radio ->
            radio.firmwareVersion = 14u
            val contact = contactFrame(key(20), "Spammer")
            radio.knownContacts += contact.slice(0, 34) + Bytes.of(0xb5) + contact.slice(35, contact.size)
            radio.respond = { packet ->
                if (packet[0].toInt() and 0xff == 0x09) {
                    radio.knownContacts[0] = Bytes.of(3) + packet.slice(1, 144) + le32(999)
                    listOf(Bytes.of(0))
                } else null
            }
        }
        var host: ActivityController<ComponentActivity>? = null
        try {
            harness.manager.connect(target)
            harness.eventually("ready") { harness.manager.connectionState == DeviceConnectionState.READY }
            val radio = assertNotNull(harness.appState.currentRadioId)
            val selected = assertNotNull(store.fetchContact(radio, key(20)))
            val selectedMessage = message.copy(radioId = radio)
            store.saveMessage(selectedMessage)
            harness.manager.disconnect()
            harness.eventually("disconnected") { harness.manager.connectionState == DeviceConnectionState.DISCONNECTED }
            val holder = MessageActionsStateHolder(harness.container.chatsFeature.actions(radio), scope)
            val mounted = mount(holder, selectedMessage = selectedMessage)
            host = mounted
            compose.onNodeWithText(mounted.get().getString(AppChatsStrings.chatsMessageActionBlockSender))
                .performScrollTo().performClick()
            compose.waitUntil(30_000) { harness.settle(); holder.state.value.directory.contacts.any { it.id == selected.id } }
            compose.onNode(isToggleable()).performClick()
            compose.onNodeWithText(mounted.get().getString(AppChatsStrings.chatsBlockSenderBlockAnyway)).performClick()
            compose.waitUntil(30_000) { harness.settle(); holder.state.value.remoteCLIBlockUncertain }
            compose.onNodeWithText(mounted.get().getString(R.string.radio_cli_recovery)).performScrollTo().assertIsDisplayed()
            assertTrue(assertNotNull(store.fetchContact(EntityKey(radio, selected.id))).isBlocked)
            assertEquals(0xb5u.toUByte(), store.fetchContact(EntityKey(radio, selected.id))?.flags)
            harness.manager.connect(target)
            harness.eventually("reconnected and reconciled") { harness.manager.connectionState == DeviceConnectionState.READY }
            compose.waitUntil(30_000) { harness.settle(); !holder.state.value.remoteCLIBlockUncertain }
            compose.onNodeWithText(mounted.get().getString(R.string.radio_cli_recovery)).assertDoesNotExist()
            assertEquals(0xa5u.toUByte(), store.fetchContact(EntityKey(radio, selected.id))?.flags)
        } finally {
            host?.pause()?.stop()?.destroy()
            scope.cancel()
            harness.container.close()
        }
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
            compose.waitForIdle()
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
            compose.waitForIdle()
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
