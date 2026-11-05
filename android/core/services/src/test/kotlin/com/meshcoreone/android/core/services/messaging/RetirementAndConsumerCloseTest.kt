// AndroidOnly: WP-208 True-ACK retirement and attempt-owned reentrant polling-close counterexamples.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.MessagePollingException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.EventFilter
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.session.MeshCoreSessionProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class RetirementAndConsumerCloseTest {
    @TestFactory fun counterexamples() = listOf(
        native("unacknowledgedManualResendRetiredByFailAllCannotIncreaseCountOrPublishResent") {
            val h = Harness(this, MessageServiceConfig(maxAttempts = 2, floodAfter = 5)); h.start()
            h.transport.suggestedTimeout = 1000u
            val message = h.message(MessageStatus.SENT); h.pending(message, isResend = true)
            val events = h.service.statusEvents(); val queue = h.queue()
            queue.hydrate(); runCurrent(); assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            h.service.failAllPendingMessages(); assertEquals(0, h.service.pendingAckCount)
            advanceTimeBy(1201); runCurrent(); queue.awaitDrainCompletion()
            assertEquals(MessageStatus.FAILED, h.stored(message.id).status); assertEquals(1L, h.stored(message.id).sendCount)
            val out = statuses(h.service, events)
            assertEquals(listOf(MessageStatusEvent.PersistenceChanged(EntityKey(RADIO, message.id))), out)
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size); queue.shutdown(); h.close()
        },
        native("actualExpiryOfUnacknowledgedResendCannotBecomeLookupAbsenceSuccess") {
            val h = Harness(this, MessageServiceConfig(maxAttempts = 2, floodAfter = 5, ackGiveUpWindow = 0.0)); h.start()
            h.transport.suggestedTimeout = 1000u
            val message = h.message(MessageStatus.SENT); h.pending(message, isResend = true)
            val events = h.service.statusEvents(); val queue = h.queue()
            queue.hydrate(); runCurrent(); h.clock.offset = 2000
            h.service.checkExpiredAcks(); assertEquals(0, h.service.pendingAckCount)
            advanceTimeBy(1201); runCurrent(); queue.awaitDrainCompletion()
            assertEquals(MessageStatus.FAILED, h.stored(message.id).status); assertEquals(1L, h.stored(message.id).sendCount)
            assertEquals(listOf(MessageStatusEvent.PersistenceChanged(EntityKey(RADIO, message.id))), statuses(h.service, events))
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size); queue.shutdown(); h.close()
        },
        native("genuineEarlyAckCanRetireItsLookupBeforeAcceptanceAndStillConfirmExactlyOneResend") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            val gate = CompletableDeferred<Unit>()
            h.transport.acknowledge = true; h.transport.ackBeforeAcceptance = true; h.transport.acceptanceGate = gate
            val message = h.message(MessageStatus.SENT); h.pending(message, isResend = true)
            val events = h.service.statusEvents(); val queue = h.queue()
            queue.hydrate(); runCurrent()
            assertEquals(1, h.service.pendingAckCount); assertEquals(MessageStatus.SENT, h.stored(message.id).status)
            assertEquals(1L, h.stored(message.id).sendCount)
            gate.complete(Unit); runCurrent(); queue.awaitDrainCompletion()
            assertEquals(0, h.service.pendingAckCount); assertEquals(MessageStatus.DELIVERED, h.stored(message.id).status)
            assertEquals(123u, h.stored(message.id).roundTripTime)
            assertEquals(2L, h.stored(message.id).sendCount)
            assertEquals(listOf(MessageStatusEvent.Resent(message.id)), statuses(h.service, events).filterIsInstance<MessageStatusEvent.Resent>())
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size); queue.shutdown(); h.close()
        },
        native("manualContactConsumerCanAwaitCloseWithoutSelfJoinOrFalsePollSuccess") {
            val h = Harness(this); h.start(); val tracked = TrackedSession(h.session)
            val p = h.poller(tracked)
            var returned: TeardownReport? = null
            p.setContactMessageHandler { _, _, _ -> returned = p.close() }
            p.startMessageEventMonitoring()
            h.transport.incomingMessages += contactPacket("close manual")
            val work = backgroundScope.async { p.pollAllMessages() }
            withTimeout(2000) { assertFailsWith<CancellationException> { work.await() } }
            runCurrent(); assertIncompleteHandlerReport(assertNotNull(returned))
            assertEquals(0, tracked.readers); assertEquals(0L, p.pendingHandlerCount); assertEquals(1, p.undeliveredCount)
            assertFalse(p.hasMessageHandlersWired)
            assertSuccessorIsIndependent(h); h.close()
        },
        native("liveContactConsumerCanAwaitCloseAndTerminatesItsActualSessionSubscription") {
            val h = Harness(this); h.start(); val tracked = TrackedSession(h.session)
            val p = h.poller(tracked)
            val returned = CompletableDeferred<TeardownReport>()
            p.setContactMessageHandler { _, _, _ -> returned.complete(p.close()) }
            p.startMessageEventMonitoring(); h.transport.mock.simulateReceive(contactPacket("close live"))
            assertIncompleteHandlerReport(withTimeout(2000) { returned.await() })
            runCurrent(); assertEquals(0, tracked.readers); assertEquals(1, p.undeliveredCount)
            assertEquals(0L, p.pendingHandlerCount); assertFalse(p.hasMessageHandlersWired)
            assertSuccessorIsIndependent(h); h.close()
        },
        native("externalCloseAndConsumerReentrantCloseCannotWaitOnEachOther") {
            val h = Harness(this); h.start(); val p = h.poller()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var consumerReport: TeardownReport? = null
            p.setContactMessageHandler { _, _, _ ->
                entered.complete(Unit)
                withContext(NonCancellable) { release.await(); consumerReport = p.close() }
            }
            h.transport.incomingMessages += contactPacket("concurrent close")
            val polling = backgroundScope.async { p.pollAllMessages() }; runCurrent(); assertTrue(entered.isCompleted)
            val close = async { p.close() }; runCurrent(); assertFalse(close.isCompleted)
            release.complete(Unit)
            assertIncompleteHandlerReport(withTimeout(2000) { close.await() })
            assertIncompleteHandlerReport(assertNotNull(consumerReport))
            assertFailsWith<CancellationException> { polling.await() }; assertEquals(1, p.undeliveredCount); h.close()
        },
        native("channelConsumerReentrantCloseRetainsTheConsumedChannelRecord") {
            val h = Harness(this); h.start(); val p = h.poller()
            var returned: TeardownReport? = null
            p.setChannelMessageHandler { wire, _, _ -> assertEquals("Local: channel", wire.text); returned = p.close() }
            h.transport.incomingMessages += ByteWriter().appendUInt8(ResponseCode.CHANNEL_MESSAGE_RECEIVED.rawValue)
                .appendUInt8(0u).appendUInt8(0u).appendUInt8(0u).appendUInt32LE(42u).append(Bytes.utf8("Local: channel")).toBytes()
            val polling = backgroundScope.async { p.pollAllMessages() }
            withTimeout(2000) { assertFailsWith<CancellationException> { polling.await() } }
            assertIncompleteHandlerReport(assertNotNull(returned)); assertEquals(1, p.undeliveredCount); h.close()
        },
    )
    private fun contactPacket(text: String): Bytes = ByteWriter().appendUInt8(ResponseCode.CONTACT_MESSAGE_RECEIVED.rawValue)
        .append(TARGET.prefix(6)).appendUInt8(0u).appendUInt8(0u).appendUInt32LE(42u).append(Bytes.utf8(text)).toBytes()
    private fun assertIncompleteHandlerReport(report: TeardownReport) {
        assertFalse(report.isComplete)
        assertTrue(report.issues.any { it.stage == LifecycleStage.STOP_SERVICES && it.cause is MessagePollingException })
    }
    private suspend fun TestScope.assertSuccessorIsIndependent(h: Harness) {
        val successor = Harness(this, store = h.store, generation = 2); successor.start()
        val p = successor.poller()
        var received = 0
        p.setContactMessageHandler { _, contact, _ -> assertEquals(CONTACT, contact?.id); received++ }
        successor.transport.incomingMessages += contactPacket("successor")
        assertEquals(1L, p.pollAllMessages()); assertEquals(1, received); assertTrue(p.close().isComplete)
        successor.close()
    }
    private class TrackedSession(private val actual: MeshCoreSessionProtocol) : MeshCoreSessionProtocol by actual {
        var readers = 0
            private set
        override fun events(filter: EventFilter): Flow<MeshEvent> {
            readers++
            return actual.events(filter).onCompletion { readers-- }
        }
    }
}
