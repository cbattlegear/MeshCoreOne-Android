// AndroidOnly: WP-309 Real composer/shortcut enqueue error after durable commit and independently triggered physical emission.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.feature.chats.composer.*
import com.meshcoreone.android.platform.shortcuts.ShortcutException
import com.meshcoreone.android.platform.shortcuts.ShortcutRecipient
import com.meshcoreone.android.platform.shortcuts.ShortcutError
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DirectEnqueueFailureSafetyTest : RoomProcessTest() {
    @Test fun actualComposerCommitThenErrorCannotOverwriteAnIndependentlyEmittedDirectMessage() =
        commitThenError(shortcut = false)

    @Test fun actualShortcutCommitThenErrorCannotOverwriteAnIndependentlyEmittedDirectMessage() =
        commitThenError(shortcut = true)

    @Test fun actualDirectComposerCannotFailAChannelThatReplacedItsOriginalPendingRow() =
        commitThenError(shortcut = false, replacement = Replacement.CHANNEL)

    @Test fun actualDirectShortcutCannotFailAChannelThatReplacedItsOriginalPendingRow() =
        commitThenError(shortcut = true, replacement = Replacement.CHANNEL)

    @Test fun actualChannelComposerRetainsCommittedPendingOnEnqueueError() =
        commitThenError(shortcut = false, channelTarget = true)

    @Test fun actualChannelShortcutRetainsCommittedPendingOnEnqueueError() =
        commitThenError(shortcut = true, channelTarget = true)

    @Test fun actualChannelComposerCannotFailAReplacementDirectRow() =
        commitThenError(shortcut = false, channelTarget = true, replacement = Replacement.DIRECT)

    @Test fun actualChannelShortcutCannotFailAReplacementDirectRow() =
        commitThenError(shortcut = true, channelTarget = true, replacement = Replacement.DIRECT)

    @Test fun actualChannelComposerCannotFailAReplacementChannelRow() =
        commitThenError(shortcut = false, channelTarget = true, replacement = Replacement.CHANNEL)

    @Test fun actualChannelShortcutCannotFailAReplacementChannelRow() =
        commitThenError(shortcut = true, channelTarget = true, replacement = Replacement.CHANNEL)

    @Test fun actualChannelComposerRetainsOriginalErrorWhenSavedRowWasDeleted() =
        commitThenError(shortcut = false, channelTarget = true, replacement = Replacement.MISSING)

    @Test fun actualChannelShortcutRetainsOriginalErrorWhenSavedRowWasDeleted() =
        commitThenError(shortcut = true, channelTarget = true, replacement = Replacement.MISSING)

    @Test fun actualChannelComposerSuccessfulEnqueueStillSendsNormally() =
        commitThenError(shortcut = false, channelTarget = true, success = true)

    @Test fun actualChannelShortcutSuccessfulEnqueueStillSendsNormally() =
        commitThenError(shortcut = true, channelTarget = true, success = true)

    private enum class Replacement { NONE, DIRECT, CHANNEL, MISSING }

    private fun commitThenError(
        shortcut: Boolean, channelTarget: Boolean = false, replacement: Replacement = Replacement.NONE,
        success: Boolean = false,
    ) = runTest {
        val committed = CompletableDeferred<PendingSendDTO>()
        val rejectCaller = CompletableDeferred<Unit>()
        val failure = PersistenceStoreException(PersistenceStoreError.FetchFailed("post-commit enqueue fixture"))
        var armed = false
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun insertPendingSendAssigningSequence(dto: PendingSendDTO): Long {
                val sequence = store.insertPendingSendAssigningSequence(dto)
                if (!armed) return sequence
                committed.complete(dto.copy(sequence = sequence))
                if (success) return sequence
                rejectCaller.await()
                throw failure
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        h.links.configureRadio = { radio ->
            radio.knownContacts += contactFrame(key(4), "Peer")
            radio.respond = { frame ->
                when (frame[0].toInt()) {
                    0x02 -> listOf(sentReply(frame, key(7)))
                    0x03 -> listOf(com.meshcoreone.android.core.protocol.bytes.Bytes.of(0))
                    else -> null
                }
            }
        }
        var composer: ComposerStateHolder? = null
        var shortcutResult: Deferred<ShortcutException?>? = null
        val reported = mutableListOf<Throwable>()
        val events = mutableListOf<MessageEvent>()
        val eventReader = backgroundScope.launch {
            h.appState.messageEventStream.events().collect { events += it }
        }
        try {
            h.manager.connect(h.target())
            h.eventually("actual enqueue graph ready") {
                h.manager.connectionState == DeviceConnectionState.READY
            }
            val radio = requireNotNull(h.appState.currentRadioId)
            val contact = requireNotNull(store.fetchContact(radio, key(4)))
            val channel = ChannelDTO(radioId = radio, index = 3u, name = "Channel")
            store.saveChannel(channel)
            armed = true
            if (shortcut) {
                shortcutResult = async {
                    try {
                        AppShortcutRadio(h.container).queueMessage(
                            if (channelTarget) ShortcutRecipient.Channel(channel) else ShortcutRecipient.Contact(contact),
                            "Retained on radio",
                        )
                        null
                    } catch (error: ShortcutException) { error }
                }
            } else {
                val environment = object : ComposerEnvironment {
                    override val connectionState = MutableStateFlow(DeviceConnectionState.READY)
                    override val device = MutableStateFlow<ComposerDeviceInfo?>(ComposerDeviceInfo("TestNode", key(7)))
                    override val mentionContacts = MutableStateFlow<List<ContactDTO>>(listOf(contact))
                    override val recentSenderOrder = MutableStateFlow<Map<String, UInt>>(emptyMap())
                    override val location = object : ComposerLocationSource {
                        override val isAuthorized = false
                        override val currentCoordinate: Coordinate? = null
                        override suspend fun requestCurrentLocation(timeoutMillis: Long): Coordinate? = null
                    }
                }
                composer = ComposerStateHolder(
                    if (channelTarget) ComposerTarget.Channel(channel) else ComposerTarget.Direct(contact),
                    h.container.chatsFeature.sender(radio),
                    h.container.chatsFeature.drafts, environment, backgroundScope,
                    ComposerTimer { awaitCancellation() }, ComposerDiagnostics { _, cause -> reported += cause },
                )
                composer.onDraftChanged("Retained on radio")
                assertTrue(composer.send())
            }
            h.eventually("durable insertion completed before caller error", virtualBudgetMillis = 0) {
                committed.isCompleted
            }
            val pending = committed.await()
            val messageKey = EntityKey(radio, pending.messageID)
            if (success) {
                h.eventually("successful channel queue reaches actual protocol", virtualBudgetMillis = 0) {
                    h.links.radios.single().sent.any { it[0] == 0x03.toUByte() } &&
                        runBlocking { store.fetchMessage(messageKey)?.status == MessageStatus.SENT } &&
                        (if (shortcut) shortcutResult?.isCompleted == true
                        else composer?.state?.value?.sendPhase == ComposerSendPhase.Idle)
                }
                if (shortcut) assertNull(requireNotNull(shortcutResult).await())
                else {
                    assertTrue(reported.isEmpty())
                    assertEquals("", requireNotNull(composer).state.value.draft)
                }
                assertTrue(events.none { it is MessageEvent.MessageFailed })
                return@runTest
            }
            assertEquals(1, store.fetchPendingSendsForMessage(messageKey).size)
            // An independent wake is legitimate after the durable commit, even while the
            // original enqueue caller is still waiting for its persistence result.
            if (!channelTarget) {
                requireNotNull(h.sessions.current).chatSendQueueService
                    .signalDMEnqueued(requireNotNull(pending.directMessageEnvelope()))
                h.eventually("real protocol send accepted without delivery ACK", virtualBudgetMillis = 0) {
                    h.links.radios.single().sent.any { it[0] == 0x02.toUByte() } &&
                        runBlocking { store.fetchMessage(messageKey)?.status == MessageStatus.SENT }
                }
            }
            val sent = requireNotNull(store.fetchMessage(messageKey))
            assertEquals(if (channelTarget) MessageStatus.PENDING else MessageStatus.SENT, sent.status)
            if (replacement != Replacement.NONE) {
                store.deleteMessage(messageKey)
                if (replacement != Replacement.MISSING) store.saveMessage(sent.copy(
                    contactID = if (replacement == Replacement.DIRECT) contact.id else null,
                    channelIndex = if (replacement == Replacement.CHANNEL) channel.index else null,
                    text = "Independent replacement", status = MessageStatus.FAILED, failureSeen = false,
                ))
            }
            val before = store.fetchMessage(messageKey)
            val pendingBefore = store.fetchPendingSendsForMessage(messageKey)
            val physicalBefore = h.links.radios.single().sent.count { it[0] == 0x02.toUByte() }
            rejectCaller.complete(Unit)
            h.eventually("original caller observes enqueue error", virtualBudgetMillis = 0) {
                if (shortcut) shortcutResult?.isCompleted == true
                else composer?.state?.value?.sendPhase is ComposerSendPhase.Failed
            }
            if (shortcut) {
                val actual = requireNotNull(requireNotNull(shortcutResult).await())
                assertEquals(ShortcutError.EnqueueUnconfirmed(ShortcutError.SEND_FAILED), actual.error)
                val queueFailure = actual.cause as ChatSendQueueServiceException
                assertSame(failure, queueFailure.cause)
            } else {
                val queueFailure = reported.single() as ChatSendQueueServiceException
                assertSame(failure, queueFailure.cause)
                assertEquals(ComposerSendPhase.Failed(ComposerSendFailure.OTHER, messageSaved = true),
                    requireNotNull(composer).state.value.sendPhase)
                assertEquals("", requireNotNull(composer).state.value.draft)
            }
            assertEquals(before, store.fetchMessage(messageKey))
            assertEquals(pendingBefore, store.fetchPendingSendsForMessage(messageKey))
            assertTrue(events.none { it is MessageEvent.MessageFailed && it.messageID == pending.messageID })
            assertEquals(physicalBefore, h.links.radios.single().sent.count { it[0] == 0x02.toUByte() })
        } finally {
            rejectCaller.complete(Unit)
            composer?.close()
            eventReader.cancel()
            shortcutResult?.cancelAndJoin()
            h.container.close()
        }
    }

    @Test fun staleDirectSnapshotCannotFailAnIdenticalKeyReplacementOrTouchItsPendingRows() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val radio = RadioId(UUID(309, 150))
            val contact = ContactDTO(radioId = radio, publicKey = key(4), name = "Peer", lastHeardTimestamp = null)
            store.saveContact(contact)
            store.saveChannel(ChannelDTO(radioId = radio, index = 3u, name = "Ambiguous ancestry"))
            for (channelIndex in listOf<UByte?>(null, 3u)) for (status in MessageStatus.entries) {
                val old = MessageDTO(
                    radioId = radio, contactID = contact.id, channelIndex = channelIndex, text = "old", timestamp = 1u,
                    status = MessageStatus.PENDING, createdAt = Instant.ofEpochSecond(1),
                )
                store.saveMessage(old)
                store.deleteMessage(EntityKey(radio, old.id))
                val replacement = old.copy(text = "replacement", status = status)
                store.saveMessage(replacement)
                val pending = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(old.id, contact.id), radio)
                store.insertPendingSendAssigningSequence(pending)
                val rows = store.fetchPendingSendsForMessage(EntityKey(radio, old.id))
                h.container.chatsFeature.failPending(old)
                assertEquals(replacement, store.fetchMessage(EntityKey(radio, old.id)))
                assertEquals(rows, store.fetchPendingSendsForMessage(EntityKey(radio, old.id)))
            }
        } finally { h.container.close() }
    }

    @Test fun uncertainChannelCompensationRetainsEveryStatusWithoutClaimingDefiniteFailure() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val radio = RadioId(UUID(309, 151))
            val channel = ChannelDTO(radioId = radio, index = 3u, name = "Channel")
            store.saveChannel(channel)
            for (status in MessageStatus.entries) {
                val message = MessageDTO(
                    radioId = radio, channelIndex = channel.index, text = "channel", timestamp = 1u, status = status,
                )
                store.saveMessage(message)
                h.container.chatsFeature.failPending(message)
                assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
            }
        } finally { h.container.close() }
    }
}
