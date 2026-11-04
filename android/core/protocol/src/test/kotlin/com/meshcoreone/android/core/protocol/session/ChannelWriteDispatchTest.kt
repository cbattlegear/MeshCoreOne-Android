// AndroidOnly: WP-107 Channel cancellation provenance on the actual generation serializer and dispatch fence.
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelWriteDispatchTest {
    @TestFactory
    fun realSessionCases() = listOf(
        nativeCase("finished contact dispatch detaches both connection callbacks while retaining provenance") {
            var admissions = 0
            var completions = 0
            val dispatch = ContactWriteDispatch(
                beforeDispatchAction = { admissions++ },
                onExchangeFinishedAction = { completions++ },
            )
            dispatch.beforeDispatch()
            val failure = IllegalStateException("Still uncertain")
            dispatch.markUncertain(failure)
            dispatch.exchangeDispatch.cancelled(ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN)
            dispatch.exchangeDispatch.exchangeFinished()
            dispatch.beforeDispatch()
            dispatch.exchangeDispatch.exchangeFinished()
            assertEquals(1, admissions)
            assertEquals(1, completions)
            assertEquals(ContactWriteDispatchOutcome.MAY_HAVE_WRITTEN, dispatch.outcome)
            assertSame(failure, dispatch.terminalFailure)
        },
        nativeCase("contact uncertainty yields only to definitive serializer no-send proof") {
            for (sent in listOf(false, true)) {
                var completionOutcome: ContactWriteDispatchOutcome? = null
                val dispatch = ContactWriteDispatch(onExchangeFinishedAction = { completionOutcome = it.outcome })
                val failure = IllegalStateException("Command refused or failed")
                supervisorScope {
                    val serializer = RequestResponseSerializer(this)
                    assertFailsWith<IllegalStateException> {
                        serializer.withOwnedSerialization(dispatch.exchangeDispatch) {
                            if (sent) beforeSend()
                            dispatch.markUncertain(failure)
                            throw failure
                        }
                    }
                }
                assertEquals(
                    if (sent) ContactWriteDispatchOutcome.MAY_HAVE_WRITTEN
                    else ContactWriteDispatchOutcome.DEFINITELY_UNSENT,
                    completionOutcome,
                )
                assertEquals(completionOutcome, dispatch.outcome)
                assertSame(failure, dispatch.terminalFailure)
            }
        },
        nativeCase("ordinary serializer failure accounts dispatch before the completion callback") {
            for (sent in listOf(false, true)) {
                var completionDisposition: ChannelWriteCancellationDisposition? = null
                lateinit var dispatch: ChannelWriteDispatch
                dispatch = ChannelWriteDispatch { completionDisposition = dispatch.cancellationDisposition }
                val failure = IllegalStateException("Injected exchange failure")
                supervisorScope {
                    val serializer = RequestResponseSerializer(this)
                    assertFailsWith<IllegalStateException> {
                        serializer.withOwnedSerialization(dispatch) {
                            if (sent) beforeSend()
                            throw failure
                        }
                    }
                }
                assertEquals(
                    if (sent) ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN
                    else ChannelWriteCancellationDisposition.DEFINITELY_UNSENT,
                    completionDisposition,
                )
                assertEquals(completionDisposition, dispatch.cancellationDisposition)
            }
        },
        nativeCase("missing generation proves dispatch unsent before serializer admission") {
            val f = fixture(); start(f)
            f.session.stop()
            val dispatch = ChannelWriteDispatch()
            assertFailsWith<MeshCoreException.SessionNotStarted> {
                f.session.setChannel(3u, "unsent", filled(0x33, 16), dispatch)
            }
            assertEquals(ChannelWriteCancellationDisposition.DEFINITELY_UNSENT, dispatch.cancellationDisposition)
            assertEquals(listOf(1), f.transport.sent.map { it[0].toInt() })
        },
        nativeCase("an already cancelled caller is definitely unsent before any serializer task exists") {
            val f = fixture(); start(f)
            val dispatch = ChannelWriteDispatch()
            val observed = CompletableDeferred<ChannelWriteCancellationDisposition>()
            val write = backgroundScope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    f.session.setChannel(3u, "cancelled", filled(0x33, 16), dispatch)
                } catch (cancelled: CancellationException) {
                    observed.complete(dispatch.cancellationDisposition)
                    throw cancelled
                }
            }
            write.cancel(CancellationException("before serializer task")); runCurrent()
            assertTrue(write.isCancelled)
            assertEquals(ChannelWriteCancellationDisposition.DEFINITELY_UNSENT, observed.await())
            assertEquals(1, f.transport.sent.size)
            assertEquals(0, f.session.core.generation().pending.size)
            val next = checkedRequest { f.session.setChannel(4u, "next", filled(0x44, 16)) }; runCurrent()
            assertEquals(2, f.transport.sent.size)
            f.transport.ok(); runCurrent(); next.await()
            f.session.stop()
        },
        nativeCase("cancellation queued on the real serializer fences any later channel dispatch") {
            val f = fixture(); start(f)
            val occupying = checkedRequest { f.session.getBattery() }; runCurrent()
            val dispatch = ChannelWriteDispatch()
            val observed = CompletableDeferred<ChannelWriteCancellationDisposition>()
            val write = backgroundScope.launch {
                try {
                    f.session.setChannel(3u, "cancelled", filled(0x33, 16), dispatch)
                } catch (cancelled: CancellationException) {
                    observed.complete(dispatch.cancellationDisposition)
                    throw cancelled
                }
            }
            runCurrent()
            assertEquals(ChannelWriteCancellationDisposition.UNKNOWN, dispatch.cancellationDisposition)
            assertEquals(listOf(1, 0x14), f.transport.sent.map { it[0].toInt() })
            write.cancel(CancellationException("queued caller")); runCurrent()
            assertTrue(write.isCancelled)
            assertEquals(ChannelWriteCancellationDisposition.DEFINITELY_UNSENT, observed.await())
            f.transport.receive(batteryPacket(4000)); runCurrent(); occupying.await()
            val next = checkedRequest { f.session.setChannel(4u, "next", filled(0x44, 16)) }; runCurrent()
            assertEquals(listOf(1, 0x14, 0x20), f.transport.sent.map { it[0].toInt() })
            assertEquals(4u.toUByte(), f.transport.sent.last()[1])
            f.transport.ok(); runCurrent(); next.await()
            assertEquals(0, f.session.core.generation().pending.size)
            f.session.stop()
        },
        nativeCase("the additive overload preserves exact bytes and normal ACK completion") {
            val f = fixture(); start(f)
            val dispatch = ChannelWriteDispatch()
            val expected = hex("2003") + Bytes.utf8("channel").paddedOrTruncated(32) + filled(0xab, 16)
            command(f, expected, raw(0)) {
                val role: ChannelSessionOps = f.session
                role.setChannel(3u, "channel", filled(0xab, 20), dispatch)
            }
            assertEquals(ChannelWriteCancellationDisposition.UNKNOWN, dispatch.cancellationDisposition)
            assertEquals(0, f.session.core.generation().pending.size)
            f.session.stop()
        },
        nativeCase("cancelling an entered transport before its cooperative write remains uncertain") {
            val f = fixture(); start(f)
            val gate = CompletableDeferred<Unit>()
            f.transport.beforeSend = { if (it[0].toInt() == 0x20) gate.await() }
            val dispatch = ChannelWriteDispatch()
            val write = checkedRequest { f.session.setChannel(0u, "held", filled(1, 16), dispatch) }; runCurrent()
            assertEquals(1, f.transport.sent.size)
            write.cancel(); runCurrent()
            assertFailsWith<CancellationException> { write.await() }
            assertEquals(ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN, dispatch.cancellationDisposition)
            gate.complete(Unit); runCurrent()
            assertEquals(1, f.transport.sent.size)
            assertFailsWith<MeshCoreException.ConnectionLost> { f.session.setChannel(1u, "next", filled(2, 16)) }
            f.session.stop()
        },
        nativeCase("a noncooperative transport may effect a write after cancellation only with uncertain disposition") {
            val f = fixture(); start(f)
            var continuation: Continuation<Unit>? = null
            val transportEffects = mutableListOf<Bytes>()
            f.transport.beforeSend = {
                if (it[0].toInt() == 0x20) {
                    suspendCoroutine<Unit> { waiting -> continuation = waiting }
                    transportEffects += it
                }
            }
            val dispatch = ChannelWriteDispatch()
            val write = checkedRequest { f.session.setChannel(2u, "delayed", filled(2, 16), dispatch) }; runCurrent()
            assertNotNull(continuation)
            write.cancel(); runCurrent()
            assertFailsWith<CancellationException> { write.await() }
            assertEquals(ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN, dispatch.cancellationDisposition)
            assertTrue(transportEffects.isEmpty())
            continuation!!.resume(Unit); runCurrent()
            assertEquals(1, transportEffects.size, "The driver deliberately effects a noncooperative late write")
            assertEquals(2u.toUByte(), transportEffects.single()[1])
            assertEquals(ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN, dispatch.cancellationDisposition)
            f.session.stop()
        },
        nativeCase("sent cancellation preserves caller exception and drains its ACK before the next write") {
            val f = fixture(); start(f)
            val dispatch = ChannelWriteDispatch()
            val observed = CompletableDeferred<CancellationException>()
            val write = backgroundScope.launch {
                try {
                    f.session.setChannel(0u, "first", filled(1, 16), dispatch)
                } catch (cancelled: CancellationException) {
                    observed.complete(cancelled)
                    throw cancelled
                }
            }
            runCurrent()
            write.cancel(CancellationException("original caller cancellation")); runCurrent()
            assertEquals("original caller cancellation", observed.await().message)
            assertTrue(write.isCancelled)
            assertEquals(ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN, dispatch.cancellationDisposition)
            val next = checkedRequest { f.session.setChannel(1u, "second", filled(2, 16)) }; runCurrent()
            assertEquals(2, f.transport.sent.size)
            f.transport.ok(); runCurrent()
            assertEquals(3, f.transport.sent.size)
            assertFalse(next.isCompleted, "The old ACK must not complete the new operation")
            f.transport.ok(); runCurrent(); next.await()
            assertEquals(ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN, dispatch.cancellationDisposition)
            f.session.stop()
        },
        nativeCase("missing ACK after sent cancellation retains same-generation correlation quarantine") {
            val f = fixture(); start(f)
            val dispatch = ChannelWriteDispatch()
            val write = checkedRequest { f.session.setChannel(0u, "first", filled(1, 16), dispatch) }; runCurrent()
            write.cancel(); runCurrent()
            assertFailsWith<CancellationException> { write.await() }
            advanceTimeBy(1000); runCurrent()
            f.transport.ok(); runCurrent()
            assertFailsWith<MeshCoreException.ConnectionLost> { f.session.setChannel(1u, "second", filled(2, 16)) }
            assertEquals(2, f.transport.sent.size)
            assertEquals(ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN, dispatch.cancellationDisposition)
            f.session.stop()
        },
        nativeCase("retiring a generation with a cancelled queued write cannot dispatch it after reconnect") {
            val f = fixture(); start(f)
            val occupying = checkedRequest { f.session.getBattery() }; runCurrent()
            val dispatch = ChannelWriteDispatch()
            val write = checkedRequest { f.session.setChannel(0u, "old", filled(1, 16), dispatch) }; runCurrent()
            write.cancel(); runCurrent()
            assertFailsWith<CancellationException> { write.await() }
            assertEquals(ChannelWriteCancellationDisposition.DEFINITELY_UNSENT, dispatch.cancellationDisposition)
            f.session.stop(); runCurrent()
            assertFailsWith<MeshCoreException.ConnectionLost> { occupying.await() }
            start(f)
            assertEquals(listOf(1, 0x14, 1), f.transport.sent.map { it[0].toInt() })
            val fresh = checkedRequest { f.session.setChannel(1u, "new", filled(2, 16)) }; runCurrent()
            f.transport.ok(); runCurrent(); fresh.await()
            assertEquals(4, f.transport.sent.size)
            f.session.stop()
        },
        nativeCase("retirement never upgrades a possibly written cancellation to definitely unsent") {
            val f = fixture(); start(f)
            val dispatch = ChannelWriteDispatch()
            val write = checkedRequest { f.session.setChannel(0u, "old", filled(1, 16), dispatch) }; runCurrent()
            write.cancel(); runCurrent()
            assertFailsWith<CancellationException> { write.await() }
            f.session.stop()
            assertEquals(ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN, dispatch.cancellationDisposition)
            start(f)
            val fresh = checkedRequest { f.session.setChannel(1u, "new", filled(2, 16)) }; runCurrent()
            f.transport.ok(); runCurrent(); fresh.await()
            assertEquals(ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN, dispatch.cancellationDisposition)
            f.session.stop()
        },
        nativeCase("a cancelled handle cannot be reused to attribute a later channel operation") {
            val f = fixture(); start(f)
            val occupying = checkedRequest { f.session.getBattery() }; runCurrent()
            val dispatch = ChannelWriteDispatch()
            val write = checkedRequest { f.session.setChannel(0u, "old", filled(1, 16), dispatch) }; runCurrent()
            write.cancel(); runCurrent()
            assertFailsWith<CancellationException> { write.await() }
            assertFailsWith<IllegalArgumentException> { f.session.setChannel(1u, "reuse", filled(2, 16), dispatch) }
            assertEquals(ChannelWriteCancellationDisposition.DEFINITELY_UNSENT, dispatch.cancellationDisposition)
            f.transport.receive(batteryPacket(4000)); runCurrent(); occupying.await()
            assertEquals(2, f.transport.sent.size)
            f.session.stop()
        },
        nativeCase("invalid channel input and device errors retain their existing typed behavior") {
            val f = fixture(); start(f)
            val invalid = ChannelWriteDispatch()
            assertFailsWith<MeshCoreException.InvalidInput> { f.session.setChannel(0u, "invalid", filled(0, 15), invalid) }
            assertEquals(1, f.transport.sent.size)
            assertEquals(ChannelWriteCancellationDisposition.UNKNOWN, invalid.cancellationDisposition)
            val dispatch = ChannelWriteDispatch()
            val write = checkedRequest { f.session.setChannel(0u, "valid", filled(1, 16), dispatch) }; runCurrent()
            f.transport.error(42); runCurrent()
            assertEquals(42u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> { write.await() }.code)
            assertEquals(ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN, dispatch.cancellationDisposition)
            f.session.stop()
        },
        nativeCase("an alternate implementation without dispatch accounting cannot claim definitely unsent") {
            val cancelled = CancellationException("alternate implementation")
            var calls = 0
            val role = object : ChannelSessionOps {
                override suspend fun getChannel(index: UByte): ChannelInfo = error("not called")
                override suspend fun getChannels(indices: List<UByte>): ChannelFetchResult = error("not called")
                override suspend fun setChannel(index: UByte, name: String, secret: Bytes) {
                    calls += 1
                    throw cancelled
                }
            }
            val dispatch = ChannelWriteDispatch()
            assertSame(cancelled, assertFailsWith<CancellationException> {
                role.setChannel(0u, "fallback", filled(1, 16), dispatch)
            })
            assertEquals(1, calls)
            assertEquals(ChannelWriteCancellationDisposition.UNKNOWN, dispatch.cancellationDisposition)
        },
    )

    @Test
    fun closedFenceRejectsOrdinaryAndCleanupDispatchEvenWithAnActiveCaller() {
        val caller = Job()
        val context = RequestContext(caller)
        assertEquals(ChannelWriteCancellationDisposition.DEFINITELY_UNSENT, context.cancelDispatch())
        assertFailsWith<CancellationException> { context.beforeSend() }
        assertFailsWith<CancellationException> { context.beforeSend(cleanup = true) }
        assertEquals(ChannelWriteCancellationDisposition.DEFINITELY_UNSENT, context.cancelDispatch())
        caller.cancel()
    }

    @Test
    fun activeCheckAndWriteMarkAreLinearizedAgainstCancellation() {
        assertEquals(ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN, activeCheckRace(fenced = true))
    }

    @Test
    fun unfencedNegativeControlProducesAFalseUnsentDisposition() {
        assertEquals(ChannelWriteCancellationDisposition.DEFINITELY_UNSENT, activeCheckRace(fenced = false))
    }

    private fun activeCheckRace(fenced: Boolean): ChannelWriteCancellationDisposition {
        val checkedActive = CountDownLatch(1)
        val releaseActiveCheck = CountDownLatch(1)
        val cancelling = CountDownLatch(1)
        val senderThread = AtomicReference<Thread>()
        val cancellationThread = AtomicReference<Thread>()
        val actual = Job()
        val caller = object : Job by actual {
            override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? {
                @Suppress("UNCHECKED_CAST")
                return if (key == Job) this as E else null
            }

            override val isActive: Boolean
                get() {
                    val snapshot = actual.isActive
                    checkedActive.countDown()
                    check(releaseActiveCheck.await(5, TimeUnit.SECONDS))
                    return snapshot
                }
        }
        val context = RequestContext(caller)
        val effects = AtomicInteger()
        val unfencedWritten = AtomicBoolean(false)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val sending = threads.submit {
                senderThread.set(Thread.currentThread())
                if (fenced) context.beforeSend()
                else {
                    caller.ensureActive()
                    unfencedWritten.set(true)
                }
                effects.incrementAndGet()
            }
            assertTrue(checkedActive.await(5, TimeUnit.SECONDS))
            actual.cancel()
            val disposition = threads.submit<ChannelWriteCancellationDisposition> {
                cancellationThread.set(Thread.currentThread())
                cancelling.countDown()
                if (fenced) context.cancelDispatch()
                else if (unfencedWritten.get()) ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN
                else ChannelWriteCancellationDisposition.DEFINITELY_UNSENT
            }
            assertTrue(cancelling.await(5, TimeUnit.SECONDS))
            if (fenced) {
                val bean = ManagementFactory.getThreadMXBean()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var contended = false
                while (System.nanoTime() < deadline) {
                    assertFalse(disposition.isDone, "An unfenced cancellation must fail this control before sender release")
                    val blocked = bean.getThreadInfo(cancellationThread.get().id)
                    if (blocked?.threadState == Thread.State.BLOCKED && blocked.lockOwnerId == senderThread.get().id) {
                        contended = true
                        break
                    }
                    Thread.yield()
                }
                assertTrue(contended, "Prove the cancellation thread is blocked on the sender's actual monitor")
            } else {
                assertEquals(ChannelWriteCancellationDisposition.DEFINITELY_UNSENT, disposition.get(5, TimeUnit.SECONDS))
                assertEquals(0, effects.get(), "The unfenced snapshot occurs before the paused sender dispatches")
            }
            releaseActiveCheck.countDown()
            sending.get(5, TimeUnit.SECONDS)
            assertEquals(1, effects.get())
            return disposition.get(5, TimeUnit.SECONDS)
        } finally {
            releaseActiveCheck.countDown()
            actual.cancel()
            threads.shutdownNow()
            assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
