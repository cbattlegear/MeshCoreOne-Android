// AndroidOnly: WP-401 Cold-start-safe notification action receiver and bounded process dispatcher.
// AndroidOnly: WP-215 Process-memory acceptance fencing and lossless reply intake.
package com.meshcoreone.android.platform.notifications.messaging

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import java.util.ArrayDeque
import java.util.logging.Logger
import java.util.logging.Level
import kotlin.coroutines.cancellation.CancellationException
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
                ?.takeIf(String::isNotBlank) ?: return
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
    private class PendingAction(
        val response: NotificationResponse,
        val receipt: NotificationActionReceipt = NotificationActionReceipt(),
    )
    private val pending = ArrayDeque<PendingAction>()
    // Not durable recovery: retain uncertain text without re-routing it or blocking the ready FIFO.
    private val unresolved = ArrayDeque<PendingAction>()
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
            if (pending.size + unresolved.size >= MAX_PENDING) false else {
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
        val job = owner.launch(start = CoroutineStart.LAZY) {
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
            var handled = false
            try {
                if (!next.receipt.preventsReplay) {
                    // Set this before withContext's cancellable return to the dispatcher.
                    withContext(next.receipt) { target(next.response).also { handled = it } }
                }
            } finally {
                if (handled || next.receipt.preventsReplay) {
                    synchronized(lock) {
                        if (pending.firstOrNull() === next) {
                            val state = next.receipt.state
                            if (state == NotificationActionReceipt.State.IN_FLIGHT ||
                                state == NotificationActionReceipt.State.UNCERTAIN
                            ) {
                                next.receipt.uncertain()
                                unresolved.addLast(next)
                                logger.warning("Notification reply outcome unknown; text retained in process only, replay suppressed")
                            }
                            pending.removeFirst()
                            if (pending.isNotEmpty()) retryRequested = true
                        }
                    }
                }
            }
            if (!handled && !next.receipt.preventsReplay) return@withLock false
        }
        @Suppress("UNREACHABLE_CODE")
        true
    }

    internal fun resetForTesting() {
        val previous = synchronized(lock) {
            val job = drainJob
            drainJob = null
            pending.clear()
            unresolved.clear()
            scope = null
            router = null
            retryRequested = false
            job
        }
        previous?.cancel()
    }
}
