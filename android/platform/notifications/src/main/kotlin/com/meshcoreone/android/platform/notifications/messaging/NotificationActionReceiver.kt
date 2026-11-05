// AndroidOnly: WP-401 Cold-start-safe notification action receiver and bounded process dispatcher.
package com.meshcoreone.android.platform.notifications.messaging

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import java.util.ArrayDeque
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    private val logger = Logger.getLogger("com.mc1.NotificationActions")
    private val lock = Any()
    private val pending = ArrayDeque<NotificationResponse>()
    private var scope: CoroutineScope? = null
    private var router: (suspend (NotificationResponse) -> Boolean)? = null
    private var drainJob: Job? = null

    fun install(scope: CoroutineScope, router: suspend (NotificationResponse) -> Boolean) {
        synchronized(lock) {
            this.scope = scope
            this.router = router
        }
        retry()
    }

    fun enqueue(response: NotificationResponse): EnqueueResult {
        val accepted = synchronized(lock) {
            if (pending.size >= MAX_PENDING) false else {
                pending.addLast(response)
                true
            }
        }
        if (!accepted) {
            logger.warning("Notification action queue is full; action was not enqueued")
            return EnqueueResult.REJECTED_CAPACITY
        }
        retry()
        return EnqueueResult.DELIVERED_OR_QUEUED
    }

    fun retry() {
        val owner = synchronized(lock) {
            if (drainJob?.isActive == true) return
            scope
        } ?: return
        val job = owner.launch {
            repeat(RETRY_COUNT) {
                if (drainOnce()) return@launch
                delay(RETRY_DELAY_MILLIS)
            }
        }
        synchronized(lock) { drainJob = job }
    }

    internal suspend fun drainOnce(): Boolean {
        while (true) {
            val next = synchronized(lock) { pending.firstOrNull() } ?: return true
            val target = synchronized(lock) { router } ?: return false
            if (!target(next)) return false
            synchronized(lock) {
                if (pending.firstOrNull() === next) pending.removeFirst()
            }
        }
    }

    internal fun resetForTesting() {
        synchronized(lock) {
            drainJob?.cancel()
            drainJob = null
            pending.clear()
            scope = null
            router = null
        }
    }
}
