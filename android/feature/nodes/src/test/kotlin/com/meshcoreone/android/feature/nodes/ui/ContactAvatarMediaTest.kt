// AndroidOnly: WP-311 Provider failure and owned-stream cancellation controls.
package com.meshcoreone.android.feature.nodes.ui

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class ContactAvatarMediaTest {
    @Test fun providerRuntimeFailuresBecomeVisibleMediaFailuresAndCloseOwnedStreams() = runBlocking {
        val openFailure = IllegalArgumentException("Provider unavailable")
        val failure = assertFailsWith<IOException> {
            ContactAvatarMedia.readSelection { throw openFailure }
        }
        assertSame(openFailure, failure.cause)
        val closes = AtomicInteger()
        val readFailure = IllegalStateException("Provider disconnected")
        assertSame(readFailure, assertFailsWith<IOException> {
            ContactAvatarMedia.readSelection {
                object : InputStream() {
                    override fun read(): Int = throw readFailure
                    override fun close() { closes.incrementAndGet() }
                }
            }
        }.cause)
        assertEquals(1, closes.get())
    }

    @Test fun cancellationClosesAnOwnedStreamEvenWhenItsReadIgnoresInterruption() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val closes = AtomicInteger()
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
        val pending = launch { ContactAvatarMedia.readSelection { stream } }
        try {
            withTimeout(2_000) { entered.await() }
            withTimeout(2_000) { pending.cancelAndJoin() }
            assertTrue(pending.isCancelled)
            assertEquals(1, closes.get())
        } finally {
            released.countDown()
            pending.cancelAndJoin()
        }
    }

    @Test fun successfulReadPreservesBytesAndClosesExactlyOnce() = runBlocking {
        val closes = AtomicInteger()
        val bytes = byteArrayOf(1, 2, 3)
        val result = ContactAvatarMedia.readSelection {
            object : ByteArrayInputStream(bytes) {
                override fun close() { closes.incrementAndGet(); super.close() }
            }
        }
        assertContentEquals(bytes, result)
        assertEquals(1, closes.get())
    }
}
