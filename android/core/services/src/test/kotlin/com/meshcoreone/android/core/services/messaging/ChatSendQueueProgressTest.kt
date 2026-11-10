// AndroidOnly: WP-208 Uncertain-send isolation and independent process-owned progress regressions.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.model.CommandCode
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendQueueProgressTest {
    @TestFactory fun recovery() = listOf(
        native("uncertain ERROR2 retains both same-channel envelopes without spending query progress") {
            val progress = ChatSendQueueProgress()
            val h = connection()
            val a = h.message(MessageStatus.PENDING, 0u)
            val b = h.message(MessageStatus.PENDING, 0u, text = "independent envelope")
            val first = h.pending(a, 2)
            val second = h.pending(b, 2)
            h.store.upsertPendingSend(second.copy(sequence = 2))
            val events = h.service.statusEvents()
            val queue = drain(h, progress, query = MessagingChannelQuery { fail("Uncertain sends must not query") })
            queue.awaitDrainCompletion()
            val retained = h.store.pending.toMap()
            val attempts = h.store.channelAttempts.toMap()
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            assertEquals(QueuedChannelDispatchState.UNCERTAIN, retained.getValue(EntityKey(RADIO, first.id)).channelDispatchState)
            assertEquals(QueuedChannelDispatchState.READY, retained.getValue(EntityKey(RADIO, second.id)).channelDispatchState)
            assertEquals(3L, retained.getValue(EntityKey(RADIO, first.id)).attemptCount)
            assertEquals(3L, retained.getValue(EntityKey(RADIO, second.id)).attemptCount)
            assertEquals(QueuedChannelAttemptPhase.UNCERTAIN, attempts.values.single().phase)
            advanceTimeBy(30_001); runCurrent()
            queue.transportDidOpen(); runCurrent()
            queue.awaitDrainCompletion()
            assertEquals(retained, h.store.pending)
            assertEquals(attempts, h.store.channelAttempts)
            assertTrue(h.sends(CommandCode.GET_CHANNEL).isEmpty())
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            assertTrue(statuses(h.service, events).isEmpty())
            assertEquals(MessageStatus.PENDING, h.stored(a.id).status)
            assertEquals(MessageStatus.PENDING, h.stored(b.id).status)
            h.close()
            val lease = progress.attach(token(generation = 2))
            assertEquals(1L, lease.increment(a.id, first.id), "A raw send error is not a query failure")
            assertEquals(1L, lease.increment(b.id, second.id))
        },
        native("sixteen replacement sessions cannot turn uncertainty into a query or a new send") {
            val progress = ChatSendQueueProgress()
            val first = connection()
            val message = first.message(MessageStatus.PENDING, 0u)
            val row = first.pending(message, 2)
            drain(first, progress).awaitDrainCompletion()
            val retained = first.store.pending.getValue(EntityKey(RADIO, row.id))
            val attempt = first.store.channelAttempts.values.single()
            first.close()
            repeat(16) { index ->
                val next = connection(first.store, index + 2L)
                drain(next, progress, query = MessagingChannelQuery { fail("Replacement must not query uncertainty") })
                    .awaitDrainCompletion()
                advanceTimeBy(30_001); runCurrent()
                assertTrue(next.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
                assertTrue(next.sends(CommandCode.GET_CHANNEL).isEmpty())
                assertEquals(retained, next.store.pending.getValue(EntityKey(RADIO, row.id)))
                assertEquals(attempt, next.store.channelAttempts.getValue(attempt.key))
                assertEquals(MessageStatus.PENDING, next.stored(message.id).status)
                next.close()
            }
        },
        native("failed hydration retains the exact uncertain attempt for a later generation") {
            val progress = ChatSendQueueProgress()
            val first = connection()
            val message = first.message(MessageStatus.PENDING, 0u)
            first.pending(message, 2)
            drain(first, progress).awaitDrainCompletion()
            val retained = first.store.pending.toMap()
            val attempts = first.store.channelAttempts.toMap()
            first.close()
            val second = connection(first.store, 2)
            second.store.faults["fetchPendingSends"] = storageFailure()
            assertFailsWith<com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException> {
                drain(second, progress)
            }
            second.close()
            second.store.faults.clear()
            val third = connection(first.store, 3)
            drain(third, progress).awaitDrainCompletion()
            assertEquals(retained, third.store.pending)
            assertEquals(attempts, third.store.channelAttempts)
            assertTrue(third.sends(CommandCode.GET_CHANNEL).isEmpty())
            assertTrue(third.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            third.close()
        },
        native("preflight persistence failure does not query or dispatch and a later generation remains safe") {
            val progress = ChatSendQueueProgress()
            val first = connection()
            val message = first.message(MessageStatus.PENDING, 0u)
            first.pending(message, 2)
            first.store.faults["incrementReadyQueuedChannelAttemptCount"] = storageFailure()
            drain(first, progress).awaitDrainCompletion()
            assertTrue(first.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            assertTrue(first.sends(CommandCode.GET_CHANNEL).isEmpty())
            assertTrue(first.store.channelAttempts.isEmpty())
            first.close()
            first.store.faults.clear()
            val second = connection(first.store, 2)
            drain(second, progress).awaitDrainCompletion()
            assertEquals(1, second.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            assertTrue(second.sends(CommandCode.GET_CHANNEL).isEmpty())
            assertEquals(QueuedChannelAttemptPhase.UNCERTAIN, second.store.channelAttempts.values.single().phase)
            assertEquals(MessageStatus.PENDING, second.stored(message.id).status)
            second.close()
        },
        native("progress counts survive replacement but reset only for the selected envelope") {
            val progress = ChatSendQueueProgress()
            val a = UUID.randomUUID(); val b = UUID.randomUUID()
            val rowA = UUID.randomUUID(); val rowB = UUID.randomUUID()
            val first = progress.attach(token())
            assertEquals(1L, first.increment(a, rowA))
            assertEquals(2L, first.increment(a, rowA))
            assertEquals(1L, first.increment(b, rowB))
            first.retire()
            val second = progress.attach(token(generation = 2))
            assertEquals(3L, second.increment(a, rowA))
            second.clear(a)
            assertEquals(1L, second.increment(a, rowA))
            assertEquals(2L, second.increment(b, rowB))
        },
        native("sixteen progress increments across generations retain the exact count") {
            val progress = ChatSendQueueProgress()
            val message = UUID.randomUUID(); val pending = UUID.randomUUID()
            repeat(16) { index ->
                val lease = progress.attach(token(generation = index + 1L))
                assertEquals(index + 1L, lease.increment(message, pending))
                lease.retire()
            }
        },
        native("replacing pending identity resets progress even for the same message UUID") {
            val lease = ChatSendQueueProgress().attach(token())
            val message = UUID.randomUUID(); val first = UUID.randomUUID()
            assertEquals(1L, lease.increment(message, first))
            assertEquals(2L, lease.increment(message, first))
            assertEquals(1L, lease.increment(message, UUID.randomUUID()))
        },
        native("successful reconciliation prunes only removed pending identities on its own radio") {
            val progress = ChatSendQueueProgress()
            val message = UUID.randomUUID()
            val row = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message, CONTACT), RADIO)
            val own = progress.attach(token())
            val peer = progress.attach(token(radio = PEER_RADIO))
            assertEquals(1L, own.increment(message, row.id))
            assertEquals(1L, peer.increment(message, row.id))
            own.reconcile(listOf(row))
            assertEquals(2L, own.increment(message, row.id))
            own.reconcile(emptyList())
            assertEquals(1L, own.increment(message, row.id))
            assertEquals(2L, peer.increment(message, row.id))
        },
        native("stale attach and retired lease cannot mutate successor progress") {
            val progress = ChatSendQueueProgress()
            val messageID = UUID.randomUUID(); val pendingID = UUID.randomUUID()
            val first = progress.attach(token())
            assertEquals(1L, first.increment(messageID, pendingID))
            val second = progress.attach(token(generation = 2))
            assertFailsWith<CancellationException> { progress.attach(token()) }
            assertFailsWith<CancellationException> { first.clear(messageID) }
            assertFailsWith<CancellationException> { first.increment(messageID, pendingID) }
            assertFailsWith<CancellationException> { first.reconcile(emptyList()) }
            first.retire()
            assertEquals(2L, second.increment(messageID, pendingID))
            second.retire()
            assertFailsWith<CancellationException> { second.clear(messageID) }
        },
    )

    private suspend fun TestScope.connection(
        store: RecordingStore = RecordingStore(), generation: Long = 1, radio: RadioId = RADIO,
    ) = Harness(this, MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 0)),
        store, generation, radio = radio).also {
        it.start()
        it.transport.channelError = 2u
    }

    private suspend fun TestScope.drain(
        h: Harness, progress: ChatSendQueueProgress,
        config: ChatSendQueueConfig = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 2),
        query: MessagingChannelQuery = MessagingChannelQuery { h.session.getChannel(it) },
    ): ChatSendQueueService {
        val queue = h.queue(config, query, progress = progress)
        h.signals.set(h.token, DeviceConnectionState.CONNECTED)
        queue.hydrate(); runCurrent()
        h.signals.set(h.token, DeviceConnectionState.READY); runCurrent()
        return queue
    }

}
