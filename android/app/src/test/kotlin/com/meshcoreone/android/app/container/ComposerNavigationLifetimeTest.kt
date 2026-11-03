// AndroidOnly: WP-308 Real Room/queue acceptance must outlive the mounted conversation, not its radio.
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.app.navigation.AppChatConversationScreen
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.feature.chats.composer.*
import com.meshcoreone.android.feature.chats.composer.ui.ConversationLinkActions
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31, 37], qualifiers = "en-rUS-w390dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerNavigationLifetimeTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private enum class Boundary { BEFORE_START, BEFORE_CREATE, AFTER_CREATE, BEFORE_QUEUE, AFTER_QUEUE }

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    @Test fun directBeforeLaunch() = exercise(false, Boundary.BEFORE_START)
    @Test fun channelBeforeLaunch() = exercise(true, Boundary.BEFORE_START)
    @Test fun directBeforeCommit() = exercise(false, Boundary.BEFORE_CREATE)
    @Test fun channelBeforeCommit() = exercise(true, Boundary.BEFORE_CREATE)
    @Test fun directCommittedBeforeReturn() = exercise(false, Boundary.AFTER_CREATE)
    @Test fun channelCommittedBeforeReturn() = exercise(true, Boundary.AFTER_CREATE)
    @Test fun directBeforeQueueAcceptance() = exercise(false, Boundary.BEFORE_QUEUE)
    @Test fun channelBeforeQueueAcceptance() = exercise(true, Boundary.BEFORE_QUEUE)
    @Test fun directAfterQueueAcceptance() = exercise(false, Boundary.AFTER_QUEUE)
    @Test fun channelAfterQueueAcceptance() = exercise(true, Boundary.AFTER_QUEUE)
    @Test fun directReplacementSessionCannotAdoptAcceptedSend() =
        exercise(false, Boundary.BEFORE_CREATE, replaceSession = true)
    @Test fun channelReplacementSessionCannotAdoptAcceptedSend() =
        exercise(true, Boundary.BEFORE_CREATE, replaceSession = true)
    @Test fun directReplacementSessionCannotAdoptCommittedSend() =
        exercise(false, Boundary.AFTER_CREATE, replaceSession = true)
    @Test fun channelReplacementSessionCannotAdoptCommittedSend() =
        exercise(true, Boundary.AFTER_CREATE, replaceSession = true)
    @Test fun formerDirectScreenOwnedSendLosesTextBeforeCreation() =
        exercise(false, Boundary.BEFORE_CREATE, legacyScreenOwner = true)
    @Test fun formerChannelScreenOwnedSendLosesTextBeforeCreation() =
        exercise(true, Boundary.BEFORE_CREATE, legacyScreenOwner = true)
    @Test fun directUnsavedCancellationRestoresOnlyUnchangedDraft() =
        exercise(false, Boundary.BEFORE_CREATE, replaceSession = true, preserveNewer = false)
    @Test fun channelUnsavedCancellationRestoresOnlyUnchangedDraft() =
        exercise(true, Boundary.BEFORE_CREATE, replaceSession = true, preserveNewer = false)
    @Test fun directUnsavedCancellationDoesNotUndoNewerExplicitClear() =
        exercise(false, Boundary.BEFORE_CREATE, replaceSession = true, typeThenClear = true)
    @Test fun channelUnsavedCancellationDoesNotUndoNewerExplicitClear() =
        exercise(true, Boundary.BEFORE_CREATE, replaceSession = true, typeThenClear = true)
    @Test fun directCommittedCreationFailureNeverRestoresOrRequeuesText() =
        exercise(false, Boundary.AFTER_CREATE, creationFailure = true)
    @Test fun channelCommittedCreationFailureNeverRestoresOrRequeuesText() =
        exercise(true, Boundary.AFTER_CREATE, creationFailure = true)
    @Test fun directUncertainCreationNeverBlindlyRestoresOrRequeuesText() =
        exercise(false, Boundary.AFTER_CREATE, creationFailure = true, reconciliationFailure = true)
    @Test fun channelUncertainCreationNeverBlindlyRestoresOrRequeuesText() =
        exercise(true, Boundary.AFTER_CREATE, creationFailure = true, reconciliationFailure = true)
    @Test fun directDurableQueueFailureIsNotResubmitted() =
        exercise(false, Boundary.AFTER_QUEUE, queueFailure = true)
    @Test fun channelDurableQueueFailureIsNotResubmitted() =
        exercise(true, Boundary.AFTER_QUEUE, queueFailure = true)
    @Test fun directMountedTimelineAdmitsCommittedFailureBeforeCreateReturns() =
        exercise(false, Boundary.AFTER_CREATE, creationFailure = true, dispose = false)
    @Test fun channelMountedTimelineAdmitsCommittedFailureBeforeCreateReturns() =
        exercise(true, Boundary.AFTER_CREATE, creationFailure = true, dispose = false)
    @Test fun channelSlotReplacementNeverRestoresOldUnsavedDraft() =
        exercise(true, Boundary.BEFORE_CREATE, replaceSlot = true, preserveNewer = false)
    @Test fun channelSlotReplacementNeverEnqueuesCommittedOldText() =
        exercise(true, Boundary.AFTER_CREATE, replaceSlot = true, preserveNewer = false)
    @Test fun channelUnreadableRecoveryRetainsTextUntilOriginalSlotCanBeRead() =
        exercise(true, Boundary.BEFORE_START, lookupFailure = true, preserveNewer = false)
    @Test fun channelUnreadableRecoveryDoesNotOverwriteNewerTyping() =
        exercise(true, Boundary.BEFORE_START, lookupFailure = true)
    @Test fun channelUnreadableRecoveryDoesNotRestoreIntoReplacementSlot() =
        exercise(true, Boundary.BEFORE_START, lookupFailure = true, replaceSlot = true, preserveNewer = false)

    private fun exercise(
        channel: Boolean, boundary: Boundary, replaceSession: Boolean = false, legacyScreenOwner: Boolean = false,
        preserveNewer: Boolean = true, typeThenClear: Boolean = false,
        creationFailure: Boolean = false, reconciliationFailure: Boolean = false, queueFailure: Boolean = false,
        dispose: Boolean = true, replaceSlot: Boolean = false,
        lookupFailure: Boolean = false,
    ) = runTest {
        val peerKey = key(4)
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        val mounted = mutableStateOf(true)
        var creations = 0
        var enqueues = 0
        var captured: MessageDTO? = null
        val envelopes = mutableListOf<ChannelMessageEnvelope>()
        val original = "Accepted \u4e2d\uD83D\uDE80 text"
        val newer = "Newer draft"
        var replacement: ComposerStateHolder? = null
        var abortSlotWrite = false
        var unreadableChannel = false
        var failedChannelReads = 0
        suspend fun pause(at: Boundary) {
            if (boundary == at) {
                reached.complete(Unit)
                gate.await()
            }
        }
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun fetchChannel(radioId: RadioId, index: UByte): ChannelDTO? {
                if (unreadableChannel) {
                    failedChannelReads++
                    throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                }
                return store.fetchChannel(radioId, index)
            }
            override suspend fun saveMessage(dto: MessageDTO) {
                if (dto.text != original) return store.saveMessage(dto)
                pause(Boundary.BEFORE_CREATE)
                if (abortSlotWrite) throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                store.saveMessage(dto)
                creations++
                captured = dto
                pause(Boundary.AFTER_CREATE)
                if (creationFailure) throw PersistenceStoreException(PersistenceStoreError.InvalidData)
            }
            override suspend fun fetchMessage(key: EntityKey): MessageDTO? {
                if (reconciliationFailure && key.id == captured?.id) {
                    throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                }
                return store.fetchMessage(key)
            }
            override suspend fun insertPendingSendAssigningSequence(dto: PendingSendDTO): Long {
                pause(Boundary.BEFORE_QUEUE)
                val sequence = store.insertPendingSendAssigningSequence(dto)
                pause(Boundary.AFTER_QUEUE)
                if (queueFailure) throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                return sequence
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        h.links.configureRadio = { radio ->
            radio.knownContacts += contactFrame(peerKey, "Lifetime peer")
            radio.respond = { frame ->
                if (frame[0].toInt() in 2..3) listOf(Bytes.of(1, if (channel) 6 else 2)) else null
            }
        }
        try {
            val connectionTarget = h.target(UUID.fromString("30800000-0000-0000-0000-000000000524"))
            h.manager.connect(connectionTarget)
            h.settle()
            h.assertReady()
            val initiating = requireNotNull(h.sessions.current)
            val radio = requireNotNull(h.appState.currentRadioId)
            val contact = requireNotNull(store.fetchContact(radio, peerKey))
            val slot = ChannelDTO(radioId = radio, index = 0u, name = "Lifetime channel")
            store.saveChannel(slot)
            val target = if (channel) ComposerTarget.Channel(slot) else ComposerTarget.Direct(contact)
            val conversation = if (channel) TimelineConversation.Channel(slot) else TimelineConversation.Direct(contact)
            val feature = h.container.chatsFeature
            val location = object : ComposerLocationSource {
                override val isAuthorized = false
                override val currentCoordinate: Coordinate? = null
                override suspend fun requestCurrentLocation(timeoutMillis: Long): Coordinate? =
                    error("Send acceptance must not request location")
            }
            val decorate: (ComposerSendPort) -> ComposerSendPort = { underlying ->
                object : ComposerSendPort by underlying {
                    override suspend fun enqueueDM(envelope: DirectMessageEnvelope) {
                        enqueues++
                        underlying.enqueueDM(envelope)
                    }
                    override suspend fun enqueueChannel(envelope: ChannelMessageEnvelope) {
                        enqueues++
                        envelopes += envelope
                        underlying.enqueueChannel(envelope)
                    }
                }
            }
            lateinit var holder: ComposerStateHolder
            lateinit var timeline: ChatViewModel
            compose.runOnUiThread {
                host.get().setContent {
                    if (mounted.value) {
                        val scope = rememberCoroutineScope()
                        val environment = remember { feature.environment(target, scope, location) }
                        holder = remember {
                            if (legacyScreenOwner) ComposerStateHolder(
                                target, decorate(feature.sender(radio)), feature.drafts, environment, scope,
                                ComposerTimer { delay(it) },
                            ) else feature.composer(target, environment, scope, decorate)
                        }
                        timeline = remember { ChatViewModel(conversation, feature.timeline(conversation), scope) }
                        MeshCoreTheme(motionScale = 0f) {
                            AppChatConversationScreen(
                                timeline, holder, "Message",
                                ConversationLinkActions(
                                    { listOf(contact) }, { "TestNode" }, { radio },
                                    { error("No navigation expected") }, { error("No external action expected") },
                                    { error("No contact selection expected") },
                                ),
                                currentUserName = "TestNode", onPickContact = { error("No picker expected") },
                                scrollTo = null, onScrollConsumed = {},
                                report = feature::report, attachments = { _, _ -> },
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            h.eventually("mounted composer is ready") {
                holder.state.value.isConnected && h.manager.connectionState == DeviceConnectionState.READY
            }
            // Same UI turn: accepted text clears before the process dispatcher is allowed to start.
            compose.runOnIdle {
                holder.onDraftChanged(original)
                unreadableChannel = lookupFailure
                assertTrue(holder.send())
                assertFalse(holder.send())
                assertEquals("", holder.state.value.draft)
                if (boundary == Boundary.BEFORE_START) {
                    assertEquals(0, creations)
                    assertEquals(0, enqueues)
                    mounted.value = false
                }
            }
            if (boundary != Boundary.BEFORE_START) {
                h.eventually("real acceptance reaches $boundary") { reached.isCompleted }
                if (boundary in listOf(Boundary.AFTER_CREATE, Boundary.BEFORE_QUEUE, Boundary.AFTER_QUEUE)) {
                    val saved = requireNotNull(captured)
                    assertEquals(original, requireNotNull(store.fetchMessage(EntityKey(radio, saved.id))).text)
                    if (boundary == Boundary.AFTER_QUEUE) {
                        assertEquals(1, store.fetchPendingSendsForMessage(EntityKey(radio, saved.id)).size)
                    }
                } else assertEquals(0, creations)
                if (dispose) compose.runOnIdle { mounted.value = false }
            }
            compose.waitForIdle()
            if (legacyScreenOwner) {
                gate.complete(Unit)
                h.settle()
                assertNull("Former screen ownership loses the cleared draft", feature.drafts.get(target.conversationId))
                val absent = if (channel) store.fetchMessages(radio, slot.index, 100, 0)
                    else store.fetchMessages(EntityKey(radio, contact.id), 100, 0)
                assertTrue("No durable row owns the accepted text", absent.isEmpty())
                assertEquals(0, creations)
                assertEquals(0, enqueues)
                return@runTest
            }
            val nextComposer = feature.composer(
                target, feature.environment(target, backgroundScope, location), backgroundScope,
            ).also { replacement = it }
            if (preserveNewer) {
                nextComposer.onDraftChanged(newer)
                if (typeThenClear) nextComposer.onDraftChanged("")
            }
            if (replaceSession) {
                h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
                h.manager.connect(connectionTarget)
                h.eventually("same radio has a distinct ready session") {
                    h.manager.connectionState == DeviceConnectionState.READY &&
                        h.sessions.current != null && h.sessions.current !== initiating
                }
                assertEquals(radio, requireNotNull(h.sessions.current).token.radioId)
            }
            if (replaceSlot) {
                store.saveChannel(slot.copy(secret = Bytes(ByteArray(16) { 9 })))
                h.appState.handleChannelSlotOccupantChanged(radio, setOf(slot.index))
                abortSlotWrite = boundary == Boundary.BEFORE_CREATE
            }
            if (lookupFailure) {
                h.eventually("creation and recovery report unreadable channel without losing custody") {
                    failedChannelReads >= 2 && holder.state.value.sendPhase is ComposerSendPhase.Failed
                }
                assertNull(feature.drafts.get(target.conversationId).takeIf { !preserveNewer })
                unreadableChannel = false
                h.appState.refreshConversations()
            }
            gate.complete(Unit)
            h.eventually("accepted operation settles after disposal") {
                holder.state.value.sendPhase !is ComposerSendPhase.Sending
            }
            val expectedDraft = when {
                preserveNewer -> if (typeThenClear) null else newer
                lookupFailure && !replaceSlot -> original
                replaceSession && boundary == Boundary.BEFORE_CREATE -> original
                else -> null
            }
            h.eventually("draft recovery respects the current revision and slot") {
                feature.drafts.get(target.conversationId) == expectedDraft
            }
            assertEquals(expectedDraft, feature.drafts.get(target.conversationId))
            h.eventually("replacement composer observes recovery without overwriting newer typing") {
                nextComposer.state.value.draft == expectedDraft.orEmpty()
            }
            val rows = if (channel) store.fetchMessages(radio, slot.index, 100, 0)
                else store.fetchMessages(EntityKey(radio, contact.id), 100, 0)
            if (lookupFailure) {
                assertEquals(0, creations)
                assertEquals(0, enqueues)
                assertTrue(rows.isEmpty())
                assertTrue(h.links.radios.single().sent.none { it[0].toInt() in 2..3 })
            } else if (replaceSlot) {
                val committed = boundary == Boundary.AFTER_CREATE
                assertEquals(if (committed) 1 else 0, creations)
                assertEquals(0, enqueues)
                assertEquals(if (committed) 1 else 0, rows.size)
                if (committed) assertEquals(MessageStatus.FAILED, rows.single().status)
                assertTrue(h.links.radios.single().sent.none { it[0].toInt() in 2..3 })
                assertNull(feature.drafts.get(target.conversationId))
            } else if (creationFailure) {
                assertEquals(1, creations)
                assertEquals(1, rows.size)
                assertEquals(requireNotNull(captured).id, rows.single().id)
                assertEquals(original, rows.single().text)
                assertEquals(0, enqueues)
                assertTrue(holder.state.value.sendPhase is ComposerSendPhase.Failed)
                assertEquals(
                    if (reconciliationFailure) MessageStatus.PENDING else MessageStatus.FAILED,
                    rows.single().status,
                )
                assertTrue(h.links.radios.all { link -> link.sent.none { it[0].toInt() in 2..3 } })
                if (!dispose) {
                    h.eventually("mounted timeline admits the exact failed creation for retry") {
                        timeline.state.value.messages.any {
                            it.id == rows.single().id && it.status == MessageStatus.FAILED
                        }
                    }
                }
            } else if (queueFailure) {
                assertEquals(1, creations)
                assertEquals(1, enqueues)
                val saved = rows.single()
                assertEquals(MessageStatus.PENDING, saved.status)
                assertEquals(1, store.fetchPendingSendsForMessage(EntityKey(radio, saved.id)).size)
                assertTrue(h.links.radios.single().sent.none { it[0].toInt() in 2..3 })
                h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
                h.manager.connect(connectionTarget)
                h.eventually("replacement drains the one durable queue row") {
                    runBlocking {
                        store.fetchMessage(EntityKey(radio, saved.id))?.status == MessageStatus.FAILED &&
                            store.fetchPendingSendsForMessage(EntityKey(radio, saved.id)).isEmpty()
                    }
                }
                assertEquals(1, h.links.radios.sumOf { link -> link.sent.count { it[0].toInt() == if (channel) 3 else 2 } })
                assertEquals(1, creations)
                assertEquals(1, enqueues)
            } else if (replaceSession) {
                val committed = boundary == Boundary.AFTER_CREATE
                assertEquals(if (committed) 1 else 0, creations)
                assertEquals(if (committed) 1 else 0, rows.size)
                assertTrue(holder.state.value.sendPhase is ComposerSendPhase.Failed)
                if (committed) assertEquals(MessageStatus.FAILED, rows.single().status)
                assertEquals(0, enqueues)
                assertTrue(h.links.radios.all { link -> link.sent.none { it[0].toInt() in 2..3 } })
            } else {
                assertEquals(1, creations)
                assertEquals(1, enqueues)
                assertEquals(1, rows.size)
                val saved = rows.single()
                assertEquals(requireNotNull(captured).id, saved.id)
                assertEquals(radio, saved.radioId)
                assertEquals(original, saved.text)
                assertEquals(if (channel) slot.index else null, saved.channelIndex)
                h.eventually("real queue emits exactly one terminal send") {
                    runBlocking {
                        store.fetchMessage(EntityKey(radio, saved.id))?.status == MessageStatus.FAILED &&
                            store.fetchPendingSendsForMessage(EntityKey(radio, saved.id)).isEmpty()
                    }
                }
                assertEquals(1, h.links.radios.single().sent.count { it[0].toInt() == if (channel) 3 else 2 })
                assertTrue(store.fetchPendingSendsForMessage(EntityKey(radio, saved.id)).isEmpty())
                if (channel) {
                    assertEquals(saved.id, envelopes.single().messageID)
                    assertFalse(envelopes.single().isResend)
                }
            }
        } finally {
            gate.complete(Unit)
            replacement?.close()
            compose.runOnUiThread { host.get().setContent {} }
            compose.waitForIdle()
            h.container.close()
        }
    }
}
