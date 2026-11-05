// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/MessageServiceACKTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: WP-208 maintainer-approved recoverable hydration and per-entry ACK persistence policy.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.model.ContactType
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryPolicyTest {
    @TestFactory fun queuedFinalizerAndReplacementCleanup() = listOf(
        native("active retry finalizer reconciles an ACK registered while waiting behind cancelled fail-all") {
            val h = Harness(this, MessageServiceConfig(maxAttempts = 1, floodAfter = 5, minTimeout = 0.0))
            h.start(); h.service.startEventMonitoring()
            val message = h.message()
            h.transport.suggestedTimeout = 10u
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val cancellation = CancellationException("fail-all cancelled after successful FAILED return")
            var writing = false
            h.store.directAckAuthority.afterFailure = {
                if (!writing) {
                    writing = true
                    entered.complete(Unit); release.await()
                    assertEquals(MessageStatus.FAILED, it.message?.status)
                    currentCoroutineContext().cancel(cancellation)
                }
            }
            val observed = mutableListOf<MessageStatusEvent>()
            observe(h, observed)
            val sending = backgroundScope.async {
                h.service.sendPendingDirectMessage(message.id, h.contact, preserveTimestamp = true)
            }
            runCurrent()
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            val code = assertNotNull(h.service.pendingAck(message.id)).ackCodes.first()
            val failing = backgroundScope.async { h.service.failAllPendingMessages() }
            entered.await()
            advanceTimeBy(20); runCurrent()
            assertFalse(sending.isCompleted)
            assertFalse(h.store.calls.contains("clearRetryingToSent"))
            h.transport.pushAck(code, 123u); runCurrent()
            assertEquals(code, h.service.pendingAck(message.id)?.acknowledgement?.code)
            release.complete(Unit)
            failing.join()
            assertTrue(failing.isCancelled)
            val delivered = sending.await()
            runCurrent()
            assertEquals(MessageStatus.DELIVERED, delivered.status)
            assertEquals(123u, delivered.roundTripTime)
            assertEquals(code.ackCodeUInt32, delivered.ackCode)
            assertNull(h.service.pendingAck(message.id))
            assertEquals(listOf<MessageStatusEvent>(
                MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 123u),
            ), observed)
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertTrue(h.service.close().isComplete)
            h.close()
        },
    ) + listOf(false, true).map { replacementDuringRead ->
        native("old close fences a successful failure marker; replacement during read=$replacementDuringRead") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            val message = h.message()
            val code = Bytes.of(0xAB, 0xCD, 0xEF, 0x12)
            h.installAcceptedPendingAck(h.tracking(message, code))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var writing = false
            h.store.directAckAuthority.afterFailure = {
                if (!writing) {
                    writing = true
                    entered.complete(Unit); release.await()
                    assertEquals(MessageStatus.FAILED, it.message?.status)
                    currentCoroutineContext().cancel(CancellationException("cancel before settlement"))
                }
            }
            val failing = backgroundScope.async { h.service.failAllPendingMessages() }
            entered.await()
            h.transport.pushAck(code, 123u); runCurrent()
            assertEquals(code, h.service.pendingAck(message.id)?.acknowledgement?.code)
            h.service.stopEventMonitoring()
            release.complete(Unit); failing.join()
            assertTrue(failing.isCancelled)
            val independentlyStored = h.stored(message.id)
            assertEquals(MessageStatus.FAILED, independentlyStored.status)
            if (replacementDuringRead) h.store.before = {
                if (it == "fetchMessage") h.signals.set(token(generation = 2), DeviceConnectionState.READY)
            }
            else h.signals.set(token(generation = 2), DeviceConnectionState.READY)
            val callsBeforeClose = h.store.calls.toList()
            val report = h.service.close()
            assertFalse(report.isComplete)
            assertTrue(report.issues.any {
                (it.cause as? MessageServiceException)?.error == MessageServiceError.NotConnected
            })
            assertEquals(
                if (replacementDuringRead) listOf("fetchMessage") else emptyList(),
                h.store.calls.drop(callsBeforeClose.size),
            )
            assertEquals(independentlyStored, h.stored(message.id))
            assertEquals(0, h.service.pendingAckCount)
            assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())
            h.close()
        }
    }

    @TestFactory fun failureAuthorityAfterQueuedRetry() = listOf(
        native("late original ACK cannot consume a failure marker after retry independently fails for a repeater") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            val message = h.service.sendDirectMessage("original accepted send", h.contact)
            val code = assertNotNull(h.service.pendingAck(message.id)).ackCodes.first()
            var writing = false
            h.store.directAckAuthority.afterFailure = {
                if (!writing) {
                    writing = true
                    assertEquals(MessageStatus.FAILED, it.message?.status)
                    currentCoroutineContext().cancel(CancellationException("cancel after FAILED commit without ACK"))
                }
            }
            val failing = backgroundScope.async { h.service.failAllPendingMessages() }
            failing.join()
            assertTrue(failing.isCancelled)
            assertEquals(MessageStatus.FAILED, h.stored(message.id).status)
            assertNotNull(h.service.pendingAck(message.id))
            h.store.before = null
            h.store.updateMessageStatus(EntityKey(RADIO, message.id), MessageStatus.PENDING)
            h.store.saveContact(h.contact.copy(typeRawValue = ContactType.REPEATER.rawValue))
            val observed = mutableListOf<MessageStatusEvent>()
            observe(h, observed)
            h.pending(message)
            val queue = h.queue()
            queue.hydrate(); runCurrent(); queue.awaitDrainCompletion()
            val independentlyFailed = h.stored(message.id)
            assertEquals(MessageStatus.FAILED, independentlyFailed.status)
            assertTrue(h.store.pending.isEmpty())
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertTrue(observed.none {
                it is MessageStatusEvent.StatusResolved && it.status == MessageStatus.DELIVERED
            })
            h.transport.pushAck(code, 123u); runCurrent()
            assertEquals(independentlyFailed, h.stored(message.id))
            assertTrue(observed.none {
                it is MessageStatusEvent.StatusResolved && it.status == MessageStatus.DELIVERED
            })
            queue.shutdown(); h.service.close()
            assertEquals(independentlyFailed, h.stored(message.id))
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            h.close()
        },
    )

    @TestFactory fun successfulFailedWriteCancellation() = listOf("expiry", "fail-all").flatMap { path ->
        listOf(false to true, true to true, false to false, true to false).map { (alreadyFailed, ackDuringWrite) ->
            native("$path cancellation preserves typed own failure or preexisting FAILED; prior FAILED=$alreadyFailed; in-write ACK=$ackDuringWrite") {
                val h = Harness(this); h.start(); h.service.startEventMonitoring()
                val message = h.message(if (alreadyFailed) MessageStatus.FAILED else MessageStatus.SENT)
                val later = h.message()
                val code = Bytes.of(0xAB, 0xCD, 0xEF, 0x12)
                h.installAcceptedPendingAck(h.tracking(message, code, age = 3600))
                h.installAcceptedPendingAck(h.tracking(later, Bytes.of(5, 6, 7, 8)))
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val cancellation = CancellationException("cancel after successful FAILED return")
                var writing = false
                var committed = false
                if (alreadyFailed) h.store.before = {
                    if (it == "fetchMessage" && !writing) {
                        writing = true
                        entered.complete(Unit); release.await()
                        committed = h.store.updateMessageStatusUnlessDelivered(
                            EntityKey(RADIO, message.id), MessageStatus.FAILED,
                        )
                        currentCoroutineContext().cancel(cancellation)
                    }
                }
                else h.store.directAckAuthority.afterFailure = {
                    if (!writing) {
                        writing = true
                        entered.complete(Unit); release.await()
                        committed = it.message?.status == MessageStatus.FAILED
                        currentCoroutineContext().cancel(cancellation)
                    }
                }
                val observed = mutableListOf<MessageStatusEvent>()
                observe(h, observed)
                val work = backgroundScope.async {
                    if (path == "expiry") h.service.checkExpiredAcks() else h.service.failAllPendingMessages()
                }
                entered.await()
                if (ackDuringWrite) {
                    h.transport.pushAck(code, 123u); runCurrent()
                    assertEquals(code, h.service.pendingAck(message.id)?.acknowledgement?.code)
                }
                release.complete(Unit)
                work.join(); runCurrent()
                assertTrue(committed)
                assertTrue(work.isCancelled)
                val propagated = assertFailsWith<CancellationException> { work.await() }
                assertTrue(generateSequence<Throwable>(propagated) { it.cause }.any { it === cancellation })
                if (!ackDuringWrite) {
                    assertEquals(MessageStatus.FAILED, h.stored(message.id).status)
                    assertNotNull(h.service.pendingAck(message.id))
                    assertTrue(observed.isEmpty())
                    h.transport.pushAck(code, 123u); runCurrent()
                }
                assertEquals(if (alreadyFailed) MessageStatus.FAILED else MessageStatus.DELIVERED,
                    h.stored(message.id).status)
                assertNull(h.service.pendingAck(message.id))
                assertEquals(MessageStatus.SENT, h.stored(later.id).status)
                assertNotNull(h.service.pendingAck(later.id))
                if (alreadyFailed) assertTrue(observed.isEmpty())
                else {
                    assertEquals(123u, h.stored(message.id).roundTripTime)
                    assertEquals(code.ackCodeUInt32, h.stored(message.id).ackCode)
                    assertEquals(listOf<MessageStatusEvent>(
                        MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 123u),
                    ), observed)
                }
                assertTrue(h.service.isEventMonitoringActive)
                assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())
                assertTrue(h.service.close().isComplete)
                h.close()
            }
        }
    }

    @TestFactory fun typedFailureCommitRecovery() = listOf("inline", "expiry", "fail-all").flatMap { path ->
        listOf(false, true).map { cancel ->
            native("$path reconciles journaled own FAILED commit after ${if (cancel) "cancellation" else "storage failure"}") {
                val h = Harness(this); h.start(); h.service.startEventMonitoring()
                val message = h.message()
                val code = Bytes.of(0xAB, 0xCD, 0xEF, 0x12)
                h.installAcceptedPendingAck(h.tracking(message, code, age = 3600))
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val fault = storageFailure()
                val cancellation = CancellationException("typed FAILED commit lost return")
                val original = IllegalStateException("initiating send error")
                h.store.directAckAuthority.afterFailure = {
                    entered.complete(Unit); release.await()
                    assertEquals(MessageStatus.FAILED, it.message?.status)
                    if (cancel) throw cancellation else throw fault
                }
                val observed = mutableListOf<MessageStatusEvent>()
                observe(h, observed)
                val work = backgroundScope.async {
                    suspend fun cleanup() = when (path) {
                        "inline" -> h.service.failMessageAndRethrow(original, message.id)
                        "expiry" -> h.service.checkExpiredAcks()
                        else -> h.service.failAllPendingMessages()
                    }
                    if (cancel) assertFailsWith<CancellationException> { cleanup() }
                    else if (path == "inline") assertSame(original, assertFailsWith<Exception> { cleanup() })
                    else if (path == "fail-all") assertSame(fault, assertFailsWith<PersistenceStoreException> { cleanup() })
                    else cleanup()
                }
                entered.await()
                h.transport.pushAck(code, 123u); runCurrent()
                assertEquals(code, h.service.pendingAck(message.id)?.acknowledgement?.code)
                release.complete(Unit)
                work.await(); runCurrent()
                assertEquals(MessageStatus.DELIVERED, h.stored(message.id).status)
                assertEquals(code.ackCodeUInt32, h.stored(message.id).ackCode)
                assertEquals(123u, h.stored(message.id).roundTripTime)
                assertNull(h.service.pendingAck(message.id))
                assertEquals(listOf<MessageStatusEvent>(
                    MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 123u),
                ), observed)
                assertTrue(h.service.close().isComplete)
                h.close()
            }
        }
    }

    @TestFactory fun uncertainFailedWrites() = listOf("inline", "expiry", "fail-all").flatMap { path ->
        listOf("commit-then-throw", "throw-before-commit", "already-failed", "competing-writer",
            "competing-after-read", "replacement", "late", "commit-then-cancel", "cancel-before-commit")
            .map { schedule -> native("$path FAILED-write $schedule retains only uncertain ACK authority") {
                val h = Harness(this); h.start(); h.service.startEventMonitoring()
                val message = h.message(if (schedule == "already-failed") MessageStatus.FAILED else MessageStatus.SENT)
                val code = Bytes.of(0xAB, 0xCD, 0xEF, 0x12)
                h.installAcceptedPendingAck(h.tracking(message, code, age = if (path == "expiry") 3600 else 0))
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val fault = storageFailure()
                val cancellation = CancellationException("controlled FAILED writer cancellation")
                val cancelling = schedule == "commit-then-cancel" || schedule == "cancel-before-commit"
                val writerFailure = if (cancelling) cancellation else fault
                var writing = false
                h.store.before = {
                    if ((it == "updateMessageStatusUnlessDelivered" ||
                            schedule == "already-failed" && it == "fetchMessage") && !writing) {
                        writing = true
                        entered.complete(Unit); release.await()
                        if (schedule !in listOf("throw-before-commit", "competing-after-read", "cancel-before-commit", "late")) {
                            h.store.updateMessageStatusUnlessDelivered(EntityKey(RADIO, message.id), MessageStatus.FAILED)
                        }
                        if (schedule != "late") throw writerFailure
                    }
                    if (it == "updateMessageAck" && schedule == "competing-after-read") {
                        h.store.updateMessageStatus(EntityKey(RADIO, message.id), MessageStatus.FAILED)
                    }
                }
                val observed = mutableListOf<MessageStatusEvent>()
                var streamFailure: MessageServiceException? = null
                val subscription = h.service.statusEvents()
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        subscription.events.collect {
                            assertEquals(h.token, it.token)
                            observed += it.event
                        }
                    } catch (failure: MessageServiceException) {
                        if (schedule != "replacement") throw failure
                        streamFailure = failure
                    }
                }
                val original = IllegalStateException("inline send failure")
                val work = backgroundScope.async {
                    suspend fun cleanup() {
                        when (path) {
                            "inline" -> h.service.failMessageAndRethrow(original, message.id)
                            "expiry" -> h.service.checkExpiredAcks()
                            else -> h.service.failAllPendingMessages()
                        }
                    }
                    val expected = when {
                        cancelling -> cancellation
                        path == "inline" -> original
                        schedule == "replacement" || path == "fail-all" && schedule != "late" -> fault
                        else -> null
                    }
                    if (expected == null) cleanup()
                    else {
                        val actual = assertFailsWith<Exception> { cleanup() }
                        if (cancelling) {
                            assertIs<CancellationException>(actual)
                            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === cancellation })
                        } else assertSame(expected, actual)
                    }
                }
                entered.await()
                if (schedule == "competing-writer") {
                    h.store.updateMessageStatus(EntityKey(RADIO, message.id), MessageStatus.FAILED)
                }
                if (schedule != "late") {
                    h.transport.pushAck(code, 123u); runCurrent()
                    assertEquals(code, h.service.pendingAck(message.id)?.acknowledgement?.code)
                }
                if (schedule == "replacement") h.signals.set(token(generation = 2), DeviceConnectionState.READY)
                release.complete(Unit)
                work.await()
                if (schedule == "late") h.transport.pushAck(code, 123u)
                runCurrent()
                if (schedule == "replacement") {
                    assertEquals(MessageServiceError.NotConnected, assertNotNull(streamFailure).error)
                } else assertNull(streamFailure)
                when (schedule) {
                    "throw-before-commit", "cancel-before-commit" -> {
                        assertEquals(MessageStatus.DELIVERED, h.stored(message.id).status)
                        assertNull(h.service.pendingAck(message.id))
                        assertEquals(listOf<MessageStatusEvent>(
                            MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 123u),
                        ), observed)
                        assertTrue(h.service.close().isComplete)
                    }
                    "already-failed", "late" -> {
                        assertEquals(MessageStatus.FAILED, h.stored(message.id).status)
                        assertNull(h.service.pendingAck(message.id))
                        assertTrue(observed.none { it is MessageStatusEvent.StatusResolved })
                    }
                    else -> {
                        assertEquals(MessageStatus.FAILED, h.stored(message.id).status)
                        assertEquals(code, h.service.pendingAck(message.id)?.acknowledgement?.code)
                        assertTrue(observed.isEmpty())
                        if (schedule == "commit-then-cancel") {
                            val other = h.message(MessageStatus.SENT)
                            val otherCode = Bytes.of(5, 6, 7, 8)
                            h.installAcceptedPendingAck(h.tracking(other, otherCode))
                            h.transport.pushAck(otherCode, 456u); runCurrent()
                            assertEquals(MessageStatus.DELIVERED, h.stored(other.id).status)
                            assertEquals(listOf<MessageStatusEvent>(
                                MessageStatusEvent.StatusResolved(other.id, MessageStatus.DELIVERED, 456u),
                            ), observed)
                            assertEquals(code, h.service.pendingAck(message.id)?.acknowledgement?.code)
                        }
                        val report = h.service.close()
                        assertFalse(report.isComplete)
                        assertTrue(report.issues.any { it.cause === writerFailure })
                        assertEquals(0, h.service.pendingAckCount)
                    }
                }
                assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())
                if (path == "inline" && schedule != "late" && !cancelling) {
                    assertTrue(original.suppressed.any { it === fault })
                }
                h.close()
            } }
    }

    @TestFactory fun recovery() = listOf(
        native("expiry retains only the failed entry and retries without duplicate failure events") {
            val h = Harness(this); h.start()
            val first = h.message(); val later = h.message()
            val tracking = h.installAcceptedPendingAck(h.tracking(first, age = 60))
            h.installAcceptedPendingAck(h.tracking(later, Bytes.of(5, 6, 7, 8), age = 60))
            val observed = mutableListOf<MessageStatusEvent>()
            observe(h, observed)
            val fault = storageFailure(); var writes = 0
            h.store.before = { if (it == "updateMessageStatusUnlessDelivered" && writes++ == 0) throw fault }
            h.service.checkExpiredAcks(); runCurrent()
            assertEquals(MessageStatus.SENT, h.stored(first.id).status)
            assertEquals(MessageStatus.FAILED, h.stored(later.id).status)
            assertSame(tracking, h.service.pendingAck(first.id))
            assertNull(h.service.pendingAck(later.id))
            assertEquals(listOf<MessageStatusEvent>(MessageStatusEvent.PersistenceChanged(EntityKey(RADIO, later.id))), observed)
            assertTrue(h.diagnostics.any { it is MessagingDiagnostic.Failure &&
                it.operation == "ackExpiry.${first.id}" && it.cause === fault })
            h.store.before = null
            h.service.checkExpiredAcks(); h.service.checkExpiredAcks(); runCurrent()
            assertEquals(MessageStatus.FAILED, h.stored(first.id).status)
            assertEquals(0, h.service.pendingAckCount)
            assertEquals(listOf<MessageStatusEvent>(MessageStatusEvent.PersistenceChanged(EntityKey(RADIO, later.id)),
                MessageStatusEvent.PersistenceChanged(EntityKey(RADIO, first.id))), observed)
            assertTrue(h.service.close().isComplete); h.close()
        },
        native("fail all diagnoses each failed write continues later entries and retries retained tracking") {
            val h = Harness(this); h.start()
            val messages = List(3) { h.message() }
            val tracking = messages.mapIndexed { index, message ->
                h.installAcceptedPendingAck(h.tracking(message, Bytes.of(index + 1, 0, 0, 0)))
            }
            val observed = mutableListOf<MessageStatusEvent>()
            observe(h, observed)
            val faults = listOf(storageFailure(), storageFailure()); var writes = 0
            h.store.before = {
                if (it == "updateMessageStatusUnlessDelivered") {
                    val index = writes++
                    if (index < faults.size) throw faults[index]
                }
            }
            val thrown = assertFailsWith<PersistenceStoreException> { h.service.failAllPendingMessages() }
            runCurrent()
            assertSame(faults[0], thrown); assertTrue(thrown.suppressed.any { it === faults[1] })
            assertEquals(3, writes)
            for (index in 0..1) {
                assertEquals(MessageStatus.SENT, h.stored(messages[index].id).status)
                assertSame(tracking[index], h.service.pendingAck(messages[index].id))
                assertTrue(h.diagnostics.any { it is MessagingDiagnostic.Failure &&
                    it.operation == "failPending.${messages[index].id}" && it.cause === faults[index] })
            }
            assertEquals(MessageStatus.FAILED, h.stored(messages[2].id).status)
            assertNull(h.service.pendingAck(messages[2].id))
            assertEquals(listOf<MessageStatusEvent>(MessageStatusEvent.PersistenceChanged(EntityKey(RADIO, messages[2].id))), observed)
            h.store.before = null
            h.service.failAllPendingMessages(); h.service.failAllPendingMessages(); runCurrent()
            assertEquals(0, h.service.pendingAckCount)
            assertTrue(messages.all { h.stored(it.id).status == MessageStatus.FAILED })
            assertEquals(listOf(messages[2].id, messages[0].id, messages[1].id),
                observed.filterIsInstance<MessageStatusEvent.PersistenceChanged>().map { it.key.id })
            assertTrue(h.service.close().isComplete); h.close()
        },
        native("unreconciled fail-all persistence errors survive in the teardown report") {
            val h = Harness(this); h.start()
            val message = h.message(); h.installAcceptedPendingAck(h.tracking(message))
            val fault = storageFailure()
            h.store.faults["updateMessageStatusUnlessDelivered"] = fault
            assertSame(fault, assertFailsWith<PersistenceStoreException> { h.service.failAllPendingMessages() })
            assertEquals(MessageStatus.SENT, h.stored(message.id).status)
            assertEquals(1, h.service.pendingAckCount)
            val report = h.service.close()
            assertFalse(report.isComplete); assertTrue(report.issues.any { it.cause === fault })
            h.close()
        },
        native("overlapping fail-all retry observes failure accounting before taking the mutation lock") {
            val h = Harness(this); h.start()
            val message = h.message(); h.installAcceptedPendingAck(h.tracking(message))
            val fault = storageFailure()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var writes = 0
            h.store.before = {
                if (it == "updateMessageStatusUnlessDelivered") {
                    if (writes++ == 0) {
                        entered.complete(Unit); release.await(); throw fault
                    }
                    assertTrue(h.diagnostics.any { diagnostic -> diagnostic is MessagingDiagnostic.Failure &&
                        diagnostic.operation == "failPending.${message.id}" && diagnostic.cause === fault })
                }
            }
            val observed = mutableListOf<MessageStatusEvent>()
            observe(h, observed)
            val failed = backgroundScope.async {
                assertFailsWith<PersistenceStoreException> { h.service.failAllPendingMessages() }
            }
            entered.await()
            // Resume the mutex waiter inline at unlock, before an outer catch could record the failure.
            val retry = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
                h.service.failAllPendingMessages()
            }
            try {
                assertFalse(retry.isCompleted)
            } finally { release.complete(Unit) }
            assertSame(fault, failed.await()); retry.await(); runCurrent()
            assertEquals(2, writes)
            assertEquals(MessageStatus.FAILED, h.stored(message.id).status)
            assertEquals(0, h.service.pendingAckCount)
            assertEquals(listOf<MessageStatusEvent>(MessageStatusEvent.PersistenceChanged(EntityKey(RADIO, message.id))), observed)
            assertTrue(h.service.close().isComplete); h.close()
        },
        native("overlapping ACK recovery observes fail-all ACK-write accounting before mutation unlock") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            val message = h.message()
            val code = Bytes.of(0xAB, 0xCD, 0xEF, 0x12)
            h.installAcceptedPendingAck(h.tracking(message, code))
            val failureEntered = CompletableDeferred<Unit>()
            val releaseFailure = CompletableDeferred<Unit>()
            val ackEntered = CompletableDeferred<Unit>()
            val releaseAck = CompletableDeferred<Unit>()
            val fault = storageFailure()
            var statusWrites = 0
            var ackWrites = 0
            h.store.before = {
                if (it == "updateMessageStatusUnlessDelivered" && statusWrites++ == 0) {
                    failureEntered.complete(Unit); releaseFailure.await()
                }
                if (it == "updateMessageAck") {
                    if (ackWrites++ == 0) {
                        ackEntered.complete(Unit); releaseAck.await(); throw fault
                    }
                    assertTrue(h.diagnostics.any { diagnostic -> diagnostic is MessagingDiagnostic.Failure &&
                        diagnostic.operation == "failPending.${message.id}" && diagnostic.cause === fault })
                }
            }
            val observed = mutableListOf<MessageStatusEvent>()
            observe(h, observed)
            val failing = backgroundScope.async {
                assertFailsWith<PersistenceStoreException> { h.service.failAllPendingMessages() }
            }
            failureEntered.await()
            h.transport.pushAck(code, 123u); runCurrent()
            assertEquals(code, h.service.pendingAck(message.id)?.acknowledgement?.code)
            h.service.stopEventMonitoring()
            releaseFailure.complete(Unit)
            ackEntered.await()
            val retry = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
                h.service.checkExpiredAcks()
            }
            try { assertFalse(retry.isCompleted) } finally { releaseAck.complete(Unit) }
            assertSame(fault, failing.await())
            retry.await(); runCurrent()
            assertEquals(2, ackWrites)
            assertEquals(MessageStatus.DELIVERED, h.stored(message.id).status)
            assertNull(h.service.pendingAck(message.id))
            assertEquals(listOf<MessageStatusEvent>(
                MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 123u),
            ), observed)
            assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())
            assertTrue(h.service.close().isComplete); h.close()
        },
        native("ACK during cleanup preserves recorded delivery without resurrecting terminal rows") {
            for (cleanupPath in listOf("inline", "expiry", "fail-all")) for (alreadyFailed in listOf(false, true)) {
                val h = Harness(this); h.start(); h.service.startEventMonitoring()
                val message = h.message(if (alreadyFailed) MessageStatus.FAILED else MessageStatus.SENT)
                val code = Bytes.of(0xAB, 0xCD, 0xEF, 0x12)
                h.installAcceptedPendingAck(h.tracking(message, code, age = if (cleanupPath == "expiry") 3600 else 0))
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val restoreFailure = storageFailure()
                var statusWrites = 0
                var deliveryWrites = 0
                h.store.before = {
                    if (alreadyFailed && it == "fetchMessage" && !entered.isCompleted) {
                        entered.complete(Unit)
                        h.transport.pushAck(code, 123u)
                        runCurrent()
                        assertEquals(code, h.service.pendingAck(message.id)?.acknowledgement?.code)
                        release.await()
                    }
                    if (it == "updateMessageStatusUnlessDelivered") {
                        statusWrites++
                        entered.complete(Unit)
                        h.transport.pushAck(code, 123u)
                        runCurrent()
                        assertEquals(code, h.service.pendingAck(message.id)?.acknowledgement?.code)
                        release.await()
                    }
                    if (it == "updateMessageAck") {
                        if (deliveryWrites++ == 0 && cleanupPath == "inline" && !alreadyFailed) throw restoreFailure
                    }
                }
                val observed = mutableListOf<MessageStatusEvent>()
                observe(h, observed)
                val failure = IllegalStateException("wire rejection")
                val failing = backgroundScope.async {
                    when (cleanupPath) {
                        "inline" -> assertSame(failure, assertFailsWith<IllegalStateException> {
                            h.service.failMessageAndRethrow(failure, message.id)
                        })
                        "expiry" -> h.service.checkExpiredAcks()
                        else -> h.service.failAllPendingMessages()
                    }
                }
                entered.await()
                release.complete(Unit)
                failing.await()
                runCurrent()
                val delivered = h.stored(message.id)
                if (alreadyFailed) assertEquals(message, delivered)
                else {
                    assertEquals(MessageStatus.DELIVERED, delivered.status)
                    assertEquals(code.ackCodeUInt32, delivered.ackCode)
                    assertEquals(123u, delivered.roundTripTime)
                }
                assertNull(h.service.pendingAck(message.id))
                assertEquals(
                    if (alreadyFailed) emptyList() else
                        listOf<MessageStatusEvent>(MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 123u)),
                    observed,
                )
                assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())
                assertEquals(if (alreadyFailed) 0 else 1, statusWrites)
                assertEquals(if (alreadyFailed) 0 else if (cleanupPath == "inline") 2 else 1, deliveryWrites)
                assertEquals(if (alreadyFailed) 0 else if (cleanupPath == "inline") 3 else 2, statusWrites + deliveryWrites)
                if (cleanupPath == "inline" && !alreadyFailed) {
                    assertTrue(failure.suppressed.any { it === restoreFailure })
                    assertTrue(h.diagnostics.any { it is MessagingDiagnostic.Failure &&
                        it.operation == "failMessage.${message.id}" && it.cause === restoreFailure })
                }
                assertTrue(h.service.close().isComplete); h.close()
            }
        },
        native("cancelling fail all keeps uncommitted tracking and does not fail later entries") {
            val h = Harness(this); h.start()
            val first = h.message(); val later = h.message()
            h.installAcceptedPendingAck(h.tracking(first))
            h.installAcceptedPendingAck(h.tracking(later, Bytes.of(5, 6, 7, 8)))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            h.store.before = {
                if (it == "updateMessageStatusUnlessDelivered") { entered.complete(Unit); release.await() }
            }
            val work = backgroundScope.async { h.service.failAllPendingMessages() }
            entered.await(); work.cancelAndJoin()
            assertTrue(work.isCancelled)
            assertEquals(2, h.service.pendingAckCount)
            assertEquals(MessageStatus.SENT, h.stored(first.id).status)
            assertEquals(MessageStatus.SENT, h.stored(later.id).status)
            assertEquals(1, h.store.calls.count { it == "updateMessageStatusUnlessDelivered" })
            assertTrue(h.diagnostics.none { it is MessagingDiagnostic.Failure })
            h.store.before = null
            h.service.failAllPendingMessages()
            assertEquals(0, h.service.pendingAckCount); h.close()
        },
        native("ownership replacement during fail all prevents later writes and stale notifications") {
            val h = Harness(this); h.start()
            val first = h.message(); val later = h.message()
            h.installAcceptedPendingAck(h.tracking(first))
            h.installAcceptedPendingAck(h.tracking(later, Bytes.of(5, 6, 7, 8)))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            h.store.before = {
                if (it == "updateMessageStatusUnlessDelivered") { entered.complete(Unit); release.await() }
            }
            val observed = mutableListOf<MessageStatusEvent>()
            observe(h, observed)
            val work = backgroundScope.async {
                assertFailsWith<MessageServiceException> { h.service.failAllPendingMessages() }
            }
            entered.await()
            h.signals.set(token(generation = 2), DeviceConnectionState.READY)
            release.complete(Unit)
            assertEquals(MessageServiceError.NotConnected, work.await().error)
            runCurrent()
            assertEquals(MessageStatus.FAILED, h.stored(first.id).status)
            assertEquals(MessageStatus.SENT, h.stored(later.id).status)
            assertEquals(2, h.service.pendingAckCount)
            assertEquals(1, h.store.calls.count { it == "updateMessageStatusUnlessDelivered" })
            assertTrue(observed.isEmpty()); h.close()
        },
        native("a raw ACK with failed delivery persistence reconciles once without another send") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            val message = h.service.sendDirectMessage("accepted before ACK storage failure", h.contact)
            val tracking = assertNotNull(h.service.pendingAck(message.id))
            val observed = mutableListOf<MessageStatusEvent>()
            observe(h, observed)
            val fault = storageFailure(); h.store.faults["updateMessageAck"] = fault
            h.transport.pushAck(tracking.ackCodes.first(), 250u); runCurrent()
            val retained = assertNotNull(h.service.pendingAck(message.id))
            assertTrue(retained.isDelivered); assertEquals(250u, retained.acknowledgement?.tripTime)
            assertEquals(MessageStatus.SENT, h.stored(message.id).status)
            assertTrue(observed.none { it is MessageStatusEvent.StatusResolved || it is MessageStatusEvent.Failed })
            assertTrue(h.diagnostics.any { it is MessagingDiagnostic.Failure && it.cause === fault })
            h.service.failAllPendingMessages()
            assertSame(retained, h.service.pendingAck(message.id))
            assertEquals(MessageStatus.SENT, h.stored(message.id).status)
            h.store.faults.clear()
            h.service.checkExpiredAcks(); h.service.checkExpiredAcks(); runCurrent()
            assertEquals(MessageStatus.DELIVERED, h.stored(message.id).status)
            assertEquals(250u, h.stored(message.id).roundTripTime)
            assertEquals(0, h.service.pendingAckCount)
            assertEquals(listOf<MessageStatusEvent>(MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 250u)), observed)
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertTrue(h.service.close().isComplete); h.close()
        },
        native("failed hydration retries the same durable envelope once and then latches successful hydration") {
            val h = Harness(this); h.start(); h.transport.acknowledge = true
            val message = h.message(MessageStatus.PENDING); val row = h.pending(message); val queue = h.queue()
            val fault = storageFailure(); h.store.faults["fetchPendingSends"] = fault
            val thrown = assertFailsWith<ChatSendQueueServiceException> { queue.hydrate() }
            assertSame(fault, assertIs<ChatSendQueueServiceError.PersistFailed>(thrown.error).underlying)
            assertEquals(row, h.store.pending.values.single())
            assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
            assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())
            h.store.faults.clear()
            queue.hydrate(); runCurrent(); queue.awaitDrainCompletion()
            assertEquals(MessageStatus.DELIVERED, h.stored(message.id).status)
            assertTrue(h.store.pending.isEmpty())
            queue.hydrate(); runCurrent(); queue.awaitDrainCompletion()
            assertEquals(2, h.store.calls.count { it == "fetchPendingSends" })
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertTrue(queue.shutdown().isComplete); h.close()
        },
        native("cancelling one hydration observer does not cancel the shared load or duplicate its send") {
            val h = Harness(this); h.start(); h.transport.acknowledge = true
            val message = h.message(MessageStatus.PENDING); h.pending(message); val queue = h.queue()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            h.store.before = { if (it == "fetchPendingSends") { entered.complete(Unit); release.await() } }
            val first = backgroundScope.async { queue.hydrate() }
            entered.await(); first.cancelAndJoin()
            val second = backgroundScope.async { queue.hydrate() }
            runCurrent(); assertFalse(second.isCompleted)
            assertEquals(1, h.store.calls.count { it == "fetchPendingSends" })
            release.complete(Unit); second.await(); runCurrent(); queue.awaitDrainCompletion()
            assertEquals(MessageStatus.DELIVERED, h.stored(message.id).status)
            assertTrue(h.store.pending.isEmpty())
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertTrue(queue.shutdown().isComplete); h.close()
        },
    ) + listOf("ACK", "expiry").map { recovery ->
        native("fail-all storage failure resolves through $recovery with complete teardown") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            val message = h.service.sendDirectMessage("recover fail-all through $recovery", h.contact)
            val tracking = assertNotNull(h.service.pendingAck(message.id))
            val observed = mutableListOf<MessageStatusEvent>()
            observe(h, observed)
            val fault = storageFailure()
            h.store.faults["updateMessageStatusUnlessDelivered"] = fault
            assertSame(fault, assertFailsWith<PersistenceStoreException> { h.service.failAllPendingMessages() })
            assertSame(tracking, h.service.pendingAck(message.id))
            assertEquals(MessageStatus.SENT, h.stored(message.id).status)
            assertTrue(observed.isEmpty())
            assertTrue(h.diagnostics.any { it is MessagingDiagnostic.Failure &&
                it.operation == "failPending.${message.id}" && it.cause === fault })
            h.store.faults.clear()
            val expected: MessageStatusEvent = if (recovery == "ACK") {
                h.transport.pushAck(tracking.ackCodes.first(), 250u); runCurrent()
                MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 250u)
            } else {
                h.clock.offset += 60_000
                h.service.checkExpiredAcks(); runCurrent()
                MessageStatusEvent.PersistenceChanged(EntityKey(RADIO, message.id))
            }
            assertEquals(if (recovery == "ACK") MessageStatus.DELIVERED else MessageStatus.FAILED,
                h.stored(message.id).status)
            assertEquals(0, h.service.pendingAckCount)
            assertEquals(listOf(expected), observed)
            h.service.failAllPendingMessages()
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertTrue(h.service.close().isComplete); h.close()
        }
    } + listOf(false, true).map { cancelSend ->
        native("fail-all storage failure resolves through ${if (cancelSend) "send cancellation" else "terminal send error"}") {
            val h = Harness(this); h.start(); h.transport.directError = 5u
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            h.transport.beforeReply = {
                if (it[0] == CommandCode.SEND_MESSAGE.rawValue) { entered.complete(Unit); release.await() }
            }
            val observed = mutableListOf<MessageStatusEvent>()
            observe(h, observed)
            val sending = backgroundScope.async {
                try {
                    h.service.sendDirectMessage("failed fail-all during send", h.contact)
                    null
                } catch (failure: MessageServiceException) { failure }
            }
            entered.await()
            val message = h.store.messages.values.single()
            val fault = storageFailure()
            h.store.faults["updateMessageStatusUnlessDelivered"] = fault
            try {
                assertSame(fault, assertFailsWith<PersistenceStoreException> { h.service.failAllPendingMessages() })
                assertEquals(1, h.service.pendingAckCount)
                assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
                h.store.faults.clear()
                if (cancelSend) {
                    sending.cancelAndJoin()
                    assertTrue(sending.isCancelled)
                } else {
                    release.complete(Unit)
                    val failure = assertNotNull(sending.await())
                    val underlying = assertIs<MessageServiceError.SessionError>(failure.error).underlying
                    assertEquals(5u.toUByte(), assertIs<MeshCoreException.DeviceError>(underlying).code)
                }
            } finally {
                h.store.faults.clear()
                release.complete(Unit)
            }
            runCurrent()
            assertEquals(MessageStatus.FAILED, h.stored(message.id).status)
            assertEquals(0, h.service.pendingAckCount)
            assertEquals(listOf<MessageStatusEvent>(MessageStatusEvent.PersistenceChanged(EntityKey(RADIO, message.id))), observed)
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertTrue(h.service.close().isComplete); h.close()
        }
    }

    private fun TestScope.observe(h: Harness, observed: MutableList<MessageStatusEvent>): Job {
        val subscription = h.service.statusEvents()
        return backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            subscription.events.collect {
                assertEquals(h.token, it.token)
                observed += it.event
            }
        }
    }
}
