// AndroidOnly: WP-208 Source-faithful disconnect, cancellation, queue isolation, dispatch and Unicode review repairs.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.model.CommandCode
import java.time.Duration
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewRecoveryTest {
    @TestFactory fun recovery() = listOf(
        native("disconnect retains an inline send whose wire outcome is uncertain") {
            val h = Harness(this); h.start(); h.transport.holdMessageReplies = true
            val send = backgroundScope.async { h.service.sendDirectMessage("inline at disconnect", h.contact) }
            runCurrent(); val message = h.store.messages.values.single()
            h.signals.set(null, DeviceConnectionState.DISCONNECTED)
            val report = withTimeout(2000) { h.service.close() }
            assertFailsWith<CancellationException> { send.await() }
            assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
            assertFalse(report.isComplete)
            assertTrue(h.diagnostics.any { it is MessagingDiagnostic.Failure && it.operation == "directUncertain.${message.id}" })
            assertEquals(0, h.service.pendingAckCount); assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertNotNull(h.store.fetchContact(EntityKey(RADIO, CONTACT))); h.close()
        },
        native("cancelling an in-flight inline send retains uncertainty without inventing a recoverable queue") {
            val h = Harness(this); h.start(); h.transport.holdMessageReplies = true
            val events = h.service.statusEvents()
            val send = backgroundScope.async { h.service.sendDirectMessage("inline cancellation", h.contact) }
            runCurrent(); val message = h.store.messages.values.single(); send.cancelAndJoin()
            advanceTimeBy(10_000); runCurrent()
            assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
            assertEquals(message.text, h.stored(message.id).text)
            assertTrue(h.store.fetchPendingSends(RADIO).isEmpty()); assertEquals(1, h.service.pendingAckCount)
            assertTrue(statuses(h.service, events).none { it is MessageStatusEvent.Failed })
            assertTrue(h.diagnostics.any { it is MessagingDiagnostic.Failure && it.operation == "directUncertain.${message.id}" })
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size); h.close()
        },
        native("a durable inline DM or channel save completing at disconnect fails before any wire command") {
            for (channel in listOf(false, true)) {
                val h = Harness(this)
                if (channel) h.store.seedChannelCreation()
                h.start(); val release = CompletableDeferred<Unit>()
                h.store.before = { if (it == "saveMessage") release.await() }
                val send = backgroundScope.async {
                    try {
                        if (channel) h.service.sendChannelMessage("save at disconnect", 0u, RADIO)
                        else h.service.sendDirectMessage("save at disconnect", h.contact)
                        null
                    } catch (failure: MessageServiceException) { failure }
                }
                runCurrent(); assertTrue(h.store.messages.isEmpty())
                h.signals.set(null, DeviceConnectionState.DISCONNECTED); release.complete(Unit)
                assertEquals(MessageServiceError.NotConnected, assertNotNull(send.await()).error)
                assertEquals(MessageStatus.FAILED, h.store.messages.values.single().status)
                assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())
                assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty()); h.close()
            }
        },
        native("accepted inline bookkeeping completes sent during disconnect without publishing a stale event") {
            val h = Harness(this); h.start(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            h.store.before = { if (it == "updateMessageAck") { entered.complete(Unit); release.await() } }
            val events = h.service.statusEvents()
            val send = backgroundScope.async { h.service.sendDirectMessage("accepted bookkeeping", h.contact) }
            runCurrent(); assertTrue(entered.isCompleted)
            val message = h.store.messages.values.single()
            h.signals.set(null, DeviceConnectionState.DISCONNECTED)
            val closing = backgroundScope.async { h.service.close() }; runCurrent(); assertFalse(closing.isCompleted)
            release.complete(Unit); closing.await()
            assertFailsWith<CancellationException> { send.await() }
            assertEquals(MessageStatus.SENT, h.stored(message.id).status)
            assertTrue(events.events.toList().isEmpty()); assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size); h.close()
        },
        native("routine disconnect preserves an already settled sent row awaiting its ACK") {
            val h = Harness(this); h.start()
            val message = h.service.sendDirectMessage("already accepted", h.contact)
            assertEquals(MessageStatus.SENT, message.status)
            h.signals.set(null, DeviceConnectionState.DISCONNECTED); h.service.close()
            assertEquals(MessageStatus.SENT, h.stored(message.id).status)
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size); h.close()
        },
        native("disconnect resets a cancelled queued retry to pending without losing its durable claim") {
            val h = Harness(this); h.start(); h.transport.suggestedTimeout = 1000u
            val message = h.message(MessageStatus.PENDING); val pending = h.pending(message); val q = h.queue()
            q.hydrate(); runCurrent(); advanceTimeBy(1201); runCurrent()
            assertEquals(MessageStatus.RETRYING, h.stored(message.id).status)
            val stamp = h.stored(message.id).timestamp
            h.signals.set(null, DeviceConnectionState.DISCONNECTED); q.shutdown()
            assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
            assertEquals(pending.id, h.store.fetchPendingSends(RADIO).single().id)
            assertEquals(1L, h.store.fetchPendingSends(RADIO).single().attemptCount)
            assertEquals(stamp, h.stored(message.id).timestamp)
            advanceTimeBy(10_000); runCurrent(); assertEquals(2, h.sends(CommandCode.SEND_MESSAGE).size); h.close()
        },
        native("cancelled predecessor cleanup cannot rewrite a successor's persisted status") {
            val h = Harness(this); h.start(); h.transport.holdMessageReplies = true
            val send = backgroundScope.async { h.service.sendDirectMessage("old generation", h.contact) }
            runCurrent(); val message = h.store.messages.values.single()
            h.signals.set(token(generation = 2), DeviceConnectionState.READY)
            h.store.updateMessageStatus(EntityKey(RADIO, message.id), MessageStatus.SENT)
            send.cancelAndJoin()
            assertEquals(MessageStatus.SENT, h.stored(message.id).status)
            assertTrue(h.diagnostics.any { it is MessagingDiagnostic.StaleResult && it.operation == "interruptedDirectRetry" })
            assertFalse(h.service.close().isComplete); h.close()
        },
        native("cancelled send storage failure propagates cancellation and retains its exact teardown cause") {
            val h = Harness(this); h.start()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            h.transport.beforeReply = {
                if (it[0] == CommandCode.RESET_PATH.rawValue) { entered.complete(Unit); release.await() }
            }
            val blocker = backgroundScope.async { h.session.resetPath(h.contact.publicKey) }
            runCurrent(); assertTrue(entered.isCompleted)
            val send = backgroundScope.async { h.service.sendDirectMessage("failed failure commit", h.contact) }
            runCurrent(); val fault = storageFailure(); h.store.faults["updateMessageStatusUnlessDelivered"] = fault
            assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())
            send.cancelAndJoin()
            assertTrue(send.isCancelled); assertEquals(MessageStatus.PENDING, h.store.messages.values.single().status)
            release.complete(Unit); runCurrent(); blocker.await()
            assertTrue(h.service.close().issues.any { it.cause === fault }); h.close()
        },
        native("an inline DM can be accepted while a queued DM awaits its end-to-end ACK") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring(); h.transport.suggestedTimeout = 100_000u
            val queued = h.message(MessageStatus.PENDING); h.pending(queued); val q = h.queue()
            q.hydrate(); runCurrent(); assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            val inline = backgroundScope.async { h.service.sendDirectMessage("quick reply", h.contact) }
            runCurrent(); assertTrue(inline.isCompleted); val reply = inline.await()
            assertEquals(2, h.sends(CommandCode.SEND_MESSAGE).size); assertEquals(0L, testScheduler.currentTime)
            h.transport.pushAck(assertNotNull(h.service.pendingAck(queued.id)).ackCodes.first()); runCurrent()
            q.awaitDrainCompletion(); h.transport.pushAck(assertNotNull(h.service.pendingAck(reply.id)).ackCodes.first()); runCurrent()
            assertEquals(MessageStatus.DELIVERED, h.stored(queued.id).status)
            assertEquals(MessageStatus.DELIVERED, h.stored(reply.id).status)
            assertEquals(2, h.sends(CommandCode.SEND_MESSAGE).size); q.shutdown(); h.close()
        },
        native("malformed hydration retains the bad row but valid later rows still drain exactly once") {
            val h = Harness(this); h.start(); h.transport.acknowledge = true
            val bad = h.message(MessageStatus.PENDING); val badRow = h.pending(bad)
            h.store.upsertPendingSend(badRow.copy(contactID = null))
            val valid = h.message(MessageStatus.PENDING).copy(text = "valid after malformed")
            h.store.saveMessage(valid); val validRow = h.pending(valid); h.store.upsertPendingSend(validRow.copy(sequence = 2))
            val q = h.queue()
            assertFailsWith<ChatSendQueueServiceException> { q.hydrate() }; runCurrent(); q.awaitDrainCompletion()
            assertEquals(MessageStatus.DELIVERED, h.stored(valid.id).status)
            assertEquals(listOf(badRow.id), h.store.fetchPendingSends(RADIO).map { it.id })
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertFailsWith<ChatSendQueueServiceException> { q.hydrate() }
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            h.store.upsertPendingSend(badRow); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertTrue(h.store.fetchPendingSends(RADIO).isEmpty())
            assertEquals(2, h.sends(CommandCode.SEND_MESSAGE).size); assertTrue(q.shutdown().isComplete); h.close()
        },
        native("DM preflight restores pending while channel preflight retains status without a wire send or attempt bump") {
            for (channel in listOf<UByte?>(null, 0u)) {
                val h = Harness(this); h.start(); val message = h.message(MessageStatus.FAILED, channel); h.pending(message)
                h.store.faults[if (channel == null) "incrementPendingSendAttemptCount"
                    else "incrementReadyQueuedChannelAttemptCount"] = storageFailure()
                val q = h.queue()
                q.hydrate(); runCurrent()
                assertEquals(if (channel == null) MessageStatus.PENDING else MessageStatus.FAILED, h.stored(message.id).status)
                assertEquals(0L, h.store.fetchPendingSends(RADIO).single().attemptCount)
                assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())
                assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty()); q.shutdown(); h.close()
            }
        },
        native("an unwired CLI delivery is retained while a later plain message is dispatched and then retryable") {
            val h = Harness(this); h.start(); val p = h.poller(); val received = mutableListOf<String>()
            p.setContactMessageHandler { message, _, _ -> received += message.text }
            h.transport.incomingMessages += reviewContactPacket("cli", 1u)
            h.transport.incomingMessages += reviewContactPacket("plain")
            assertFailsWith<MessagePollingException> { p.pollAllMessages() }
            assertEquals(listOf("plain"), received); assertEquals(1, p.undeliveredCount)
            assertTrue(h.transport.incomingMessages.isEmpty())
            p.setCLIMessageHandler { message, _ -> received += message.text }
            assertEquals(0L, p.pollAllMessages()); assertEquals(listOf("plain", "cli"), received)
            assertEquals(0, p.undeliveredCount); assertTrue(p.close().isComplete); h.close()
        },
        native("a failing handler cannot block a later manual delivery or become a successful poll") {
            val h = Harness(this); h.start(); val p = h.poller(); val received = mutableListOf<String>()
            val cause = IllegalStateException("consumer failure"); var failing = true
            p.setContactMessageHandler { message, _, _ ->
                if (message.text == "bad" && failing) throw cause
                received += message.text
            }
            h.transport.incomingMessages += reviewContactPacket("bad"); h.transport.incomingMessages += reviewContactPacket("good")
            val failure = assertFailsWith<MessagePollingException> { p.pollAllMessages() }
            assertIs<IllegalStateException>(failure.cause)
            assertEquals(cause.message, failure.cause?.message)
            // Coroutine stacktrace recovery may copy the exception, but must retain the original cause.
            assertTrue(generateSequence(failure.cause) { it.cause }.any { it === cause })
            assertEquals(listOf("good"), received); assertEquals(1, p.undeliveredCount)
            assertFalse(p.waitForPendingHandlers(Duration.ZERO))
            failing = false; assertEquals(0L, p.pollAllMessages())
            assertEquals(listOf("good", "bad"), received); assertTrue(p.close().isComplete); h.close()
        },
        native("an unwired live delivery cannot hold every subsequent contact event behind it") {
            val h = Harness(this); h.start(); val p = h.poller(); val received = mutableListOf<String>()
            p.setContactMessageHandler { message, _, _ -> received += message.text }; p.startMessageEventMonitoring()
            h.transport.mock.simulateReceive(reviewContactPacket("live CLI", 1u)); runCurrent()
            h.transport.mock.simulateReceive(reviewContactPacket("live plain")); runCurrent()
            assertEquals(listOf("live plain"), received); assertEquals(1, p.undeliveredCount)
            assertFalse(p.close().isComplete); h.close()
        },
        native("channel sender parsing trims Foundation zero-width spaces and ignores combining-mark colons") {
            assertEquals(ChannelMessageFormat.Parsed("Alice", "hi"), ChannelMessageFormat.parse("\u200BAlice\u200B: hi\u200B"))
            assertNull(ChannelMessageFormat.parse("Bob:\u0301 hi"))
            assertEquals(ChannelMessageFormat.Parsed("Bob:\u0301 next", "hi"), ChannelMessageFormat.parse("Bob:\u0301 next: hi"))
            assertEquals(ChannelMessageFormat.Parsed("Alice", "hi"), ChannelMessageFormat.parse("Alice:\u200B hi"))
        },
        native("channel trimming uses every Foundation whitespace scalar but never newlines or BOM") {
            val scalars = listOf(0x09, 0x20, 0xA0, 0x1680) + (0x2000..0x200B) + listOf(0x202F, 0x205F, 0x3000)
            for (scalar in scalars) {
                val space = scalar.toChar()
                assertEquals(ChannelMessageFormat.Parsed("Alice", "hi"), ChannelMessageFormat.parse("${space}Alice${space}: ${space}hi${space}"))
            }
            assertEquals(ChannelMessageFormat.Parsed("\uFEFFAlice", "\nhi\r\n\uFEFF"),
                ChannelMessageFormat.parse("\uFEFFAlice: \nhi\r\n\uFEFF"))
        },
    )
}
