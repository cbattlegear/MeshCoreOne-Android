// AndroidOnly: WP-208 Deterministic cancellation, wire, identity, persistence and consumer regressions.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.*
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.model.ResponseCode
import java.time.Duration
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class NativeMessagingTest {
    @TestFactory fun regressions() = listOf(
        native("defaultRetryBudgetIsExactlyFourBeforeResetAndOneAfter") {
            val h = Harness(this); h.start()
            h.service.sendMessageWithRetry("default budget", h.contact.copy(outPathLength = 2u, outPath = Bytes.of(1, 2)))
            val reset = h.transport.sentData.indexOfFirst { it[0] == CommandCode.RESET_PATH.rawValue }
            assertEquals(4, h.transport.sentData.take(reset).count { it[0] == CommandCode.SEND_MESSAGE.rawValue })
            assertEquals(1, h.transport.sentData.drop(reset + 1).count { it[0] == CommandCode.SEND_MESSAGE.rawValue })
            assertEquals(listOf(0, 1, 2, 3, 4), h.sends(CommandCode.SEND_MESSAGE).map { it[2].toInt() }); h.close()
        },
        native("ackBeforeMessageSentCannotCauseSecondWireSendOrOverwriteFirmwareRTT") {
            earlyAckCompletesOnce(actualOverride = null)
        },
        native("firmwareAckMismatchStillMatchesAnEarlyBufferedAuthoritativeAck") {
            earlyAckCompletesOnce(actualOverride = Bytes.of(9, 8, 7, 6))
        },
        native("lateDifferentActualAckRetiresTrackerAndCompletesRetryWithListener") {
            lateDifferentActualAckCompletes(queued = false, listener = true)
        },
        native("lateDifferentActualAckCompletesRetryThroughRawMailbox") {
            lateDifferentActualAckCompletes(queued = false, listener = false)
        },
        native("lateDifferentActualAckRetiresTrackerAndDrainsRealQueue") {
            lateDifferentActualAckCompletes(queued = true, listener = true)
        },
        native("listenerDoesNotCompleteDeliveryReceiptBeforeAuthoritySettlement") {
            deliveryReceiptWaitsForAuthority(listener = true)
        },
        native("rawMailboxDoesNotCompleteDeliveryReceiptBeforeAuthoritySettlement") {
            deliveryReceiptWaitsForAuthority(listener = false)
        },
        native("eachPhysicalPoolBackoffCallHasItsOwnPrecommittedEmissionMarker") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            var calls = 0
            h.transport.beforeReply = { bytes -> if (bytes[0] == CommandCode.SEND_MESSAGE.rawValue) {
                calls++
                val evidence = assertIs<DirectAckRadioEvidenceObservation.Found>(
                    h.store.directAckAuthority.inspectDirectAckRadioEvidence(RADIO)).snapshot.wires
                assertEquals(calls, evidence.size)
                assertEquals(DirectAckRadioWireOutcome.MayHaveWritten, evidence.last().outcome)
                h.transport.directError = if (calls == 1) 3u else null
            } }
            val first = h.service.sendDirectMessage("pool backoff physical evidence", h.contact)
            val evidence = assertIs<DirectAckRadioEvidenceObservation.Found>(
                h.store.directAckAuthority.inspectDirectAckRadioEvidence(RADIO)).snapshot.wires
            assertEquals(2, evidence.size)
            assertEquals(1, evidence.map { it.wireRequestId }.toSet().size)
            assertEquals(2, evidence.map { it.emissionRequestId }.toSet().size)
            assertEquals(DirectAckRadioWireOutcome.MayHaveWritten, evidence.first().outcome)
            assertEquals(DirectAckRadioWireOutcome.Accepted(requireNotNull(first.ackCode)), evidence.last().outcome)
            h.transport.beforeReply = null
            val distinctCode = Bytes.of(9, 8, 7, 6)
            h.transport.expectedAckOverride = distinctCode
            val later = h.service.sendDirectMessage("distinct after unresolved pool rejection", h.contact)
            val tracker = assertNotNull(h.service.pendingAck(later.id))
            val events = h.service.statusEvents()
            h.transport.pushAck(distinctCode, 999u); runCurrent()
            assertEquals(later, h.stored(later.id))
            assertFalse(tracker.receipt.isCompleted)
            assertNull(assertNotNull(h.service.pendingAck(later.id)).acknowledgement)
            val uncertain = assertIs<DirectAckNonsettlementException>(
                h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().single {
                    it.operation == "ackAmbiguous.${later.id}"
                }.cause)
            assertEquals(h.service.token, uncertain.token)
            assertEquals(later.id, uncertain.messageID)
            assertEquals(setOf(DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION), uncertain.reasons.toSet())
            assertTrue(statuses(h.service, events).isEmpty())
            assertEquals(3, h.sends(CommandCode.SEND_MESSAGE).size)
            h.close()
        },
        native("preDispatchMarkerFailureLeavesNotificationReadyAndNeverCallsTransport") {
            val h = Harness(this); h.start()
            val receipt = NotificationActionReceipt()
            val fault = storageFailure()
            h.store.faults["markDirectAckWireMayHaveWritten"] = fault
            val sending = backgroundScope.async(receipt) {
                assertFailsWith<PersistenceStoreException> {
                    h.service.sendDirectMessage("marker failure no transport", h.contact)
                }
            }
            sending.await()
            assertEquals(NotificationActionReceipt.State.READY, receipt.state)
            assertEquals(0, h.sends(CommandCode.SEND_MESSAGE).size)
            assertTrue(assertIs<DirectAckRadioEvidenceObservation.Found>(
                h.store.directAckAuthority.inspectDirectAckRadioEvidence(RADIO)).snapshot.wires
                .none { it.outcome is DirectAckRadioWireOutcome.Accepted })
            val failedBeforeTransport = h.store.messages.values.single()
            h.store.faults.clear()
            h.service.startEventMonitoring()
            val actual = Bytes.of(9, 8, 7, 6)
            h.transport.expectedAckOverride = actual
            val later = h.service.sendDirectMessage("distinct after modern pre-marker failure", h.contact)
            val tracker = assertNotNull(h.service.pendingAck(later.id))
            val events = h.service.statusEvents()
            h.transport.pushAck(actual, 321u); runCurrent()
            assertEquals(later.copy(status = MessageStatus.DELIVERED, roundTripTime = 321u), h.stored(later.id))
            assertEquals(MeshAcknowledgement(actual, 321u), tracker.receipt.await())
            assertEquals(failedBeforeTransport, h.stored(failedBeforeTransport.id))
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertEquals(listOf(MessageStatusEvent.StatusResolved(later.id, MessageStatus.DELIVERED, 321u)),
                statuses(h.service, events))
            h.close()
        },
        native("legacyMarkerlessUnknownCannotBeUpgradedByANewerModernWire") {
            legacyUnknownBlocksModernDelivery(acceptedCode = null)
        },
        native("legacyAcceptedCodeDoesNotDismissUnknownEarlierPhysicalEmissions") {
            legacyUnknownBlocksModernDelivery(acceptedCode = 0x44332211u)
        },
        native("committedMarkerUnknownReturnReplaysOnlyExactMetadataAndNeverTransport") {
            val h = Harness(this); h.start()
            val receipt = NotificationActionReceipt()
            var committed: DirectAckEmissionReceipt? = null
            var attempt: DirectAckAttempt? = null
            h.store.directAckAuthority.afterEmissionMark = {
                committed = it
                attempt = h.service.pendingAck(h.store.messages.values.single().id)?.authority
                throw storageFailure()
            }
            val sending = backgroundScope.async(receipt) {
                assertFailsWith<PersistenceStoreException> {
                    h.service.sendDirectMessage("marker committed response lost", h.contact)
                }
            }
            sending.await()
            val marker = assertNotNull(committed)
            assertEquals(NotificationActionReceipt.State.READY, receipt.state)
            assertEquals(0, h.sends(CommandCode.SEND_MESSAGE).size)
            h.store.directAckAuthority.afterEmissionMark = null
            val replay = assertIs<DirectAckEmissionMarkResult.AlreadyMarked>(
                h.store.directAckAuthority.markDirectAckWireMayHaveWritten(
                    assertNotNull(attempt), marker.evidence.wireRequestId, marker.emissionRequestId))
            assertEquals(marker, replay.receipt)
            assertEquals(DirectAckRadioWireOutcome.MayHaveWritten, replay.receipt.evidence.outcome)
            assertEquals(marker, assertIs<DirectAckEmissionObservation.Found>(
                h.store.directAckAuthority.inspectDirectAckWireEmission(
                    assertNotNull(attempt), marker.evidence.wireRequestId, marker.emissionRequestId)).receipt)
            assertEquals(0, h.sends(CommandCode.SEND_MESSAGE).size)
            assertTrue(h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
                it.operation == "ackMark.${marker.evidence.claimRequestId}.${marker.evidence.wireRequestId}.${marker.emissionRequestId}"
            })
            h.close()
        },
        native("missingRadioExclusionCapabilityNeverFallsBackToLegacyDispatch") {
            val h = Harness(this); h.start()
            val service = MessageService(h.token, SELF, h.session, h.store, h.signals, h.scope,
                clock = h.clock, directAckAuthority = h.store.directAckAuthority,
                directPendingCompletionAuthority = h.store.directAckAuthority,
                directAckRadioExclusionAuthority = null)
            try {
                assertFailsWith<PersistenceStoreException> {
                    service.sendDirectMessage("capability absent", h.contact)
                }
                assertEquals(0, h.sends(CommandCode.SEND_MESSAGE).size)
                assertFalse(h.store.calls.any { it == "resolveDirectAckWireEmission" })
                assertTrue(h.store.messages.values.none { it.status == MessageStatus.DELIVERED })
            } finally { service.close(); h.close() }
        },
        native("notificationAcceptancePrecedesSuspendingPhysicalAcceptedResolution") {
            val h = Harness(this); h.start()
            val receipt = NotificationActionReceipt()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            h.store.before = { if (it == "resolveDirectAckWireEmission") {
                entered.complete(Unit); release.await()
            } }
            val sending = backgroundScope.async(receipt) {
                h.service.sendDirectMessage("accepted before evidence refinement", h.contact)
            }
            try {
                entered.await()
                assertEquals(NotificationActionReceipt.State.ACCEPTED, receipt.state)
                assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
                sending.cancelAndJoin()
                assertEquals(NotificationActionReceipt.State.ACCEPTED, receipt.state)
                h.store.before = null; release.complete(Unit); runCurrent()
                assertIs<DirectAckRadioWireOutcome.Accepted>(assertIs<DirectAckRadioEvidenceObservation.Found>(
                    h.store.directAckAuthority.inspectDirectAckRadioEvidence(RADIO)).snapshot.wires.single().outcome)
                assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            } finally {
                withContext(NonCancellable) {
                    release.complete(Unit); h.store.before = null; sending.cancelAndJoin(); h.close()
                }
            }
        },
        native("transportAcceptanceCannotSynthesizeRecipientDeliveryDuringFinalization") {
            val h = Harness(this); h.start()
            val message = h.service.sendDirectMessage("accepted not acknowledged", h.contact)
            val tracker = assertNotNull(h.service.pendingAck(message.id))
            val events = h.service.statusEvents()
            assertFailsWith<PersistenceStoreException> {
                h.service.finalizeSend(message.id, CONTACT, TARGET,
                    com.meshcoreone.android.core.protocol.event.MessageSentInfo(0u, tracker.ackCodes.single(), 10u), 0u)
            }
            assertEquals(message, h.stored(message.id))
            assertFalse(tracker.receipt.isCompleted)
            assertTrue(statuses(h.service, events).isEmpty())
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            h.close()
        },
        native("acceptedPhysicalResolutionRecoveryDoesNotRepeatWireOrQueueAttempt") {
            val h = Harness(this, MessageServiceConfig(maxAttempts = 1))
            h.start(); h.service.startEventMonitoring()
            val actual = Bytes.of(9, 8, 7, 6)
            h.transport.expectedAckOverride = actual
            val message = h.message(MessageStatus.PENDING)
            h.pending(message)
            var failResolution = true
            h.store.before = { if (it == "resolveDirectAckWireEmission" && failResolution) {
                failResolution = false; throw storageFailure()
            } }
            val queue = h.queue()
            h.signals.set(h.token, DeviceConnectionState.CONNECTED)
            queue.hydrate(); runCurrent()
            h.signals.set(h.token, DeviceConnectionState.READY); runCurrent()
            val before = h.stored(message.id)
            assertEquals(MessageStatus.PENDING, before.status)
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertEquals(1L, h.store.fetchPendingSends(RADIO).single().attemptCount)
            assertEquals(DirectAckRadioWireOutcome.MayHaveWritten,
                assertIs<DirectAckRadioEvidenceObservation.Found>(
                    h.store.directAckAuthority.inspectDirectAckRadioEvidence(RADIO)).snapshot.wires.single().outcome)
            val events = h.service.statusEvents()
            h.store.before = null
            queue.transportDidOpen(); runCurrent()
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertEquals(1L, h.store.fetchPendingSends(RADIO).single().attemptCount)
            val tracker = assertNotNull(h.service.pendingAck(message.id))
            assertTrue(actual in tracker.ackCodes)
            h.transport.pushAck(actual, 321u); runCurrent(); queue.awaitDrainCompletion()
            assertEquals(before.copy(status = MessageStatus.DELIVERED,
                ackCode = actual.ackCodeUInt32, roundTripTime = 321u), h.stored(message.id))
            assertEquals(MeshAcknowledgement(actual, 321u), tracker.receipt.await())
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertTrue(h.store.fetchPendingSends(RADIO).isEmpty())
            assertEquals(0, h.service.pendingAckCount)
            assertEquals(0, h.service.retainedDirectAuthorityCount)
            assertEquals(listOf(MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 321u)),
                statuses(h.service, events))
            queue.shutdown(); h.close()
        },
        native("inlineAcceptedEvidenceRecoveryReconcilesOnlyItsExistingLiveAckBuffer") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            val actual = Bytes.of(9, 8, 7, 6)
            h.transport.expectedAckOverride = actual
            h.transport.acknowledge = true
            h.transport.ackBeforeAcceptance = true
            var failResolution = true
            h.store.before = { if (it == "resolveDirectAckWireEmission" && failResolution) {
                failResolution = false; throw storageFailure()
            } }
            val receipt = NotificationActionReceipt()
            val events = h.service.statusEvents()
            val sending = backgroundScope.async(receipt) {
                assertFailsWith<PersistenceStoreException> {
                    h.service.sendDirectMessage("live buffered ack metadata recovery", h.contact)
                }
            }
            sending.await(); runCurrent()
            assertTrue(receipt.isAccepted)
            val before = h.store.messages.values.single()
            assertEquals(MessageStatus.PENDING, before.status)
            assertEquals(1, h.service.bufferedAcknowledgementCount(before.id))
            val tracker = assertNotNull(h.service.pendingAck(before.id))
            assertFalse(tracker.receipt.isCompleted)
            h.store.before = null
            h.service.checkExpiredAcks(); runCurrent()
            assertEquals(before.copy(status = MessageStatus.DELIVERED,
                ackCode = actual.ackCodeUInt32, roundTripTime = 123u), h.stored(before.id))
            assertEquals(MeshAcknowledgement(actual, 123u), tracker.receipt.await())
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertEquals(0, h.service.bufferedAcknowledgementCount(before.id))
            assertEquals(0, h.service.pendingAckCount)
            assertEquals(0, h.service.retainedDirectAuthorityCount)
            assertEquals(listOf(MessageStatusEvent.StatusResolved(before.id, MessageStatus.DELIVERED, 123u)),
                statuses(h.service, events))
            h.close()
        },
        native("oldActualCodeCannotDeliverANewerClaimWithListener") {
            oldActualCodeCannotDeliverNewerClaim(listener = true)
        },
        native("oldActualCodeCannotCompleteANewerRawMailboxWait") {
            oldActualCodeCannotDeliverNewerClaim(listener = false)
        },
        native("definitelyUnsentSerializerCancellationDoesNotExcludeDistinctSuccessorAck") {
            cancelledWirePredecessor(definitelyUnsent = true)
        },
        native("possiblyWrittenCancellationStillExcludesDistinctSuccessorAck") {
            cancelledWirePredecessor(definitelyUnsent = false)
        },
        native("displacedUnwiredRetryEmissionRemainsExcludedFromItsReplacement") {
            val h = Harness(this); h.start()
            val message = h.message(status = MessageStatus.PENDING, text = "displaced before wire")
            val ids = listOf(UUID.randomUUID(), message.id).iterator()
            val service = MessageService(h.token, SELF, h.session, h.store, h.signals, h.scope,
                config = MessageServiceConfig(maxAttempts = 1), clock = h.clock, newID = { ids.next() },
                reporter = MessagingIssueReporter { h.diagnostics += it },
                directAckAuthority = h.store.directAckAuthority,
                directPendingCompletionAuthority = h.store.directAckAuthority)
            service.startEventMonitoring()
            val release = CompletableDeferred<Unit>()
            h.transport.acknowledge = false
            h.transport.expectedAckOverride = Bytes.of(1, 2, 3, 4)
            h.transport.acceptanceGate = release
            val blocker = backgroundScope.async { service.sendDirectMessage("holds DM operations", h.contact) }
            val oldReceipt = NotificationActionReceipt()
            val old = backgroundScope.async(oldReceipt) {
                assertFailsWith<PersistenceStoreException> {
                    service.sendPendingDirectMessage(message.id, h.contact, preserveTimestamp = true)
                }
            }
            var replacement: Deferred<MessageDTO>? = null
            try {
                runCurrent()
                val oldTracker = assertNotNull(service.pendingAck(message.id))
                assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
                assertEquals(NotificationActionReceipt.State.READY, oldReceipt.state)
                val newClaim = backgroundScope.async { service.sendDirectMessage(message.text, h.contact) }
                replacement = newClaim
                runCurrent()
                assertNotSame(oldTracker.receipt, assertNotNull(service.pendingAck(message.id)).receipt)
                assertTrue(oldTracker.receipt.isCancelled)
                assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
                val actual = Bytes.of(9, 8, 7, 6)
                h.transport.expectedAckOverride = actual
                release.complete(Unit); runCurrent()
                blocker.await()
                val oldFailure = old.await()
                assertEquals("Direct ACK authority: INVALIDATED",
                    assertIs<PersistenceStoreError.SaveFailed>(oldFailure.error).reason)
                newClaim.await()
                assertEquals(NotificationActionReceipt.State.READY, oldReceipt.state)
                assertEquals(2, h.sends(CommandCode.SEND_MESSAGE).size)
                val before = h.stored(message.id)
                val tracker = assertNotNull(service.pendingAck(message.id))
                val events = service.statusEvents()
                h.transport.pushAck(actual, 999u); runCurrent()
                assertEquals(before.copy(status = MessageStatus.DELIVERED, roundTripTime = 999u), h.stored(message.id))
                assertEquals(MessageStatus.SENT, before.status)
                assertEquals(MeshAcknowledgement(actual, 999u), tracker.receipt.await())
                assertNull(service.pendingAck(message.id))
                assertTrue(h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().none {
                    it.operation.startsWith("ackAmbiguous.")
                })
                assertEquals(listOf(MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 999u)),
                    statuses(service, events))
            } finally {
                withContext(NonCancellable) {
                    release.complete(Unit)
                    old.cancelAndJoin(); replacement?.cancelAndJoin(); blocker.cancelAndJoin()
                    service.close(); h.close()
                }
            }
        },
        native("compactedKnownEmissionHistoryAllowsDistinctLaterResend") {
            val h = Harness(this, MessageServiceConfig(maxAttempts = 1, minTimeout = 0.0))
            h.start(); h.service.startEventMonitoring()
            h.transport.acknowledge = false
            h.transport.expectedAckOverride = ByteWriter().appendUInt32LE(1u).toBytes()
            val message = h.service.sendDirectMessage("bounded predecessor history", h.contact)
            repeat(65) { index ->
                h.transport.expectedAckOverride = ByteWriter().appendUInt32LE((index + 2).toUInt()).toBytes()
                val sending = async { h.service.resendDirectMessage(message.id, h.contact, preserveTimestamp = true) }
                runCurrent(); advanceTimeBy(13); runCurrent()
                sending.await()
            }
            val actual = ByteWriter().appendUInt32LE(1000u).toBytes()
            h.transport.expectedAckOverride = actual
            val sending = async { h.service.resendDirectMessage(message.id, h.contact, preserveTimestamp = true) }
            try {
                runCurrent()
                val before = h.stored(message.id)
                h.transport.pushAck(actual, 321u); runCurrent()
                val delivered = sending.await()
                assertEquals(MessageStatus.DELIVERED, delivered.status)
                assertEquals(1000u, delivered.ackCode)
                assertEquals(321u, delivered.roundTripTime)
                assertEquals(before.sendCount + 1, delivered.sendCount)
                assertEquals(67, h.sends(CommandCode.SEND_MESSAGE).size)
                assertEquals(0, h.service.pendingAckCount)
                assertEquals(0, h.service.retainedDirectAuthorityCount)
                assertEquals(67, h.service.emissionHistoryBucketCount)
                assertTrue(h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().none {
                    it.operation.startsWith("ackAmbiguous.") })
            } finally {
                withContext(NonCancellable) { sending.cancelAndJoin(); h.close() }
            }
        },
        native("compactedHistoryDoesNotBlockIndependentDistinctMessage") {
            val h = Harness(this, MessageServiceConfig(maxAttempts = 1, minTimeout = 0.0))
            h.start(); h.service.startEventMonitoring()
            h.transport.acknowledge = false
            val olderCode = ByteWriter().appendUInt32LE(7000u).toBytes()
            h.transport.expectedAckOverride = olderCode
            val older = h.service.sendDirectMessage("older confirmed tracker", h.contact)
            h.transport.expectedAckOverride = ByteWriter().appendUInt32LE(1u).toBytes()
            val later = h.service.sendDirectMessage("later saturation", h.contact)
            repeat(65) { index ->
                h.transport.expectedAckOverride = ByteWriter().appendUInt32LE((index + 2).toUInt()).toBytes()
                val sending = async { h.service.resendDirectMessage(later.id, h.contact, preserveTimestamp = true) }
                runCurrent(); advanceTimeBy(13); runCurrent(); sending.await()
            }
            val actual = ByteWriter().appendUInt32LE(1000u).toBytes()
            h.transport.expectedAckOverride = actual
            val sending = async { h.service.sendMessageWithRetry("independently distinct after compaction", h.contact) }
            try {
                runCurrent()
                val beforeOlder = h.stored(older.id)
                val beforeLater = h.stored(later.id)
                val olderTracker = assertNotNull(h.service.pendingAck(older.id))
                val events = h.service.statusEvents()
                h.transport.pushAck(olderCode, 555u); runCurrent()
                assertEquals(beforeOlder.copy(status = MessageStatus.DELIVERED, roundTripTime = 555u), h.stored(older.id))
                assertEquals(MeshAcknowledgement(olderCode, 555u), olderTracker.receipt.await())
                assertNull(h.service.pendingAck(older.id))
                h.transport.pushAck(actual, 321u); runCurrent()
                val delivered = sending.await()
                assertEquals(beforeLater, h.stored(later.id))
                assertEquals(MessageStatus.DELIVERED, delivered.status)
                assertEquals(1000u, delivered.ackCode)
                assertEquals(321u, delivered.roundTripTime)
                assertEquals(1L, delivered.sendCount)
                assertEquals(68, h.sends(CommandCode.SEND_MESSAGE).size)
                assertNull(h.service.pendingAck(delivered.id))
                assertEquals(1, h.service.retainedDirectAuthorityCount)
                assertEquals(67, h.service.emissionHistoryBucketCount)
                assertTrue(h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().none {
                    it.operation.startsWith("ackAmbiguous.") })
                assertEquals(listOf(
                    MessageStatusEvent.StatusResolved(older.id, MessageStatus.DELIVERED, 555u),
                    MessageStatusEvent.StatusResolved(delivered.id, MessageStatus.DELIVERED, 321u),
                ), statuses(h.service, events))
            } finally {
                withContext(NonCancellable) { sending.cancelAndJoin(); h.close() }
            }
        },
        native("successfulDistinctTrafficRetiresTerminalAuthorityStateBeyondSixtyFourMessages") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            h.transport.acknowledge = true
            repeat(128) { index ->
                val code = (index + 1).toUInt()
                h.transport.expectedAckOverride = ByteWriter().appendUInt32LE(code).toBytes()
                val delivered = h.service.sendMessageWithRetry("distinct message $index", h.contact)
                assertEquals(MessageStatus.DELIVERED, delivered.status)
                assertEquals(code, delivered.ackCode)
                assertEquals(123u, delivered.roundTripTime)
                assertEquals(1L, delivered.sendCount)
                assertEquals(0, h.service.pendingAckCount)
                assertEquals(0, h.service.retainedDirectAuthorityCount)
                assertEquals(index + 1, h.sends(CommandCode.SEND_MESSAGE).size)
                assertEquals(index + 1, h.service.emissionHistoryBucketCount)
            }
            assertEquals(128, h.store.messages.size)
            h.close()
        },
        native("persistedFullCodeHistoryDisambiguatesLocalDiagnosticBucketCollision") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            h.transport.acknowledge = true
            repeat(65) { index ->
                h.transport.expectedAckOverride = ByteWriter().appendUInt32LE((index + 1).toUInt()).toBytes()
                assertEquals(MessageStatus.DELIVERED,
                    h.service.sendMessageWithRetry("compact history $index", h.contact).status)
            }
            assertEquals(0, h.service.retainedDirectAuthorityCount)
            assertEquals(65, h.service.emissionHistoryBucketCount)
            h.transport.acknowledge = false
            val differentActual = ByteWriter().appendUInt32LE(65537u).toBytes()
            h.transport.expectedAckOverride = differentActual
            val message = h.service.sendDirectMessage("same bucket different full code", h.contact)
            val tracker = assertNotNull(h.service.pendingAck(message.id))
            val events = h.service.statusEvents()
            h.transport.pushAck(differentActual, 999u); runCurrent()
            assertEquals(message.copy(status = MessageStatus.DELIVERED, roundTripTime = 999u), h.stored(message.id))
            assertEquals(MeshAcknowledgement(differentActual, 999u), tracker.receipt.await())
            assertNull(h.service.pendingAck(message.id))
            assertEquals(66, h.sends(CommandCode.SEND_MESSAGE).size)
            assertEquals(listOf(MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 999u)),
                statuses(h.service, events))
            assertTrue(h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
                .none { it.operation.startsWith("ackAmbiguous.") })
            h.close()
        },
        native("persistedHistoryPreservesEveryPreviouslyKnownCodeAcrossDiagnosticBuckets") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            h.transport.acknowledge = true
            repeat(65) { index ->
                h.transport.expectedAckOverride = ByteWriter().appendUInt32LE((index + 1).toUInt()).toBytes()
                assertEquals(MessageStatus.DELIVERED,
                    h.service.sendMessageWithRetry("promote code $index", h.contact).status)
            }
            assertEquals(65, h.service.emissionHistoryBucketCount)
            h.transport.acknowledge = false
            val events = h.service.statusEvents()
            val observed = mutableListOf<MessageStatusEvent>()
            val collector = backgroundScope.launch { events.events.collect { observed += it.event } }
            repeat(65) { index ->
                val actual = ByteWriter().appendUInt32LE((index + 1).toUInt()).toBytes()
                h.transport.expectedAckOverride = actual
                val message = h.service.sendDirectMessage("cannot forget promoted code $index", h.contact)
                runCurrent()
                val beforeEvents = observed.toList()
                val tracker = assertNotNull(h.service.pendingAck(message.id))
                h.transport.pushAck(actual, 999u); runCurrent()
                assertEquals(message, h.stored(message.id))
                assertFalse(tracker.receipt.isCompleted)
                assertEquals(beforeEvents, observed)
                assertEquals(66 + index, h.sends(CommandCode.SEND_MESSAGE).size)
            }
            assertEquals(65, h.service.retainedDirectAuthorityCount)
            assertEquals(65, h.service.emissionHistoryBucketCount)
            collector.cancelAndJoin(); h.close()
        },
        native("activeInlineCompletionRetainsItsAuthorityAfterTrackerRetirement") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            h.transport.acknowledge = true
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            h.store.before = { operation ->
                if (operation == "fetchMessage" && h.store.messages.values.singleOrNull()?.let {
                    it.status == MessageStatus.DELIVERED && h.service.pendingAck(it.id) == null
                } == true) {
                    entered.complete(Unit); release.await()
                }
            }
            val sending = backgroundScope.async { h.service.sendDirectMessage("active inline finalization", h.contact) }
            try {
                entered.await()
                assertEquals(0, h.service.pendingAckCount)
                assertEquals(1, h.service.retainedDirectAuthorityCount)
                release.complete(Unit); runCurrent()
                val delivered = sending.await()
                assertEquals(MessageStatus.DELIVERED, delivered.status)
                assertEquals(123u, delivered.roundTripTime)
                assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
                assertEquals(0, h.service.retainedDirectAuthorityCount)
            } finally {
                withContext(NonCancellable) {
                    release.complete(Unit); sending.cancelAndJoin(); h.store.before = null; h.close()
                }
            }
        },
        native("unknownEmittedOutcomeCannotBeResetByANewerSessionGraph") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            h.transport.holdMessageReplies = true
            val receipt = NotificationActionReceipt()
            val first = backgroundScope.async(receipt) { h.service.sendDirectMessage("unknown prior generation", h.contact) }
            runCurrent()
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            first.cancelAndJoin()
            assertEquals(NotificationActionReceipt.State.UNCERTAIN, receipt.state)
            h.close()
            val successor = Harness(this, store = h.store, generation = 2)
            successor.start(); successor.service.startEventMonitoring()
            val actual = Bytes.of(9, 8, 7, 6)
            successor.transport.expectedAckOverride = actual
            val message = successor.service.sendDirectMessage("newer generation same radio", successor.contact)
            val tracker = assertNotNull(successor.service.pendingAck(message.id))
            val events = successor.service.statusEvents()
            successor.transport.pushAck(actual, 999u); runCurrent()
            assertEquals(message, successor.stored(message.id))
            assertFalse(tracker.receipt.isCompleted)
            assertEquals(1, successor.sends(CommandCode.SEND_MESSAGE).size)
            assertTrue(statuses(successor.service, events).isEmpty())
            successor.close()
        },
        native("acceptedPhysicalEvidenceFinishesAcrossTeardownWithoutRenewingOldProductAuthority") {
            val h = Harness(this); h.start()
            val oldCode = Bytes.of(1, 2, 3, 4)
            h.transport.expectedAckOverride = oldCode
            val receipt = NotificationActionReceipt()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            h.store.before = { if (it == "resolveDirectAckWireEmission") {
                entered.complete(Unit); release.await()
            } }
            val sending = backgroundScope.async(receipt) {
                h.service.sendDirectMessage("accepted retired graph evidence", h.contact)
            }
            var closing: Deferred<TeardownReport>? = null
            try {
                entered.await()
                assertTrue(receipt.isAccepted)
                sending.cancelAndJoin()
                val oldRow = h.store.messages.values.single()
                h.signals.set(token(generation = 2), DeviceConnectionState.READY)
                val teardown = backgroundScope.async { h.service.close() }
                closing = teardown
                runCurrent(); assertFalse(teardown.isCompleted)
                h.store.before = null; release.complete(Unit); runCurrent(); teardown.await()
                val evidence = assertIs<DirectAckRadioEvidenceObservation.Found>(
                    h.store.directAckAuthority.inspectDirectAckRadioEvidence(RADIO)).snapshot.wires.single()
                assertEquals(DirectAckRadioWireOutcome.Accepted(oldCode.ackCodeUInt32), evidence.outcome)
                assertEquals(oldRow, h.stored(oldRow.id))
                assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
                h.close()
                val successor = Harness(this, store = h.store, generation = 2)
                successor.start(); successor.service.startEventMonitoring()
                successor.transport.expectedAckOverride = Bytes.of(9, 8, 7, 6)
                successor.transport.acknowledge = true
                val delivered = successor.service.sendMessageWithRetry("known disjoint successor", successor.contact)
                assertEquals(MessageStatus.DELIVERED, delivered.status)
                assertEquals(0x06070809u, delivered.ackCode)
                assertEquals(123u, delivered.roundTripTime)
                assertEquals(oldRow, successor.stored(oldRow.id))
                assertEquals(1, successor.sends(CommandCode.SEND_MESSAGE).size)
                successor.close()
            } finally {
                withContext(NonCancellable) {
                    h.store.before = null; release.complete(Unit)
                    sending.cancelAndJoin(); closing?.await(); h.close()
                }
            }
        },
        native("bufferedUnrelatedAckIsReportedUnmatchedOnlyAfterActualResponse") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            val gate = CompletableDeferred<Unit>()
            h.transport.acknowledge = false
            h.transport.expectedAckOverride = Bytes.of(9, 8, 7, 6)
            h.transport.acceptanceGate = gate
            val sending = async { h.service.sendDirectMessage("deferred unmatched", h.contact) }
            runCurrent()
            h.transport.pushAck(Bytes.of(1, 2, 3, 4), 123u); runCurrent()
            assertTrue(h.diagnostics.none { it is MessagingDiagnostic.UnmatchedAcknowledgement })
            gate.complete(Unit); runCurrent(); sending.await()
            assertEquals(1, h.diagnostics.filterIsInstance<MessagingDiagnostic.UnmatchedAcknowledgement>().size)
            h.close()
        },
        native("confirmedOtherMessageAckDoesNotConsumeUnresolvedBufferOrReportUnmatched") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            h.transport.acknowledge = false
            val firstCode = Bytes.of(9, 8, 7, 6)
            h.transport.expectedAckOverride = firstCode
            val first = h.service.sendDirectMessage("confirmed first", h.contact)
            val gate = CompletableDeferred<Unit>()
            h.transport.expectedAckOverride = Bytes.of(5, 6, 7, 8)
            h.transport.acceptanceGate = gate
            val second = async { h.service.sendDirectMessage("unresolved second", h.contact) }
            runCurrent()
            val secondID = h.store.messages.values.single { it.id != first.id }.id
            val beforeFirst = h.stored(first.id)
            val beforeSecond = h.stored(secondID)
            val firstTracker = assertNotNull(h.service.pendingAck(first.id))
            val secondTracker = assertNotNull(h.service.pendingAck(secondID))
            h.transport.pushAck(firstCode, 123u); runCurrent()
            assertEquals(beforeFirst, h.stored(first.id))
            assertEquals(beforeSecond, h.stored(secondID))
            assertFalse(firstTracker.receipt.isCompleted)
            assertFalse(secondTracker.receipt.isCompleted)
            assertEquals(0, h.service.bufferedAcknowledgementCount(secondID))
            gate.complete(Unit); runCurrent(); second.await()
            assertEquals(beforeFirst, h.stored(first.id))
            h.transport.pushAck(firstCode, 123u); runCurrent()
            assertEquals(beforeFirst.copy(status = MessageStatus.DELIVERED, roundTripTime = 123u), h.stored(first.id))
            assertEquals(MessageStatus.DELIVERED, h.stored(first.id).status)
            assertEquals(MeshAcknowledgement(firstCode, 123u), firstTracker.receipt.await())
            assertEquals(2, h.sends(CommandCode.SEND_MESSAGE).size)
            assertTrue(h.diagnostics.none { it is MessagingDiagnostic.UnmatchedAcknowledgement })
            h.close()
        },
        native("laterEmittedUnknownOrMatchingCodeCannotDeliverAnOlderConfirmedTracker") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            h.transport.acknowledge = false
            val actual = Bytes.of(9, 8, 7, 6)
            h.transport.expectedAckOverride = actual
            val first = h.service.sendDirectMessage("confirmed older", h.contact)
            val gate = CompletableDeferred<Unit>()
            h.transport.acceptanceGate = gate
            val second = backgroundScope.async {
                assertFailsWith<MessageServiceException> { h.service.sendDirectMessage("later different prediction", h.contact) }
            }
            try {
                runCurrent()
                val secondID = h.store.messages.values.single { it.id != first.id }.id
                val beforeFirst = h.stored(first.id)
                val beforeSecond = h.stored(secondID)
                val firstTracker = assertNotNull(h.service.pendingAck(first.id))
                val secondTracker = assertNotNull(h.service.pendingAck(secondID))
                assertFalse(actual in secondTracker.ackCodes)
                assertEquals(2, h.sends(CommandCode.SEND_MESSAGE).size)
                val events = h.service.statusEvents()
                h.transport.pushAck(actual, 999u); runCurrent()
                assertEquals(beforeFirst, h.stored(first.id))
                assertEquals(beforeSecond, h.stored(secondID))
                assertFalse(firstTracker.receipt.isCompleted)
                assertFalse(secondTracker.receipt.isCompleted)
                assertNull(assertNotNull(h.service.pendingAck(first.id)).acknowledgement)
                assertNull(assertNotNull(h.service.pendingAck(secondID)).acknowledgement)
                val uncertain = assertIs<DirectAckNonsettlementException>(
                    h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().single {
                        it.operation == "ackAmbiguous.${first.id}"
                    }.cause)
                assertEquals(h.service.token, uncertain.token)
                assertEquals(first.id, uncertain.messageID)
                assertEquals(actual.readUInt32LE(0), uncertain.acknowledgedCode)
                assertEquals(setOf(DirectAckRadioNonsettlingReason.KNOWN_OTHER_CLAIM_CODE), uncertain.reasons.toSet())
                assertTrue(statuses(h.service, events).isEmpty())
                gate.complete(Unit); runCurrent(); second.await()
                h.transport.pushAck(actual, 888u); runCurrent()
                assertEquals(beforeFirst, h.stored(first.id))
                assertFalse(firstTracker.receipt.isCompleted)
                assertEquals(2, h.sends(CommandCode.SEND_MESSAGE).size)
            } finally {
                withContext(NonCancellable) { gate.complete(Unit); second.cancelAndJoin(); h.close() }
            }
        },
        native("duplicateEarlyAckTuplesStayBoundedAndTerminalRetirementReleasesEvidence") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            val gate = CompletableDeferred<Unit>()
            h.transport.acknowledge = false
            h.transport.acceptanceGate = gate
            val sending = async { h.service.sendDirectMessage("terminal buffer", h.contact) }
            runCurrent()
            val id = h.store.messages.values.single().id
            repeat(256) { h.transport.pushAck(Bytes.of(1, 2, 3, 4), 123u) }
            runCurrent()
            assertEquals(1, h.service.bufferedAcknowledgementCount(id))
            assertTrue(h.diagnostics.none { it is MessagingDiagnostic.UnmatchedAcknowledgement })
            h.service.failAllPendingMessages()
            assertEquals(0, h.service.bufferedAcknowledgementCount(id))
            assertEquals(MessageStatus.FAILED, h.stored(id).status)
            assertEquals(1, h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
                .count { it.operation.startsWith("ackBuffer.") })
            gate.complete(Unit); runCurrent(); sending.await()
            assertEquals(MessageStatus.FAILED, h.stored(id).status)
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            h.close()
        },
        native("unresolvedAckFloodIsBoundedAndOverflowDoesNotInventDeliveryOrUnmatched") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            val gate = CompletableDeferred<Unit>()
            val actual = Bytes.of(9, 8, 7, 6)
            h.transport.acknowledge = false
            h.transport.expectedAckOverride = actual
            h.transport.acceptanceGate = gate
            val events = h.service.statusEvents()
            val observed = mutableListOf<MessageStatusEvent>()
            val collector = backgroundScope.launch { events.events.collect { observed += it.event } }
            val sending = async { h.service.sendDirectMessage("bounded early ACK", h.contact) }
            runCurrent()
            val id = h.store.messages.values.single().id
            val before = h.stored(id)
            val tracker = assertNotNull(h.service.pendingAck(id))
            repeat(64) {
                h.transport.pushAck(Bytes.of(it, 0, 0, 0), 123u)
                runCurrent()
            }
            assertEquals(64, h.service.bufferedAcknowledgementCount(id))
            assertTrue(h.diagnostics.none { it is MessagingDiagnostic.UnmatchedAcknowledgement })
            h.transport.pushAck(Bytes.of(64, 0, 0, 0), 123u); runCurrent()
            repeat(256) { h.transport.pushAck(Bytes.of(it, 1, 0, 0), 123u) }
            h.transport.pushAck(actual, 123u); runCurrent()
            assertEquals(0, h.service.bufferedAcknowledgementCount(id))
            assertEquals(1, h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
                .count { it.operation.startsWith("ackBuffer.") })
            assertTrue(h.diagnostics.none { it is MessagingDiagnostic.UnmatchedAcknowledgement })
            assertEquals(before, h.stored(id))
            assertFalse(tracker.receipt.isCompleted)
            assertTrue(observed.isEmpty())
            gate.complete(Unit); runCurrent()
            val accepted = sending.await()
            assertEquals(MessageStatus.SENT, accepted.status)
            assertNull(accepted.roundTripTime)
            assertEquals(before.copy(status = MessageStatus.SENT, ackCode = actual.ackCodeUInt32), accepted)
            assertFalse(tracker.receipt.isCompleted)
            h.transport.pushAck(actual, 123u); runCurrent()
            assertEquals(MessageStatus.DELIVERED, h.stored(id).status)
            assertEquals(123u, h.stored(id).roundTripTime)
            assertEquals(accepted.copy(status = MessageStatus.DELIVERED, roundTripTime = 123u), h.stored(id))
            assertEquals(MeshAcknowledgement(actual, 123u), tracker.receipt.await())
            assertEquals(listOf(
                MessageStatusEvent.StatusResolved(id, MessageStatus.SENT, null),
                MessageStatusEvent.StatusResolved(id, MessageStatus.DELIVERED, 123u)), observed)
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            collector.cancelAndJoin(); h.close()
        },
        native("wrongAckDoesNotResolveAndLateFirstAttemptAckStillResolvesSecondAttempt") {
            val h = Harness(this, MessageServiceConfig(maxAttempts = 2, floodAfter = 5)); h.start(); h.service.startEventMonitoring()
            h.transport.suggestedTimeout = 1000u
            val send = backgroundScope.async { h.service.sendMessageWithRetry("late", h.contact) }; runCurrent()
            val m = h.store.messages.values.single(); val first = assertNotNull(h.service.pendingAck(m.id)).ackCodes.first()
            h.transport.pushAck(Bytes.of(1, 2, 3, 4)); runCurrent(); assertFalse(send.isCompleted)
            advanceTimeBy(1201); runCurrent(); assertEquals(2, h.sends(CommandCode.SEND_MESSAGE).size)
            h.transport.pushAck(first, 222u); assertEquals(MessageStatus.DELIVERED, send.await().status)
            assertEquals(222u, h.stored(m.id).roundTripTime); assertEquals(2, h.sends(CommandCode.SEND_MESSAGE).size); h.close()
        },
        native("poolBackoffUsesThreeRetriesWithExactInjectedTimingAndUnchangedAttemptByte") {
            val h = Harness(this); h.start(); h.transport.directError = 3u
            val times = mutableListOf<Long>()
            h.transport.beforeReply = { if (it[0] == CommandCode.SEND_MESSAGE.rawValue) times += testScheduler.currentTime }
            val error = assertFailsWith<MessageServiceException> { h.service.sendDirectMessage("pool", h.contact) }
            assertEquals(3.toUByte(), assertIs<MeshCoreException.DeviceError>(assertIs<MessageServiceError.SessionError>(error.error).underlying).code)
            assertEquals(listOf(0L, 500L, 1500L, 3500L), times)
            assertTrue(h.sends(CommandCode.SEND_MESSAGE).all { it[2] == 0.toUByte() }); h.close()
        },
        native("cancellingSendDuringBackoffStopsWireWorkAndPreservesThePendingRow") {
            val h = Harness(this); h.start(); h.transport.directError = 3u
            val message = h.service.createPendingMessage("cancel pool", h.contact); val queue = h.queue()
            queue.enqueueDM(DirectMessageEnvelope(message.id, CONTACT)); runCurrent()
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size); queue.shutdown()
            advanceTimeBy(10_000); runCurrent(); assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertEquals(MessageStatus.PENDING, h.store.messages.values.single().status)
            assertEquals(message.id, h.store.fetchPendingSends(RADIO).single().messageID); h.close()
        },
        native("unicodeGraphemesCJKRTLAndCombiningTextUseUTF8BytesWithoutNormalization") {
            val h = Harness(this)
            val combining = "e\u0301".repeat(50)
            assertEquals(combining, h.service.createPendingMessage(combining, h.contact).text)
            assertEquals("\u6F22".repeat(50), h.service.createPendingMessage("\u6F22".repeat(50), h.contact).text)
            assertEquals("\u0645\u0631\u062D\u0628\u0627", h.service.createPendingMessage("\u0645\u0631\u062D\u0628\u0627", h.contact).text)
            assertFailsWith<MessageServiceException> { h.service.createPendingMessage("\uD83D\uDC69\u200D\uD83D\uDCBB".repeat(14), h.contact) }
            assertNotEquals(DeduplicationKey.contentBased(CONTACT, null, null, 1u, "\u00E9"),
                DeduplicationKey.contentBased(CONTACT, null, null, 1u, "e\u0301")); h.close()
        },
        native("channelMetadataBudgetCountsTheActualUnicodeNameBytesAndStoreFailureDoesNotBecomeAbsence") {
            val h = Harness(this); h.store.saveDevice(DeviceDTO(radioId = RADIO, publicKey = SELF, nodeName = "\u6F22\u5B57"))
            assertEquals(131, h.service.createPendingChannelMessage("a".repeat(131), 0u, RADIO).text.length)
            assertFailsWith<MessageServiceException> { h.service.createPendingChannelMessage("a".repeat(132), 0u, RADIO) }
            val fault = storageFailure(); h.store.faults["fetchDevice"] = fault
            assertSame(fault, assertFailsWith<PersistenceStoreException> { h.service.createPendingChannelMessage("short", 0u, RADIO) }); h.close()
        },
        native("unsignedTimestampAckBitsAndImmutablePublicKeysKeepSourceIdentity") {
            val bytes = ByteArray(32) { 0xFE.toByte() }; val key = Bytes(bytes); val before = AckCodeBuilder.expectedAck(UInt.MAX_VALUE, 4u, "high", key)
            bytes.fill(0); key.toByteArray().fill(0)
            assertEquals(before, AckCodeBuilder.expectedAck(UInt.MAX_VALUE, 0u, "high", key))
            assertTrue(DeduplicationKey.contentBased(CONTACT, null, null, UInt.MAX_VALUE, "high").contains("-4294967295-"))
            assertEquals(0xDEADBEEFu, Bytes.of(0xEF, 0xBE, 0xAD, 0xDE).ackCodeUInt32)
            assertFailsWith<IllegalArgumentException> { AckCodeBuilder.expectedAck(1u, 5u, "invalid", key) }
            assertFailsWith<IllegalArgumentException> { MessageServiceConfig(maxAttempts = 6) }
        },
        native("statusSubscriptionsMulticastEqualEventsInOrderAndFinishLateSubscribers") {
            val events = MessagingEvents<Int>(token()); val a = events.subscribe(); val b = events.subscribe()
            repeat(500) { events.yield(7) }; events.finish()
            assertEquals(List(500) { 7 }, a.events.toList().map { it.event })
            assertEquals(List(500) { 7 }, b.events.toList().map { it.event })
            assertTrue(events.subscribe().events.toList().isEmpty()); assertEquals(0, events.subscriberCount)
        },
        native("broadcasterTerminalFailureRetainsBufferedEventsAndTheActualCause") {
            val events = MessagingEvents<Int>(token()); val subscription = events.subscribe(); val cause = storageFailure()
            events.yield(1); events.finish(cause); val values = mutableListOf<Int>()
            assertSame(cause, assertFailsWith<PersistenceStoreException> { subscription.events.collect { values += it.event } })
            assertEquals(listOf(1), values)
            assertSame(cause, assertFailsWith<PersistenceStoreException> { events.subscribe().events.toList() })
        },
        native("signalFireWakesEveryWaiterAndCloseNeverLooksLikeAnOpenSignal") {
            val signal = BLETransportOpenedSignal(); val a = async { signal.wait() }; val b = async { signal.wait() }
            runCurrent(); assertEquals(2, signal.waiterCount); signal.fire(); a.await(); b.await()
            signal.finish(); assertFailsWith<CancellationException> { signal.wait() }
        },
        native("queueObserverCancellationDoesNotCancelAnAuthoritativeDrain") {
            val gate = CompletableDeferred<Unit>(); val sent = mutableListOf<Int>()
            val queue = SendQueue<Int>(backgroundScope, { gate.await(); sent += it }, { _, _ -> fail("unexpected") }, {})
            queue.enqueue(1); runCurrent(); val observer = backgroundScope.async { queue.awaitDrainCompletion() }
            runCurrent(); observer.cancelAndJoin(); gate.complete(Unit); runCurrent()
            assertEquals(listOf(1), sent); queue.shutdown()
        },
        native("explicitQueueHaltRequeuesButDoesNotAutoRespawnUntilAnotherEnqueue") {
            val gate = CompletableDeferred<Unit>(); var calls = 0; val sent = mutableListOf<Int>()
            val queue = SendQueue<Int>(backgroundScope, { calls++; gate.await(); sent += it }, { _, _ -> fail("unexpected") }, {})
            queue.enqueue(1); runCurrent(); queue.cancelDrain(); runCurrent(); assertEquals(1, calls); assertEquals(1, queue.count)
            gate.complete(Unit); queue.enqueue(2); runCurrent(); assertEquals(listOf(1, 2), sent); assertEquals(3, calls); queue.shutdown()
        },
        native("persistFailureIsTypedAndNoEnvelopeOrWireWorkIsInvented") {
            val h = Harness(this); h.start(); val m = h.message(MessageStatus.PENDING); val q = h.queue()
            val fault = storageFailure(); h.store.faults["insertPendingSendAssigningSequence"] = fault
            val failure = assertFailsWith<ChatSendQueueServiceException> { q.enqueueDM(DirectMessageEnvelope(m.id, CONTACT)) }
            assertSame(fault, assertIs<ChatSendQueueServiceError.PersistFailed>(failure.error).underlying)
            assertEquals(0, q.queuedEnvelopeCount); assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty()); q.shutdown(); h.close()
        },
        native("hydrationFailureIsVisibleAndRetryDoesNotDuplicateAlreadyEnqueuedRows") {
            val h = Harness(this); val q = h.queue(); h.store.faults["fetchPendingSends"] = storageFailure()
            assertFailsWith<ChatSendQueueServiceException> { q.hydrate() }
            h.store.faults.clear(); h.store.insertPendingSendAssigningSequence(PendingSendDTO.fromEnvelope(
                DirectMessageEnvelope(UUID.randomUUID(), UUID.randomUUID()), RADIO))
            q.hydrate(); runCurrent(); q.awaitDrainCompletion(); assertTrue(h.store.fetchPendingSends(RADIO).isEmpty()); q.shutdown(); h.close()
        },
        native("channelDeletionFailureRetriesOnlyPersistenceNotTheAcceptedBroadcast") {
            val h = Harness(this); h.start(); val m = h.message(MessageStatus.PENDING, 0u); h.pending(m)
            val q = h.queue(); h.signals.set(h.token, DeviceConnectionState.CONNECTED)
            h.store.faults["deletePendingSendsForMessage"] = storageFailure()
            q.hydrate(); runCurrent(); h.signals.set(h.token, DeviceConnectionState.READY); runCurrent()
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size); assertEquals(MessageStatus.SENT, h.stored(m.id).status)
            h.store.faults.clear(); q.transportDidOpen(); runCurrent(); q.awaitDrainCompletion()
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size); assertTrue(h.store.fetchPendingSends(RADIO).isEmpty()); q.shutdown(); h.close()
        },
        native("channelStatusCommitFailureRetriesBookkeepingWithoutASecondBroadcast") {
            val h = Harness(this); h.start(); val m = h.message(MessageStatus.PENDING, 0u); h.pending(m)
            h.store.faults["updateMessageStatus"] = storageFailure(); val q = h.queue()
            h.signals.set(h.token, DeviceConnectionState.CONNECTED); q.hydrate(); runCurrent()
            h.signals.set(h.token, DeviceConnectionState.READY); runCurrent(); assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            h.store.faults.clear(); q.transportDidOpen(); runCurrent(); q.awaitDrainCompletion()
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size); assertEquals(MessageStatus.SENT, h.stored(m.id).status); q.shutdown(); h.close()
        },
        native("resendReactionIndexReceivesTheNewWireTimestampBeforePendingDeletion") {
            val h = Harness(this); h.start(); val m = h.message(MessageStatus.SENT, 0u); h.pending(m, isResend = true)
            val indexed = mutableListOf<UInt>()
            val q = h.queue(indexer = OutgoingChannelReactionIndexer { id, _, _, text, stamp ->
                assertEquals(m.id, id); assertEquals(m.text, text); assertTrue(h.store.hasPendingSend(EntityKey(RADIO, id))); indexed += stamp
            })
            q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertEquals(listOf(h.stored(m.id).timestamp), indexed); assertNotEquals(m.timestamp, indexed.single()); q.shutdown(); h.close()
        },
        native("generationAndRadioSwitchRejectOldAckAndCannotSendAnotherPartition") {
            val h = Harness(this); h.start(); val m = h.message(); val t = h.tracking(m); h.installAcceptedPendingAck(t)
            h.signals.set(token(PEER_RADIO, 2), DeviceConnectionState.READY)
            h.service.handleAcknowledgement(t.ackCodes.first(), 12u); assertEquals(MessageStatus.SENT, h.stored(m.id).status)
            assertFailsWith<MessageServiceException> { h.service.sendDirectMessage("wrong generation", h.contact) }
            assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty()); h.close()
        },
        native("changedTargetPublicKeyCannotReceiveAnOldPendingAcknowledgement") {
            val h = Harness(this); h.start(); val m = h.message(); val t = h.tracking(m).copy(publicKey = TARGET)
            h.installAcceptedPendingAck(t); h.store.contacts[EntityKey(RADIO, CONTACT)] = h.contact.copy(publicKey = SELF)
            assertFailsWith<MessageServiceException> { h.service.handleAcknowledgement(t.ackCodes.first(), 12u) }
            assertEquals(MessageStatus.SENT, h.stored(m.id).status); assertFalse(h.service.close().isComplete); h.session.stop()
        },
        native("identicalCrossMessageAckIdentityIsRejectedBeforeAmbiguousWireWork") {
            val h = Harness(this); h.start(); val a = h.message(); val b = h.message()
            h.service.trackPendingAck(a.id, CONTACT, Bytes.of(1, 2, 3, 4), 30.0)
            assertFailsWith<MessageServiceException> { h.service.trackPendingAck(b.id, CONTACT, Bytes.of(1, 2, 3, 4), 30.0) }
            assertEquals(1, h.service.pendingAckCount); assertTrue(h.diagnostics.any { it is MessagingDiagnostic.AckCodeCollision }); h.close()
        },
        native("teardownDoesNotOwnTheTransportAndIsSingleFlight") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring(); h.service.startAckExpiryChecking()
            val a = async { h.service.close() }; val b = async { h.service.close() }
            assertEquals(a.await(), b.await()); assertEquals(0, h.transport.disconnectCalls)
            assertTrue(h.transport.isConnected()); assertFalse(h.service.isAckExpiryCheckingActive)
            assertTrue(h.service.statusEvents().events.toList().isEmpty()); h.session.stop(); assertEquals(1, h.transport.disconnectCalls)
        },
        native("retainedTransportCannotReopenTheStoppedProtocolGeneration") {
            val h = Harness(this); h.start(); h.service.close(); h.session.stop(disconnectTransport = false)
            assertTrue(h.transport.isConnected()); assertFailsWith<MeshCoreException.ConnectionLost> { h.session.start() }
            h.transport.disconnect()
        },
        native("failedDeliveredPersistenceDoesNotEmitFakeDeliveryAndSurvivesInTeardownReport") {
            val h = Harness(this); h.start(); val m = h.message(); val t = h.tracking(m); val events = h.service.statusEvents()
            val fault = storageFailure(); h.store.faults["updateMessageAck"] = fault; h.installAcceptedPendingAck(t)
            assertSame(fault, assertFailsWith<PersistenceStoreException> { h.service.handleAcknowledgement(t.ackCodes.first(), 5u) })
            assertEquals(MessageStatus.SENT, h.stored(m.id).status)
            assertTrue(statuses(h.service, events).isEmpty()); val report = h.service.close()
            assertFalse(report.isComplete); assertTrue(report.issues.any { it.cause === fault }); h.session.stop()
        },
        native("recursiveQueueFailurePreservesTheOriginalSessionAndPersistenceMetadata") {
            val leaf = MeshCoreException.InvalidInput("source reason")
            val persistence = PersistenceStoreException(PersistenceStoreError.SaveFailed("store reason"), leaf)
            val queue = ChatSendQueueServiceException(ChatSendQueueServiceError.PersistFailed(persistence))
            assertSame(persistence, queue.cause); assertSame(leaf, queue.cause?.cause)
            assertEquals("store reason", assertIs<PersistenceStoreError.SaveFailed>(persistence.error).reason)
            assertEquals("source reason", assertIs<MeshCoreException.InvalidInput>(queue.cause?.cause).reason)
        },
        native("coalescedPollingObserversCancelIndependentlyAndBacklogHasOneFreshAnchor") {
            val h = Harness(this); h.start(); val poller = poller(h); val gate = CompletableDeferred<Unit>()
            val contexts = mutableListOf<DeliveryContext>(); var calls = 0
            poller.setContactMessageHandler { _, contact, context -> assertEquals(CONTACT, contact?.id); calls++; contexts += context; gate.await() }
            h.transport.incomingMessages += contactPacket("one"); h.transport.incomingMessages += contactPacket("two")
            poller.startMessageEventMonitoring()
            val a = backgroundScope.async { poller.pollAllMessages() }; val b = backgroundScope.async { poller.pollAllMessages() }
            runCurrent(); assertEquals(1, calls); a.cancelAndJoin(); gate.complete(Unit)
            assertEquals(2L, b.await()); assertEquals(2, calls)
            assertEquals(1, contexts.map { assertIs<DeliveryContext.InitialSync>(it).anchor }.toSet().size)
            assertEquals(3, h.sends(CommandCode.GET_MESSAGE).size); assertTrue(poller.waitForPendingHandlers(Duration.ZERO)); poller.close(); h.close()
        },
        native("pollingRoutesCLIAndSignedRawTypesWithoutClampingTheirPayloads") {
            val h = Harness(this); h.start(); val p = poller(h); val routes = mutableListOf<String>()
            p.setCLIMessageHandler { m, c -> assertEquals(CONTACT, c?.id); assertEquals(1.toUByte(), m.textType); routes += m.text }
            p.setSignedMessageHandler { m, _ -> assertEquals(Bytes.of(0x80, 0xFF, 1, 2), m.signature); routes += m.text }
            p.setContactMessageHandler { m, _, _ -> assertEquals(255.toUByte(), m.textType); routes += m.text }
            h.transport.incomingMessages += contactPacket("cli", 1u)
            h.transport.incomingMessages += contactPacket("signed", 2u)
            h.transport.incomingMessages += contactPacket("unknown", 255u)
            assertEquals(3L, p.pollAllMessages()); assertEquals(listOf("cli", "signed", "unknown"), routes); p.close(); h.close()
        },
        native("lookupFailureRetainsTheConsumedMessageAndNeverBecomesAnUnknownContact") {
            val h = Harness(this); h.start(); val p = poller(h); var calls = 0
            p.setContactMessageHandler { _, _, _ -> calls++ }; h.store.faults["fetchContactByPrefix"] = storageFailure()
            h.transport.incomingMessages += contactPacket("retained")
            assertFailsWith<PersistenceStoreException> { p.pollAllMessages() }; assertEquals(0, calls); assertEquals(1, p.undeliveredCount)
            h.store.faults.clear(); assertEquals(0L, p.pollAllMessages()); assertEquals(1, calls); assertEquals(0, p.undeliveredCount)
            p.close(); h.close()
        },
        native("delayedPollingLookupCannotCallAConsumerInTheSuccessorGeneration") {
            val h = Harness(this); h.start(); val p = poller(h); val gate = CompletableDeferred<Unit>(); var calls = 0
            p.setContactMessageHandler { _, _, _ -> calls++ }
            h.store.before = { if (it == "fetchContactByPrefix") gate.await() }
            h.transport.incomingMessages += contactPacket("obsolete")
            val polling = backgroundScope.async {
                try { p.pollAllMessages(); null }
                catch (failure: MessagePollingException) { failure }
            }
            runCurrent()
            h.signals.set(token(generation = 2), DeviceConnectionState.READY); gate.complete(Unit)
            assertEquals(MessagePollingError.NotConnected, assertNotNull(polling.await()).error); assertEquals(0, calls)
            assertFalse(p.close().isComplete); h.close()
        },
        native("pendingHandlerWaitUsesItsRealDeadlineAndCancellationDoesNotCancelDelivery") {
            val h = Harness(this); h.start(); val p = poller(h); val gate = CompletableDeferred<Unit>()
            p.setContactMessageHandler { _, _, _ -> gate.await() }; h.transport.incomingMessages += contactPacket("waiting")
            val polling = backgroundScope.async { p.pollAllMessages() }; runCurrent()
            val wait = backgroundScope.async { p.waitForPendingHandlers(Duration.ofMillis(30)) }
            advanceTimeBy(31); runCurrent(); assertFalse(wait.await()); assertFalse(polling.isCompleted)
            gate.complete(Unit); assertEquals(1L, polling.await()); p.close(); h.close()
        },
        native("clearedHandlersDoNotProduceSuccessAndTeardownRetainsThePendingCause") {
            val h = Harness(this); h.start(); val p = poller(h)
            p.setContactMessageHandler { _, _, _ -> fail("cleared callback") }; p.clearMessageHandlers()
            h.transport.incomingMessages += contactPacket("unhandled")
            assertFailsWith<MessagePollingException> { p.pollAllMessages() }; assertEquals(1, p.undeliveredCount)
            assertFalse(p.close().isComplete); assertFalse(p.hasMessageHandlersWired); h.close()
        },
        native("channelFormatPreservesFirstColonEmptyBodyAndWhitespaceNotNewlines") {
            assertNull(ChannelMessageFormat.parse("no separator"))
            assertNull(ChannelMessageFormat.parse(": body"))
            assertEquals(ChannelMessageFormat.Parsed("Alice", ""), ChannelMessageFormat.parse("Alice:"))
            assertEquals(ChannelMessageFormat.Parsed("Alice", "one: two"), ChannelMessageFormat.parse(" Alice : one: two "))
            assertEquals(ChannelMessageFormat.Parsed("Alice", "\nbody\n"), ChannelMessageFormat.parse("\tAlice\u3000: \nbody\n "))
        },
        native("rawChannelV3KeepsSignedSNRHighTimestampAndGenuineAbsentMetadata") {
            val h = Harness(this); h.start(); val p = poller(h); var received = 0
            p.setChannelMessageHandler { message, channel, context ->
                received++
                assertNull(channel); assertEquals(-2.5, message.snr)
                assertEquals(UInt.MAX_VALUE.toLong(), message.senderTimestamp.epochSecond)
                assertEquals("Alice: \u6F22\u5B57", message.text)
                assertIs<DeliveryContext.InitialSync>(context)
            }
            h.transport.incomingMessages += ByteWriter().appendUInt8(ResponseCode.CHANNEL_MESSAGE_RECEIVED_V3.rawValue)
                .appendInt8(-10).appendUInt16LE(0u).appendUInt8(3u).appendUInt8(0u).appendUInt8(0u)
                .appendUInt32LE(UInt.MAX_VALUE).append(Bytes.utf8("Alice: \u6F22\u5B57")).toBytes()
            assertEquals(1L, p.pollAllMessages()); assertEquals(1, received); p.close(); h.close()
        },
        native("rawDatagramsDrainWithoutBecomingVisibleMessageCountOrHandlerCalls") {
            val h = Harness(this); h.start(); val p = poller(h); var visible = 0
            p.setContactMessageHandler { _, _, _ -> visible++ }
            p.setChannelMessageHandler { _, _, _ -> visible++ }
            h.transport.incomingMessages += ByteWriter().appendUInt8(ResponseCode.CHANNEL_DATA_RECEIVED.rawValue)
                .appendInt8(-4).appendUInt16LE(0u).appendUInt8(0u).appendUInt8(0u)
                .appendUInt16LE(0xFF80u).appendUInt8(2u).append(Bytes.of(0x80, 0xFF)).toBytes()
            assertEquals(0L, p.pollAllMessages()); assertEquals(0, visible)
            assertEquals(2, h.sends(CommandCode.GET_MESSAGE).size); p.close(); h.close()
        },
        native("ackSetKeepsWholeImmutableByteValuesAndBlockedContactMetadataStaysRadioScoped") {
            val h = Harness(this); h.start(); val message = h.message()
            val first = Bytes.of(0x80, 0xFF, 1, 2); val second = Bytes.of(0xFF, 0x80, 3, 4)
            h.service.trackPendingAck(message.id, CONTACT, first, 30.0)
            h.service.trackPendingAck(message.id, CONTACT, second, 30.0)
            assertEquals(setOf(first, second), assertNotNull(h.service.pendingAck(message.id)).ackCodes)
            val p = poller(h); var delivered = 0
            h.store.contacts[EntityKey(RADIO, CONTACT)] = h.contact.copy(isBlocked = true)
            h.store.contacts[EntityKey(PEER_RADIO, CONTACT)] = h.contact.copy(radioId = PEER_RADIO, isBlocked = false)
            p.setContactMessageHandler { wire, contact, _ ->
                delivered++
                assertEquals(TARGET.prefix(6), wire.senderPublicKeyPrefix)
                assertEquals(RADIO, contact?.radioId)
                assertEquals(TARGET, contact?.publicKey)
                assertTrue(assertNotNull(contact).isBlocked)
            }
            h.transport.incomingMessages += contactPacket("blocked sender metadata")
            assertEquals(1L, p.pollAllMessages()); assertEquals(1, delivered)
            assertFalse(assertNotNull(h.store.contacts[EntityKey(PEER_RADIO, CONTACT)]).isBlocked)
            p.close(); h.close()
        },
    )
    private suspend fun TestScope.earlyAckCompletesOnce(actualOverride: Bytes?) {
        val h = Harness(this); h.start(); h.service.startEventMonitoring()
        val release = CompletableDeferred<Unit>()
        h.transport.acknowledge = true
        h.transport.ackBeforeAcceptance = true
        h.transport.expectedAckOverride = actualOverride
        h.transport.acceptanceGate = release
        val events = h.service.statusEvents()
        val sending = backgroundScope.async {
            h.service.sendMessageWithRetry(if (actualOverride == null) "fast" else "different firmware code", h.contact)
        }
        try {
            runCurrent()
            val before = h.store.messages.values.single()
            val tracker = assertNotNull(h.service.pendingAck(before.id))
            val actual = actualOverride ?: tracker.ackCodes.single()
            assertEquals(MessageStatus.PENDING, before.status)
            assertEquals(1, h.service.bufferedAcknowledgementCount(before.id))
            assertFalse(tracker.receipt.isCompleted)
            assertNull(tracker.acknowledgement)
            assertEquals(DirectAckRadioWireOutcome.MayHaveWritten,
                assertIs<DirectAckRadioEvidenceObservation.Found>(
                    h.store.directAckAuthority.inspectDirectAckRadioEvidence(RADIO)).snapshot.wires.single().outcome)
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            release.complete(Unit); runCurrent()
            val delivered = sending.await()
            assertEquals(before.copy(status = MessageStatus.DELIVERED,
                ackCode = actual.ackCodeUInt32, roundTripTime = 123u), delivered)
            assertEquals(MessageStatus.DELIVERED, delivered.status)
            assertEquals(123u, delivered.roundTripTime)
            assertEquals(MeshAcknowledgement(actual, 123u), tracker.receipt.await())
            assertEquals(0, h.service.pendingAckCount)
            assertEquals(0, h.service.bufferedAcknowledgementCount(before.id))
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertTrue(h.diagnostics.none { it is MessagingDiagnostic.UnmatchedAcknowledgement })
            if (actualOverride != null) {
                assertEquals(0x06070809u, delivered.ackCode)
                assertTrue(h.diagnostics.any { it is MessagingDiagnostic.AckCodeMismatch })
            }
            assertEquals(listOf(MessageStatusEvent.StatusResolved(before.id, MessageStatus.DELIVERED, 123u)),
                statuses(h.service, events))
        } finally {
            withContext(NonCancellable) {
                release.complete(Unit); sending.cancelAndJoin(); h.close()
            }
        }
    }

    private suspend fun TestScope.legacyUnknownBlocksModernDelivery(acceptedCode: UInt?) {
        val h = Harness(this); h.start(); h.service.startEventMonitoring()
        h.transport.expectedAckOverride = Bytes.of(5, 6, 7, 8)
        h.service.sendDirectMessage("initialize modern grant", h.contact)
        val historical = h.message(MessageStatus.SENT, text = "represented historical wire")
        val old = h.store.directAckAuthority.seedLegacyFixture(EntityKey(RADIO, historical.id),
            0x44332211u, acceptedCode)
        val before = assertIs<DirectAckRadioEvidenceObservation.Found>(
            h.store.directAckAuthority.inspectDirectAckRadioEvidence(RADIO)).snapshot.wires
            .single { it.emissionRequestId == null }
        val rejected = assertIs<DirectAckEmissionMarkResult.Rejected>(
            h.store.directAckAuthority.markDirectAckWireMayHaveWritten(old, before.wireRequestId, UUID.randomUUID()))
        assertEquals(DirectAckRejection.NOT_ELIGIBLE, rejected.reason)
        val actual = Bytes.of(9, 8, 7, 6)
        h.transport.expectedAckOverride = actual
        val later = h.service.sendDirectMessage("modern cannot erase historical ambiguity", h.contact)
        val tracker = assertNotNull(h.service.pendingAck(later.id))
        val events = h.service.statusEvents()
        h.transport.pushAck(actual, 999u); runCurrent()
        assertEquals(later, h.stored(later.id))
        assertEquals(historical, h.stored(historical.id))
        assertFalse(tracker.receipt.isCompleted)
        assertNull(assertNotNull(h.service.pendingAck(later.id)).acknowledgement)
        assertTrue(statuses(h.service, events).isEmpty())
        assertEquals(before, assertIs<DirectAckRadioEvidenceObservation.Found>(
            h.store.directAckAuthority.inspectDirectAckRadioEvidence(RADIO)).snapshot.wires
            .single { it.emissionRequestId == null })
        if (acceptedCode == null) assertEquals(DirectAckRadioWireOutcome.LegacyUnknown, before.outcome)
        else assertEquals(DirectAckRadioWireOutcome.LegacyAcceptedUnknown(acceptedCode), before.outcome)
        assertEquals(2, h.sends(CommandCode.SEND_MESSAGE).size)
        h.close()
    }

    private suspend fun TestScope.deliveryReceiptWaitsForAuthority(listener: Boolean) {
        val h = Harness(this, MessageServiceConfig(maxAttempts = 1))
        h.start()
        if (listener) h.service.startEventMonitoring()
        val sending = backgroundScope.async { h.service.sendMessageWithRetry("authority before receipt", h.contact) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            runCurrent()
            val before = h.store.messages.values.single()
            val tracker = assertNotNull(h.service.pendingAck(before.id))
            val actual = tracker.ackCodes.single()
            val events = h.service.statusEvents()
            val observed = mutableListOf<MessageStatusEvent>()
            val collector = backgroundScope.launch { events.events.collect { observed += it.event } }
            h.store.before = { if (it == "updateMessageAck") { entered.complete(Unit); release.await() } }
            h.transport.pushAck(actual, 321u); runCurrent(); entered.await()
            assertEquals(before, h.stored(before.id))
            assertFalse(tracker.receipt.isCompleted)
            assertFalse(sending.isCompleted)
            assertTrue(observed.isEmpty())
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            release.complete(Unit); runCurrent()
            val delivered = sending.await()
            assertEquals(before.copy(status = MessageStatus.DELIVERED,
                ackCode = actual.ackCodeUInt32, roundTripTime = 321u), delivered)
            assertEquals(MeshAcknowledgement(actual, 321u), tracker.receipt.await())
            assertEquals(listOf(MessageStatusEvent.StatusResolved(before.id, MessageStatus.DELIVERED, 321u)), observed)
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            collector.cancelAndJoin()
        } finally {
            withContext(NonCancellable) {
                release.complete(Unit); h.store.before = null; sending.cancelAndJoin(); h.close()
            }
        }
    }

    private suspend fun TestScope.cancelledWirePredecessor(definitelyUnsent: Boolean) {
        val h = Harness(this); h.start(); h.service.startEventMonitoring()
        val release = CompletableDeferred<Unit>()
        var blockingCommand: Deferred<Unit>? = null
        val receipt = NotificationActionReceipt()
        var cancelledSend: Deferred<MessageDTO>? = null
        try {
            h.transport.acknowledge = false
            if (definitelyUnsent) {
                h.transport.beforeReply = {
                    if (it[0] == CommandCode.RESET_PATH.rawValue) release.await()
                }
                blockingCommand = backgroundScope.async { h.session.resetPath(TARGET) }
                runCurrent()
                assertEquals(1, h.sends(CommandCode.RESET_PATH).size)
            } else h.transport.holdMessageReplies = true
            val call = backgroundScope.async(receipt) { h.service.sendDirectMessage("cancelled predecessor", h.contact) }
            cancelledSend = call
            runCurrent()
            assertEquals(NotificationActionReceipt.State.IN_FLIGHT, receipt.state)
            assertEquals(if (definitelyUnsent) 0 else 1, h.sends(CommandCode.SEND_MESSAGE).size)
            call.cancelAndJoin()
            assertFailsWith<CancellationException> { call.await() }
            assertEquals(if (definitelyUnsent) NotificationActionReceipt.State.READY
                else NotificationActionReceipt.State.UNCERTAIN, receipt.state)
            val predecessor = h.store.messages.values.single()
            if (definitelyUnsent) {
                release.complete(Unit); blockingCommand?.await(); runCurrent()
                assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())
            } else {
                h.transport.holdMessageReplies = false
                h.transport.mock.simulateReceive(ByteWriter().appendUInt8(ResponseCode.MESSAGE_SENT.rawValue)
                    .appendUInt8(0u).append(Bytes.of(1, 2, 3, 4)).appendUInt32LE(10u).toBytes())
                runCurrent()
            }
            h.transport.beforeReply = null
            val actual = Bytes.of(9, 8, 7, 6)
            h.transport.expectedAckOverride = actual
            val successor = h.service.sendDirectMessage("distinct successor", h.contact)
            val tracker = assertNotNull(h.service.pendingAck(successor.id))
            val events = h.service.statusEvents()
            h.transport.pushAck(actual, 321u); runCurrent()
            assertEquals(if (definitelyUnsent) successor.copy(status = MessageStatus.DELIVERED, roundTripTime = 321u)
                else successor, h.stored(successor.id))
            assertEquals(predecessor, h.stored(predecessor.id))
            assertEquals(if (definitelyUnsent) 1 else 2, h.sends(CommandCode.SEND_MESSAGE).size)
            assertEquals(definitelyUnsent, tracker.receipt.isCompleted)
            if (definitelyUnsent) {
                assertEquals(MeshAcknowledgement(actual, 321u), tracker.receipt.await())
                assertNull(h.service.pendingAck(successor.id))
                assertEquals(listOf(MessageStatusEvent.StatusResolved(successor.id, MessageStatus.DELIVERED, 321u)),
                    statuses(h.service, events))
            } else {
                assertNull(tracker.acknowledgement)
                assertTrue(statuses(h.service, events).isEmpty())
            }
            assertEquals(!definitelyUnsent, h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
                it.operation.startsWith("ackAmbiguous.")
            })
        } finally {
            withContext(NonCancellable) {
                release.complete(Unit); cancelledSend?.cancelAndJoin(); blockingCommand?.cancelAndJoin(); h.close()
            }
        }
    }

    private suspend fun TestScope.oldActualCodeCannotDeliverNewerClaim(listener: Boolean) {
        val h = Harness(this, MessageServiceConfig(maxAttempts = 1))
        h.start()
        if (listener) h.service.startEventMonitoring()
        h.transport.acknowledge = false
        val actual = Bytes.of(9, 8, 7, 6)
        h.transport.expectedAckOverride = actual
        val first = h.service.sendDirectMessage("older same actual", h.contact)
        val events = h.service.statusEvents()
        val sending = async { h.service.resendDirectMessage(first.id, h.contact, preserveTimestamp = true) }
        try {
            runCurrent()
            val before = h.stored(first.id)
            h.transport.pushAck(actual, 999u); runCurrent()
            assertEquals(before, h.stored(first.id))
            assertFalse(sending.isCompleted)
            assertEquals(2, h.sends(CommandCode.SEND_MESSAGE).size)
            assertNull(assertNotNull(h.service.pendingAck(first.id)).acknowledgement)
            assertFalse(assertNotNull(h.service.pendingAck(first.id)).receipt.isCompleted)
            assertTrue(h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
                it.operation.startsWith("ackAmbiguous.")
            })
            assertTrue(statuses(h.service, events).isEmpty())
        } finally {
            withContext(NonCancellable) { sending.cancelAndJoin(); h.close() }
        }
    }

    private suspend fun TestScope.lateDifferentActualAckCompletes(queued: Boolean, listener: Boolean) {
        val h = Harness(this, MessageServiceConfig(maxAttempts = 1))
        h.start()
        if (listener) h.service.startEventMonitoring()
        val actual = Bytes.of(9, 8, 7, 6)
        val gate = CompletableDeferred<Unit>()
        h.transport.acknowledge = false
        h.transport.expectedAckOverride = actual
        h.transport.acceptanceGate = gate
        val queue = if (queued) h.queue() else null
        val events = h.service.statusEvents()
        var sending: Deferred<MessageDTO>? = null
        try {
            if (queue != null) {
                val message = h.service.createPendingMessage("late mismatched actual", h.contact)
                queue.enqueueDM(DirectMessageEnvelope(message.id, CONTACT))
            } else sending = async { h.service.sendMessageWithRetry("late mismatched actual", h.contact) }
            runCurrent()
            val row = h.store.messages.values.single()
            val initial = assertNotNull(h.service.pendingAck(row.id))
            assertFalse(actual in initial.ackCodes)
            gate.complete(Unit); runCurrent()
            val accepted = assertNotNull(h.service.pendingAck(row.id))
            assertSame(initial.receipt, accepted.receipt)
            assertTrue(actual in accepted.ackCodes)
            assertFalse(initial.receipt.isCompleted)
            h.transport.pushAck(initial.ackCodes.single(), 999u); runCurrent()
            assertFalse(initial.receipt.isCompleted)
            assertFalse(sending?.isCompleted ?: false)
            assertNotEquals(MessageStatus.DELIVERED, h.stored(row.id).status)
            assertNull(h.stored(row.id).roundTripTime)
            h.transport.pushAck(actual, 321u); runCurrent()
            if (queue != null) queue.awaitDrainCompletion()
            else assertEquals(h.stored(row.id), assertNotNull(sending).await())
            assertTrue(initial.receipt.isCompleted)
            assertEquals(actual, initial.receipt.await().code)
            assertEquals(MessageStatus.DELIVERED, h.stored(row.id).status)
            assertEquals(0x06070809u, h.stored(row.id).ackCode)
            assertEquals(321u, h.stored(row.id).roundTripTime)
            assertEquals(0, h.service.pendingAckCount)
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertTrue(h.store.fetchPendingSends(RADIO).isEmpty())
            assertEquals(listOf(MessageStatusEvent.StatusResolved(row.id, MessageStatus.DELIVERED, 321u)),
                statuses(h.service, events))
        } finally {
            withContext(NonCancellable) {
                gate.complete(Unit)
                sending?.cancelAndJoin()
                h.close()
            }
        }
    }

    private fun poller(h: Harness) = h.poller()
    private fun contactPacket(text: String, type: UByte = 0u): Bytes {
        val writer = ByteWriter().appendUInt8(ResponseCode.CONTACT_MESSAGE_RECEIVED.rawValue).append(TARGET.prefix(6))
            .appendUInt8(0u).appendUInt8(type).appendUInt32LE(UInt.MAX_VALUE)
        if (type == 2.toUByte()) writer.append(Bytes.of(0x80, 0xFF, 1, 2))
        return writer.append(Bytes.utf8(text)).toBytes()
    }
}
