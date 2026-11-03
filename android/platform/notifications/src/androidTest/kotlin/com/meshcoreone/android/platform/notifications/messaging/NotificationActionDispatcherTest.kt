// AndroidOnly: WP-401 Deterministic cold-start and stale-radio action dispatch evidence.
// AndroidOnly: WP-215 Accepted/uncertain action cancellation, replacement and capacity regressions.
package com.meshcoreone.android.platform.notifications.messaging

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
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
