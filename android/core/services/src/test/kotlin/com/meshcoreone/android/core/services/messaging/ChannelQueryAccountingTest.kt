// AndroidOnly: WP-208 Genuine ChannelService retry outcomes complete before requested recovery teardown.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.session.SessionCorrelationException
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.services.contacts.ChannelService
import com.meshcoreone.android.core.services.contacts.ChannelServiceClock
import com.meshcoreone.android.core.services.contacts.ChannelServiceError
import java.util.UUID
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelQueryAccountingTest {
    @TestFactory fun recovery() = listOf(
        native("direct-message readiness wake racing shutdown retains its pending identity") {
            assertReadinessRetirement(channel = false)
        },
        native("channel readiness wake racing shutdown retains its pending identity") {
            assertReadinessRetirement(channel = true)
        },
        native("selected direct-message park wake racing shutdown retains its pending identity") {
            val h = connection()
            val message = h.message(MessageStatus.PENDING)
            val pending = h.pending(message, 2)
            h.transport.directError = 3u
            var parked = false
            val timerEntered = CompletableDeferred<Unit>()
            val timerCancelled = CompletableDeferred<Unit>()
            val releaseTimer = CompletableDeferred<Unit>()
            val subscriptionCancelled = CompletableDeferred<Unit>()
            val releaseSubscription = CompletableDeferred<Unit>()
            val clock = object : SessionClock by h.clock {
                override suspend fun sleepFor(duration: Duration) {
                    if (!parked) {
                        h.clock.sleepFor(duration)
                        return
                    }
                    timerEntered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            timerCancelled.complete(Unit)
                            releaseTimer.await()
                        }
                    }
                }
            }
            val signals = object : ConnectionSignals by h.signals {
                override suspend fun subscribeTransitions(): ConnectionSubscription {
                    val source = h.signals.subscribeTransitions()
                    return object : ConnectionSubscription {
                        override val initial = source.initial
                        override val transitions = flow {
                            try { emitAll(source.transitions) }
                            finally {
                                withContext(NonCancellable) {
                                    subscriptionCancelled.complete(Unit)
                                    releaseSubscription.await()
                                }
                            }
                        }
                        override fun close() = source.close()
                    }
                }
            }
            val queue = ChatSendQueueService(
                h.token, h.store, h.service, MessagingChannelQuery { null },
                OutgoingChannelReactionIndexer { _, _, _, _, _ -> }, signals, h.scope,
                ChatSendQueueConfig(), clock, MessagingIssueReporter {
                    if (it is MessagingDiagnostic.Failure && it.operation == "park.${message.id}") parked = true
                    h.diagnostics += it
                },
            )
            try {
                start(h, queue)
                assertTrue(timerEntered.isCompleted, "The real direct sender must park on its transport deadline")
                val receipt = h.store.fetchPendingSendsForMessage(EntityKey(RADIO, message.id)).single()
                assertEquals(pending.id, receipt.id)
                assertEquals(3L, receipt.attemptCount)
                assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
                queue.transportDidOpen()
                runCurrent()
                assertTrue(timerCancelled.isCompleted, "The selected direct-message wake must precede shutdown")

                val stopping = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { queue.shutdown() }
                runCurrent()
                assertTrue(subscriptionCancelled.isCompleted, "Shutdown must seal ownership before the sender resumes")
                assertFalse(stopping.isCompleted)
                releaseTimer.complete(Unit)
                runCurrent()
                releaseSubscription.complete(Unit)
                runCurrent()
                val report = stopping.await()
                val failures = h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
                assertTrue(failures.none { it.operation == "terminal.${message.id}" ||
                    it.operation == "dmQueueCallback" }, "Retirement must not terminalize a parked direct envelope: $failures")
                assertTrue(report.issues.isEmpty(), "Retirement must not retain a direct queue failure: ${report.issues}")
                val retained = h.store.fetchPendingSendsForMessage(EntityKey(RADIO, message.id)).single()
                assertEquals(pending.id, retained.id)
                assertEquals(3L, retained.attemptCount)
                assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
            } finally {
                releaseTimer.complete(Unit)
                releaseSubscription.complete(Unit)
                queue.shutdown()
                h.close()
            }
        },
        native("selected park wake racing shutdown retains only its persisted query failure") {
            val h = connection()
            val message = h.message(MessageStatus.PENDING, 0u)
            val pending = h.pending(message, 2)
            val queryFailure = ChannelServiceError.SessionError(MeshCoreException.Timeout())
            val accounting = mutableListOf<ChannelQueryAccountingEvent>()
            var parked = false
            val timerEntered = CompletableDeferred<Unit>()
            val timerCancelled = CompletableDeferred<Unit>()
            val releaseTimer = CompletableDeferred<Unit>()
            val subscriptionCancelled = CompletableDeferred<Unit>()
            val releaseSubscription = CompletableDeferred<Unit>()
            val clock = object : SessionClock by h.clock {
                override suspend fun sleepFor(duration: Duration) {
                    if (!parked) {
                        h.clock.sleepFor(duration)
                        return
                    }
                    timerEntered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            timerCancelled.complete(Unit)
                            releaseTimer.await()
                        }
                    }
                }
            }
            val signals = object : ConnectionSignals by h.signals {
                override suspend fun subscribeTransitions(): ConnectionSubscription {
                    val source = h.signals.subscribeTransitions()
                    return object : ConnectionSubscription {
                        override val initial = source.initial
                        override val transitions = flow {
                            try {
                                emitAll(source.transitions)
                            } finally {
                                withContext(NonCancellable) {
                                    subscriptionCancelled.complete(Unit)
                                    releaseSubscription.await()
                                }
                            }
                        }
                        override fun close() = source.close()
                    }
                }
            }
            val queue = ChatSendQueueService(
                h.token, h.store, h.service, MessagingChannelQuery { throw queryFailure },
                OutgoingChannelReactionIndexer { _, _, _, _, _ -> }, signals, h.scope,
                ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 2), clock,
                MessagingIssueReporter { h.diagnostics += it }, onQueryAccounting = {
                    parked = true
                    accounting += it
                },
            )
            try {
                start(h, queue)
                assertTrue(timerEntered.isCompleted, "The real sender must be parked on its transport deadline")
                val receipt = accounting.single()
                assertEquals(ChannelQueryAccountingOutcome.PARKED, receipt.outcome)
                assertSame(queryFailure, receipt.queryFailure)
                assertEquals(pending.id, receipt.pendingID)
                assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
                queue.transportDidOpen()
                runCurrent()
                assertTrue(timerCancelled.isCompleted, "A successful transport wake must win before shutdown")

                val stopping = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { queue.shutdown() }
                runCurrent()
                assertTrue(subscriptionCancelled.isCompleted, "Shutdown must seal ownership before the sender resumes")
                assertFalse(stopping.isCompleted)
                releaseTimer.complete(Unit)
                runCurrent()
                releaseSubscription.complete(Unit)
                runCurrent()
                val report = stopping.await()
                val failures = h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
                assertEquals(1, report.issues.size, "Retain only the persisted park cause; diagnostics=$failures")
                assertSame(queryFailure, report.issues.single().cause)
                assertEquals(LifecycleStage.STOP_SERVICES, report.issues.single().stage)
                assertTrue(failures.none { it.operation == "terminal.${message.id}" ||
                    it.operation == "channelQueueCallback" }, "Retirement must not terminalize a parked envelope: $failures")
                assertEquals(pending.id, h.store.fetchPendingSendsForMessage(EntityKey(RADIO, message.id)).single().id)
                assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
                assertEquals(listOf(receipt), accounting)
                assertSame(report, queue.shutdown(), "Repeated shutdown must return the same retained report")
            } finally {
                releaseTimer.complete(Unit)
                releaseSubscription.complete(Unit)
                queue.shutdown()
                h.close()
            }
        },
        native("early recovery waits through real ChannelService retry and terminal deletion across generations") {
            val progress = ChatSendQueueProgress()
            val store = RecordingStore()
            var message: MessageDTO? = null
            var sibling: MessageDTO? = null
            repeat(2) { index ->
                val h = connection(store, index + 1L)
                val a = message ?: h.message(MessageStatus.PENDING, 0u).also {
                    message = it
                    h.pending(it, 2)
                }
                val b = sibling ?: h.message(MessageStatus.PENDING, 0u, text = "same channel B").also {
                    sibling = it
                    store.upsertPendingSend(h.pending(it, 2).copy(sequence = 2))
                }
                val pendingID = store.fetchPendingSendsForMessage(EntityKey(RADIO, a.id)).single().id
                val accounting = mutableListOf<ChannelQueryAccountingEvent>()
                val deletion = CompletableDeferred<Unit>()
                val releaseDeletion = CompletableDeferred<Unit>()
                var retry: Duration? = null
                var recovery: Deferred<Unit>? = null
                lateinit var queue: ChatSendQueueService
                store.before = { operation ->
                    if (index == 1 && operation == "deletePendingSendsForMessage") {
                        deletion.complete(Unit)
                        releaseDeletion.await()
                    }
                }
                try {
                    val channels = ChannelService(h.session, store, null, ChannelServiceClock { duration ->
                        retry = duration
                        recovery = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                            queue.awaitChannelQueryAccounting()
                            assertEquals(1, accounting.size, "The persistence receipt must precede recovery")
                            assertEquals(h.token, h.signals.snapshot.value.token)
                            if (index == 0) {
                                assertEquals(MessageStatus.PENDING, h.stored(a.id).status)
                                assertTrue(store.hasPendingSend(EntityKey(RADIO, a.id)))
                            } else {
                                assertEquals(MessageStatus.FAILED, h.stored(a.id).status)
                                assertFalse(store.hasPendingSend(EntityKey(RADIO, a.id)))
                            }
                            queue.shutdown()
                            h.signals.set(null, DeviceConnectionState.DISCONNECTED)
                        }
                        delay(duration)
                    }, Random(0))
                    queue = h.queue(query = MessagingChannelQuery(channels::fetchChannel), progress = progress,
                        config = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 2),
                        onQueryAccounting = { event ->
                            assertEquals(h.token, h.signals.snapshot.value.token)
                            assertFalse(assertNotNull(recovery).isCompleted)
                            assertEquals(if (index == 0) MessageStatus.PENDING else MessageStatus.FAILED, h.stored(a.id).status)
                            assertEquals(index == 0, store.pending.values.any { it.messageID == a.id })
                            assertTrue(h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
                                it.operation == if (index == 0) "park.${a.id}" else "terminal.${a.id}"
                            })
                            accounting += event
                        })
                    start(h, queue)
                    advanceTimeBy(1_999); runCurrent()
                    assertNull(retry)
                    assertTrue(queryFailures(h, a.id).isEmpty())
                    assertTrue(accounting.isEmpty())
                    advanceTimeBy(1); runCurrent()
                    val retryDelay = assertNotNull(retry).inWholeMilliseconds
                    assertTrue(retryDelay in 400L..600L)
                    assertFalse(assertNotNull(recovery).isCompleted)
                    assertTrue(queryFailures(h, a.id).isEmpty())
                    assertEquals(h.token, h.signals.snapshot.value.token)
                    assertEquals(1, h.sends(CommandCode.GET_CHANNEL).size)
                    advanceTimeBy(retryDelay - 1); runCurrent()
                    assertTrue(queryFailures(h, a.id).isEmpty())
                    advanceTimeBy(1); runCurrent()
                    val failure = assertIs<ChannelServiceError.SessionError>(queryFailures(h, a.id).single().cause)
                    assertIs<MeshCoreException.ConnectionLost>(failure.error)
                    val chain = generateSequence<Throwable>(failure.error) { it.cause }.take(16).toList()
                    val unresolved = assertIs<SessionCorrelationException.UnresolvedReply>(
                        chain.dropWhile { it is MeshCoreException.ConnectionLost }.firstOrNull())
                    assertEquals(1L, unresolved.generation)
                    assertEquals("channel:0", unresolved.responseFamily)
                    assertEquals(1, h.sends(CommandCode.GET_CHANNEL).size)
                    if (index == 1) {
                        assertTrue(deletion.isCompleted)
                        assertFalse(assertNotNull(recovery).isCompleted)
                        assertEquals(h.token, h.signals.snapshot.value.token)
                        assertTrue(store.hasPendingSend(EntityKey(RADIO, a.id)))
                        assertTrue(accounting.isEmpty(), "Blocked deletion cannot produce a persistence receipt")
                        releaseDeletion.complete(Unit); runCurrent()
                    }
                    assertNotNull(recovery).await()
                    val event = accounting.single()
                    assertEquals(h.token, event.token)
                    assertEquals(a.id, event.messageID)
                    assertEquals(pendingID, event.pendingID)
                    assertEquals(index + 1L, event.failureCount)
                    assertEquals(2L, event.failureCap)
                    assertEquals(if (index == 0) ChannelQueryAccountingOutcome.PARKED
                        else ChannelQueryAccountingOutcome.TERMINAL_DELETED, event.outcome)
                    assertSame(failure, event.queryFailure)
                    assertTrue(store.hasPendingSend(EntityKey(RADIO, b.id)))
                    assertEquals(MessageStatus.PENDING, h.stored(b.id).status)
                    assertEquals(2L, store.fetchPendingSendsForMessage(EntityKey(RADIO, b.id)).single().attemptCount)
                    assertEquals(0, h.signals.subscriberCount)
                    h.close()
                } finally {
                    releaseDeletion.complete(Unit)
                    store.before = null
                }
            }
        },
        native("manual shutdown cancels accounting observation without spending a failure") {
            val progress = ChatSendQueueProgress()
            val first = connection()
            val m = first.message(MessageStatus.PENDING, 0u); first.pending(m, 2)
            val accounting = mutableListOf<ChannelQueryAccountingEvent>()
            val queue = first.queue(query = MessagingChannelQuery { first.session.getChannel(it) }, progress = progress,
                config = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 1), onQueryAccounting = accounting::add)
            start(first, queue)
            val observer = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { queue.awaitChannelQueryAccounting() }
            assertFalse(observer.isCompleted)
            queue.shutdown(); runCurrent()
            assertFailsWith<CancellationException> { observer.await() }
            assertTrue(queryFailures(first, m.id).isEmpty())
            assertTrue(accounting.isEmpty())
            first.close()
            val second = connection(first.store, 2)
            val next = second.queue(query = MessagingChannelQuery { second.session.getChannel(it) }, progress = progress,
                config = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 2))
            start(second, next)
            advanceTimeBy(2_000); runCurrent()
            assertEquals(1, queryFailures(second, m.id).size)
            assertTrue(second.store.hasPendingSend(EntityKey(RADIO, m.id)))
        },
        native("cancelling only an accounting observer does not cancel the real query") {
            val h = connection()
            val m = h.message(MessageStatus.PENDING, 0u); h.pending(m, 2)
            val accounting = mutableListOf<ChannelQueryAccountingEvent>()
            val channels = ChannelService(h.session, h.store, null, ChannelServiceClock { delay(it) }, Random(0))
            val queue = h.queue(query = MessagingChannelQuery(channels::fetchChannel),
                config = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 1), onQueryAccounting = accounting::add)
            start(h, queue)
            val observer = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { queue.awaitChannelQueryAccounting() }
            observer.cancelAndJoin()
            advanceTimeBy(2_600); runCurrent()
            assertIs<ChannelServiceError.SessionError>(queryFailures(h, m.id).single().cause)
            assertEquals(MessageStatus.FAILED, h.stored(m.id).status)
            assertFalse(h.store.hasPendingSend(EntityKey(RADIO, m.id)))
            assertEquals(ChannelQueryAccountingOutcome.TERMINAL_DELETED, accounting.single().outcome)
            assertSame(queryFailures(h, m.id).single().cause, accounting.single().queryFailure)
        },
        native("terminal storage failure completes accounting without restoring a full budget") {
            val progress = ChatSendQueueProgress()
            val first = connection()
            val m = first.message(MessageStatus.PENDING, 0u); first.pending(m, 2)
            val accounting = mutableListOf<ChannelQueryAccountingEvent>()
            val query = MessagingChannelQuery { first.session.getChannel(it) }
            val queue = first.queue(query = query, progress = progress,
                config = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 1), onQueryAccounting = accounting::add)
            first.store.faults["deletePendingSendsForMessage"] = storageFailure()
            start(first, queue)
            val observer = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { queue.awaitChannelQueryAccounting() }
            advanceTimeBy(2_000); runCurrent()
            observer.await()
            assertTrue(first.store.hasPendingSend(EntityKey(RADIO, m.id)))
            assertTrue(accounting.isEmpty(), "Failed deletion must not certify terminal accounting")
            assertTrue(first.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
                it.operation == "terminalPersistence.${m.id}" && it.cause is PersistenceStoreException
            })
            first.close(); first.store.faults.clear()
            val second = connection(first.store, 2)
            val next = second.queue(query = MessagingChannelQuery { second.session.getChannel(it) }, progress = progress,
                config = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 2), onQueryAccounting = accounting::add)
            start(second, next)
            advanceTimeBy(2_000); runCurrent()
            assertFalse(second.store.hasPendingSend(EntityKey(RADIO, m.id)))
            val event = accounting.single()
            assertEquals(second.token, event.token)
            assertEquals(m.id, event.messageID)
            assertEquals(2L, event.failureCount)
            assertEquals(2L, event.failureCap)
            assertEquals(MessageStatus.FAILED, second.stored(m.id).status)
            assertEquals(ChannelQueryAccountingOutcome.TERMINAL_DELETED, event.outcome)
            assertSame(queryFailures(second, m.id).single().cause, event.queryFailure)
        },
        native("real unconfigured query holds accounting until its terminal deletion completes") {
            val h = connection()
            val m = h.message(MessageStatus.PENDING, 0u); h.pending(m, 2)
            h.transport.channelReplies[0u] = ChannelInfo(0u, "", Bytes(ByteArray(16)))
            val accounting = mutableListOf<ChannelQueryAccountingEvent>()
            val release = CompletableDeferred<Unit>()
            h.store.before = { operation -> if (operation == "deletePendingSendsForMessage") release.await() }
            try {
                val channels = ChannelService(h.session, h.store, null)
                val queue = h.queue(query = MessagingChannelQuery(channels::fetchChannel), onQueryAccounting = accounting::add)
                start(h, queue)
                val observer = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    queue.awaitChannelQueryAccounting()
                }
                assertFalse(observer.isCompleted)
                assertTrue(h.store.hasPendingSend(EntityKey(RADIO, m.id)))
                release.complete(Unit); runCurrent()
                observer.await()
                assertFalse(h.store.hasPendingSend(EntityKey(RADIO, m.id)))
                assertTrue(queryFailures(h, m.id).isEmpty())
                assertTrue(accounting.isEmpty(), "A real null is not a thrown-query failure")
            } finally { release.complete(Unit); h.store.before = null }
        },
        native("replacement cancels an old accounting waiter before a non-cooperative late result") {
            val h = connection()
            val m = h.message(MessageStatus.PENDING, 0u); h.pending(m, 2)
            val accounting = mutableListOf<ChannelQueryAccountingEvent>()
            val release = CompletableDeferred<Unit>()
            try {
                val queue = h.queue(query = MessagingChannelQuery {
                    withContext(NonCancellable) { release.await(); throw MeshCoreException.Timeout() }
                }, onQueryAccounting = accounting::add)
                start(h, queue)
                val observer = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    queue.awaitChannelQueryAccounting()
                }
                h.signals.set(token(generation = 2), DeviceConnectionState.CONNECTED); runCurrent()
                assertTrue(observer.isCancelled)
                assertFailsWith<CancellationException> { observer.await() }
                release.complete(Unit); runCurrent()
                assertTrue(queryFailures(h, m.id).isEmpty())
                assertTrue(accounting.isEmpty())
            } finally { release.complete(Unit) }
        },
        native("no active query is immediately observable only on the live generation") {
            val h = connection()
            val queue = h.queue()
            queue.awaitChannelQueryAccounting()
            assertTrue(h.sends(CommandCode.GET_CHANNEL).isEmpty())
            queue.shutdown()
            assertFailsWith<com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException> {
                queue.awaitChannelQueryAccounting()
            }
        },
        native("idle recovery seals later direct and channel envelopes without dropping durable submissions") {
            val h = connection()
            val queue = h.queue()
            queue.awaitChannelQueryAccounting()
            val channel = h.message(MessageStatus.PENDING, 0u)
            val direct = h.message(MessageStatus.PENDING)
            queue.enqueueChannel(ChannelMessageEnvelope(channel.id, 0u, false, channel.text, channel.timestamp, "Test"))
            queue.enqueueDM(DirectMessageEnvelope(direct.id, CONTACT, false))
            runCurrent()
            queue.transportDidOpen()
            start(h, queue)
            queue.awaitChannelQueryAccounting()
            assertTrue(h.sends(CommandCode.GET_CHANNEL).isEmpty())
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())
            for (id in listOf(channel.id, direct.id)) {
                assertEquals(0L, h.store.fetchPendingSendsForMessage(EntityKey(RADIO, id)).single().attemptCount)
                assertEquals(MessageStatus.PENDING, h.stored(id).status)
            }
            assertEquals(2, queue.queuedEnvelopeCount)
        },
        native("preparation racing preflight prevents a later wire send or query without cancelling persistence") {
            val h = connection()
            val m = h.message(MessageStatus.PENDING, 0u); h.pending(m, 2)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            h.store.before = { operation ->
                if (operation == "incrementPendingSendAttemptCount") {
                    entered.complete(Unit)
                    release.await()
                }
            }
            try {
                val queue = h.queue(query = MessagingChannelQuery { h.session.getChannel(it) })
                start(h, queue)
                assertTrue(entered.isCompleted)
                queue.awaitChannelQueryAccounting()
                assertEquals(h.token, h.signals.snapshot.value.token)
                release.complete(Unit); runCurrent()
                assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
                assertTrue(h.sends(CommandCode.GET_CHANNEL).isEmpty())
                assertTrue(queryFailures(h, m.id).isEmpty())
                assertEquals(3L, h.store.fetchPendingSendsForMessage(EntityKey(RADIO, m.id)).single().attemptCount)
                assertEquals(MessageStatus.PENDING, h.stored(m.id).status)
            } finally { release.complete(Unit); h.store.before = null }
        },
        native("failed pending remap never becomes successful accounting through shutdown remapping") {
            val h = connection()
            val m = h.message(MessageStatus.PENDING, 0u); val pending = h.pending(m, 2)
            val accounting = mutableListOf<ChannelQueryAccountingEvent>()
            val queue = h.queue(query = MessagingChannelQuery { h.session.getChannel(it) },
                config = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 2), onQueryAccounting = accounting::add)
            start(h, queue)
            h.store.updateMessageStatus(EntityKey(RADIO, m.id), MessageStatus.RETRYING)
            val storage = storageFailure()
            h.store.faults["updateMessageStatusUnlessDelivered"] = storage
            val observer = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { queue.awaitChannelQueryAccounting() }
            advanceTimeBy(2_000); runCurrent()
            observer.await()
            assertIs<MeshCoreException.Timeout>(queryFailures(h, m.id).single().cause)
            assertEquals(MessageStatus.RETRYING, h.stored(m.id).status)
            assertTrue(accounting.isEmpty(), "A finished barrier does not certify failed persistence")
            assertEquals(pending.id, h.store.fetchPendingSendsForMessage(EntityKey(RADIO, m.id)).single().id)
            assertTrue(h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
                it.operation == "remap.${m.id}" && it.cause === storage
            })
            h.store.faults.clear()
            queue.shutdown()
            assertEquals(MessageStatus.PENDING, h.stored(m.id).status)
            assertTrue(accounting.isEmpty(), "Later shutdown persistence is not successful query accounting")
        },
        native("failed terminal status write cannot emit a terminal persistence receipt") {
            val h = connection()
            val m = h.message(MessageStatus.PENDING, 0u); val pending = h.pending(m, 2)
            val accounting = mutableListOf<ChannelQueryAccountingEvent>()
            val queue = h.queue(query = MessagingChannelQuery { h.session.getChannel(it) },
                config = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 1), onQueryAccounting = accounting::add)
            start(h, queue)
            h.store.updateMessageStatus(EntityKey(RADIO, m.id), MessageStatus.RETRYING)
            val storage = storageFailure()
            h.store.faults["updateMessageStatusUnlessDelivered"] = storage
            val observer = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { queue.awaitChannelQueryAccounting() }
            advanceTimeBy(2_000); runCurrent()
            observer.await()
            assertIs<MeshCoreException.Timeout>(queryFailures(h, m.id).single().cause)
            assertEquals(MessageStatus.RETRYING, h.stored(m.id).status)
            assertEquals(pending.id, h.store.fetchPendingSendsForMessage(EntityKey(RADIO, m.id)).single().id)
            assertTrue(h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
                it.operation == "terminalPersistence.${m.id}" && it.cause === storage
            })
            assertTrue(accounting.isEmpty(), "Neither FAILED nor deletion committed, despite barrier completion")
            h.store.faults.clear()
        },
        native("successful configured channel query is not reported as a thrown-query completion") {
            val h = connection()
            val m = h.message(MessageStatus.PENDING, 0u); h.pending(m, 2)
            val accounting = mutableListOf<ChannelQueryAccountingEvent>()
            h.transport.channelReplies[0u] = ChannelInfo(0u, "configured", Bytes(ByteArray(16) { 1 }))
            val channels = ChannelService(h.session, h.store, null)
            val queue = h.queue(query = MessagingChannelQuery(channels::fetchChannel), onQueryAccounting = accounting::add)
            start(h, queue)
            queue.awaitChannelQueryAccounting()
            assertEquals(1, h.sends(CommandCode.GET_CHANNEL).size)
            assertTrue(queryFailures(h, m.id).isEmpty())
            assertTrue(accounting.isEmpty())
            assertEquals(MessageStatus.PENDING, h.stored(m.id).status)
        },
        native("passive park receipt certifies committed status and pending identity without a recovery waiter") {
            val h = connection()
            val m = h.message(MessageStatus.PENDING, 0u); val pending = h.pending(m, 2)
            val accounting = mutableListOf<ChannelQueryAccountingEvent>()
            val queue = h.queue(query = MessagingChannelQuery { h.session.getChannel(it) },
                config = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 3),
                onQueryAccounting = { event ->
                    assertEquals(h.token, h.signals.snapshot.value.token)
                    assertEquals(MessageStatus.PENDING, h.stored(m.id).status)
                    assertEquals(pending.id, h.store.pending.values.single().id)
                    accounting += event
                })
            start(h, queue)
            h.store.updateMessageStatus(EntityKey(RADIO, m.id), MessageStatus.RETRYING)
            advanceTimeBy(1_999); runCurrent()
            assertTrue(accounting.isEmpty())
            advanceTimeBy(1); runCurrent()
            val event = accounting.single()
            assertEquals(h.token, event.token)
            assertEquals(m.id, event.messageID)
            assertEquals(pending.id, event.pendingID)
            assertEquals(1L, event.failureCount)
            assertEquals(3L, event.failureCap)
            assertEquals(ChannelQueryAccountingOutcome.PARKED, event.outcome)
            assertIs<MeshCoreException.Timeout>(event.queryFailure)
            assertSame(queryFailures(h, m.id).single().cause, event.queryFailure)
            assertEquals(3L, h.store.fetchPendingSendsForMessage(EntityKey(RADIO, m.id)).single().attemptCount)
            queue.awaitChannelQueryAccounting()
            queue.awaitChannelQueryAccounting()
            assertEquals(1, accounting.size, "Repeated observation cannot duplicate a finalized flight")
        },
        native("shutdown during noncancellable remap cancels accounting even if the write later commits") {
            val h = connection()
            val m = h.message(MessageStatus.PENDING, 0u); val pending = h.pending(m, 2)
            val accounting = mutableListOf<ChannelQueryAccountingEvent>()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val queue = h.queue(query = MessagingChannelQuery { h.session.getChannel(it) },
                config = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 2), onQueryAccounting = accounting::add)
            start(h, queue)
            h.store.updateMessageStatus(EntityKey(RADIO, m.id), MessageStatus.RETRYING)
            h.store.before = { operation ->
                if (operation == "updateMessageStatusUnlessDelivered") {
                    entered.complete(Unit)
                    release.await()
                }
            }
            try {
                val observer = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { queue.awaitChannelQueryAccounting() }
                advanceTimeBy(2_000); runCurrent()
                assertTrue(entered.isCompleted)
                assertEquals(MessageStatus.RETRYING, h.stored(m.id).status)
                assertFalse(observer.isCompleted)
                assertTrue(accounting.isEmpty())
                val stopping = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { queue.shutdown() }
                assertFailsWith<CancellationException> { observer.await() }
                assertFalse(stopping.isCompleted)
                release.complete(Unit); runCurrent()
                stopping.await()
                assertEquals(MessageStatus.PENDING, h.stored(m.id).status)
                assertEquals(pending.id, h.store.fetchPendingSendsForMessage(EntityKey(RADIO, m.id)).single().id)
                assertIs<MeshCoreException.Timeout>(queryFailures(h, m.id).single().cause)
                assertTrue(accounting.isEmpty(), "A real committed write cannot revive cancelled accounting")
            } finally { release.complete(Unit); h.store.before = null }
        },
        native("shutdown during terminal deletion cannot certify a failed or cancelled terminal write") {
            val h = connection()
            val m = h.message(MessageStatus.PENDING, 0u); val pending = h.pending(m, 2)
            val accounting = mutableListOf<ChannelQueryAccountingEvent>()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val queue = h.queue(query = MessagingChannelQuery { h.session.getChannel(it) },
                config = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 1), onQueryAccounting = accounting::add)
            h.store.before = { operation ->
                if (operation == "deletePendingSendsForMessage") {
                    entered.complete(Unit)
                    release.await()
                }
            }
            try {
                start(h, queue)
                val observer = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { queue.awaitChannelQueryAccounting() }
                advanceTimeBy(2_000); runCurrent()
                assertTrue(entered.isCompleted)
                assertEquals(MessageStatus.FAILED, h.stored(m.id).status)
                assertTrue(h.store.hasPendingSend(EntityKey(RADIO, m.id)))
                assertTrue(accounting.isEmpty())
                queue.shutdown(); runCurrent()
                assertFailsWith<CancellationException> { observer.await() }
                assertEquals(pending.id, h.store.fetchPendingSendsForMessage(EntityKey(RADIO, m.id)).single().id)
                assertEquals(MessageStatus.PENDING, h.stored(m.id).status)
                assertIs<MeshCoreException.Timeout>(queryFailures(h, m.id).single().cause)
                assertTrue(accounting.isEmpty())
            } finally { release.complete(Unit); h.store.before = null }
        },
    ) + listOf(1L, 2L).map { cap ->
        native("no-op status update at query cap $cap does not certify a committed FAILED or PENDING transition") {
            val h = connection()
            val m = h.message(MessageStatus.PENDING, 0u); h.pending(m, 2)
            val accounting = mutableListOf<ChannelQueryAccountingEvent>()
            val queue = h.queue(query = MessagingChannelQuery { h.session.getChannel(it) },
                config = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = cap), onQueryAccounting = accounting::add)
            start(h, queue)
            h.store.updateMessageStatus(EntityKey(RADIO, m.id), MessageStatus.DELIVERED)
            advanceTimeBy(2_000); runCurrent()
            assertEquals(MessageStatus.DELIVERED, h.stored(m.id).status)
            assertIs<MeshCoreException.Timeout>(queryFailures(h, m.id).single().cause)
            assertEquals(cap != 1L, h.store.hasPendingSend(EntityKey(RADIO, m.id)))
            assertTrue(accounting.isEmpty(), "A no-op write is not evidence of the requested persisted status")
        }
    }

    private suspend fun TestScope.connection(store: RecordingStore = RecordingStore(), generation: Long = 1) =
        Harness(this, MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 0)), store, generation).also {
            it.start()
            it.transport.channelError = 2u
        }

    private suspend fun TestScope.start(h: Harness, queue: ChatSendQueueService) {
        h.signals.set(h.token, DeviceConnectionState.CONNECTED)
        queue.hydrate(); runCurrent()
        h.signals.set(h.token, DeviceConnectionState.READY); runCurrent()
    }

    private suspend fun TestScope.assertReadinessRetirement(channel: Boolean) {
        val h = connection()
        val subscriptionCancelled = CompletableDeferred<Unit>()
        val releaseSubscription = CompletableDeferred<Unit>()
        val readinessWait = CompletableDeferred<Unit>()
        val timerCancelled = CompletableDeferred<Unit>()
        val releaseTimer = CompletableDeferred<Unit>()
        var holdTimerCleanup = false
        val clock = object : SessionClock by h.clock {
            override suspend fun sleepFor(duration: Duration) {
                readinessWait.complete(Unit)
                try { h.clock.sleepFor(duration) }
                finally {
                    if (holdTimerCleanup) withContext(NonCancellable) {
                        timerCancelled.complete(Unit)
                        releaseTimer.await()
                    }
                }
            }
        }
        val signals = object : ConnectionSignals by h.signals {
            override suspend fun subscribeTransitions(): ConnectionSubscription {
                val source = h.signals.subscribeTransitions()
                return object : ConnectionSubscription {
                    override val initial = source.initial
                    override val transitions = flow {
                        try { emitAll(source.transitions) }
                        finally {
                            withContext(NonCancellable) {
                                subscriptionCancelled.complete(Unit)
                                releaseSubscription.await()
                            }
                        }
                    }
                    override fun close() = source.close()
                }
            }
        }
        val queue = ChatSendQueueService(
            h.token, h.store, h.service, MessagingChannelQuery { null },
            OutgoingChannelReactionIndexer { _, _, _, _, _ -> }, signals, h.scope,
            ChatSendQueueConfig(), clock, MessagingIssueReporter { h.diagnostics += it },
        )
        try {
            h.signals.set(h.token, DeviceConnectionState.CONNECTED)
            assertEquals(h.token, signals.snapshot.value.token)
            assertFalse(signals.snapshot.value.state.canDrainSendQueue)
            queue.observeConnectionState()
            queue.hydrate()
            runCurrent()
            val message = h.message(MessageStatus.PENDING, if (channel) 0u else null)
            if (channel) {
                queue.enqueueChannel(ChannelMessageEnvelope(message.id, 0u, false, message.text, message.timestamp, "Test"))
            } else {
                queue.enqueueDM(DirectMessageEnvelope(message.id, CONTACT, false))
            }
            runCurrent()
            assertTrue(readinessWait.isCompleted, "The envelope must be dequeued and suspended at readiness")
            val pending = h.store.fetchPendingSendsForMessage(EntityKey(RADIO, message.id)).single()
            assertEquals(0L, pending.attemptCount)
            assertEquals(MessageStatus.PENDING, h.stored(message.id).status)

            h.signals.set(h.token, DeviceConnectionState.READY)
            holdTimerCleanup = true
            queue.transportDidOpen()
            runCurrent()
            assertTrue(timerCancelled.isCompleted, "The readiness wake must win before shutdown starts")
            val stopping = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { queue.shutdown() }
            runCurrent()
            assertTrue(subscriptionCancelled.isCompleted, "Shutdown must invalidate ownership before releasing the sender")
            assertFalse(stopping.isCompleted)
            releaseTimer.complete(Unit)
            runCurrent()
            releaseSubscription.complete(Unit)
            runCurrent()
            val report = stopping.await()

            assertTrue(h.diagnostics.filterIsInstance<MessagingDiagnostic.StaleResult>().any {
                it.token == h.token && it.operation == "queueReadiness.woke"
            }, "The selected readiness wake must reach the retired generation check")
            assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            val failures = h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
            val lane = if (channel) "channel" else "dm"
            assertTrue(failures.none {
                it.operation == "terminal.${message.id}" || it.operation == "${lane}QueueCallback"
            }, "Retirement must not terminalize the dequeued envelope: $failures")
            assertTrue(report.issues.isEmpty(), "Retirement must not retain a queue teardown failure: ${report.issues}")
            val retained = h.store.fetchPendingSendsForMessage(EntityKey(RADIO, message.id)).single()
            assertEquals(pending.id, retained.id)
            assertEquals(0L, retained.attemptCount)
            assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
        } finally {
            releaseTimer.complete(Unit)
            releaseSubscription.complete(Unit)
            queue.shutdown()
            h.close()
        }
    }

    private fun queryFailures(h: Harness, messageID: UUID) =
        h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().filter { it.operation == "channelQuery.$messageID" }
}
