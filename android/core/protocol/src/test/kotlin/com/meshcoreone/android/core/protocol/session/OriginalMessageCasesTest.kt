// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/AutoMessageFetchTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/GetMessageCoalescingTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/GetMessageSerializationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/GetMessageTimeoutTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/V115GetMessageTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.MeshTransportError
import com.meshcoreone.android.core.protocol.event.MessageResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OriginalMessageCasesTest {
    @TestFactory
    fun sourceCases() = listOf(
        original("AutoMessageFetchTests", "concurrent getMessage calls share one wire request") {
            val f = fixture()
            start(f)
            val first = checkedRequest { f.session.getMessage(timeout = 10.0) }
            val second = checkedRequest { f.session.getMessage(timeout = 10.0) }
            runCurrent()
            assertEquals(listOf(hex("01032020202020204d436f7265"), hex("0a")), f.transport.sent)
            f.transport.receive(raw(0x0a))
            runCurrent()
            assertEquals(MessageResult.NoMoreMessages, first.await())
            assertEquals(MessageResult.NoMoreMessages, second.await())
            assertEquals(2, f.transport.sent.size)
            f.session.stop()
        },
        original("AutoMessageFetchTests", "auto-fetch coalesces repeated messagesWaiting notifications") {
            val f = fixture()
            start(f)
            f.session.startAutoMessageFetching()
            repeat(3) { f.transport.receive(raw(0x83)) }
            runCurrent()
            assertEquals(2, f.transport.sent.size)
            f.transport.receive(raw(0x0a))
            runCurrent()
            assertTrue(f.transport.sent.size in 2..3)
            if (f.transport.sent.size == 3) {
                f.transport.receive(raw(0x0a))
                runCurrent()
            }
            assertTrue(f.transport.sent.size <= 3)
            f.session.stopAutoMessageFetching()
            assertEquals(0, f.session.core.generation().pending.size)
            f.session.stop()
        },
        original("AutoMessageFetchTests", "a newer messagesWaiting notification invalidates a retained empty poll") {
            val f = fixture()
            start(f)
            val caller = checkedRequest { f.session.getMessage() }
            runCurrent()
            val poll = assertNotNull(f.session.core.generation().poll)
            caller.cancel()
            runCurrent()
            assertFailsWith<CancellationException> { caller.await() }

            f.transport.receive(raw(0x0a))
            runCurrent()
            assertTrue(poll.isCompleted)
            assertSame(poll, f.session.core.generation().poll)

            f.session.startAutoMessageFetching()
            f.transport.receive(raw(0x83))
            runCurrent()
            assertEquals(3, f.transport.sent.size)

            f.transport.receive(raw(0x0a))
            runCurrent()
            assertEquals(3, f.transport.sent.size)
            f.session.stopAutoMessageFetching()
            f.session.stop()
        },
        original("AutoMessageFetchTests", "manual getMessage shares in-flight auto-fetch poll") {
            val f = fixture()
            start(f)
            f.session.startAutoMessageFetching()
            f.transport.receive(raw(0x83))
            runCurrent()
            val manual = checkedRequest { f.session.getMessage(timeout = 10.0) }
            runCurrent()
            assertEquals(2, f.transport.sent.size)
            f.transport.receive(raw(0x0a))
            runCurrent()
            assertEquals(MessageResult.NoMoreMessages, manual.await())
            assertEquals(2, f.transport.sent.size)
            f.session.stopAutoMessageFetching()
            f.session.stop()
        },
        original("GetMessageCoalescingTests", "a coalesced caller shares the leader's frame and result") {
            val f = fixture()
            start(f)
            val leader = checkedRequest { f.session.getMessage() }
            runCurrent()
            val follower = checkedRequest { f.session.getMessage() }
            runCurrent()
            assertEquals(2, f.transport.sent.size)
            f.transport.receive(raw(0x0a))
            runCurrent()
            assertEquals(MessageResult.NoMoreMessages, leader.await())
            assertEquals(leader.await(), follower.await())
            assertEquals(2, f.transport.sent.size)
            f.session.stop()
        },
        original("GetMessageCoalescingTests", "a coalesced caller is released when the leader times out") {
            val f = fixture()
            start(f)
            val leader = checkedRequest { f.session.getMessage(timeout = 0.15) }
            runCurrent()
            val follower = checkedRequest { f.session.getMessage() }
            runCurrent()
            advanceTimeBy(149)
            runCurrent()
            assertFalse(leader.isCompleted)
            assertFalse(follower.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertFailsWith<MeshCoreException.Timeout> { leader.await() }
            assertFailsWith<MeshCoreException.Timeout> { follower.await() }
            assertEquals(2, f.transport.sent.size)
            assertEquals(0, f.session.core.generation().pending.size)
            f.session.stop()
        },
        original("GetMessageCoalescingTests", "a coalesced caller is released when the leader fails with a device error") {
            val f = fixture()
            start(f)
            val leader = checkedRequest { f.session.getMessage() }
            runCurrent()
            val follower = checkedRequest { f.session.getMessage() }
            runCurrent()
            f.transport.error(9)
            runCurrent()
            assertEquals(9u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> { leader.await() }.code)
            assertEquals(9u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> { follower.await() }.code)
            assertEquals(2, f.transport.sent.size)

            val retry = checkedRequest { f.session.getMessage() }
            runCurrent()
            assertEquals(3, f.transport.sent.size)
            f.transport.receive(raw(0x0a))
            runCurrent()
            assertEquals(MessageResult.NoMoreMessages, retry.await())
            f.session.stop()
        },
        original("GetMessageCoalescingTests", "a cancelled coalesced caller does not hang") {
            val f = fixture()
            start(f)
            val leader = checkedRequest { f.session.getMessage(timeout = 0.3) }
            runCurrent()
            val follower = checkedRequest { f.session.getMessage() }
            runCurrent()
            follower.cancel()
            runCurrent()
            assertTrue(follower.isCompleted)
            assertFailsWith<CancellationException> { follower.await() }
            assertFalse(leader.isCompleted)
            advanceTimeBy(300)
            runCurrent()
            assertFailsWith<MeshCoreException.Timeout> { leader.await() }
            assertEquals(2, f.transport.sent.size)
            f.session.stop()
        },
        original("GetMessageCoalescingTests", "a completed GET result remains available after its caller is cancelled") {
            val f = fixture()
            start(f)
            val caller = checkedRequest { f.session.getMessage() }
            runCurrent()
            caller.cancel()
            runCurrent()
            assertFailsWith<CancellationException> { caller.await() }

            f.transport.receive(raw(0x0a))
            runCurrent()
            assertEquals(2, f.transport.sent.size)

            val retry = checkedRequest { f.session.getMessage() }
            runCurrent()
            assertEquals(MessageResult.NoMoreMessages, retry.await())
            assertNull(f.session.core.generation().poll)
            assertEquals(2, f.transport.sent.size)
            f.session.stop()
        },
        original("GetMessageCoalescingTests", "cancellation after poll completion retains the result until return") {
            val f = fixture()
            start(f)
            val caller = checkedRequest { f.session.getMessage() }
            runCurrent()
            val poll = assertNotNull(f.session.core.generation().poll)
            var cancelledAtPollCompletion = false
            poll.invokeOnCompletion {
                assertTrue(poll.isCompleted)
                cancelledAtPollCompletion = true
                caller.cancel()
            }

            f.transport.receive(raw(0x0a))
            runCurrent()
            assertTrue(cancelledAtPollCompletion)
            assertTrue(poll.isCompleted)
            assertFalse(poll.isCancelled)
            assertFailsWith<CancellationException> { caller.await() }
            assertEquals(2, f.transport.sent.size)

            val retry = checkedRequest { f.session.getMessage() }
            runCurrent()
            assertEquals(MessageResult.NoMoreMessages, retry.await())
            assertNull(f.session.core.generation().poll)
            assertEquals(2, f.transport.sent.size)
            f.session.stop()
        },
        original("GetMessageCoalescingTests", "a retired generation's completed poll is not rebound") {
            val f = fixture()
            start(f)
            val caller = checkedRequest { f.session.getMessage() }
            runCurrent()
            val retiredGeneration = f.session.core.generation()
            val retiredPoll = assertNotNull(retiredGeneration.poll)
            caller.cancel()
            runCurrent()
            assertFailsWith<CancellationException> { caller.await() }

            f.transport.receive(raw(0x0a))
            runCurrent()
            assertTrue(retiredPoll.isCompleted)
            assertFalse(retiredPoll.isCancelled)
            assertSame(retiredPoll, retiredGeneration.poll)
            f.session.stop()
            assertSame(retiredPoll, retiredGeneration.poll)

            start(f)
            val newGeneration = f.session.core.generation()
            assertNotSame(retiredGeneration, newGeneration)
            val next = checkedRequest { f.session.getMessage() }
            runCurrent()
            assertNotSame(retiredPoll, newGeneration.poll)
            assertEquals(4, f.transport.sent.size)
            f.transport.receive(raw(0x0a))
            runCurrent()
            assertEquals(MessageResult.NoMoreMessages, next.await())
            assertSame(retiredPoll, retiredGeneration.poll)
            f.session.stop()
        },
        original("GetMessageCoalescingTests", "an inline poll failure preserves its transport error") {
            val session = MeshCoreSession.offlinePlaceholder(
                SessionRadioTransport(),
                clock = SchedulerClock(testScheduler),
                coroutineContext = Dispatchers.Unconfined,
            )
            try {
                assertFailsWith<MeshTransportError.NotConnected> { session.getMessage() }
            } finally {
                session.stop()
            }
        },
        original("GetMessageSerializationTests", "an unrelated error does not fail an in-flight getMessage") {
            val f = fixture()
            start(f)
            val reset = checkedRequest { f.session.factoryReset() }
            runCurrent()
            val message = checkedRequest { f.session.getMessage() }
            runCurrent()
            assertEquals(2, f.transport.sent.size)
            assertEquals(hex("337265736574"), f.transport.sent.last())
            f.transport.error(17)
            runCurrent()
            assertEquals(17u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> { reset.await() }.code)
            assertEquals(3, f.transport.sent.size)
            assertEquals(hex("0a"), f.transport.sent.last())
            f.transport.receive(raw(0x0a))
            runCurrent()
            assertEquals(MessageResult.NoMoreMessages, message.await())
            f.session.stop()
        },
        original("GetMessageTimeoutTests", "getMessage times out when no response arrives") {
            val f = fixture(SessionConfiguration(defaultTimeout = 0.02, clientIdentifier = "MCore"))
            f.transport.connect()
            val task = checkedRequest { f.session.getMessage() }
            runCurrent()
            assertEquals(listOf(hex("0a")), f.transport.sent)
            advanceTimeBy(20)
            runCurrent()
            assertFailsWith<MeshCoreException.Timeout> { task.await() }
            f.session.stop()
        },
        original("GetMessageTimeoutTests", "getMessage timeout override can be shorter than the session default") {
            val f = fixture(SessionConfiguration(defaultTimeout = 2.0, clientIdentifier = "MCore"))
            f.transport.connect()
            val task = checkedRequest { f.session.getMessage(timeout = 0.02) }
            runCurrent()
            advanceTimeBy(19)
            runCurrent()
            assertFalse(task.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertFailsWith<MeshCoreException.Timeout> { task.await() }
            assertEquals(20L, testScheduler.currentTime)
            f.session.stop()
        },
        original("GetMessageTimeoutTests", "getMessage timeout override can extend beyond the session default") {
            val f = fixture(SessionConfiguration(defaultTimeout = 0.02, clientIdentifier = "MCore"))
            f.transport.connect()
            val task = checkedRequest { f.session.getMessage(timeout = 0.12) }
            runCurrent()
            advanceTimeBy(80)
            runCurrent()
            assertFalse(task.isCompleted)
            advanceTimeBy(40)
            runCurrent()
            assertFailsWith<MeshCoreException.Timeout> { task.await() }
            assertEquals(120L, testScheduler.currentTime)
            f.session.stop()
        },
        original("V115GetMessageTests", "getMessage yields channelDatagram") {
            val f = fixture()
            start(f)
            val result = command(f, hex("0a"), hex("1b00000002ffffff03deadbe")) { f.session.getMessage() }
            val datagram = assertIs<MessageResult.ChannelDatagram>(result).datagram
            assertEquals(2u.toUByte(), datagram.channelIndex)
            assertEquals(0xffffu.toUShort(), datagram.dataType)
            assertEquals(hex("deadbe"), datagram.data)
            f.session.stop()
        },
    )
}
