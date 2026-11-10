// AndroidOnly: WP-401 Deterministic cold-start and stale-radio action dispatch evidence.
package com.meshcoreone.android.platform.notifications.messaging

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
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
    fun contentTapAcceptsOnlyAValidOpenPayloadIntoTheExistingQueue() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val delivered = mutableListOf<NotificationResponse>()
        val response = response("10000000-0000-0000-0000-000000000001").copy(action = null, userText = null)
        val action = AndroidMessagingNotificationDelivery.ACTION_PREFIX + AndroidMessagingNotificationDelivery.ACTION_OPEN
        try {
            NotificationActionDispatcher.install(scope) { delivered += it; true }
            assertEquals(NotificationContentTapResult.NOT_NOTIFICATION, NotificationContentTap.enqueue(Intent(Intent.ACTION_VIEW)))
            assertEquals(NotificationContentTapResult.MALFORMED, NotificationContentTap.enqueue(Intent(action)))
            val intent = NotificationPayloadCodec.write(Intent(action), response.payload)
            assertEquals(NotificationContentTapResult.ENQUEUED, NotificationContentTap.enqueue(intent))
            testScheduler.runCurrent()
            assertEquals(listOf(response), delivered)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun retryDuringTheFinalDelayIsLatchedUntilTheActiveDrainFinishes() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var ready = false
        var attempts = 0
        val delivered = mutableListOf<NotificationResponse>()
        val response = response("10000000-0000-0000-0000-000000000001")
        try {
            NotificationActionDispatcher.install(scope) { response ->
                attempts++
                if (!ready) false else true.also { delivered += response }
            }
            NotificationActionDispatcher.enqueue(response)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(14_750)
            testScheduler.runCurrent()
            assertEquals(60, attempts)
            assertTrue(delivered.isEmpty())

            ready = true
            NotificationActionDispatcher.retry()
            testScheduler.advanceTimeBy(250)
            testScheduler.runCurrent()
            assertEquals(61, attempts)
            assertEquals(listOf(response), delivered)

            NotificationActionDispatcher.retry()
            testScheduler.runCurrent()
            assertEquals(61, attempts)
            assertEquals(listOf(response), delivered)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun replacementScopeWaitsForCancelledNoncooperativeDeliveryWithoutReplayingIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val originalScope = CoroutineScope(SupervisorJob() + dispatcher)
        val replacementScope = CoroutineScope(SupervisorJob() + dispatcher)
        val first = response("10000000-0000-0000-0000-000000000001")
        val second = response("90000000-0000-0000-0000-000000000009")
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
                suspendCoroutine<Unit> { suspended -> continuation = suspended }
                originalDeliveries += it
                active--
                true
            }
            NotificationActionDispatcher.enqueue(first)
            testScheduler.runCurrent()
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
            testScheduler.runCurrent()
            assertTrue(replacementDeliveries.isEmpty())
            assertEquals(1, active)

            released = true
            continuation!!.resume(Unit)
            testScheduler.runCurrent()
            assertEquals(listOf(first), originalDeliveries)
            assertTrue(replacementDeliveries.isEmpty())

            NotificationActionDispatcher.enqueue(second)
            testScheduler.runCurrent()
            assertEquals(listOf(second), replacementDeliveries)
            assertEquals(1, maxActive)
        } finally {
            if (!released) continuation?.resume(Unit)
            originalScope.cancel()
            replacementScope.cancel()
            testScheduler.runCurrent()
        }
    }

    @Test
    fun cancellationAfterACommitStillDrainsAnIdenticalQueuedDelivery() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val same = response("10000000-0000-0000-0000-000000000001")
        var calls = 0
        try {
            NotificationActionDispatcher.install(scope) {
                calls++
                checkNotNull(currentCoroutineContext()[NotificationActionCommit]).navigationCommitted()
                if (calls == 1) throw kotlinx.coroutines.CancellationException("cancelled after commit")
                true
            }
            NotificationActionDispatcher.enqueue(same)
            NotificationActionDispatcher.enqueue(same)
            testScheduler.runCurrent()
            assertEquals(2, calls)

            NotificationActionDispatcher.retry()
            testScheduler.runCurrent()
            assertEquals(2, calls)
        } finally {
            scope.cancel()
        }
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
