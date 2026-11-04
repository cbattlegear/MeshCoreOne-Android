// AndroidOnly: WP-401 Deterministic cold-start and stale-radio action dispatch evidence.
// AndroidOnly: WP-215 Accepted/uncertain action cancellation, replacement and capacity regressions.
package com.meshcoreone.android.platform.notifications.messaging

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationActionDispatcherTest {
    @After
    fun reset() {
        NotificationActionDispatcher.resetForTesting()
    }

    @Test
    fun receiverPreservesExactReplyTextWithoutAdmittingWhitespaceOnlyInput() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = "  Original reply\nwith trailing space  "
        val payload = response("10000000-0000-0000-0000-000000000001").payload
        val delivered = mutableListOf<NotificationResponse>()
        NotificationActionDispatcher.install(this) { delivered += it; true }
        fun replyIntent(text: String): Intent {
            val intent = NotificationPayloadCodec.write(
                Intent(context, NotificationActionReceiver::class.java)
                    .putExtra(NotificationActionReceiver.EXTRA_ACTION, NotificationAction.REPLY.rawValue),
                payload,
            )
            val input = RemoteInput.Builder(NotificationActionReceiver.REMOTE_INPUT_KEY).build()
            RemoteInput.addResultsToIntent(
                arrayOf(input), intent,
                Bundle().apply { putCharSequence(input.resultKey, text) },
            )
            return intent
        }
        val receiver = NotificationActionReceiver()
        receiver.onReceive(context, replyIntent(" \t\n "))
        receiver.onReceive(context, replyIntent(expected))
        assertTrue(NotificationActionDispatcher.drainOnce())
        assertEquals(1, delivered.size)
        assertEquals(expected, delivered.single().userText)
    }

    @Test
    fun coldStartKeepsActionUntilTheCurrentGraphIsReady() = runTest {
        var ready = false
        val delivered = mutableListOf<NotificationResponse>()
        NotificationActionDispatcher.install(this) { response ->
            if (!ready) false else true.also { delivered += response }
        }
        val response = response("10000000-0000-0000-0000-000000000001")

        assertEquals(EnqueueResult.DELIVERED_OR_QUEUED, NotificationActionDispatcher.enqueue(response))
        assertFalse(NotificationActionDispatcher.drainOnce())
        assertTrue(delivered.isEmpty())

        ready = true
        assertTrue(NotificationActionDispatcher.drainOnce())
        assertEquals(listOf(response), delivered)
    }

    @Test
    fun staleRadioIdentityRemainsAttachedForTheSessionHandlerGuard() = runTest {
        val response = response("90000000-0000-0000-0000-000000000009")
        var routedRadio: RadioId? = null
        NotificationActionDispatcher.install(this) {
            routedRadio = NotificationPayloadCodec.radioId(it.payload)
            true
        }

        NotificationActionDispatcher.enqueue(response)
        assertTrue(NotificationActionDispatcher.drainOnce())
        assertEquals(RadioId(UUID.fromString("90000000-0000-0000-0000-000000000009")), routedRadio)
    }

    @Test
    fun replacementWhileAnAcceptedRouterIsSuspendedDoesNotReplayItsAction() = runTest {
        val first = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        var calls = 0
        NotificationActionDispatcher.install(first) {
            calls++
            requireNotNull(currentCoroutineContext()[NotificationActionReceipt]).accepted()
            awaitCancellation()
        }
        NotificationActionDispatcher.enqueue(response("10000000-0000-0000-0000-000000000001"))
        runCurrent()
        assertEquals(1, calls)
        NotificationActionDispatcher.install(this) { calls++; true }
        runCurrent()
        assertEquals(1, calls)
        assertTrue(NotificationActionDispatcher.drainOnce())
        first.cancel()
    }

    @Test
    fun replacementRetriesAnUncommittedCancelledRouter() = runTest {
        var calls = 0
        NotificationActionDispatcher.install(this) { calls++; awaitCancellation() }
        NotificationActionDispatcher.enqueue(response("10000000-0000-0000-0000-000000000001"))
        runCurrent()
        assertEquals(1, calls)
        NotificationActionDispatcher.install(this) { calls++; true }
        runCurrent()
        assertEquals(2, calls)
        assertTrue(NotificationActionDispatcher.drainOnce())
    }

    @Test
    fun uncertainWireOutcomeIsNotAutomaticallyRetried() = runTest {
        var calls = 0
        NotificationActionDispatcher.install(this) {
            calls++
            val receipt = requireNotNull(currentCoroutineContext()[NotificationActionReceipt])
            receipt.wireStarted()
            receipt.uncertain()
            awaitCancellation()
        }
        NotificationActionDispatcher.enqueue(response("10000000-0000-0000-0000-000000000001"))
        runCurrent()
        NotificationActionDispatcher.install(this) { calls++; true }
        runCurrent()
        assertEquals(1, calls)
        assertTrue(NotificationActionDispatcher.drainOnce())
    }

    @Test
    fun committedMarkReadIsNotReplayedAfterCancelledReturn() = runTest {
        var writes = 0
        NotificationActionDispatcher.install(this) {
            writes++
            requireNotNull(currentCoroutineContext()[NotificationActionReceipt]).committed()
            awaitCancellation()
        }
        NotificationActionDispatcher.enqueue(
            response("10000000-0000-0000-0000-000000000001").copy(action = NotificationAction.MARK_READ, userText = null),
        )
        runCurrent()
        NotificationActionDispatcher.install(this) { writes++; true }
        runCurrent()
        assertEquals(1, writes)
        assertTrue(NotificationActionDispatcher.drainOnce())
    }

    @Test
    fun navigationCommitRemainsIndependentOfReplyAcceptance() = runTest {
        var calls = 0
        var receipt: NotificationActionReceipt? = null
        NotificationActionDispatcher.install(this) {
            calls++
            receipt = currentCoroutineContext()[NotificationActionReceipt]
            requireNotNull(currentCoroutineContext()[NotificationActionCommit]).navigationCommitted()
            awaitCancellation()
        }
        NotificationActionDispatcher.enqueue(
            response("10000000-0000-0000-0000-000000000001").copy(action = null, userText = null),
        )
        runCurrent()
        assertEquals(NotificationActionReceipt.State.READY, requireNotNull(receipt).state)
        NotificationActionDispatcher.install(this) { calls++; true }
        runCurrent()
        assertEquals(1, calls)
        assertTrue(NotificationActionDispatcher.drainOnce())
    }

    @Test
    fun readyRetryDuringTheFinalDelayIsNotLost() = runTest {
        var ready = false
        var delivered = 0
        NotificationActionDispatcher.install(this) {
            if (!ready) false else true.also { delivered++ }
        }
        NotificationActionDispatcher.enqueue(response("10000000-0000-0000-0000-000000000001"))
        runCurrent()
        advanceTimeBy(14_750)
        runCurrent()
        assertEquals(0, delivered)
        ready = true
        NotificationActionDispatcher.retry()
        advanceTimeBy(250)
        runCurrent()
        assertEquals(1, delivered)
        assertTrue(NotificationActionDispatcher.drainOnce())
    }

    @Test
    fun readyRetryAfterTheBoundedWindowStillDeliversTheRetainedAction() = runTest {
        var ready = false
        var delivered = 0
        NotificationActionDispatcher.install(this) {
            if (!ready) false else true.also { delivered++ }
        }
        NotificationActionDispatcher.enqueue(response("10000000-0000-0000-0000-000000000001"))
        advanceTimeBy(15_001)
        runCurrent()
        assertEquals(0, delivered)
        ready = true
        NotificationActionDispatcher.retry()
        runCurrent()
        assertEquals(1, delivered)
    }

    @Test
    fun contentTapAcceptsOnlyAValidOpenPayloadIntoTheExistingQueue() = runTest {
        val delivered = mutableListOf<NotificationResponse>()
        val response = response("10000000-0000-0000-0000-000000000001").copy(action = null, userText = null)
        val action = AndroidMessagingNotificationDelivery.ACTION_PREFIX + AndroidMessagingNotificationDelivery.ACTION_OPEN
        NotificationActionDispatcher.install(this) { delivered += it; true }
        assertEquals(NotificationContentTapResult.NOT_NOTIFICATION, NotificationContentTap.enqueue(Intent(Intent.ACTION_VIEW)))
        assertEquals(NotificationContentTapResult.MALFORMED, NotificationContentTap.enqueue(Intent(action)))
        val intent = NotificationPayloadCodec.write(Intent(action), response.payload)
        assertEquals(NotificationContentTapResult.ENQUEUED, NotificationContentTap.enqueue(intent))
        runCurrent()
        assertEquals(listOf(response), delivered)
    }

    @Test
    fun replacementScopeWaitsForCancelledNoncooperativeDeliveryWithoutReplayingIt() = runTest {
        val originalScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val replacementScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val first = response("10000000-0000-0000-0000-000000000001").copy(action = null, userText = null)
        val second = response("90000000-0000-0000-0000-000000000009").copy(action = null, userText = null)
        val originalDeliveries = mutableListOf<NotificationResponse>()
        val replacementDeliveries = mutableListOf<NotificationResponse>()
        var continuation: Continuation<Unit>? = null
        var released = false
        var active = 0
        var maxActive = 0
        try {
            NotificationActionDispatcher.install(originalScope) {
                active++
                maxActive = maxOf(maxActive, active)
                suspendCoroutine<Unit> { continuation = it }
                originalDeliveries += it
                active--
                true
            }
            NotificationActionDispatcher.enqueue(first)
            runCurrent()
            assertTrue(continuation != null)
            assertEquals(1, active)
            originalScope.cancel()
            NotificationActionDispatcher.install(replacementScope) {
                active++
                maxActive = maxOf(maxActive, active)
                replacementDeliveries += it
                active--
                true
            }
            runCurrent()
            assertTrue(replacementDeliveries.isEmpty())
            assertEquals(1, active)
            released = true
            requireNotNull(continuation).resume(Unit)
            runCurrent()
            assertEquals(listOf(first), originalDeliveries)
            assertTrue(replacementDeliveries.isEmpty())
            NotificationActionDispatcher.enqueue(second)
            runCurrent()
            assertEquals(listOf(second), replacementDeliveries)
            assertEquals(1, maxActive)
        } finally {
            if (!released) continuation?.resume(Unit)
            originalScope.cancel()
            replacementScope.cancel()
            runCurrent()
        }
    }

    @Test
    fun cancellationAfterACommitStillDrainsAnIdenticalQueuedDelivery() = runTest {
        val same = response("10000000-0000-0000-0000-000000000001")
        var calls = 0
        NotificationActionDispatcher.install(this) {
            calls++
            requireNotNull(currentCoroutineContext()[NotificationActionCommit]).navigationCommitted()
            if (calls == 1) throw kotlinx.coroutines.CancellationException("cancelled after commit")
            true
        }
        NotificationActionDispatcher.enqueue(same)
        NotificationActionDispatcher.enqueue(same)
        runCurrent()
        assertEquals(2, calls)
        NotificationActionDispatcher.retry()
        runCurrent()
        assertEquals(2, calls)
    }

    @Test
    fun receiptBookkeepingDoesNotChangeTheBoundedQueueCapacity() = runTest {
        repeat(32) {
            assertEquals(EnqueueResult.DELIVERED_OR_QUEUED,
                NotificationActionDispatcher.enqueue(response("10000000-0000-0000-0000-000000000001")))
        }
        assertEquals(EnqueueResult.REJECTED_CAPACITY,
            NotificationActionDispatcher.enqueue(response("10000000-0000-0000-0000-000000000001")))
        var calls = 0
        NotificationActionDispatcher.install(this) { calls++; true }
        runCurrent()
        assertEquals(32, calls)
    }

    private fun response(radio: String): NotificationResponse {
        val radioId = RadioId(UUID.fromString(radio))
        return NotificationResponse(
            NotificationPayload.DirectMessage(
                EntityKey(radioId, UUID.fromString("20000000-0000-0000-0000-000000000002")),
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
            ),
            NotificationAction.REPLY,
            "reply",
        )
    }
}
