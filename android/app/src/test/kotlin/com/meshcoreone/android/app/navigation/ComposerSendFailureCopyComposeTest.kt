// AndroidOnly: WP-308 Mounted disconnected-send copy and draft-preservation regressions.
// ReferenceInput: MC1/Views/Chats/ViewModel/ChatViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceError
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelMessageEnvelope
import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.DirectMessageEnvelope
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TextType
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.composer.ComposerDeviceInfo
import com.meshcoreone.android.feature.chats.composer.ComposerEnvironment
import com.meshcoreone.android.feature.chats.composer.ComposerLocationSource
import com.meshcoreone.android.feature.chats.composer.ComposerSendPort
import com.meshcoreone.android.feature.chats.composer.ComposerSendPhase
import com.meshcoreone.android.feature.chats.composer.ComposerStateHolder
import com.meshcoreone.android.feature.chats.composer.ComposerTarget
import com.meshcoreone.android.feature.chats.composer.ComposerTimer
import com.meshcoreone.android.feature.chats.composer.ui.ComposerRoute
import com.meshcoreone.android.feature.chats.timeline.TimelineDraftStore
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerSendFailureCopyComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
    }

    @After fun destroyHost() {
        host.pause().stop().destroy()
    }

    @Test fun disconnectedEnqueueUsesSourceCopyAndPreservesNewDraft() = runTest {
        val failure = ChatSendQueueServiceException(ChatSendQueueServiceError.NotConnected)
        val rig = createRig(failure)
        try {
            mount(rig)
            val editor = compose.onNodeWithContentDescription(rig.editorDescription)
            editor.performTextInput("first message")
            compose.waitUntil(10_000) {
                rig.holder.state.value.draft == "first message" && rig.holder.state.value.canSend
            }
            compose.onNodeWithContentDescription(rig.sendLabel).performClick()
            compose.waitUntil(10_000) { rig.port.enqueueEntered.isCompleted }
            editor.performTextInput("new draft")
            compose.waitUntil(10_000) { rig.holder.state.value.draft == "new draft" }
            rig.port.releaseEnqueue.complete(Unit)
            compose.waitUntil(10_000) {
                rig.holder.state.value.sendPhase is ComposerSendPhase.Failed &&
                    rig.port.localEnqueueFailures.size == 1
            }

            val sourceCopy = host.get().getString(AppChatsStrings.chatsAlertUnableToSendMessage)
            assertEquals("Please ensure your device is connected and try again.", sourceCopy)
            compose.onNodeWithText(sourceCopy, useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText(
                host.get().getString(AppChatsStrings.chatsErrorNoDeviceConnected),
                useUnmergedTree = true,
            ).assertDoesNotExist()
            assertEquals("first message", rig.port.pending.single().text)
            assertEquals(1, rig.port.enqueued.size)
            assertEquals(rig.port.pending.single().id, rig.port.enqueued.single().messageID)
            assertEquals("new draft", rig.holder.state.value.draft)
            assertEquals("new draft", rig.drafts.get(rig.target.conversationId))
            assertEquals(listOf(rig.port.pending.single().id), rig.port.localEnqueueFailures)
        } finally {
            dispose(rig)
        }
    }

    @Test fun queuePersistenceFailureRetainsItsDistinctCopy() = runTest {
        val failure = ChatSendQueueServiceException(
            ChatSendQueueServiceError.PersistFailed(IllegalStateException("queue write failed")),
        )
        val rig = createRig(failure)
        try {
            mount(rig)
            val editor = compose.onNodeWithContentDescription(rig.editorDescription)
            editor.performTextInput("saved pending message")
            compose.waitUntil(10_000) { rig.holder.state.value.canSend }
            compose.onNodeWithContentDescription(rig.sendLabel).performClick()
            compose.waitUntil(10_000) { rig.port.enqueueEntered.isCompleted }
            rig.port.releaseEnqueue.complete(Unit)
            compose.waitUntil(10_000) {
                rig.holder.state.value.sendPhase is ComposerSendPhase.Failed &&
                    rig.port.localEnqueueFailures.size == 1
            }

            val expected = host.get().getString(AppChatsStrings.chatsErrorSendQueuePersistFailed)
            compose.onNodeWithText(expected, useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText(
                host.get().getString(AppChatsStrings.chatsAlertUnableToSendMessage),
                useUnmergedTree = true,
            ).assertDoesNotExist()
            assertEquals("saved pending message", rig.port.pending.single().text)
            assertEquals("", rig.holder.state.value.draft)
            assertNull(rig.drafts.get(rig.target.conversationId))
        } finally {
            dispose(rig)
        }
    }

    private fun createRig(failure: Throwable): Rig {
        val radio = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000403"))
        val contact = ContactDTO(
            radioId = radio,
            publicKey = Bytes(ByteArray(32) { 4 }),
            name = "Alice",
            lastHeardTimestamp = null,
        )
        val target = ComposerTarget.Direct(contact)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val environment = TestEnvironment()
        val drafts = MemoryDrafts()
        val port = FailingSendPort(radio, failure)
        val holder = ComposerStateHolder(target, port, drafts, environment, scope, ComposerTimer { })
        val resources = host.get().resources
        val label = resources.getString(AppChatsStrings.chatsInputAccessibilityLabel)
        val encrypted = resources.getString(AppChatsStrings.chatsInputEncrypted)
        return Rig(
            target = target,
            scope = scope,
            holder = holder,
            drafts = drafts,
            port = port,
            editorDescription = "$label, $encrypted",
            sendLabel = resources.getString(AppChatsStrings.chatsInputSendMessage),
        )
    }

    private fun mount(rig: Rig) {
        compose.runOnUiThread {
            host.get().setContent {
                MeshCoreTheme(motionScale = 0f) {
                    ComposerRoute(rig.holder, "Direct Message", onPickContact = {})
                }
            }
        }
        compose.onNodeWithContentDescription(rig.editorDescription).assertIsDisplayed()
    }

    private fun dispose(rig: Rig) {
        compose.runOnUiThread { host.get().setContent {} }
        compose.waitForIdle()
        rig.holder.close()
        rig.scope.cancel()
    }

    private data class Rig(
        val target: ComposerTarget.Direct,
        val scope: CoroutineScope,
        val holder: ComposerStateHolder,
        val drafts: MemoryDrafts,
        val port: FailingSendPort,
        val editorDescription: String,
        val sendLabel: String,
    )

    private class MemoryDrafts : TimelineDraftStore {
        private val values = mutableMapOf<ChatConversationID, String>()

        override fun get(conversationId: ChatConversationID): String? = values[conversationId]
        override fun set(conversationId: ChatConversationID, text: String?) {
            if (text == null) values.remove(conversationId) else values[conversationId] = text
        }
    }

    private class TestEnvironment : ComposerEnvironment {
        override val connectionState = MutableStateFlow(DeviceConnectionState.READY)
        override val device = MutableStateFlow<ComposerDeviceInfo?>(null)
        override val mentionContacts = MutableStateFlow<List<ContactDTO>>(emptyList())
        override val recentSenderOrder = MutableStateFlow<Map<String, UInt>>(emptyMap())
        override val location = object : ComposerLocationSource {
            override val isAuthorized = false
            override val currentCoordinate: Coordinate? = null
            override suspend fun requestCurrentLocation(timeoutMillis: Long): Coordinate? = null
        }
    }

    private class FailingSendPort(
        private val radio: RadioId,
        private val enqueueFailure: Throwable,
    ) : ComposerSendPort {
        val enqueueEntered = CompletableDeferred<Unit>()
        val releaseEnqueue = CompletableDeferred<Unit>()
        val pending = mutableListOf<MessageDTO>()
        val enqueued = mutableListOf<DirectMessageEnvelope>()
        val localEnqueueFailures = mutableListOf<UUID>()

        override suspend fun createPendingDirect(
            text: String,
            contact: ContactDTO,
            textType: TextType,
            replyToId: UUID?,
        ): MessageDTO = MessageDTO(
            radioId = radio,
            contactID = contact.id,
            text = text,
            timestamp = 1u,
            createdAt = Instant.EPOCH,
            sortDate = Instant.EPOCH,
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.PENDING,
        ).also(pending::add)

        override suspend fun createPendingChannel(
            text: String,
            channel: ChannelDTO,
            textType: TextType,
        ): MessageDTO = error("Direct-message test must not create a channel message")

        override suspend fun enqueueDM(envelope: DirectMessageEnvelope) {
            enqueued += envelope
            enqueueEntered.complete(Unit)
            releaseEnqueue.await()
            throw enqueueFailure
        }

        override suspend fun enqueueChannel(envelope: ChannelMessageEnvelope) {
            error("Direct-message test must not enqueue a channel message")
        }

        override suspend fun signalDMEnqueued(envelope: DirectMessageEnvelope) {
            error("Direct-message composer must not use the retry-only signal path")
        }

        override suspend fun recordLocalEnqueueFailure(messageId: UUID) {
            localEnqueueFailures += messageId
        }
    }
}
