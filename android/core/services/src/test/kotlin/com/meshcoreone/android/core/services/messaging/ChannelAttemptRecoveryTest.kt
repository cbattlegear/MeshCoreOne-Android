// AndroidOnly: WP-208 Exact submission/attempt recovery through real messaging services and protocol sessions.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.CommandCode
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelAttemptRecoveryTest {
    @TestFactory fun recovery() = listOf(
        native("two-ready-siblings-are-retained-before-count-or-wire") {
            val h = Harness(this); h.start()
            val message = h.message(MessageStatus.PENDING, 0u)
            val first = h.pending(message)
            val second = h.pending(message, isResend = true)
            val queue = h.queue()
            queue.hydrate(); runCurrent(); queue.awaitDrainCompletion()
            assertEquals(setOf(first, second), h.store.pending.values.toSet())
            assertEquals(message, h.stored(message.id))
            assertTrue(h.store.channelAttempts.isEmpty())
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            assertFalse(h.store.calls.any { it == "incrementReadyQueuedChannelAttemptCount" })
            val conflicts = h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
                .filter { it.operation.startsWith("channelRetained.") }
            assertTrue(conflicts.any {
                (it.cause.cause as? QueuedChannelAttemptRejected)?.reason == QueuedChannelAttemptConflict.RETAINED_SUBMISSION
            })
            queue.shutdown()
            assertEquals(setOf(first, second), h.store.pending.values.toSet())
        },
        native("ready-sibling-arriving-while-fence-waits-is-retained-before-begin") {
            val h = Harness(this); h.start()
            val message = h.message(MessageStatus.PENDING, 0u)
            val first = h.pending(message)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val holder = launch {
                h.channelFence.withMutation(h.channelLease) { entered.complete(Unit); release.await() }
            }
            entered.await()
            val send = async {
                assertFailsWith<QueuedChannelRetainedException> {
                    h.service.sendQueuedChannel(first.id, requireNotNull(first.channelMessageEnvelope()), true)
                }
            }
            runCurrent()
            val second = h.pending(message, isResend = true)
            release.complete(Unit); holder.join()
            val retained = send.await()
            assertEquals(QueuedChannelAttemptConflict.RETAINED_SUBMISSION,
                (retained.cause?.cause as? QueuedChannelAttemptRejected)?.reason)
            assertEquals(setOf(first, second), h.store.pending.values.toSet())
            assertTrue(h.store.channelAttempts.isEmpty())
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
        },
        native("accepted-plus-ready-sibling-keeps-both-without-cleanup-or-wire") {
            val h = Harness(this); h.start()
            val message = h.message(MessageStatus.PENDING, 0u)
            val first = h.pending(message)
            val second = h.pending(message, isResend = true)
            // Models the retained state reached when acceptance precedes sibling-rejecting completion.
            val accepted = seedAccepted(h, first, message.timestamp)
            val rows = h.store.pending.values.toSet()
            val queue = h.queue()
            queue.hydrate(); runCurrent(); queue.awaitDrainCompletion()
            assertEquals(rows, h.store.pending.values.toSet())
            assertTrue(h.store.pending.values.any { it.id == second.id })
            assertEquals(accepted, h.store.channelAttempts.getValue(accepted.key))
            assertEquals(message, h.stored(message.id))
            assertFalse(h.store.calls.any { it == "completeAcceptedQueuedChannelAttempt" ||
                it == "incrementReadyQueuedChannelAttemptCount" })
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
        },
        native("single-ready-submission-still-sends-and-completes-once") {
            val h = Harness(this); h.start()
            val message = h.message(MessageStatus.PENDING, 0u)
            val row = h.pending(message)
            val queue = h.queue()
            queue.hydrate(); runCurrent(); queue.awaitDrainCompletion()
            assertTrue(h.store.pending.isEmpty())
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            val accepted = h.store.channelAttempts.values.single()
            assertEquals(row.id, accepted.key.pendingID)
            assertTrue(accepted.isCompleted)
            assertEquals(QueuedChannelAttemptPhase.ACCEPTED, accepted.phase)
        },
        *listOf(null, QueuedChannelDispatchState.DISPATCHING, QueuedChannelDispatchState.UNCERTAIN).map { state ->
            native("retained-$state-never-bumps-queries-sends-remaps-or-fails") {
                val h = Harness(this); h.start()
                val message = h.message(MessageStatus.PENDING, 0u)
                val row = h.pending(message)
                h.store.pending[EntityKey(RADIO, row.id)] = row.copy(channelDispatchState = state)
                val events = h.service.statusEvents()
                val q = h.queue(query = MessagingChannelQuery { fail("Retained submissions must not query") })
                q.hydrate(); runCurrent(); q.awaitDrainCompletion()
                assertEquals(0L, h.store.pending.getValue(EntityKey(RADIO, row.id)).attemptCount)
                assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
                assertFalse(h.store.calls.any { it in setOf("beginQueuedChannelAttempt", "incrementPendingSendAttemptCount",
                    "incrementReadyQueuedChannelAttemptCount", "updateMessageStatusUnlessDelivered",
                    "retireDefinitelyUnsentQueuedChannelAttempt") })
                assertEquals(message, h.stored(message.id))
                assertTrue(statuses(h.service, events).isEmpty())
                q.shutdown()
                assertEquals(message, h.stored(message.id))
            }
        }.toTypedArray(),
        native("accepted-resend-restart-is-cleanup-only-and-bookkeeping-is-once") {
            val h = Harness(this); h.start()
            val message = h.message(MessageStatus.SENT, 0u)
            val row = h.pending(message, isResend = true)
            val accepted = seedAccepted(h, row, 555u)
            val q = h.queue()
            q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            assertTrue(h.store.pending.isEmpty())
            assertEquals(2L, h.stored(message.id).sendCount)
            assertEquals(555u, h.stored(message.id).timestamp)
            h.service.completeQueuedChannel(accepted.key)
            assertEquals(2L, h.stored(message.id).sendCount)
            assertTrue(h.store.channelAttempts.getValue(accepted.key).isCompleted)
        },
        native("exact-persisted-pending-id-reaches-prewire-cas") {
            val h = Harness(this); h.start()
            val message = h.message(MessageStatus.PENDING, 0u)
            val row = h.pending(message)
            val accepted = h.service.sendQueuedChannel(row.id, requireNotNull(row.channelMessageEnvelope()), true)
            assertEquals(row.id, accepted.key.pendingID)
            assertEquals(message.id, accepted.key.messageID)
            assertEquals(row.channelOccupantID, accepted.channelOccupantID)
            assertEquals(QueuedChannelAttemptPhase.ACCEPTED, accepted.phase)
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
        },
        native("not-found-without-definite-unsent-proof-cannot-enter-physical-backoff") {
            val h = Harness(this, MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelay = 0.001)))
            h.start(); h.transport.channelError = 2u
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            assertFailsWith<QueuedChannelRetainedException> {
                h.service.sendQueuedChannel(row.id, requireNotNull(row.channelMessageEnvelope()), true)
            }
            val attempts = h.store.channelAttempts.values.toList()
            assertEquals(1, attempts.size)
            assertTrue(attempts.all { it.key.pendingID == row.id && it.phase == QueuedChannelAttemptPhase.UNCERTAIN })
            assertEquals(QueuedChannelDispatchState.UNCERTAIN, h.store.pending.getValue(EntityKey(RADIO, row.id)).channelDispatchState)
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
        },
        native("begin-commit-return-loss-retains-dispatching-with-zero-wire") {
            val h = Harness(this); h.start()
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            h.store.after = { if (it == "beginQueuedChannelAttempt") throw storageFailure() }
            assertFailsWith<QueuedChannelRetainedException> {
                h.service.sendQueuedChannel(row.id, requireNotNull(row.channelMessageEnvelope()), true)
            }
            h.store.after = null
            val q = h.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertEquals(QueuedChannelDispatchState.DISPATCHING, h.store.pending.getValue(EntityKey(RADIO, row.id)).channelDispatchState)
            assertEquals(1, h.store.channelAttempts.size)
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
        },
        native("crash-after-dispatching-before-wire-does-not-replay-on-replacement") {
            val first = Harness(this); first.start()
            val row = first.pending(first.message(MessageStatus.PENDING, 0u))
            first.store.after = { if (it == "beginQueuedChannelAttempt") throw CancellationException("Simulated process loss") }
            assertFailsWith<CancellationException> {
                first.service.sendQueuedChannel(row.id, requireNotNull(row.channelMessageEnvelope()), true)
            }
            first.store.after = null; first.close()
            val next = Harness(this, store = first.store, generation = 2); next.start()
            val q = next.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertTrue(first.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            assertTrue(next.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            assertEquals(QueuedChannelDispatchState.DISPATCHING, next.store.pending.getValue(EntityKey(RADIO, row.id)).channelDispatchState)
        },
        native("begin-and-readback-failures-preserve-the-original-cause-without-wire") {
            val h = Harness(this); h.start()
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            val beginFailure = storageFailure()
            val readFailure = storageFailure()
            h.store.after = { if (it == "beginQueuedChannelAttempt") throw beginFailure }
            h.store.faults["fetchQueuedChannelAttempt"] = readFailure
            val retained = assertFailsWith<QueuedChannelRetainedException> {
                h.service.sendQueuedChannel(row.id, requireNotNull(row.channelMessageEnvelope()), true)
            }
            assertSame(beginFailure, retained.cause)
            assertTrue(beginFailure.suppressed.any { it === readFailure })
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            assertEquals(QueuedChannelDispatchState.DISPATCHING, h.store.pending.getValue(EntityKey(RADIO, row.id)).channelDispatchState)
        },
        native("accepted-settlement-return-loss-reads-back-exact-key-and-never-replays") {
            val h = Harness(this); h.start()
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            h.store.after = { if (it == "settleQueuedChannelAttempt") throw storageFailure() }
            val accepted = h.service.sendQueuedChannel(row.id, requireNotNull(row.channelMessageEnvelope()), true)
            assertEquals(QueuedChannelAttemptPhase.ACCEPTED, accepted.phase)
            h.store.after = null
            val q = h.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            assertTrue(h.store.pending.isEmpty())
        },
        *listOf(false, true).map { acceptedResponse ->
            native("settlement-and-readback-return-loss-accepted-$acceptedResponse-replacement-restart-keeps-exact-evidence") {
                val first = Harness(this); first.start()
                val message = first.message(MessageStatus.PENDING, 0u)
                val row = first.pending(message)
                if (!acceptedResponse) first.transport.channelError = 2u
                val settlementFailure = storageFailure()
                val readFailure = storageFailure()
                first.store.after = {
                    if (it == "settleQueuedChannelAttempt") {
                        first.store.faults["fetchQueuedChannelAttempt"] = readFailure
                        throw settlementFailure
                    }
                }
                val retained = assertFailsWith<QueuedChannelRetainedException> {
                    first.service.sendQueuedChannel(row.id, requireNotNull(row.channelMessageEnvelope()), true)
                }
                assertSame(settlementFailure, retained.cause)
                assertTrue(settlementFailure.suppressed.any { it === readFailure })
                val attempt = first.store.channelAttempts.values.single()
                val phase = if (acceptedResponse) QueuedChannelAttemptPhase.ACCEPTED else QueuedChannelAttemptPhase.UNCERTAIN
                assertEquals(phase, attempt.phase)
                assertEquals(row.id, attempt.key.pendingID)
                assertEquals(message.id, attempt.key.messageID)
                assertEquals(message.channelOccupantID, attempt.channelOccupantID)
                assertEquals(attempt.key.attemptID, first.store.pending.getValue(EntityKey(RADIO, row.id)).channelAttemptID)
                assertEquals(1, first.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
                first.store.after = null
                first.store.faults.clear()
                first.close()

                val replacementOccupant = UUID.randomUUID()
                first.store.channelOccupants[RADIO to 0u.toUByte()] = replacementOccupant
                val next = Harness(this, store = first.store, generation = 2); next.start()
                val events = next.service.statusEvents()
                val queue = next.queue(query = MessagingChannelQuery { fail("Settlement evidence must not trigger a channel query") })
                queue.hydrate(); runCurrent(); queue.awaitDrainCompletion()
                queue.hydrate(); runCurrent(); queue.awaitDrainCompletion()
                assertTrue(next.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
                assertFalse(next.store.calls.any { it == "incrementReadyQueuedChannelAttemptCount" })
                assertEquals(1, next.store.channelAttempts.size)
                val savedAttempt = next.store.channelAttempts.getValue(attempt.key)
                assertEquals(phase, savedAttempt.phase)
                assertEquals(message.channelOccupantID, savedAttempt.channelOccupantID)
                assertEquals(replacementOccupant, next.store.channelOccupants[RADIO to 0u.toUByte()])
                assertEquals(message.channelOccupantID, next.stored(message.id).channelOccupantID)
                assertEquals(message.text, next.stored(message.id).text)
                if (acceptedResponse) {
                    assertTrue(savedAttempt.isCompleted)
                    assertTrue(next.store.pending.isEmpty())
                    assertEquals(MessageStatus.SENT, next.stored(message.id).status)
                } else {
                    assertEquals(attempt, savedAttempt)
                    assertEquals(message, next.stored(message.id))
                    val pending = next.store.pending.getValue(EntityKey(RADIO, row.id))
                    assertEquals(QueuedChannelDispatchState.UNCERTAIN, pending.channelDispatchState)
                    assertEquals(0L, pending.attemptCount)
                    assertEquals(row.channelOccupantID, pending.channelOccupantID)
                    assertTrue(statuses(next.service, events).isEmpty())
                }
                queue.shutdown()
                assertEquals(savedAttempt, next.store.channelAttempts.getValue(attempt.key))
            }
        }.toTypedArray(),
        native("ok-before-settlement-rollback-retains-unknown-without-second-wire") {
            val h = Harness(this); h.start()
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            h.store.faults["settleQueuedChannelAttempt"] = storageFailure()
            assertFailsWith<QueuedChannelRetainedException> {
                h.service.sendQueuedChannel(row.id, requireNotNull(row.channelMessageEnvelope()), true)
            }
            h.store.faults.clear()
            val q = h.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            assertEquals(QueuedChannelDispatchState.DISPATCHING, h.store.pending.getValue(EntityKey(RADIO, row.id)).channelDispatchState)
            assertEquals(MessageStatus.PENDING, h.stored(row.messageID).status)
        },
        native("caller-cancellation-after-ok-cannot-cancel-accepted-settlement") {
            val h = Harness(this); h.start()
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            h.store.before = { if (it == "settleQueuedChannelAttempt") { entered.complete(Unit); release.await() } }
            val send = async { h.service.sendQueuedChannel(row.id, requireNotNull(row.channelMessageEnvelope()), true) }
            entered.await(); send.cancel(); release.complete(Unit); send.join()
            assertEquals(QueuedChannelDispatchState.ACCEPTED, h.store.pending.getValue(EntityKey(RADIO, row.id)).channelDispatchState)
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            assertTrue(h.store.channelAttempts.values.all { it.phase == QueuedChannelAttemptPhase.ACCEPTED })
        },
        native("cancellation-before-fence-admission-does-not-begin-an-attempt") {
            val h = Harness(this); h.start()
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val holder = launch { h.channelFence.withMutation(h.channelLease) { entered.complete(Unit); release.await() } }
            entered.await()
            val send = async { h.service.sendQueuedChannel(row.id, requireNotNull(row.channelMessageEnvelope()), true) }
            runCurrent(); send.cancelAndJoin(); release.complete(Unit); holder.join()
            assertTrue(h.store.channelAttempts.isEmpty())
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
        },
        native("accepted-cleanup-return-loss-is-read-back-without-duplicate-resend-count") {
            val h = Harness(this); h.start()
            val row = h.pending(h.message(MessageStatus.SENT, 0u), isResend = true)
            val accepted = seedAccepted(h, row, 777u)
            h.store.after = { if (it == "completeAcceptedQueuedChannelAttempt") throw storageFailure() }
            h.service.completeQueuedChannel(accepted.key)
            h.service.completeQueuedChannel(accepted.key)
            assertEquals(2L, h.stored(row.messageID).sendCount)
            assertTrue(h.store.pending.isEmpty())
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
        },
        native("accepted-cleanup-rollback-retries-only-cleanup-on-a-new-generation") {
            val first = Harness(this); first.start()
            val row = first.pending(first.message(MessageStatus.SENT, 0u), isResend = true)
            seedAccepted(first, row, 888u)
            first.store.faults["completeAcceptedQueuedChannelAttempt"] = storageFailure()
            val q = first.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertEquals(1L, first.stored(row.messageID).sendCount)
            assertEquals(QueuedChannelDispatchState.ACCEPTED, first.store.pending.getValue(EntityKey(RADIO, row.id)).channelDispatchState)
            first.close(); first.store.faults.clear()
            val next = Harness(this, store = first.store, generation = 2); next.start()
            val replacement = next.queue(); replacement.hydrate(); runCurrent(); replacement.awaitDrainCompletion()
            assertEquals(2L, next.stored(row.messageID).sendCount)
            assertTrue(next.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
        },
        native("explicit-resend-preserves-origin-and-replacement-occupant-produces-zero-wire") {
            val h = Harness(this); h.start()
            val message = h.message(MessageStatus.SENT, 0u)
            val row = h.pending(message, isResend = true)
            h.store.channelOccupants[RADIO to 0u.toUByte()] = UUID.randomUUID()
            val q = h.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            assertEquals(message, h.stored(message.id))
            assertEquals(row.channelOccupantID, h.store.pending.getValue(EntityKey(RADIO, row.id)).channelOccupantID)
            assertTrue(h.store.channelAttempts.isEmpty())
        },
        native("resampled-envelope-cannot-rebind-saved-origin-even-for-send-again") {
            val h = Harness(this); h.start()
            val row = h.pending(h.message(MessageStatus.PENDING, 0u), isResend = true)
            val replacement = UUID.randomUUID()
            h.store.channelOccupants[RADIO to 0u.toUByte()] = replacement
            val rebound = row.copy(channelOccupantID = replacement)
            h.store.pending[EntityKey(RADIO, row.id)] = rebound
            assertFailsWith<QueuedChannelRetainedException> {
                h.service.sendQueuedChannel(row.id, requireNotNull(rebound.channelMessageEnvelope()), true)
            }
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            assertTrue(h.store.channelAttempts.isEmpty())
        },
        native("unbound-send-again-after-restart-retains-original-text-and-pending-status") {
            val first = Harness(this); first.start()
            val message = first.message(MessageStatus.PENDING, 0u).copy(channelOccupantID = null)
            first.store.saveMessage(message)
            val row = first.pending(message, isResend = true)
            first.close()
            val next = Harness(this, store = first.store, generation = 2); next.start()
            next.store.channelOccupants[RADIO to 0u.toUByte()] = UUID.randomUUID()
            val q = next.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertEquals(message, next.stored(message.id))
            assertEquals(row, next.store.pending.getValue(EntityKey(RADIO, row.id)))
            assertTrue(next.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
        },
        native("accepted-cleanup-cannot-remove-a-newer-submission") {
            val h = Harness(this); h.start()
            val row = h.pending(h.message(MessageStatus.SENT, 0u), isResend = true)
            val accepted = seedAccepted(h, row, 999u)
            val newer = row.copy(id = UUID.randomUUID(), channelDispatchState = QueuedChannelDispatchState.READY)
            h.store.pending[EntityKey(RADIO, newer.id)] = newer
            assertFailsWith<QueuedChannelRetainedException> { h.service.completeQueuedChannel(accepted.key) }
            assertEquals(setOf(row.id, newer.id), h.store.pending.values.map { it.id }.toSet())
            assertEquals(1L, h.stored(row.messageID).sendCount)
        },
        native("raw-device-error-does-not-masquerade-as-an-atomic-retirement-receipt") {
            val h = Harness(this, MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 0)))
            h.start(); h.transport.channelError = 5u
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            val events = h.service.statusEvents()
            val q = h.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertEquals(QueuedChannelDispatchState.UNCERTAIN, h.store.pending.getValue(EntityKey(RADIO, row.id)).channelDispatchState)
            assertEquals(MessageStatus.PENDING, h.stored(row.messageID).status)
            assertTrue(statuses(h.service, events).isEmpty())
            assertFalse(h.store.calls.contains("deletePendingSendsForMessage"))
            assertEquals(0, h.store.calls.count { it == "retireDefinitelyUnsentQueuedChannelAttempt" })
        },
        native("newer-submission-racing-terminal-retirement-is-not-marked-failed") {
            val h = Harness(this, MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 0)))
            h.start()
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            val definite = seedDefinitelyUnsent(h, row)
            h.store.before = { operation ->
                if (operation == "retireDefinitelyUnsentQueuedChannelAttempt") {
                    val newer = row.copy(id = UUID.randomUUID(), channelDispatchState = QueuedChannelDispatchState.READY)
                    h.store.pending[EntityKey(RADIO, newer.id)] = newer
                }
            }
            val events = h.service.statusEvents()
            assertFailsWith<PersistenceStoreException> {
                h.store.retireDefinitelyUnsentQueuedChannelAttempt(definite.key)
            }
            assertEquals(2, h.store.pending.size)
            assertEquals(MessageStatus.PENDING, h.stored(row.messageID).status)
            assertTrue(statuses(h.service, events).isEmpty())
        },
        native("ambiguous-protocol-timeout-is-uncertain-and-never-automatically-replayed") {
            val h = Harness(this); h.start(); h.transport.holdChannelReplies = true
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            val events = h.service.statusEvents()
            assertFailsWith<QueuedChannelRetainedException> {
                h.service.sendQueuedChannel(row.id, requireNotNull(row.channelMessageEnvelope()), true)
            }
            val q = h.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertEquals(QueuedChannelDispatchState.UNCERTAIN, h.store.pending.getValue(EntityKey(RADIO, row.id)).channelDispatchState)
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            assertEquals(MessageStatus.PENDING, h.stored(row.messageID).status)
            assertTrue(statuses(h.service, events).isEmpty())
        },
        native("fresh-enqueue-carries-the-id-assigned-before-the-durable-insert") {
            val h = Harness(this); h.start()
            val message = h.message(MessageStatus.PENDING, 0u)
            val pendingID = UUID.randomUUID()
            val q = ChatSendQueueService(h.token, h.store, h.service, MessagingChannelQuery { null },
                OutgoingChannelReactionIndexer { _, _, _, _, _ -> }, h.signals, h.scope,
                clock = h.clock, newID = { pendingID })
            try {
                q.enqueueChannel(ChannelMessageEnvelope(message.id, 0u, false, message.text,
                    message.timestamp, null, message.channelOccupantID))
                runCurrent(); q.awaitDrainCompletion()
                assertEquals(pendingID, h.store.channelAttempts.keys.single().pendingID)
                assertTrue(h.store.pending.isEmpty())
                assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            } finally { q.shutdown() }
        },
        native("missing-production-lease-retains-ready-rather-than-minting-admission") {
            val h = Harness(this); h.start()
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            val unbound = MessageService(h.token, SELF, h.session, h.store, h.signals, h.scope, clock = h.clock)
            try {
                assertFailsWith<QueuedChannelRetainedException> {
                    unbound.sendQueuedChannel(row.id, requireNotNull(row.channelMessageEnvelope()), true)
                }
                assertTrue(h.store.channelAttempts.isEmpty())
                assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
                assertEquals(QueuedChannelDispatchState.READY, h.store.pending.getValue(EntityKey(RADIO, row.id)).channelDispatchState)
            } finally { unbound.close() }
        },
        native("sent-status-alone-does-not-suppress-a-genuine-new-send-again-submission") {
            val h = Harness(this); h.start()
            val row = h.pending(h.message(MessageStatus.SENT, 0u), isResend = true)
            val accepted = h.service.sendQueuedChannel(row.id, requireNotNull(row.channelMessageEnvelope()), false)
            h.service.completeQueuedChannel(accepted.key)
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            assertEquals(2L, h.stored(row.messageID).sendCount)
            assertEquals(accepted.wireTimestamp, h.stored(row.messageID).timestamp)
            assertTrue(h.store.pending.isEmpty())
        },
        native("accepted-cleanup-preserves-delivered-status") {
            val h = Harness(this); h.start()
            val row = h.pending(h.message(MessageStatus.DELIVERED, 0u), isResend = true)
            val accepted = seedAccepted(h, row, 999u)
            h.service.completeQueuedChannel(accepted.key)
            assertEquals(MessageStatus.DELIVERED, h.stored(row.messageID).status)
            assertEquals(2L, h.stored(row.messageID).sendCount)
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
        },
        native("lost-terminal-commit-return-never-fabricates-a-failed-event-or-replays") {
            val h = Harness(this, MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 0)))
            h.start()
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            val definite = seedDefinitelyUnsent(h, row)
            val events = h.service.statusEvents()
            h.store.after = { if (it == "retireDefinitelyUnsentQueuedChannelAttempt") throw storageFailure() }
            assertFailsWith<PersistenceStoreException> {
                h.store.retireDefinitelyUnsentQueuedChannelAttempt(definite.key)
            }
            h.store.after = null
            val q = h.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertTrue(h.store.pending.isEmpty())
            assertEquals(MessageStatus.FAILED, h.stored(row.messageID).status)
            assertTrue(statuses(h.service, events).isEmpty())
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
        },
        native("unknown-sibling-blocks-exact-ready-submission-at-prewire-cas") {
            val h = Harness(this); h.start()
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            val unknown = row.copy(id = UUID.randomUUID(), channelDispatchState = null)
            h.store.pending[EntityKey(RADIO, unknown.id)] = unknown
            assertFailsWith<QueuedChannelRetainedException> {
                h.service.sendQueuedChannel(row.id, requireNotNull(row.channelMessageEnvelope()), true)
            }
            assertTrue(h.store.channelAttempts.isEmpty())
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            assertEquals(unknown, h.store.pending.getValue(EntityKey(RADIO, unknown.id)))
            assertEquals(row, h.store.pending.getValue(EntityKey(RADIO, row.id)))
        },
        native("saved-text-change-does-not-send-the-previously-captured-envelope") {
            val h = Harness(this); h.start()
            val message = h.message(MessageStatus.PENDING, 0u)
            val row = h.pending(message)
            val changed = message.copy(text = "different saved text")
            h.store.saveMessage(changed)
            assertFailsWith<QueuedChannelRetainedException> {
                h.service.sendQueuedChannel(row.id, requireNotNull(row.channelMessageEnvelope()), true)
            }
            assertTrue(h.store.channelAttempts.isEmpty())
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            assertEquals(changed, h.stored(row.messageID))
            assertEquals(row, h.store.pending.getValue(EntityKey(RADIO, row.id)))
        },
        native("replacement-before-exact-count-never-bumps-or-dispatches-the-successor") {
            val h = Harness(this); h.start()
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            val replacement = row.copy(id = UUID.randomUUID())
            h.store.before = {
                if (it == "incrementReadyQueuedChannelAttemptCount") {
                    h.store.pending.remove(EntityKey(RADIO, row.id))
                    h.store.pending[EntityKey(RADIO, replacement.id)] = replacement
                }
            }
            val q = h.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertEquals(listOf(replacement), h.store.pending.values.toList())
            assertTrue(h.store.channelAttempts.isEmpty())
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            assertFalse(h.store.calls.contains("incrementPendingSendAttemptCount"))
        },
        native("unknown-transition-before-exact-count-does-not-mutate-or-dispatch") {
            val h = Harness(this); h.start()
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            val unknown = row.copy(channelDispatchState = null)
            h.store.before = {
                if (it == "incrementReadyQueuedChannelAttemptCount") {
                    h.store.pending[EntityKey(RADIO, row.id)] = unknown
                }
            }
            val q = h.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertEquals(unknown, h.store.pending.getValue(EntityKey(RADIO, row.id)))
            assertTrue(h.store.channelAttempts.isEmpty())
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
        },
        native("unknown-sibling-before-exact-count-protects-both-counts-and-wire") {
            val h = Harness(this); h.start()
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            val unknown = row.copy(id = UUID.randomUUID(), channelDispatchState = null)
            h.store.before = {
                if (it == "incrementReadyQueuedChannelAttemptCount") {
                    h.store.pending[EntityKey(RADIO, unknown.id)] = unknown
                }
            }
            val q = h.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertEquals(setOf(row, unknown), h.store.pending.values.toSet())
            assertTrue(h.store.channelAttempts.isEmpty())
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
        },
        native("count-commit-return-loss-does-not-reincrement-or-infer-wire-permission") {
            val h = Harness(this); h.start()
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            h.store.after = { if (it == "incrementReadyQueuedChannelAttemptCount") throw storageFailure() }
            val q = h.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            q.transportDidOpen(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertEquals(1L, h.store.pending.getValue(EntityKey(RADIO, row.id)).attemptCount)
            assertEquals(1, h.store.calls.count { it == "incrementReadyQueuedChannelAttemptCount" })
            assertTrue(h.store.channelAttempts.isEmpty())
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            assertEquals(MessageStatus.PENDING, h.stored(row.messageID).status)
        },
        native("cancelled-count-return-cannot-restart-an-automatic-count-or-wire") {
            val h = Harness(this); h.start()
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            h.store.after = {
                if (it == "incrementReadyQueuedChannelAttemptCount") throw CancellationException("Count committed without receipt")
            }
            val q = h.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertEquals(1L, h.store.pending.getValue(EntityKey(RADIO, row.id)).attemptCount)
            assertEquals(1, h.store.calls.count { it == "incrementReadyQueuedChannelAttemptCount" })
            assertTrue(h.store.channelAttempts.isEmpty())
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
        },
        native("one-drain-count-does-not-authorize-backoff-after-unproved-not-found") {
            val h = Harness(this, MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelay = 0.001)))
            h.start(); h.transport.channelError = 2u
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            val q = h.queue(); q.hydrate(); runCurrent()
            advanceTimeBy(10); runCurrent()
            assertEquals(1L, h.store.pending.getValue(EntityKey(RADIO, row.id)).attemptCount)
            assertEquals(1, h.store.calls.count { it == "incrementReadyQueuedChannelAttemptCount" })
            assertEquals(1, h.store.channelAttempts.size)
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            assertTrue(h.store.channelAttempts.values.all { it.phase == QueuedChannelAttemptPhase.UNCERTAIN })
        },
        native("terminal-notification-stays-bound-to-the-returned-exact-attempt-receipt") {
            val h = Harness(this, MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 0)))
            h.start()
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            val events = h.service.statusEvents()
            val key = seedDefinitelyUnsent(h, row).key
            val receipt = h.store.retireDefinitelyUnsentQueuedChannelAttempt(key)
            h.store.faults["fetchMessage"] = storageFailure()
            h.service.notifyQueuedChannelRetired(key, receipt)
            h.service.notifyQueuedChannelRetired(key, receipt)
            assertEquals(listOf(MessageStatusEvent.Failed(row.messageID)), statuses(h.service, events))
            assertEquals(MessageStatus.FAILED, h.stored(row.messageID).status)
            assertFalse(h.store.calls.contains("updateMessageStatusUnlessDelivered"))
        },
        native("device-error-and-settlement-rollback-retain-both-causes-and-dispatching-evidence") {
            val h = Harness(this); h.start(); h.transport.channelError = 5u
            val row = h.pending(h.message(MessageStatus.PENDING, 0u))
            val storage = storageFailure()
            h.store.faults["settleQueuedChannelAttempt"] = storage
            val retained = assertFailsWith<QueuedChannelRetainedException> {
                h.service.sendQueuedChannel(row.id, requireNotNull(row.channelMessageEnvelope()), true)
            }
            assertSame(storage, retained.cause)
            assertEquals(5u.toUByte(), assertIs<MeshCoreException.DeviceError>(retained.suppressed.single()).code)
            val attempt = h.store.channelAttempts.values.single()
            assertEquals(QueuedChannelAttemptPhase.DISPATCHING, attempt.phase)
            assertEquals(attempt.key.attemptID, h.store.pending.getValue(EntityKey(RADIO, row.id)).channelAttemptID)
            assertEquals(MessageStatus.PENDING, h.stored(row.messageID).status)
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
        },
    ) + listOf(false, true).flatMap { cancelledReturn ->
        listOf(
            native("count-return-loss-cancelled-$cancelledReturn-recreation-changes-first-resend-wire-timestamp-control") {
                for (lostReturn in listOf(false, true)) {
                    val first = Harness(this); first.start()
                    val message = first.message(MessageStatus.SENT, 0u, timestamp = 100u)
                    val row = first.pending(message, isResend = true)
                    if (lostReturn) {
                        first.store.after = {
                            if (it == "incrementReadyQueuedChannelAttemptCount") {
                                if (cancelledReturn) throw CancellationException("Committed count return cancelled")
                                throw storageFailure()
                            }
                        }
                        val q = first.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
                        q.transportDidOpen(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
                        assertEquals(1L, first.store.pending.getValue(EntityKey(RADIO, row.id)).attemptCount)
                        assertEquals(1, first.store.calls.count { it == "incrementReadyQueuedChannelAttemptCount" })
                        assertTrue(first.store.channelAttempts.isEmpty())
                        assertTrue(first.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
                    }
                    first.store.after = null; first.close()
                    val next = Harness(this, store = first.store, generation = 2); next.start()
                    next.store.after = {
                        if (it == "incrementReadyQueuedChannelAttemptCount") {
                            assertEquals(if (lostReturn) 2L else 1L,
                                next.store.pending.getValue(EntityKey(RADIO, row.id)).attemptCount)
                        }
                    }
                    val q = next.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
                    val accepted = next.store.channelAttempts.values.single()
                    assertEquals(row.id, accepted.key.pendingID)
                    assertEquals(message.id, accepted.key.messageID)
                    assertEquals(row.channelOccupantID, accepted.channelOccupantID)
                    assertEquals(QueuedChannelAttemptPhase.ACCEPTED, accepted.phase)
                    assertEquals(1, next.store.calls.count { it == "beginQueuedChannelAttempt" })
                    assertEquals(if (lostReturn) 2 else 1,
                        next.store.calls.count { it == "incrementReadyQueuedChannelAttemptCount" })
                    assertEquals(1, next.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
                    if (lostReturn) assertEquals(message.timestamp, accepted.wireTimestamp)
                    else assertNotEquals(message.timestamp, accepted.wireTimestamp)
                    assertEquals(accepted.wireTimestamp, next.sends(CommandCode.SEND_CHANNEL_MESSAGE).single().readUInt32LE(3))
                    assertEquals(accepted.wireTimestamp, next.stored(message.id).timestamp)
                    assertEquals(2L, next.stored(message.id).sendCount)
                    assertTrue(next.store.pending.isEmpty())
                    next.close()
                }
            },
            native("count-return-loss-cancelled-$cancelledReturn-recreation-threshold-cannot-authorize-uncertain-retirement") {
                for (lostReturn in listOf(false, true)) {
                    val config = MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 0))
                    val first = Harness(this, config); first.start()
                    val message = first.message(MessageStatus.PENDING, 0u)
                    val row = first.pending(message)
                    if (lostReturn) {
                        first.store.after = {
                            if (it == "incrementReadyQueuedChannelAttemptCount") {
                                if (cancelledReturn) throw CancellationException("Committed count return cancelled")
                                throw storageFailure()
                            }
                        }
                        val q = first.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
                        assertTrue(first.store.channelAttempts.isEmpty())
                        assertTrue(first.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
                    }
                    first.store.after = null; first.close()
                    val next = Harness(this, config, store = first.store, generation = 2); next.start()
                    next.transport.channelError = 2u
                    val events = next.service.statusEvents()
                    var queries = 0
                    val q = next.queue(config = ChatSendQueueConfig(disambiguateAfterAttempts = 2),
                        query = MessagingChannelQuery { queries++; null })
                    q.hydrate(); runCurrent()
                    q.awaitDrainCompletion()
                    assertEquals(0, queries)
                    assertEquals(1, next.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
                    assertEquals(1, next.store.channelAttempts.size)
                    val attempt = next.store.channelAttempts.values.single()
                    assertEquals(QueuedChannelAttemptPhase.UNCERTAIN, attempt.phase)
                    val retained = next.store.pending.getValue(EntityKey(RADIO, row.id))
                    assertEquals(attempt.key.attemptID, retained.channelAttemptID)
                    assertEquals(QueuedChannelDispatchState.UNCERTAIN, retained.channelDispatchState)
                    assertEquals(if (lostReturn) 2L else 1L, retained.attemptCount)
                    assertEquals(MessageStatus.PENDING, next.stored(message.id).status)
                    assertTrue(statuses(next.service, events).isEmpty())
                    assertFalse(next.store.calls.contains("retireDefinitelyUnsentQueuedChannelAttempt"))
                    next.close()
                }
            },
            native("count-return-loss-cancelled-$cancelledReturn-recreation-does-not-bypass-replaced-occupant-fence") {
                val first = Harness(this); first.start()
                val message = first.message(MessageStatus.SENT, 0u)
                val row = first.pending(message, isResend = true)
                first.store.after = {
                    if (it == "incrementReadyQueuedChannelAttemptCount") {
                        if (cancelledReturn) throw CancellationException("Committed count return cancelled")
                        throw storageFailure()
                    }
                }
                val original = first.queue(); original.hydrate(); runCurrent(); original.awaitDrainCompletion()
                first.store.after = null; first.close()
                val next = Harness(this, store = first.store, generation = 2); next.start()
                next.store.channelOccupants[RADIO to 0u.toUByte()] = UUID.randomUUID()
                val q = next.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
                assertEquals(2L, next.store.pending.getValue(EntityKey(RADIO, row.id)).attemptCount)
                assertEquals(row.channelOccupantID, next.store.pending.getValue(EntityKey(RADIO, row.id)).channelOccupantID)
                assertTrue(next.store.channelAttempts.isEmpty())
                assertTrue(first.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
                assertTrue(next.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
                assertEquals(message, next.stored(message.id))
                next.close()
            },
        )
    } + listOf(0, 1, 2, 3, 4, 5, 6, 7, 99, 255).map { code ->
        native("raw-device-error-$code-retains-exact-uncertain-attempt-across-recreation") {
            val first = Harness(this, MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 3, baseDelay = 0.001)))
            first.start(); first.transport.channelError = code.toUByte()
            val message = first.message(MessageStatus.PENDING, 0u)
            val row = first.pending(message, attempt = 2)
            val events = first.service.statusEvents()
            val q = first.queue(config = ChatSendQueueConfig(disambiguateAfterAttempts = 0),
                query = MessagingChannelQuery { fail("Raw firmware error cannot authorize a channel query") })
            q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            val attempt = first.store.channelAttempts.values.single()
            val retained = first.store.pending.getValue(EntityKey(RADIO, row.id))
            assertEquals(row.id, attempt.key.pendingID)
            assertEquals(message.id, attempt.key.messageID)
            assertEquals(row.channelOccupantID, attempt.channelOccupantID)
            assertEquals(QueuedChannelAttemptPhase.UNCERTAIN, attempt.phase)
            assertEquals(attempt.key.attemptID, retained.channelAttemptID)
            assertEquals(QueuedChannelDispatchState.UNCERTAIN, retained.channelDispatchState)
            assertEquals(3L, retained.attemptCount)
            assertEquals(message, first.stored(message.id))
            assertTrue(statuses(first.service, events).isEmpty())
            assertEquals(1, first.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            val failure = first.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().first {
                it.operation == "channelRetained.${row.id}" && it.cause is QueuedChannelRetainedException
            }
            assertEquals(code.toUByte(), assertIs<MeshCoreException.DeviceError>(failure.cause.cause).code)
            assertFalse(first.store.calls.any {
                it in setOf("retireDefinitelyUnsentQueuedChannelAttempt", "deletePendingSendsForMessage",
                    "updateMessageStatusUnlessDelivered")
            })
            first.close()
            val next = Harness(this, store = first.store, generation = 2); next.start()
            val replacement = next.queue(query = MessagingChannelQuery { fail("Uncertain restart must not query") })
            replacement.hydrate(); runCurrent(); replacement.awaitDrainCompletion()
            assertEquals(retained, next.store.pending.getValue(EntityKey(RADIO, row.id)))
            assertEquals(attempt, next.store.channelAttempts.getValue(attempt.key))
            assertTrue(next.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            assertEquals(1, next.store.calls.count { it == "incrementReadyQueuedChannelAttemptCount" })
            assertEquals(message, next.stored(message.id))
            next.close()
        }
    }

    private fun seedAccepted(h: Harness, row: PendingSendDTO, stamp: UInt): QueuedChannelAttempt {
        val key = QueuedChannelAttemptKey(RADIO, row.id, row.messageID, UUID.randomUUID())
        val accepted = QueuedChannelAttempt(key, requireNotNull(row.channelIndex),
            requireNotNull(row.channelOccupantID), stamp, row.isResend, QueuedChannelAttemptPhase.ACCEPTED, false)
        h.store.channelAttempts[key] = accepted
        h.store.pending[EntityKey(RADIO, row.id)] = row.copy(
            channelDispatchState = QueuedChannelDispatchState.ACCEPTED, channelAttemptID = key.attemptID)
        return accepted
    }

    private fun seedDefinitelyUnsent(h: Harness, row: PendingSendDTO): QueuedChannelAttempt {
        val key = QueuedChannelAttemptKey(RADIO, row.id, row.messageID, UUID.randomUUID())
        val attempt = QueuedChannelAttempt(key, requireNotNull(row.channelIndex),
            requireNotNull(row.channelOccupantID), row.messageTimestamp, row.isResend,
            QueuedChannelAttemptPhase.DEFINITELY_UNSENT, false)
        h.store.channelAttempts[key] = attempt
        return attempt
    }
}
