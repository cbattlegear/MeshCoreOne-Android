// AndroidOnly: WP-218 native allocation ownership at the cancellable decode return boundary.
package com.meshcoreone.android.app.content

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.services.content.GifImageMetadata
import com.meshcoreone.android.core.services.content.ImageDecodeOutcome
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class DecodedBitmapOwnershipTest {
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun runNext() = pending.removeFirst().run()
    }

    private fun DecodedBitmapOwnership.decode(
        animated: Boolean,
        allocations: MutableList<Bitmap>,
    ): ImageDecodeOutcome.Decoded {
        val bytes = NativeImageFixtures.png(8, 4)
        val frames = List(if (animated) 2 else 1) {
            own(requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))).also(allocations::add)
        }
        return ImageDecodeOutcome.Decoded(
            if (animated) GifDecodedImageHandle(
                frames, GifImageMetadata(8, 4, listOf(20L, 100L).snapshot()), 8, 4,
            ) else BitmapDecodedImageHandle(frames.single()),
        )
    }

    @Test
    fun `cancellation after native decode but before caller resumes recycles all undelivered pixels`() = runTest {
        for (animated in listOf(false, true)) {
            val worker = QueuedDispatcher()
            val allocations = mutableListOf<Bitmap>()
            var finishedDecode = false
            var published = false
            val job = launch {
                decodeWithBitmapOwnership(worker) {
                    decode(animated, allocations).also { finishedDecode = true }
                }
                published = true
            }
            runCurrent()
            worker.runNext()
            assertTrue(finishedDecode)
            assertEquals(if (animated) 2 else 1, allocations.size)
            assertTrue(allocations.none { it.isRecycled })
            job.cancel()
            runCurrent()
            job.join()
            assertTrue(job.isCancelled)
            assertFalse(published)
            assertTrue(allocations.all { it.isRecycled })
        }
    }

    @Test
    fun `cancellation during native decode recycles partial animation frames`() = runTest {
        val allocations = mutableListOf<Bitmap>()
        val job = launch {
            decodeWithBitmapOwnership(Dispatchers.Unconfined) {
                decode(animated = true, allocations)
                currentCoroutineContext().job.cancel()
                currentCoroutineContext().ensureActive()
                error("Cancelled decode must not finish")
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(2, allocations.size)
        assertTrue(allocations.all { it.isRecycled })
    }

    @Test
    fun `failure results and exceptions retain no native allocations`() = runTest {
        for (throwFailure in listOf(false, true)) {
            val allocations = mutableListOf<Bitmap>()
            try {
                val result = decodeWithBitmapOwnership(Dispatchers.Unconfined) {
                    decode(animated = true, allocations)
                    if (throwFailure) throw IllegalStateException("handle construction failed")
                    ImageDecodeOutcome.Failed("native decode failed")
                }
                assertFalse(throwFailure)
                assertIs<ImageDecodeOutcome.Failed>(result)
            } catch (failure: IllegalStateException) {
                assertTrue(throwFailure)
                assertEquals("handle construction failed", failure.message)
            }
            assertEquals(2, allocations.size)
            assertTrue(allocations.all { it.isRecycled })
        }
    }

    @Test
    fun `successful delivery keeps bitmap and animation pixels alive for their consumers`() = runTest {
        for (animated in listOf(false, true)) {
            val worker = QueuedDispatcher()
            val allocations = mutableListOf<Bitmap>()
            var published = false
            val job = launch {
                assertIs<ImageDecodeOutcome.Decoded>(decodeWithBitmapOwnership(worker) {
                    decode(animated, allocations)
                })
                published = true
            }
            try {
                runCurrent()
                worker.runNext()
                runCurrent()
                job.join()
                assertTrue(published)
                assertFalse(job.isCancelled)
                assertEquals(if (animated) 2 else 1, allocations.size)
                assertTrue(allocations.none { it.isRecycled })
            } finally {
                allocations.forEach { it.recycle() }
            }
        }
    }

    @Test
    fun `cancellation before dispatch allocates no pixels`() = runTest {
        val worker = QueuedDispatcher()
        var enteredDecode = false
        val job = launch {
            decodeWithBitmapOwnership(worker) {
                enteredDecode = true
                ImageDecodeOutcome.Failed("unused")
            }
        }
        runCurrent()
        job.cancel()
        worker.runNext()
        runCurrent()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(enteredDecode)
    }
}
