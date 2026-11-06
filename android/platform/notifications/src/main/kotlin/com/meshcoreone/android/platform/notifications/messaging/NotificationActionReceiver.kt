// AndroidOnly: WP-401 Cold-start-safe notification action receiver and bounded process dispatcher.
// AndroidOnly: WP-215 Retain process-local action receipts across cancelled and replacement consumers.
package com.meshcoreone.android.platform.notifications.messaging

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationReadReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import java.util.ArrayDeque
import java.util.logging.Logger
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

internal enum class EnqueueResult { DELIVERED_OR_QUEUED, REJECTED_CAPACITY }

internal object NotificationActionDispatcher {
    private const val MAX_PENDING = 32
    private const val RETRY_COUNT = 60
    private const val RETRY_DELAY_MILLIS = 250L

    private class PendingAction(val response: NotificationResponse) {
        val receipt = NotificationActionReceipt()
        val readReceipt = NotificationReadReceipt()
        val preventsReplay: Boolean get() = receipt.preventsReplay || readReceipt.preventsReplay
    }

    private class Router(
        val scope: CoroutineScope,
        val receive: suspend (NotificationResponse) -> Boolean,
    )

    private val logger = Logger.getLogger("com.mc1.NotificationActions")
    private val lock = Any()
    private val deliveryLock = Mutex()
    private val pending = ArrayDeque<PendingAction>()
    private var router: Router? = null
    private var drainJob: Job? = null

    fun install(scope: CoroutineScope, router: suspend (NotificationResponse) -> Boolean) {
        val previous = synchronized(lock) {
            this.router = Router(scope, router)
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
        var drained = false
        val job = synchronized(lock) {
            if (drainJob?.isCompleted == false) return
            val selected = router ?: return
            selected.scope.launch(start = CoroutineStart.LAZY) {
                repeat(RETRY_COUNT) {
                    if (drainOnce(selected)) {
                        drained = true
                        return@launch
                    }
                    delay(RETRY_DELAY_MILLIS)
                }
            }.also { drainJob = it }
        }
        job.invokeOnCompletion { failure ->
            val restart = synchronized(lock) {
                if (drainJob !== job) false else {
                    drainJob = null
                    failure == null && drained && pending.isNotEmpty()
                }
            }
            // An enqueue can race an empty drain before its Job completes.
            if (restart) retry()
        }
        job.start()
    }

    internal suspend fun drainOnce(): Boolean = drainOnce(null)

    private suspend fun drainOnce(selected: Router?): Boolean = deliveryLock.withLock {
        while (true) {
            currentCoroutineContext().ensureActive()
            val (next, target) = synchronized(lock) {
                val target = router ?: return@withLock false
                if (selected != null && target !== selected) return@withLock false
                val next = pending.firstOrNull() ?: return@withLock true
                next to target
            }
            if (next.preventsReplay) {
                removeSettled(next)
                continue
            }
            var handled = false
            try {
                handled = withContext(next.receipt + next.readReceipt) { target.receive(next.response) }
            } finally {
                // A cancelled or false-returning consumer cannot replay an observed platform/local effect.
                if (handled || next.preventsReplay) removeSettled(next)
            }
            if (!handled) return@withLock false
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }

    private fun removeSettled(action: PendingAction) {
        val removed = synchronized(lock) {
            if (pending.firstOrNull() !== action) false else {
                pending.removeFirst()
                true
            }
        }
        if (removed && (
                action.receipt.state == NotificationActionReceipt.State.IN_FLIGHT ||
                    action.receipt.state == NotificationActionReceipt.State.UNCERTAIN ||
                    action.readReceipt.state == NotificationReadReceipt.State.STARTED ||
                    action.readReceipt.state == NotificationReadReceipt.State.UNCERTAIN
                )
        ) {
            logger.warning("Notification action outcome is uncertain; automatic replay suppressed (process-memory only)")
        }
    }

    internal fun resetForTesting() {
        val previous = synchronized(lock) {
            pending.clear()
            router = null
            drainJob.also { drainJob = null }
        }
        previous?.cancel()
    }
}
