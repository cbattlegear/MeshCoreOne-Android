// AndroidOnly: WP-208 Real-session interleavings for the immutable-head independent review findings.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MessageResult
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.session.MeshCoreSessionProtocol
import java.time.Duration
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewInterleavingTest {
    @TestFactory fun reviewCases() = listOf(
        native("persistedMessageCallbackCanAwaitAnotherDMWithoutHoldingTheWireLease") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring(); h.transport.acknowledge = true
            var callbacks = 0
            val outer = withTimeout(2000) {
                h.service.sendMessageWithRetry("outer", h.contact, onMessageCreated = { saved ->
                    callbacks++; assertEquals(MessageStatus.PENDING, h.stored(saved.id).status)
                    assertTrue(h.service.sendDirectMessage("nested", h.contact).status in setOf(MessageStatus.SENT, MessageStatus.DELIVERED))
                })
            }
            assertEquals(1, callbacks); assertEquals(MessageStatus.DELIVERED, outer.status)
            assertTrue(h.store.messages.values.all { it.status == MessageStatus.DELIVERED })
            assertEquals(listOf("nested", "outer"), h.sends(CommandCode.SEND_MESSAGE).map { it.slice(13, it.size).toByteArray().toString(Charsets.UTF_8) })
            h.close()
        },
        native("persistedMessageCallbackCanAwaitCloseWithoutSelfJoining") {
            val h = Harness(this); h.start(); var callbacks = 0
            withTimeout(2000) {
                assertFailsWith<MessageServiceException> {
                    h.service.sendMessageWithRetry("close callback", h.contact, onMessageCreated = {
                        callbacks++; assertTrue(h.service.close().isComplete)
                    })
                }
            }
            assertEquals(1, callbacks); assertEquals(MessageStatus.PENDING, h.store.messages.values.single().status)
            assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty()); h.session.stop()
        },
        native("ackDuringSuspendedRetryStatusPreventsRegisteringOrSendingTheNextAttempt") {
            val h = Harness(this, MessageServiceConfig(maxAttempts = 2, floodAfter = 5)); h.start()
            h.service.startEventMonitoring(); h.transport.suggestedTimeout = 1000u
            val gate = CompletableDeferred<Unit>(); var statusEntered = false
            h.store.before = { if (it == "updateMessageRetryStatus") { statusEntered = true; gate.await() } }
            val sending = backgroundScope.async { h.service.sendMessageWithRetry("retry interleave", h.contact) }
            runCurrent(); val message = h.store.messages.values.single()
            val first = assertNotNull(h.service.pendingAck(message.id)).ackCodes.first()
            advanceTimeBy(1201); runCurrent(); assertTrue(statusEntered)
            h.transport.pushAck(first, 321u); runCurrent(); assertEquals(MessageStatus.DELIVERED, h.stored(message.id).status)
            gate.complete(Unit); assertEquals(MessageStatus.DELIVERED, sending.await().status)
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size); h.close()
        },
        native("ackDuringSuspendedFloodResetPreventsTheFloodResend") {
            val h = Harness(this, MessageServiceConfig(maxAttempts = 2, floodAfter = 1)); h.start()
            h.service.startEventMonitoring(); h.transport.suggestedTimeout = 1000u
            val gate = CompletableDeferred<Unit>(); var resetEntered = false
            h.transport.beforeReply = { if (it[0] == CommandCode.RESET_PATH.rawValue) { resetEntered = true; gate.await() } }
            val sending = backgroundScope.async { h.service.sendMessageWithRetry("flood interleave", h.contact) }
            runCurrent(); val message = h.store.messages.values.single()
            val first = assertNotNull(h.service.pendingAck(message.id)).ackCodes.first()
            advanceTimeBy(1201); runCurrent(); assertTrue(resetEntered)
            h.transport.pushAck(first, 322u); runCurrent(); gate.complete(Unit)
            assertEquals(MessageStatus.DELIVERED, sending.await().status)
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size); assertEquals(1, h.sends(CommandCode.RESET_PATH).size); h.close()
        },
        native("confirmedResendFinalReadRecoveryDoesNotResendOrRecountAndNewClaimStillSends") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring(); h.transport.acknowledge = true
            val message = h.message(MessageStatus.SENT); h.pending(message, isResend = true)
            var failFinal = true
            h.store.before = {
                if (it == "fetchMessage" && failFinal && "incrementMessageSendCount" in h.store.calls) {
                    failFinal = false; throw storageFailure()
                }
            }
            val queue = h.queue(); h.signals.set(h.token, DeviceConnectionState.CONNECTED)
            queue.hydrate(); runCurrent(); h.signals.set(h.token, DeviceConnectionState.READY); runCurrent()
            assertEquals(2L, h.stored(message.id).sendCount); assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertTrue(h.store.hasPendingSend(EntityKey(RADIO, message.id)))
            queue.transportDidOpen(); runCurrent(); queue.awaitDrainCompletion()
            assertEquals(2L, h.stored(message.id).sendCount); assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertTrue(h.store.fetchPendingSends(RADIO).isEmpty())
            h.transport.expectedAckOverride = Bytes.of(9, 8, 7, 6)
            queue.enqueueDM(DirectMessageEnvelope(message.id, CONTACT, true)); runCurrent(); queue.awaitDrainCompletion()
            assertEquals(3L, h.stored(message.id).sendCount); assertEquals(2, h.sends(CommandCode.SEND_MESSAGE).size)
            assertEquals(MessageStatus.DELIVERED, h.stored(message.id).status)
            assertEquals(0x06070809u, h.stored(message.id).ackCode)
            assertEquals(123u, h.stored(message.id).roundTripTime)
            queue.shutdown(); h.close()
        },
        native("sharedGetMessageResultBeforeManualNoMoreIsDeliveredOnceBeforeSuccessfulCompletion") {
            val h = Harness(this); h.start(); val paused = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val actual = h.session
            val role = object : MeshCoreSessionProtocol by actual {
                override suspend fun stopAutoMessageFetching() {
                    actual.stopAutoMessageFetching(); paused.complete(Unit); release.await()
                }
            }
            val p = h.poller(role)
            val delivered = mutableListOf<String>(); val contexts = mutableListOf<DeliveryContext>()
            p.setContactMessageHandler { wire, _, context -> delivered += wire.text; contexts += context }
            p.startAutoFetch(RADIO); h.transport.holdGetReplies = true
            val authority = backgroundScope.async { actual.getMessage() }; runCurrent()
            val manual = backgroundScope.async { p.pollAllMessages() }; runCurrent(); assertTrue(paused.isCompleted)
            h.transport.mock.simulateReceive(contactPacket("preexisting authority")); runCurrent()
            assertIs<MessageResult.ContactMessage>(authority.await())
            h.transport.holdGetReplies = false; release.complete(Unit)
            assertEquals(0L, manual.await()); assertEquals(listOf("preexisting authority"), delivered)
            assertEquals(listOf<DeliveryContext>(DeliveryContext.Live), contexts); assertTrue(p.waitForPendingHandlers(Duration.ZERO))
            p.close(); h.close()
        },
        native("manualOwnershipKeepsNewestPauseAndPreventsResumeFromCompetingOnTheWire") {
            val h = Harness(this); h.start(); var starts = 0
            val actual = h.session
            val role = object : MeshCoreSessionProtocol by actual {
                override suspend fun startAutoMessageFetching() { starts++; actual.startAutoMessageFetching() }
            }
            val p = h.poller(role)
            val gate = CompletableDeferred<Unit>()
            p.setContactMessageHandler { _, _, _ -> gate.await() }
            p.startAutoFetch(RADIO); h.transport.incomingMessages += contactPacket("manual")
            val a = backgroundScope.async { p.pollAllMessages() }; val b = backgroundScope.async { p.pollAllMessages() }
            runCurrent(); assertEquals(1, starts); p.resumeAutoFetch(); assertEquals(1, starts)
            p.pauseAutoFetch(); a.cancelAndJoin(); gate.complete(Unit)
            assertEquals(1L, b.await()); assertEquals(1, starts)
            val before = h.sends(CommandCode.GET_MESSAGE).size
            h.transport.mock.simulateReceive(Bytes.of(ResponseCode.MESSAGES_WAITING.rawValue.toInt())); runCurrent()
            assertEquals(before, h.sends(CommandCode.GET_MESSAGE).size)
            p.resumeAutoFetch(); assertEquals(2, starts); p.close(); h.close()
        },
        native("resumeDuringManualOwnershipIsAppliedOnlyAfterTheManualDrainReleases") {
            val h = Harness(this); h.start(); var starts = 0
            val actual = h.session
            val role = object : MeshCoreSessionProtocol by actual {
                override suspend fun startAutoMessageFetching() { starts++; actual.startAutoMessageFetching() }
            }
            val p = h.poller(role)
            val gate = CompletableDeferred<Unit>(); p.setContactMessageHandler { _, _, _ -> gate.await() }
            p.startAutoFetch(RADIO); h.transport.incomingMessages += contactPacket("manual resume")
            val work = backgroundScope.async { p.pollAllMessages() }; runCurrent()
            p.pauseAutoFetch(); p.resumeAutoFetch(); assertEquals(1, starts)
            gate.complete(Unit); assertEquals(1L, work.await()); assertEquals(2, starts)
            p.close(); h.close()
        },
        native("rxCorrelationComparesCanonicalSenderKeysButNeverNormalizesTheContentHashInput") {
            fun rx(sender: String, body: String) = RxLogEntryDTO(UUID.randomUUID(), RADIO, BASE_TIME, 1.0, -70,
                RouteType.FLOOD, PayloadType.GROUP_TEXT, 0u, null, 1u, Bytes.of(0x80), Bytes.of(1), Bytes.of(1),
                "fixture", channelIndex = 0u, decryptStatus = DecryptStatus.SUCCESS, senderTimestamp = 42u,
                payloadTypeBits = 5u, decodedText = "$sender: $body")
            val key = DeduplicationKey.contentBased(null, 0u, "\u00E9", 42u, "one")
            assertEquals(1, ChannelRXCorrelation.matching(listOf(rx("e\u0301", "one")), key).size)
            val composedBody = DeduplicationKey.contentBased(null, 0u, "\u00E9", 42u, "\u00E9")
            assertTrue(ChannelRXCorrelation.matching(listOf(rx("e\u0301", "e\u0301")), composedBody).isEmpty())
            assertNotEquals(DeduplicationKey.contentBased(null, 0u, "\u00E9", 42u, "\u00E9"),
                DeduplicationKey.contentBased(null, 0u, "\u00E9", 42u, "e\u0301"))
        },
        native("generationChangeAfterCommittedResendCountCannotPublishOrFinalizeIntoASuccessor") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring(); h.transport.acknowledge = true
            val message = h.message(MessageStatus.SENT); val events = h.service.statusEvents()
            h.store.before = { if (it == "incrementMessageSendCount") h.signals.set(token(generation = 2), DeviceConnectionState.READY) }
            assertFailsWith<MessageServiceException> { h.service.resendDirectMessage(message.id, h.contact) }
            assertEquals(2L, h.stored(message.id).sendCount)
            assertFalse(statuses(h.service, events).any { it is MessageStatusEvent.Resent })
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size); h.close()
        },
    )
    private fun contactPacket(text: String): Bytes = ByteWriter().appendUInt8(ResponseCode.CONTACT_MESSAGE_RECEIVED.rawValue)
        .append(TARGET.prefix(6)).appendUInt8(0u).appendUInt8(0u).appendUInt32LE(42u).append(Bytes.utf8(text)).toBytes()
}
