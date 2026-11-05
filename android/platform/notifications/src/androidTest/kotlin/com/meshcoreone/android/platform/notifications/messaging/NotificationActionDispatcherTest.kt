// AndroidOnly: WP-401 Deterministic cold-start and stale-radio action dispatch evidence.
// AndroidOnly: WP-215 Receipt cancellation, queue replacement and exact-text controls.
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
import com.meshcoreone.android.core.contracts.notifications.NotificationReadReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
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
    fun uncertainWireOutcomeDoesNotReplayOrBlockTheNextReadyAction() = runTest {
        var calls = 0
        NotificationActionDispatcher.install(this) {
            calls++
            requireNotNull(currentCoroutineContext()[NotificationActionReceipt]).wireStarted()
            awaitCancellation()
        }
        val response = response("10000000-0000-0000-0000-000000000001")
        NotificationActionDispatcher.enqueue(response)
        runCurrent()
        NotificationActionDispatcher.enqueue(response)
        NotificationActionDispatcher.install(this) { calls++; true }
        runCurrent()
        assertEquals(2, calls)
        assertTrue(NotificationActionDispatcher.drainOnce())
    }

    @Test
    fun uncertainTextStillOccupiesCapacityRatherThanBeingSilentlyDiscarded() = runTest {
        var calls = 0
        NotificationActionDispatcher.install(this) {
            calls++
            requireNotNull(currentCoroutineContext()[NotificationActionReceipt]).wireStarted()
            awaitCancellation()
        }
        val response = response("10000000-0000-0000-0000-000000000001")
        NotificationActionDispatcher.enqueue(response)
        runCurrent()
        NotificationActionDispatcher.install(this) { calls++; true }
        runCurrent()
        repeat(31) {
            assertEquals(EnqueueResult.DELIVERED_OR_QUEUED, NotificationActionDispatcher.enqueue(response))
        }
        assertEquals(EnqueueResult.REJECTED_CAPACITY, NotificationActionDispatcher.enqueue(response))
        runCurrent()
        assertEquals(32, calls)
    }

    @Test
    fun receiptBookkeepingDoesNotChangeTheBoundedQueueCapacity() = runTest {
        val response = response("10000000-0000-0000-0000-000000000001")
        repeat(32) {
            assertEquals(EnqueueResult.DELIVERED_OR_QUEUED, NotificationActionDispatcher.enqueue(response))
        }

        assertEquals(EnqueueResult.REJECTED_CAPACITY, NotificationActionDispatcher.enqueue(response))
        var calls = 0
        NotificationActionDispatcher.install(this) { calls++; true }
        runCurrent()
        assertEquals(32, calls)
    }

    @Test
    fun routerTrueCannotDiscardAnUncertainReadOrTurnItIntoAutomaticRetry() = runTest {
        var calls = 0
        val read = response("10000000-0000-0000-0000-000000000001")
            .copy(action = NotificationAction.MARK_READ, userText = null)
        NotificationActionDispatcher.install(this) {
            calls++
            val progress = requireNotNull(currentCoroutineContext()[NotificationReadReceipt])
            progress.started()
            progress.writeReturned()
            progress.uncertain()
            true // The production response containment also returns normally after a store failure.
        }
        NotificationActionDispatcher.enqueue(read)
        runCurrent()
        assertEquals(1, calls)
        NotificationActionDispatcher.install(this) { calls++; true }
        runCurrent()
        assertEquals(1, calls)
        repeat(31) {
            assertEquals(EnqueueResult.DELIVERED_OR_QUEUED, NotificationActionDispatcher.enqueue(read))
        }
        assertEquals(EnqueueResult.REJECTED_CAPACITY, NotificationActionDispatcher.enqueue(read))
        runCurrent()
        assertEquals("The uncertain read remains retained while later ready actions can progress", 32, calls)
    }

    @Test
    fun handledResultIsRecordedBeforeCancellationAtContextHandoff() = runTest {
        var calls = 0
        NotificationActionDispatcher.install(this) {
            calls++
            currentCoroutineContext().cancel()
            true
        }
        NotificationActionDispatcher.enqueue(response("10000000-0000-0000-0000-000000000001"))
        runCurrent()
        NotificationActionDispatcher.install(this) { calls++; true }
        runCurrent()
        assertEquals(1, calls)
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
