// AndroidOnly: WP-208 Readiness retirement and no fabricated query accounting for uncertain sends.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.session.SessionClock
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlin.time.Duration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelQueryAccountingTest {
    @TestFactory fun recovery() = listOf(
        native("direct-message retirement before initial readiness retains its pending identity") {
            assertInitialReadinessRetirement(channel = false)
        },
        native("channel retirement before initial readiness retains its pending identity") {
            assertInitialReadinessRetirement(channel = true)
        },
        native("direct-message selected readiness wake cannot restart during token replacement") {
            assertReplacementRetirement(channel = false)
        },
        native("channel selected readiness wake cannot restart during token replacement") {
            assertReplacementRetirement(channel = true)
        },
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
                if (operation == "incrementReadyQueuedChannelAttemptCount") {
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
    ) + listOf(0L, 3L, 100L).flatMap { threshold ->
        listOf(1L, 2L, 16L).map { cap ->
            native("raw ERROR2 never starts query accounting at threshold $threshold and cap $cap") {
                val h = connection()
                val message = h.message(MessageStatus.PENDING, 0u)
                val pending = h.pending(message, 2)
                val accounting = mutableListOf<ChannelQueryAccountingEvent>()
                val events = h.service.statusEvents()
                var queried = false
                val queue = h.queue(
                    query = MessagingChannelQuery { queried = true; throw storageFailure() },
                    config = ChatSendQueueConfig(disambiguateAfterAttempts = threshold,
                        maxConsecutiveFetchChannelFailures = cap),
                    onQueryAccounting = accounting::add,
                )
                start(h, queue)
                queue.awaitDrainCompletion()
                val retained = h.store.pending.getValue(EntityKey(RADIO, pending.id))
                val attempt = h.store.channelAttempts.values.single()
                assertEquals(QueuedChannelDispatchState.UNCERTAIN, retained.channelDispatchState)
                assertEquals(QueuedChannelAttemptPhase.UNCERTAIN, attempt.phase)
                assertEquals(attempt.key.attemptID, retained.channelAttemptID)
                assertEquals(pending.id, attempt.key.pendingID)
                assertEquals(message.channelOccupantID, attempt.channelOccupantID)
                assertEquals(3L, retained.attemptCount)
                assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
                advanceTimeBy(30_001); runCurrent()
                queue.transportDidOpen(); runCurrent()
                queue.awaitDrainCompletion()
                queue.awaitChannelQueryAccounting()
                assertFalse(queried)
                assertTrue(h.sends(CommandCode.GET_CHANNEL).isEmpty())
                assertTrue(accounting.isEmpty(), "An uncertain send is not a completed query or park")
                assertTrue(queryFailures(h, message.id).isEmpty())
                assertTrue(h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().none {
                    it.operation == "park.${message.id}" || it.operation == "terminal.${message.id}"
                })
                assertEquals(retained, h.store.pending.getValue(EntityKey(RADIO, pending.id)))
                assertEquals(attempt, h.store.channelAttempts.getValue(attempt.key))
                assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
                assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
                assertTrue(statuses(h.service, events).isEmpty())
                queue.shutdown()
                assertFailsWith<com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException> {
                    queue.awaitChannelQueryAccounting()
                }
                assertTrue(accounting.isEmpty())
                h.close()
            }
        }
    } + listOf("updateMessageStatusUnlessDelivered", "retireDefinitelyUnsentQueuedChannelAttempt",
        "deletePendingSendsForMessage").map { operation ->
        native("uncertain ERROR2 never attempts $operation or fabricates its accounting receipt") {
            val h = connection()
            val message = h.message(MessageStatus.PENDING, 0u)
            val pending = h.pending(message, 2)
            val accounting = mutableListOf<ChannelQueryAccountingEvent>()
            h.store.faults[operation] = storageFailure()
            val queue = h.queue(
                query = MessagingChannelQuery { fail("Raw error must not enter query recovery") },
                onQueryAccounting = accounting::add,
            )
            start(h, queue)
            queue.awaitDrainCompletion()
            assertFalse(operation in h.store.calls)
            assertTrue(accounting.isEmpty())
            assertTrue(h.sends(CommandCode.GET_CHANNEL).isEmpty())
            val retained = h.store.pending.getValue(EntityKey(RADIO, pending.id))
            assertEquals(QueuedChannelDispatchState.UNCERTAIN, retained.channelDispatchState)
            assertEquals(message.id, retained.messageID)
            assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            h.store.faults.clear()
            h.store.updateMessageStatus(EntityKey(RADIO, message.id), MessageStatus.DELIVERED)
            queue.awaitChannelQueryAccounting()
            assertEquals(MessageStatus.DELIVERED, h.stored(message.id).status)
            assertEquals(retained, h.store.pending.getValue(EntityKey(RADIO, pending.id)))
            assertTrue(accounting.isEmpty(), "A no-op accounting observation cannot certify a status write")
            h.close()
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

    private suspend fun TestScope.assertInitialReadinessRetirement(channel: Boolean) {
        val h = connection()
        val subscriptionCancelled = CompletableDeferred<Unit>()
        val releaseSubscription = CompletableDeferred<Unit>()
        val accounting = mutableListOf<ChannelQueryAccountingEvent>()
        var clockWaits = 0
        val clock = object : SessionClock by h.clock {
            override suspend fun sleepFor(duration: Duration) {
                clockWaits++
                h.clock.sleepFor(duration)
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
            onQueryAccounting = accounting::add,
        )
        var stopping: Deferred<TeardownReport>? = null
        var checkpointEntries = 0
        var checkpointFailure: Throwable? = null
        var originalPendingID: UUID? = null
        try {
            val occupant = ChannelDTO(radioId = RADIO, index = 0u, name = "Test")
            h.store.channels[EntityKey(RADIO, occupant.id)] = occupant
            assertEquals(occupant.id, h.store.fetchChannel(RADIO, 0u)?.id)
            h.signals.set(h.token, DeviceConnectionState.CONNECTED)
            assertEquals(h.token, signals.snapshot.value.token)
            assertFalse(signals.snapshot.value.state.canDrainSendQueue)
            queue.observeConnectionState()
            queue.hydrate()
            runCurrent()
            val message = h.message(MessageStatus.PENDING, if (channel) 0u else null)
            queue.beforeInitialReadinessCheck = {
                queue.beforeInitialReadinessCheck = null
                checkpointEntries++
                try {
                    val receipt = h.store.pending.values.single { it.messageID == message.id }
                    originalPendingID = receipt.id
                    assertEquals(0L, receipt.attemptCount)
                    assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
                    stopping = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { queue.shutdown() }
                } catch (failure: Throwable) {
                    checkpointFailure = failure
                    throw failure
                }
            }
            if (channel) {
                queue.enqueueChannel(ChannelMessageEnvelope(message.id, 0u, false,
                    message.text, message.timestamp, "Test", requireNotNull(message.channelOccupantID)))
            } else {
                queue.enqueueDM(DirectMessageEnvelope(message.id, CONTACT, false))
            }
            runCurrent()
            assertNull(checkpointFailure, "A checkpoint error cannot masquerade as sender retirement")
            assertEquals(1, checkpointEntries, "The real dequeued sender must reach initial readiness exactly once")
            assertNull(queue.beforeInitialReadinessCheck)
            val shutdown = assertNotNull(stopping)
            assertTrue(subscriptionCancelled.isCompleted, "Advance the observer finally before checking its gate")
            assertFalse(shutdown.isCompleted, "Observer cleanup must hold shutdown before either drain is cancelled")
            val stale = h.diagnostics.filterIsInstance<MessagingDiagnostic.StaleResult>()
            assertEquals(1, stale.count { it.token == h.token && it.operation == "queueReadiness" })
            assertTrue(stale.none { it.operation == "queueReadiness.woke" })
            assertEquals(0, clockWaits, "Initial retirement must not enter the readiness wait")
            assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            assertTrue(accounting.isEmpty())
            assertEquals(1, queue.queuedEnvelopeCount)
            fun assertRetained() {
                val retained = h.store.pending.values.single { it.messageID == message.id }
                assertEquals(assertNotNull(originalPendingID), retained.id)
                assertEquals(0L, retained.attemptCount)
                assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
                val failures = h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
                assertTrue(failures.none {
                    it.operation == "terminal.${message.id}" || it.operation == "dmQueueCallback" ||
                        it.operation == "channelQueueCallback" || it.operation == "sendQueueDrain"
                }, "Initial retirement must not enter real terminal handling or fail its callback: $failures")
            }
            assertRetained()
            releaseSubscription.complete(Unit)
            runCurrent()
            val report = shutdown.await()
            assertTrue(report.issues.isEmpty(), "Initial retirement must not retain callback failure: ${report.issues}")
            assertRetained()
        } finally {
            queue.beforeInitialReadinessCheck = null
            releaseSubscription.complete(Unit)
            queue.shutdown()
            h.close()
        }
    }

    private suspend fun TestScope.assertReplacementRetirement(channel: Boolean) {
        val h = connection()
        val releaseTimer = CompletableDeferred<Unit>()
        val timerCancelled = CompletableDeferred<Unit>()
        var timers = 0
        var replacing = false
        var checkpointReached = false
        var wokeBeforeCancellation = false
        var restartedBeforeCancellation = false
        fun stale(operation: String) = h.diagnostics.filterIsInstance<MessagingDiagnostic.StaleResult>()
            .count { it.token == h.token && it.operation == operation }
        val dispatcher = object : CoroutineDispatcher() {
            val ready = ArrayDeque<Runnable>()
            var checkpoint: (() -> Unit)? = null
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (replacing) checkpoint?.also { checkpoint = null }?.invoke()
                ready.addLast(block)
            }
            fun runReady(until: () -> Boolean = { false }) {
                var steps = 0
                while (ready.isNotEmpty() && !until()) {
                    check(++steps <= 100) { "Replacement dispatcher did not quiesce within 100 continuations" }
                    ready.removeFirst().run()
                }
            }
        }
        val clock = object : SessionClock by h.clock {
            override suspend fun sleepFor(duration: Duration) {
                val selected = ++timers == 1
                try { awaitCancellation() }
                finally {
                    if (selected) withContext(NonCancellable) {
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
                            source.transitions.collect { snapshot ->
                                replacing = snapshot.token != null && snapshot.token != h.token
                                try { emit(snapshot) }
                                finally { replacing = false }
                            }
                        } finally { source.close() }
                    }
                    override fun close() = source.close()
                }
            }
        }
        val accounting = mutableListOf<ChannelQueryAccountingEvent>()
        val queue = ChatSendQueueService(
            h.token, h.store, h.service, MessagingChannelQuery { null },
            OutgoingChannelReactionIndexer { _, _, _, _, _ -> }, signals,
            CoroutineScope(h.scope.coroutineContext + dispatcher), ChatSendQueueConfig(), clock,
            MessagingIssueReporter { h.diagnostics += it }, onQueryAccounting = accounting::add,
        )
        suspend fun dispatched(action: suspend () -> Unit) {
            val work = backgroundScope.async(dispatcher, start = CoroutineStart.LAZY) { action() }
            work.start()
            dispatcher.runReady()
            work.await()
        }
        suspend fun enqueue(message: MessageDTO) = dispatched {
            val channelIndex = message.channelIndex
            if (channelIndex != null) {
                queue.enqueueChannel(ChannelMessageEnvelope(message.id, channelIndex, false,
                    message.text, message.timestamp, "Test", requireNotNull(message.channelOccupantID)))
            } else {
                queue.enqueueDM(DirectMessageEnvelope(message.id, CONTACT, false))
            }
        }
        try {
            val occupant = ChannelDTO(radioId = RADIO, index = 0u, name = "Test")
            h.store.channels[EntityKey(RADIO, occupant.id)] = occupant
            assertEquals(occupant.id, h.store.fetchChannel(RADIO, 0u)?.id)
            h.signals.set(h.token, DeviceConnectionState.CONNECTED)
            assertFalse(signals.snapshot.value.state.canDrainSendQueue)
            queue.observeConnectionState()
            dispatched { queue.hydrate() }
            val primary = h.message(MessageStatus.PENDING, if (channel) 0u else null)
            enqueue(primary)
            assertEquals(1, timers, "The real persisted primary must wait for readiness")
            val pending = h.store.fetchPendingSendsForMessage(EntityKey(RADIO, primary.id)).single()
            assertEquals(0L, pending.attemptCount)

            h.signals.set(h.token, DeviceConnectionState.READY)
            dispatcher.runReady()
            assertTrue(timerCancelled.isCompleted, "Select the primary wake and hold its timer cleanup")
            h.signals.set(h.token, DeviceConnectionState.CONNECTED)
            dispatcher.runReady()
            val sentinel = h.message(MessageStatus.PENDING, if (channel) null else 0u)
            enqueue(sentinel)
            assertEquals(2, timers, "The opposite lane must really wait on the transport signal")
            val sentinelPending = h.store.fetchPendingSendsForMessage(EntityKey(RADIO, sentinel.id)).single()

            dispatcher.checkpoint = {
                // finish() dispatches the other waiter's cancellation before either cancelDrain().
                // Run only the already-selected sender while that observer dispatch is held.
                checkpointReached = true
                releaseTimer.complete(Unit)
                dispatcher.runReady { stale("queueReadiness") != 0 }
                wokeBeforeCancellation = stale("queueReadiness.woke") == 1
                restartedBeforeCancellation = stale("queueReadiness") != 0
            }
            val replacement = token(generation = 2)
            h.signals.set(replacement, DeviceConnectionState.CONNECTED)
            dispatcher.runReady()
            assertTrue(checkpointReached, "The actual replacement observer must reach finish before cancelDrain")
            assertTrue(wokeBeforeCancellation, "The selected sender must check its retired lifetime before drain cancellation")
            assertFalse(restartedBeforeCancellation, "A normally completed retirement cancellation must not restart the old drain")
            assertEquals(1, stale("queueConnection"))
            assertEquals(replacement, signals.snapshot.value.token)
            assertEquals(2, timers)
            assertTrue(accounting.isEmpty())
            assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            val failures = h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
            assertTrue(failures.none {
                it.operation.startsWith("terminal.") || it.operation == "dmQueueCallback" ||
                    it.operation == "channelQueueCallback" || it.operation == "sendQueueDrain"
            }, "Replacement must not terminalize or fail either lane: $failures")
            for ((message, receipt) in listOf(primary to pending, sentinel to sentinelPending)) {
                val retained = h.store.fetchPendingSendsForMessage(EntityKey(RADIO, message.id)).single()
                assertEquals(receipt.id, retained.id)
                assertEquals(0L, retained.attemptCount)
                assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
            }
        } finally {
            replacing = false
            dispatcher.checkpoint = null
            releaseTimer.complete(Unit)
            dispatched { queue.shutdown() }
            h.close()
        }
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
                queue.enqueueChannel(ChannelMessageEnvelope(
                    message.id, 0u, false, message.text, message.timestamp, "Test",
                    requireNotNull(message.channelOccupantID),
                ))
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
