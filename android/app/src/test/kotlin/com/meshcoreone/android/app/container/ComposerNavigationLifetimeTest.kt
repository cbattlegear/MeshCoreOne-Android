// AndroidOnly: WP-308 Real Room/queue acceptance must outlive the mounted conversation, not its radio.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.navigation.AppChatConversationScreen
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryRejected
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceError
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.feature.chats.composer.*
import com.meshcoreone.android.feature.chats.composer.ui.ConversationLinkActions
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import com.meshcoreone.android.feature.chats.timeline.TimelineEvent
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

@Config(sdk = [31, 37], qualifiers = "en-rUS-w390dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerNavigationLifetimeTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private enum class Boundary { BEFORE_START, BEFORE_CREATE, AFTER_CREATE, BEFORE_QUEUE, AFTER_QUEUE }
    private enum class RecoveryFailure { PENDING_READ, STATUS_WRITE }

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    @Test fun sendAgainRefusesLegacyUnboundChannelTextBeforeQueueAdmission() =
        channelSendAgainOrigin(unbound = true, replaced = false)

    @Test fun sendAgainRefusesPersistedOldOccupantBeforeQueueAdmission() =
        channelSendAgainOrigin(unbound = false, replaced = true)

    @Test fun currentPersistedOriginReachesConnectivityCheckWithoutBeingRelabeled() =
        channelSendAgainOrigin(unbound = false, replaced = false)

    @Test fun missingOriginValidationCannotAdmitSendAgainOrMasqueradeAsMissingChannel() =
        channelSendAgainOrigin(unbound = false, replaced = false, hideHistoryRole = true)

    private fun channelSendAgainOrigin(
        unbound: Boolean, replaced: Boolean, hideHistoryRole: Boolean = false,
    ) = runTest {
        val radio = RadioId(UUID(0, 710))
        val slot = com.meshcoreone.android.core.protocol.event.ChannelInfo(
            2u, "Original", Bytes(ByteArray(16) { 1 }),
        )
        store.saveChannel(radio, slot)
        val originalOccupant = requireNotNull(store.fetchChannelOccupantID(radio, slot.index))
        val original = MessageDTO(
            id = UUID(0, 711), radioId = radio, channelIndex = slot.index,
            text = "  original channel text \u4e2d\uD83D\uDE80  ", timestamp = 100u,
            status = MessageStatus.FAILED, channelOccupantID = originalOccupant.takeUnless { unbound },
        )
        store.saveMessage(original)
        if (replaced) store.saveChannel(radio, slot.copy(name = "Replacement", secret = Bytes(ByteArray(16) { 2 })))
        assertEquals(original, store.fetchMessage(EntityKey(radio, original.id)))
        if (replaced) assertNotEquals(originalOccupant, store.fetchChannelOccupantID(radio, slot.index))
        val current = requireNotNull(store.fetchChannel(radio, slot.index))
        val processStore = if (hideHistoryRole) object : PersistenceStoreProtocol by store {} else store
        val rig = ContainerHarness(this, store, persistenceOverride = processStore)
        try {
            try {
                rig.container.chatsFeature.sendAgain(original, TimelineConversation.Channel(current))
                fail("Offline Send Again must not admit a queue submission")
            } catch (rejected: MessageServiceException) {
                assertTrue(unbound || replaced)
                assertEquals(MessageServiceError.ChannelNotFound, rejected.error)
                assertTrue(rejected.cause is PersistenceStoreException)
                assertTrue(rejected.cause?.cause is ChannelHistoryRejected)
            } catch (offline: ChatSendQueueServiceException) {
                assertFalse(hideHistoryRole)
                assertFalse(unbound || replaced)
                assertEquals(ChatSendQueueServiceError.NotConnected, offline.error)
            } catch (unavailable: PersistenceStoreException) {
                assertTrue(hideHistoryRole)
                assertEquals(
                    PersistenceStoreError.FetchFailed("Channel origin validation is unavailable"),
                    unavailable.error,
                )
            }
            assertEquals(original, store.fetchMessage(EntityKey(radio, original.id)))
            assertTrue(store.fetchPendingSendsForMessage(EntityKey(radio, original.id)).isEmpty())
            assertTrue(rig.links.links.isEmpty())
        } finally {
            rig.container.close()
        }
    }

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
    @Test fun directCommittedFailureRepairsFirstMessageListVisibility() =
        exercise(false, Boundary.AFTER_CREATE, creationFailure = true, dispose = false)
    @Test fun directFailurePreservesNewerLiveWhitespace() =
        exercise(false, Boundary.AFTER_CREATE, creationFailure = true, dispose = false, newerLiveText = " \n ")
    @Test fun channelFailurePreservesNewerLiveWhitespace() =
        exercise(true, Boundary.AFTER_CREATE, creationFailure = true, dispose = false, newerLiveText = " \n ")
    @Test fun directCommittedRecoveryRetriesFailedPendingReadWithoutSending() =
        exercise(false, Boundary.AFTER_CREATE, creationFailure = true, dispose = false,
            recoveryFailure = RecoveryFailure.PENDING_READ)
    @Test fun channelCommittedRecoveryRetriesFailedPendingReadWithoutSending() =
        exercise(true, Boundary.AFTER_CREATE, creationFailure = true, dispose = false,
            recoveryFailure = RecoveryFailure.PENDING_READ)
    @Test fun directCommittedRecoveryRetriesFailedStatusWriteWithoutSending() =
        exercise(false, Boundary.AFTER_CREATE, creationFailure = true, dispose = false,
            recoveryFailure = RecoveryFailure.STATUS_WRITE)
    @Test fun channelCommittedRecoveryRetriesFailedStatusWriteWithoutSending() =
        exercise(true, Boundary.AFTER_CREATE, creationFailure = true, dispose = false,
            recoveryFailure = RecoveryFailure.STATUS_WRITE)
    @Test fun directUnknownCreationReconcilesExactRowIntoMountedRetryWithoutSending() =
        exercise(false, Boundary.AFTER_CREATE, creationFailure = true, reconciliationFailure = true,
            reconcileLater = true, dispose = false)
    @Test fun channelUnknownCreationReconcilesExactRowIntoMountedRetryWithoutSending() =
        exercise(true, Boundary.AFTER_CREATE, creationFailure = true, reconciliationFailure = true,
            reconcileLater = true, dispose = false)
    @Test fun directUnknownCreationDoesNotReplayOnReplacementSession() =
        exercise(false, Boundary.AFTER_CREATE, creationFailure = true, reconciliationFailure = true,
            reconcileLater = true, replaceSession = true)
    @Test fun channelUnknownCreationDoesNotReplayOnReplacementSession() =
        exercise(true, Boundary.AFTER_CREATE, creationFailure = true, reconciliationFailure = true,
            reconcileLater = true, replaceSession = true)
    @Test fun directUnknownAbsentCreationRestoresOnlyAfterExactKeyRead() =
        exercise(false, Boundary.BEFORE_CREATE, creationFailure = true, reconciliationFailure = true,
            reconcileLater = true, failBeforeCommit = true, preserveNewer = false)
    @Test fun channelUnknownAbsentCreationRestoresOnlyAfterExactKeyRead() =
        exercise(true, Boundary.BEFORE_CREATE, creationFailure = true, reconciliationFailure = true,
            reconcileLater = true, failBeforeCommit = true, preserveNewer = false)
    @Test fun directUnknownAbsentCreationNeverOverwritesNewerDraft() =
        exercise(false, Boundary.BEFORE_CREATE, creationFailure = true, reconciliationFailure = true,
            reconcileLater = true, failBeforeCommit = true)
    @Test fun channelUnknownAbsentCreationNeverOverwritesNewerDraft() =
        exercise(true, Boundary.BEFORE_CREATE, creationFailure = true, reconciliationFailure = true,
            reconcileLater = true, failBeforeCommit = true)
    @Test fun directUnknownCreationReconcilesAfterTheSavedRowWasMarkedRead() =
        exercise(false, Boundary.AFTER_CREATE, creationFailure = true, reconciliationFailure = true,
            reconcileLater = true, markSavedReadBeforeReconcile = true, dispose = false)
    @Test fun channelUnknownCreationReconcilesAfterTheSavedRowWasMarkedRead() =
        exercise(true, Boundary.AFTER_CREATE, creationFailure = true, reconciliationFailure = true,
            reconcileLater = true, markSavedReadBeforeReconcile = true, dispose = false)
    @Test fun channelSuccessfulHeldSaveUnderReplacementRejectsActualRetry() =
        exercise(true, Boundary.BEFORE_CREATE, replaceSlot = true, preserveNewer = false,
            successfulReplacedSave = true)
    @Test fun channelOriginalCommittedFailureAllowsExplicitRetry() =
        exercise(true, Boundary.AFTER_CREATE, creationFailure = true, dispose = false, explicitRetry = true)
    @Test fun channelSuccessfulHeldSaveUnderReplacementRejectsSendAgainAfterRoomReopen() =
        exercise(true, Boundary.BEFORE_CREATE, replaceSlot = true, preserveNewer = false,
            successfulReplacedSave = true, restartSendAgain = true)

    @Test fun directLocalEnqueueFailureRetainsRecoveryAcrossPendingReadFailure() =
        exercise(false, Boundary.BEFORE_QUEUE, localEnqueueFailure = true,
            recoveryFailure = RecoveryFailure.PENDING_READ)
    @Test fun channelLocalEnqueueFailureRetainsRecoveryAcrossPendingReadFailure() =
        exercise(true, Boundary.BEFORE_QUEUE, localEnqueueFailure = true,
            recoveryFailure = RecoveryFailure.PENDING_READ)
    @Test fun directLocalEnqueueFailureRetainsRecoveryAcrossStatusWriteFailure() =
        exercise(false, Boundary.BEFORE_QUEUE, localEnqueueFailure = true,
            recoveryFailure = RecoveryFailure.STATUS_WRITE)
    @Test fun channelLocalEnqueueFailureRetainsRecoveryAcrossStatusWriteFailure() =
        exercise(true, Boundary.BEFORE_QUEUE, localEnqueueFailure = true,
            recoveryFailure = RecoveryFailure.STATUS_WRITE)
    @Test fun channelReplacedBeforeEnqueueRetainsOriginalPendingRowAndRejectsRetry() =
        exercise(true, Boundary.BEFORE_QUEUE, localEnqueueFailure = true,
            replaceSlot = true, preserveNewer = false)

    private class DiskHistoryStore(context: Context, name: String) {
        private val ownerJob = SupervisorJob()
        private val owner = CoroutineScope(ownerJob + Dispatchers.Default)
        private val immediate = java.util.concurrent.Executor { it.run() }
        val database = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        val store = RoomPersistenceStore(database, owner)

        suspend fun close() = withContext(NonCancellable) {
            try {
                store.close()
            } finally {
                try {
                    ownerJob.cancelAndJoin()
                } finally {
                    database.close()
                }
            }
        }
    }

    private fun exercise(
        channel: Boolean, boundary: Boundary, replaceSession: Boolean = false, legacyScreenOwner: Boolean = false,
        preserveNewer: Boolean = true, typeThenClear: Boolean = false,
        creationFailure: Boolean = false, reconciliationFailure: Boolean = false, queueFailure: Boolean = false,
        dispose: Boolean = true, replaceSlot: Boolean = false,
        lookupFailure: Boolean = false,
        newerLiveText: String? = null, recoveryFailure: RecoveryFailure? = null,
        reconcileLater: Boolean = false, failBeforeCommit: Boolean = false,
        markSavedReadBeforeReconcile: Boolean = false,
        successfulReplacedSave: Boolean = false, explicitRetry: Boolean = false,
        restartSendAgain: Boolean = false,
        localEnqueueFailure: Boolean = false,
    ) = runTest {
        require(!explicitRetry || !dispose) { "Explicit Retry must reach the mounted timeline control" }
        require(!restartSendAgain || (channel && successfulReplacedSave && replaceSlot && dispose)) {
            "Restart Send Again requires the held successful channel replacement"
        }
        val diskContext = if (restartSendAgain) ApplicationProvider.getApplicationContext<Context>() else null
        val diskName = "wp308-send-again-${UUID.randomUUID()}.db"
        var disk = diskContext?.let { DiskHistoryStore(it, diskName) }
        val store = disk?.store ?: this@ComposerNavigationLifetimeTest.store
        var initialContainerClosed = false
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
        var recoveryUnavailable = false
        var failedRecoveryOperations = 0
        var unreadableCreation = reconciliationFailure
        val replacementEvents = mutableListOf<TimelineEvent>()
        var replacementConversation: TimelineConversation.Channel? = null
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
                captured = dto
                pause(Boundary.BEFORE_CREATE)
                if (abortSlotWrite || failBeforeCommit) {
                    throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                }
                store.saveMessage(dto)
                creations++
                captured = dto
                pause(Boundary.AFTER_CREATE)
                if (creationFailure) {
                    recoveryUnavailable = recoveryFailure != null
                    throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                }
            }
            override suspend fun fetchMessage(key: EntityKey): MessageDTO? {
                if (unreadableCreation && key.id == captured?.id) {
                    throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                }
                return store.fetchMessage(key)
            }
            override suspend fun fetchPendingSendsForMessage(key: EntityKey): SnapshotList<PendingSendDTO> {
                if (recoveryUnavailable && recoveryFailure == RecoveryFailure.PENDING_READ &&
                    key.id == captured?.id
                ) {
                    failedRecoveryOperations++
                    throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                }
                return store.fetchPendingSendsForMessage(key)
            }
            override suspend fun updateMessageStatusUnlessDelivered(key: EntityKey, status: MessageStatus): Boolean {
                if (recoveryUnavailable && recoveryFailure == RecoveryFailure.STATUS_WRITE &&
                    key.id == captured?.id
                ) {
                    failedRecoveryOperations++
                    throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                }
                return store.updateMessageStatusUnlessDelivered(key, status)
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
                when (frame[0].toInt()) {
                    2, 3 -> listOf(Bytes.of(1, if (channel) 6 else 2))
                    0x20 -> listOf(Bytes.of(0))
                    else -> null
                }
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
            if (!channel && creationFailure) assertNull("First outgoing message has no list metadata", contact.lastMessageDate)
            val slot = if (restartSendAgain) {
                initiating.channelService.setChannelWithSecret(
                    radio, 0u, "Lifetime channel", Bytes(ByteArray(16) { 5 }),
                )
                requireNotNull(store.fetchChannel(radio, 0u))
            } else {
                ChannelDTO(radioId = radio, index = 0u, name = "Lifetime channel").also { store.saveChannel(it) }
            }
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
                    private suspend fun beforeEnqueue() {
                        if (!localEnqueueFailure) return
                        pause(Boundary.BEFORE_QUEUE)
                        if (!replaceSlot) {
                            recoveryUnavailable = recoveryFailure != null
                            throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                        }
                    }
                    override suspend fun enqueueDM(envelope: DirectMessageEnvelope) {
                        enqueues++
                        beforeEnqueue()
                        underlying.enqueueDM(envelope)
                    }
                    override suspend fun enqueueChannel(envelope: ChannelMessageEnvelope) {
                        enqueues++
                        envelopes += envelope
                        beforeEnqueue()
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
                if (newerLiveText != null) {
                    compose.runOnIdle { holder.onDraftChanged(newerLiveText) }
                } else {
                    nextComposer.onDraftChanged(newer)
                    if (typeThenClear) nextComposer.onDraftChanged("")
                }
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
                val secret = Bytes(ByteArray(16) { 9 })
                if (successfulReplacedSave) {
                    val writesBefore = h.links.radios.single().sent.count { it[0].toInt() == 0x20 }
                    initiating.channelService.setChannelWithSecret(radio, slot.index, slot.name, secret)
                    assertEquals(writesBefore + 1,
                        h.links.radios.single().sent.count { it[0].toInt() == 0x20 })
                    assertEquals(secret, requireNotNull(store.fetchChannel(radio, slot.index)).secret)
                } else {
                    store.saveChannel(slot.copy(secret = secret))
                    h.appState.handleChannelSlotOccupantChanged(radio, setOf(slot.index))
                }
                abortSlotWrite = boundary == Boundary.BEFORE_CREATE && !successfulReplacedSave
                val replacementSlot = requireNotNull(store.fetchChannel(radio, slot.index))
                replacementConversation = TimelineConversation.Channel(replacementSlot)
                val replacementFeed = feature.timeline(requireNotNull(replacementConversation))
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    replacementFeed.events.collect { replacementEvents += it }
                }
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
            if (reconcileLater) {
                val saved = requireNotNull(captured)
                assertEquals(if (failBeforeCommit) null else MessageStatus.PENDING,
                    store.fetchMessage(EntityKey(radio, saved.id))?.status)
                assertEquals(0, enqueues)
                if (markSavedReadBeforeReconcile) {
                    store.markMessageAsRead(EntityKey(radio, saved.id))
                    assertTrue(requireNotNull(store.fetchMessage(EntityKey(radio, saved.id))).isRead)
                }
                unreadableCreation = false
                h.appState.refreshConversations()
                if (!failBeforeCommit) h.eventually("exact identity reconciliation exposes the committed retry row") {
                    runBlocking { store.fetchMessage(EntityKey(radio, saved.id))?.status == MessageStatus.FAILED }
                }
            }
            if (recoveryFailure != null) {
                h.eventually("local committed recovery reports its storage failure") { failedRecoveryOperations > 0 }
                val saved = requireNotNull(captured)
                assertEquals(MessageStatus.PENDING, requireNotNull(store.fetchMessage(EntityKey(radio, saved.id))).status)
                assertTrue(store.fetchPendingSendsForMessage(EntityKey(radio, saved.id)).isEmpty())
                recoveryUnavailable = false
                h.appState.refreshConversations()
                h.eventually("an invalidation recovers the same committed row without queueing") {
                    runBlocking { store.fetchMessage(EntityKey(radio, saved.id))?.status == MessageStatus.FAILED }
                }
            }
            if (newerLiveText != null) assertEquals(newerLiveText, holder.state.value.draft)
            val expectedDraft = when {
                newerLiveText != null -> null
                preserveNewer -> if (typeThenClear) null else newer
                failBeforeCommit && reconcileLater -> original
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
                val committed = boundary == Boundary.AFTER_CREATE || successfulReplacedSave || localEnqueueFailure
                assertEquals(if (committed) 1 else 0, creations)
                assertEquals(if (localEnqueueFailure) 1 else 0, enqueues)
                assertEquals(if (committed) 1 else 0, rows.size)
                if (committed) {
                    assertEquals(MessageStatus.PENDING, rows.single().status)
                    val replacementTarget = requireNotNull(replacementConversation)
                    try {
                        feature.timeline(replacementTarget).retryService.retry(rows.single(), replacementTarget)
                        fail("The replacement occupant must not retry the old committed intent")
                    } catch (failure: MessageServiceException) {
                        assertEquals(MessageServiceError.ChannelNotFound, failure.error)
                    }
                    h.settle()
                    assertTrue("No old-intent event is admitted to the replacement timeline",
                        replacementEvents.none { event ->
                            event is TimelineEvent.MessageReceived && event.message.id == rows.single().id
                        })
                    assertTrue(store.fetchPendingSendsForMessage(EntityKey(radio, rows.single().id)).isEmpty())
                }
                assertTrue(h.links.radios.single().sent.none { it[0].toInt() in 2..3 })
                assertNull(feature.drafts.get(target.conversationId))
                if (restartSendAgain) {
                    val saved = rows.single()
                    val key = EntityKey(radio, saved.id)
                    val replacementSlot = requireNotNull(replacementConversation).channel
                    replacement?.close()
                    replacement = null
                    h.container.close()
                    initialContainerClosed = true
                    requireNotNull(disk).close()
                    disk = null
                    val reopenedDisk = DiskHistoryStore(requireNotNull(diskContext), diskName)
                    disk = reopenedDisk
                    val reopenedStore = reopenedDisk.store
                    assertEquals("Closing and reopening Room preserves the exact old row",
                        saved, reopenedStore.fetchMessage(key))
                    val restarted = ContainerHarness(this, reopenedStore)
                    restarted.links.configureRadio = { firmware ->
                        firmware.respond = { frame ->
                            when (frame[0].toInt()) {
                                3 -> listOf(Bytes.of(1, 6))
                                0x1f -> listOf(
                                    if (frame[1] == replacementSlot.index) {
                                        Bytes.of(0x12, replacementSlot.index.toInt()) +
                                            Bytes.utf8(replacementSlot.name).paddedOrTruncated(32) +
                                            replacementSlot.secret
                                    } else Bytes.of(0x12, frame[1].toInt()) + Bytes(ByteArray(48)),
                                )
                                else -> null
                            }
                        }
                    }
                    try {
                        restarted.manager.connect(connectionTarget)
                        restarted.eventually("recreated process reaches READY with replacement B") {
                            restarted.manager.connectionState == DeviceConnectionState.READY
                        }
                        assertEquals(radio, restarted.appState.currentRadioId)
                        assertNotSame(h.container.chatsFeature, restarted.container.chatsFeature)
                        val currentSlot = requireNotNull(reopenedStore.fetchChannel(radio, slot.index))
                        assertEquals(replacementSlot.secret, currentSlot.secret)
                        val retained = requireNotNull(reopenedStore.fetchMessage(key))
                        assertEquals(saved.id, retained.id)
                        assertEquals(original, retained.text)
                        var refusal: MessageServiceException? = null
                        try {
                            restarted.container.chatsFeature.sendAgain(
                                retained, TimelineConversation.Channel(currentSlot),
                            )
                        } catch (failure: MessageServiceException) {
                            refusal = failure
                        }
                        restarted.eventually("Send Again either refuses or exposes its unexpected wire send") {
                            refusal != null || restarted.links.radios.single().sent.any { it[0].toInt() == 3 }
                        }
                        assertTrue("Recreated process must never send old A text under replacement B",
                            restarted.links.radios.single().sent.none { it[0].toInt() in 2..3 })
                        assertNotNull("Unproved Send Again must return an explicit typed refusal", refusal)
                        assertEquals(MessageServiceError.ChannelNotFound, requireNotNull(refusal).error)
                        assertTrue(reopenedStore.fetchPendingSendsForMessage(key).isEmpty())
                        assertEquals(original, requireNotNull(reopenedStore.fetchMessage(key)).text)
                    } finally {
                        restarted.container.close()
                    }
                }
            } else if (failBeforeCommit) {
                assertEquals(0, creations)
                assertEquals(0, enqueues)
                assertTrue(rows.isEmpty())
                assertTrue(h.links.radios.all { link -> link.sent.none { it[0].toInt() in 2..3 } })
            } else if (creationFailure) {
                assertEquals(1, creations)
                assertEquals(1, rows.size)
                assertEquals(requireNotNull(captured).id, rows.single().id)
                assertEquals(original, rows.single().text)
                if (markSavedReadBeforeReconcile) assertTrue(rows.single().isRead)
                assertEquals(0, enqueues)
                assertTrue(holder.state.value.sendPhase is ComposerSendPhase.Failed)
                assertEquals(
                    if (reconciliationFailure && !reconcileLater) MessageStatus.PENDING else MessageStatus.FAILED,
                    rows.single().status,
                )
                assertTrue(h.links.radios.all { link -> link.sent.none { it[0].toInt() in 2..3 } })
                if (!dispose) {
                    h.eventually("mounted timeline admits the exact failed creation for retry") {
                        timeline.state.value.messages.any {
                            it.id == rows.single().id && it.status == MessageStatus.FAILED
                        }
                    }
                    if (!channel && (!reconciliationFailure || reconcileLater)) {
                        assertTrue("First-message failure remains reachable from Chats",
                            store.fetchConversations(radio).any { it.id == contact.id })
                        assertEquals(rows.single().date,
                            requireNotNull(store.fetchContact(EntityKey(radio, contact.id))).lastMessageDate)
                    }
                    if (explicitRetry) {
                        feature.timeline(conversation).retryService.retry(rows.single(), conversation)
                        h.eventually("explicit Retry on the original occupant sends exactly once") {
                            runBlocking {
                                store.fetchPendingSendsForMessage(EntityKey(radio, rows.single().id)).isEmpty() &&
                                    h.links.radios.single().sent.count { it[0].toInt() == 3 } == 1
                            }
                        }
                        assertEquals(1, creations)
                        assertEquals(1, h.links.radios.single().sent.count { it[0].toInt() == 3 })
                    }
                }
            } else if (localEnqueueFailure) {
                assertEquals(1, creations)
                assertEquals(1, enqueues)
                val saved = rows.single()
                assertEquals(requireNotNull(captured).id, saved.id)
                assertEquals(original, saved.text)
                assertEquals(MessageStatus.FAILED, saved.status)
                assertTrue(store.fetchPendingSendsForMessage(EntityKey(radio, saved.id)).isEmpty())
                assertTrue(h.links.radios.all { link -> link.sent.none { it[0].toInt() in 2..3 } })
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
            try {
                if (!initialContainerClosed) h.container.close()
            } finally {
                try {
                    disk?.close()
                } finally {
                    if (diskContext != null) {
                        assertTrue("Only the named fixture database is removed", diskContext.deleteDatabase(diskName))
                    }
                }
            }
        }
    }
}
