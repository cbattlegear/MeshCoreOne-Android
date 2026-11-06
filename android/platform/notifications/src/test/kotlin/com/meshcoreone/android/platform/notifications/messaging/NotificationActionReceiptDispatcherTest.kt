// AndroidOnly: WP-215 Retained receipt and serialized replacement-consumer boundaries.
package com.meshcoreone.android.platform.notifications.messaging

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationReadReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationActionReceiptDispatcherTest {
    private val payload = NotificationPayload.DirectMessage(
        EntityKey(
            RadioId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
            UUID.fromString("20000000-0000-0000-0000-000000000002"),
        ),
        UUID.fromString("30000000-0000-0000-0000-000000000003"),
    )
    private val reply = NotificationResponse(payload, NotificationAction.REPLY, "Reply")

    @Before
    fun reset() = NotificationActionDispatcher.resetForTesting()

    @After
    fun cleanup() = NotificationActionDispatcher.resetForTesting()

    @Test
    fun acceptedReplyCannotReplayAfterCallerCancellation() = runTest {
        val first = owner()
        val replacement = owner()
        var calls = 0
        NotificationActionDispatcher.install(first) {
            calls++
            requireNotNull(currentCoroutineContext()[NotificationActionReceipt]).accepted()
            awaitCancellation()
        }
        assertEquals(EnqueueResult.DELIVERED_OR_QUEUED, NotificationActionDispatcher.enqueue(reply))
        runCurrent()
        assertEquals(1, calls)
        first.cancel()
        NotificationActionDispatcher.install(replacement) { calls++; true }
        runCurrent()
        assertEquals(1, calls)
        assertTrue(NotificationActionDispatcher.drainOnce())
    }

    @Test
    fun preAcceptanceCancellationRetriesWithTheSameReceipt() = runTest {
        val first = owner()
        val replacement = owner()
        var calls = 0
        var original: NotificationActionReceipt? = null
        NotificationActionDispatcher.install(first) {
            calls++
            original = currentCoroutineContext()[NotificationActionReceipt]
            awaitCancellation()
        }
        NotificationActionDispatcher.enqueue(reply)
        runCurrent()
        assertEquals(NotificationActionReceipt.State.READY, requireNotNull(original).state)
        first.cancel()
        NotificationActionDispatcher.install(replacement) {
            calls++
            assertSame(original, currentCoroutineContext()[NotificationActionReceipt])
            true
        }
        runCurrent()
        assertEquals(2, calls)
        assertTrue(NotificationActionDispatcher.drainOnce())
    }

    @Test
    fun uncertainSendIsNotReplayedEvenWhenConsumerReturnsFalse() = runTest {
        val first = owner()
        val replacement = owner()
        var calls = 0
        NotificationActionDispatcher.install(first) {
            calls++
            requireNotNull(currentCoroutineContext()[NotificationActionReceipt]).apply {
                wireStarted()
                uncertain()
            }
            false
        }
        NotificationActionDispatcher.enqueue(reply)
        runCurrent()
        NotificationActionDispatcher.install(replacement) { calls++; true }
        runCurrent()
        assertEquals(1, calls)
        assertTrue(NotificationActionDispatcher.drainOnce())
    }

    @Test
    fun partialReadIsNotReplayedWhenConsumerReturnsFalse() = runTest {
        val first = owner()
        val replacement = owner()
        var calls = 0
        NotificationActionDispatcher.install(first) {
            calls++
            requireNotNull(currentCoroutineContext()[NotificationReadReceipt]).apply {
                started()
                writeReturned()
                uncertain()
            }
            false
        }
        NotificationActionDispatcher.enqueue(NotificationResponse(payload, NotificationAction.MARK_READ, null))
        runCurrent()
        NotificationActionDispatcher.install(replacement) { calls++; true }
        runCurrent()
        assertEquals(1, calls)
        assertTrue(NotificationActionDispatcher.drainOnce())
    }

    @Test
    fun replacementWaitsForCancelledConsumerToReleaseDeliveryBeforeNextAction() = runTest {
        val first = owner()
        val replacement = owner()
        val unwinding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var firstCalls = 0
        var replacementCalls = 0
        NotificationActionDispatcher.install(first) {
            firstCalls++
            requireNotNull(currentCoroutineContext()[NotificationActionReceipt]).accepted()
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    unwinding.complete(Unit)
                    release.await()
                }
            }
        }
        try {
            NotificationActionDispatcher.enqueue(reply)
            NotificationActionDispatcher.enqueue(reply.copy(userText = "Second reply"))
            runCurrent()
            assertEquals(1, firstCalls)
            NotificationActionDispatcher.install(replacement) { replacementCalls++; true }
            runCurrent()
            assertTrue(unwinding.isCompleted)
            assertEquals(0, replacementCalls)
            assertFalse(release.isCompleted)
            release.complete(Unit)
            runCurrent()
            assertEquals(1, firstCalls)
            assertEquals(1, replacementCalls)
            assertTrue(NotificationActionDispatcher.drainOnce())
        } finally {
            release.complete(Unit)
            first.cancel()
            replacement.cancel()
        }
    }

    private fun TestScope.owner(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
}
