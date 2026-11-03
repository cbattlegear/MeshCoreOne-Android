// AndroidOnly: WP-401 Cold-start-safe notification action receiver and bounded process dispatcher.
// AndroidOnly: WP-215 Acceptance-aware cancellation and serialized dispatcher replacement.
// AndroidOnly: WP-302 Preserve existing content-tap/navigation commitment and READY retry handshake.
package com.meshcoreone.android.platform.notifications.messaging

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val payload = NotificationPayloadCodec.read(intent) ?: return
        val action = intent.getStringExtra(EXTRA_ACTION)?.let { raw ->
            NotificationAction.entries.firstOrNull { it.rawValue == raw } ?: return
        }
        val text = if (action == NotificationAction.REPLY) {
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REMOTE_INPUT_KEY)?.toString()
                ?.trim()?.takeIf(String::isNotEmpty) ?: return
        } else {
            null
        }
        NotificationActionDispatcher.enqueue(NotificationResponse(payload, action, text))
        if (action == null) {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }?.let(context::startActivity)
        }
    }

    companion object {
        internal const val EXTRA_ACTION = "mc1.notification.action"
        internal const val REMOTE_INPUT_KEY = "mc1.notification.reply"
    }
}

enum class NotificationContentTapResult { NOT_NOTIFICATION, ENQUEUED, MALFORMED, QUEUE_FULL }

object NotificationContentTap {
    fun enqueue(intent: Intent): NotificationContentTapResult {
        if (intent.action != AndroidMessagingNotificationDelivery.ACTION_PREFIX + AndroidMessagingNotificationDelivery.ACTION_OPEN) {
            return NotificationContentTapResult.NOT_NOTIFICATION
        }
        val payload = NotificationPayloadCodec.read(intent) ?: return NotificationContentTapResult.MALFORMED
        return when (NotificationActionDispatcher.enqueue(NotificationResponse(payload, null, null))) {
            EnqueueResult.DELIVERED_OR_QUEUED -> NotificationContentTapResult.ENQUEUED
            EnqueueResult.REJECTED_CAPACITY -> NotificationContentTapResult.QUEUE_FULL
        }
    }
}

class NotificationActionCommit internal constructor() : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NotificationActionCommit>

    @Volatile private var committed = false

    fun navigationCommitted() { committed = true }
    internal fun reset() { committed = false }
    internal fun isCommitted() = committed
}

internal enum class EnqueueResult { DELIVERED_OR_QUEUED, REJECTED_CAPACITY }

internal object NotificationActionDispatcher {
    private const val MAX_PENDING = 32
    private const val RETRY_COUNT = 60
    private const val RETRY_DELAY_MILLIS = 250L

    private val logger = Logger.getLogger("com.mc1.NotificationActions")
    private val lock = Any()
    private class PendingAction(val response: NotificationResponse, val receipt: NotificationActionReceipt = NotificationActionReceipt())
    private val pending = ArrayDeque<PendingAction>()
    private val delivery = Mutex()
    private var scope: CoroutineScope? = null
    private var router: (suspend (NotificationResponse) -> Boolean)? = null
    private var drainJob: Job? = null
    private var retryRequested = false

    fun install(scope: CoroutineScope, router: suspend (NotificationResponse) -> Boolean) {
        val previous = synchronized(lock) {
            this.scope = scope
            this.router = router
            drainJob.also { drainJob = null }
        }
        previous?.cancel()
        retry()
    }

    fun enqueue(response: NotificationResponse): EnqueueResult {
        val accepted = synchronized(lock) {
            if (pending.size >= MAX_PENDING) false else {
                pending.addLast(PendingAction(response))
                true
            }
        }
        if (!accepted) {
            logger.warning("Notification action queue is full; action retained by neither app nor platform")
            return EnqueueResult.REJECTED_CAPACITY
        }
        retry()
        return EnqueueResult.DELIVERED_OR_QUEUED
    }

    fun retry() {
        val job = synchronized(lock) {
            retryRequested = true
            prepareDrainLocked()
        }
        job?.start()
    }

    private fun prepareDrainLocked(): Job? {
        val owner = scope ?: return null
        if (drainJob != null || owner.coroutineContext[Job]?.isActive == false) return null
        val job = owner.launch(context = NotificationActionCommit(), start = CoroutineStart.LAZY) {
            repeat(RETRY_COUNT) {
                synchronized(lock) { retryRequested = false }
                if (drainOnce()) return@launch
                delay(RETRY_DELAY_MILLIS)
            }
        }
        drainJob = job
        job.invokeOnCompletion { failure ->
            if (failure != null && failure !is CancellationException) {
                logger.log(Level.WARNING, "Notification action drain failed", failure)
            }
            val next = synchronized(lock) {
                if (drainJob !== job) return@invokeOnCompletion
                drainJob = null
                if (retryRequested) prepareDrainLocked() else null
            }
            next?.start()
        }
        return job
    }

    internal suspend fun drainOnce(): Boolean = delivery.withLock {
        while (true) {
            currentCoroutineContext().ensureActive()
            val next = synchronized(lock) {
                retryRequested = false
                pending.firstOrNull()
            } ?: return@withLock true
            val target = synchronized(lock) { router } ?: return@withLock false
            val navigation = currentCoroutineContext()[NotificationActionCommit]
            navigation?.reset()
            var handled = false
            try {
                if (!next.receipt.preventsReplay) {
                    handled = withContext(next.receipt) { target(next.response) }
                }
            } finally {
                // This executes even if cancellation hides a successful router result.
                if (handled || next.receipt.preventsReplay || navigation?.isCommitted() == true) {
                    if (next.receipt.state == NotificationActionReceipt.State.UNCERTAIN ||
                        next.receipt.state == NotificationActionReceipt.State.IN_FLIGHT
                    ) logger.warning("Notification reply outcome is uncertain; automatic replay suppressed")
                    removeCurrent(next)
                }
            }
            if (!handled && !next.receipt.preventsReplay && navigation?.isCommitted() != true) return@withLock false
        }
        @Suppress("UNREACHABLE_CODE")
        true
    }

    private fun removeCurrent(action: PendingAction) = synchronized(lock) {
        if (pending.firstOrNull() === action) {
            pending.removeFirst()
            if (pending.isNotEmpty()) retryRequested = true
        }
    }

    internal fun resetForTesting() {
        val previous = synchronized(lock) {
            val job = drainJob
            drainJob = null
            pending.clear()
            scope = null
            router = null
            retryRequested = false
            job
        }
        previous?.cancel()
    }
}
