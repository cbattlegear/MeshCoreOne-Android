// AndroidOnly: WP-302 Notification drain ownership across router replacement.
// AndroidOnly: WP-215 Per-action navigation, send and read cancellation boundaries.
package com.meshcoreone.android.platform.notifications.messaging

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationReadReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationActionDispatcherUnitTest {
    @Before
    fun setUp() {
        NotificationActionDispatcher.resetForTesting()
    }

    @After
    fun tearDown() {
        NotificationActionDispatcher.resetForTesting()
    }

    @Test
    fun replacementInstalledBeforeCancellationRetriesAnInterruptedDelivery() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val originalScope = CoroutineScope(SupervisorJob() + dispatcher)
        val replacementScope = CoroutineScope(SupervisorJob() + dispatcher)
        val response = response()
        val delivered = mutableListOf<NotificationResponse>()
        var oldAttempts = 0
        var replacementAttempts = 0
        try {
            NotificationActionDispatcher.install(originalScope) {
                oldAttempts++
                false
            }
            NotificationActionDispatcher.enqueue(response)
            testScheduler.runCurrent()
            assertEquals(1, oldAttempts)

            NotificationActionDispatcher.install(replacementScope) {
                replacementAttempts++
                if (replacementAttempts == 1) awaitCancellation()
                delivered += it
                true
            }
            testScheduler.advanceTimeBy(250)
            testScheduler.runCurrent()
            assertEquals(1, replacementAttempts)
            assertTrue(delivered.isEmpty())

            originalScope.cancel()
            testScheduler.runCurrent()
            assertEquals(2, replacementAttempts)
            assertEquals(listOf(response), delivered)

            NotificationActionDispatcher.retry()
            testScheduler.runCurrent()
            assertEquals(2, replacementAttempts)
            assertEquals(listOf(response), delivered)
        } finally {
            originalScope.cancel()
            replacementScope.cancel()
            testScheduler.runCurrent()
        }
    }

    @Test
    fun routerReplacementInTheSameScopeRetainsItsRetry() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val response = response()
        val delivered = mutableListOf<NotificationResponse>()
        var replacementAttempts = 0
        try {
            NotificationActionDispatcher.install(scope) { false }
            NotificationActionDispatcher.enqueue(response)
            testScheduler.runCurrent()

            NotificationActionDispatcher.install(scope) {
                replacementAttempts++
                if (replacementAttempts == 1) throw CancellationException("superseded router")
                delivered += it
                true
            }
            testScheduler.advanceTimeBy(250)
            testScheduler.runCurrent()
            assertEquals(2, replacementAttempts)
            assertEquals(listOf(response), delivered)
        } finally {
            scope.cancel()
            testScheduler.runCurrent()
        }
    }

    @Test
    fun replacementDoesNotReplayAnAlreadyCommittedNoncooperativeDelivery() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val originalScope = CoroutineScope(SupervisorJob() + dispatcher)
        val replacementScope = CoroutineScope(SupervisorJob() + dispatcher)
        val response = response()
        val delivered = mutableListOf<NotificationResponse>()
        var replacementAttempts = 0
        var continuation: Continuation<Unit>? = null
        try {
            NotificationActionDispatcher.install(originalScope) { false }
            NotificationActionDispatcher.enqueue(response)
            testScheduler.runCurrent()

            NotificationActionDispatcher.install(replacementScope) {
                replacementAttempts++
                checkNotNull(currentCoroutineContext()[NotificationActionCommit]).navigationCommitted()
                suspendCoroutine<Unit> { continuation = it }
                delivered += it
                true
            }
            testScheduler.advanceTimeBy(250)
            testScheduler.runCurrent()
            assertEquals(1, replacementAttempts)
            assertTrue(continuation != null)

            originalScope.cancel()
            testScheduler.runCurrent()
            assertEquals(1, replacementAttempts)
            assertTrue(delivered.isEmpty())

            continuation!!.resume(Unit)
            continuation = null
            testScheduler.runCurrent()
            assertEquals(1, replacementAttempts)
            assertEquals(listOf(response), delivered)
        } finally {
            continuation?.resume(Unit)
            originalScope.cancel()
            replacementScope.cancel()
            testScheduler.runCurrent()
        }
    }

    @Test
    fun navigationReplyAndReadCommitmentsStayBoundToTheirOwnQueuedActions() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val reply = response()
        val open = reply.copy(action = null, userText = null)
        val read = reply.copy(action = NotificationAction.MARK_READ, userText = null)
        val attempts = mutableListOf<NotificationResponse>()
        try {
            NotificationActionDispatcher.install(scope) {
                attempts += it
                val context = currentCoroutineContext()
                when (attempts.size) {
                    1 -> checkNotNull(context[NotificationActionCommit]).navigationCommitted()
                    2 -> checkNotNull(context[NotificationActionReceipt]).accepted()
                    3 -> checkNotNull(context[NotificationReadReceipt]).apply {
                        started()
                        writeReturned()
                        committed()
                    }
                    4 -> return@install false
                    else -> return@install true
                }
                context.cancel()
                true
            }
            listOf(open, reply, read, open).forEach {
                assertEquals(EnqueueResult.DELIVERED_OR_QUEUED, NotificationActionDispatcher.enqueue(it))
            }
            testScheduler.runCurrent()
            assertEquals(listOf(open, reply, read, open), attempts)

            testScheduler.advanceTimeBy(250)
            testScheduler.runCurrent()
            assertEquals(listOf(open, reply, read, open, open), attempts)
            assertTrue(NotificationActionDispatcher.drainOnce())

            NotificationActionDispatcher.retry()
            testScheduler.runCurrent()
            assertEquals(5, attempts.size)
        } finally {
            scope.cancel()
            testScheduler.runCurrent()
        }
    }

    private fun response(): NotificationResponse {
        val radioId = RadioId(UUID(0, 1))
        return NotificationResponse(
            NotificationPayload.DirectMessage(EntityKey(radioId, UUID(0, 2)), UUID(0, 3)),
            NotificationAction.REPLY,
            "reply",
        )
    }
}
