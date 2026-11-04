// AndroidOnly: WP-208 Deterministic real-service/session commit and acceptance cancellation boundaries.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import com.meshcoreone.android.core.protocol.session.MessageWriteDispatch
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.session.MeshCoreSessionProtocol
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class CommitRecoveryTest {
    @Test fun committedCreationReceiptRetainsTheExactReturnedRow() {
        val proposed = MessageDTO(
            id = UUID.randomUUID(), radioId = RADIO, channelIndex = 0u, text = "original",
            timestamp = 1u, createdAt = Instant.EPOCH, textType = TextType.PLAIN,
        )
        val persisted = proposed.copy(timestamp = 2u, status = MessageStatus.SENT)
        val receipt = PendingMessageCreation()

        receipt.writing(proposed)

        assertTrue(receipt.committed(persisted))
        assertEquals(PendingMessageCreation.Snapshot(PendingMessageCreation.State.COMMITTED, persisted), receipt.snapshot)
    }

    @Test fun committedCreationReceiptDoesNotAcceptAnotherRowIdentity() {
        val proposed = MessageDTO(
            id = UUID.randomUUID(), radioId = RADIO, channelIndex = 0u, text = "original",
            timestamp = 1u, createdAt = Instant.EPOCH, textType = TextType.PLAIN,
        )
        val receipt = PendingMessageCreation()

        receipt.writing(proposed)

        assertFalse(receipt.committed(proposed.copy(id = UUID.randomUUID())))
        assertEquals(PendingMessageCreation.State.UNKNOWN, receipt.snapshot.state)
        assertEquals(proposed, receipt.snapshot.message)
    }

    private enum class Destination(val wire: CommandCode) {
        DIRECT(CommandCode.SEND_MESSAGE), CHANNEL(CommandCode.SEND_CHANNEL_MESSAGE);

        suspend fun create(service: MessageService): MessageDTO = when (this) {
            DIRECT -> service.createPendingMessage("original", ContactDTO(CONTACT, RADIO, TARGET, "Peer", lastHeardTimestamp = 0u))
            CHANNEL -> service.createPendingChannelMessage("original", 0u, RADIO)
        }

        suspend fun send(service: MessageService) {
            when (this) {
                DIRECT -> service.sendDirectMessage("reply", ContactDTO(CONTACT, RADIO, TARGET, "Peer", lastHeardTimestamp = 0u))
                CHANNEL -> service.sendChannelMessage("reply", 0u, RADIO)
            }
        }
    }

    @TestFactory fun pendingCreationBoundaries() = Destination.entries.flatMap { destination ->
        listOf(
            native("${destination}_cancelWhileQueuedForPendingOperationsLeavesReceiptNotStarted") {
                val h = Harness(this); h.start()
                val firstSaveEntered = CompletableDeferred<Unit>()
                val releaseFirstSave = CompletableDeferred<Unit>()
                val store = object : PersistenceStoreProtocol by h.store {
                    override suspend fun saveMessage(dto: MessageDTO) {
                        firstSaveEntered.complete(Unit)
                        releaseFirstSave.await()
                        h.store.saveMessage(dto)
                    }
                }
                withService(h, store) { service ->
                    val first = backgroundScope.async { destination.create(service) }
                    firstSaveEntered.await()
                    val receipt = PendingMessageCreation()
                    val queuedCallStarted = CompletableDeferred<Unit>()
                    val queued = backgroundScope.async(receipt) {
                        queuedCallStarted.complete(Unit)
                        destination.create(service)
                    }
                    queuedCallStarted.await()
                    runCurrent()
                    assertEquals(PendingMessageCreation.State.NOT_STARTED, receipt.snapshot.state)
                    assertNull(receipt.snapshot.message)
                    assertTrue(h.store.messages.isEmpty())
                    queued.cancelAndJoin()
                    assertEquals(PendingMessageCreation.State.NOT_STARTED, receipt.snapshot.state)
                    assertNull(receipt.snapshot.message)
                    assertTrue(h.store.messages.isEmpty())
                    assertTrue(h.sends(destination.wire).isEmpty())
                    releaseFirstSave.complete(Unit)
                    first.await()
                    assertEquals(1, h.store.messages.size)
                }
            },
            native("${destination}_cancelBeforeWriteWithoutOwnerBarrierKeepsIdentityUnknown") {
                val h = Harness(this); h.start()
                val entered = CompletableDeferred<Unit>()
                val store = object : PersistenceStoreProtocol by h.store {
                    override suspend fun saveMessage(dto: MessageDTO) { entered.complete(Unit); awaitCancellation() }
                }
                withService(h, store) { service ->
                    val receipt = PendingMessageCreation()
                    val call = backgroundScope.async(receipt) { destination.create(service) }
                    entered.await()
                    assertEquals(PendingMessageCreation.State.WRITING, receipt.snapshot.state)
                    call.cancelAndJoin()
                    assertEquals(PendingMessageCreation.State.UNKNOWN, receipt.snapshot.state)
                    assertEquals(RADIO, assertNotNull(receipt.snapshot.message).radioId)
                    assertTrue(h.store.messages.isEmpty()); assertTrue(h.sends(destination.wire).isEmpty())
                }
            },
            native("${destination}_commitBeforeCanceledReturnRetainsExactRowIdentity") {
                val h = Harness(this); h.start()
                val committed = CompletableDeferred<Unit>()
                val store = object : PersistenceStoreProtocol by h.store {
                    override suspend fun saveMessage(dto: MessageDTO) {
                        h.store.saveMessage(dto); committed.complete(Unit); awaitCancellation()
                    }
                }
                withService(h, store) { service ->
                    val receipt = PendingMessageCreation()
                    val call = backgroundScope.async(receipt) { destination.create(service) }
                    committed.await(); call.cancelAndJoin()
                    assertEquals(PendingMessageCreation.State.COMMITTED, receipt.snapshot.state)
                    val message = assertNotNull(receipt.snapshot.message)
                    assertEquals(message, h.stored(message.id))
                    assertEquals("original", message.text); assertEquals(1, h.store.messages.size)
                    assertTrue(h.sends(destination.wire).isEmpty()); assertTrue(h.store.pending.isEmpty())
                }
            },
            native("${destination}_committedWriteMayThrowWithoutLosingItsIdentity") {
                val h = Harness(this); h.start()
                val failure = storageFailure()
                val store = object : PersistenceStoreProtocol by h.store {
                    override suspend fun saveMessage(dto: MessageDTO) { h.store.saveMessage(dto); throw failure }
                }
                withService(h, store) { service ->
                    val receipt = PendingMessageCreation()
                    assertSame(failure, assertFailsWith<PersistenceStoreException> {
                        withContext(receipt) { destination.create(service) }
                    })
                    assertEquals(PendingMessageCreation.State.COMMITTED, receipt.snapshot.state)
                    assertEquals(receipt.snapshot.message, h.store.messages.values.single())
                    assertTrue(h.sends(destination.wire).isEmpty())
                }
            },
            native("${destination}_writeFailureAndFailedReadExposeUnknownNotRollback") {
                val h = Harness(this); h.start()
                val failure = storageFailure()
                val readFailure = storageFailure()
                val store = object : PersistenceStoreProtocol by h.store {
                    override suspend fun saveMessage(dto: MessageDTO) { h.store.saveMessage(dto); throw failure }
                    override suspend fun fetchMessage(key: EntityKey): MessageDTO? = throw readFailure
                }
                withService(h, store) { service ->
                    val receipt = PendingMessageCreation()
                    assertSame(failure, assertFailsWith<PersistenceStoreException> {
                        withContext(receipt) { destination.create(service) }
                    })
                    assertEquals(PendingMessageCreation.State.UNKNOWN, receipt.snapshot.state)
                    assertEquals(receipt.snapshot.message, h.store.messages.values.single())
                    assertTrue(readFailure in failure.suppressed)
                    assertTrue(h.diagnostics.any { it is MessagingDiagnostic.Failure && it.cause === readFailure })
                }
            },
            native("${destination}_replacementAfterCommitFencesFollowupButPreservesCommit") {
                val h = Harness(this); h.start()
                val store = object : PersistenceStoreProtocol by h.store {
                    override suspend fun saveMessage(dto: MessageDTO) {
                        h.store.saveMessage(dto)
                        h.signals.set(token(generation = 2), DeviceConnectionState.READY)
                    }
                }
                withService(h, store) { service ->
                    val receipt = PendingMessageCreation()
                    assertFailsWith<MessageServiceException> { withContext(receipt) { destination.create(service) } }
                    assertEquals(PendingMessageCreation.State.COMMITTED, receipt.snapshot.state)
                    assertEquals(receipt.snapshot.message, h.store.messages.values.single())
                    assertFalse("updateContactLastMessage" in h.store.calls)
                    assertTrue(h.sends(destination.wire).isEmpty())
                }
            },
            native("${destination}_teardownJoinsCommittedCreationReconciliation") {
                val h = Harness(this); h.start()
                val committed = CompletableDeferred<Unit>()
                val store = object : PersistenceStoreProtocol by h.store {
                    override suspend fun saveMessage(dto: MessageDTO) {
                        h.store.saveMessage(dto); committed.complete(Unit); awaitCancellation()
                    }
                }
                withService(h, store) { service ->
                    val receipt = PendingMessageCreation()
                    val call = backgroundScope.async(receipt) { destination.create(service) }
                    committed.await(); service.close()
                    assertFailsWith<CancellationException> { call.await() }
                    assertEquals(PendingMessageCreation.State.COMMITTED, receipt.snapshot.state)
                    assertEquals(receipt.snapshot.message, h.store.messages.values.single())
                    assertFalse("updateContactLastMessage" in h.store.calls)
                    assertTrue(h.sends(destination.wire).isEmpty())
                }
            },
            native("${destination}_receiptCannotCreateAnotherRow") {
                val h = Harness(this); h.start()
                val receipt = PendingMessageCreation()
                val first = withContext(receipt) { destination.create(h.service) }
                assertFailsWith<IllegalStateException> { withContext(receipt) { destination.create(h.service) } }
                assertEquals(first, receipt.snapshot.message); assertEquals(1, h.store.messages.size)
                assertEquals(PendingMessageCreation.State.COMMITTED, receipt.snapshot.state)
            },
            native("${destination}_failedWriteWithAbsentReadbackIsUnknownWithoutOwnerBarrier") {
                val h = Harness(this); h.start()
                val failure = storageFailure()
                h.store.faults["saveMessage"] = failure
                val receipt = PendingMessageCreation()
                assertSame(failure, assertFailsWith<PersistenceStoreException> {
                    withContext(receipt) { destination.create(h.service) }
                })
                assertEquals(PendingMessageCreation.State.UNKNOWN, receipt.snapshot.state)
                assertNotNull(receipt.snapshot.message); assertTrue(h.store.messages.isEmpty())
                assertTrue(h.sends(destination.wire).isEmpty())
            },
        )
    }

    @TestFactory fun inlineAcceptanceBoundaries() = Destination.entries.flatMap { destination ->
        listOf(
            native("${destination}_cancelBehindActualSerializerProvesUnsentAndAllowsOneRetry") {
                val h = Harness(this); h.start()
                val holding = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                h.transport.beforeReply = {
                    if (it[0] == CommandCode.RESET_PATH.rawValue) { holding.complete(Unit); release.await() }
                }
                val priorCommand = backgroundScope.async { h.session.resetPath(TARGET) }
                holding.await()
                val receipt = NotificationActionReceipt()
                val call = backgroundScope.async(receipt) { destination.send(h.service) }
                runCurrent()
                assertEquals(NotificationActionReceipt.State.IN_FLIGHT, receipt.state)
                assertTrue(h.sends(destination.wire).isEmpty())
                call.cancelAndJoin()
                assertEquals(NotificationActionReceipt.State.READY, receipt.state)
                assertFalse(receipt.preventsReplay)
                assertTrue(h.sends(destination.wire).isEmpty())
                release.complete(Unit); priorCommand.await(); runCurrent()
                assertTrue(h.sends(destination.wire).isEmpty())
                withContext(receipt) { destination.send(h.service) }
                assertTrue(receipt.isAccepted)
                assertEquals(1, h.sends(destination.wire).size)
            },
            native("${destination}_acceptedBeforeCallerCancelIsNotFailedOrReplayed") {
                val h = Harness(this); h.start()
                val accepted = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                h.store.before = {
                    if (it == if (destination == Destination.DIRECT) "updateMessageAck" else "updateMessageStatus") {
                        accepted.complete(Unit)
                        // DM completion is deliberately noncancellable; let the test release that small write.
                        release.await()
                    }
                }
                val receipt = NotificationActionReceipt()
                val call = backgroundScope.async(receipt) { destination.send(h.service) }
                accepted.await()
                assertEquals(NotificationActionReceipt.State.ACCEPTED, receipt.state)
                call.cancel(); release.complete(Unit); call.join()
                assertFailsWith<CancellationException> { call.await() }
                assertTrue(receipt.isAccepted); assertTrue(receipt.preventsReplay)
                assertEquals(1, h.sends(destination.wire).size)
                assertNotEquals(MessageStatus.FAILED, h.store.messages.values.single().status)
            },
            native("${destination}_preSaveCancellationIsNotWireAcceptance") {
                val h = Harness(this); h.start()
                val entered = CompletableDeferred<Unit>()
                h.store.before = { if (it == "saveMessage") { entered.complete(Unit); awaitCancellation() } }
                val receipt = NotificationActionReceipt()
                val call = backgroundScope.async(receipt) { destination.send(h.service) }
                entered.await(); call.cancelAndJoin()
                assertEquals(NotificationActionReceipt.State.READY, receipt.state)
                assertFalse(receipt.preventsReplay); assertTrue(h.sends(destination.wire).isEmpty())
            },
            native("${destination}_mayHaveWrittenWithoutReplyIsUncertainAndCannotAuthorizeReplay") {
                val h = Harness(this); h.start()
                val enteredTransport = CompletableDeferred<Unit>()
                val waitForReply = CompletableDeferred<Unit>()
                h.transport.beforeReply = {
                    if (it[0] == destination.wire.rawValue) {
                        enteredTransport.complete(Unit)
                        waitForReply.await()
                    }
                }
                val receipt = NotificationActionReceipt()
                val call = backgroundScope.async(receipt) { destination.send(h.service) }
                enteredTransport.await()
                assertEquals(1, h.sends(destination.wire).size)
                assertEquals(NotificationActionReceipt.State.IN_FLIGHT, receipt.state)
                call.cancelAndJoin()
                assertEquals(NotificationActionReceipt.State.UNCERTAIN, receipt.state)
                assertTrue(receipt.preventsReplay); assertFalse(receipt.isAccepted)
                assertEquals(1, h.sends(destination.wire).size)
            },
            native("${destination}_definiteDeviceRejectionPermitsRecovery") {
                val h = Harness(this); h.start()
                if (destination == Destination.DIRECT) h.transport.directError = 1u else h.transport.channelError = 1u
                val receipt = NotificationActionReceipt()
                assertFailsWith<MessageServiceException> { withContext(receipt) { destination.send(h.service) } }
                assertEquals(NotificationActionReceipt.State.READY, receipt.state)
                assertFalse(receipt.preventsReplay); assertEquals(1, h.sends(destination.wire).size)
            },
            native("${destination}_teardownAfterObservedAcceptanceCannotMakeItUnsent") {
                val h = Harness(this); h.start()
                val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                h.store.before = {
                    if (it == if (destination == Destination.DIRECT) "updateMessageAck" else "updateMessageStatus") {
                        entered.complete(Unit); release.await()
                    }
                }
                val receipt = NotificationActionReceipt()
                val call = backgroundScope.async(receipt) { destination.send(h.service) }
                entered.await()
                val close = backgroundScope.async { h.service.close() }
                runCurrent(); release.complete(Unit); assertTrue(close.await().isComplete)
                assertFailsWith<CancellationException> { call.await() }
                assertTrue(receipt.isAccepted); assertTrue(receipt.preventsReplay)
                assertNotEquals(MessageStatus.FAILED, h.store.messages.values.single().status)
                assertEquals(1, h.sends(destination.wire).size)
            },
            native("${destination}_acceptanceSurvivesReplacementBeforeOwnershipFence") {
                val h = Harness(this); h.start()
                val actual = h.session
                val role = object : MeshCoreSessionProtocol by actual {
                    override suspend fun sendMessage(
                        destination: Bytes, text: String, timestamp: Instant, attempt: UByte,
                    ): MessageSentInfo =
                        actual.sendMessage(destination, text, timestamp, attempt).also {
                            h.signals.set(token(generation = 2), DeviceConnectionState.READY)
                        }
                    override suspend fun sendMessage(
                        destination: Bytes, text: String, timestamp: Instant, attempt: UByte, dispatch: MessageWriteDispatch,
                    ): MessageSentInfo =
                        actual.sendMessage(destination, text, timestamp, attempt, dispatch).also {
                            h.signals.set(token(generation = 2), DeviceConnectionState.READY)
                        }
                    override suspend fun sendChannelMessage(channel: UByte, text: String, timestamp: Instant) {
                        actual.sendChannelMessage(channel, text, timestamp)
                        h.signals.set(token(generation = 2), DeviceConnectionState.READY)
                    }
                    override suspend fun sendChannelMessage(
                        channel: UByte, text: String, timestamp: Instant, dispatch: MessageWriteDispatch,
                    ) {
                        actual.sendChannelMessage(channel, text, timestamp, dispatch)
                        h.signals.set(token(generation = 2), DeviceConnectionState.READY)
                    }
                }
                withService(h, h.store, role) { service ->
                    val receipt = NotificationActionReceipt()
                    assertFailsWith<MessageServiceException> { withContext(receipt) { destination.send(service) } }
                    assertTrue(receipt.isAccepted); assertTrue(receipt.preventsReplay)
                    assertEquals(MessageStatus.PENDING, h.store.messages.values.single().status)
                    assertFalse("updateMessageStatusUnlessDelivered" in h.store.calls)
                    assertEquals(1, h.sends(destination.wire).size)
                }
            },
        )
    }

    @TestFactory fun pendingDirectActionReceiptBoundaries() = listOf(
        native("pendingDirect_acceptanceSurvivesCancellationAndCannotReplay") {
            val h = Harness(this); h.start()
            val message = h.message(status = MessageStatus.PENDING)
            val receipt = NotificationActionReceipt()
            val call = backgroundScope.async(receipt) {
                h.service.sendPendingDirectMessage(message.id, h.contact)
            }

            runCurrent()
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertEquals(NotificationActionReceipt.State.ACCEPTED, receipt.state)
            call.cancelAndJoin()
            assertFailsWith<CancellationException> { call.await() }
            assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
            assertTrue(receipt.isAccepted)

            assertFailsWith<MessageServiceException> {
                withContext(receipt) { h.service.sendPendingDirectMessage(message.id, h.contact) }
            }
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
        },
        native("pendingDirect_definiteDeviceRejectionReturnsReadyWithoutFailingRow") {
            val h = Harness(this); h.start()
            val message = h.message(status = MessageStatus.PENDING)
            h.transport.directError = 1u
            val receipt = NotificationActionReceipt()

            assertFailsWith<MessageServiceException> {
                withContext(receipt) { h.service.sendPendingDirectMessage(message.id, h.contact) }
            }

            assertEquals(NotificationActionReceipt.State.READY, receipt.state)
            assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
            assertEquals(0, h.service.pendingAckCount)
            h.transport.directError = null
            h.transport.acknowledge = true
            withContext(receipt) { h.service.sendPendingDirectMessage(message.id, h.contact) }
            assertTrue(receipt.isAccepted)
            assertEquals(2, h.sends(CommandCode.SEND_MESSAGE).size)
        },
        native("pendingDirect_definitelyUnsentCancellationReturnsReadyForSameRow") {
            val h = Harness(this); h.start()
            val message = h.message(status = MessageStatus.PENDING)
            val holding = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            h.transport.beforeReply = {
                if (it[0] == CommandCode.RESET_PATH.rawValue) {
                    holding.complete(Unit)
                    release.await()
                }
            }
            val priorCommand = backgroundScope.async { h.session.resetPath(TARGET) }
            holding.await()
            val receipt = NotificationActionReceipt()
            val call = backgroundScope.async(receipt) {
                h.service.sendPendingDirectMessage(message.id, h.contact)
            }

            runCurrent()
            assertEquals(NotificationActionReceipt.State.IN_FLIGHT, receipt.state)
            call.cancelAndJoin()
            assertEquals(NotificationActionReceipt.State.READY, receipt.state)
            assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
            assertEquals(0, h.service.pendingAckCount)
            assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())

            release.complete(Unit)
            priorCommand.await()
            h.transport.beforeReply = null
            h.transport.acknowledge = true
            withContext(receipt) { h.service.sendPendingDirectMessage(message.id, h.contact) }
            assertTrue(receipt.isAccepted)
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
        },
        native("pendingDirect_possibleWireCancellationIsUncertainAndCannotFailOrReplay") {
            val h = Harness(this); h.start()
            val message = h.message(status = MessageStatus.PENDING)
            val entered = CompletableDeferred<Unit>()
            val waitForReply = CompletableDeferred<Unit>()
            h.transport.beforeReply = {
                if (it[0] == CommandCode.SEND_MESSAGE.rawValue) {
                    entered.complete(Unit)
                    waitForReply.await()
                }
            }
            val receipt = NotificationActionReceipt()
            val call = backgroundScope.async(receipt) {
                h.service.sendPendingDirectMessage(message.id, h.contact)
            }

            entered.await()
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            call.cancelAndJoin()
            assertEquals(NotificationActionReceipt.State.UNCERTAIN, receipt.state)
            assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
            assertFailsWith<MessageServiceException> {
                withContext(receipt) { h.service.sendPendingDirectMessage(message.id, h.contact) }
            }
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
        },
        native("pendingDirect_acceptedStateStaysMonotonicAcrossLaterRetryFailure") {
            val h = Harness(this); h.start()
            val message = h.message(status = MessageStatus.PENDING)
            var wireCount = 0
            h.transport.beforeReply = {
                if (it[0] == CommandCode.SEND_MESSAGE.rawValue && ++wireCount == 2) {
                    h.transport.directError = 1u
                }
            }
            val dispatches = mutableListOf<MessageWriteDispatch>()
            val session = object : MeshCoreSessionProtocol by h.session {
                override suspend fun sendMessage(
                    destination: Bytes, text: String, timestamp: Instant, attempt: UByte,
                    dispatch: MessageWriteDispatch,
                ): MessageSentInfo {
                    dispatches += dispatch
                    return h.session.sendMessage(destination, text, timestamp, attempt, dispatch)
                }
            }

            withService(h, h.store, session) { service ->
                val receipt = NotificationActionReceipt()
                val call = backgroundScope.async(receipt) {
                    service.sendPendingDirectMessage(message.id, h.contact)
                }
                runCurrent()
                assertEquals(NotificationActionReceipt.State.ACCEPTED, receipt.state)
                assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)

                advanceTimeBy(20)
                runCurrent()
                assertFailsWith<MessageServiceException> { call.await() }
                assertEquals(2, h.sends(CommandCode.SEND_MESSAGE).size)
                assertEquals(2, dispatches.size)
                assertNotSame(dispatches[0], dispatches[1])
                assertTrue(receipt.isAccepted)
                assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
                assertFailsWith<MessageServiceException> {
                    withContext(receipt) { service.sendPendingDirectMessage(message.id, h.contact) }
                }
                assertEquals(2, h.sends(CommandCode.SEND_MESSAGE).size)
            }
        },
    )

    private suspend fun withService(
        h: Harness, store: PersistenceStoreProtocol, session: MeshCoreSessionProtocol = h.session,
        block: suspend (MessageService) -> Unit,
    ) {
        val service = MessageService(h.token, SELF, session, store, h.signals, h.scope, clock = h.clock,
            reporter = MessagingIssueReporter { h.diagnostics += it })
        try { block(service) } finally { service.close() }
    }
}
