// AndroidOnly: WP-311 Provider failure and owned-stream cancellation controls.
package com.meshcoreone.android.feature.nodes.ui

import android.os.CancellationSignal
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ContactAvatarMediaTest {
    private fun assertCauseChainContains(expected: Throwable, actual: Throwable) {
        assertTrue(generateSequence(actual) { it.cause }.take(16).any { it === expected },
            "The original provider failure must survive coroutine stack-trace recovery: $actual")
    }

    @Test fun providerRuntimeFailuresBecomeVisibleMediaFailuresAndCloseOwnedStreams() = runBlocking {
        val openFailure = IllegalArgumentException("Provider unavailable")
        val failure = assertFailsWith<IOException> {
            ContactAvatarMedia.readSelection { throw openFailure }
        }
        assertCauseChainContains(openFailure, failure)
        val closes = AtomicInteger()
        val readFailure = IllegalStateException("Provider disconnected")
        assertCauseChainContains(readFailure, assertFailsWith<IOException> {
            ContactAvatarMedia.readSelection {
                object : InputStream() {
                    override fun read(): Int = throw readFailure
                    override fun close() { closes.incrementAndGet() }
                }
            }
        })
        assertEquals(1, closes.get())
    }

    @Test fun cancellationClosesAnOwnedStreamEvenWhenItsReadIgnoresInterruption() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val closes = AtomicInteger()
        val cancellations = AtomicInteger()
        val stream = object : InputStream() {
            override fun read(): Int {
                entered.complete(Unit)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (released.count != 0L && System.nanoTime() < deadline) {
                    try {
                        released.await(20, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        // This fixture deliberately models a provider that ignores thread interruption.
                    }
                }
                check(released.count == 0L) { "Cancellation did not close the provider stream" }
                return -1
            }
            override fun close() {
                closes.incrementAndGet()
                released.countDown()
            }
        }
        val pending = launch {
            ContactAvatarMedia.readSelection(cancelOpen = { cancellations.incrementAndGet() }) { stream }
        }
        try {
            withTimeout(2_000) { entered.await() }
            withTimeout(2_000) { pending.cancelAndJoin() }
            assertTrue(pending.isCancelled)
            assertEquals(1, closes.get())
            assertEquals(1, cancellations.get())
        } finally {
            released.countDown()
            pending.cancelAndJoin()
        }
    }

    @Test fun cancellationSignalReleasesAProviderOpenThatIgnoresInterruptionAndClosesItsLateStream() = runBlocking {
        val signal = CancellationSignal()
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val released = CountDownLatch(1)
        val closes = AtomicInteger()
        val reads = AtomicInteger()
        val stream = object : InputStream() {
            override fun read(): Int {
                reads.incrementAndGet()
                return -1
            }
            override fun close() {
                closes.incrementAndGet()
            }
        }
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            ContactAvatarMedia.readSelection(cancelOpen = { signal.cancel() }) {
                signal.setOnCancelListener {
                    cancelled.countDown()
                    released.countDown()
                }
                entered.countDown()
                while (released.count != 0L) {
                    try {
                        released.await(20, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        // Opening models a provider that responds only to CancellationSignal.
                    }
                }
                stream
            }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertEquals(0L, cancelled.count, "The active open must receive signal cancellation")
            assertEquals(0, reads.get(), "A stream returned after cancellation must not be read")
            assertEquals(1, closes.get(), "The late stream must be closed exactly once")
        } finally {
            released.countDown()
            signal.cancel()
            pending.cancelAndJoin()
        }
    }

    @Test fun throwingCancellationSignalListenerCannotReplaceCancellationOrLeakItsLateStream() = runBlocking {
        val signal = CancellationSignal()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val closes = AtomicInteger()
        val reads = AtomicInteger()
        val stream = object : InputStream() {
            override fun read(): Int {
                reads.incrementAndGet()
                return -1
            }
            override fun close() {
                closes.incrementAndGet()
            }
        }
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            ContactAvatarMedia.readSelection(cancelOpen = { signal.cancel() }) {
                signal.setOnCancelListener {
                    released.countDown()
                    throw IllegalStateException("Provider cancellation failed")
                }
                entered.countDown()
                while (released.count != 0L) {
                    try {
                        released.await(20, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        // The open responds to the signal listener, not thread interruption.
                    }
                }
                stream
            }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertEquals(0, reads.get())
            assertEquals(1, closes.get())
            assertTrue(signal.isCanceled)
        } finally {
            released.countDown()
            signal.cancel()
            pending.cancelAndJoin()
        }
    }

    @Test fun successfulReadPreservesBytesAndClosesExactlyOnce() = runBlocking {
        val closes = AtomicInteger()
        val cancellations = AtomicInteger()
        val bytes = byteArrayOf(1, 2, 3)
        val result = ContactAvatarMedia.readSelection(cancelOpen = { cancellations.incrementAndGet() }) {
            object : ByteArrayInputStream(bytes) {
                override fun close() { closes.incrementAndGet(); super.close() }
            }
        }
        assertContentEquals(bytes, result)
        assertEquals(1, closes.get())
        assertEquals(0, cancellations.get())
    }

    @Test fun cancelledOpenClosesALateStreamBeforeReadingIt() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val closes = AtomicInteger()
        val reads = AtomicInteger()
        val stream = object : InputStream() {
            override fun read(): Int { reads.incrementAndGet(); return -1 }
            override fun close() { closes.incrementAndGet() }
        }
        val pending = launch {
            ContactAvatarMedia.readSelection {
                entered.complete(Unit)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (released.count != 0L && System.nanoTime() < deadline) {
                    try {
                        released.await(20, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        // Opening cannot publish an owned stream until this provider returns.
                    }
                }
                check(released.count == 0L) { "Late-open fixture was not released" }
                stream
            }
        }
        try {
            withTimeout(2_000) { entered.await() }
            pending.cancel()
            released.countDown()
            withTimeout(2_000) { pending.join() }
            assertTrue(pending.isCancelled)
            assertEquals(0, reads.get())
            assertEquals(1, closes.get())
        } finally {
            released.countDown()
            pending.cancelAndJoin()
        }
    }

    @Test fun providerSecurityFailureIsPreservedAndCloseFailureIsNotReportedAsSuccess() = runBlocking {
        val denied = SecurityException("Grant revoked")
        val securityFailure = assertFailsWith<SecurityException> {
            ContactAvatarMedia.readSelection { throw denied }
        }
        assertEquals(denied.message, securityFailure.message)
        assertCauseChainContains(denied, securityFailure)
        val closeFailure = UnsupportedOperationException("Provider close failed")
        assertCauseChainContains(closeFailure, assertFailsWith<IOException> {
            ContactAvatarMedia.readSelection {
                object : ByteArrayInputStream(byteArrayOf(1)) {
                    override fun close() { throw closeFailure }
                }
            }
        })
    }

    @Test fun cancellationWinsOverThrowingCancelAndCloseCallbacksAndStillClosesOnce() = runBlocking {
        for ((throwCancel, throwClose) in listOf(true to false, false to true, true to true)) {
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<CancellationException>()
            val released = CountDownLatch(1)
            val closes = AtomicInteger()
            val cancellations = AtomicInteger()
            val stream = object : InputStream() {
                override fun read(): Int {
                    entered.complete(Unit)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (released.count != 0L && System.nanoTime() < deadline) {
                        try {
                            released.await(20, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                            // The close callback must still run if provider cancellation fails.
                        }
                    }
                    check(released.count == 0L) { "Throwing cleanup did not release its stream" }
                    return -1
                }
                override fun close() {
                    closes.incrementAndGet()
                    released.countDown()
                    if (throwClose) throw IOException("Provider close failed")
                }
            }
            val pending = launch {
                try {
                    ContactAvatarMedia.readSelection(cancelOpen = {
                        cancellations.incrementAndGet()
                        if (throwCancel) throw IllegalStateException("Provider cancel failed")
                    }) { stream }
                } catch (failure: CancellationException) {
                    cancelled.complete(failure)
                }
            }
            try {
                withTimeout(2_000) { entered.await() }
                withTimeout(2_000) { pending.cancelAndJoin() }
                assertTrue(pending.isCancelled)
                assertTrue(cancelled.isCompleted, "Cleanup failure must not replace coroutine cancellation")
                assertEquals(1, cancellations.get())
                assertEquals(1, closes.get())
            } finally {
                released.countDown()
                pending.cancelAndJoin()
            }
        }
    }
}
