// AndroidOnly: WP-208 Real-poller custody of exact guarded ingress outcomes and cancellation.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.MessagePollingException
import com.meshcoreone.android.core.contracts.domain.errors.MessagePollingError
import com.meshcoreone.android.core.contracts.domain.errors.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.errors.PersistenceStoreError
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.MessageResult
import com.meshcoreone.android.core.protocol.event.EventDispatcher
import com.meshcoreone.android.core.protocol.event.CriticalEventEnd
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.session.MeshCoreSessionProtocol
import com.meshcoreone.android.core.services.reactions.*
import com.meshcoreone.android.core.services.sync.*
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.TestFactory

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChannelIngressCustodyTest {
    @TestFactory
    fun terminalResidualFailuresAreNotRecoverable() = listOf("persistence", "polling").map { kind ->
        native("guarded residual $kind failure stops with exact terminal and remaining buffered input") {
            val h = Harness(this)
            h.start()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val role = object : MeshCoreSessionProtocol by h.session {
                override suspend fun getMessage(timeout: Double?): MessageResult {
                    val result = h.session.getMessage(timeout)
                    if (result == MessageResult.NoMoreMessages) {
                        entered.complete(Unit)
                        release.await()
                    }
                    return result
                }
            }
            val p = h.poller(role)
            val failure = if (kind == "persistence")
                PersistenceStoreException(PersistenceStoreError.SaveFailed("guarded residual failure"))
            else MessagePollingException(MessagePollingError.PollingFailed)
            var calls = 0
            p.setGuardedChannelReceiveEnvelopeHandler { _, _ ->
                calls++
                throw failure
            }
            p.startMessageEventMonitoring()
            val waiting = backgroundScope.async { assertFailsWith<Exception> { p.pollAllMessages() } }
            runCurrent()
            assertTrue(entered.isCompleted)
            repeat(2) {
                h.transport.mock.simulateReceive(packet())
                runCurrent()
            }
            assertEquals(0, calls)
            release.complete(Unit)
            runCurrent()
            assertTrue(waiting.isCompleted)
            assertSame(failure, waiting.await())
            val custody = assertIs<MessagePollingService.ChannelIngressUnclassifiedCustody>(p.retainedChannelIngressFailure)
            assertSame(failure, custody.failure)
            assertEquals(1, calls)
            assertEquals(2, p.undeliveredCount)
            val requests = h.sends(CommandCode.GET_MESSAGE).size
            assertSame(failure, assertFailsWith<Exception> { p.pollAllMessages() })
            assertEquals(requests, h.sends(CommandCode.GET_MESSAGE).size)
            assertEquals(1, calls)
            assertFalse(p.close().isComplete)
            assertSame(custody, p.retainedChannelIngressFailure)
        }
    }

    @TestFactory
    fun explicitlyGuardedUnclassifiedOutcomes() = listOf("lookup", "handler", "committed").flatMap { stage ->
        listOf(false, true).map { cancelled ->
            native("explicit guarded $stage ${if (cancelled) "cancellation" else "failure"} retains selected input without outcome claims") {
                val h = Harness(this)
                h.start()
                val p = h.poller()
                val cause = if (cancelled) CancellationException("unclassified cancellation")
                    else IllegalStateException("unclassified failure")
                var calls = 0
                var saved: MessageDTO? = null
                var selected: ChannelReceiveEnvelope? = null
                p.setGuardedChannelReceiveEnvelopeHandler { envelope, _ ->
                    calls++
                    selected = envelope
                    val marker = assertNotNull(currentCoroutineContext()[GuardedChannelIngressInvocation])
                    assertSame(p, marker.service)
                    assertSame(envelope, marker.envelope)
                    if (stage == "committed") {
                        saved = dto(envelope)
                        h.store.saveMessage(assertNotNull(saved))
                    }
                    throw cause
                }
                if (stage == "lookup") h.store.before = { if (it == "fetchChannel") throw cause }
                h.transport.incomingMessages += packet()
                assertFailsWith<Exception> { p.pollAllMessages() }
                val custody = assertIs<MessagePollingService.ChannelIngressUnclassifiedCustody>(p.retainedChannelIngressFailure)
                assertSame(cause, custody.failure)
                assertSame(custody.selectedEnvelope.message,
                    assertIs<MeshEvent.ChannelMessageReceived>(custody.selectedEvent).message)
                if (stage != "lookup") assertSame(selected, custody.selectedEnvelope)
                assertEquals(if (stage == "lookup") 0 else 1, calls)
                if (stage == "committed") assertSame(saved, h.stored(assertNotNull(saved).id))
                else assertTrue(h.store.messages.isEmpty())
                assertNull(p.retainedChannelIngressUnknown)
                assertEquals(1, p.undeliveredCount)
                h.store.before = null
                val requests = h.sends(CommandCode.GET_MESSAGE).size
                p.setChannelMessageHandler { _, _, _ -> calls++ }
                assertSame(cause, assertFailsWith<Exception> { p.pollAllMessages() })
                assertEquals(requests, h.sends(CommandCode.GET_MESSAGE).size)
                assertFalse(p.close().isComplete)
                assertSame(custody, p.retainedChannelIngressFailure)
                assertSame(cause, assertFailsWith<Exception> { p.pollAllMessages() })
            }
        }
    }

    @TestFactory
    fun guardedSelectionKeepsOriginalRegistration() = listOf("replace", "retire", "close").map { action ->
        native("guarded selected lookup survives registration $action without downgrade or raw replay") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var guardedCalls = 0
            var replacementCalls = 0
            val cause = IllegalStateException("original guarded callback")
            p.setGuardedChannelReceiveEnvelopeHandler { _, _ ->
                guardedCalls++
                throw cause
            }
            h.store.before = {
                if (it == "fetchChannel") {
                    entered.complete(Unit)
                    release.await()
                }
            }
            h.transport.incomingMessages += packet()
            val waiting = backgroundScope.async { assertFailsWith<Exception> { p.pollAllMessages() } }
            runCurrent()
            assertTrue(entered.isCompleted)
            when (action) {
                "replace" -> p.setChannelReceiveEnvelopeHandler { _, _ -> replacementCalls++ }
                "retire" -> h.signals.set(token(generation = 2), DeviceConnectionState.READY)
                else -> {
                    assertFalse(p.close().isComplete)
                }
            }
            release.complete(Unit)
            waiting.await()
            val custody = assertIs<MessagePollingService.ChannelIngressUnclassifiedCustody>(p.retainedChannelIngressFailure)
            if (action == "replace") assertSame(cause, custody.failure)
            if (action == "close") assertIs<CancellationException>(custody.failure)
            assertEquals(if (action == "replace") 1 else 0, guardedCalls)
            assertEquals(0, replacementCalls)
            h.store.before = null
            assertEquals(1, p.undeliveredCount)
            assertFalse(p.close().isComplete)
            assertSame(custody, p.retainedChannelIngressFailure)
        }
    }

    @TestFactory
    fun guardedReservationSuccessAndLegacyIsolation() = listOf("guarded", "legacy", "envelope").map { mode ->
        native("$mode registration preserves its success or ordinary redrive contract") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            var calls = 0
            val envelopes = mutableListOf<ChannelReceiveEnvelope>()
            when (mode) {
                "guarded" -> p.setGuardedChannelReceiveEnvelopeHandler { envelope, _ ->
                    calls++
                    envelopes += envelope
                }
                "legacy" -> p.setChannelMessageHandler { _, _, _ ->
                    calls++
                    if (calls == 1) error("ordinary legacy failure")
                }
                else -> p.setChannelReceiveEnvelopeHandler { _, _ ->
                    calls++
                    assertNull(currentCoroutineContext()[GuardedChannelIngressInvocation])
                    if (calls == 1) error("unmarked envelope failure")
                }
            }
            h.transport.incomingMessages += packet()
            if (mode == "guarded") {
                h.transport.incomingMessages += packet()
                assertEquals(2L, p.pollAllMessages())
                assertEquals(2, envelopes.size)
                assertTrue(envelopes[0].occurrenceID != envelopes[1].occurrenceID)
            } else {
                assertFailsWith<MessagePollingException> { p.pollAllMessages() }
                assertNull(p.retainedChannelIngressFailure)
                assertEquals(1, p.undeliveredCount)
                assertEquals(0L, p.pollAllMessages())
            }
            assertEquals(2, calls)
            assertNull(p.retainedChannelIngressFailure)
            assertEquals(0, p.undeliveredCount)
            assertTrue(p.close().isComplete)
        }
    }

    @TestFactory
    fun guardedCancellationAndOverflowBoundaries() = listOf(
        native("guarded callback cancellation retains both unclassified failure and its original cancellation") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            val cancellation = CancellationException("callback's job cancelled")
            val failure = IllegalStateException("callback fails after cancellation")
            p.setGuardedChannelReceiveEnvelopeHandler { _, _ ->
                currentCoroutineContext().cancel(cancellation)
                throw failure
            }
            h.transport.incomingMessages += packet()
            assertFailsWith<CancellationException> { p.pollAllMessages() }
            val custody = assertIs<MessagePollingService.ChannelIngressUnclassifiedCustody>(p.retainedChannelIngressFailure)
            assertSame(failure, custody.failure)
            assertSame(cancellation, custody.cancellation)
            val report = p.close()
            assertFalse(report.isComplete)
            assertTrue(report.issues.any { it.cause === failure })
            assertTrue(report.issues.any { it.cause === cancellation })
            assertSame(custody, p.retainedChannelIngressFailure)
        },
        native("guarded in-flight delivery retains exact critical overflow and backlog before propagation") {
            val h = Harness(this, useCriticalIngress = true)
            h.start()
            val p = h.poller()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var selected: ChannelReceiveEnvelope? = null
            var calls = 0
            p.setGuardedChannelReceiveEnvelopeHandler { envelope, _ ->
                calls++
                selected = envelope
                entered.complete(Unit)
                release.await()
            }
            p.startMessageEventMonitoring()
            h.transport.mock.simulateReceive(packet())
            runCurrent()
            assertTrue(entered.isCompleted)
            repeat(EventDispatcher.BUFFER_CAPACITY + 1) {
                h.transport.mock.simulateReceive(packet())
                runCurrent()
            }
            val overflow = assertNotNull(p.criticalIngressTerminal)
            assertEquals(CriticalEventEnd.OVERFLOW, overflow.reason)
            assertEquals(EventDispatcher.BUFFER_CAPACITY, overflow.backlog.size)
            assertNotNull(overflow.firstOverflow)
            assertNull(p.retainedChannelIngressFailure)
            release.complete(Unit)
            runCurrent()
            val custody = assertIs<MessagePollingService.ChannelIngressUnclassifiedCustody>(p.retainedChannelIngressFailure)
            assertSame(selected, custody.selectedEnvelope)
            assertSame(overflow, custody.failure)
            assertSame(custody.selectedEnvelope.message,
                assertIs<MeshEvent.ChannelMessageReceived>(overflow.inFlight).message)
            val requests = h.sends(CommandCode.GET_MESSAGE).size
            assertSame(overflow, assertFailsWith<Exception> { p.pollAllMessages() })
            assertEquals(requests, h.sends(CommandCode.GET_MESSAGE).size)
            assertEquals(1, calls)
            assertFalse(p.close().isComplete)
            assertSame(custody, p.retainedChannelIngressFailure)
            assertSame(overflow, p.criticalIngressTerminal)
        },
    )

    @TestFactory
    fun unresolvedDuplicateOutcomes() = listOf("hit", "lookup-failure", "lookup-cancellation", "cancel-after-hit").map { kind ->
        native("unresolved duplicate $kind retains exact handoff independently of save uncertainty") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            val cancellation = CancellationException("cancelled duplicate lookup or later observer")
            val lookupFailure = IllegalStateException("duplicate lookup failed")
            var held: ChannelMessageDuplicateUnresolved? = null
            var raised: Exception? = null
            var candidate: MessageDTO? = null
            var calls = 0
            p.setGuardedChannelReceiveEnvelopeHandler { envelope, _ ->
                calls++
                val original = dto(envelope)
                val reason = when (kind) {
                    "lookup-failure" -> ChannelMessageDuplicateUnresolvedReason.LookupFailed(lookupFailure)
                    "lookup-cancellation" -> ChannelMessageDuplicateUnresolvedReason.LookupFailed(cancellation)
                    else -> ChannelMessageDuplicateUnresolvedReason.ContentKeyHit(
                        original.copy(id = UUID.randomUUID()).also { candidate = it },
                    )
                }
                val work = ChannelMessageDuplicateUnresolved(envelope, unproven(envelope), original, reason)
                held = work
                val failure = if (kind == "lookup-cancellation")
                    ChannelMessageDuplicateUnresolvedCancellation(work, cancellation)
                else ChannelMessageDuplicateUnresolvedException(work)
                raised = failure
                if (kind == "cancel-after-hit") currentCoroutineContext().cancel(cancellation)
                throw failure
            }
            h.transport.incomingMessages += packet()
            h.transport.incomingMessages += packet()
            assertFailsWith<Exception> { p.pollAllMessages() }
            val custody = assertIs<MessagePollingService.ChannelDuplicateUnresolvedCustody>(p.retainedChannelIngressFailure)
            val work = assertNotNull(held)
            assertSame(work, custody.work)
            assertSame(raised, custody.failure)
            assertSame(work.envelope, custody.selectedEnvelope)
            assertSame(work.envelope.message, assertIs<MeshEvent.ChannelMessageReceived>(custody.selectedEvent).message)
            when (kind) {
                "hit", "cancel-after-hit" -> {
                    assertSame(candidate, assertIs<ChannelMessageDuplicateUnresolvedReason.ContentKeyHit>(work.reason).observedCandidate)
                    if (kind == "cancel-after-hit") assertSame(cancellation, custody.cancellation)
                    else assertNull(custody.cancellation)
                }
                "lookup-failure" -> {
                    assertSame(lookupFailure, work.reason.let { assertIs<ChannelMessageDuplicateUnresolvedReason.LookupFailed>(it).cause })
                    assertNull(custody.cancellation)
                }
                else -> {
                    assertSame(raised, custody.cancellation)
                    assertSame(cancellation, custody.failure.cause)
                }
            }
            assertNull(p.retainedChannelIngressUnknown)
            assertTrue(h.store.messages.isEmpty())
            assertEquals(1, p.undeliveredCount)
            val requests = h.sends(CommandCode.GET_MESSAGE).size
            p.setChannelMessageHandler { _, _, _ -> calls++ }
            assertSame(raised, assertFailsWith<Exception> { p.pollAllMessages() })
            assertEquals(requests, h.sends(CommandCode.GET_MESSAGE).size)
            assertEquals(1, calls)
            val report = p.close()
            assertFalse(report.isComplete)
            if (kind == "cancel-after-hit") assertTrue(report.issues.any { it.cause === cancellation })
            assertSame(custody, p.retainedChannelIngressFailure)
        }
    }

    @TestFactory
    fun knownOutcomesSurviveCallbackJobCancellation() = listOf(
        "unknown", "committed", "unusable", "selected", "native", "unselected",
    ).map { kind ->
        native("guarded $kind typed handoff survives its cancelled callback context") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            val cancellation = CancellationException("callback cancelled after typed handoff preparation")
            val originalCause = IllegalStateException("original typed failure")
            var raised: Exception? = null
            var originalReactionWork: ChannelReactionUnretainedWork? = null
            p.setGuardedChannelReceiveEnvelopeHandler { envelope, _ ->
                val message = dto(envelope)
                val failure: Exception = when (kind) {
                    "unknown" -> ChannelMessageIngressUnknownException(
                        ChannelMessageIngressUnknown(envelope, message, originalCause),
                    )
                    "committed" -> {
                        h.store.saveMessage(message)
                        ChannelMessageDeliveryIncompleteException(ChannelMessageDeliveryIncomplete(
                            envelope, unproven(envelope), message, CommittedChannelMessageReceipt.GenericSaveCompleted,
                            ChannelMessageDeliveryStage.SAVE_COMMITTED, originalCause,
                        ))
                    }
                    "unusable" -> ChannelMessageIngressUnusableReceiptException(ChannelMessageIngressUnusableReceipt(
                        envelope, unproven(envelope), message, ChannelMessageUnusableReceipt.Rejected(
                            ChannelReactionIngressResult.Rejected(ChannelReactionRejection.NOT_ELIGIBLE), originalCause,
                        ),
                    ))
                    else -> ChannelReactionRetentionException(
                        reactionWork(kind, envelope).also { originalReactionWork = it }, originalCause,
                    )
                }
                raised = failure
                currentCoroutineContext().cancel(cancellation)
                throw failure
            }
            h.transport.incomingMessages += packet()
            assertFailsWith<CancellationException> { p.pollAllMessages() }
            val custody = assertNotNull(p.retainedChannelIngressFailure)
            when (kind) {
                "unknown" -> assertSame(assertIs<ChannelMessageIngressUnknownException>(raised).work,
                    assertIs<MessagePollingService.ChannelIngressUnknownCustody>(custody).work)
                "committed" -> assertSame(assertIs<ChannelMessageDeliveryIncompleteException>(raised).work,
                    assertIs<MessagePollingService.ChannelDeliveryIncompleteCustody>(custody).work)
                "unusable" -> assertSame(assertIs<ChannelMessageIngressUnusableReceiptException>(raised).work,
                    assertIs<MessagePollingService.ChannelIngressUnusableReceiptCustody>(custody).work)
                else -> {
                    val retained = assertIs<MessagePollingService.ChannelReactionCustody>(custody).work
                    assertSame(assertIs<ChannelReactionRetentionException>(raised).work, retained)
                    assertSame(originalReactionWork, retained)
                    if (kind == "unselected") {
                        assertSame(
                            assertIs<ChannelReactionUnretainedWork.Unselected>(originalReactionWork).work.failure,
                            assertIs<ChannelReactionUnretainedWork.Unselected>(retained).work.failure,
                        )
                    }
                }
            }
            assertSame(raised, custody.failure)
            assertSame(originalCause, custody.failure.cause)
            assertSame(cancellation, custody.cancellation)
            assertEquals(1, p.undeliveredCount)
            val requests = h.sends(CommandCode.GET_MESSAGE).size
            assertFailsWith<Exception> { p.pollAllMessages() }
            assertEquals(requests, h.sends(CommandCode.GET_MESSAGE).size)
            assertFalse(p.close().isComplete)
            assertSame(custody, p.retainedChannelIngressFailure)
        }
    }

    @TestFactory
    fun unusableReturnedReceipts() = listOf("rejected", "inserted", "inserted-unproven", "existing").flatMap { kind ->
        listOf(false, true).map { withCause ->
            native("$kind unusable receipt with cause=$withCause occupies the terminal slot without redrive") {
                val h = Harness(this)
                h.start()
                val p = h.poller()
                val cause = if (withCause) IllegalStateException("returned receipt cannot be used") else null
                var held: ChannelMessageIngressUnusableReceipt? = null
                var returned: ChannelReactionIngressResult? = null
                var raised: ChannelMessageIngressUnusableReceiptException? = null
                var calls = 0
                p.setChannelReceiveEnvelopeHandler { envelope, _ ->
                    calls++
                    val original = dto(envelope)
                    val different = original.copy(id = UUID.randomUUID())
                    val result = when (kind) {
                        "rejected" -> ChannelReactionIngressResult.Rejected(ChannelReactionRejection.NOT_ELIGIBLE)
                        // Retention-only opaque marker, never issued or consumed as Proven authority.
                        "inserted" -> ChannelReactionIngressResult.Inserted(
                            CapturedChannelReactionTarget(object : ChannelReactionTarget {}, different),
                        )
                        "inserted-unproven" -> ChannelReactionIngressResult.InsertedUnproven(
                            different, ChannelReactionUnprovenReason.UNORDERED_INGRESS,
                        )
                        else -> ChannelReactionIngressResult.Existing(EntityKey(different.radioId, different.id))
                    }
                    returned = result
                    val receipt = if (result is ChannelReactionIngressResult.Rejected)
                        ChannelMessageUnusableReceipt.Rejected(result, cause)
                    else ChannelMessageUnusableReceipt.Inconsistent(result, cause)
                    val work = ChannelMessageIngressUnusableReceipt(envelope, unproven(envelope), original, receipt)
                    held = work
                    throw ChannelMessageIngressUnusableReceiptException(work).also { raised = it }
                }
                h.transport.incomingMessages += packet()
                val failure = assertFailsWith<ChannelMessageIngressUnusableReceiptException> { p.pollAllMessages() }
                assertSame(raised, failure)
                val custody = assertIs<MessagePollingService.ChannelIngressUnusableReceiptCustody>(p.retainedChannelIngressFailure)
                val original = assertNotNull(held)
                assertSame(original, custody.work)
                assertSame(raised, custody.failure)
                assertSame(cause, custody.failure.cause)
                assertSame(cause, original.receipt.cause)
                assertSame(returned, original.receipt.result)
                assertSame(original.envelope, custody.selectedEnvelope)
                assertSame(original.envelope, original.occurrence.envelope)
                assertSame(original.envelope.message, assertIs<MeshEvent.ChannelMessageReceived>(
                    custody.selectedEvent,
                ).message)
                if (kind == "rejected") assertIs<ChannelMessageUnusableReceipt.Rejected>(original.receipt)
                else assertIs<ChannelMessageUnusableReceipt.Inconsistent>(original.receipt)
                assertNull(p.retainedChannelIngressUnknown)
                assertEquals(1, p.undeliveredCount)
                val requests = h.sends(CommandCode.GET_MESSAGE).size
                p.setChannelReceiveEnvelopeHandler { _, _ -> calls++ }
                assertSame(original, assertFailsWith<ChannelMessageIngressUnusableReceiptException> { p.pollAllMessages() }.work)
                assertEquals(requests, h.sends(CommandCode.GET_MESSAGE).size)
                assertEquals(1, calls)
                assertFalse(p.close().isComplete)
                assertSame(custody, p.retainedChannelIngressFailure)
                assertSame(original, assertFailsWith<ChannelMessageIngressUnusableReceiptException> { p.pollAllMessages() }.work)
            }
        }
    }

    @TestFactory
    fun knownCommittedOutcomes() = listOf("generic", "inserted-unproven", "existing").flatMap { receiptKind ->
        listOf(false, true).map { cancelled ->
            native("known $receiptKind commit and joint native batch survive ${if (cancelled) "cancellation" else "delivery failure"}") {
                val h = Harness(this)
                h.start()
                val p = h.poller()
                val cancellation = CancellationException("channel delivery cancelled")
                val cause = if (cancelled) cancellation else IllegalStateException("channel delivery failed")
                val replayCause = IllegalStateException("earlier native overflow")
                var held: ChannelMessageDeliveryIncomplete? = null
                var originalReceipt: CommittedChannelMessageReceipt? = null
                var originalOverflow: ChannelMessageReactionOverflow? = null
                var earlierEnvelope: ChannelReceiveEnvelope? = null
                var raised: Exception? = null
                var calls = 0
                p.setChannelReceiveEnvelopeHandler { envelope, _ ->
                    calls++
                    val dto = dto(envelope)
                    h.store.saveMessage(dto)
                    val receipt = when (receiptKind) {
                        "inserted-unproven" -> CommittedChannelMessageReceipt.Authority(
                            ChannelReactionIngressResult.InsertedUnproven(
                                dto, ChannelReactionUnprovenReason.UNORDERED_INGRESS,
                            ),
                        )
                        "existing" -> CommittedChannelMessageReceipt.Authority(
                            ChannelReactionIngressResult.Existing(EntityKey(dto.radioId, dto.id)),
                        )
                        else -> CommittedChannelMessageReceipt.GenericSaveCompleted
                    }
                    originalReceipt = receipt
                    val earlier = envelope.copy(occurrenceID = UUID.randomUUID())
                    earlierEnvelope = earlier
                    val native = ChannelReactionUnretainedWork.Native(
                        (0 until ReactionService.MAX_PENDING_REACTIONS).map {
                            assertIs<ChannelReactionUnretainedWork.Native>(reactionWork("native", earlier)).work.single()
                        }.snapshot(),
                    )
                    val overflow = ChannelMessageReactionOverflow(native, replayCause)
                    originalOverflow = overflow
                    val work = ChannelMessageDeliveryIncomplete(
                        envelope, unproven(envelope), dto, receipt,
                        ChannelMessageDeliveryStage.REACTION_PREPARATION_COMPLETED, cause,
                        refreshedDTO = dto.copy(heardRepeats = 1),
                        reactionOverflow = overflow,
                    )
                    held = work
                    val failure = if (cancelled) ChannelMessageDeliveryIncompleteCancellation(work, cancellation)
                        else ChannelMessageDeliveryIncompleteException(work)
                    raised = failure
                    throw failure
                }
                h.transport.incomingMessages += packet()
                if (cancelled) assertFailsWith<ChannelMessageDeliveryIncompleteCancellation> { p.pollAllMessages() }
                else assertFailsWith<ChannelMessageDeliveryIncompleteException> { p.pollAllMessages() }
                val custody = assertIs<MessagePollingService.ChannelDeliveryIncompleteCustody>(p.retainedChannelIngressFailure)
                val original = assertNotNull(held)
                assertSame(original, custody.work)
                assertSame(raised, custody.failure)
                assertSame(cause, custody.failure.cause)
                assertSame(original.envelope, custody.selectedEnvelope)
                assertSame(original.messageDTO, h.stored(original.messageDTO.id))
                assertSame(originalReceipt, original.receipt)
                assertSame(originalOverflow, original.reactionOverflow)
                assertSame(replayCause, original.reactionOverflow?.originalCause)
                assertEquals(ReactionService.MAX_PENDING_REACTIONS, original.reactionOverflow?.work?.work?.size)
                original.reactionOverflow?.work?.work?.forEach {
                    assertSame(earlierEnvelope, it.pending.reactionOccurrence?.envelope)
                }
                assertEquals(ChannelMessageDeliveryStage.REACTION_PREPARATION_COMPLETED, original.lastCompletedStage)
                assertNull(p.retainedChannelIngressUnknown)
                assertEquals(1, p.undeliveredCount)
                val requests = h.sends(CommandCode.GET_MESSAGE).size
                p.setChannelReceiveEnvelopeHandler { _, _ -> calls++ }
                assertSame(original, assertFailsWith<ChannelMessageDeliveryIncompleteException> { p.pollAllMessages() }.work)
                assertEquals(requests, h.sends(CommandCode.GET_MESSAGE).size)
                assertEquals(1, calls)
                assertFalse(p.close().isComplete)
                assertSame(custody, p.retainedChannelIngressFailure)
                assertSame(original, assertFailsWith<ChannelMessageDeliveryIncompleteException> { p.pollAllMessages() }.work)
            }
        }
    }

    @TestFactory
    fun actualReactionRetentionOverflow() = listOf("selected", "native", "unselected").flatMap { kind ->
        listOf(false, true).map { cancelled ->
            native("actual $kind reaction capacity overflow survives ${if (cancelled) "cancellation" else "failure"} without raw replay") {
                val h = Harness(this)
                h.start()
                val p = h.poller()
                val reactions = ReactionService()
                val cancellation = CancellationException("reaction retention cancelled")
                val cause = if (cancelled) cancellation else IllegalStateException("reaction retention exceeded")
                var overflow: ChannelReactionUnretainedWork? = null
                var raised: Exception? = null
                var calls = 0
                p.setChannelReceiveEnvelopeHandler { envelope, _ ->
                    calls++
                    repeat(ReactionService.MAX_PENDING_REACTIONS) {
                        val old = envelope.copy(occurrenceID = UUID.randomUUID())
                        assertNull(park(reactions, reactionWork(kind, old)))
                    }
                    val work = reactionWork(kind, envelope)
                    assertSame(work, park(reactions, work))
                    overflow = work
                    val failure = if (cancelled) ChannelReactionRetentionCancellation(work, cancellation)
                        else ChannelReactionRetentionException(work, cause)
                    raised = failure
                    throw failure
                }
                h.transport.incomingMessages += packet()
                if (cancelled) assertFailsWith<ChannelReactionRetentionCancellation> { p.pollAllMessages() }
                else assertFailsWith<ChannelReactionRetentionException> { p.pollAllMessages() }
                val custody = assertIs<MessagePollingService.ChannelReactionCustody>(p.retainedChannelIngressFailure)
                val original = assertNotNull(overflow)
                assertSame(original, custody.work)
                assertSame(raised, custody.failure)
                assertSame(cause, custody.failure.cause)
                val occurrence = when (original) {
                    is ChannelReactionUnretainedWork.Selected -> original.work.reactionOccurrence
                    is ChannelReactionUnretainedWork.Native -> checkNotNull(original.work.single().pending.reactionOccurrence)
                    is ChannelReactionUnretainedWork.Unselected -> original.work.reactionOccurrence
                }
                assertSame(custody.selectedEnvelope, occurrence.envelope)
                assertNull(p.retainedChannelIngressUnknown)
                assertEquals(1, p.undeliveredCount)
                val count = when (kind) {
                    "selected" -> reactions.retainedChannelReactionFailures(h.token.radioId).size
                    "native" -> reactions.retainedNativeChannelReactionFailures(h.token.radioId).size
                    else -> reactions.retainedUnselectedChannelReactionFailures(h.token.radioId).size
                }
                assertEquals(ReactionService.MAX_PENDING_REACTIONS, count)
                val requests = h.sends(CommandCode.GET_MESSAGE).size
                p.setChannelReceiveEnvelopeHandler { _, _ -> calls++ }
                assertSame(original, assertFailsWith<ChannelReactionRetentionException> { p.pollAllMessages() }.work)
                assertEquals(requests, h.sends(CommandCode.GET_MESSAGE).size)
                assertEquals(1, calls)
                assertFalse(p.close().isComplete)
                assertSame(custody, p.retainedChannelIngressFailure)
            }
        }
    }

    private fun dto(envelope: ChannelReceiveEnvelope) = MessageDTO(
        radioId = envelope.session.radioId, channelIndex = envelope.message.channelIndex,
        text = "body", timestamp = 42u, direction = MessageDirection.INCOMING,
    )

    private fun unproven(envelope: ChannelReceiveEnvelope) =
        ChannelReactionOccurrence.Unproven(envelope, ChannelReactionUnprovenReason.UNORDERED_INGRESS)

    private fun reactionWork(kind: String, envelope: ChannelReceiveEnvelope): ChannelReactionUnretainedWork {
        val occurrence = unproven(envelope)
        val message = dto(envelope)
        // This opaque marker is retained only; no storage or Proven authority is fabricated.
        val captured = CapturedChannelReactionTarget(object : ChannelReactionTarget {}, message)
        return when (kind) {
            "selected" -> ChannelReactionUnretainedWork.Selected(ParkedChannelReaction(
                captured, ReactionDTO(
                    messageID = message.id, emoji = "X", senderName = "Alice", messageHash = "hash",
                    rawText = envelope.message.text, channelIndex = message.channelIndex, radioId = message.radioId,
                ), ParkedChannelReactionFormat.MCO_V3,
                ParkedChannelReactionFailure.Unproven(ChannelReactionUnprovenReason.UNORDERED_INGRESS), occurrence,
            ))
            "native" -> ChannelReactionUnretainedWork.Native(SnapshotList.of(ParkedNativeChannelReaction(
                PendingReaction(
                    ParsedReaction("X", "Alice", "hash"), envelope.message.channelIndex, "Bob",
                    envelope.message.text, message.radioId, message.createdAt, queueKeyOrder = 7, queueEntryOrder = 11,
                    boundTarget = captured, reactionOccurrence = occurrence,
                ), NativeChannelReactionFailure.CapacityExceeded,
            )))
            else -> ChannelReactionUnretainedWork.Unselected(UnselectedChannelReaction(
                envelope.message.text, envelope.message.channelIndex, message.radioId, "Bob", null,
                message.createdAt, UnselectedChannelReactionFormat.NATIVE, IllegalStateException("selection failed"), occurrence,
            ))
        }
    }

    private fun park(service: ReactionService, work: ChannelReactionUnretainedWork): ChannelReactionUnretainedWork? =
        when (work) {
            is ChannelReactionUnretainedWork.Selected -> when (val result = service.parkChannelReaction(work.work)) {
                ChannelReactionParkResult.Retained -> null
                is ChannelReactionParkResult.Overflow -> { assertSame(work.work, result.unretained); work }
            }
            is ChannelReactionUnretainedWork.Native -> when (val result = service.parkNativeChannelReaction(work.work.single())) {
                NativeChannelReactionParkResult.Retained -> null
                is NativeChannelReactionParkResult.Overflow -> { assertSame(work.work.single(), result.unretained); work }
            }
            is ChannelReactionUnretainedWork.Unselected -> when (val result = service.parkUnselectedChannelReaction(work.work)) {
                UnselectedChannelReactionParkResult.Retained -> null
                is UnselectedChannelReactionParkResult.Overflow -> { assertSame(work.work, result.unretained); work }
            }
        }

    private fun packet() = ByteWriter().appendUInt8(ResponseCode.CHANNEL_MESSAGE_RECEIVED.rawValue)
        .appendUInt8(0u).appendUInt8(0u).appendUInt8(0u).appendUInt32LE(42u)
        .append(Bytes.utf8("Alice: body")).toBytes()
}
