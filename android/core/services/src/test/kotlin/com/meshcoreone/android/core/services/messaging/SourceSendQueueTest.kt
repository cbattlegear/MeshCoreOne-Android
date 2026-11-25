// PortedFrom: MC1Tests/State/SendQueueTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.messaging

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class SourceSendQueueTest {
    private class Boom(val id: Int) : Exception()
    @TestFactory fun queues() = listOf(
        original("SendQueueTests", "Serial draining: enqueueing N envelopes triggers N sends in FIFO order") {
            val sent = mutableListOf<Int>()
            val queue = SendQueue<Int>(backgroundScope, { sent += it }, { _, _ -> fail("unexpected error") }, {})
            (1..5).forEach(queue::enqueue); runCurrent()
            assertEquals((1..5).toList(), sent); assertEquals(0, queue.count)
        },
        original("SendQueueTests", "CancellationError requeues at the front and the drain auto-respawns") {
            var calls = 0; val sent = mutableListOf<Int>()
            val queue = SendQueue<Int>(backgroundScope, { if (++calls == 1) throw CancellationException(); sent += it },
                { _, _ -> fail("cancellation reached onError") }, {})
            queue.enqueue(42); runCurrent(); assertEquals(2, calls); assertEquals(listOf(42), sent); assertEquals(0, queue.count)
        },
        original("SendQueueTests", "Non-cancellation error fires onError and the drain continues") {
            val sent = mutableListOf<Int>(); val errors = mutableListOf<Int>()
            val queue = SendQueue<Int>(backgroundScope, { if (it == 2) throw Boom(it); sent += it }, { _, id -> errors += id }, {})
            (1..3).forEach(queue::enqueue); runCurrent(); assertEquals(listOf(1, 3), sent); assertEquals(listOf(2), errors)
        },
        original("SendQueueTests", "onDrain fires exactly once per drain pass") {
            val gate = CompletableDeferred<Unit>(); val sent = mutableListOf<Int>(); var drained = 0
            val queue = SendQueue<Int>(backgroundScope, { gate.await(); sent += it }, { _, _ -> fail("unexpected") }, { drained++ })
            queue.enqueue(1); runCurrent(); queue.enqueue(2); queue.enqueue(3); gate.complete(Unit); runCurrent()
            assertEquals(listOf(1, 2, 3), sent); assertEquals(1, drained)
        },
        original("SendQueueTests", "onDrain receives the most recent non-cancellation error from the drain pass") {
            var last: Exception? = null
            val queue = SendQueue<Int>(backgroundScope, { throw Boom(it) }, { _, _ -> }, { last = it })
            (1..3).forEach(queue::enqueue); runCurrent(); assertEquals(3, assertIs<Boom>(last).id)
        },
        original("SendQueueTests", "onDrain receives nil when no envelope failed during the drain pass") {
            var fired = false; var last: Exception? = Boom(1)
            val queue = SendQueue<Int>(backgroundScope, {}, { _, _ -> fail("unexpected") }, { fired = true; last = it })
            queue.enqueue(1); runCurrent(); assertTrue(fired); assertNull(last)
        },
        original("SendQueueTests", "drain's outer loop processes envelopes enqueued during onDrain") {
            val gate = CompletableDeferred<Unit>(); val sent = mutableListOf<Int>(); var drains = 0
            val queue = SendQueue<Int>(backgroundScope, { sent += it }, { _, _ -> fail("unexpected") }, { if (++drains == 1) gate.await() })
            queue.enqueue(1); runCurrent(); queue.enqueue(2); gate.complete(Unit); runCurrent()
            assertEquals(listOf(1, 2), sent); assertEquals(2, drains)
        },
        original("SendQueueTests", "Drain task captures actor strongly: drain completes after only external ref drops") {
            val gate = CompletableDeferred<Unit>(); var completed = false
            fun submit() { SendQueue<Int>(backgroundScope, { gate.await(); completed = true }, { _, _ -> fail("unexpected") }, {}).enqueue(1) }
            submit(); runCurrent(); assertFalse(completed); gate.complete(Unit); runCurrent(); assertTrue(completed)
        },
    )
}
