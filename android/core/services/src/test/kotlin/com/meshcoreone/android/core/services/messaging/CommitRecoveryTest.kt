// AndroidOnly: WP-208 Deterministic real-service/session commit and acceptance cancellation boundaries.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import com.meshcoreone.android.core.protocol.session.MessageWriteDispatch
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.session.MeshCoreSessionProtocol
import com.meshcoreone.android.core.services.contacts.ChannelDispatchFence
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class CommitRecoveryTest {
    @TestFactory fun legacyChannelDiagnostics() = listOf(
        native("legacyChannelUncertaintyReportsOutsideTheOperationAndPreservesItsPrimaryCause") {
            val h = Harness(this)
            h.store.seedChannelCreation()
            h.start()
            h.transport.channelError = 2u
            val identity = ChannelIdentityMutex()
            val fence = ChannelDispatchFence(identity)
            val lease = fence.openSession(h.token, h.session, ChannelDispatchAdmission(h.token, h.session))
            identity.withChannelOperationScope { fence.recordReconciled(lease, it, listOf(0u)) }
            val reportFailure = IllegalStateException("diagnostic observer")
            var observedOutsideOperation = false
            val service = MessageService(h.token, SELF, h.session, h.store, h.signals, h.scope, clock = h.clock,
                reporter = MessagingIssueReporter {
                    runBlocking {
                        withTimeout(1_000) {
                            identity.withChannelOperation { observedOutsideOperation = true }
                        }
                    }
                    throw reportFailure
                }, channelDispatchLease = lease)
            try {
                val failure = assertFailsWith<QueuedChannelRetainedException> {
                    service.sendChannelMessage("diagnostic boundary", 0u, RADIO)
                }
                val rejection = assertNotNull(generateSequence<Throwable>(failure) { it.cause }
                    .filterIsInstance<MeshCoreException.DeviceError>().firstOrNull())
                assertEquals(2.toUByte(), rejection.code)
                assertTrue(observedOutsideOperation)
                assertTrue(failure.suppressed.any { it === reportFailure })
                assertEquals(MessageStatus.PENDING, h.store.messages.values.single().status)
                assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            } finally {
                service.close()
                fence.retire(lease)
            }
        },
    )

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

        suspend fun start(h: Harness) {
            if (this == CHANNEL) h.store.seedChannelCreation()
            h.start()
        }

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
                h.store.seedChannelCreation()
                val receipt = PendingMessageCreation()
                val first = withContext(receipt) { destination.create(h.service) }
                assertFailsWith<IllegalStateException> { withContext(receipt) { destination.create(h.service) } }
                assertEquals(first, receipt.snapshot.message); assertEquals(1, h.store.messages.size)
                assertEquals(PendingMessageCreation.State.COMMITTED, receipt.snapshot.state)
            },
            native("${destination}_failedWriteWithAbsentReadbackIsUnknownWithoutOwnerBarrier") {
                val h = Harness(this); h.start()
                h.store.seedChannelCreation()
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
            native("${destination}_overlappingTopLevelCallsCannotShareOneNotificationReceipt") {
                val h = Harness(this); destination.start(h)
                val firstSaveEntered = CompletableDeferred<Unit>()
                val releaseFirstSave = CompletableDeferred<Unit>()
                var saveCount = 0
                h.store.before = {
                    if (it == "saveMessage" && ++saveCount == 1) {
                        firstSaveEntered.complete(Unit)
                        releaseFirstSave.await()
                    }
                }
                val receipt = NotificationActionReceipt()
                val first = backgroundScope.async(receipt) { destination.send(h.service) }
                firstSaveEntered.await()
                advanceTimeBy(1_000)
                runCurrent()
                val second = backgroundScope.async(receipt) {
                    try {
                        destination.send(h.service)
                        null
                    } catch (failure: MessageServiceException) {
                        failure
                    }
                }
                runCurrent()

                assertTrue(second.isCompleted)
                assertNotNull(second.await(), "overlapping ${destination.name.lowercase()} action must be rejected")
                assertTrue(h.store.messages.isEmpty())
                assertTrue(h.sends(destination.wire).isEmpty())
                assertEquals(NotificationActionReceipt.State.READY, receipt.state)
                releaseFirstSave.complete(Unit)
                first.await()

                assertEquals(1, h.store.messages.size)
                assertTrue(h.store.messages.values.all { it.timestamp == BASE_TIME.epochSecond.toUInt() })
                assertEquals(1, h.sends(destination.wire).size)
                assertEquals(NotificationActionReceipt.State.ACCEPTED, receipt.state)
            },
            native("${destination}_ownedTopLevelActionKeepsItsClaimAcross${if (destination == Destination.DIRECT) "BackoffRetry" else "UncertainRetention"}") {
                val h = Harness(this); destination.start(h)
                val firstAttempt = CompletableDeferred<Unit>()
                var attempts = 0
                if (destination == Destination.DIRECT) h.transport.directError = 3u else h.transport.channelError = 2u
                h.transport.beforeReply = {
                    if (it[0] == destination.wire.rawValue) {
                        attempts++
                        if (attempts == 1) firstAttempt.complete(Unit)
                        else if (attempts == 2) {
                            if (destination == Destination.DIRECT) h.transport.directError = null
                            else h.transport.channelError = null
                        }
                    }
                }
                val receipt = NotificationActionReceipt()
                val call = backgroundScope.async(receipt) {
                    if (destination == Destination.CHANNEL) {
                        assertFailsWith<QueuedChannelRetainedException> { destination.send(h.service) }
                    } else {
                        destination.send(h.service)
                        null
                    }
                }
                firstAttempt.await()
                runCurrent()
                assertEquals(1, h.sends(destination.wire).size)
                if (destination == Destination.CHANNEL) {
                    val failure = assertIs<QueuedChannelRetainedException>(call.await())
                    val rejection = generateSequence<Throwable>(failure) { it.cause }
                        .filterIsInstance<MeshCoreException.DeviceError>().firstOrNull()
                    assertEquals(2.toUByte(), assertNotNull(rejection).code)
                    assertEquals(NotificationActionReceipt.State.UNCERTAIN, receipt.state)
                    assertTrue(receipt.preventsReplay)
                    assertEquals(MessageStatus.PENDING, h.store.messages.values.single().status)
                    advanceTimeBy(1_000); runCurrent()
                    assertEquals(1, attempts)
                    assertEquals(1, h.sends(destination.wire).size)
                    assertFailsWith<MessageServiceException> {
                        withContext(receipt) { destination.send(h.service) }
                    }
                } else {
                    assertEquals(NotificationActionReceipt.State.READY, receipt.state)
                    advanceTimeBy(1_000); runCurrent(); call.await()
                    assertEquals(2, attempts)
                    assertEquals(2, h.sends(destination.wire).size)
                    assertEquals(NotificationActionReceipt.State.ACCEPTED, receipt.state)
                }
                assertEquals(1, h.store.messages.size)
            },
            native("${destination}_receiptClaimSpansServiceInstances") {
                val h = Harness(this); destination.start(h)
                val firstSaveEntered = CompletableDeferred<Unit>()
                val releaseFirstSave = CompletableDeferred<Unit>()
                h.store.before = {
                    if (it == "saveMessage") {
                        firstSaveEntered.complete(Unit)
                        releaseFirstSave.await()
                    }
                }
                withService(h, h.store, h.session) { otherService ->
                    val receipt = NotificationActionReceipt()
                    val first = backgroundScope.async(receipt) { destination.send(h.service) }
                    firstSaveEntered.await()
                    advanceTimeBy(1_000)
                    runCurrent()
                    val second = backgroundScope.async(receipt) {
                        try {
                            destination.send(otherService)
                            null
                        } catch (failure: MessageServiceException) {
                            failure
                        }
                    }
                    runCurrent()

                    assertTrue(second.isCompleted)
                    assertNotNull(second.await())
                    assertTrue(h.store.messages.isEmpty())
                    assertTrue(h.sends(destination.wire).isEmpty())
                    assertEquals(NotificationActionReceipt.State.READY, receipt.state)
                    releaseFirstSave.complete(Unit)
                    first.await()

                    assertEquals(1, h.store.messages.size)
                    assertEquals(BASE_TIME.epochSecond.toUInt(), h.store.messages.values.single().timestamp)
                    assertEquals(1, h.sends(destination.wire).size)
                    assertEquals(NotificationActionReceipt.State.ACCEPTED, receipt.state)
                }
            },
            native("${destination}_cancelBehindActualSerializerProvesUnsentAndAllowsOneRetry") {
                val h = Harness(this); destination.start(h)
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
                val overlapping = backgroundScope.async(receipt) {
                    try {
                        destination.send(h.service)
                        null
                    } catch (failure: MessageServiceException) {
                        failure
                    }
                }
                runCurrent()
                assertTrue(overlapping.isCompleted)
                assertNotNull(overlapping.await())
                assertEquals(1, h.store.messages.size)
                assertTrue(h.sends(destination.wire).isEmpty())
                call.cancelAndJoin()
                assertEquals(NotificationActionReceipt.State.READY, receipt.state)
                assertFalse(receipt.preventsReplay)
                assertEquals(MessageStatus.FAILED, h.store.messages.values.single().status)
                assertTrue(h.sends(destination.wire).isEmpty())
                release.complete(Unit); priorCommand.await(); runCurrent()
                assertTrue(h.sends(destination.wire).isEmpty())
                withContext(receipt) { destination.send(h.service) }
                assertTrue(receipt.isAccepted)
                assertEquals(1, h.sends(destination.wire).size)
                assertEquals(2, h.store.messages.size)
                assertEquals(1, h.store.messages.values.count { it.status == MessageStatus.FAILED })
                assertFalse(h.store.messages.values.any { it.status == MessageStatus.PENDING })
            },
            native("${destination}_acceptedBeforeCallerCancelIsNotFailedOrReplayed") {
                val h = Harness(this); destination.start(h)
                val accepted = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                h.store.before = {
                    if (it == if (destination == Destination.DIRECT) "updateMessageAck" else "completeAcceptedQueuedChannelAttempt") {
                        accepted.complete(Unit)
                        // Accepted completion is deliberately noncancellable; release its small write.
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
                h.store.before = null
                assertFailsWith<MessageServiceException> {
                    withContext(receipt) { destination.send(h.service) }
                }
                assertEquals(NotificationActionReceipt.State.ACCEPTED, receipt.state)
                assertEquals(1, h.store.messages.size)
                assertEquals(1, h.sends(destination.wire).size)
            },
            native("${destination}_preSaveCancellationIsNotWireAcceptance") {
                val h = Harness(this); destination.start(h)
                val entered = CompletableDeferred<Unit>()
                h.store.before = { if (it == "saveMessage") { entered.complete(Unit); awaitCancellation() } }
                val receipt = NotificationActionReceipt()
                val call = backgroundScope.async(receipt) { destination.send(h.service) }
                entered.await(); call.cancelAndJoin()
                assertEquals(NotificationActionReceipt.State.READY, receipt.state)
                assertFalse(receipt.preventsReplay); assertTrue(h.sends(destination.wire).isEmpty())
            },
            native("${destination}_mayHaveWrittenWithoutReplyIsUncertainAndCannotAuthorizeReplay") {
                val h = Harness(this); destination.start(h)
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
                assertEquals(MessageStatus.PENDING, h.store.messages.values.single().status)
                h.transport.beforeReply = null
                assertFailsWith<MessageServiceException> {
                    withContext(receipt) { destination.send(h.service) }
                }
                assertEquals(NotificationActionReceipt.State.UNCERTAIN, receipt.state)
                assertEquals(1, h.store.messages.size)
                assertEquals(1, h.sends(destination.wire).size)
            },
            native("${destination}_${if (destination == Destination.DIRECT) "definiteDeviceRejectionPermitsRecovery" else "uncorrelatedDeviceErrorRetainsUncertainty"}") {
                val h = Harness(this); destination.start(h)
                if (destination == Destination.DIRECT) h.transport.directError = 1u else h.transport.channelError = 1u
                val receipt = NotificationActionReceipt()
                if (destination == Destination.CHANNEL) {
                    val failure = assertFailsWith<QueuedChannelRetainedException> {
                        withContext(receipt) { destination.send(h.service) }
                    }
                    val rejection = generateSequence<Throwable>(failure) { it.cause }
                        .filterIsInstance<MeshCoreException.DeviceError>().firstOrNull()
                    assertEquals(1.toUByte(), assertNotNull(rejection).code)
                    assertEquals(NotificationActionReceipt.State.UNCERTAIN, receipt.state)
                    assertEquals(MessageStatus.PENDING, h.store.messages.values.single().status)
                    assertTrue(receipt.preventsReplay)
                } else {
                    assertFailsWith<MessageServiceException> { withContext(receipt) { destination.send(h.service) } }
                    assertEquals(NotificationActionReceipt.State.READY, receipt.state)
                    assertEquals(MessageStatus.FAILED, h.store.messages.values.single().status)
                    assertFalse(receipt.preventsReplay)
                }
                assertEquals(1, h.sends(destination.wire).size)
            },
            native("${destination}_teardownAfterObservedAcceptanceCannotMakeItUnsent") {
                val h = Harness(this); destination.start(h)
                val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                h.store.before = {
                    if (it == if (destination == Destination.DIRECT) "updateMessageAck" else "completeAcceptedQueuedChannelAttempt") {
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
                val h = Harness(this); destination.start(h)
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
                    assertFailsWith<MessageServiceException> {
                        service.sendPendingDirectMessage(message.id, h.contact)
                    }
                }
                runCurrent()
                assertEquals(NotificationActionReceipt.State.ACCEPTED, receipt.state)
                assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
                assertEquals(MessageStatus.PENDING, h.stored(message.id).status)

                advanceTimeBy(20)
                runCurrent()
                call.await()
                assertEquals(2, h.sends(CommandCode.SEND_MESSAGE).size)
                assertEquals(2, dispatches.size)
                assertNotSame(dispatches[0], dispatches[1])
                assertTrue(receipt.isAccepted)
                assertEquals(MessageStatus.RETRYING, h.stored(message.id).status)
                assertEquals(0L, h.stored(message.id).retryAttempt)
                assertEquals(service.config.maxAttempts - 1, h.stored(message.id).maxRetryAttempts)
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
        if (h.store.channels.values.none { it.radioId == RADIO && it.index == 0.toUByte() }) h.store.seedChannelCreation()
        val creationStore = object : PersistenceStoreProtocol by store, ChannelHistoryPersisting by h.store,
            QueuedChannelTerminalPersisting by h.store, InlineChannelAttemptPersisting by h.store {
            override suspend fun createOrReconcileChannelMessage(
                creation: ChannelMessageCreation, timestamp: UInt, createdAt: Instant,
            ): MessageDTO = h.store.capturedChannelMessage(creation, timestamp, createdAt).also {
                store.saveMessage(it)
            }
        }
        val lease = if (session === h.session) h.channelLease else {
            val identity = ChannelIdentityMutex()
            val fence = ChannelDispatchFence(identity)
            fence.openSession(h.token, session, ChannelDispatchAdmission(h.token, session)).also { lease ->
                identity.withChannelOperationScope { fence.recordReconciled(lease, it, listOf(0u)) }
            }
        }
        val service = MessageService(h.token, SELF, session, creationStore, h.signals, h.scope, clock = h.clock,
            reporter = MessagingIssueReporter { h.diagnostics += it }, channelDispatchLease = lease,
            directAckAuthority = h.store.directAckAuthority,
            directPendingCompletionAuthority = h.store.directAckAuthority)
        try { block(service) } finally {
            service.close()
            if (session !== h.session) lease.owner.retire(lease)
        }
    }
}
