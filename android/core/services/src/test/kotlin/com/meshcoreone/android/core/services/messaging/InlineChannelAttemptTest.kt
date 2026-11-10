// AndroidOnly: WP-208 Synchronous channel sends retain exact durable outcomes without queue scheduling.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.services.contacts.ChannelDispatchFailure
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class InlineChannelAttemptTest {
    private suspend fun ready(test: TestScope, store: RecordingStore = RecordingStore(), generation: Long = 1): Harness {
        val h = Harness(test, store = store, generation = generation)
        if (store.channels.values.none { it.radioId == RADIO && it.index == 0.toUByte() }) store.seedChannelCreation()
        h.start()
        return h
    }

    @TestFactory fun immediateOutcomes() = listOf(
        native("inline_waits_for_actual_wire_then_commits_the_same_message_pending_and_attempt_origin") {
            val h = ready(this)
            val origin = h.store.channelOccupants.getValue(RADIO to 0.toUByte())
            h.transport.holdChannelReplies = true
            val receipt = NotificationActionReceipt()
            val events = h.service.statusEvents()
            val call = backgroundScope.async(receipt) {
                h.service.sendChannelMessage("original \uD83C\uDF10", 0u, RADIO)
            }
            runCurrent()
            assertFalse(call.isCompleted)
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            val message = h.store.messages.values.single()
            val pending = h.store.pending.values.single()
            val attempt = h.store.channelAttempts.values.single()
            assertEquals(MessageStatus.PENDING, message.status)
            assertEquals(message.id, pending.messageID)
            assertEquals(pending.id, attempt.key.pendingID)
            assertEquals(message.id, attempt.key.messageID)
            assertEquals(origin, message.channelOccupantID)
            assertEquals(origin, pending.channelOccupantID)
            assertEquals(origin, pending.channelMessageEnvelope()?.channelOccupantID)
            assertEquals(origin, attempt.channelOccupantID)
            assertEquals(message.text, pending.messageText)
            assertEquals(message.timestamp, attempt.wireTimestamp)
            assertEquals(QueuedChannelDispatchState.DISPATCHING, pending.channelDispatchState)
            assertEquals(QueuedChannelAttemptPhase.DISPATCHING, attempt.phase)
            assertEquals(NotificationActionReceipt.State.IN_FLIGHT, receipt.state)

            h.transport.mock.simulateOK()
            runCurrent()
            val sent = call.await()
            assertEquals(message.id, sent.id)
            assertEquals(message.timestamp, sent.timestamp)
            assertEquals(message.copy(status = MessageStatus.SENT), h.stored(message.id))
            assertTrue(h.store.pending.isEmpty())
            assertTrue(h.store.channelAttempts.values.single().isCompleted)
            assertEquals(QueuedChannelAttemptPhase.ACCEPTED, h.store.channelAttempts.values.single().phase)
            assertTrue(receipt.isAccepted)
            assertEquals(listOf(MessageStatusEvent.StatusResolved(message.id, MessageStatus.SENT, null)),
                statuses(h.service, events))
            assertTrue(h.sends(CommandCode.GET_CHANNEL).isEmpty())
        },
        native("inline_raw_ERROR2_is_durable_uncertain_not_FAILED_or_backoff_and_cannot_replay_after_restart") {
            val h = ready(this)
            h.transport.channelError = 2u
            val receipt = NotificationActionReceipt()
            val events = h.service.statusEvents()
            assertFailsWith<QueuedChannelRetainedException> {
                withContext(receipt) { h.service.sendChannelMessage("retain original text", 0u, RADIO) }
            }
            val message = h.store.messages.values.single()
            val pending = h.store.pending.values.single()
            val attempt = h.store.channelAttempts.values.single()
            assertEquals(MessageStatus.PENDING, message.status)
            assertEquals(message.text, pending.messageText)
            assertEquals(message.channelOccupantID, pending.channelOccupantID)
            assertEquals(message.channelOccupantID, attempt.channelOccupantID)
            assertEquals(QueuedChannelDispatchState.UNCERTAIN, pending.channelDispatchState)
            assertEquals(QueuedChannelAttemptPhase.UNCERTAIN, attempt.phase)
            assertEquals(NotificationActionReceipt.State.UNCERTAIN, receipt.state)
            assertTrue(receipt.preventsReplay)
            assertFailsWith<QueuedChannelRetainedException> { h.service.sendPendingChannelMessage(message.id) }
            assertFailsWith<QueuedChannelRetainedException> { h.service.resendChannelMessage(message.id) }
            advanceTimeBy(60_000); runCurrent()
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            assertTrue(h.sends(CommandCode.GET_CHANNEL).isEmpty())
            assertTrue(statuses(h.service, events).isEmpty())
            h.close()

            // A new fence models lost process memory; durable state, not the retired lease, prevents replay.
            val next = ready(this, h.store, generation = 2)
            assertFailsWith<QueuedChannelRetainedException> { next.service.sendPendingChannelMessage(message.id) }
            assertFailsWith<QueuedChannelRetainedException> { next.service.resendChannelMessage(message.id) }
            val queue = next.queue(query = MessagingChannelQuery { fail("Raw ERROR2 must not trigger a query") })
            queue.hydrate(); runCurrent(); queue.awaitDrainCompletion()
            assertEquals(message, next.stored(message.id))
            assertEquals(pending, next.store.pending.values.single())
            assertEquals(attempt, next.store.channelAttempts.values.single())
            assertTrue(next.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            assertTrue(next.sends(CommandCode.GET_CHANNEL).isEmpty())
        },
        native("inline_cancel_behind_real_serializer_retires_only_proved_unsent_attempt_and_keeps_user_text") {
            val h = ready(this)
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            h.transport.beforeReply = {
                if (it[0] == CommandCode.RESET_PATH.rawValue) { held.complete(Unit); release.await() }
            }
            val prior = backgroundScope.async { h.session.resetPath(TARGET) }
            held.await()
            val receipt = NotificationActionReceipt()
            val call = backgroundScope.async(receipt) {
                h.service.sendChannelMessage("keep cancelled text", 0u, RADIO)
            }
            runCurrent()
            val saved = h.store.messages.values.single()
            val pendingID = h.store.pending.values.single().id
            assertEquals(NotificationActionReceipt.State.IN_FLIGHT, receipt.state)
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            call.cancelAndJoin()
            assertFailsWith<CancellationException> { call.await() }
            val attempt = h.store.channelAttempts.values.single()
            assertEquals(pendingID, attempt.key.pendingID)
            assertEquals(saved.id, attempt.key.messageID)
            assertEquals(saved.channelOccupantID, attempt.channelOccupantID)
            assertEquals(QueuedChannelAttemptPhase.DEFINITELY_UNSENT, attempt.phase)
            assertEquals(saved.copy(status = MessageStatus.FAILED), h.stored(saved.id))
            assertTrue(h.store.pending.isEmpty())
            assertEquals(NotificationActionReceipt.State.READY, receipt.state)
            assertFalse(receipt.preventsReplay)
            h.channelFence.withMutation(h.channelLease) { }
            release.complete(Unit); prior.await(); runCurrent()
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty(),
                "The cancelled protocol request cannot dispatch after its predecessor")
            h.close()

            val next = ready(this, h.store, generation = 2)
            val queue = next.queue()
            queue.hydrate(); runCurrent(); queue.awaitDrainCompletion()
            assertTrue(next.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            assertEquals(saved.copy(status = MessageStatus.FAILED), next.stored(saved.id))
            next.service.sendPendingChannelMessage(saved.id)
            assertEquals(1, next.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            assertEquals(MessageStatus.SENT, next.stored(saved.id).status)
            assertEquals(saved.text, next.stored(saved.id).text)
            assertEquals(saved.channelOccupantID, next.stored(saved.id).channelOccupantID)
            assertTrue(next.store.pending.isEmpty())
            assertEquals(2, next.store.channelAttempts.size)
        },
        native("inline_cancel_after_transport_entry_keeps_uncertain_origin_and_never_automatically_replays") {
            val h = ready(this)
            val entered = CompletableDeferred<Unit>()
            h.transport.beforeReply = {
                if (it[0] == CommandCode.SEND_CHANNEL_MESSAGE.rawValue) { entered.complete(Unit); awaitCancellation() }
            }
            val receipt = NotificationActionReceipt()
            val call = backgroundScope.async(receipt) {
                h.service.sendChannelMessage("possibly broadcast", 0u, RADIO)
            }
            entered.await()
            call.cancelAndJoin()
            assertFailsWith<CancellationException> { call.await() }
            val saved = h.store.messages.values.single()
            val pending = h.store.pending.values.single()
            assertEquals(MessageStatus.PENDING, saved.status)
            assertEquals(saved.channelOccupantID, pending.channelOccupantID)
            assertEquals(QueuedChannelDispatchState.UNCERTAIN, pending.channelDispatchState)
            assertEquals(QueuedChannelAttemptPhase.UNCERTAIN, h.store.channelAttempts.values.single().phase)
            assertEquals(NotificationActionReceipt.State.UNCERTAIN, receipt.state)
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            assertFalse("settleAndRetireDefinitelyUnsentQueuedChannelAttempt" in h.store.calls)
            h.close()
            val next = ready(this, h.store, generation = 2)
            next.queue().let { it.hydrate(); runCurrent(); it.awaitDrainCompletion() }
            assertFailsWith<QueuedChannelRetainedException> { next.service.sendPendingChannelMessage(saved.id) }
            assertEquals(saved, next.stored(saved.id))
            assertEquals(pending, next.store.pending.values.single())
            assertTrue(next.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
        },
        native("inline_missing_fence_fails_before_wire_without_manufacturing_an_attempt_or_losing_text") {
            val h = ready(this)
            val service = MessageService(h.token, SELF, h.session, h.store, h.signals, h.scope, clock = h.clock)
            try {
                val failure = assertFailsWith<QueuedChannelRetainedException> {
                    service.sendChannelMessage("cannot dispatch", 0u, RADIO)
                }
                assertIs<ChannelDispatchFailure.RetiredSession>(failure.cause)
                val saved = h.store.messages.values.single()
                assertEquals("cannot dispatch", saved.text)
                assertEquals(h.store.channelOccupants.getValue(RADIO to 0.toUByte()), saved.channelOccupantID)
                assertEquals(MessageStatus.FAILED, saved.status)
                assertTrue(h.store.pending.isEmpty())
                assertTrue(h.store.channelAttempts.isEmpty())
                assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            } finally { service.close() }
        },
        native("legacy_stale_origin_is_rejected_without_rebinding_to_the_replacement_occupant") {
            val h = ready(this)
            val row = h.message(MessageStatus.PENDING, 0u, "old intent")
            val replacement = java.util.UUID.randomUUID()
            h.store.channelOccupants[RADIO to 0.toUByte()] = replacement
            assertFailsWith<QueuedChannelRetainedException> { h.service.sendPendingChannelMessage(row.id) }
            assertEquals(row.channelOccupantID, h.stored(row.id).channelOccupantID)
            assertEquals(replacement, h.store.channelOccupants[RADIO to 0.toUByte()])
            assertEquals(row.text, h.stored(row.id).text)
            assertTrue(h.store.channelAttempts.isEmpty())
            assertTrue(h.store.pending.isEmpty())
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
        },
        native("inline_slot_replacement_during_creation_keeps_captured_origin_and_never_dispatches_to_replacement") {
            val h = ready(this)
            val captured = h.store.channelOccupants.getValue(RADIO to 0.toUByte())
            val replacement = java.util.UUID.randomUUID()
            h.store.before = {
                if (it == "saveMessage") h.store.channelOccupants[RADIO to 0.toUByte()] = replacement
            }
            assertFailsWith<QueuedChannelRetainedException> {
                h.service.sendChannelMessage("captured original", 0u, RADIO)
            }
            val saved = h.store.messages.values.single()
            assertEquals(captured, saved.channelOccupantID)
            assertEquals("captured original", saved.text)
            assertEquals(replacement, h.store.channelOccupants[RADIO to 0.toUByte()])
            assertEquals(MessageStatus.FAILED, saved.status)
            assertTrue(h.store.pending.isEmpty())
            assertTrue(h.store.channelAttempts.isEmpty())
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
        },
        native("inline_unsent_retirement_failure_is_not_safe_notification_replay_or_a_failed_row_receipt") {
            val h = ready(this)
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            h.transport.beforeReply = {
                if (it[0] == CommandCode.RESET_PATH.rawValue) { held.complete(Unit); release.await() }
            }
            val prior = backgroundScope.async { h.session.resetPath(TARGET) }
            held.await()
            val storage = storageFailure()
            h.store.faults["settleAndRetireDefinitelyUnsentQueuedChannelAttempt"] = storage
            val receipt = NotificationActionReceipt()
            val call = backgroundScope.async(receipt) {
                h.service.sendChannelMessage("retain cleanup failure", 0u, RADIO)
            }
            runCurrent(); call.cancelAndJoin()
            assertFailsWith<CancellationException> { call.await() }
            val saved = h.store.messages.values.single()
            assertEquals("retain cleanup failure", saved.text)
            assertEquals(MessageStatus.PENDING, saved.status)
            assertEquals(QueuedChannelAttemptPhase.DISPATCHING, h.store.channelAttempts.values.single().phase)
            assertEquals(QueuedChannelDispatchState.DISPATCHING, h.store.pending.values.single().channelDispatchState)
            assertEquals(NotificationActionReceipt.State.UNCERTAIN, receipt.state)
            assertTrue(receipt.preventsReplay)
            assertTrue(h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
                it.cause is QueuedChannelRetainedException && it.cause.cause === storage
            })
            assertFailsWith<ChannelDispatchFailure.UnsettledDispatch> {
                h.channelFence.withMutation(h.channelLease) { fail("Cleanup failure cannot release the radio") }
            }
            h.store.faults.clear()
            release.complete(Unit); prior.await(); runCurrent()
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            h.close()
            val next = ready(this, h.store, generation = 2)
            next.queue().let { it.hydrate(); runCurrent(); it.awaitDrainCompletion() }
            assertEquals(saved, next.stored(saved.id))
            assertEquals(QueuedChannelDispatchState.DISPATCHING, next.store.pending.values.single().channelDispatchState)
            assertTrue(next.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
        },
        native("inline_cancel_after_atomic_admission_retires_before_protocol_entry_without_restart_replay") {
            val h = ready(this)
            val admitted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            h.store.after = {
                if (it == "beginInlineChannelAttempt") { admitted.complete(Unit); release.await() }
            }
            val receipt = NotificationActionReceipt()
            val call = backgroundScope.async(receipt) {
                h.service.sendChannelMessage("cancel before protocol", 0u, RADIO)
            }
            admitted.await()
            val message = h.store.messages.values.single()
            val pendingID = h.store.pending.values.single().id
            h.store.messages[EntityKey(RADIO, message.id)] = message.copy(failureSeen = true)
            assertEquals(QueuedChannelDispatchState.DISPATCHING, h.store.pending.values.single().channelDispatchState)
            assertEquals(QueuedChannelAttemptPhase.DISPATCHING, h.store.channelAttempts.values.single().phase)
            assertEquals(NotificationActionReceipt.State.IN_FLIGHT, receipt.state)
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            call.cancel(); runCurrent()
            assertFalse(call.isCompleted, "Cancellation must await noncancellable admission and exact cleanup")
            release.complete(Unit); call.join()
            assertFailsWith<CancellationException> { call.await() }
            val attempt = h.store.channelAttempts.values.single()
            assertEquals(pendingID, attempt.key.pendingID)
            assertEquals(message.id, attempt.key.messageID)
            assertEquals(message.channelOccupantID, attempt.channelOccupantID)
            assertEquals(QueuedChannelAttemptPhase.DEFINITELY_UNSENT, attempt.phase)
            assertEquals(message.copy(status = MessageStatus.FAILED), h.stored(message.id))
            assertTrue(h.store.pending.isEmpty())
            assertEquals(NotificationActionReceipt.State.READY, receipt.state)
            assertFalse(receipt.preventsReplay)
            assertFalse("insertPendingSendAssigningSequence" in h.store.calls)
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            h.store.after = null
            h.close()
            val next = ready(this, h.store, generation = 2)
            next.queue().let { it.hydrate(); runCurrent(); it.awaitDrainCompletion() }
            assertTrue(next.store.pending.isEmpty())
            assertEquals(attempt, next.store.channelAttempts.values.single())
            assertEquals(message.copy(status = MessageStatus.FAILED), next.stored(message.id))
            assertTrue(next.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
        },
        native("inline_accepted_completion_failure_resumes_exact_attempt_after_restart_without_second_wire") {
            val h = ready(this)
            h.store.faults["completeAcceptedQueuedChannelAttempt"] = storageFailure()
            val receipt = NotificationActionReceipt()
            assertFailsWith<QueuedChannelRetainedException> {
                withContext(receipt) { h.service.sendChannelMessage("accepted original", 0u, RADIO) }
            }
            val message = h.store.messages.values.single()
            val pending = h.store.pending.values.single()
            val accepted = h.store.channelAttempts.values.single()
            assertEquals(QueuedChannelDispatchState.ACCEPTED, pending.channelDispatchState)
            assertEquals(QueuedChannelAttemptPhase.ACCEPTED, accepted.phase)
            assertFalse(accepted.isCompleted)
            assertTrue(receipt.isAccepted)
            assertEquals(MessageStatus.PENDING, message.status)
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            h.store.faults.clear(); h.close()

            val next = ready(this, h.store, generation = 2)
            next.service.sendPendingChannelMessage(message.id)
            assertEquals(message.copy(status = MessageStatus.SENT), next.stored(message.id))
            assertEquals(accepted.copy(isCompleted = true), next.store.channelAttempts.values.single())
            assertTrue(next.store.pending.isEmpty())
            assertTrue(next.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
        },
        native("legacy_resend_accepted_completion_retry_returns_recorded_wire_stamp_and_increments_exactly_once") {
            val h = ready(this)
            val original = h.message(MessageStatus.FAILED, 0u, "resend once", 100u)
            val row = original.copy(heardRepeats = 3)
            h.store.messages[EntityKey(RADIO, row.id)] = row
            val events = h.service.statusEvents()
            h.store.faults["completeAcceptedQueuedChannelAttempt"] = storageFailure()
            assertFailsWith<QueuedChannelRetainedException> { h.service.resendChannelMessage(row.id) }
            val attempt = h.store.channelAttempts.values.single()
            assertEquals(QueuedChannelAttemptPhase.ACCEPTED, attempt.phase)
            assertEquals(BASE_TIME.epochSecond.toUInt(), attempt.wireTimestamp)
            assertEquals(row, h.stored(row.id))
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            h.store.faults.clear()
            assertEquals(attempt.wireTimestamp, h.service.resendChannelMessage(row.id))
            assertEquals(row.copy(status = MessageStatus.SENT, timestamp = attempt.wireTimestamp,
                sendCount = row.sendCount + 1, heardRepeats = 0), h.stored(row.id))
            assertTrue(h.store.pending.isEmpty())
            assertTrue(h.store.channelAttempts.values.single().isCompleted)
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            assertEquals(listOf(MessageStatusEvent.Resent(row.id)), statuses(h.service, events))
        },
        native("inline_lost_completion_return_reconciles_exact_completed_attempt_not_a_new_send") {
            val h = ready(this)
            h.store.after = { if (it == "completeAcceptedQueuedChannelAttempt") throw storageFailure() }
            val sent = h.service.sendChannelMessage("committed once", 0u, RADIO)
            assertEquals(MessageStatus.SENT, h.stored(sent.id).status)
            assertTrue(h.store.pending.isEmpty())
            assertTrue(h.store.channelAttempts.values.single().isCompleted)
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            h.store.after = null
        },
    )

    @TestFactory fun admissionFailures() = listOf(false, true).flatMap { lostReturn ->
        listOf(false, true).map { cancelCaller ->
            native("inline_atomic_admission_lost_return_${lostReturn}_caller_cancelled_$cancelCaller") {
                val h = ready(this)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val failure = storageFailure()
                val boundary: suspend (String) -> Unit = {
                    if (it == "beginInlineChannelAttempt") {
                        entered.complete(Unit)
                        release.await()
                        throw failure
                    }
                }
                if (lostReturn) h.store.after = boundary else h.store.before = boundary
                val receipt = NotificationActionReceipt()
                val call = backgroundScope.async(receipt) {
                    h.service.sendChannelMessage("retain admission fault", 0u, RADIO)
                }
                entered.await()
                val message = h.store.messages.values.single()
                val pending = h.store.pending.values.singleOrNull()
                val attempt = h.store.channelAttempts.values.singleOrNull()
                if (lostReturn) {
                    assertEquals(QueuedChannelDispatchState.DISPATCHING, assertNotNull(pending).channelDispatchState)
                    assertEquals(QueuedChannelAttemptPhase.DISPATCHING, assertNotNull(attempt).phase)
                    assertEquals(message.id, pending.messageID)
                    assertEquals(pending.id, attempt.key.pendingID)
                    assertEquals(message.channelOccupantID, attempt.channelOccupantID)
                } else {
                    assertNull(pending)
                    assertNull(attempt)
                }
                assertEquals(NotificationActionReceipt.State.IN_FLIGHT, receipt.state)
                assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
                if (cancelCaller) { call.cancel(); runCurrent() }
                release.complete(Unit)
                if (cancelCaller) {
                    call.join()
                    assertFailsWith<CancellationException> { call.await() }
                } else {
                    assertSame(failure, assertFailsWith<QueuedChannelRetainedException> { call.await() }.cause)
                }
                assertEquals(message, h.stored(message.id))
                assertEquals(MessageStatus.PENDING, message.status)
                assertEquals("retain admission fault", message.text)
                assertEquals(pending, h.store.pending.values.singleOrNull())
                assertEquals(attempt, h.store.channelAttempts.values.singleOrNull())
                assertEquals(NotificationActionReceipt.State.UNCERTAIN, receipt.state)
                assertTrue(receipt.preventsReplay)
                assertTrue(h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any { it.cause === failure })
                assertFalse("insertPendingSendAssigningSequence" in h.store.calls)
                assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
                h.store.before = null; h.store.after = null
                h.close()

                val next = ready(this, h.store, generation = 2)
                next.queue().let { it.hydrate(); runCurrent(); it.awaitDrainCompletion() }
                assertEquals(message, next.stored(message.id))
                assertEquals(pending, next.store.pending.values.singleOrNull())
                assertEquals(attempt, next.store.channelAttempts.values.singleOrNull())
                assertTrue(next.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
                assertTrue(next.sends(CommandCode.GET_CHANNEL).isEmpty())
            }
        }
    }

    @TestFactory fun legacyAndSettlementFailures() = listOf(false, true).flatMap { resend ->
        listOf(
            native("legacy_inline_resend_${resend}_raw_ERROR2_has_one_wire_and_keeps_original_projection") {
                val h = ready(this)
                val row = h.message(if (resend) MessageStatus.FAILED else MessageStatus.PENDING,
                    0u, "legacy original", 100u)
                val events = h.service.statusEvents()
                h.transport.channelError = 2u
                assertFailsWith<QueuedChannelRetainedException> {
                    if (resend) h.service.resendChannelMessage(row.id) else h.service.sendPendingChannelMessage(row.id)
                }
                assertEquals(row, h.stored(row.id))
                val pending = h.store.pending.values.single()
                val attempt = h.store.channelAttempts.values.single()
                assertEquals(row.id, pending.messageID)
                assertEquals(resend, pending.isResend)
                assertEquals(row.channelOccupantID, pending.channelOccupantID)
                assertEquals(row.channelOccupantID, attempt.channelOccupantID)
                assertEquals(QueuedChannelDispatchState.UNCERTAIN, pending.channelDispatchState)
                assertEquals(QueuedChannelAttemptPhase.UNCERTAIN, attempt.phase)
                assertEquals(if (resend) BASE_TIME.epochSecond.toUInt() else row.timestamp, attempt.wireTimestamp)
                assertFailsWith<QueuedChannelRetainedException> {
                    if (resend) h.service.resendChannelMessage(row.id) else h.service.sendPendingChannelMessage(row.id)
                }
                advanceTimeBy(60_000); runCurrent()
                assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
                assertTrue(h.sends(CommandCode.GET_CHANNEL).isEmpty())
                assertTrue(statuses(h.service, events).isEmpty())
            },
            native("inline_${if (resend) "raw_error" else "accepted"}_settlement_failure_retains_dispatching_not_retryable_failure") {
                val h = ready(this)
                if (resend) h.transport.channelError = 2u
                h.store.faults["settleQueuedChannelAttempt"] = storageFailure()
                val receipt = NotificationActionReceipt()
                assertFailsWith<QueuedChannelRetainedException> {
                    withContext(receipt) { h.service.sendChannelMessage("unsettled text", 0u, RADIO) }
                }
                val row = h.store.messages.values.single()
                assertEquals(MessageStatus.PENDING, row.status)
                assertEquals(QueuedChannelDispatchState.DISPATCHING, h.store.pending.values.single().channelDispatchState)
                assertEquals(QueuedChannelAttemptPhase.DISPATCHING, h.store.channelAttempts.values.single().phase)
                assertEquals(if (resend) NotificationActionReceipt.State.UNCERTAIN else
                    NotificationActionReceipt.State.ACCEPTED, receipt.state)
                assertTrue(receipt.preventsReplay)
                assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
                h.store.faults.clear(); h.close()
                val next = ready(this, h.store, generation = 2)
                next.queue().let { it.hydrate(); runCurrent(); it.awaitDrainCompletion() }
                assertFailsWith<QueuedChannelRetainedException> { next.service.sendPendingChannelMessage(row.id) }
                assertEquals(row, next.stored(row.id))
                assertTrue(next.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            },
        )
    }
}
