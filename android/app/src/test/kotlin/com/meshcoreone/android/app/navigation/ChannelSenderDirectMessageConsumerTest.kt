// PortedFrom: MC1/Views/Chats/Sheets/SendDMSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: mounted channel action, scoped directory failures and owned DM navigation.
package com.meshcoreone.android.app.navigation

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryPersisting
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as Chats
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChannelSenderDirectMessageConsumerTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private lateinit var h: ContainerHarness
    private lateinit var channel: ChannelDTO
    private lateinit var message: MessageDTO
    private lateinit var destination: NavigationDestination
    private var failReads = false
    private var holdDirectory: CompletableDeferred<Unit>? = null
    private var directoryEntered = false
    private var holdSelection: CompletableDeferred<Unit>? = null
    private var selectionEntered = false

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    private fun pickerTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            holdDirectory?.complete(Unit)
            holdSelection?.complete(Unit)
            compose.runOnUiThread { host.get().setContent {} }
            compose.waitForIdle()
            if (::h.isInitialized) h.container.close()
        }
    }

    private fun contact(radio: RadioId, key: Int, name: String = "Alice", nickname: String? = null) =
        ContactDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { key.toByte() }),
            name = name, nickname = nickname, lastHeardTimestamp = null)

    private fun text(id: Int) = host.get().getString(id)

    private suspend fun TestScope.mount(
        fontScale: Float = 1f,
        contacts: (RadioId) -> List<ContactDTO>,
    ): List<ContactDTO> {
        val persistence = object : PersistenceStoreProtocol by store, ChannelHistoryPersisting by store {
            override suspend fun fetchContacts(radioId: RadioId): SnapshotList<ContactDTO> {
                holdDirectory?.let { gate ->
                    withContext(NonCancellable) {
                        directoryEntered = true
                        gate.await()
                    }
                }
                if (failReads) throw IllegalStateException("Controlled sender directory read failure")
                return store.fetchContacts(radioId)
            }

            override suspend fun fetchContact(key: com.meshcoreone.android.core.contracts.domain.EntityKey): ContactDTO? {
                holdSelection?.let { gate ->
                    withContext(NonCancellable) {
                        selectionEntered = true
                        gate.await()
                    }
                }
                return store.fetchContact(key)
            }
        }
        h = ContainerHarness(this, store, persistenceOverride = persistence)
        h.manager.connect(h.target())
        h.settle()
        h.assertReady()
        val radio = requireNotNull(h.appState.currentRadioId)
        val saved = contacts(radio)
        saved.forEach { store.saveContact(it) }
        channel = seedReconciledChannel(ChannelDTO(radioId = radio, index = 1u, name = "Channel"))
        message = MessageDTO(
            radioId = radio, channelIndex = channel.index, channelOccupantID = channel.occupantID,
            senderNodeName = "Alice", senderKeyPrefix = saved.firstOrNull()?.publicKey?.prefix(1),
            text = "Channel sender DM target", timestamp = 100u,
            direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
        )
        store.saveMessage(message)
        h.container.navigation.navigateToChannel(channel)
        destination = h.container.navigation.state.value.activeStack.last().destination
        compose.runOnUiThread {
            host.get().setContent {
                val config = Configuration(LocalConfiguration.current).apply { this.fontScale = fontScale }
                CompositionLocalProvider(
                    LocalConfiguration provides config,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                ) {
                    MeshCoreTheme(motionScale = 0f) {
                        AppChatsContent(h.container.chatsFeature, destination, h.container.navigation)
                    }
                }
            }
        }
        awaitText(message.text, unmerged = true)
        compose.onNodeWithText(message.text, useUnmergedTree = true).performTouchInput { longClick() }
        awaitText(text(Chats.chatsMessageActionSendDM))
        return saved
    }

    private fun awaitText(value: String, unmerged: Boolean = false) {
        compose.waitUntil(30_000) {
            h.settle()
            compose.onAllNodesWithText(value, useUnmergedTree = unmerged).fetchSemanticsNodes().size == 1
        }
    }

    private fun openPicker() {
        compose.onNodeWithText(text(Chats.chatsMessageActionSendDM)).performScrollTo().performClick()
        awaitText(text(Chats.chatsSendDMLimitation))
        compose.onNodeWithText(text(Chats.chatsMessageActionSendDM)).assertDoesNotExist()
        assertEquals(destination, h.container.navigation.state.value.activeStack.last().destination)
    }

    private fun choose(contact: ContactDTO) {
        awaitText(contact.publicKey.hexString)
        compose.onNodeWithText(contact.publicKey.hexString).performScrollTo().performClick()
        compose.waitUntil(30_000) {
            h.settle()
            h.container.navigation.state.value.chatsSelectedRoute is ChatSelection.Direct
        }
        val selected = h.container.navigation.state.value.chatsSelectedRoute as ChatSelection.Direct
        assertEquals(contact.id, selected.contact.id)
        assertEquals(contact.publicKey, selected.contact.publicKey)
        assertEquals(channel.radioId, selected.contact.radioId)
    }

    @Test fun singleCaseVariantRequiresExplicitSelectionAndDisclosesUnverifiedNickname() = pickerTest {
        val alice = mount { listOf(contact(it, 1, "aLiCe", "Local nickname")) }.single()
        openPicker()
        val title = Chats.chatsSendDMTitle(host.get().resources, "Local nickname (Alice)")
        awaitText(title)
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText(text(Chats.chatsMessageSenderUnverifiedNicknameExplanation)).assertExists()
        assertEquals(destination, h.container.navigation.state.value.activeStack.last().destination)
        choose(alice)
    }

    @Test fun multipleMatchesExcludeBlockedForeignRadioAndNonexactNamesAndUseChosenIdentity() = pickerTest {
        val saved = mount {
            listOf(
                contact(it, 1, nickname = "First"),
                contact(it, 2, "ALICE", "Second"),
                contact(it, 3).copy(isBlocked = true),
                contact(RadioId(UUID(0, 999)), 4),
                contact(it, 5, " Alice "),
                contact(it, 6, "Bob"),
            )
        }
        openPicker()
        awaitText(saved[1].publicKey.hexString)
        saved.drop(2).forEach { compose.onNodeWithText(it.publicKey.hexString).assertDoesNotExist() }
        compose.onNodeWithText(saved.first().publicKey.hexString).assertExists()
        choose(saved[1])
    }

    @Test fun noMatchesHasEmptyStateAndCancelPreservesChannel() = pickerTest {
        mount { emptyList() }
        openPicker()
        awaitText(Chats.chatsSendDMNoMatches(host.get().resources, "Alice"))
        compose.onNodeWithText(text(Chats.chatsSendDMCancel)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(destination, h.container.navigation.state.value.activeStack.last().destination)
        compose.onNodeWithText(text(Chats.chatsSendDMLimitation)).assertDoesNotExist()
        compose.onNodeWithTag("timeline-bubble:${message.id}", useUnmergedTree = true).assertExists()
    }

    @Test fun blockedOnlyMatchIsEmptyEvenWhenItsKeyPrefixMatches() = pickerTest {
        val blocked = mount { listOf(contact(it, 1).copy(isBlocked = true)) }.single()
        openPicker()
        awaitText(Chats.chatsSendDMNoMatches(host.get().resources, "Alice"))
        compose.onNodeWithText(blocked.publicKey.hexString).assertDoesNotExist()
        assertEquals(destination, h.container.navigation.state.value.activeStack.last().destination)
    }

    @Test fun directoryFailureIsNotEmptyAndRetryRecoversTheSamePicker() = pickerTest {
        val alice = mount { listOf(contact(it, 1)) }.single()
        failReads = true
        openPicker()
        awaitText("Controlled sender directory read failure")
        compose.onNodeWithText(Chats.chatsSendDMNoMatches(host.get().resources, "Alice")).assertDoesNotExist()
        compose.onNodeWithText(alice.publicKey.hexString).assertDoesNotExist()
        failReads = false
        compose.onNodeWithText(text(AppLocalizableStrings.commonTryAgain)).performScrollTo().performClick()
        choose(alice)
    }

    @Test fun pendingReadShowsLoadingAndCancelledResultCannotNavigateOrReopenPicker() = pickerTest {
        val alice = mount { listOf(contact(it, 1)) }.single()
        val gate = CompletableDeferred<Unit>()
        holdDirectory = gate
        openPicker()
        compose.waitUntil(30_000) { h.settle(); directoryEntered }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsDisplayed()
        compose.onNodeWithText(Chats.chatsSendDMNoMatches(host.get().resources, "Alice")).assertDoesNotExist()
        compose.onNodeWithText(alice.publicKey.hexString).assertDoesNotExist()
        compose.onNodeWithText(text(Chats.chatsSendDMCancel)).performScrollTo().performClick()
        gate.complete(Unit)
        compose.waitForIdle()
        h.settle()
        compose.onNodeWithText(text(Chats.chatsSendDMLimitation)).assertDoesNotExist()
        assertEquals(destination, h.container.navigation.state.value.activeStack.last().destination)
    }

    @Test @Config(qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
    fun expandedLargeFontPickerKeepsDisclosureAndCanCancelWithoutSelecting() = pickerTest {
        val alice = mount(fontScale = 2f) { listOf(contact(it, 1)) }.single()
        openPicker()
        awaitText(alice.publicKey.hexString)
        compose.onNodeWithText(text(Chats.chatsSendDMLimitation)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(alice.publicKey.hexString).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(Chats.chatsSendDMCancel)).performScrollTo()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        assertEquals(destination, h.container.navigation.state.value.activeStack.last().destination)
    }

    @Test fun contactBlockedAfterLoadingCannotBeSelected() = pickerTest {
        val alice = mount { listOf(contact(it, 1)) }.single()
        openPicker()
        awaitText(alice.publicKey.hexString)
        store.saveContact(alice.copy(isBlocked = true))
        compose.onNodeWithText(alice.publicKey.hexString).performScrollTo().performClick()
        awaitText(text(AppLocalizableStrings.commonTryAgain))
        assertEquals(destination, h.container.navigation.state.value.activeStack.last().destination)
    }

    @Test fun replacedChannelOccupantCannotOpenDmFromRetainedPicker() = pickerTest {
        val alice = mount { listOf(contact(it, 1)) }.single()
        openPicker()
        awaitText(alice.publicKey.hexString)
        store.saveChannel(channel.radioId, ChannelInfo(channel.index, "Replacement", Bytes(ByteArray(16) { 2 })))
        compose.onNodeWithText(alice.publicKey.hexString).performScrollTo().performClick()
        awaitText(text(AppLocalizableStrings.commonTryAgain))
        assertEquals(destination, h.container.navigation.state.value.activeStack.last().destination)
    }

    @Test fun pendingSelectionCannotNavigateAfterLeavingOriginalChannel() = pickerTest {
        val alice = mount { listOf(contact(it, 1)) }.single()
        openPicker()
        awaitText(alice.publicKey.hexString)
        val gate = CompletableDeferred<Unit>()
        holdSelection = gate
        compose.onNodeWithText(alice.publicKey.hexString).performScrollTo().performClick()
        compose.waitUntil(30_000) { h.settle(); selectionEntered }
        val foreign = contact(RadioId(UUID(0, 999)), 9, "Other radio")
        h.container.navigation.navigateToChat(foreign)
        val moved = h.container.navigation.state.value.activeStack.last().destination
        gate.complete(Unit)
        awaitText(text(AppLocalizableStrings.commonTryAgain))
        assertEquals(moved, h.container.navigation.state.value.activeStack.last().destination)
    }
}
