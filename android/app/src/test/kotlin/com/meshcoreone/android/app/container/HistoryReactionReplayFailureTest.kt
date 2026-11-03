// AndroidOnly: WP-309 Real Room history replay failures and cancellation before and after durable commits.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.services.reactions.ReactionParser
import com.meshcoreone.android.core.services.reactions.PendingReaction
import com.meshcoreone.android.core.services.reactions.PendingDMReaction
import com.meshcoreone.android.core.services.reactions.ReactionService
import com.meshcoreone.android.core.services.rendering.MessageFragment
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HistoryReactionReplayFailureTest : RoomProcessTest() {
    private val emoji = "\uD83D\uDC4D"
    private val stamp = 1_704_067_200u

    private enum class Fault { EXISTS, SAVE_BEFORE, SAVE_AFTER, FETCH_BEFORE, FETCH_AFTER, SUMMARY_BEFORE, SUMMARY_AFTER }

    @Test fun directExistsFailureRetainsTheUnfinishedBatch() = runTest { recover(this, true, Fault.EXISTS, false) }
    @Test fun channelExistsFailureRetainsTheUnfinishedBatch() = runTest { recover(this, false, Fault.EXISTS, false) }
    @Test fun directSaveFailureRetainsTheUnfinishedBatch() = runTest { recover(this, true, Fault.SAVE_BEFORE, false) }
    @Test fun channelSaveFailureRetainsTheUnfinishedBatch() = runTest { recover(this, false, Fault.SAVE_BEFORE, false) }
    @Test fun directCommittedSaveFailureReconcilesWithoutAnotherRow() = runTest { recover(this, true, Fault.SAVE_AFTER, false) }
    @Test fun channelCommittedSaveFailureReconcilesWithoutAnotherRow() = runTest { recover(this, false, Fault.SAVE_AFTER, false) }
    @Test fun directFetchFailureReconcilesWithoutAnotherRow() = runTest { recover(this, true, Fault.FETCH_BEFORE, false) }
    @Test fun channelFetchFailureReconcilesWithoutAnotherRow() = runTest { recover(this, false, Fault.FETCH_BEFORE, false) }
    @Test fun directCompletedFetchFailureReconcilesWithoutAnotherRow() = runTest { recover(this, true, Fault.FETCH_AFTER, false) }
    @Test fun channelCompletedFetchFailureReconcilesWithoutAnotherRow() = runTest { recover(this, false, Fault.FETCH_AFTER, false) }
    @Test fun directSummaryFailureReconcilesWithoutAnotherRow() = runTest { recover(this, true, Fault.SUMMARY_BEFORE, false) }
    @Test fun channelSummaryFailureReconcilesWithoutAnotherRow() = runTest { recover(this, false, Fault.SUMMARY_BEFORE, false) }
    @Test fun directCommittedSummaryFailureReconcilesWithoutAnotherRow() = runTest { recover(this, true, Fault.SUMMARY_AFTER, false) }
    @Test fun channelCommittedSummaryFailureReconcilesWithoutAnotherRow() = runTest { recover(this, false, Fault.SUMMARY_AFTER, false) }

    @Test fun directExistsCancellationRetainsTheUnfinishedBatch() = runTest { recover(this, true, Fault.EXISTS, true) }
    @Test fun channelExistsCancellationRetainsTheUnfinishedBatch() = runTest { recover(this, false, Fault.EXISTS, true) }
    @Test fun directSaveCancellationRetainsTheUnfinishedBatch() = runTest { recover(this, true, Fault.SAVE_BEFORE, true) }
    @Test fun channelSaveCancellationRetainsTheUnfinishedBatch() = runTest { recover(this, false, Fault.SAVE_BEFORE, true) }
    @Test fun directCommittedSaveCancellationReconcilesWithoutAnotherRow() = runTest { recover(this, true, Fault.SAVE_AFTER, true) }
    @Test fun channelCommittedSaveCancellationReconcilesWithoutAnotherRow() = runTest { recover(this, false, Fault.SAVE_AFTER, true) }
    @Test fun directFetchCancellationReconcilesWithoutAnotherRow() = runTest { recover(this, true, Fault.FETCH_BEFORE, true) }
    @Test fun channelFetchCancellationReconcilesWithoutAnotherRow() = runTest { recover(this, false, Fault.FETCH_BEFORE, true) }
    @Test fun directCompletedFetchCancellationReconcilesWithoutAnotherRow() = runTest { recover(this, true, Fault.FETCH_AFTER, true) }
    @Test fun channelCompletedFetchCancellationReconcilesWithoutAnotherRow() = runTest { recover(this, false, Fault.FETCH_AFTER, true) }
    @Test fun directSummaryCancellationReconcilesWithoutAnotherRow() = runTest { recover(this, true, Fault.SUMMARY_BEFORE, true) }
    @Test fun channelSummaryCancellationReconcilesWithoutAnotherRow() = runTest { recover(this, false, Fault.SUMMARY_BEFORE, true) }
    @Test fun directCommittedSummaryCancellationReconcilesWithoutAnotherRow() = runTest { recover(this, true, Fault.SUMMARY_AFTER, true) }
    @Test fun channelCommittedSummaryCancellationReconcilesWithoutAnotherRow() = runTest { recover(this, false, Fault.SUMMARY_AFTER, true) }

    @Test fun directDuplicateReplaySerializesALiveReaction() = runTest { interleave(this, true, true) }
    @Test fun channelDuplicateReplaySerializesALiveReaction() = runTest { interleave(this, false, true) }
    @Test fun directLiveReactionSerializesDuplicateReplay() = runTest { interleave(this, true, false) }
    @Test fun channelLiveReactionSerializesDuplicateReplay() = runTest { interleave(this, false, false) }
    @Test fun directCancelledDuplicateWaiterRetainsItsPendingReaction() = runTest { interleave(this, true, false, true) }
    @Test fun channelCancelledDuplicateWaiterRetainsItsPendingReaction() = runTest { interleave(this, false, false, true) }

    private suspend fun interleave(test: TestScope, direct: Boolean, historyFirst: Boolean, cancelWaiter: Boolean = false) {
        val snapshotReady = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val duplicateChecked = CompletableDeferred<Unit>()
        val liveSaveStarted = CompletableDeferred<Unit>()
        var fetches = 0
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun fetchReactions(message: EntityKey, limit: Long): SnapshotList<ReactionDTO> {
                val first = ++fetches == 1
                val result = store.fetchReactions(message, limit)
                if (first) {
                    snapshotReady.complete(Unit)
                    releaseSnapshot.await()
                }
                return result
            }

            override suspend fun reactionExists(message: EntityKey, senderName: String, emoji: String): Boolean {
                val result = store.reactionExists(message, senderName, emoji)
                duplicateChecked.complete(Unit)
                return result
            }

            override suspend fun saveReaction(dto: ReactionDTO) {
                liveSaveStarted.complete(Unit)
                store.saveReaction(dto)
            }
        }
        val harness = ContainerHarness(test, store, persistenceOverride = persistence)
        val jobs = mutableListOf<Job>()
        var mounted: ChatViewModel? = null
        try {
            harness.manager.connect(harness.target())
            harness.eventually("concurrent reaction session ready") {
                harness.manager.connectionState == DeviceConnectionState.READY
            }
            val live = assertNotNull(harness.sessions.current)
            val radio = live.token.radioId
            val contact = ContactDTO(radioId = radio, publicKey = key(3), name = "Alice", lastHeardTimestamp = null)
            store.saveContact(contact)
            store.saveChannel(radio, ChannelInfo(3u, "General", Bytes(ByteArray(16))))
            val channel = assertNotNull(store.fetchChannel(radio, 3u))
            val target = MessageDTO(
                radioId = radio, contactID = if (direct) contact.id else null,
                channelIndex = if (direct) null else channel.index, text = "Hello", timestamp = stamp,
                createdAt = EPOCH, sortDate = EPOCH, senderNodeName = if (direct) null else "Alice",
                direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED, reactionSummary = "$emoji:1",
            )
            store.saveMessage(target)
            val key = EntityKey(radio, target.id)
            val wire = if (direct) "$emoji\nb45pc4ek" else "@[Alice]$emoji\nb45pc4ek"
            val bob = ReactionDTO(
                messageID = target.id, emoji = emoji, senderName = "Bob", messageHash = "b45pc4ek", rawText = wire,
                contactID = if (direct) contact.id else null, channelIndex = if (direct) null else channel.index,
                radioId = radio, receivedAt = EPOCH,
            )
            store.saveReaction(bob)
            val carol = bob.copy(id = UUID.randomUUID(), senderName = "Carol", receivedAt = EPOCH.plusSeconds(1))
            val service = live.reactionService
            if (direct) service.queuePendingDMReaction(
                assertNotNull(ReactionParser.parseDM(wire)), contact.id, "Bob", wire, radio,
            ) else service.queuePendingReaction(
                assertNotNull(ReactionParser.parse(wire)), channel.index, "Bob", wire, radio,
            )
            val conversation = if (direct) TimelineConversation.Direct(contact) else TimelineConversation.Channel(channel)
            val viewModel = ChatViewModel(
                conversation, harness.container.chatsFeature.timeline(conversation), CoroutineScope(currentCoroutineContext()),
            )
            mounted = viewModel
            // The actual sync adapter and history consumer share this session's service and real Room store.
            val adapter = SyncReactionAdapter(service)
            if (historyFirst) {
                val history = test.async(start = CoroutineStart.UNDISPATCHED) { viewModel.open() }.also(jobs::add)
                snapshotReady.await()
                val arrival = test.async(start = CoroutineStart.UNDISPATCHED) {
                    adapter.persistReactionAndUpdateSummary(carol, persistence)
                }.also(jobs::add)
                assertFalse(liveSaveStarted.isCompleted, "Live persistence must wait before saving, not only before summary write")
                assertFalse(arrival.isCompleted)
                assertEquals(listOf(bob), store.fetchReactions(key))
                releaseSnapshot.complete(Unit)
                history.await()
                assertEquals("$emoji:2", assertNotNull(arrival.await()).summary)
            } else {
                val arrival = test.async(start = CoroutineStart.UNDISPATCHED) {
                    adapter.persistReactionAndUpdateSummary(carol, persistence)
                }.also(jobs::add)
                snapshotReady.await()
                val history = test.async(start = CoroutineStart.UNDISPATCHED) { viewModel.open() }.also(jobs::add)
                duplicateChecked.await()
                assertEquals(1, fetches, "Duplicate reconciliation must wait for the complete live persistence operation")
                assertFalse(history.isCompleted)
                if (cancelWaiter) {
                    history.cancelAndJoin()
                    assertTrue(history.isCancelled)
                    val retained = if (direct) {
                        val pending = service.indexDMMessage(target.id, contact.id, "Hello", stamp)
                        pending.forEach(service::requeuePendingDMReaction)
                        pending.map { it.senderName }
                    } else {
                        val pending = service.indexMessage(target.id, channel.index, "Alice", "Hello", stamp)
                        pending.forEach(service::requeuePendingReaction)
                        pending.map { it.senderNodeName }
                    }
                    assertEquals(listOf("Bob"), retained, "A cancelled mutex waiter must restore its drained batch")
                }
                releaseSnapshot.complete(Unit)
                assertEquals("$emoji:2", assertNotNull(arrival.await()).summary)
                if (!cancelWaiter) history.await()
            }
            viewModel.open()
            assertNull(viewModel.state.value.loadError)
            assertEquals("$emoji:2", assertNotNull(store.fetchMessage(key)).reactionSummary)
            assertEquals("$emoji:2", viewModel.state.value.messages.single().reactionSummary)
            val saved = store.fetchReactions(key)
            assertEquals(setOf(bob, carol), saved.toSet(), "Neither path may replace committed reaction identities")
            viewModel.refresh()
            assertEquals(saved, store.fetchReactions(key))
            assertEquals("$emoji:2", viewModel.state.value.messages.single().reactionSummary)
        } finally {
            releaseSnapshot.complete(Unit)
            jobs.forEach { it.cancelAndJoin() }
            mounted?.close()
            harness.container.close()
        }
    }

    @Test fun restoredChannelMatchesKeepTheirMetadataAndTheExistingQueueBound() {
        val service = ReactionService(Clock.fixed(EPOCH, ZoneOffset.UTC))
        val radio = com.meshcoreone.android.core.model.RadioId(UUID.randomUUID())
        val raw = "@[Cafe\u0301]$emoji\nb45pc4ek"
        val parsed = assertNotNull(ReactionParser.parse(raw))
        val pending = (0..100).map {
            PendingReaction(parsed, 3u, "sender $it", raw, radio, EPOCH.minusSeconds(it.toLong()))
        }
        pending.forEach(service::requeuePendingReaction)
        assertTrue(service.indexMessage(UUID.randomUUID(), 4u, "Caf\u00e9", "Hello", stamp).isEmpty())
        assertEquals(pending.drop(1), service.indexMessage(UUID.randomUUID(), 3u, "Caf\u00e9", "Hello", stamp))
    }

    @Test fun restoredDirectMatchesKeepTheirMetadataAndTheExistingQueueBound() {
        val service = ReactionService(Clock.fixed(EPOCH, ZoneOffset.UTC))
        val radio = com.meshcoreone.android.core.model.RadioId(UUID.randomUUID())
        val contact = UUID.randomUUID()
        val raw = "$emoji\nb45pc4ek"
        val parsed = assertNotNull(ReactionParser.parseDM(raw))
        val pending = (0..100).map {
            PendingDMReaction(parsed, contact, "sender $it", raw, radio, EPOCH.minusSeconds(it.toLong()))
        }
        pending.forEach(service::requeuePendingDMReaction)
        assertTrue(service.indexDMMessage(UUID.randomUUID(), UUID.randomUUID(), "Hello", stamp).isEmpty())
        assertEquals(pending.drop(1), service.indexDMMessage(UUID.randomUUID(), contact, "Hello", stamp))
    }

    /** All unselected operations delegate to the production Room store, including the actual writes. */
    private class FaultStore(private val actual: PersistenceStoreProtocol) : PersistenceStoreProtocol by actual {
        private var selected: Fault? = null
        private var occurrence = 0
        private var calls = 0
        private var cancellationGate: CompletableDeferred<Unit>? = null

        fun arm(fault: Fault, occurrence: Int, cancellationGate: CompletableDeferred<Unit>? = null) {
            selected = fault
            this.occurrence = occurrence
            calls = 0
            this.cancellationGate = cancellationGate
        }

        private suspend fun hit(fault: Fault) {
            if (selected != fault || ++calls != occurrence) return
            selected = null
            cancellationGate?.let {
                it.complete(Unit)
                awaitCancellation()
            }
            val error = if (fault == Fault.EXISTS || fault == Fault.FETCH_BEFORE || fault == Fault.FETCH_AFTER) {
                PersistenceStoreError.FetchFailed("Injected $fault")
            } else {
                PersistenceStoreError.SaveFailed("Injected $fault")
            }
            throw PersistenceStoreException(error)
        }

        override suspend fun reactionExists(message: EntityKey, senderName: String, emoji: String): Boolean {
            hit(Fault.EXISTS)
            return actual.reactionExists(message, senderName, emoji)
        }

        override suspend fun saveReaction(dto: ReactionDTO) {
            hit(Fault.SAVE_BEFORE)
            actual.saveReaction(dto)
            hit(Fault.SAVE_AFTER)
        }

        override suspend fun fetchReactions(message: EntityKey, limit: Long): SnapshotList<ReactionDTO> {
            hit(Fault.FETCH_BEFORE)
            val reactions = actual.fetchReactions(message, limit)
            hit(Fault.FETCH_AFTER)
            return reactions
        }

        override suspend fun updateMessageReactionSummary(message: EntityKey, summary: String?) {
            hit(Fault.SUMMARY_BEFORE)
            actual.updateMessageReactionSummary(message, summary)
            hit(Fault.SUMMARY_AFTER)
        }
    }

    private data class Drained(val values: List<Any>, val restore: () -> Unit)

    private suspend fun recover(test: TestScope, direct: Boolean, fault: Fault, cancel: Boolean) {
        val persistence = FaultStore(store)
        val harness = ContainerHarness(test, store, persistenceOverride = persistence)
        var mounted: ChatViewModel? = null
        try {
            harness.manager.connect(harness.target())
            harness.eventually("failure replay session ready") { harness.manager.connectionState == DeviceConnectionState.READY }
            val live = assertNotNull(harness.sessions.current)
            val radio = live.token.radioId
            val contact = ContactDTO(radioId = radio, publicKey = key(3), name = "Alice", lastHeardTimestamp = null)
            store.saveContact(contact)
            store.saveChannel(radio, ChannelInfo(3u, "General", Bytes(ByteArray(16))))
            val channel: ChannelDTO = assertNotNull(store.fetchChannel(radio, 3u))
            val target = MessageDTO(
                radioId = radio, contactID = if (direct) contact.id else null,
                channelIndex = if (direct) null else channel.index, text = "Hello",
                timestamp = if (direct) stamp + 300u else stamp, senderTimestamp = if (direct) stamp else null,
                timestampCorrected = direct, createdAt = EPOCH, sortDate = EPOCH,
                senderNodeName = if (direct) null else "Alice", direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
            )
            store.saveMessage(target)
            val key = EntityKey(radio, target.id)
            val conversation = if (direct) TimelineConversation.Direct(contact) else TimelineConversation.Channel(channel)
            val viewModel = ChatViewModel(
                conversation, harness.container.chatsFeature.timeline(conversation), CoroutineScope(currentCoroutineContext()),
            )
            mounted = viewModel
            viewModel.open()
            assertNull(viewModel.state.value.loadError)
            assertNull(viewModel.state.value.messages.single().reactionSummary)
            val service = live.reactionService
            val wire = if (direct) "$emoji\nb45pc4ek" else "@[Alice]$emoji\nb45pc4ek"
            for (sender in listOf("Bob", "Carol", "Dave")) {
                if (direct) service.queuePendingDMReaction(
                    assertNotNull(ReactionParser.parseDM(wire)), contact.id, sender, wire, radio,
                ) else service.queuePendingReaction(
                    assertNotNull(ReactionParser.parse(wire)), channel.index, sender, wire, radio,
                )
            }
            fun drain(): Drained = if (direct) {
                val batch = service.indexDMMessage(target.id, contact.id, target.text, target.reactionTimestamp)
                Drained(batch) { batch.forEach(service::requeuePendingDMReaction) }
            } else {
                val batch = service.indexMessage(target.id, channel.index, "Alice", target.text, target.timestamp)
                Drained(batch) { batch.forEach(service::requeuePendingReaction) }
            }
            val original = drain()
            assertEquals(3, original.values.size)
            original.restore()
            val gate = if (cancel) CompletableDeferred<Unit>() else null
            persistence.arm(fault, occurrence = 2, cancellationGate = gate)
            if (gate != null) {
                val attempt = test.launch { viewModel.refresh() }
                gate.await()
                attempt.cancelAndJoin()
                assertTrue(attempt.isCancelled)
                assertNull(viewModel.state.value.passiveError, "Cancellation is not a displayed store failure")
            } else {
                viewModel.refresh()
                assertIs<PersistenceStoreException>(viewModel.state.value.passiveError)
            }
            val unfinished = drain()
            assertEquals(original.values.drop(1), unfinished.values,
                "Only Bob completed: retain Carol and Dave, including parsed identity, radio, raw text and receivedAt")
            unfinished.restore()
            val partial = store.fetchReactions(key)
            val committed = fault !in setOf(Fault.EXISTS, Fault.SAVE_BEFORE)
            assertEquals(if (committed) 2 else 1, partial.size)
            assertEquals(if (committed) setOf("Bob", "Carol") else setOf("Bob"), partial.map { it.senderName }.toSet())
            assertEquals(if (fault == Fault.SUMMARY_AFTER) "$emoji:2" else "$emoji:1",
                assertNotNull(store.fetchMessage(key)).reactionSummary)
            assertNull(viewModel.state.value.messages.single().reactionSummary, "Failed refresh must not publish half a page")

            if (committed) {
                // Retry the already-saved row, and fail its duplicate-summary repair once more.
                persistence.arm(Fault.SUMMARY_BEFORE, occurrence = 1)
                viewModel.refresh()
                assertIs<PersistenceStoreException>(viewModel.state.value.passiveError)
                val stillUnfinished = drain()
                assertEquals(unfinished.values, stillUnfinished.values)
                stillUnfinished.restore()
                assertEquals(partial, store.fetchReactions(key), "Duplicate repair must not replace stored identities")
                if (cancel) {
                    val duplicateGate = CompletableDeferred<Unit>()
                    persistence.arm(Fault.FETCH_AFTER, occurrence = 1, cancellationGate = duplicateGate)
                    val duplicateAttempt = test.launch { viewModel.refresh() }
                    duplicateGate.await()
                    duplicateAttempt.cancelAndJoin()
                    assertTrue(duplicateAttempt.isCancelled)
                    assertNull(viewModel.state.value.passiveError)
                    val cancelledDuplicate = drain()
                    assertEquals(unfinished.values, cancelledDuplicate.values)
                    cancelledDuplicate.restore()
                    assertEquals(partial, store.fetchReactions(key))
                }
            }
            viewModel.refresh()
            assertNull(viewModel.state.value.passiveError)
            val saved = store.fetchReactions(key)
            assertEquals(3, saved.size)
            assertEquals(setOf("Bob", "Carol", "Dave"), saved.map { it.senderName }.toSet())
            for (prior in partial) assertEquals(prior, saved.single { it.senderName == prior.senderName })
            assertTrue(saved.all {
                it.radioId == radio && it.messageID == target.id && it.emoji == emoji && it.rawText == wire &&
                    it.messageHash == "b45pc4ek" && it.contactID == (if (direct) contact.id else null) &&
                    it.channelIndex == (if (direct) null else channel.index)
            })
            assertEquals("$emoji:3", assertNotNull(store.fetchMessage(key)).reactionSummary)
            assertEquals("$emoji:3", viewModel.state.value.messages.single().reactionSummary)
            val rendered = assertNotNull(harness.appState.chatCoordinatorRegistry).coordinator(conversation.id)
                .renderStateFlow.first { state ->
                    state.items.singleOrNull()?.content?.contains(MessageFragment.ReactionSummary("$emoji:3")) == true
                }
            assertEquals(target.id, rendered.items.single().id)
            assertTrue(drain().values.isEmpty(), "Only successful reconciliation acknowledges the whole drained batch")
            viewModel.refresh()
            assertEquals(saved, store.fetchReactions(key))
            assertEquals("$emoji:3", viewModel.state.value.messages.single().reactionSummary)
            assertFalse(viewModel.state.value.isLoading)
        } finally {
            mounted?.close()
            harness.container.close()
        }
    }
}
