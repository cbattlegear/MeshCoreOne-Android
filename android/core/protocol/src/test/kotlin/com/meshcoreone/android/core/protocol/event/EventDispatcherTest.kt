// PortedFrom: MeshCore/Tests/MeshCoreTests/Events/EventDispatcherDropTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Tests/MeshCoreTests/Events/EventDispatcherFilteredSubscriptionTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Sources/MeshCore/Events/EventDispatcher.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.event

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class EventDispatcherTest {
    @Test
    fun `Slow consumer preserves latest 100 with exact counted and observed oldest drops`() = runTest {
        val drops = mutableListOf<EventDrop>()
        val dispatcher = EventDispatcher { drops += it }
        val subscription = dispatcher.subscribeTracked()
        repeat(200) { dispatcher.dispatch(MeshEvent.Advertisement(Bytes.of(it))) }
        assertEquals(100L, dispatcher.droppedEventCount)
        assertEquals((1L..100L).toList(), drops.map { it.totalDroppedEvents })
        assertTrue(drops.all { it.subscriptionId == subscription.id && it.caseName == "advertisement" })
        dispatcher.finishSubscription(subscription.id)
        assertEquals((100..199).map { MeshEvent.Advertisement(Bytes.of(it)) }, subscription.stream.toList())
    }

    @Test
    fun `Filtered subscription receives only matching events`() = runTest {
        val dispatcher = EventDispatcher { error("A filtered buffer must not overflow") }
        val subscription = dispatcher.subscribeTracked(EventFilter.anyAcknowledgement)
        val expected = MeshEvent.Acknowledgement(Bytes.of(0x10, 0x20, 0x30, 0x40), 500u)
        dispatcher.dispatch(MeshEvent.Advertisement(Bytes.of(1)))
        dispatcher.dispatch(expected)
        dispatcher.dispatch(MeshEvent.Advertisement(Bytes.of(2)))
        dispatcher.finishSubscription(subscription.id)
        assertEquals(listOf(expected), subscription.stream.toList())
    }

    @Test
    fun `Filtered subscription survives 500 nonmatching events without ACK eviction`() = runTest {
        val dispatcher = EventDispatcher { error("Nonmatching events must never enter this buffer") }
        val subscription = dispatcher.subscribeTracked(EventFilter.anyAcknowledgement)
        repeat(500) { dispatcher.dispatch(MeshEvent.Advertisement(Bytes.of(it % 256))) }
        val expected = MeshEvent.Acknowledgement(Bytes.of(0xab, 0xcd, 0xef, 0x12), 1234u)
        dispatcher.dispatch(expected)
        dispatcher.finishAllSubscriptions()
        assertEquals(listOf(expected), subscription.stream.toList())
        assertEquals(0L, dispatcher.droppedEventCount)
    }

    @Test
    fun `Registration is eager before collection or the first dispatch`() = runTest {
        val dispatcher = EventDispatcher()
        val stream = dispatcher.subscribe()
        assertEquals(1, dispatcher.subscriberCount)
        val expected = MeshEvent.Ok(0xffu)
        dispatcher.dispatch(expected)
        assertEquals(expected, stream.first())
        assertEquals(0, dispatcher.subscriberCount)
    }

    @Test
    fun `Each subscriber gets an independent complete broadcast and filter`() = runTest {
        val dispatcher = EventDispatcher()
        val all = dispatcher.subscribeTracked()
        val ack = dispatcher.subscribeTracked(EventFilter.anyAcknowledgement)
        val ok = dispatcher.subscribeTracked(EventFilter.ok)
        val values = listOf(MeshEvent.Ok(null), MeshEvent.Acknowledgement(Bytes.of(1)), MeshEvent.MessagesWaiting)
        values.forEach(dispatcher::dispatch)
        dispatcher.finishAllSubscriptions()
        assertEquals(values, all.stream.toList())
        assertEquals(listOf(values[1]), ack.stream.toList())
        assertEquals(listOf(values[0]), ok.stream.toList())
    }

    @Test
    fun `Drop accounting is per subscription and filtering happens before capacity`() = runTest {
        val dispatcher = EventDispatcher { }
        val first = dispatcher.subscribeTracked()
        val second = dispatcher.subscribeTracked()
        val filtered = dispatcher.subscribeTracked(EventFilter.anyAcknowledgement)
        repeat(150) { dispatcher.dispatch(MeshEvent.Ok(it.toUInt())) }
        assertEquals(100L, dispatcher.droppedEventCount)
        dispatcher.dispatch(MeshEvent.Acknowledgement(Bytes.of(1)))
        assertEquals(102L, dispatcher.droppedEventCount)
        dispatcher.finishAllSubscriptions()
        assertEquals(100, first.stream.toList().size)
        assertEquals(100, second.stream.toList().size)
        assertEquals(listOf(MeshEvent.Acknowledgement(Bytes.of(1))), filtered.stream.toList())
    }

    @Test
    fun `Finish specific subscription drains its existing queue and excludes later events`() = runTest {
        val dispatcher = EventDispatcher()
        val first = dispatcher.subscribeTracked()
        val second = dispatcher.subscribeTracked()
        dispatcher.dispatch(MeshEvent.Ok(1u))
        dispatcher.finishSubscription(first.id)
        dispatcher.finishSubscription(first.id)
        dispatcher.dispatch(MeshEvent.Ok(2u))
        dispatcher.finishAllSubscriptions()
        assertEquals(listOf(MeshEvent.Ok(1u)), first.stream.toList())
        assertEquals(listOf(MeshEvent.Ok(1u), MeshEvent.Ok(2u)), second.stream.toList())
        assertEquals(0, dispatcher.subscriberCount)
    }

    @Test
    fun `Finish all terminates suspended receivers and supports a new connection subscription`() = runTest {
        val dispatcher = EventDispatcher()
        val subscription = dispatcher.subscribeTracked()
        val result = async { subscription.stream.toList() }
        runCurrent()
        dispatcher.finishAllSubscriptions()
        assertEquals(emptyList(), result.await())
        val replacement = dispatcher.subscribeTracked()
        dispatcher.dispatch(MeshEvent.Ok(9u))
        dispatcher.finishAllSubscriptions()
        assertEquals(listOf(MeshEvent.Ok(9u)), replacement.stream.toList())
    }

    @Test
    fun `Consumer cancellation unregisters immediately and does not count discarded queue as overflow`() = runTest {
        val dispatcher = EventDispatcher { error("No overflow is expected") }
        val subscription = dispatcher.subscribeTracked()
        val consumer = launch { subscription.stream.toList() }
        runCurrent()
        consumer.cancelAndJoin()
        assertEquals(0, dispatcher.subscriberCount)
        repeat(200) { dispatcher.dispatch(MeshEvent.Ok(null)) }
        assertEquals(0L, dispatcher.droppedEventCount)
    }

    @Test
    fun `Taking a subset cleans up remaining buffered values without false drops`() = runTest {
        val dispatcher = EventDispatcher { error("No overflow is expected") }
        val subscription = dispatcher.subscribeTracked()
        repeat(10) { dispatcher.dispatch(MeshEvent.Ok(it.toUInt())) }
        assertEquals(listOf(MeshEvent.Ok(0u)), subscription.stream.take(1).toList())
        assertEquals(0, dispatcher.subscriberCount)
        assertEquals(0L, dispatcher.droppedEventCount)
    }

    @Test
    fun `A subscriber has exactly one consumer rather than accidentally splitting broadcast`() = runTest {
        val dispatcher = EventDispatcher()
        val subscription = dispatcher.subscribeTracked()
        dispatcher.finishAllSubscriptions()
        assertEquals(emptyList(), subscription.stream.toList())
        assertFailsWith<IllegalStateException> { subscription.stream.toList() }
    }

    @Test
    fun `Concurrent buffer consumption and overflow preserve order conservation and complete termination`() = runTest {
        val buffer = EventBuffer()
        val dropped = AtomicLong()
        val consumer = async(Dispatchers.Default) {
            buildList {
                while (true) {
                    val event = buffer.next() ?: break
                    add(event as MeshEvent.Ok)
                }
            }
        }
        withContext(Dispatchers.Default) {
            repeat(10_000) { value ->
                if (buffer.offer(MeshEvent.Ok(value.toUInt())) != null) dropped.incrementAndGet()
            }
            buffer.finish()
        }
        val received = consumer.await().map { checkNotNull(it.value).toInt() }
        assertEquals(10_000L, received.size.toLong() + dropped.get())
        assertEquals(received.distinct(), received)
        assertTrue(received.zipWithNext().all { (before, after) -> before < after })
        assertEquals(9_999, received.last())
    }
}
