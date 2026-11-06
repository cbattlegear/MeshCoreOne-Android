// AndroidOnly: WP-208 Nonactivated bounded critical ingress custody and independent terminal observation.
package com.meshcoreone.android.core.protocol.event

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SchedulerClock
import com.meshcoreone.android.core.protocol.session.SessionDiagnostic
import com.meshcoreone.android.core.protocol.session.SessionRadioTransport
import com.meshcoreone.android.core.protocol.session.fixture
import com.meshcoreone.android.core.protocol.session.start
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CriticalEventSubscriptionTest {
    @Test
    fun unconfinedPendingReplyCanReenterTeardownWithoutLockHeldTerminalNotification() = runTest {
        pendingReplyReentry(triggerAck = false)
    }

    @Test
    fun criticalAckIsCapturedBeforeItsOwnPendingReplyReentersTeardown() = runTest {
        pendingReplyReentry(triggerAck = true)
    }

    private suspend fun TestScope.pendingReplyReentry(triggerAck: Boolean) {
        val f = fixture()
        start(f)
        val subscription = f.session.criticalEvents(CriticalEventKind.ACKNOWLEDGEMENTS)
        val generation = f.session.core.generation()
        val filter: (MeshEvent) -> Boolean = {
            if (triggerAck) it is MeshEvent.Acknowledgement else it is MeshEvent.Ok
        }
        val pending = f.session.core.register(generation, filter)
        var enteredUnderLock = false
        var notifications = 0
        val owner = async(Dispatchers.Unconfined) {
            subscription.awaitTermination().also {
                assertFalse(f.session.core.isLockHeldByCurrentThread())
                notifications++
            }
        }
        val reentrant = async(Dispatchers.Unconfined) {
            val reply = pending.channel.receive()
            if (triggerAck) assertIs<MeshEvent.Acknowledgement>(reply) else assertIs<MeshEvent.Ok>(reply)
            enteredUnderLock = f.session.core.isLockHeldByCurrentThread()
            f.session.stop()
        }
        val endingPending = f.session.core.register(generation, filter)
        var closedUnderLock = false
        val ending = async(Dispatchers.Unconfined) {
            assertFailsWith<MeshCoreException.ConnectionLost> { endingPending.channel.receive() }
            closedUnderLock = f.session.core.isLockHeldByCurrentThread()
            f.session.core.unregister(generation, endingPending)
        }
        try {
            val ack = ByteWriter().appendUInt8(ResponseCode.ACK.rawValue).appendUInt32LE(9u).toBytes()
            if (!triggerAck) {
                f.transport.receive(ack)
                runCurrent()
            }
            val wire = async {
                assertFailsWith<MeshCoreException.ConnectionLost> {
                    f.session.sendChannelMessage(0u, "reply races teardown", Instant.ofEpochSecond(1))
                }
            }
            runCurrent()
            assertEquals(CommandCode.SEND_CHANNEL_MESSAGE.rawValue, f.transport.sent.last()[0])
            assertEquals(3, generation.pending.size, "Exercise reentry with an unconfined closing waiter and actual wire waiter")
            if (triggerAck) f.transport.receive(ack) else f.transport.ok()
            runCurrent()
            reentrant.await()
            ending.await()
            wire.await()
            assertTrue(enteredUnderLock, "Exercise actual pending-response trySend reentry, not a later scheduled stop")
            assertTrue(closedUnderLock, "Exercise synchronous unregister from a pending channel's close callback")
            val terminal = owner.await()
            assertEquals(CriticalEventEnd.GENERATION_ENDED, terminal.reason)
            assertEquals(listOf(MeshEvent.Acknowledgement(Bytes.of(9, 0, 0, 0))), terminal.backlog)
            assertSame(terminal, subscription.terminal)
            assertSame(terminal, subscription.awaitTermination())
            assertTrue(generation.pending.isEmpty())
            assertEquals(1, notifications)
        } finally {
            f.session.core.unregister(generation, pending)
            f.session.core.unregister(generation, endingPending)
            withContext(NonCancellable) { f.session.stop() }
        }
    }

    @Test
    fun consumerCloseAndExceptionalNestedExitStillFlushAfterOutermostRelease() = runTest {
        val f = fixture()
        start(f)
        val subscription = f.session.criticalEvents(CriticalEventKind.ACKNOWLEDGEMENTS)
        val owner = async(Dispatchers.Unconfined) {
            subscription.awaitTermination().also { assertFalse(f.session.core.isLockHeldByCurrentThread()) }
        }
        val original = IllegalStateException("leave the outer boundary exceptionally")
        try {
            val failure = assertFailsWith<IllegalStateException> {
                f.session.core.withLock {
                    f.session.core.withLock { subscription.close() }
                    assertFalse(owner.isCompleted)
                    throw original
                }
            }
            assertSame(original, failure)
            val terminal = owner.await()
            assertEquals(CriticalEventEnd.CONSUMER_ENDED, terminal.reason)
            assertSame(terminal, subscription.terminal)
        } finally {
            withContext(NonCancellable) { f.session.stop() }
        }
    }

    @Test
    fun synchronousReaderTerminationPublishesAfterOutermostStartupLockRelease() = runTest {
        val radio = SessionRadioTransport()
        val ack = ByteWriter().appendUInt8(ResponseCode.ACK.rawValue).appendUInt32LE(7u).toBytes()
        val transport = object : MeshTransport by radio {
            override suspend fun receivedData() = flow { emit(ack) }
        }
        lateinit var session: MeshCoreSession
        var endedUnderLock = false
        session = MeshCoreSession(
            transport, clock = SchedulerClock(testScheduler),
            coroutineContext = backgroundScope.coroutineContext + Dispatchers.Unconfined,
            onDiagnostic = {
                if (it is SessionDiagnostic.StreamEnded) {
                    endedUnderLock = session.core.isLockHeldByCurrentThread()
                }
            },
        )
        val subscription = session.criticalEvents(CriticalEventKind.ACKNOWLEDGEMENTS)
        var notifications = 0
        val owner = async(Dispatchers.Unconfined) {
            subscription.awaitTermination().also {
                assertFalse(session.core.isLockHeldByCurrentThread())
                notifications++
            }
        }
        try {
            assertFailsWith<MeshCoreException.ConnectionLost> { session.start() }
            assertTrue(endedUnderLock, "Exercise synchronous reader completion inside the actual startup boundary")
            val terminal = owner.await()
            assertEquals(CriticalEventEnd.GENERATION_ENDED, terminal.reason)
            assertEquals(listOf(MeshEvent.Acknowledgement(Bytes.of(7, 0, 0, 0))), terminal.backlog)
            assertSame(terminal, subscription.terminal)
            assertSame(terminal, subscription.awaitTermination())
            assertEquals(1, notifications)
        } finally {
            withContext(NonCancellable) { session.stop() }
        }
    }

    @Test
    fun replacementGenerationCannotInheritOldCustodyOrRefreshItsSubscription() = runTest {
        val f = fixture()
        start(f)
        val old = f.session.criticalEvents(CriticalEventKind.ACKNOWLEDGEMENTS)
        f.transport.receive(ByteWriter().appendUInt8(ResponseCode.ACK.rawValue).appendUInt32LE(1u).toBytes())
        runCurrent()
        f.session.stop()
        val originalFailure = old.awaitTermination()
        start(f)
        try {
            val current = f.session.criticalEvents(CriticalEventKind.ACKNOWLEDGEMENTS)
            assertNotSame(old, current)
            assertNotSame(old.owner, current.owner)
            assertTrue(current.owner.generation > old.owner.generation)
            assertNull(current.terminal)
            assertSame(originalFailure, old.terminal)
            assertEquals(1, originalFailure.backlog.size)
            assertFailsWith<CriticalEventTerminal> { old.stream.toList() }
        } finally {
            withContext(NonCancellable) { f.session.stop() }
        }
    }

    @Test
    fun actualGenerationSignalsOutsideProtocolLockAndStillReceivesWireReplyAfterOverflow() = runTest {
        val f = fixture()
        start(f)
        try {
            val subscription = f.session.criticalEvents(CriticalEventKind.ACKNOWLEDGEMENTS)
            val owner = async(Dispatchers.Unconfined) {
                subscription.awaitTermination().also {
                    assertFalse(f.session.core.isLockHeldByCurrentThread())
                }
            }
            repeat(EventDispatcher.BUFFER_CAPACITY + 1) { index ->
                f.transport.receive(ByteWriter().appendUInt8(ResponseCode.ACK.rawValue)
                    .appendUInt32LE(index.toUInt()).toBytes())
                runCurrent()
            }
            assertEquals(CriticalEventEnd.OVERFLOW, owner.await().reason)
            f.transport.onSend = { packet ->
                assertEquals(CommandCode.SEND_CHANNEL_MESSAGE.rawValue, packet[0])
                f.transport.ok()
            }
            val wire = async { f.session.sendChannelMessage(0u, "still correlates", Instant.ofEpochSecond(1)) }
            runCurrent()
            wire.await()
            assertSame(subscription, f.session.criticalEvents(CriticalEventKind.ACKNOWLEDGEMENTS))
            val rx = f.session.criticalEvents(CriticalEventKind.RECEIVE_LOG)
            val ending = async(Dispatchers.Unconfined) {
                rx.awaitTermination().also { assertFalse(f.session.core.isLockHeldByCurrentThread()) }
            }
            f.session.stop()
            assertEquals(CriticalEventEnd.GENERATION_ENDED, ending.await().reason)
            assertSame(subscription.terminal, owner.await())
        } finally {
            withContext(NonCancellable) { f.session.stop() }
        }
    }

    @Test
    fun overflowPreservesOriginalBoundedBacklogAndFirstOverflowWithoutReplacingOrdinaryPolicy() = runTest {
        val dispatcher = EventDispatcher { }
        val owner = CriticalEventOwner(7)
        val critical = dispatcher.subscribeCritical(owner, CriticalEventKind.ACKNOWLEDGEMENTS)
        val ordinary = dispatcher.subscribeTracked(EventFilter.anyAcknowledgement)
        val values = (0..EventDispatcher.BUFFER_CAPACITY).map { MeshEvent.Acknowledgement(Bytes.of(it)) }
        values.forEach(dispatcher::dispatch)
        val failure = critical.awaitTermination()
        assertSame(owner, failure.owner)
        assertEquals(7L, failure.owner.generation)
        assertEquals(CriticalEventEnd.OVERFLOW, failure.reason)
        assertNull(failure.inFlight)
        assertEquals(values.dropLast(1), failure.backlog)
        assertSame(values.last(), failure.firstOverflow)
        assertSame(critical, dispatcher.subscribeCritical(owner, critical.kind))
        repeat(300) { dispatcher.dispatch(MeshEvent.Acknowledgement(Bytes.of(255))) }
        assertSame(failure, critical.terminal)
        assertEquals(values.dropLast(1), failure.backlog)
        assertFailsWith<CriticalEventTerminal> { critical.stream.toList() }
        dispatcher.finishSubscription(ordinary.id)
        assertEquals(EventDispatcher.BUFFER_CAPACITY, ordinary.stream.toList().size)
        assertEquals(301L, dispatcher.droppedEventCount)
    }

    @Test
    fun blockedConsumerDoesNotBlockIndependentOwnerTerminalAndRetainsInFlightIdentity() = runTest {
        val dispatcher = EventDispatcher()
        val owner = CriticalEventOwner(1)
        val subscription = dispatcher.subscribeCritical(owner, CriticalEventKind.ACKNOWLEDGEMENTS)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = MeshEvent.Acknowledgement(Bytes.of(1))
        val observer = async { subscription.awaitTermination() }
        val consumer = async {
            assertFailsWith<CriticalEventTerminal> {
                subscription.stream.collect {
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        dispatcher.dispatch(first)
        runCurrent()
        assertTrue(entered.isCompleted)
        repeat(EventDispatcher.BUFFER_CAPACITY + 1) {
            dispatcher.dispatch(MeshEvent.Acknowledgement(Bytes.of(2)))
        }
        runCurrent()
        assertTrue(observer.isCompleted)
        assertFalse(consumer.isCompleted)
        val failure = observer.await()
        assertSame(first, failure.inFlight)
        assertEquals(EventDispatcher.BUFFER_CAPACITY, failure.backlog.size)
        release.complete(Unit)
        assertSame(failure, consumer.await())
    }

    @Test
    fun cancellationPreservesUnresolvedOriginalWorkAndCannotRefreshSubscription() = runTest {
        val dispatcher = EventDispatcher()
        val owner = CriticalEventOwner(3)
        val subscription = dispatcher.subscribeCritical(owner, CriticalEventKind.ACKNOWLEDGEMENTS)
        val first = MeshEvent.Acknowledgement(Bytes.of(1))
        val second = MeshEvent.Acknowledgement(Bytes.of(2))
        val consumer = launch {
            subscription.stream.collect { CompletableDeferred<Unit>().await() }
        }
        dispatcher.dispatch(first)
        runCurrent()
        dispatcher.dispatch(second)
        consumer.cancelAndJoin()
        val failure = subscription.awaitTermination()
        assertEquals(CriticalEventEnd.CONSUMER_ENDED, failure.reason)
        assertSame(first, failure.inFlight)
        assertEquals(listOf(second), failure.backlog)
        assertSame(subscription, dispatcher.subscribeCritical(owner, subscription.kind))
        assertFailsWith<IllegalStateException> {
            dispatcher.subscribeCritical(CriticalEventOwner(3), subscription.kind)
        }
    }

    @Test
    fun nonmatchingEventsDoNotFillCriticalBufferAndGenerationEndIsNotSuccess() = runTest {
        val dispatcher = EventDispatcher { }
        val subscription = dispatcher.subscribeCritical(CriticalEventOwner(4), CriticalEventKind.ACKNOWLEDGEMENTS)
        repeat(500) { dispatcher.dispatch(MeshEvent.Advertisement(Bytes.of(1))) }
        assertNull(subscription.terminal)
        val ack = MeshEvent.Acknowledgement(Bytes.of(2))
        dispatcher.dispatch(ack)
        dispatcher.finishAllSubscriptions()
        val failure = subscription.awaitTermination()
        assertEquals(CriticalEventEnd.GENERATION_ENDED, failure.reason)
        assertEquals(listOf(ack), failure.backlog)
        assertFailsWith<CriticalEventTerminal> { subscription.stream.toList() }
    }
}
