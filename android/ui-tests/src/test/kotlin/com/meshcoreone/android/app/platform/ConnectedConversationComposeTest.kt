// AndroidOnly: WP-502 Drive the production composer through the real Room/service graph and a rejecting fake firmware.
// ReferenceInput: MeshCore/Sources/MeshCore/Protocol/ErrorCode.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/ViewModel/ChatViewModel+SendQueues.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/ChatConversationView.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/State/NavigationCoordinator.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.platform

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.app.container.*
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.MessagingDiagnostic
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as C
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.uitests.ConversationScreenCases
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31], qualifiers = "w360dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedConversationComposeTest : RoomProcessTest() {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun uncertainNativeDirectSendOffersExplicitSendAgainAndDelivers() = runTest {
        rejectedSendAndRetry(this, channel = false)
    }

    @Test fun rejectedNativeChannelSendRemainsUncertainWithoutMountedRetry() = runTest {
        rejectedSendAndRetry(this, channel = true)
    }

    @Test
    @Config(sdk = [31], qualifiers = "w1080dp-h1200dp-mdpi")
    fun directDraftAndExplicitRecoverySurviveAdaptivePanesAndTabReturn() = runTest {
        rejectedSendAndRetry(this, channel = false, adaptive = true)
    }

    @Test
    @Config(sdk = [31], qualifiers = "w1080dp-h1200dp-mdpi")
    fun channelDraftAndUncertainStatusSurviveAdaptivePanesAndTabReturn() = runTest {
        rejectedSendAndRetry(this, channel = true, adaptive = true)
    }

    private suspend fun rejectedSendAndRetry(test: TestScope, channel: Boolean, adaptive: Boolean = false) {
        val h = ContainerHarness(test, store)
        val width = mutableStateOf(360)
        val contactKey = Bytes(ByteArray(32) { 4 })
        val opcode = if (channel) 3 else 2
        val failures = Collections.synchronizedList(mutableListOf<UUID>())
        val accepted = Collections.synchronizedList(mutableListOf<PendingSendDTO>())
        test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            h.appState.messageEventStream.events().collect { event ->
                if (event is MessageEvent.MessageFailed) failures += event.messageID
            }
        }
        h.links.configureRadio = { radio ->
            radio.knownContacts += contactFrame(contactKey, "Synthetic mesh peer")
            radio.respond = { request ->
                if (request[0].toInt() == opcode) {
                    accepted += runBlocking { store.fetchPendingSends(requireNotNull(h.appState.currentRadioId)).single() }
                    if (!channel && accepted.size == 2) sentAndDelivered(request, key(7))
                    else listOf(Bytes.of(1, if (channel) 6 else 2))
                } else if (channel && request[0].toInt() == 0x1f) {
                    listOf(
                        if (request[1].toInt() == 0) Bytes.of(0x12, 0) +
                            Bytes.utf8("Synthetic mesh channel").paddedOrTruncated(32) + Bytes(ByteArray(16) { 0x11 })
                        else Bytes.of(0x12, request[1].toInt()) + Bytes(ByteArray(48)),
                    )
                } else null
            }
        }
        try {
            if (channel) h.container.foreground.activityStarted()
            h.manager.connect(h.target(UUID.fromString("50200000-0000-0000-0000-000000000099")))
            h.settle()
            h.assertReady()
            val radio = requireNotNull(h.appState.currentRadioId)
            val contact = requireNotNull(store.fetchContact(radio, contactKey))
            val channelDTO = if (channel) requireNotNull(store.fetchChannel(radio, 0u))
                else ChannelDTO(radioId = radio, index = 0u, name = "Synthetic mesh channel")
            suspend fun messages() = if (channel) store.fetchMessages(radio, channelDTO.index, 10, 0)
                else store.fetchMessages(EntityKey(radio, contact.id), 10, 0)
            compose.runOnUiThread {
                if (channel) h.container.navigation.navigateToChannel(channelDTO)
                else h.container.navigation.navigateToChat(contact)
                compose.activity.setContent {
                    MeshCoreTheme(motionScale = 0f) {
                        if (adaptive) {
                            val density = LocalDensity.current.density
                            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                                Box(Modifier.width(width.value.dp).fillMaxHeight()) {
                                    NativeNavigationShell(h.container.navigation, chats = h.container.chatsFeature)
                                }
                            }
                        } else NativeNavigationShell(h.container.navigation, chats = h.container.chatsFeature)
                    }
                }
            }
            h.settle()
            compose.waitForIdle()
            val original = ConversationScreenCases.originalText
            compose.onNode(
                hasSetTextAction() and hasContentDescription(
                    compose.activity.getString(C.chatsInputAccessibilityLabel), substring = true,
                ),
            ).assertIsDisplayed().performTextInput(original)
            val send = compose.onNodeWithContentDescription(compose.activity.getString(C.chatsInputSendMessage))
            send.assertIsEnabled().performClick()
            val sending = compose.activity.getString(C.chatsMessageStatusSending)
            val delivered = compose.activity.getString(C.chatsMessageStatusDelivered)
            val retry = compose.activity.getString(C.chatsMessageStatusRetry)
            var messageID: UUID? = null
            var firstPendingID: UUID? = null
            val attempts = if (channel) 1 else 2
            repeat(attempts) { attempt ->
                val uncertain = channel || attempt == 0
                h.eventually("mounted queue attempt ${attempt + 1} persists its expected outcome") {
                    runBlocking {
                        val persisted = messages().singleOrNull()
                        val pending = persisted?.let { store.fetchPendingSendsForMessage(EntityKey(radio, it.id)).singleOrNull() }
                        persisted?.text == original && if (uncertain) {
                            persisted.status == MessageStatus.PENDING && pending != null &&
                                if (channel) pending.channelDispatchState == QueuedChannelDispatchState.UNCERTAIN
                                else h.messagingDiagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
                                    it.operation == "directUncertain.${pending.id}"
                                }
                        } else {
                            persisted?.status == MessageStatus.DELIVERED && pending == null
                        }
                    }
                }
                val persisted = messages().single()
                if (attempt == 0) messageID = persisted.id else assertEquals(messageID, persisted.id)
                assertEquals(original, persisted.text)
                assertEquals(if (uncertain) MessageStatus.PENDING else MessageStatus.DELIVERED, persisted.status)
                assertEquals(radio, persisted.radioId)
                val pending = accepted[attempt]
                assertEquals(persisted.id, pending.messageID)
                assertEquals(1L, pending.attemptCount)
                assertEquals(attempt == 1, pending.isResend)
                if (attempt == 0) firstPendingID = pending.id else assertNotEquals(firstPendingID, pending.id)
                if (channel) {
                    assertEquals(original, pending.messageText)
                    assertEquals(requireNotNull(persisted.channelOccupantID), pending.channelOccupantID)
                    assertEquals(persisted.timestamp, pending.messageTimestamp)
                    assertEquals("TestNode", pending.localNodeName)
                }
                h.settle()
                compose.waitForIdle()
                val label = "$original. ${if (uncertain) sending else delivered}"
                compose.waitUntil(30_000) {
                    h.settle()
                    compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithContentDescription(label).assertIsDisplayed()
                compose.onNodeWithText(retry).assertDoesNotExist()
                assertNull(h.draftStore.draft(if (channel) ChatConversationID.channel(radio, channelDTO.index)
                    else ChatConversationID.dm(radio, contact.id)))
                if (!channel && attempt == 0) {
                    assertEquals(1, h.links.radios.single().sent.count { it[0].toInt() == opcode })
                    test.testScheduler.advanceTimeBy(1_100)
                    compose.onNodeWithText(compose.activity.getString(C.chatsMessageActionDetails)).performClick()
                    compose.onNodeWithText(compose.activity.getString(C.chatsMessageActionSendAgain))
                        .performScrollTo().assertIsEnabled().performClick()
                }
            }
            if (!channel) {
                val wires = h.links.radios.single().sent.filter { it[0].toInt() == opcode }
                assertEquals(2, wires.size)
                assertNotEquals(sentReply(wires[0], key(7)), sentReply(wires[1], key(7)))
            }
            if (adaptive) {
                val retained = "Unsent draft: $original"
                val fieldMatcher = hasSetTextAction() and hasContentDescription(
                    compose.activity.getString(C.chatsInputAccessibilityLabel), substring = true,
                )
                val field = compose.onNode(fieldMatcher)
                field.performClick().performTextReplacement(retained)
                field.assertIsFocused()
                val conversation = if (channel) ChatConversationID.channel(radio, channelDTO.index)
                    else ChatConversationID.dm(radio, contact.id)
                assertEquals(retained, h.draftStore.draft(conversation))
                val entry = h.container.navigation.state.value.activeStack.last()
                val token = requireNotNull(h.sessions.current).token
                val persisted = messages().single()
                val exercisedWidths = mutableListOf<Int>()
                for (value in listOf(360, 599, 600, 779, 780, 834, 360)) {
                    compose.runOnIdle { width.value = value }
                    h.settle()
                    compose.waitForIdle()
                    compose.onNodeWithTag(if (value < 780) "single-pane" else "list-detail").assertIsDisplayed()
                    compose.onNodeWithTag(if (value < 600) "navigation-hidden" else "navigation-rail").assertIsDisplayed()
                    field.assertIsDisplayed().assertIsFocused().assertTextContains(retained)
                    val layouts = mutableListOf<TextLayoutResult>()
                    field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                    assertEquals(2f, layouts.single().layoutInput.density.fontScale, 0f)
                    assertFalse("The actual 200% draft must not clip vertically at width$value", layouts.single().didOverflowHeight)
                    compose.onNodeWithContentDescription("$original. ${if (channel) sending else delivered}")
                        .assertIsDisplayed()
                    compose.onNodeWithText(retry).assertDoesNotExist()
                    assertEquals(entry, h.container.navigation.state.value.activeStack.last())
                    assertEquals(value.toFloat(), h.container.navigation.state.value.widthDp, 0f)
                    assertEquals(retained, h.draftStore.draft(conversation))
                    assertEquals(persisted, messages().single())
                    assertEquals(token, requireNotNull(h.sessions.current).token)
                    exercisedWidths += value
                }
                assertEquals(listOf(360, 599, 600, 779, 780, 834, 360), exercisedWidths)
                compose.runOnIdle { width.value = 834 }
                compose.onNodeWithTag("tab:SETTINGS").performClick().assertIsSelected()
                h.settle()
                compose.onNode(fieldMatcher).assertDoesNotExist()
                assertEquals(retained, h.draftStore.draft(conversation))
                compose.onNodeWithTag("tab:CHATS").performClick().assertIsSelected()
                h.settle()
                compose.waitForIdle()
                compose.onNode(fieldMatcher).assertIsDisplayed().assertTextContains(retained)
                compose.onNodeWithContentDescription("$original. ${if (channel) sending else delivered}")
                    .assertIsDisplayed()
                compose.onNodeWithText(retry).assertDoesNotExist()
                assertEquals(AppTab.CHATS, h.container.navigation.state.value.selectedTab)
                assertEquals(entry, h.container.navigation.state.value.activeStack.last())
                assertEquals(retained, h.draftStore.draft(conversation))
                assertEquals(persisted, messages().single())
                assertEquals(token, requireNotNull(h.sessions.current).token)
            }
            assertTrue(failures.isEmpty())
            assertEquals(attempts, accepted.size)
            assertEquals(1, h.lifecycle.created.size)
            assertEquals(1, h.links.radios.size)
            assertEquals(attempts, h.links.radios.single().sent.count { it[0].toInt() == opcode })
            if (channel) {
                val retained = store.fetchPendingSendsForMessage(EntityKey(radio, requireNotNull(messageID))).single()
                assertEquals(QueuedChannelDispatchState.UNCERTAIN, retained.channelDispatchState)
                assertNotNull(retained.channelAttemptID)
            } else assertEquals(0, requireNotNull(h.sessions.current).chatSendQueueService.queuedEnvelopeCount)
        } finally {
            compose.runOnUiThread { compose.activity.setContent {} }
            h.container.close()
        }
    }
}
